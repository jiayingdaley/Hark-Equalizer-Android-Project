#include <jni.h>
#include "HarkAudioEngine.h"

// The single, static instance of our audio engine.
static HarkAudioEngine engine;

extern "C" JNIEXPORT void JNICALL
Java_com_wcy_hark_audio_HarkAudioBridge_startEngine(JNIEnv *env, jobject /* this */) {
    engine.start();
}

extern "C" JNIEXPORT void JNICALL
Java_com_wcy_hark_audio_HarkAudioBridge_stopEngine(JNIEnv *env, jobject /* this */) {
    engine.stop();
}

extern "C" JNIEXPORT void JNICALL
Java_com_wcy_hark_audio_HarkAudioBridge_setBandGain(
        JNIEnv *env, jobject /* this */, jint bandIndex, jfloat gainDb) {
    engine.setBandGain(bandIndex, gainDb);
}

extern "C" JNIEXPORT void JNICALL
Java_com_wcy_hark_audio_HarkAudioBridge_setBandQ(
        JNIEnv *env, jobject /* this */, jint bandIndex, jfloat q_factor) {
    engine.setBandQ(bandIndex, q_factor);
}

extern "C" JNIEXPORT void JNICALL
Java_com_wcy_hark_audio_HarkAudioBridge_setAudioInputDeviceId(
        JNIEnv *env, jobject /* this */, jint device_id) {
    engine.setInputDeviceId(device_id);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_wcy_hark_audio_HarkAudioBridge_isEngineActuallyRunning(
        JNIEnv *env, jobject /* this */) {
    return (jboolean) engine.isEngineRunning();
}

extern "C" JNIEXPORT void JNICALL
Java_com_wcy_hark_audio_HarkAudioBridge_setNoiseReductionEnabled(
        JNIEnv *env, jobject /* this */, jboolean enabled) {
    engine.setNoiseReductionEnabled(enabled);
}

extern "C" JNIEXPORT void JNICALL
Java_com_wcy_hark_audio_HarkAudioBridge_resetGesture(
        JNIEnv *env, jobject /* this */) {
    engine.resetGesture();
}

extern "C" JNIEXPORT void JNICALL
Java_com_wcy_hark_audio_HarkAudioBridge_logLatencyStatistics(
        JNIEnv *env, jobject /* this */) {
    engine.logLatencyStatistics();
}

extern "C" JNIEXPORT void JNICALL
Java_com_wcy_hark_audio_HarkAudioBridge_calibrateNoiseSuppressor(
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
Java_com_wcy_hark_audio_HarkAudioBridge_setSituationalMode(
        JNIEnv *env, jobject /* this */, jint mode) {
    HarkAudioEngine::SituationalMode m;
    switch (mode) {
        case 1:  m = HarkAudioEngine::SituationalMode::CONVERSATION; break;
        case 2:  m = HarkAudioEngine::SituationalMode::OUTDOOR;      break;
        case 3:  m = HarkAudioEngine::SituationalMode::CINEMA;       break;
        case 4:  m = HarkAudioEngine::SituationalMode::AUTO;         break;
        default: m = HarkAudioEngine::SituationalMode::TRANSPARENCY; break;
    }
    engine.setSituationalMode(m);
}

extern "C" JNIEXPORT void JNICALL
Java_com_wcy_hark_audio_HarkAudioBridge_setPinnaEnabled(
        JNIEnv *env, jobject /* this */, jboolean enabled) {
    engine.setPinnaEnabled(enabled);
}

extern "C" JNIEXPORT void JNICALL
Java_com_wcy_hark_audio_HarkAudioBridge_setBandWdrcParameters(
        JNIEnv *env, jobject /* this */,
        jint band, jfloat thresholdDb, jfloat ratio,
        jfloat attackMs, jfloat releaseMs) {
    engine.setBandWdrcParameters(band, thresholdDb, ratio, attackMs, releaseMs);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_wcy_hark_audio_HarkAudioBridge_getEnvironmentEnergy(JNIEnv *env, jobject /* this */) {
    jfloatArray result = env->NewFloatArray(5);
    float energy[5];
    for (int i = 0; i < 5; ++i) {
        energy[i] = engine.getBandEnergy(i);
    }
    env->SetFloatArrayRegion(result, 0, 5, energy);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_wcy_hark_audio_HarkAudioBridge_setBypassMode(
        JNIEnv *env, jobject /* this */, jboolean bypass) {
    engine.setBypassMode(bypass);
}

extern "C" JNIEXPORT void JNICALL
Java_com_wcy_hark_audio_HarkAudioBridge_setMasterGain(
        JNIEnv *env, jobject /* this */, jfloat gain) {
    engine.setMasterGain(gain);
}

extern "C" JNIEXPORT void JNICALL
Java_com_wcy_hark_audio_HarkAudioBridge_setMuted(
        JNIEnv *env, jobject /* this */, jboolean muted) {
    engine.setMuted(muted);
}