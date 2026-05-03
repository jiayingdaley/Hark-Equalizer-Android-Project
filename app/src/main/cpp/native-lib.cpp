#include <jni.h>
#include "HarkAudioEngine.h"

// The single, static instance of our audio engine.
// Lifetime: process lifetime (static storage duration).
// Thread safety: individual methods are protected by mDSPMutex inside HarkAudioEngine.
static HarkAudioEngine engine;

// ---------------------------------------------------------------------------
// JNI Bridge
// Kotlin class: com.wcy.hark.audio.HarkAudioBridge (object)
// Naming convention: Java_[package_underscored]_[ClassName]_[methodName]
// Ref: JNI Tips – https://developer.android.com/training/articles/perf-jni
// ---------------------------------------------------------------------------

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