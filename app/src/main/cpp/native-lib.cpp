#include <jni.h>
#include "HarkAudioEngine.h"

// The single, static instance of our audio engine.
static HarkAudioEngine engine;

extern "C" JNIEXPORT void JNICALL
Java_com_example_hark_MainActivity_startEngine(JNIEnv *env, jobject /* this */) {
engine.start();
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_hark_MainActivity_stopEngine(JNIEnv *env, jobject /* this */) {
engine.stop();
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_hark_MainActivity_setBandGain(JNIEnv *env, jobject /* this */, jint bandIndex, jfloat gainDb) {
engine.setBandGain(bandIndex, gainDb);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_hark_MainActivity_setBandQ(JNIEnv *env, jobject /* this */, jint bandIndex, jfloat q_factor) {
engine.setBandQ(bandIndex, q_factor);
}

// This function is kept for future use if you want to switch engine types from Kotlin.
extern "C" JNIEXPORT void JNICALL
Java_com_example_hark_MainActivity_setEngineMode(JNIEnv *env, jobject /* this */, jint mode) {
// Placeholder for now
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_hark_MainActivity_setAudioInputDeviceId(JNIEnv *env, jobject /* this */, jint device_id) {
engine.setInputDeviceId(device_id);
}

// This is the only function needed to query the engine's real status.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_hark_MainActivity_isEngineActuallyRunning(JNIEnv *env, jobject /* this */) {
    return (jboolean) engine.isEngineRunning();
}