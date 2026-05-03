#include "FilterChain.h"
#include <android/log.h>

#define LOG_TAG "FilterChain"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

FilterChain::FilterChain(int numBands) : filters(numBands) {}

void FilterChain::updateBand(int bandIndex, BiquadFilter::Type type, double sampleRate, 
                             double centerHz, double gainDb, double q_factor) {
    if (bandIndex < 0 || bandIndex >= (int)filters.size()) {
        LOGE("FilterChain::updateBand - index %d out of range [0,%zu)", bandIndex, filters.size());
        return;
    }
    filters[bandIndex].updateCoefficients(type, sampleRate, centerHz, gainDb, q_factor);
}

float FilterChain::process(float in) {
    float out = in;
    // 将输入信号依次通过所有滤波器（串联处理）
    for (auto& filter : filters) {
        out = filter.process(out);
    }
    return out;
}