package com.wcy.hark.audiometry

import android.util.Log
import com.wcy.hark.audio.bridge.HarkAudioBridge

/**
 * HearingLossSim — 聽損模擬的「一份組態」，供所有測驗路徑共用。
 *
 * 兩個作用點：
 *   1. 語詞測驗（離線）：混音後的整段音訊 → [離線 DSP 補償] → [本模擬器] → 播放
 *   2. 純音測驗（閉式）：純音是單頻穩態訊號，擴展器的作用僅是位準映射，
 *      直接算增益施加在音源振幅上即可，不需跑濾波器組。
 *
 * ★ 順序是效度核心 ★ 模擬器一定在 DSP 補償之後。真實情境是助聽器先處理、
 * 聲音才進入受損的耳蝸；反過來檢驗的就是「訊號還原」而不是助聽器。
 */
data class HearingLossSim(
    val profile: HearingLossProfile.Profile,
    /** 測試者本人的純音聽閾（dBFS），freq(Hz) → 閾值。模擬器的零點。 */
    val thresholdsDbfs: Map<Int, Float>,
    val smearing: Boolean
) {
    /** 8 個 DSP 頻帶的模擬損失量（dB）。 */
    val lossPerBand: FloatArray by lazy { HearingLossProfile.lossPerBand(profile) }

    /** 8 個 DSP 頻帶的測試者聽閾（dBFS）。 */
    val thresholdsPerBand: FloatArray by lazy {
        HearingLossProfile.thresholdsPerBand(thresholdsDbfs)
    }

    val broadenFactor: Float
        get() = if (smearing && !profile.isNone) HearingLossProfile.DEFAULT_BROADEN_FACTOR else 1f

    val isActive: Boolean get() = !profile.isNone

    /**
     * 對整段音訊套用聽損模擬。務必在 DSP 補償之後呼叫。
     * 失敗時回傳原訊號（不靜默改變實驗條件——會寫進 log 供事後檢核）。
     *
     * ★ 位準尺度契約 ★ 模擬器的擴展器以「感覺級 dB SL」運作，其 sl 由
     * 內部 envDb（訊號 RMS 之 dB）減 thresholdsPerBand（dBFS，0 dBFS = 滿刻度
     * = 振幅 1.0）而得；故模擬器要求輸入以「0 dBFS = 1.0」正規化（單元測試即以
     * 此尺度餵訊號）。但 SsnAudioMixer 的整條離線鏈以 short 尺度（±32768）運作，
     * 若直接把 short 尺度餵進來，envDb 會比真實 dBFS 高出 20·log10(32768) ≈
     * 90.3 dB，sl 隨之膨脹 90 dB，target 增益恆被夾到 0（全開）——擴展器對語音
     * 完全不衰減，聽損模擬在語詞路徑上形同未作用（純音路徑走 toneGainDb 閉式，
     * 不受影響）。故此處在邊界正規化：/32768 進、×32768 出。
     */
    fun processOffline(input: FloatArray, sampleRate: Int): FloatArray {
        if (!isActive) return input
        return try {
            val normalized = FloatArray(input.size) { input[it] / SHORT_FULL_SCALE }
            val out = HarkAudioBridge.hlSimProcessOffline(
                normalized, sampleRate,
                thresholdsPerBand, lossPerBand,
                HearingLossProfile.DEFAULT_UCL_DB, broadenFactor
            )
            for (i in out.indices) out[i] *= SHORT_FULL_SCALE
            out
        } catch (e: Throwable) {
            Log.e(TAG, "hlSimProcessOffline failed — 本 trial 未套用聽損模擬: ${e.message}", e)
            input
        }
    }

    /**
     * 純音的模擬增益（dB，≤0）。
     * @param freqHz     純音頻率
     * @param levelDbfs  未模擬前的輸出位準
     */
    fun toneGainDb(freqHz: Int, levelDbfs: Float): Float {
        if (!isActive) return 0f
        val bandIdx = nearestBand(freqHz.toFloat())
        return try {
            HarkAudioBridge.hlSimToneGainDb(
                levelDbfs,
                thresholdsPerBand[bandIdx],
                lossPerBand[bandIdx],
                HearingLossProfile.DEFAULT_UCL_DB
            )
        } catch (e: Throwable) {
            Log.e(TAG, "hlSimToneGainDb failed: ${e.message}")
            0f
        }
    }

    /** 該頻率之目標模擬損失量（dB）——操作檢核時拿來跟實測閾值比對。 */
    fun targetLossDb(freqHz: Int): Float = lossPerBand[nearestBand(freqHz.toFloat())]

    private fun nearestBand(freqHz: Float): Int {
        var best = 0
        var bestDist = Float.MAX_VALUE
        HearingLossProfile.BAND_CENTER_FREQS.forEachIndexed { i, f ->
            // 在對數頻率上比距離（聽覺是對數的）
            val d = kotlin.math.abs(kotlin.math.ln(freqHz / f))
            if (d < bestDist) { bestDist = d; best = i }
        }
        return best
    }

    companion object {
        private const val TAG = "HearingLossSim"

        /** short PCM 滿刻度：0 dBFS 對應之振幅，用於離線鏈 short↔正規化尺度換算。 */
        private const val SHORT_FULL_SCALE = 32768f

        /** 未模擬（正常聽力）的空組態。 */
        fun none() = HearingLossSim(HearingLossProfile.NONE, emptyMap(), false)
    }
}
