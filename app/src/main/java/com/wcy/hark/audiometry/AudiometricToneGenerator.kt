package com.wcy.hark.audiometry

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/**
 * AudiometricToneGenerator
 *
 * Shared pure-tone source for the pure-tone hearing test, the per-frequency
 * earphone calibration screen, and (future) EQ beep previews. Plays through
 * AudioTrack on STREAM_MUSIC — the calibration table is only valid if the
 * calibration tone travels the exact same output path as the test tones.
 *
 * The AudioTrack instance is reused between play() calls to prevent codec
 * power-up/power-down click transients (abrupt capacitor charging).
 */
class AudiometricToneGenerator(private val sampleRate: Int = 44100) {

    enum class Ear { LEFT, RIGHT, BOTH }

    private var audioTrack: AudioTrack? = null
    private var trackNumSamples = 0

    /**
     * Synthesizes and plays a tone.
     *
     * @param frequencyHz  tone frequency in Hz
     * @param dbfs         digital level in dBFS (0 = full scale); amplitude = 10^(dbfs/20)
     * @param ear          output channel(s)
     * @param pulsed       true = pulseOnMs on / pulseOffMs off pulses with 10 ms ramps
     * @param durationSec  buffer duration in seconds
     * @param loop         true = loop indefinitely until stop() (calibration tone)
     * @param pulseOnMs    pulse ON time in ms (audiometric standard 300)
     * @param pulseOffMs   pulse OFF time in ms (audiometric standard 200)
     * @param bakeVolume   true = amplitude baked into samples (test tones);
     *                     false = samples at full scale, level applied via
     *                     AudioTrack.setVolume() so setVolumeDbfs() can change
     *                     it later WITHOUT restarting the waveform (no clicks)
     */
    fun play(
        frequencyHz: Int,
        dbfs: Float,
        ear: Ear,
        pulsed: Boolean = false,
        durationSec: Float = 1.5f,
        loop: Boolean = false,
        pulseOnMs: Float = 300.0f,
        pulseOffMs: Float = 200.0f,
        bakeVolume: Boolean = true
    ) {
        val numSamples = (durationSec * sampleRate).toInt()
        val volume = 10.0.pow(dbfs / 20.0).toFloat().coerceIn(0.0f, 1.0f)
        val bakedAmp = if (bakeVolume) volume else 1.0f

        val periodMs = pulseOnMs + pulseOffMs
        // Float PCM：16-bit 的量化底線約 −90.3 dBFS（更低即全零靜音），會讓正常
        // 聽力者的閾值區（約 −100 ~ −85 dBFS）無法呈現；浮點路徑以 ≥24-bit 精度
        // 送入 DAC，實際下限由硬體底噪決定。
        val tone = FloatArray(numSamples)
        for (i in 0 until numSamples) {
            val time = i.toFloat() / sampleRate
            val fade: Float
            if (pulsed) {
                val tMod = (time * 1000.0f) % periodMs
                fade = when {
                    tMod < 10.0f -> tMod / 10.0f                          // 10ms fade-in
                    tMod < pulseOnMs - 10.0f -> 1.0f                      // on
                    tMod < pulseOnMs -> (pulseOnMs - tMod) / 10.0f        // 10ms fade-out
                    else -> 0.0f                                          // off
                }
            } else {
                fade = 1.0f
            }
            tone[i] = (bakedAmp * fade * sin(2 * PI * frequencyHz * time)).toFloat()
        }

        val stereoBuffer = FloatArray(numSamples * 2)
        val leftOn = ear == Ear.LEFT || ear == Ear.BOTH
        val rightOn = ear == Ear.RIGHT || ear == Ear.BOTH
        for (i in 0 until numSamples) {
            stereoBuffer[i * 2] = if (leftOn) tone[i] else 0.0f
            stereoBuffer[i * 2 + 1] = if (rightOn) tone[i] else 0.0f
        }

        // MODE_STATIC buffer size is fixed at construction; recreate only when it changes.
        if (audioTrack == null || trackNumSamples != numSamples) {
            audioTrack?.release()
            audioTrack = AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
                numSamples * 8,
                AudioTrack.MODE_STATIC,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )
            trackNumSamples = numSamples
        }

        try {
            if (audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) {
                audioTrack?.stop()
            }
            audioTrack?.flush()
            audioTrack?.setPlaybackHeadPosition(0)
        } catch (e: Exception) {
            Log.w("AudiometricToneGen", "AudioTrack reset skipped: ${e.message}")
        }

        audioTrack?.write(stereoBuffer, 0, stereoBuffer.size, AudioTrack.WRITE_BLOCKING)
        audioTrack?.setLoopPoints(0, numSamples, if (loop) -1 else 0)
        audioTrack?.setVolume(if (bakeVolume) 1.0f else volume)
        audioTrack?.play()
    }

    /**
     * Changes the playback level of the currently looping tone without
     * touching the waveform — no stop/flush, hence no click transients.
     * Only meaningful after play(..., bakeVolume = false).
     */
    fun setVolumeDbfs(dbfs: Float) {
        val v = 10.0.pow(dbfs / 20.0).toFloat().coerceIn(0.0f, 1.0f)
        try { audioTrack?.setVolume(v) } catch (e: Exception) { /* no-op */ }
    }

    fun pause() {
        try { audioTrack?.pause() } catch (e: Exception) { /* no-op */ }
    }

    fun resume() {
        try { audioTrack?.play() } catch (e: Exception) { /* no-op */ }
    }

    fun stop() {
        try {
            if (audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) audioTrack?.stop()
        } catch (e: Exception) { /* no-op */ }
    }

    fun release() {
        audioTrack?.release()
        audioTrack = null
        trackNumSamples = 0
    }
}
