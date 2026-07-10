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
    external fun setBandGain(ear: Int, bandIndex: Int, gainDb: Float)
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

    // 非線性頻率壓縮（移頻）：使用者可選開關；cutoffHz 起始壓縮頻率、ratio 壓縮比
    external fun setFrequencyLoweringEnabled(enabled: Boolean)
    external fun setFrequencyLoweringParams(cutoffHz: Float, ratio: Float)
    external fun isFrequencyLoweringEnabled(): Boolean

    /**
     * 離線 NLFC：對整段測試音套用與即時引擎相同的移頻演算法（獨立實例），
     * 供語詞測驗做移頻效益的行為驗證。輸入輸出等長、時間對齊。
     */
    external fun nlfcProcessOffline(
        input: FloatArray, sampleRate: Int, cutoffHz: Float, ratio: Float
    ): FloatArray

    external fun setWdrcExpanderThreshold(thresholdDb: Float)
    external fun setLimiterParameters(thresholdDb: Float, releaseMs: Float)
    external fun setStreamOverrides(sharingMode: Int, inputPreset: Int)

    /**
     * Returns 6-element FloatArray:
     * [0] Raw mic input peak (dBFS)
     * [1] Output peak (dBFS)
     * [2] WouldBlock rate (%)
     * [3] Input XRuns (count)
     * [4] Output XRuns (count)
     * [5] Post-InputGain-Compensation peak (dBFS)  ← NEW tap point
     */
    external fun getDiagnosticMetrics(): FloatArray

    // --- Experiment Signal Generators ---
    // These inject signals directly in the Oboe audio callback, bypassing
    // all DSP modules for accurate acoustic measurement.

    /**
     * Activate / update / stop a fixed-frequency calibration sine tone.
     * While active, the engine outputs ONLY this tone (mic is silenced).
     * @param freqHz     Tone frequency (250/500/1000/2000/3000/4000/6000/8000 Hz)
     * @param levelDbfs  Output amplitude (-40 to 0 dBFS)
     * @param enabled    true = start/update, false = stop
     */
    external fun setCalibTone(freqHz: Float, levelDbfs: Float, enabled: Boolean)

    /**
     * Start or stop a log-swept sine chirp (ANSI S3.22 swept pure tone).
     * Frequency increases exponentially from startHz to endHz over durationSec.
     * @param startHz     Sweep start frequency (Hz), typically 250
     * @param endHz       Sweep end frequency (Hz), typically 8000
     * @param durationSec Total sweep duration in seconds (10–60)
     * @param levelDbfs   Output amplitude (-40 to 0 dBFS)
     * @param enabled     true = start from beginning, false = stop
     */
    external fun setLogChirp(
        startHz: Float, endHz: Float,
        durationSec: Float, levelDbfs: Float,
        enabled: Boolean
    )

    /**
     * Start or stop pink noise output (optional OSPL90 source).
     * Uses Voss-McCartney 8-octave algorithm.
     * @param levelDbfs Output amplitude (-40 to 0 dBFS)
     * @param enabled   true = generate, false = stop
     */
    external fun setPinkNoise(levelDbfs: Float, enabled: Boolean)

    /**
     * Controls the overall academic experiment mode active state in the engine.
     */
    external fun setExperimentModeActive(active: Boolean)

    /**
     * Set to true to inject calibration signals into the DSP pipeline instead of bypassing it.
     */
    external fun setInjectDspMode(inject: Boolean)

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
