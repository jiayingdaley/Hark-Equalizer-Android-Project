package com.wcy.hark.audiometry

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import java.io.DataInputStream
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * SsnAudioMixer — sample-accurate speech + speech-shaped-noise mixing.
 *
 * Decodes the word WAV and the pre-generated SSN (res/raw/ssn_noise.wav,
 * offline-shaped to the corpus long-term average spectrum), scales the noise
 * segment so that SNR = 20·log10(rms_speech / rms_noise), then plays the mix
 * through a single AudioTrack. Noise leads/trails the word by [padMs].
 */
class SsnAudioMixer(private val context: Context) {

    companion object {
        private const val TAG = "SsnAudioMixer"
        const val SAMPLE_RATE = 16000
        private const val PAD_MS = 500
    }

    private var noisePcm: ShortArray? = null
    private var audioTrack: AudioTrack? = null

    /** Parses a mono 16-bit PCM WAV from res/raw. */
    private fun readWavResource(resId: Int): ShortArray {
        context.resources.openRawResource(resId).use { ins ->
            val dis = DataInputStream(ins)
            val header = ByteArray(12)
            dis.readFully(header) // RIFF....WAVE
            // Walk chunks until "data"
            while (true) {
                val chunkId = ByteArray(4); dis.readFully(chunkId)
                val sizeBytes = ByteArray(4); dis.readFully(sizeBytes)
                val size = (sizeBytes[0].toInt() and 0xFF) or
                           ((sizeBytes[1].toInt() and 0xFF) shl 8) or
                           ((sizeBytes[2].toInt() and 0xFF) shl 16) or
                           ((sizeBytes[3].toInt() and 0xFF) shl 24)
                if (String(chunkId) == "data") {
                    val data = ByteArray(size)
                    dis.readFully(data)
                    val out = ShortArray(size / 2)
                    for (i in out.indices) {
                        out[i] = (((data[i * 2 + 1].toInt() shl 8) or
                                   (data[i * 2].toInt() and 0xFF))).toShort()
                    }
                    return out
                } else {
                    dis.skipBytes(size)
                }
            }
        }
    }

    private fun ensureNoiseLoaded(noiseResId: Int): ShortArray {
        return noisePcm ?: readWavResource(noiseResId).also { noisePcm = it }
    }

    private fun rms(x: ShortArray, from: Int = 0, len: Int = x.size): Double {
        var sum = 0.0
        for (i in from until from + len) sum += x[i].toDouble() * x[i]
        return sqrt(sum / len) / 32768.0
    }

    /**
     * Mixes and plays one word at the given SNR (dB).
     * @return total playback duration in ms, or -1 on failure.
     */
    fun playWordInNoise(wordResId: Int, noiseResId: Int, snrDb: Float): Long {
        return try {
            val speech = readWavResource(wordResId)
            val noise = ensureNoiseLoaded(noiseResId)

            val padSamples = SAMPLE_RATE * PAD_MS / 1000
            val totalLen = speech.size + 2 * padSamples

            // Random noise segment
            val start = Random.nextInt(0, (noise.size - totalLen).coerceAtLeast(1))

            val speechRms = rms(speech).coerceAtLeast(1e-6)
            val noiseRms = rms(noise, start, totalLen).coerceAtLeast(1e-6)
            // Scale noise so speechRms / (noiseRms*g) = 10^(snr/20)
            val noiseGain = (speechRms / (noiseRms * 10.0.pow(snrDb / 20.0))).toFloat()

            val mix = ShortArray(totalLen)
            var peak = 0
            for (i in 0 until totalLen) {
                val s = if (i in padSamples until padSamples + speech.size)
                    speech[i - padSamples].toInt() else 0
                val n = (noise[start + i] * noiseGain).toInt()
                var v = s + n
                if (v > peak) peak = v
                if (-v > peak) peak = -v
                mix[i] = v.coerceIn(-32768, 32767).toShort()
            }
            // Soft normalization if the sum clips
            if (peak > 32767) {
                val g = 32000f / peak
                for (i in mix.indices) mix[i] = (mix[i] * g).toInt().toShort()
                Log.w(TAG, "Mix clipped (peak=$peak); normalized by ${20 * log10(g.toDouble())} dB")
            }

            stop()
            audioTrack = AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
                mix.size * 2,
                AudioTrack.MODE_STATIC,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )
            audioTrack?.write(mix, 0, mix.size)
            audioTrack?.play()

            totalLen * 1000L / SAMPLE_RATE
        } catch (e: Exception) {
            Log.e(TAG, "playWordInNoise failed: ${e.message}", e)
            -1L
        }
    }

    val audioSessionId: Int get() = audioTrack?.audioSessionId ?: 0

    fun stop() {
        try {
            audioTrack?.let {
                if (it.playState == AudioTrack.PLAYSTATE_PLAYING) it.stop()
                it.release()
            }
        } catch (e: Exception) { /* best effort */ }
        audioTrack = null
    }

    fun release() {
        stop()
        noisePcm = null
    }
}
