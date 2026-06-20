#pragma once

#include "BiquadFilter.h"
#include <vector>

class FilterChain {
public:
    FilterChain(int numBands);
    void updateBand(int bandIndex, BiquadFilter::Type type, double sampleRate, double centerHz, double gainDb, double q_factor);
    float process(float in);

private:
    std::vector<BiquadFilter> filters;
};