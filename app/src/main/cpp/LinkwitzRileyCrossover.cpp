#include "LinkwitzRileyCrossover.h"

void LinkwitzRileyCrossover::setFrequency(double freq, double sampleRate) {
    // For LR4, we use two 2nd-order Butterworth filters in series.
    // Q factor for Butterworth is 0.707.
    const double Q = 0.70710678118;
    
    lp1.updateCoefficients(BiquadFilter::Type::LowPass, sampleRate, freq, 0.0, Q);
    lp2.updateCoefficients(BiquadFilter::Type::LowPass, sampleRate, freq, 0.0, Q);
    
    hp1.updateCoefficients(BiquadFilter::Type::HighPass, sampleRate, freq, 0.0, Q);
    hp2.updateCoefficients(BiquadFilter::Type::HighPass, sampleRate, freq, 0.0, Q);
}

LinkwitzRileyCrossover::Output LinkwitzRileyCrossover::process(float in) {
    // Cascade processing
    float low = lp2.process(lp1.process(in));
    float high = hp2.process(hp1.process(in));
    return { low, high };
}
