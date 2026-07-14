package com.wcy.hark.audiometry

import kotlin.math.ln

/**
 * HearingLossProfile — 模擬聽損的組態（標準聽力圖 + 頻譜模糊）。
 *
 * 為什麼需要模擬聽損：測試者是聽力正常的人。若直接比較「有無 DSP 補償」，
 * 補償的對象並不存在——補償唯一可能的效益是「把落在聽閾以下的語音線索拉回
 * 可聽範圍」，正常耳沒有這種線索，A/B 對照的期望效果為零（甚至因壓縮失真為負）。
 * 先以訊號處理在正常耳上重建聽損的知覺後果，補償才有可檢驗的對象。
 *
 * 聽力圖採 IEC 60118-15 之 10 張標準聽力圖：
 *   Bisgaard, N., Vlaming, M. S. M. G., & Dahlquist, M. (2010).
 *   Standard audiograms for the IEC 60118-15 measurement procedure.
 *   Trends in Amplification, 14(2), 113–120.
 *   N1–N7 = 平坦／緩降型（很輕度 → 極重度）；S1–S3 = 陡降型。
 *
 * 這 10 張圖是以向量量化（vector quantization）從 28,244 張臨床聽力圖分群得出的
 * 代表點，宣稱可涵蓋臨床上遇到的整個聽力圖範圍——換言之，選其中幾張作為
 * 模擬條件，等於是在「臨床母體的代表點」上取樣，而不是自訂一張沒有依據的圖。
 */
object HearingLossProfile {

    /** 聽力圖的量測頻率（Hz），與純音測驗一致。 */
    val AUDIOGRAM_FREQS = intArrayOf(250, 500, 1000, 2000, 3000, 4000, 6000, 8000)

    /**
     * 8 個 DSP 頻帶的代表頻率（幾何中心，Hz）。
     * 對應 HarkDspConfig 的 LR4 分頻樹：
     *   Band0 <250 / Band1 250–500 / Band2 500–1000 / Band3 1000–1500
     *   Band4 1500–2500 / Band5 2500–4500 / Band6 4500–6000 / Band7 >6000
     */
    val BAND_CENTER_FREQS = floatArrayOf(
        180f, 354f, 707f, 1225f, 1936f, 3354f, 5196f, 7000f
    )

    const val DEFAULT_UCL_DB = 100f

    /**
     * 頻譜模糊的聽覺濾波器加寬倍數。
     * 1.0 = 不模糊；3.0 ≈ 中重度感音神經性聽損之典型加寬程度（Baer & Moore, 1993）。
     */
    const val DEFAULT_BROADEN_FACTOR = 3.0f

    /**
     * @param key         代號（寫入資料庫與 CSV）
     * @param label       UI 顯示名稱
     * @param thresholds  各 AUDIOGRAM_FREQS 之聽力損失量（dB HL）
     */
    data class Profile(
        val key: String,
        val label: String,
        val thresholds: FloatArray
    ) {
        val isNone: Boolean get() = key == "NONE"

        /** 本聽力圖之最大損失量（dB HL）——決定所需的數位餘裕。 */
        val maxLossDb: Float get() = thresholds.max()

        override fun equals(other: Any?) = other is Profile && other.key == key
        override fun hashCode() = key.hashCode()
    }

    val NONE = Profile("NONE", "不模擬（正常聽力）", FloatArray(8) { 0f })

    // ─────────────────────────────────────────────────────────────────────────
    // Bisgaard et al. (2010) Table 2 之標準聽力圖（dB HL）。
    //
    // 原表的量測格點為 250 / 375 / 500 / 750 / 1000 / 1500 / 2000 / 3000 / 4000 /
    // 6000 Hz，本專案的純音格點為 250 / 500 / 1k / 2k / 3k / 4k / 6k / 8k。
    // 重疊的 7 個頻率直接取原值；原表沒有 8 kHz，此處延用 6 kHz 之值（保守外推，
    // 不臆造原表沒有的數字）。
    // ─────────────────────────────────────────────────────────────────────────
    //                                     250   500    1k    2k    3k    4k    6k    8k*
    val N1 = Profile("N1", "N1 · 很輕度（平坦）",
        floatArrayOf(10f, 10f, 10f, 15f, 20f, 30f, 40f, 40f))
    val N2 = Profile("N2", "N2 · 輕度（平坦）",
        floatArrayOf(20f, 20f, 25f, 35f, 40f, 45f, 50f, 50f))
    val N3 = Profile("N3", "N3 · 中度（平坦）",
        floatArrayOf(35f, 35f, 40f, 50f, 55f, 60f, 65f, 65f))
    val N4 = Profile("N4", "N4 · 中重度（平坦）",
        floatArrayOf(55f, 55f, 55f, 65f, 70f, 75f, 80f, 80f))
    val N5 = Profile("N5", "N5 · 重度（平坦）",
        floatArrayOf(65f, 70f, 75f, 80f, 80f, 80f, 80f, 80f))
    val N6 = Profile("N6", "N6 · 重度（平坦，較深）",
        floatArrayOf(75f, 80f, 85f, 90f, 95f, 100f, 100f, 100f))
    val N7 = Profile("N7", "N7 · 極重度（平坦）",
        floatArrayOf(90f, 95f, 105f, 105f, 105f, 105f, 105f, 105f))

    val S1 = Profile("S1", "S1 · 很輕度陡降（預設）",
        floatArrayOf(10f, 10f, 10f, 15f, 30f, 55f, 70f, 70f))
    val S2 = Profile("S2", "S2 · 輕度陡降（陡）",
        floatArrayOf(20f, 20f, 25f, 55f, 75f, 95f, 95f, 95f))
    val S3 = Profile("S3", "S3 · 中重度陡降",
        floatArrayOf(30f, 35f, 60f, 75f, 80f, 80f, 85f, 85f))

    /**
     * UI 選單順序：先放本研究實際會用的三張（S1 主要條件、N2 平坦對照、S2 加重），
     * 其餘 Bisgaard 標準圖列於其後備用。
     *
     * ★ 為什麼預設是 S1 而不是更重的 S2/N4 ★
     *
     * 兩個約束把可用範圍壓得比想像中窄：
     *
     * (1) 數位餘裕。模擬器只會衰減（增益恆 ≤ 0）。要讓測試者在模擬 HL 之上還聽得到
     *     SL 分貝的語音，送進模擬器的數位位準必須是「測試者聽閾 + HL + SL」。測試者
     *     的聽閾約在 −70 dBFS，總餘裕就只有 70 dB。S2 在 4 kHz 損失 95 dB —— 光是要
     *     讓它「剛好可聽」就得送出 +25 dBFS，數位上根本不存在，補償後也一樣不存在。
     *     未輔助聽不見、輔助後「仍然」聽不見 → ΔSL 為零，實驗做不出東西。
     *
     * (2) 處方增益上限。DSL v5 對 95 dB HL 開的增益遠超過 Hark 的 30 dB 夾限，也超過
     *     一般耳機在不破音下的輸出能力。
     *
     * S1（高頻 55–70 dB HL、低頻幾乎正常）落在兩個約束之內，同時仍是乾淨的陡降型：
     * 高頻擦音未輔助時確實掉到模擬聽閾以下，補償把它救回來的效果可測且可解釋。
     * 這正是要展示的機制。
     *
     * 用 requiredHeadroomDbfs() 對「當下這位測試者的實測聽閾」逐一檢查，不要憑感覺選。
     */
    val ALL = listOf(NONE, S1, S2, S3, N1, N2, N3, N4, N5, N6, N7)

    val DEFAULT = S1

    fun fromKey(key: String?): Profile = ALL.firstOrNull { it.key == key } ?: NONE

    /**
     * 這位測試者、這張聽力圖，要在「模擬聽損—純音測試」把音量推到模擬聽閾所需的
     * 最大數位位準（dBFS），取 [checkFreqs] 之最大值。
     *
     * 這是整個設計裡最硬的一道約束。模擬器只會衰減（增益恆 ≤ 0），要讓測試者聽到
     * 模擬聽閾，送進去的數位位準必須是「本人聽閾 + 模擬損失」。本人聽閾約在
     * −70 dBFS，離數位滿刻度只有 70 dB —— 損失量一旦逼近這個數字，模擬聽閾就
     * 推不上去了：未輔助聽不見、輔助後「仍然」聽不見，ΔSL 為零，實驗做不出東西。
     *
     * 這也正是本專案不能用 Bisgaard 較重的那幾張圖（S2 在 4 kHz 損失 95 dB、
     * N4 以上）的原因，與「耳機推不動」無關，是數位域先撞牆。
     *
     * @param thresholdsDbfs 測試者本人各頻率的實測聽閾（dBFS，來自步驟①基準純音）
     * @param checkFreqs     模擬聽損純音測試實際會測的頻率
     * @return 所需的最大 dBFS；與滑桿上限比較即知可不可行
     */
    fun requiredLevelDbfs(
        profile: Profile,
        thresholdsDbfs: Map<Int, Float>,
        checkFreqs: List<Int>
    ): Float {
        if (profile.isNone || thresholdsDbfs.isEmpty()) return -100f
        var worst = -100f
        checkFreqs.forEach { f ->
            val i = AUDIOGRAM_FREQS.indexOf(f)
            val t = thresholdsDbfs[f]
            if (i >= 0 && t != null) {
                val need = t + profile.thresholds[i]
                if (need > worst) worst = need
            }
        }
        return worst
    }

    /**
     * 把聽力圖（8 個量測頻率）內插到 8 個 DSP 頻帶的代表頻率上（對數頻率線性內插）。
     * 模擬器是逐頻帶作用的，故需要的是「每個 DSP 頻帶的損失量」而非「每個量測頻率的」。
     */
    fun lossPerBand(profile: Profile): FloatArray =
        FloatArray(8) { b -> interpolate(profile.thresholds, BAND_CENTER_FREQS[b]) }

    /**
     * 把測試者本人的純音聽閾（dBFS，量在 8 個量測頻率上）內插到 8 個 DSP 頻帶。
     * 這就是模擬器的「零點」——所有位準以此為基準的感覺級（dB SL）計算，
     * 因此完全不需要人工耳或聲級計做絕對聲學校正。
     *
     * @param thresholdsDbfs freq(Hz) → 閾值 dBFS（雙耳平均或單耳）
     */
    fun thresholdsPerBand(thresholdsDbfs: Map<Int, Float>): FloatArray {
        // 缺測頻率以鄰近值補；全缺時退回一個保守的預設閾值
        val arr = FloatArray(AUDIOGRAM_FREQS.size)
        var lastKnown = -75f
        for (i in AUDIOGRAM_FREQS.indices) {
            val v = thresholdsDbfs[AUDIOGRAM_FREQS[i]]
            if (v != null) lastKnown = v
            arr[i] = lastKnown
        }
        return FloatArray(8) { b -> interpolate(arr, BAND_CENTER_FREQS[b]) }
    }

    /** 對數頻率上的線性內插（端點外一律夾住）。 */
    private fun interpolate(values: FloatArray, freqHz: Float): Float {
        val f = freqHz.coerceIn(
            AUDIOGRAM_FREQS.first().toFloat(), AUDIOGRAM_FREQS.last().toFloat()
        )
        for (i in 0 until AUDIOGRAM_FREQS.size - 1) {
            val f0 = AUDIOGRAM_FREQS[i].toFloat()
            val f1 = AUDIOGRAM_FREQS[i + 1].toFloat()
            if (f in f0..f1) {
                val t = (ln(f / f0) / ln(f1 / f0)).toFloat()
                return values[i] + t * (values[i + 1] - values[i])
            }
        }
        return values.last()
    }
}
