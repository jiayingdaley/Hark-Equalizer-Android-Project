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
 * offline-shaped to the corpus long-term average spectrum). The noise keeps its
 * original level (anchored to the user's comfortable volume) and the SPEECH is
 * scaled so that SNR = 20·log10(rms_speech / rms_noise) — negative SNR makes
 * the word quieter, never the noise louder. Mix plays through a single
 * AudioTrack; noise leads/trails the word by PAD_MS.
 */
class SsnAudioMixer(private val context: Context) {

    companion object {
        private const val TAG = "SsnAudioMixer"
        const val SAMPLE_RATE = 16000
        private const val PAD_MS = 500

        /**
         * 固定呈現餘裕（presentation headroom），對「所有」trial 一律套用，
         * 因此各 trial 的絕對呈現級別彼此一致、SNR 不受影響，且大幅降低
         * 混音削波（進而觸發逐題正規化）的機率。
         */
        const val PRESENTATION_GAIN_DB = -6.0f
    }

    /**
     * @param durationMs 播放總長（毫秒）；-1 表示失敗
     * @param normGainDb 削波防護的額外正規化增益（dB，≤0）；0 表示未觸發。
     *                   此值必須寫入測試紀錄——它代表該 trial 的絕對呈現級別
     *                   相對其他 trial 額外降低了多少。
     */
    data class MixResult(val durationMs: Long, val normGainDb: Float)

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
     */
    fun playWordInNoise(wordResId: Int, noiseResId: Int, snrDb: Float): MixResult {
        return try {
            val speech = readWavResource(wordResId)
            val noise = ensureNoiseLoaded(noiseResId)

            val padSamples = SAMPLE_RATE * PAD_MS / 1000
            val totalLen = speech.size + 2 * padSamples

            // Random noise segment
            val start = Random.nextInt(0, (noise.size - totalLen).coerceAtLeast(1))

            val speechRms = rms(speech).coerceAtLeast(1e-6)
            val noiseRms = rms(noise, start, totalLen).coerceAtLeast(1e-6)
            // 噪音固定在舒適音量（維持原始位準），依 SNR 調整「語音」相對大小：
            //   speechRms_out / noiseRms_out = 10^(snr/20)
            // 如此負 SNR 只是語音變更小、更難聽，噪音不會超過使用者設定的舒適音量。
            val speechGain = (noiseRms * 10.0.pow(snrDb / 20.0) / speechRms).toFloat()

            // 固定呈現餘裕（全 trial 一致，不影響 SNR 也不影響 trial 間可比性）
            val presentGain = 10.0.pow(PRESENTATION_GAIN_DB / 20.0).toFloat()

            // 先以浮點混音求峰值，再一次性量化，避免「先削波再縮小」造成失真
            val mixF = FloatArray(totalLen)
            var peak = 0f
            for (i in 0 until totalLen) {
                val s = if (i in padSamples until padSamples + speech.size)
                    speech[i - padSamples].toFloat() else 0f
                val v = (s * speechGain + noise[start + i]) * presentGain
                mixF[i] = v
                val a = if (v >= 0f) v else -v
                if (a > peak) peak = a
            }
            // 削波防護：仍超出 16-bit 範圍時整段等比例縮小，並回報衰減量
            var normGainDb = 0f
            if (peak > 32767f) {
                val g = 32000f / peak
                for (i in mixF.indices) mixF[i] *= g
                normGainDb = (20.0 * log10(g.toDouble())).toFloat()
                Log.w(TAG, "Mix clipped (peak=$peak); normalized by $normGainDb dB — recorded in trial data")
            }
            val mix = ShortArray(totalLen)
            for (i in 0 until totalLen) {
                mix[i] = mixF[i].toInt().coerceIn(-32768, 32767).toShort()
            }

            playPcm(mix)
            MixResult(totalLen * 1000L / SAMPLE_RATE, normGainDb)
        } catch (e: Exception) {
            Log.e(TAG, "playWordInNoise failed: ${e.message}", e)
            MixResult(-1L, 0f)
        }
    }

    /**
     * 無噪音的「小聲語詞」測驗：將語詞呈現於指定的絕對數位位準 levelDbfs（dBFS，
     * 相對滿刻度）。levelDbfs 由施測端以「該受試者純音閾值 + 感覺級（dB SL）」換算
     * 而得，故位準綁定個人聽力（見 SSNTestActivity）。無噪音、無 SNR 混音。
     */
    fun playWordQuiet(wordResId: Int, levelDbfs: Float): MixResult {
        return try {
            val speech = readWavResource(wordResId)
            val padSamples = SAMPLE_RATE * PAD_MS / 1000
            val totalLen = speech.size + 2 * padSamples

            val speechRms = rms(speech).coerceAtLeast(1e-6)          // 正規化 (0..1)
            val targetRms = 10.0.pow(levelDbfs / 20.0)               // 目標正規化 RMS
            val gain = (targetRms / speechRms).toFloat()

            val mixF = FloatArray(totalLen)
            var peak = 0f
            for (i in 0 until totalLen) {
                val s = if (i in padSamples until padSamples + speech.size)
                    speech[i - padSamples].toFloat() * gain else 0f
                mixF[i] = s
                val a = if (s >= 0f) s else -s
                if (a > peak) peak = a
            }
            var normGainDb = 0f
            if (peak > 32767f) {
                val g = 32000f / peak
                for (i in mixF.indices) mixF[i] *= g
                normGainDb = (20.0 * log10(g.toDouble())).toFloat()
            }
            val mix = ShortArray(totalLen) { mixF[it].toInt().coerceIn(-32768, 32767).toShort() }
            playPcm(mix)
            MixResult(totalLen * 1000L / SAMPLE_RATE, normGainDb)
        } catch (e: Exception) {
            Log.e(TAG, "playWordQuiet failed: ${e.message}", e)
            MixResult(-1L, 0f)
        }
    }

    private fun playPcm(mix: ShortArray) {
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
