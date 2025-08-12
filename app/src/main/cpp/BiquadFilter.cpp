#include "BiquadFilter.h"

BiquadFilter::BiquadFilter() : b0(1.0), b1(0.0), b2(0.0), a1(0.0), a2(0.0), x1(0.0), x2(0.0), y1(0.0), y2(0.0) {}

void BiquadFilter::updateCoefficients(Type type, double sampleRate, double centerHz, double gainDb, double q_factor) {
    double A = pow(10, gainDb / 40.0); // 對 Shelf 濾波器，這裡用 40
    double V = pow(10, gainDb / 20.0); // 對 Peaking 濾波器
    double w0 = 2.0 * M_PI * centerHz / sampleRate;
    double cos_w0 = cos(w0);
    double sin_w0 = sin(w0);
    double alpha = sin_w0 / (2.0 * q_factor);

    // 根據 Audio EQ Cookbook 的公式
    double a0_inv; // 倒數 a0，用於歸一化

    switch (type) {
        case Type::Peaking:
            b0 = 1.0 + alpha * V;
            b1 = -2.0 * cos_w0;
            b2 = 1.0 - alpha * V;
            a0_inv = 1.0 / (1.0 + alpha / V);
            a1 = -2.0 * cos_w0;
            a2 = 1.0 - alpha / V;
            break;

        case Type::LowShelf:
            b0 = A * ((A + 1.0) - (A - 1.0) * cos_w0 + 2.0 * sqrt(A) * alpha);
            b1 = 2.0 * A * ((A - 1.0) - (A + 1.0) * cos_w0);
            b2 = A * ((A + 1.0) - (A - 1.0) * cos_w0 - 2.0 * sqrt(A) * alpha);
            a0_inv = 1.0 / ((A + 1.0) + (A - 1.0) * cos_w0 + 2.0 * sqrt(A) * alpha);
            a1 = -2.0 * ((A - 1.0) + (A + 1.0) * cos_w0);
            a2 = (A + 1.0) + (A - 1.0) * cos_w0 - 2.0 * sqrt(A) * alpha;
            break;

        case Type::HighShelf:
            b0 = A * ((A + 1.0) + (A - 1.0) * cos_w0 + 2.0 * sqrt(A) * alpha);
            b1 = -2.0 * A * ((A - 1.0) + (A + 1.0) * cos_w0);
            b2 = A * ((A + 1.0) + (A - 1.0) * cos_w0 - 2.0 * sqrt(A) * alpha);
            a0_inv = 1.0 / ((A + 1.0) - (A - 1.0) * cos_w0 + 2.0 * sqrt(A) * alpha);
            a1 = 2.0 * ((A - 1.0) - (A + 1.0) * cos_w0);
            a2 = (A + 1.0) - (A - 1.0) * cos_w0 - 2.0 * sqrt(A) * alpha;
            break;
    }

    // 歸一化係數
    b0 *= a0_inv;
    b1 *= a0_inv;
    b2 *= a0_inv;
    a1 *= a0_inv;
    a2 *= a0_inv;
}

float BiquadFilter::process(float in) {
    double out = b0 * in + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
    x2 = x1; x1 = in;
    y2 = y1; y1 = out;
    return (float)out;
}