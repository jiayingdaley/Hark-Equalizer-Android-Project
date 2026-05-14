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

class EqViewModel(private val repository: EqSettingsRepository) : ViewModel() {

    enum class EngineMode {
        BIQUAD_16_MIC,
        BIQUAD_8_MIC
    }

    companion object {
        const val MAX_GAIN_DB = 24f
        const val MIN_GAIN_DB = -24f
        const val DEFAULT_Q: Float = 1.4f
    }

    val currentMode = mutableStateOf(EngineMode.BIQUAD_16_MIC)
    val statusText = mutableStateOf("狀態：已停用")
    val isDataLoaded = mutableStateOf(false)

    // Situational Mode State
    val situationalMode = mutableStateOf(SceneManager.Mode.TRANSPARENCY)
    val isAutoLocked = mutableStateOf(false)
    val pinnaEnabled = mutableStateOf(true)

    // Frequencies
    val centerFrequencies16 = listOf(250, 315, 400, 500, 630, 800, 1000, 1250, 1600, 2000, 2500, 3150, 4000, 5000, 6300, 8000)
    
    private val _bandGains16 = List(centerFrequencies16.size) { mutableStateOf(0f) }
    val bandGains16: List<MutableState<Float>> = _bandGains16

    init {
        viewModelScope.launch {
            val savedGains16 = repository.getBandGainsFlow(0, 16).first()
            savedGains16.forEachIndexed { index, gain -> 
                _bandGains16[index].value = gain 
                HarkAudioBridge.setBandGain(index, gain)
            }
            isDataLoaded.value = true
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
    }

    fun selectSituationalMode(mode: SceneManager.Mode) {
        HarkAudioService.sceneManager?.selectModeManual(mode)
        situationalMode.value = mode
    }

    fun togglePinna(enabled: Boolean) {
        pinnaEnabled.value = enabled
        HarkAudioBridge.setPinnaEnabled(enabled)
    }

    fun updateBandGain(bandIndex: Int, gain: Float) {
        if (bandIndex in 0 until 16) {
            val coercedGain = gain.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
            _bandGains16[bandIndex].value = coercedGain
            HarkAudioBridge.setBandGain(bandIndex, coercedGain)
            viewModelScope.launch {
                repository.saveBandGain(0, bandIndex, coercedGain)
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
