#include "BiquadFilter.h"

BiquadFilter::BiquadFilter() : b0(1.0), b1(0.0), b2(0.0), a1(0.0), a2(0.0), x1(0.0), x2(0.0), y1(0.0), y2(0.0) {}

void BiquadFilter::updateCoefficients(double sampleRate, double centerHz, double gainDb, double q_factor) {
    double K = tan(M_PI * centerHz / sampleRate);
    double V = pow(10, gainDb / 20.0);

    double norm;
    if (gainDb >= 0) { // Boost
        norm = 1.0 / (1.0 + 1.0 / q_factor * K + K * K);
        b0 = (1.0 + V / q_factor * K + K * K) * norm;
        b1 = 2.0 * (K * K - 1.0) * norm;
        b2 = (1.0 - V / q_factor * K + K * K) * norm;
        a1 = b1;
        a2 = (1.0 - 1.0 / q_factor * K + K * K) * norm;
    } else { // Cut
        norm = 1.0 / (1.0 + V / q_factor * K + K * K);
        b0 = (1.0 + 1.0 / q_factor * K + K * K) * norm;
        b1 = 2.0 * (K * K - 1.0) * norm;
        b2 = (1.0 - 1.0 / q_factor * K + K * K) * norm;
        a1 = 2.0 * (V * V * K * K - 1.0) * norm;
        a2 = (1.0 - V / q_factor * K + V * V * K * K) * norm;
    }
}

float BiquadFilter::process(float in) {
    double out = b0 * in + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
    x2 = x1;
    x1 = in;
    y2 = y1;
    y1 = out;
    return (float)out;
}