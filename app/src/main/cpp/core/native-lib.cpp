#include <jni.h>
#include "HarkAudioEngine.h"

// The single, static instance of our audio engine.
static HarkAudioEngine engine;

extern "C" JNIEXPORT void JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_startEngine(JNIEnv *env, jobject /* this */) {
    engine.start();
}

extern "C" JNIEXPORT void JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_stopEngine(JNIEnv *env, jobject /* this */) {
    engine.stop();
}

extern "C" JNIEXPORT void JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_setBandGain(
        JNIEnv *env, jobject /* this */, jint ear, jint bandIndex, jfloat gainDb) {
    engine.setBandGain(ear, bandIndex, gainDb);
}

extern "C" JNIEXPORT void JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_setBandQ(
        JNIEnv *env, jobject /* this */, jint bandIndex, jfloat q_factor) {
    engine.setBandQ(bandIndex, q_factor);
}

extern "C" JNIEXPORT void JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_setAudioInputDeviceId(
        JNIEnv *env, jobject /* this */, jint device_id) {
    engine.setInputDeviceId(device_id);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_isEngineActuallyRunning(
        JNIEnv *env, jobject /* this */) {
    return (jboolean) engine.isEngineRunning();
}

extern "C" JNIEXPORT void JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_setNoiseReductionEnabled(
        JNIEnv *env, jobject /* this */, jboolean enabled) {
    engine.setNoiseReductionEnabled(enabled);
}

extern "C" JNIEXPORT void JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_resetGesture(
        JNIEnv *env, jobject /* this */) {
    engine.resetGesture();
}

extern "C" JNIEXPORT void JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_logLatencyStatistics(
        JNIEnv *env, jobject /* this */) {
    engine.logLatencyStatistics();
}

extern "C" JNIEXPORT void JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_calibrateNoiseSuppressor(
        JNIEnv *env, jobject /* this */) {
    engine.calibrateNoiseSuppressor();
}

/**
 * Situational mode:
 *   0 = TRANSPARENCY
 *   1 = CONVERSATION
 *   2 = OUTDOOR
 *   3 = CINEMA
 *   4 = AUTO
 */
extern "C" JNIEXPORT void JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_setSituationalMode(
        JNIEnv *env, jobject /* this */, jint mode) {
    SituationalMode m;
    switch (mode) {
        case 1:  m = SituationalMode::CONVERSATION; break;
        case 2:  m = SituationalMode::OUTDOOR;      break;
        case 3:  m = SituationalMode::CINEMA;       break;
        case 4:  m = SituationalMode::AUTO;         break;
        default: m = SituationalMode::TRANSPARENCY; break;
    }
    engine.setSituationalMode(m);
}

extern "C" JNIEXPORT void JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_setBandWdrcParameters(
        JNIEnv *env, jobject /* this */,
        jint band, jfloat thresholdDb, jfloat ratio,
        jfloat attackMs, jfloat releaseMs) {
    engine.setBandWdrcParameters(band, thresholdDb, ratio, attackMs, releaseMs);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_getEnvironmentEnergy(JNIEnv *env, jobject /* this */) {
    jfloatArray result = env->NewFloatArray(5);
    float energy[5];
    for (int i = 0; i < 5; ++i) {
        energy[i] = engine.getBandEnergy(i);
    }
    env->SetFloatArrayRegion(result, 0, 5, energy);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_setBypassMode(
        JNIEnv *env, jobject /* this */, jboolean bypass) {
    engine.setBypassMode(bypass);
}

extern "C" JNIEXPORT void JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_setMasterGain(
        JNIEnv *env, jobject /* this */, jfloat gain) {
    engine.setMasterGain(gain);
}

extern "C" JNIEXPORT void JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_setMuted(
        JNIEnv *env, jobject /* this */, jboolean muted) {
    engine.setMuted(muted);
}

extern "C" JNIEXPORT void JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_setInputGainOffset(
        JNIEnv *env, jobject /* this */, jfloat gainDb) {
    engine.setInputGainOffset(gainDb);
}

extern "C" JNIEXPORT void JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_setUseHeadsetMic(
        JNIEnv *env, jobject /* this */, jboolean useHeadset) {
    engine.setUseHeadsetMic(useHeadset);
}

extern "C" JNIEXPORT void JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_setIsBluetoothInput(
        JNIEnv *env, jobject /* this */, jboolean isBluetooth) {
    engine.setIsBluetoothInput(isBluetooth);
}

extern "C" JNIEXPORT void JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_setHeadphonesConnected(
        JNIEnv *env, jobject /* this */, jboolean connected) {
    engine.setHeadphonesConnected(connected);
}

extern "C" JNIEXPORT void JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_setMediaCaptureMode(
        JNIEnv *env, jobject /* this */, jboolean enabled) {
    engine.setMediaCaptureMode(enabled);
}

extern "C" JNIEXPORT void JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_pushMediaAudioData(
        JNIEnv *env, jobject /* this */, jfloatArray data, jint numFrames) {
    jfloat *c_data = env->GetFloatArrayElements(data, nullptr);
    if (c_data != nullptr) {
        engine.pushMediaAudioData(c_data, numFrames);
        env->ReleaseFloatArrayElements(data, c_data, JNI_ABORT);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_setDcBlockerEnabled(
        JNIEnv *env, jobject /* this */, jboolean enabled) {
    engine.setDcBlockerEnabled(enabled);
}

extern "C" JNIEXPORT void JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_setCrossoverWdrcEnabled(
        JNIEnv *env, jobject /* this */, jboolean enabled) {
    engine.setCrossoverWdrcEnabled(enabled);
}

extern "C" JNIEXPORT void JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_setLimiterEnabled(
        JNIEnv *env, jobject /* this */, jboolean enabled) {
    engine.setLimiterEnabled(enabled);
}

extern "C" JNIEXPORT void JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_setTransientSuppressorEnabled(
        JNIEnv *env, jobject /* this */, jboolean enabled) {
    engine.setTransientSuppressorEnabled(enabled);
}

extern "C" JNIEXPORT void JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_setOwnVoiceDetectorEnabled(
        JNIEnv *env, jobject /* this */, jboolean enabled) {
    engine.setOwnVoiceDetectorEnabled(enabled);
}

extern "C" JNIEXPORT void JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_setWdrcExpanderThreshold(
        JNIEnv *env, jobject /* this */, jfloat thresholdDb) {
    engine.setWdrcExpanderThreshold(thresholdDb);
}

extern "C" JNIEXPORT void JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_setLimiterParameters(
        JNIEnv *env, jobject /* this */, jfloat thresholdDb, jfloat releaseMs) {
    engine.setLimiterParameters(thresholdDb, 20.0f, 0.5f, releaseMs);
}

extern "C" JNIEXPORT void JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_setStreamOverrides(
        JNIEnv *env, jobject /* this */, jint sharingMode, jint inputPreset) {
    engine.setStreamOverrides(sharingMode, inputPreset);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_getDiagnosticMetrics(
        JNIEnv *env, jobject /* this */) {
    jfloatArray result = env->NewFloatArray(5);
    if (result == nullptr) return nullptr;
    float metrics[5];
    engine.getDiagnosticMetrics(metrics);
    env->SetFloatArrayRegion(result, 0, 5, metrics);
    return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_isNoiseReductionEnabled(JNIEnv *env, jobject /* this */) {
    return (jboolean) engine.isNoiseReductionEnabled();
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_getMasterGain(JNIEnv *env, jobject /* this */) {
    return (jfloat) engine.getMasterGain();
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_getInputGainOffset(JNIEnv *env, jobject /* this */) {
    return (jfloat) engine.getInputGainOffset();
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_getWdrcExpanderThreshold(JNIEnv *env, jobject /* this */) {
    return (jfloat) engine.getWdrcExpanderThreshold();
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_getLimiterThreshold(JNIEnv *env, jobject /* this */) {
    return (jfloat) engine.getLimiterThreshold();
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_getLimiterRelease(JNIEnv *env, jobject /* this */) {
    return (jfloat) engine.getLimiterRelease();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_isDcBlockerEnabled(JNIEnv *env, jobject /* this */) {
    return (jboolean) engine.isDcBlockerEnabled();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_isCrossoverWdrcEnabled(JNIEnv *env, jobject /* this */) {
    return (jboolean) engine.isCrossoverWdrcEnabled();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_isLimiterEnabled(JNIEnv *env, jobject /* this */) {
    return (jboolean) engine.isLimiterEnabled();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_isTransientSuppressorEnabled(JNIEnv *env, jobject /* this */) {
    return (jboolean) engine.isTransientSuppressorEnabled();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_isOwnVoiceDetectorEnabled(JNIEnv *env, jobject /* this */) {
    return (jboolean) engine.isOwnVoiceDetectorEnabled();
}