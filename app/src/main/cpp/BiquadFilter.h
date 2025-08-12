#pragma once

#include <cmath>

class BiquadFilter {
public:
    enum class Type {
        Peaking,
        LowShelf,
        HighShelf
    };

    BiquadFilter();
    void updateCoefficients(Type type, double sampleRate, double centerHz, double gainDb, double q_factor);
    float process(float in);

private:
    double b0, b1, b2, a1, a2;
    double x1, x2, y1, y2;
};