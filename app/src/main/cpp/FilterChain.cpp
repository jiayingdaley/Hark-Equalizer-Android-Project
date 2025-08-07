#include "FilterChain.h"

FilterChain::FilterChain(int numBands) : filters(numBands) {}

void FilterChain::updateBand(int bandIndex, double sampleRate, double centerHz, double gainDb, double q_factor) {
    if (bandIndex >= 0 && bandIndex < filters.size()) {
        filters[bandIndex].updateCoefficients(sampleRate, centerHz, gainDb, q_factor);
    }
}

float FilterChain::process(float in) {
    float out = in;
    for (auto& filter : filters) {
        out = filter.process(out);
    }
    return out;
}