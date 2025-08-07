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
