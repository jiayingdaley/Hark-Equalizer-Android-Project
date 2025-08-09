package com.example.hark

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel

class EqViewModel : ViewModel() {

    enum class EngineMode {
        BIQUAD_16_MIC,
        BIQUAD_8_MIC
    }

    companion object {
        // 定義增益範圍，方便在 UI 中使用
        const val MAX_GAIN_DB = 12f // 示例最大增益
        const val MIN_GAIN_DB = -12f // 示例最小增益
        const val DEFAULT_Q: Float = 1.8f
    }

    val currentMode = mutableStateOf(EngineMode.BIQUAD_16_MIC)
    val statusText = mutableStateOf("狀態：已停用")

    // 中心頻率 (這些可以保持不變，因為它們只是標籤)
    val centerFrequencies16 = listOf(250, 315, 400, 500, 630, 800, 1000, 1250, 1600, 2000, 2500, 3150, 4000, 5000, 6300, 8000)
    val centerFrequencies8 = listOf(250, 500, 1000, 1500, 2000, 4000, 6000, 8000)

    // --- 修改開始 ---

    // 為 16-band 模式獨立管理增益
    private val _bandGains16 = List(centerFrequencies16.size) { mutableStateOf(0f) }
    val bandGains16: List<MutableState<Float>> = _bandGains16

    // 為 8-band 模式獨立管理增益
    private val _bandGains8 = List(centerFrequencies8.size) { mutableStateOf(0f) }
    val bandGains8: List<MutableState<Float>> = _bandGains8

    // 為 16-band 模式獨立管理 Q 值
    private val _bandQs16 = List(centerFrequencies16.size) { mutableStateOf(DEFAULT_Q) }
    val bandQs16: List<MutableState<Float>> = _bandQs16

    // 為 8-band 模式獨立管理 Q 值
    private val _bandQs8 = List(centerFrequencies8.size) { mutableStateOf(DEFAULT_Q) }
    val bandQs8: List<MutableState<Float>> = _bandQs8


    /**
     * 根據當前 [currentMode] 返回對應的增益列表。
     * UI 和其他邏輯應該觀察這個屬性來獲取和顯示增益。
     */
    val currentBandGains: List<MutableState<Float>>
        get() = if (currentMode.value == EngineMode.BIQUAD_16_MIC) bandGains16 else bandGains8

    /**
     * 根據當前 [currentMode] 返回對應的 Q 值列表。
     */
    val currentBandQs: List<MutableState<Float>>
        get() = if (currentMode.value == EngineMode.BIQUAD_16_MIC) bandQs16 else bandQs8

    /**
     * 根據當前 [currentMode] 返回對應的中心頻率列表。
     */
    val currentCenterFrequencies: List<Int>
        get() = if (currentMode.value == EngineMode.BIQUAD_16_MIC) centerFrequencies16 else centerFrequencies8

    /**
     * 更新指定頻段的增益值。
     * 這個方法會自動更新當前模式對應的增益列表。
     * @param bandIndex 頻段的索引 (0 到 N-1)。
     * @param gain 新的增益值。
     */
    fun updateBandGain(bandIndex: Int, gain: Float) {
        val gains = currentBandGains
        if (bandIndex >= 0 && bandIndex < gains.size) {
            gains[bandIndex].value = gain.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
        }
    }

    /**
     * 更新指定頻段的 Q 值。
     * @param bandIndex 頻段的索引。
     * @param q 新的 Q 值。
     */
    fun updateBandQ(bandIndex: Int, q: Float) {
        val qs = currentBandQs
        if (bandIndex >= 0 && bandIndex < qs.size) {
            // 在這裡可以添加 Q 值的範圍限制，如果需要的話
            qs[bandIndex].value = q
        }
    }

    /**
     * 重設當前模式下所有頻段的增益為 0dB，Q 值為預設值。
     */
    fun resetCurrentModeBands() {
        currentBandGains.forEach { it.value = 0f }
        currentBandQs.forEach { it.value = DEFAULT_Q }
    }

}
