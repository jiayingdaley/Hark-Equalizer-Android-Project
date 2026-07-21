package com.wcy.hark.audio.manager

import android.util.Log
import kotlinx.coroutines.*
import kotlin.math.log10
import kotlin.math.sqrt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.wcy.hark.audio.bridge.HarkAudioBridge

/**
 * SceneManager: The intelligent hub for situational mode switching.
 *
 * Responsibilities:
 * 1. Periodic environmental analysis (using DSP spectral data).
 * 2. Handling Manual Override (lock mode).
 *
 * CINEMA (影音) is manual-only. Two auto-detection paths were considered and
 * rejected:
 *   - MediaSessionObserver (own-device media playback via Notification
 *     Access): removed — if the phone is playing its own media, the user
 *     already has a dedicated better path ("手機影音/內部音訊" tab, which
 *     processes system output directly instead of picking it back up through
 *     the mic), so this only covered a narrow "still on mic mode while phone
 *     plays media in the background" case, at the cost of a permission most
 *     users never grant.
 *   - Spectral classification (same features as OUTDOOR/CONVERSATION):
 *     rejected after field-recording validation
 *     (tests/field_performance/raw/env_mode_classification) showed music's
 *     envelope modulation (modStd) is statistically indistinguishable from
 *     conversation (median 5.1 vs 5.3 dB) — a rule here would silently
 *     misclassify concerts/ambient music as CONVERSATION.
 *
 * Lifecycle:
 * The caller (HarkAudioService) provides an external [scope] tied to the Service's lifecycle.
 * This ensures all launched coroutines are cancelled when the Service is destroyed,
 * preventing orphaned coroutines calling JNI after engine teardown (KNOWN-ISSUE-007 fix).
 * Ref: SceneManager coroutine lifecycle alignment — .ai_collaboration/llm_bug_knowledge.md
 */
class SceneManager(
    private val scope: CoroutineScope  // Must be the Service's scope, not a self-owned one
) {

    enum class Mode(val id: Int) {
        TRANSPARENCY(0),
        CONVERSATION(1),
        OUTDOOR(2),
        CINEMA(3),
        AUTO(4)
    }

    private val _currentMode = MutableStateFlow(Mode.TRANSPARENCY)
    val currentMode: StateFlow<Mode> = _currentMode

    private val _isAutoLocked = MutableStateFlow(false)
    val isAutoLocked: StateFlow<Boolean> = _isAutoLocked

    private var autoJob: Job? = null

    fun start() {
        // start() 會被重複呼叫（服務的 startForegroundService() 對每個 ACTION_START
        // 都執行一次）。若使用者已手動鎖定模式，重跑 start() 不得重啟自動分類，
        // 否則手動鎖被靜默抹掉（實測：問卷頁鎖「對話」仍被自動分類切走）。
        if (_isAutoLocked.value) return
        startAutoAnalysis()
    }

    fun stop() {
        autoJob?.cancel()
        // Note: we do NOT cancel the external scope here — the caller (HarkAudioService) owns it
    }

    /**
     * User manually selects a mode. This "locks" the auto-detection.
     */
    fun selectModeManual(mode: Mode) {
        if (mode == Mode.AUTO) {
            _isAutoLocked.value = false
            startAutoAnalysis()
        } else {
            _isAutoLocked.value = true
            autoJob?.cancel()
            applyMode(mode)
        }
    }

    // ── 自動環境分類（2026-07-14 二次改版：忠實重現後重新驗證）──────────────
    //
    // 前一版改用 lowRatio（500+1k 佔總能量比例）判斷戶外，門檻依 Welch PSD
    // 區塊平均得出（lowRatio 中位數 0.88 對 0.84、modStd 5.2 對 3.1 dB）。
    // 但那組分析用的頻帶能量估計方式跟本引擎實際運作方式不同——
    // getEnvironmentEnergy() 回傳的是 NoiseSuppressor 內部 5 顆 RBJ biquad
    // 帶通濾波器整流後、以每取樣點 EMA（alpha=0.95，見 NS_ALPHA_SIGNAL）追蹤
    // 的快速包絡瞬時值，不是整段訊號的頻譜功率平均。用同一條鏈路（RBJ
    // biquad + 逐樣本 EMA）重新分析同一批錄音後（tests/field_performance/raw/
    // env_mode_classification/code/env_mode_analysis_faithful.py）發現
    // lowRatio 在四種模式間幾乎無法區分（中位數 0.44/0.44/0.45/0.36），套用
    // 舊門檻之對話正確率僅 14%、戶外僅 2%——舊門檻並未驗證到實際會執行的程式碼。
    //
    // 忠實重算後改用「總能量高低」取代 lowRatio 作為戶外判準（戶外錄音之
    // meanTotal 中位數比對話/影音高一個數量級以上，來自風切/交通之寬頻高能
    // 量），modStd 門檻同時重新校正（2.4 dB，原 4 dB 過高）。三類（不含手動
    // 限定之 CINEMA）網格搜尋後之準確率：透明 76%、對話 75%、戶外 68%。
    //
    //   安靜（平均總能量 < QUIET_TOTAL）        → TRANSPARENCY
    //   總能量 > OUTDOOR_TOTAL                  → OUTDOOR（風切/交通之寬頻高能量）
    //   包絡調變（dB 標準差）> MOD_STD_CONV     → CONVERSATION
    //   其餘                                     → TRANSPARENCY
    //
    // 並加入遲滯：連續 HYSTERESIS_WINDOWS 個 5 秒窗判成同一模式才實際切換，
    // 避免每個窗來回跳動。CINEMA 為手動限定模式，不參與自動判斷（見類別註解）。

    private val sampleTotalsDb = ArrayDeque<Float>()   // 每 250 ms 一筆：總能量 (dB)
    private val sampleBandSums = FloatArray(5)         // 窗內各頻段能量累加（線性）
    private var samplesInWindow = 0
    private var pendingMode: Mode? = null
    private var pendingCount = 0

    private fun startAutoAnalysis() {
        autoJob?.cancel()
        sampleTotalsDb.clear()
        sampleBandSums.fill(0f)
        samplesInWindow = 0
        pendingMode = null
        pendingCount = 0
        autoJob = scope.launch {
            while (isActive) {
                if (!_isAutoLocked.value) {
                    collectSample()
                    if (samplesInWindow >= SAMPLES_PER_WINDOW) evaluateWindow()
                }
                delay(SAMPLE_INTERVAL_MS)
            }
        }
    }

    private fun collectSample() {
        val energy = HarkAudioBridge.getEnvironmentEnergy()
        if (energy.size < 5) return
        val total = energy.sum()
        sampleTotalsDb.addLast(10f * log10(total + 1e-12f))
        for (i in 0 until 5) sampleBandSums[i] += energy[i]
        samplesInWindow++
    }

    private fun evaluateWindow() {
        val meanTotal = sampleBandSums.sum() / samplesInWindow

        // 包絡調變：窗內總能量 (dB) 的標準差
        val meanDb = sampleTotalsDb.sum() / sampleTotalsDb.size
        var varDb = 0f
        for (v in sampleTotalsDb) varDb += (v - meanDb) * (v - meanDb)
        val modStdDb = sqrt(varDb / sampleTotalsDb.size)

        val detected = when {
            meanTotal < QUIET_TOTAL -> Mode.TRANSPARENCY   // 極安靜
            meanTotal > OUTDOOR_TOTAL -> Mode.OUTDOOR      // 風切/交通之寬頻高能量
            modStdDb > MOD_STD_CONV -> Mode.CONVERSATION   // 語音的音節調變
            else -> Mode.TRANSPARENCY
        }

        // 校準用：QUIET_TOTAL／OUTDOOR_TOTAL 無法離線驗證（手機錄音有 AGC），
        // 需在實機環境下讀此 log 校定門檻。
        Log.d(TAG, "window: meanTotal=%.3e modStd=%.1fdB → %s"
            .format(meanTotal, modStdDb, detected))

        // 遲滯：連續 HYSTERESIS_WINDOWS 個窗一致才切換
        if (detected == pendingMode) pendingCount++ else { pendingMode = detected; pendingCount = 1 }
        if (pendingCount >= HYSTERESIS_WINDOWS && detected != currentMode.value) {
            applyMode(detected)
        }

        sampleTotalsDb.clear()
        sampleBandSums.fill(0f)
        samplesInWindow = 0
    }

    private fun applyMode(mode: Mode) {
        _currentMode.value = mode
        HarkAudioBridge.setSituationalMode(mode.id)
    }

    companion object {
        private const val TAG = "SceneManager"

        private const val SAMPLE_INTERVAL_MS = 250L        // 能量取樣間隔
        private const val SAMPLES_PER_WINDOW = 20          // 20 × 250 ms = 5 秒窗
        private const val HYSTERESIS_WINDOWS = 2           // 連續一致窗數才切換

        /** 極安靜門檻（平均總能量，線性）。離線錄音因 AGC 無法校此值，
         *  需在實機安靜環境讀 window log 校定。取自忠實重算之網格搜尋結果。 */
        private const val QUIET_TOTAL = 0.0009f
        /** 對話門檻：5 秒窗內總能量 dB 標準差。忠實重算後之實地資料中位數：
         *  對話 3.0、戶外 2.0、影音 3.6——與舊版 Welch PSD 估計（5.2/3.1）不同，
         *  因估計方法不同（見類別註解），門檻已重新網格搜尋校正。 */
        private const val MOD_STD_CONV = 2.4f
        /** 戶外門檻：平均總能量（線性），取代舊版 lowRatio（實地資料顯示 lowRatio
         *  在四模式間幾乎無法區分，見類別註解）。戶外錄音之寬頻風切/交通能量
         *  遠高於對話/影音，故改以總能量高低判斷。 */
        private const val OUTDOOR_TOTAL = 0.0112f
    }
}
