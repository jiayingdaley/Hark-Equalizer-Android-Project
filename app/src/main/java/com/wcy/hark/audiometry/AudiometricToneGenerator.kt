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
 * 純音音源，供純音聽力測驗、耳機逐頻率校正、EQ 逼逼聲預覽共用。一律走
 * AudioTrack / STREAM_MUSIC —— 校正表只有在「校正音與測驗音走完全相同的輸出
 * 路徑」時才成立。
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * ★ 位準一律烘進取樣值，絕不可用 AudioTrack.setVolume() 當衰減器 ★
 * ─────────────────────────────────────────────────────────────────────────────
 * AudioTrack.setVolume() 傳到 AudioFlinger 之後是「定點數」：混音路徑以
 * UNITY_GAIN_INT = 0x1000（4096）表示增益，最小非零階為 1/4096 ≈ −72.2 dBFS。
 *
 * 這對聽力測驗是致命的。聽力正常者的純音聽閾落在約 −90 ~ −75 dBFS，整個閾值區
 * 都在這條地板以下：
 *
 *     設定 −45 dBFS → 5.6e−3 → 23/4096      尚可
 *     設定 −72 dBFS → 2.5e−4 →  1/4096      最後一階
 *     設定 −80 dBFS → 1.0e−4 →  0.41 階     ← 表達不出來
 *     設定 −99 dBFS → 1.1e−5 →  0.046 階    ← 表達不出來
 *
 * 實際症狀：音量在 −72 dBFS 附近觸底，滑桿再往下拉聲音也不再變小（實測回報
 * 「調到 −99 dBFS 還算大聲」）。量到的「聽閾」其實是被這條地板截斷的假值，
 * 而聽閾正是後續所有 dB SL 位準的零點——整條位準鏈會靜默地錯掉。
 *
 * 解法：把振幅直接乘進 float 取樣值。Float PCM 有 24 bit 尾數精度，1.1e−5 的
 * 振幅精確可表示，衰減完全由數位取樣值決定，與裝置混音器的實作無關。
 *
 * 那原本為什麼要用 setVolume()？為了在不重建波形的情況下改音量、避免爆音。
 * 但測驗音本來就是脈衝式的（300 ms 開 / 200 ms 關、含 10 ms 淡入淡出），
 * 重建緩衝區並從頭播放時起手就是淡入——天然無爆音。
 */
class AudiometricToneGenerator(private val sampleRate: Int = 44100) {

    enum class Ear { LEFT, RIGHT, BOTH }

    private var audioTrack: AudioTrack? = null
    private var trackNumSamples = 0

    // 目前這顆音的參數，供 setLevelDbfs() 以新振幅重建
    private var curFreqHz = 1000
    private var curEar = Ear.BOTH
    private var curPulsed = true
    private var curDurationSec = 1.0f
    private var curLoop = false
    private var curPulseOnMs = 300.0f
    private var curPulseOffMs = 200.0f
    private var curDbfs = -70.0f
    private var playing = false

    /**
     * 合成並播放純音。振幅一律烘進取樣值（amplitude = 10^(dbfs/20)）。
     *
     * @param frequencyHz  頻率（Hz）
     * @param dbfs         數位位準（0 = 滿刻度）
     * @param ear          輸出耳別
     * @param pulsed       true = 300 ms 開 / 200 ms 關（含 10 ms 淡入淡出）
     * @param durationSec  緩衝區長度（秒）
     * @param loop         true = 持續循環至 stop()
     */
    fun play(
        frequencyHz: Int,
        dbfs: Float,
        ear: Ear,
        pulsed: Boolean = false,
        durationSec: Float = 1.5f,
        loop: Boolean = false,
        pulseOnMs: Float = 300.0f,
        pulseOffMs: Float = 200.0f
    ) {
        curFreqHz = frequencyHz
        curEar = ear
        curPulsed = pulsed
        curDurationSec = durationSec
        curLoop = loop
        curPulseOnMs = pulseOnMs
        curPulseOffMs = pulseOffMs
        curDbfs = dbfs
        render()
    }

    /**
     * 改變目前這顆音的位準：以新振幅重建緩衝區並從頭播放。
     *
     * 刻意不用 AudioTrack.setVolume()——它在 AudioFlinger 是 1/4096 的定點數，
     * 低於 −72 dBFS 就表達不出來，而那正是聽力測驗最需要的區間（見類別註解）。
     */
    fun setLevelDbfs(dbfs: Float) {
        if (curDbfs == dbfs) return
        curDbfs = dbfs
        if (playing) render()
    }

    /** 舊名稱；語意已改為「以新振幅重建波形」。 */
    fun setVolumeDbfs(dbfs: Float) = setLevelDbfs(dbfs)

    /** 依 [dbfs] 計算烘進取樣值的線性振幅（MUTE_DBFS 以下為真正的 0）。 */
    fun amplitudeFor(dbfs: Float): Float =
        if (dbfs <= MUTE_DBFS) 0.0f
        else 10.0.pow(dbfs / 20.0).toFloat().coerceIn(0.0f, 1.0f)

    private fun render() {
        val numSamples = (curDurationSec * sampleRate).toInt()
        val amp = amplitudeFor(curDbfs)

        val periodMs = curPulseOnMs + curPulseOffMs
        val stereoBuffer = FloatArray(numSamples * 2)
        val leftOn = curEar == Ear.LEFT || curEar == Ear.BOTH
        val rightOn = curEar == Ear.RIGHT || curEar == Ear.BOTH

        // 靜音聲道不可填數位全零：部分 DAC／USB-C 轉接器對全零聲道做省電閘控，
        // 對側脈衝一開一關會帶動放大器開關，在「靜音耳」產生與脈衝同步的細微
        // 噠噠聲（實測回報：測右耳時左耳有噠噠聲）。填 −110 dBFS 雜訊讓通道
        // 保持活躍——比最敏感聽閾（約 −99 dBFS）低一個數量級，聽不見。
        var seed = 0x9E3779B9.toInt()
        fun dither(): Float {
            seed = seed * 1664525 + 1013904223
            return (seed / 2.147483647E9f) * KEEPALIVE_AMP
        }

        for (i in 0 until numSamples) {
            val time = i.toFloat() / sampleRate
            val fade: Float = if (curPulsed) {
                val tMod = (time * 1000.0f) % periodMs
                when {
                    // 升餘弦淡入淡出：線性斜坡的頻譜濺射較寬，經線路串音漏到
                    // 對側聲道就是「噠」聲；餘弦形把瞬態能量壓掉。
                    tMod < FADE_MS -> 0.5f * (1.0f - kotlin.math.cos(PI * tMod / FADE_MS).toFloat())
                    tMod < curPulseOnMs - FADE_MS -> 1.0f
                    tMod < curPulseOnMs ->
                        0.5f * (1.0f - kotlin.math.cos(PI * (curPulseOnMs - tMod) / FADE_MS).toFloat())
                    else -> 0.0f                                             // 靜默段
                }
            } else 1.0f

            val s = (amp * fade * sin(2 * PI * curFreqHz * time)).toFloat()
            stereoBuffer[i * 2] = (if (leftOn) s else 0.0f) + dither()
            stereoBuffer[i * 2 + 1] = (if (rightOn) s else 0.0f) + dither()
        }

        // MODE_STATIC 的緩衝區大小在建構時固定，僅在長度改變時重建。
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
            if (audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) audioTrack?.stop()
            audioTrack?.flush()
            audioTrack?.setPlaybackHeadPosition(0)
        } catch (e: Exception) {
            Log.w(TAG, "AudioTrack reset skipped: ${e.message}")
        }

        audioTrack?.write(stereoBuffer, 0, stereoBuffer.size, AudioTrack.WRITE_BLOCKING)
        audioTrack?.setLoopPoints(0, numSamples, if (curLoop) -1 else 0)
        // 硬體音量固定為 1.0：所有衰減都已在取樣值裡完成。
        audioTrack?.setVolume(1.0f)
        audioTrack?.play()
        playing = true
    }

    /** 真正靜音（輸出振幅歸零）。 */
    fun mute() = setLevelDbfs(MUTE_DBFS)

    fun pause() {
        try { audioTrack?.pause(); playing = false } catch (e: Exception) { /* no-op */ }
    }

    fun resume() {
        try { audioTrack?.play(); playing = true } catch (e: Exception) { /* no-op */ }
    }

    fun stop() {
        try {
            if (audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) audioTrack?.stop()
            playing = false
        } catch (e: Exception) { /* no-op */ }
    }

    fun release() {
        audioTrack?.release()
        audioTrack = null
        trackNumSamples = 0
        playing = false
    }

    companion object {
        private const val TAG = "AudiometricToneGen"
        /** 低於此 dBFS 一律視為靜音（真正歸零輸出）。 */
        const val MUTE_DBFS = -119f
        /** 淡入淡出長度（ms）。 */
        private const val FADE_MS = 10.0f
        /** 通道保活雜訊振幅：−110 dBFS（3.2e−6），防 DAC 對全零聲道閘控。 */
        private const val KEEPALIVE_AMP = 3.2e-6f
    }
}
