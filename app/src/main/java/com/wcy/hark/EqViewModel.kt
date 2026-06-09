package com.wcy.hark

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.wcy.hark.audio.HarkAudioBridge
import com.wcy.hark.audio.HarkAudioService
import com.wcy.hark.audio.SceneManager
import com.wcy.hark.data.EqSettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

enum class AudioSourceMode {
    MICROPHONE,
    INTERNAL_MEDIA
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
    val isSystemDspOn = mutableStateOf(com.wcy.hark.audio.SystemDspManager.isEnabled)

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

    fun setSystemDspEnabled(enabled: Boolean) {
        // We just call the manager; the flow collector below will update isSystemDspOn
        com.wcy.hark.audio.SystemDspManager.setEnabled(enabled)
    }

    // Frequencies
    val centerFrequencies16 = listOf(250, 315, 400, 500, 630, 800, 1000, 1250, 1600, 2000, 2500, 3150, 4000, 5000, 6300, 8000)
    
    private val _bandGains16 = List(centerFrequencies16.size) { mutableStateOf(0f) }
    val bandGains16: List<MutableState<Float>> = _bandGains16

    private val saveJobs = arrayOfNulls<kotlinx.coroutines.Job>(16)

    init {
        viewModelScope.launch {
            repository.getBandGainsFlow(0, 16).collect { savedGains16 ->
                savedGains16.forEachIndexed { index, gain -> 
                    // Only apply value from database if we are NOT currently dragging/debouncing it!
                    if (saveJobs[index]?.isActive != true) {
                        if (_bandGains16[index].value != gain) {
                            _bandGains16[index].value = gain 
                            HarkAudioBridge.setBandGain(index, gain)
                            com.wcy.hark.audio.SystemDspManager.updateBandGain(index, gain)
                        }
                    }
                }
                isDataLoaded.value = true
            }
        }

        viewModelScope.launch {
            com.wcy.hark.audio.SystemDspManager.isEnabledFlow.collect { enabled ->
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

        // Diagnostics Polling loop
        viewModelScope.launch {
            while(true) {
                if (HarkAudioBridge.isEngineActuallyRunning()) {
                    try {
                        val metrics = HarkAudioBridge.getDiagnosticMetrics()
                        if (metrics.size >= 5) {
                            diagInputLevel.value = metrics[0]
                            diagOutputLevel.value = metrics[1]
                            diagWouldBlockRate.value = metrics[2]
                            diagInputXRun.value = metrics[3].toInt()
                            diagOutputXRun.value = metrics[4].toInt()
                        }
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

    fun selectSituationalMode(mode: SceneManager.Mode) {
        HarkAudioService.sceneManager?.selectModeManual(mode)
        situationalMode.value = mode
    }



    fun toggleHeadsetMic(useHeadset: Boolean) {
        useHeadsetMic.value = useHeadset
        // 注意：這需要觸發 MainActivity 重新偵測設備，
        // 在 MainActivity 中我們會透過觀察這個值或手動觸發來達成。
    }

    fun updateBandGain(bandIndex: Int, gain: Float) {
        if (bandIndex in 0 until 16) {
            val coercedGain = gain.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
            // Only update memory state if it actually changed, to prevent redundant recompositions
            if (_bandGains16[bandIndex].value != coercedGain) {
                _bandGains16[bandIndex].value = coercedGain
                HarkAudioBridge.setBandGain(bandIndex, coercedGain)
                com.wcy.hark.audio.SystemDspManager.updateBandGain(bandIndex, coercedGain)
                
                // Debounce DataStore saving to prevent UI rebounding during drag
                saveJobs[bandIndex]?.cancel()
                saveJobs[bandIndex] = viewModelScope.launch {
                    kotlinx.coroutines.delay(200)
                    repository.saveBandGain(0, bandIndex, coercedGain)
                }
            }
        }
    }

    fun resetCurrentModeBands() {
        _bandGains16.forEach { it.value = 0f }
        _bandGains16.forEachIndexed { index, _ -> HarkAudioBridge.setBandGain(index, 0f) }
        viewModelScope.launch {
            repository.resetBands(0, 16)
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
