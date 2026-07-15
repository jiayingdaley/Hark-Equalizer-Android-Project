package com.wcy.hark.audio.fitting

import kotlin.math.ln

/**
 * Prescriptions — 聽力圖處方增益計算（DSL v5 by Hand 成人近似 / NAL-R）。
 *
 * 兩個處方都輸出「該頻率的插入增益 (dB)」，由 EqViewModel 對映到 16 段 EQ。
 * 計算方式詳見 docs/FITTING_PRESCRIPTIONS.md。
 *
 * 參考文獻：
 * - DSL v5 by Hand, Child Amplification Lab, National Centre for Audiology,
 *   Western University.（Table 7: Pediatric Targets for Mid Speech 65 dB SPL）
 * - Bagatto et al. (2005), Trends in Amplification 9(4):199-226.
 * - Scollie et al. (2005), Trends in Amplification 9(4):159-197.
 * - Byrne & Dillon (1986). The National Acoustic Laboratories' (NAL) new
 *   procedure for selecting the gain and frequency response of a hearing aid.
 *   Ear and Hearing, 7(4):257-265.
 */
object Prescriptions {

    enum class Method { DSL_V5, NAL_R }

    // 16 段 EQ 中心頻率（與 EqViewModel.centerFrequencies16 / SystemDspManager
    // 一致）。獨立列於此供不持有 ViewModel 的情境（如測試者實驗流程 A/B 對照）
    // 直接計算固定處方增益陣列。
    val CENTER_FREQUENCIES_16 = listOf(
        250, 315, 400, 500, 630, 800, 1000, 1250,
        1600, 2000, 2500, 3150, 4000, 5000, 6300, 8000
    )
    private const val MAX_GAIN_DB = 30f

    // 模擬中度聽損（測試者實驗流程「SSN A/B 對照」用）：平坦 50 dB HL、
    // 雙耳皆有聽力圖之假設（binaural = true）。此為固定、非個人化之處方，
    // 目的僅是在正常聽力測試者身上建立一個穩定可重現的「有補償」條件，
    // 與「無補償（OFF）」比較語詞辨識表現差異（ΔSRT50），而非驗配其本人。
    const val SIMULATED_MODERATE_LOSS_HL = 50f

    /** 平坦 50 dB HL 模擬聽損之 16 段 DSL v5 處方增益（雙耳修正 −3 dB 已套用）。 */
    fun simulatedModerateLossGains16(): FloatArray =
        CENTER_FREQUENCIES_16.map {
            dslV5Gain(it, SIMULATED_MODERATE_LOSS_HL, binaural = true).coerceIn(0f, MAX_GAIN_DB)
        }.toFloatArray()

    // ── DSL v5 by Hand ──────────────────────────────────────────────────
    // Table 7: Mid Speech (65 dB SPL) 目標真耳輸出 REAR (dB SPL re: ear canal)，
    // 小兒目標；成人使用時減 7 dB（by-Hand Appendix 1 第 1 點）。
    private val DSL_FREQS = intArrayOf(250, 500, 750, 1000, 1500, 2000, 3000, 4000, 6000)
    private val DSL_THRESHOLDS = IntArray(23) { it * 5 }   // 0, 5, ..., 110 dB HL

    private val DSL_REAR_65 = arrayOf(
        //          250  500  750 1000 1500 2000 3000 4000 6000
        /*   0 */ intArrayOf(56, 59, 55, 53, 53, 56, 57, 55, 48),
        /*   5 */ intArrayOf(59, 62, 58, 56, 56, 59, 60, 58, 51),
        /*  10 */ intArrayOf(61, 64, 61, 59, 59, 62, 64, 62, 54),
        /*  15 */ intArrayOf(64, 67, 64, 62, 62, 66, 67, 65, 58),
        /*  20 */ intArrayOf(67, 70, 67, 65, 66, 69, 70, 68, 61),
        /*  25 */ intArrayOf(70, 73, 69, 68, 69, 72, 73, 71, 65),
        /*  30 */ intArrayOf(71, 74, 71, 69, 70, 73, 75, 73, 67),
        /*  35 */ intArrayOf(73, 75, 72, 71, 72, 75, 76, 75, 69),
        /*  40 */ intArrayOf(76, 77, 74, 72, 74, 77, 78, 77, 72),
        /*  45 */ intArrayOf(78, 79, 76, 75, 76, 79, 81, 79, 75),
        /*  50 */ intArrayOf(81, 81, 78, 77, 79, 82, 83, 82, 78),
        /*  55 */ intArrayOf(85, 85, 82, 81, 81, 84, 86, 85, 81),
        /*  60 */ intArrayOf(89, 88, 85, 84, 85, 88, 90, 89, 85),
        /*  65 */ intArrayOf(93, 91, 88, 87, 89, 92, 94, 93, 89),
        /*  70 */ intArrayOf(97, 95, 92, 91, 93, 96, 98, 97, 94),
        /*  75 */ intArrayOf(101, 99, 96, 95, 97, 100, 101, 99, 98),
        /*  80 */ intArrayOf(104, 102, 99, 99, 100, 103, 103, 102, 101),
        /*  85 */ intArrayOf(108, 104, 102, 102, 103, 105, 106, 105, 104),
        /*  90 */ intArrayOf(109, 107, 105, 105, 106, 108, 110, 109, 107),
        /*  95 */ intArrayOf(113, 111, 108, 108, 109, 112, 114, 113, 112),
        /* 100 */ intArrayOf(117, 115, 112, 112, 113, 116, 117, 116, 116),
        /* 105 */ intArrayOf(121, 116, 116, 116, 117, 118, 119, 119, 118),
        /* 110 */ intArrayOf(125, 120, 120, 120, 120, 121, 123, 121, 121)
    )

    // Worksheet「Subtract input SPL」列（65 dB SPL 輸入時的各頻帶語音輸入 SPL）
    private val DSL_INPUT_65 = intArrayOf(55, 57, 52, 50, 48, 44, 42, 41, 40)

    private const val DSL_ADULT_CORRECTION_DB = 7f     // Appendix 1 第 1 點
    private const val DSL_BINAURAL_CORRECTION_DB = 3f  // Appendix 1 第 4-5 點

    /**
     * DSL v5 by Hand 成人近似插入增益。
     * gain = REAR₆₅(threshold, f) − inputSPL₆₅(f) − 7（成人）− 3（雙耳，可選）
     * 頻率超出表格範圍（>6000）沿用 6000 欄；閾值於 5 dB 表列間線性內插。
     */
    fun dslV5Gain(freqHz: Int, thresholdHl: Float, binaural: Boolean): Float {
        val hl = thresholdHl.coerceIn(0f, 110f)
        val rowLo = (hl / 5f).toInt().coerceAtMost(21)
        val rowFrac = (hl - DSL_THRESHOLDS[rowLo]) / 5f
        val rearLo = interpAcrossFreq(freqHz, DSL_FREQS, DSL_REAR_65[rowLo])
        val rearHi = interpAcrossFreq(freqHz, DSL_FREQS, DSL_REAR_65[rowLo + 1])
        val rear = rearLo + (rearHi - rearLo) * rowFrac
        val input = interpAcrossFreq(freqHz, DSL_FREQS, DSL_INPUT_65)
        val gain = rear - input - DSL_ADULT_CORRECTION_DB -
                (if (binaural) DSL_BINAURAL_CORRECTION_DB else 0f)
        return gain.coerceAtLeast(0f)
    }

    // ── NAL-R (Byrne & Dillon, 1986) ────────────────────────────────────
    // IG(f) = X + 0.31 × HTL(f) + k(f)，X = 0.15 × H3FA（500/1k/2k 三頻平均）。
    // k(f) 頻率修正項；原始論文至 6000 Hz，8000 沿用高頻值。
    private val NAL_FREQS = intArrayOf(250, 500, 750, 1000, 1500, 2000, 3000, 4000, 6000)
    private val NAL_K = intArrayOf(-17, -8, -3, 1, 1, -1, -2, -2, -2)

    /**
     * NAL-R 插入增益。h3fa = (HTL₅₀₀ + HTL₁₀₀₀ + HTL₂₀₀₀) / 3。
     */
    fun nalRGain(freqHz: Int, thresholdHl: Float, h3fa: Float): Float {
        val x = 0.15f * h3fa
        val k = interpAcrossFreq(freqHz, NAL_FREQS, NAL_K)
        return (x + 0.31f * thresholdHl + k).coerceAtLeast(0f)
    }

    // ── 共用：聽力圖缺測頻率內插 ─────────────────────────────────────────
    /**
     * 於對數頻率軸上以已測閾值線性內插目標頻率的閾值。
     * 超出已測範圍時取最近端點；audiogram 為空回傳 null。
     */
    fun interpolateThreshold(freqHz: Int, audiogram: Map<Int, Float>): Float? {
        if (audiogram.isEmpty()) return null
        audiogram[freqHz]?.let { return it }
        val sorted = audiogram.entries.sortedBy { it.key }
        if (freqHz <= sorted.first().key) return sorted.first().value
        if (freqHz >= sorted.last().key) return sorted.last().value
        val hi = sorted.first { it.key > freqHz }
        val lo = sorted.last { it.key < freqHz }
        val t = (ln(freqHz.toFloat()) - ln(lo.key.toFloat())) /
                (ln(hi.key.toFloat()) - ln(lo.key.toFloat()))
        return lo.value + (hi.value - lo.value) * t
    }

    /** 表格頻率間對數頻率線性內插；範圍外取端點值。 */
    private fun interpAcrossFreq(freqHz: Int, freqs: IntArray, values: IntArray): Float {
        if (freqHz <= freqs.first()) return values.first().toFloat()
        if (freqHz >= freqs.last()) return values.last().toFloat()
        var i = 0
        while (freqs[i + 1] < freqHz) i++
        if (freqs[i + 1] == freqHz) return values[i + 1].toFloat()
        val t = (ln(freqHz.toFloat()) - ln(freqs[i].toFloat())) /
                (ln(freqs[i + 1].toFloat()) - ln(freqs[i].toFloat()))
        return values[i] + (values[i + 1] - values[i]) * t
    }
}
