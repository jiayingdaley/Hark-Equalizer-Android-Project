#pragma once
#include "BiquadFilter.h"

/**
 * Linkwitz-Riley 4th Order Crossover (LR4)
 * Consists of two cascaded 2nd-order Butterworth filters.
 * Provides -6dB at crossover frequency and perfect phase alignment when summed.
 */
class LinkwitzRileyCrossover {
public:
    struct Output {
        float low;
        float high;
    };

    void setFrequency(double freq, double sampleRate);
    Output process(float in);

private:
    // LR4 Low-Pass = LP2 * LP2
    BiquadFilter lp1, lp2;
    // LR4 High-Pass = HP2 * HP2
    BiquadFilter hp1, hp2;
};
