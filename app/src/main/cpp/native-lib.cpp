#include <jni.h>
#include "HarkAudioEngine.h"

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

extern "C" JNIEXPORT void JNICALL
Java_com_example_hark_MainActivity_setEngineMode(JNIEnv *env, jobject /* this */, jint mode) {
    // Placeholder for now
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_hark_MainActivity_setAudioInputDeviceId(JNIEnv *env, jobject thiz, jint device_id) {
    engine.setInputDeviceId(device_id);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_hark_MainActivity_isEngineRunning(JNIEnv *env, jobject thiz) {
return (jboolean) engine.isEngineRunning();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_hark_MainActivity_isEngineActuallyRunning(JNIEnv *env, jobject /* this */) {
    // 假設 engine.isEngineRunning() 回傳 C++ 的 bool
    // bool isRunning = engine.isEngineRunning();
    // return (jboolean)isRunning;

    return (jboolean) engine.isEngineRunning();

    // 如果只是測試，可以這樣：
    return JNI_TRUE; // 或 JNI_FALSE
}