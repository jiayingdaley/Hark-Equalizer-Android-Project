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
     * @param pulsed       true = 300 ms on / 200 ms off pulses with 10 ms ramps
     * @param durationSec  buffer duration in seconds
     * @param loop         true = loop indefinitely until stop() (calibration tone)
     */
    fun play(
        frequencyHz: Int,
        dbfs: Float,
        ear: Ear,
        pulsed: Boolean = false,
        durationSec: Float = 1.5f,
        loop: Boolean = false
    ) {
        val numSamples = (durationSec * sampleRate).toInt()
        val volume = 10.0.pow(dbfs / 20.0).toFloat().coerceIn(0.0f, 1.0f)

        val tone = ShortArray(numSamples)
        for (i in 0 until numSamples) {
            val time = i.toFloat() / sampleRate
            val fade: Float
            if (pulsed) {
                // Pulsed tone (300ms ON, 200ms OFF -> period 500ms)
                val tMs = time * 1000.0f
                val tMod = tMs % 500.0f
                fade = when {
                    tMod < 10.0f -> tMod / 10.0f            // 10ms fade-in
                    tMod < 290.0f -> 1.0f                   // on
                    tMod < 300.0f -> (300.0f - tMod) / 10.0f // 10ms fade-out
                    else -> 0.0f                            // off
                }
            } else {
                fade = 1.0f
            }
            tone[i] = (Short.MAX_VALUE * volume * fade * sin(2 * PI * frequencyHz * time)).toInt().toShort()
        }

        // Local per-channel copies keep the silent channel truly silent
        // (avoids residual DC transient "thud" between back-to-back calls).
        val silent = ShortArray(numSamples)
        val leftData = if (ear == Ear.LEFT || ear == Ear.BOTH) tone else silent
        val rightData = if (ear == Ear.RIGHT || ear == Ear.BOTH) tone else silent

        val stereoBuffer = ShortArray(numSamples * 2)
        for (i in 0 until numSamples) {
            stereoBuffer[i * 2] = leftData[i]
            stereoBuffer[i * 2 + 1] = rightData[i]
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
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
                numSamples * 4,
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

        audioTrack?.write(stereoBuffer, 0, stereoBuffer.size)
        audioTrack?.setLoopPoints(0, numSamples, if (loop) -1 else 0)
        audioTrack?.play()
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
