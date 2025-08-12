#include "FilterChain.h"

FilterChain::FilterChain(int numBands) : filters(numBands) {}

void FilterChain::updateBand(int bandIndex, BiquadFilter::Type type, double sampleRate, double centerHz, double gainDb, double q_factor) {
    if (bandIndex >= 0 && bandIndex < filters.size()) {
        filters[bandIndex].updateCoefficients(type, sampleRate, centerHz, gainDb, q_factor);
    }
}

float FilterChain::process(float in) {
    float out = 0.0f;
    // 將輸入訊號並聯送入所有濾波器，然後將結果相加
    for (auto& filter : filters) {
        out += filter.process(in);
    }
    return out;
}