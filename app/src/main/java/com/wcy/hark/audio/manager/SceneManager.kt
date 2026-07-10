package com.wcy.hark.audio.manager

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlin.math.log10
import kotlin.math.sqrt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.wcy.hark.audio.bridge.HarkAudioBridge
import com.wcy.hark.audio.router.MediaSessionObserver

/**
 * SceneManager: The intelligent hub for situational mode switching.
 *
 * Responsibilities:
 * 1. Periodic environmental analysis (using DSP spectral data).
 * 2. Media session monitoring (via MediaSessionObserver).
 * 3. Handling Manual Override (lock mode).
 *
 * Lifecycle:
 * The caller (HarkAudioService) provides an external [scope] tied to the Service's lifecycle.
 * This ensures all launched coroutines are cancelled when the Service is destroyed,
 * preventing orphaned coroutines calling JNI after engine teardown (KNOWN-ISSUE-007 fix).
 * Ref: SceneManager coroutine lifecycle alignment — .ai_collaboration/llm_bug_knowledge.md
 */
class SceneManager(
    private val context: Context,
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

    private val mediaObserver = MediaSessionObserver(context) { isPlaying ->
        if (!isAutoLocked.value) {
            if (isPlaying) {
                applyMode(Mode.CINEMA)
            } else {
                // Return to auto-analysis if music stops
                startAutoAnalysis()
            }
        }
    }

    fun start() {
        mediaObserver.start()
        startAutoAnalysis()
    }

    fun stop() {
        mediaObserver.stop()
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

    // ── 自動環境分類（2026-07 實地錄音驗證後改版）──────────────────────────
    //
    // 舊版每 5 秒取一次 5 頻段能量，用頻譜比例門檻分類（lowFreq>0.7 → 戶外、
    // highFreq>0.4 → 對話）。實地錄音驗證（tests/field_performance/raw/
    // env_mode_classification）顯示：語音能量本來就集中在 500–1k Hz，對話與
    // 戶外的頻譜比例幾乎相同（low ratio 中位數 0.88 vs 0.84），highFreq>0.4
    // 幾乎不會觸發（對話正確率 0–7%）。能區分兩者的是「時間包絡起伏」：
    // 語音有 ~4 Hz 音節調變（5 秒窗內能量 dB 標準差 ~5 dB），風切/交通為
    // 穩態（~3 dB）。因此改為每 250 ms 取樣、滾動 5 秒窗：
    //
    //   安靜（平均總能量 < QUIET_TOTAL）        → TRANSPARENCY
    //   包絡調變（dB 標準差）> MOD_STD_CONV     → CONVERSATION
    //   低頻佔比 > LOW_RATIO_OUTDOOR            → OUTDOOR
    //   其餘                                     → TRANSPARENCY
    //
    // 並加入遲滯：連續 HYSTERESIS_WINDOWS 個 5 秒窗判成同一模式才實際切換，
    // 避免每個窗來回跳動。CINEMA 仍由 MediaSessionObserver 觸發，不走頻譜。

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
        val lowRatio = (sampleBandSums[0] + sampleBandSums[1]) /
                       (sampleBandSums.sum() + 1e-12f)

        // 包絡調變：窗內總能量 (dB) 的標準差
        val meanDb = sampleTotalsDb.sum() / sampleTotalsDb.size
        var varDb = 0f
        for (v in sampleTotalsDb) varDb += (v - meanDb) * (v - meanDb)
        val modStdDb = sqrt(varDb / sampleTotalsDb.size)

        val detected = when {
            meanTotal < QUIET_TOTAL -> Mode.TRANSPARENCY   // 極安靜
            modStdDb > MOD_STD_CONV -> Mode.CONVERSATION   // 語音的音節調變
            lowRatio > LOW_RATIO_OUTDOOR -> Mode.OUTDOOR   // 穩態低頻（風切/交通）
            else -> Mode.TRANSPARENCY
        }

        // 校準用：QUIET_TOTAL 無法離線驗證（手機錄音有 AGC），需在實機安靜
        // 環境下讀此 log 校定門檻。
        Log.d(TAG, "window: meanTotal=%.3e lowRatio=%.2f modStd=%.1fdB → %s"
            .format(meanTotal, lowRatio, modStdDb, detected))

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
         *  需在實機安靜環境讀 window log 校定。 */
        private const val QUIET_TOTAL = 0.001f
        /** 對話門檻：5 秒窗內總能量 dB 標準差（實地資料：語音 ~5.2、穩態噪音 ~3.1）。 */
        private const val MOD_STD_CONV = 4.0f
        /** 戶外門檻：500+1k Hz 佔總能量比例（實地資料掃描結果）。 */
        private const val LOW_RATIO_OUTDOOR = 0.6f
    }
}
