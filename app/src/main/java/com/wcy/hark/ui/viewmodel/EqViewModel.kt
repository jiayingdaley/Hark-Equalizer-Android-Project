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
        const val MAX_GAIN_DB = 24f
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

    // DSP Testing & Diagnostics State
    val testDcBlockerEnabled = mutableStateOf(true)
    val testNoiseReductionEnabled = mutableStateOf(true)
    val testCrossoverWdrcEnabled = mutableStateOf(true)
    val testLimiterEnabled = mutableStateOf(true)
    val testTransientSuppressorEnabled = mutableStateOf(true)
    val testOwnVoiceDetectorEnabled = mutableStateOf(true)

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
    situationalMode.value = mode
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
     * Applies DSL v5 compensation formula using saved audiogram thresholds.
     */
    fun applyDslV5Fitting() {
        viewModelScope.launch {
            val leftAudiogram = mutableMapOf<Int, Int>()
            val rightAudiogram = mutableMapOf<Int, Int>()
            val testFrequencies = listOf(250, 500, 1000, 2000, 3000, 4000, 6000, 8000)

            for (freq in testFrequencies) {
                leftAudiogram[freq] = repository.getAudiogramThresholdFlow("left", freq).first()
                rightAudiogram[freq] = repository.getAudiogramThresholdFlow("right", freq).first()
            }

            // Apply to Left channel
            if (leftAudiogram.values.any { it != -1 }) {
                centerFrequencies16.forEachIndexed { index, freq ->
                    val threshold = interpolateThreshold(freq, leftAudiogram)
                    val dslGain = calculateDslV5Gain(threshold)
                    updateBandGain(EarType.LEFT, index, dslGain)
                }
            }

            // Apply to Right channel
            if (rightAudiogram.values.any { it != -1 }) {
                centerFrequencies16.forEachIndexed { index, freq ->
                    val threshold = interpolateThreshold(freq, rightAudiogram)
                    val dslGain = calculateDslV5Gain(threshold)
                    updateBandGain(EarType.RIGHT, index, dslGain)
                }
            }
        }
    }

    private fun calculateDslV5Gain(threshold: Float): Float {
        if (threshold <= 20f) return 0f
        // DSL v5 adult target gain approximation for moderate speech: Gain = 0.35 * (Threshold - 20) + 3.0
        val targetGain = 0.35f * (threshold - 20f) + 3f
        return targetGain.coerceIn(0f, MAX_GAIN_DB)
    }

    private fun interpolateThreshold(targetFreq: Int, audiogram: Map<Int, Int>): Float {
        // Map 16 parametric EQ bands to nearest measured frequencies
        val freqMap = mapOf(
            250 to 250, 315 to 250, 400 to 250,
            500 to 500, 630 to 500, 800 to 500,
            1000 to 1000, 1250 to 1000,
            1600 to 2000, 2000 to 2000,
            2500 to 3000, 3150 to 3000,
            4000 to 4000,
            5000 to 6000, 6300 to 6000,
            8000 to 8000
        )
        val measuredFreq = freqMap[targetFreq] ?: 1000
        val threshold = audiogram[measuredFreq] ?: -1
        return if (threshold == -1) 0f else threshold.toFloat()
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
