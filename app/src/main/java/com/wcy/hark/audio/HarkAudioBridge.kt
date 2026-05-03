package com.wcy.hark.audio

/**
 * JNI Bridge: Centralizes all native (C++) audio engine function declarations.
 *
 * By declaring 'external' functions here instead of in MainActivity, any Kotlin
 * class can invoke the native engine without a direct dependency on the Activity.
 *
 * The native library ("hark") is loaded explicitly in MainActivity.onCreate() via
 * System.loadLibrary("hark") before this object's functions are first called.
 *
 * Native implementation: app/src/main/cpp/native-lib.cpp
 * JNI naming: Java_com_wcy_hark_audio_HarkAudioBridge_[methodName]
 *
 * Ref: Android JNI Tips – https://developer.android.com/training/articles/perf-jni
 */
object HarkAudioBridge {

    /**
     * Starts the Oboe audio streams (input + output) and begins real-time DSP
     * processing. Calls HarkAudioEngine::start() in C++.
     */
    external fun startEngine()

    /**
     * Stops and releases all Oboe audio streams.
     * Safe to call even if the engine is not currently running.
     * Calls HarkAudioEngine::stop() in C++.
     */
    external fun stopEngine()

    /**
     * Sets the gain for a specific parametric EQ band.
     *
     * @param bandIndex 0-based index (0..15) into the 16 peaking filters.
     * @param gainDb    Gain in dB. Negative = cut, Positive = boost.
     */
    external fun setBandGain(bandIndex: Int, gainDb: Float)

    /**
     * Sets the Q factor (selectivity / bandwidth) for a specific EQ band.
     *
     * @param bandIndex 0-based index (0..15).
     * @param q_factor  Higher Q = narrower bandwidth. Default: 1.8f.
     */
    external fun setBandQ(bandIndex: Int, q_factor: Float)

    /**
     * Specifies the Android audio device ID for the Oboe input (microphone) stream.
     * Must be called before startEngine() to take effect.
     *
     * @param deviceId AudioDeviceInfo.getId() value. Use 0 for system default.
     */
    external fun setAudioInputDeviceId(deviceId: Int)

    /**
     * Queries the actual running state of the native audio streams by checking
     * the Oboe stream state directly — more reliable than a Kotlin boolean flag.
     *
     * @return true if the output stream exists and is not in Closed state.
     */
    external fun isEngineActuallyRunning(): Boolean
}
