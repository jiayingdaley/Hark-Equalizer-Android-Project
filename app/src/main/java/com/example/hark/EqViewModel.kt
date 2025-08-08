package com.example.hark

import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.compose.runtime.MutableState

class EqViewModel : ViewModel() {

    enum class EngineMode {
        BIQUAD_16_MIC,
        BIQUAD_8_MIC
    }

    val currentMode = mutableStateOf(EngineMode.BIQUAD_16_MIC)
    val statusText = mutableStateOf("狀態：已停用")

    val centerFrequencies16 = listOf(250, 315, 400, 500, 630, 800, 1000, 1250, 1600, 2000, 2500, 3150, 4000, 5000, 6300, 8000)
    val centerFrequencies8 = listOf(250, 500, 1000, 1500, 2000, 4000, 6000, 8000)

    // Store up to 16 bands of gains and Qs
    val bandGains: List<MutableState<Float>> = List(16) { mutableStateOf(0f) }
    val bandQs: List<MutableState<Float>> = List(16) { mutableStateOf(1.8f) }
}
