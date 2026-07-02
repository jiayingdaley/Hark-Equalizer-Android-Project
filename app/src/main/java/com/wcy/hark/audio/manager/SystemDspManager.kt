package com.wcy.hark.audio.manager

import android.media.audiofx.DynamicsProcessing
import android.util.Log

/**
 * SystemDspManager handles attaching Android's native DynamicsProcessing
 * to external AudioSessionIds (like Spotify) or Session 0 (Global Mix Fallback).
 */
object SystemDspManager {
    private const val TAG = "SystemDspManager"

    // Maintain active DSP instances mapped by Audio Session ID
    private val activeEffects = mutableMapOf<Int, DynamicsProcessing>()

    // Current settings
    private val _isEnabledFlow = kotlinx.coroutines.flow.MutableStateFlow(false)
    val isEnabledFlow: kotlinx.coroutines.flow.StateFlow<Boolean> = _isEnabledFlow
    val isEnabled: Boolean get() = _isEnabledFlow.value

    private var bandGains16Left = FloatArray(16) { 0f }
    private var bandGains16Right = FloatArray(16) { 0f }
    
    // UI 16 Band center frequencies
    private val UI_CENTER_FREQS = floatArrayOf(
        250f, 315f, 400f, 500f, 630f, 800f, 1000f, 1250f,
        1600f, 2000f, 2500f, 3150f, 4000f, 5000f, 6300f, 8000f
    )

    /**
     * Toggles whether the systemic DSP should be active.
     */
    fun setEnabled(enabled: Boolean) {
        _isEnabledFlow.value = enabled
        activeEffects.values.forEach { 
            try { it.enabled = enabled } catch (e: Exception) { Log.e(TAG, "Error enabling DP", e) }
        }
    }

    /**
     * Attaches DynamicsProcessing to a specific audio session.
     */
    fun attachToSession(sessionId: Int, forceEnabled: Boolean = false) {
        if (activeEffects.containsKey(sessionId)) return

        try {
            val builder = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                2, // channels
                true, // enable PreEQ
                16,   // number of bands for PreEQ
                true, // enable MBC (Multi-Band Compressor)
                8,    // number of bands for MBC
                false, // enable PostEQ
                0,    // postEQ bands
                true  // enable Limiter
            )

            val config = builder.build()
            val dp = DynamicsProcessing(0, sessionId, config)
            dp.enabled = if (forceEnabled) true else _isEnabledFlow.value

            // Setup 16-Band Pre-EQ (Left: channel 0, Right: channel 1)
            for (i in 0 until 16) {
                dp.setPreEqBandByChannelIndex(0, i, DynamicsProcessing.EqBand(true, UI_CENTER_FREQS[i], bandGains16Left[i]))
                dp.setPreEqBandByChannelIndex(1, i, DynamicsProcessing.EqBand(true, UI_CENTER_FREQS[i], bandGains16Right[i]))
            }

            // Setup WDRC as MBC (8 Bands)
            val mbcFreqs = floatArrayOf(500f, 1000f, 1500f, 2500f, 4500f, 6000f, 8000f, 12000f)
            for (i in 0 until 8) {
                // MbcBand(enabled, cutoffFreq, attackTime, releaseTime, ratio, threshold, kneeWidth, noiseGateThreshold, expanderRatio, preGain, postGain)
                val mbcBand = DynamicsProcessing.MbcBand(true, mbcFreqs[i], 8.0f, 10.0f, 1.5f, -30.0f, 0.0f, -90.0f, 1.0f, 1.0f, 0.0f)
                dp.setMbcBandAllChannelsTo(i, mbcBand)
            }

            // Setup Limiter: Limiter(inUse, enabled, linkGroup, attackTime, releaseTime, ratio, threshold, postGain)
            val limiter = DynamicsProcessing.Limiter(true, true, 0, 0.5f, 30.0f, 10.0f, -2.0f, 1.0f)
            dp.setLimiterAllChannelsTo(limiter)
            
            activeEffects[sessionId] = dp
            Log.d(TAG, "Successfully attached DynamicsProcessing to Session $sessionId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach DynamicsProcessing to session $sessionId: ${e.message}")
        }
    }

    /**
     * Detaches DynamicsProcessing from a specific audio session.
     */
    fun detachFromSession(sessionId: Int) {
        activeEffects.remove(sessionId)?.let {
            try {
                it.enabled = false
                it.release()
                Log.d(TAG, "Released DynamicsProcessing for Session $sessionId")
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing DP", e)
            }
        }
    }

    /**
     * Update EQ band gain (index 0-15).
     */
    fun updateBandGain(ear: Int, bandIndex: Int, gainDb: Float) {
        if (bandIndex !in 0..15) return
        if (ear == 0) {
            bandGains16Left[bandIndex] = gainDb
        } else {
            bandGains16Right[bandIndex] = gainDb
        }

        activeEffects.values.forEach { dp ->
            try {
                dp.setPreEqBandByChannelIndex(ear, bandIndex, DynamicsProcessing.EqBand(true, UI_CENTER_FREQS[bandIndex], gainDb))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update DP band gain (ear=$ear)", e)
            }
        }
    }

    fun clearAllEffects() {
        activeEffects.values.forEach { 
            try { 
                it.enabled = false
                it.release() 
            } catch (e: Exception) {} 
        }
        activeEffects.clear()
        Log.d(TAG, "Cleared all global DSP effects")
    }
}
