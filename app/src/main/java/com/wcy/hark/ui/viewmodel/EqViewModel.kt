package com.wcy.hark.ui.viewmodel

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.wcy.hark.audio.bridge.HarkAudioBridge
import com.wcy.hark.audio.service.HarkAudioService
import com.wcy.hark.audio.manager.SceneManager
import com.wcy.hark.audio.manager.SystemDspManager
import com.wcy.hark.audio.fitting.Prescriptions
import com.wcy.hark.data.EqSettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

enum class AudioSourceMode {
    MICROPHONE,
    INTERNAL_MEDIA
}

enum class EarType {
    LEFT,
    BOTH,
    RIGHT
}

class EqViewModel(private val repository: EqSettingsRepository) : ViewModel() {

    companion object {
        // +30 dB：環境輔聽麥克風輸入約 −40 dBFS 以下仍有數位餘裕，且輸出端有
        // limiter 防削波；市售 PSAP 最大聲學增益普遍 25–35 dB。
        const val MAX_GAIN_DB = 30f
        const val MIN_GAIN_DB = -24f
        const val DEFAULT_Q: Float = 1.4f
    }

    val statusText = mutableStateOf("狀態：已停用")
    val isDataLoaded = mutableStateOf(false)

    // Situational Mode State
    val situationalMode = mutableStateOf(SceneManager.Mode.TRANSPARENCY)
    val isAutoLocked = mutableStateOf(false)
    val useHeadsetMic = mutableStateOf(true) // 預設使用耳機麥克風
    val isMicrophonePermissionGranted = mutableStateOf(false)
    val isMediaCaptureEnabled = mutableStateOf(false)
    val currentSourceMode = mutableStateOf(AudioSourceMode.MICROPHONE)
    val isSystemDspOn = mutableStateOf(SystemDspManager.isEnabled)

    /** 耳機是否連接（由 MainActivity 的裝置回呼更新）；影音 DSP 開關依此啟用/停用。 */
    val isHeadphoneConnected = mutableStateOf(false)

    // DSP Testing & Diagnostics State
    val testDcBlockerEnabled = mutableStateOf(true)
    val testNoiseReductionEnabled = mutableStateOf(true)
    val testCrossoverWdrcEnabled = mutableStateOf(true)
    val testLimiterEnabled = mutableStateOf(true)
    val testTransientSuppressorEnabled = mutableStateOf(true)
    val testOwnVoiceDetectorEnabled = mutableStateOf(true)
    val testFrequencyLoweringEnabled = mutableStateOf(false)   // 移頻預設關閉

    /** 套用處方後，若高頻（≥3 kHz）增益被上限截斷 → 建議開啟 NLFC（Rule 4 的 UI 化）。 */
    val suggestNlfc = mutableStateOf(false)

    val testMasterGain = mutableStateOf(1.0f)
    val testInputGainOffset = mutableStateOf(0.0f)
    val testWdrcExpanderThreshold = mutableStateOf(-72.0f)
    val testLimiterThreshold = mutableStateOf(-1.5f)
    val testLimiterRelease = mutableStateOf(30.0f)
    val testSharingModeOverride = mutableStateOf(0)
    val testInputPresetOverride = mutableStateOf(4)

    val diagInputLevel = mutableStateOf(-120.0f)
    val diagOutputLevel = mutableStateOf(-120.0f)
    val diagWouldBlockRate = mutableStateOf(0.0f)
    val diagInputXRun = mutableStateOf(0)
    val diagOutputXRun = mutableStateOf(0)
    val isDiagnosticsActive = mutableStateOf(false)

    fun setSystemDspEnabled(enabled: Boolean) {
        // We just call the manager; the flow collector below will update isSystemDspOn
        SystemDspManager.setEnabled(enabled)
    }

    // Frequencies
    val centerFrequencies16 = listOf(250, 315, 400, 500, 630, 800, 1000, 1250, 1600, 2000, 2500, 3150, 4000, 5000, 6300, 8000)

    // ── 8 段檢視層（對齊聽力檢查頻率）────────────────────────────────
    // 底層儲存與 DSP 永遠是 16 段；8 段只是 UI 檢視。分組邊界取相鄰
    // 中心頻率的幾何平均（例：√(2000×3000)=2449，故 2500 歸 3000 組）。
    val centerFrequencies8 = listOf(250, 500, 1000, 2000, 3000, 4000, 6000, 8000)
    val band8To16: List<List<Int>> = listOf(
        listOf(0, 1),        // 250:  250, 315
        listOf(2, 3, 4),     // 500:  400, 500, 630
        listOf(5, 6, 7),     // 1000: 800, 1000, 1250
        listOf(8, 9),        // 2000: 1600, 2000
        listOf(10, 11),      // 3000: 2500, 3150
        listOf(12),          // 4000: 4000
        listOf(13, 14),      // 6000: 5000, 6300
        listOf(15)           // 8000: 8000
    )
    val band16ToGroup8: IntArray = IntArray(16).also { arr ->
        band8To16.forEachIndexed { g, members -> members.forEach { arr[it] = g } }
    }

    /** 8 段 slider 顯示值 = 該組 16 段子頻段的平均增益 */
    fun band8Gain(gains16: List<MutableState<Float>>, band8Index: Int): Float {
        val members = band8To16[band8Index]
        return members.map { gains16[it].value }.sum() / members.size
    }

    /** 一支 8 段 slider 同時驅動它管轄的所有 16 段子頻段 */
    fun updateBand8Gain(ear: EarType, band8Index: Int, gain: Float) {
        band8To16[band8Index].forEach { updateBandGain(ear, it, gain) }
    }

    val currentEarTab = mutableStateOf(EarType.BOTH)

    private val _bandGainsLeft16 = List(centerFrequencies16.size) { mutableStateOf(0f) }
    val bandGainsLeft16: List<MutableState<Float>> = _bandGainsLeft16

    private val _bandGainsRight16 = List(centerFrequencies16.size) { mutableStateOf(0f) }
    val bandGainsRight16: List<MutableState<Float>> = _bandGainsRight16

    // Keep compatibility for other screens
    val bandGains16: List<MutableState<Float>> = _bandGainsLeft16

    private val saveJobsLeft = arrayOfNulls<kotlinx.coroutines.Job>(16)
    private val saveJobsRight = arrayOfNulls<kotlinx.coroutines.Job>(16)

    init {
        var isFirstLeft = true
        // Collect Left Channel Gains
        viewModelScope.launch {
            repository.getBandGainsFlow("left", 0, 16).collect { savedGains16 ->
                savedGains16.forEachIndexed { index, gain -> 
                    if (saveJobsLeft[index]?.isActive != true) {
                        if (isFirstLeft || _bandGainsLeft16[index].value != gain) {
                            _bandGainsLeft16[index].value = gain 
                            HarkAudioBridge.setBandGain(0, index, gain)
                            SystemDspManager.updateBandGain(0, index, gain)
                        }
                    }
                }
                isFirstLeft = false
                isDataLoaded.value = true
            }
        }

        var isFirstRight = true
        // Collect Right Channel Gains
        viewModelScope.launch {
            repository.getBandGainsFlow("right", 0, 16).collect { savedGains16 ->
                savedGains16.forEachIndexed { index, gain -> 
                    if (saveJobsRight[index]?.isActive != true) {
                        if (isFirstRight || _bandGainsRight16[index].value != gain) {
                            _bandGainsRight16[index].value = gain 
                            HarkAudioBridge.setBandGain(1, index, gain)
                            SystemDspManager.updateBandGain(1, index, gain)
                        }
                    }
                }
                isFirstRight = false
            }
        }

        viewModelScope.launch {
            SystemDspManager.isEnabledFlow.collect { enabled ->
                isSystemDspOn.value = enabled
            }
        }

        // NLFC 持久化狀態 → UI 與引擎（引擎未跑時 JNI 只是暫存參數，無害）
        viewModelScope.launch {
            repository.getFrequencyLoweringFlow().collect { enabled ->
                if (testFrequencyLoweringEnabled.value != enabled) {
                    testFrequencyLoweringEnabled.value = enabled
                    HarkAudioBridge.setFrequencyLoweringParams(4500f, 2.0f)
                    HarkAudioBridge.setFrequencyLoweringEnabled(enabled)
                }
            }
        }

    // Observe SceneManager if service is running
    viewModelScope.launch {
        while(true) {
            HarkAudioService.sceneManager?.let { sm ->
                situationalMode.value = sm.currentMode.value
                isAutoLocked.value = sm.isAutoLocked.value
            }
            kotlinx.coroutines.delay(500)
        }
    }

    // Diagnostics Polling loop (Only runs when diagnostics screen is active)
    viewModelScope.launch {
        while(true) {
            if (isDiagnosticsActive.value && HarkAudioBridge.isEngineActuallyRunning()) {
                try {
                    val metrics = HarkAudioBridge.getDiagnosticMetrics()
                    if (metrics.size >= 5) {
                        diagInputLevel.value = metrics[0]
                        diagOutputLevel.value = metrics[1]
                        diagWouldBlockRate.value = metrics[2]
                        diagInputXRun.value = metrics[3].toInt()
                        diagOutputXRun.value = metrics[4].toInt()
                    }
                    refreshAllDspParameters()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            } else {
                diagInputLevel.value = -120.0f
                diagOutputLevel.value = -120.0f
                diagWouldBlockRate.value = 0.0f
                diagInputXRun.value = 0
                diagOutputXRun.value = 0
            }
            kotlinx.coroutines.delay(500)
        }
    }
}

fun setDiagnosticsActive(active: Boolean) {
    isDiagnosticsActive.value = active
    if (active) {
        refreshAllDspParameters()
    }
}

fun refreshAllDspParameters() {
    if (HarkAudioBridge.isEngineActuallyRunning()) {
        try {
            testDcBlockerEnabled.value = HarkAudioBridge.isDcBlockerEnabled()
            testNoiseReductionEnabled.value = HarkAudioBridge.isNoiseReductionEnabled()
            testCrossoverWdrcEnabled.value = HarkAudioBridge.isCrossoverWdrcEnabled()
            testLimiterEnabled.value = HarkAudioBridge.isLimiterEnabled()
            testTransientSuppressorEnabled.value = HarkAudioBridge.isTransientSuppressorEnabled()
            testOwnVoiceDetectorEnabled.value = HarkAudioBridge.isOwnVoiceDetectorEnabled()
            testFrequencyLoweringEnabled.value = HarkAudioBridge.isFrequencyLoweringEnabled()

            testMasterGain.value = HarkAudioBridge.getMasterGain()
            testInputGainOffset.value = HarkAudioBridge.getInputGainOffset()
            testWdrcExpanderThreshold.value = HarkAudioBridge.getWdrcExpanderThreshold()
            testLimiterThreshold.value = HarkAudioBridge.getLimiterThreshold()
            testLimiterRelease.value = HarkAudioBridge.getLimiterRelease()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

fun selectSituationalMode(mode: SceneManager.Mode) {
    HarkAudioService.sceneManager?.selectModeManual(mode)
    if (mode == SceneManager.Mode.AUTO) {
        isAutoLocked.value = false
    } else {
        isAutoLocked.value = true
        situationalMode.value = mode
    }
}



    fun toggleHeadsetMic(useHeadset: Boolean) {
        useHeadsetMic.value = useHeadset
        // 注意：這需要觸發 MainActivity 重新偵測設備，
        // 在 MainActivity 中我們會透過觀察這個值或手動觸發來達成。
    }

    fun updateBandGain(ear: EarType, bandIndex: Int, gain: Float) {
        if (bandIndex in 0 until 16) {
            val coercedGain = gain.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
            
            if (ear == EarType.LEFT || ear == EarType.BOTH) {
                if (_bandGainsLeft16[bandIndex].value != coercedGain) {
                    _bandGainsLeft16[bandIndex].value = coercedGain
                    HarkAudioBridge.setBandGain(0, bandIndex, coercedGain)
                    SystemDspManager.updateBandGain(0, bandIndex, coercedGain)
                    
                    saveJobsLeft[bandIndex]?.cancel()
                    saveJobsLeft[bandIndex] = viewModelScope.launch {
                        kotlinx.coroutines.delay(200)
                        repository.saveBandGain("left", 0, bandIndex, coercedGain)
                        com.wcy.hark.util.FirebaseHelper.logEqAdjustment(bandIndex, coercedGain)
                    }
                }
            }
            
            if (ear == EarType.RIGHT || ear == EarType.BOTH) {
                if (_bandGainsRight16[bandIndex].value != coercedGain) {
                    _bandGainsRight16[bandIndex].value = coercedGain
                    HarkAudioBridge.setBandGain(1, bandIndex, coercedGain)
                    SystemDspManager.updateBandGain(1, bandIndex, coercedGain)
                    
                    saveJobsRight[bandIndex]?.cancel()
                    saveJobsRight[bandIndex] = viewModelScope.launch {
                        kotlinx.coroutines.delay(200)
                        repository.saveBandGain("right", 0, bandIndex, coercedGain)
                    }
                }
            }
        }
    }

    fun updateBandGain(bandIndex: Int, gain: Float) {
        updateBandGain(EarType.BOTH, bandIndex, gain)
    }

    fun resetCurrentModeBands() {
        val ear = currentEarTab.value
        if (ear == EarType.LEFT || ear == EarType.BOTH) {
            _bandGainsLeft16.forEach { it.value = 0f }
            _bandGainsLeft16.forEachIndexed { index, _ -> HarkAudioBridge.setBandGain(0, index, 0f) }
        }
        if (ear == EarType.RIGHT || ear == EarType.BOTH) {
            _bandGainsRight16.forEach { it.value = 0f }
            _bandGainsRight16.forEachIndexed { index, _ -> HarkAudioBridge.setBandGain(1, index, 0f) }
        }
        viewModelScope.launch {
            if (ear == EarType.BOTH) {
                repository.resetBands(0, 16)
            } else if (ear == EarType.LEFT) {
                for (i in 0 until 16) {
                    repository.saveBandGain("left", 0, i, 0f)
                }
            } else {
                for (i in 0 until 16) {
                    repository.saveBandGain("right", 0, i, 0f)
                }
            }
        }
    }

    // --- DSP Test Setters ---
    fun setTestDcBlockerEnabled(enabled: Boolean) {
        testDcBlockerEnabled.value = enabled
        HarkAudioBridge.setDcBlockerEnabled(enabled)
    }

    fun setTestNoiseReductionEnabled(enabled: Boolean) {
        testNoiseReductionEnabled.value = enabled
        HarkAudioBridge.setNoiseReductionEnabled(enabled)
    }

    fun setTestCrossoverWdrcEnabled(enabled: Boolean) {
        testCrossoverWdrcEnabled.value = enabled
        HarkAudioBridge.setCrossoverWdrcEnabled(enabled)
    }

    fun setTestLimiterEnabled(enabled: Boolean) {
        testLimiterEnabled.value = enabled
        HarkAudioBridge.setLimiterEnabled(enabled)
    }

    fun setTestTransientSuppressorEnabled(enabled: Boolean) {
        testTransientSuppressorEnabled.value = enabled
        HarkAudioBridge.setTransientSuppressorEnabled(enabled)
    }

    fun setTestOwnVoiceDetectorEnabled(enabled: Boolean) {
        testOwnVoiceDetectorEnabled.value = enabled
        HarkAudioBridge.setOwnVoiceDetectorEnabled(enabled)
    }

    fun setTestFrequencyLoweringEnabled(enabled: Boolean) {
        testFrequencyLoweringEnabled.value = enabled
        // 針對華語高頻擦音：cutoff 4.5 kHz、壓縮比 2:1
        HarkAudioBridge.setFrequencyLoweringParams(4500f, 2.0f)
        HarkAudioBridge.setFrequencyLoweringEnabled(enabled)
        // 持久化：HarkAudioService 每次啟動引擎時會讀取並重新套用
        viewModelScope.launch { repository.saveFrequencyLoweringEnabled(enabled) }
    }

    fun setTestMasterGain(gain: Float) {
        testMasterGain.value = gain
        HarkAudioBridge.setMasterGain(gain)
    }

    fun setTestInputGainOffset(offsetDb: Float) {
        testInputGainOffset.value = offsetDb
        HarkAudioBridge.setInputGainOffset(offsetDb)
    }

    fun setTestWdrcExpanderThreshold(thresholdDb: Float) {
        testWdrcExpanderThreshold.value = thresholdDb
        HarkAudioBridge.setWdrcExpanderThreshold(thresholdDb)
    }

    fun setTestLimiterParameters(thresholdDb: Float, releaseMs: Float) {
        testLimiterThreshold.value = thresholdDb
        testLimiterRelease.value = releaseMs
        HarkAudioBridge.setLimiterParameters(thresholdDb, releaseMs)
    }



    fun applyStreamOverrides(sharingMode: Int, inputPreset: Int) {
        testSharingModeOverride.value = sharingMode
        testInputPresetOverride.value = inputPreset
        HarkAudioBridge.setStreamOverrides(sharingMode, inputPreset)
        // Safely restart Oboe stream asynchronously via Kotlin Coroutines
        viewModelScope.launch {
            statusText.value = "狀態：正在套用物理配置..."
            if (HarkAudioBridge.isEngineActuallyRunning()) {
                HarkAudioBridge.stopEngine()
                kotlinx.coroutines.delay(400) // Give hardware a moment to settle
                HarkAudioBridge.startEngine()
            }
            statusText.value = "狀態：已套用物理配置並重啟"
        }
    }

    /**
     * 依儲存的聽力圖套用處方增益（DSL v5 by Hand 成人近似 或 NAL-R）。
     * 缺測頻率以鄰近已測值於對數頻率軸內插；計算細節見
     * docs/FITTING_PRESCRIPTIONS.md。完成後以 onResult 回報摘要訊息。
     */
    fun applyFitting(method: Prescriptions.Method, onResult: (String) -> Unit) {
        viewModelScope.launch {
            val testFrequencies = listOf(250, 500, 1000, 2000, 3000, 4000, 6000, 8000)
            suspend fun loadAudiogram(ear: String): Map<Int, Float> = buildMap {
                for (freq in testFrequencies) {
                    val t = repository.getAudiogramThresholdFlow(ear, freq).first()
                    if (t != -1) put(freq, t.toFloat())
                }
            }
            val leftAudiogram = loadAudiogram("left")
            val rightAudiogram = loadAudiogram("right")

            if (leftAudiogram.isEmpty() && rightAudiogram.isEmpty()) {
                onResult("沒有聽力圖資料，請先完成純音聽力測驗")
                return@launch
            }

            // 雙耳皆有聽力圖 → DSL v5 套用雙耳配戴 −3 dB 修正
            val binaural = leftAudiogram.isNotEmpty() && rightAudiogram.isNotEmpty()
            var clampedBands = 0
            var clampedHighFreqBands = 0   // ≥3 kHz 的截斷頻段（NLFC 建議依據）

            fun applyEar(ear: EarType, audiogram: Map<Int, Float>) {
                if (audiogram.isEmpty()) return
                // NAL-R 的 X 項：500/1k/2k 三頻平均（缺測時同樣內插）
                val h3fa = listOf(500, 1000, 2000)
                    .mapNotNull { Prescriptions.interpolateThreshold(it, audiogram) }
                    .average().toFloat()
                centerFrequencies16.forEachIndexed { index, freq ->
                    val threshold = Prescriptions.interpolateThreshold(freq, audiogram) ?: return@forEachIndexed
                    val gain = when (method) {
                        Prescriptions.Method.DSL_V5 -> Prescriptions.dslV5Gain(freq, threshold, binaural)
                        Prescriptions.Method.NAL_R -> Prescriptions.nalRGain(freq, threshold, h3fa)
                    }
                    if (gain > MAX_GAIN_DB) {
                        clampedBands++
                        if (freq >= 3000) clampedHighFreqBands++
                    }
                    updateBandGain(ear, index, gain.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB))
                }
            }
            applyEar(EarType.LEFT, leftAudiogram)
            applyEar(EarType.RIGHT, rightAudiogram)

            val name = if (method == Prescriptions.Method.DSL_V5) "DSL v5" else "NAL-R"
            val ears = when {
                binaural -> "雙耳"
                leftAudiogram.isNotEmpty() -> "左耳（右耳無聽力圖）"
                else -> "右耳（左耳無聽力圖）"
            }
            val msg = buildString {
                append("已套用 $name 處方（$ears）")
                if (clampedBands > 0) {
                    append("\n⚠️ ${clampedBands} 個頻段已達增益上限 ${MAX_GAIN_DB.toInt()} dB，未能完全達到處方目標")
                }
            }
            // Rule 4（漸進式補償）：高頻增益補不到位時，才建議引入移頻
            if (clampedHighFreqBands > 0 && !testFrequencyLoweringEnabled.value) {
                suggestNlfc.value = true
            }
            onResult(msg)
        }
    }
}

class EqViewModelFactory(private val repository: EqSettingsRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(EqViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return EqViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
