package com.wcy.hark.audio.bridge

/**
 * JNI Bridge: Centralizes all native (C++) audio engine function declarations.
 */
object HarkAudioBridge {

    external fun startEngine()
    external fun stopEngine()

    /**
     * Sets the gain for a specific parametric EQ band.
     * Note: In V3, this maps to the pre-WDRC prescription gain.
     */
    external fun setBandGain(bandIndex: Int, gainDb: Float)
    external fun setBandQ(bandIndex: Int, q_factor: Float)

    external fun setAudioInputDeviceId(deviceId: Int)
    external fun isEngineActuallyRunning(): Boolean
    external fun setNoiseReductionEnabled(enabled: Boolean)
    external fun resetGesture()
    external fun logLatencyStatistics()
    external fun calibrateNoiseSuppressor()

    external fun setSituationalMode(mode: Int)

    external fun setBandWdrcParameters(band: Int, thresholdDb: Float, ratio: Float,
                                       attackMs: Float, releaseMs: Float)

    /**
     * 獲取環境噪音能量 (5個頻段: 500, 1k, 2k, 3k, 4kHz).
     */
    external fun getEnvironmentEnergy(): FloatArray

    external fun setBypassMode(bypass: Boolean)
    external fun setMasterGain(gain: Float)
    external fun setMuted(muted: Boolean)
    external fun setInputGainOffset(gainDb: Float)
    external fun setUseHeadsetMic(useHeadset: Boolean)
    external fun setIsBluetoothInput(isBluetooth: Boolean)
    external fun setHeadphonesConnected(connected: Boolean)

    // --- Media Capture Mode ---
    external fun setMediaCaptureMode(enabled: Boolean)
    external fun pushMediaAudioData(data: FloatArray, numFrames: Int)

    // --- Testing & Diagnostics API ---
    external fun setDcBlockerEnabled(enabled: Boolean)
    external fun setCrossoverWdrcEnabled(enabled: Boolean)
    external fun setLimiterEnabled(enabled: Boolean)
    external fun setTransientSuppressorEnabled(enabled: Boolean)
    external fun setOwnVoiceDetectorEnabled(enabled: Boolean)
    external fun setWdrcExpanderThreshold(thresholdDb: Float)
    external fun setLimiterParameters(thresholdDb: Float, releaseMs: Float)
    external fun setStreamOverrides(sharingMode: Int, inputPreset: Int)
    external fun getDiagnosticMetrics(): FloatArray

    // --- State Inspection Getters ---
    external fun isNoiseReductionEnabled(): Boolean
    external fun getMasterGain(): Float
    external fun getInputGainOffset(): Float
    external fun getWdrcExpanderThreshold(): Float
    external fun getLimiterThreshold(): Float
    external fun getLimiterRelease(): Float
    
    external fun isDcBlockerEnabled(): Boolean
    external fun isCrossoverWdrcEnabled(): Boolean
    external fun isLimiterEnabled(): Boolean
    external fun isTransientSuppressorEnabled(): Boolean
    external fun isOwnVoiceDetectorEnabled(): Boolean
}
