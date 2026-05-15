package com.wcy.hark.audio

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

    /**
     * 情境模式切換 (Situational Mode).
     * @param mode 0=TRANSPARENCY, 1=CONVERSATION, 2=OUTDOOR, 3=CINEMA, 4=AUTO
     */
    external fun setSituationalMode(mode: Int)

    /**
     * 耳廓補償開關 (Pinna Restore).
     */
    external fun setPinnaEnabled(enabled: Boolean)

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
}
