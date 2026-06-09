#include "BiquadFilter.h"
#ifdef __ANDROID__
#include <android/log.h>
#define LOG_TAG "BiquadFilter"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#else
#include <cstdio>
#define LOGE(...) do { std::fprintf(stderr, __VA_ARGS__); std::fprintf(stderr, "\n"); } while(0)
#define LOGW(...) do { std::fprintf(stderr, __VA_ARGS__); std::fprintf(stderr, "\n"); } while(0)
#endif

BiquadFilter::BiquadFilter() : b0(1.0), b1(0.0), b2(0.0), a1(0.0), a2(0.0), x1(0.0), x2(0.0), y1(0.0), y2(0.0) {}

void BiquadFilter::updateCoefficients(Type type, double sampleRate, double centerHz, double gainDb, double q_factor) {
    // parameter guards (mirror C++ guards)
    if (sampleRate <= 0.0) { LOGE("Invalid sampleRate: %.0f", sampleRate); return; }
    if (centerHz  <= 0.0) { LOGE("Invalid centerHz: %.0f",  centerHz);  return; }
    if (q_factor  <= 0.0) { LOGE("Invalid q_factor: %.2f",  q_factor);  return; }

    // BUG-03 fix: Peaking/Shelf filters at 0dB are pure pass-through.
    // Mark as inactive so process() skips all IIR computation, preventing
    // per-band floating-point noise accumulation amplified by Makeup Gain.
    // HighPass/LowPass/BandPass are structural (always active).
    const bool isGainType = (type == Type::Peaking ||
                             type == Type::LowShelf ||
                             type == Type::HighShelf);
    if (isGainType && std::abs(gainDb) < 0.02) {
        mIsActive = false;
        // Reset state so re-activation starts clean
        x1 = x2 = y1 = y2 = 0.0;
        return;
    }
    mIsActive = true;
    
    // 截止频率不能超过 Nyquist 频率
    double nyquist = sampleRate / 2.0;
    if (centerHz >= nyquist) {
        LOGW("centerHz %.0f >= Nyquist %.0f. Clamping to 0.99*Nyquist", centerHz, nyquist);
        centerHz = nyquist * 0.99;
    }

    double A = pow(10, gainDb / 40.0);  // 對於 Shelf 和 Peaking 濾波器通用的線性振幅 (RBJ Cookbook 標準)
    // 移除錯誤的 V = pow(10, gainDb / 20.0);  
    double w0 = 2.0 * M_PI * centerHz / sampleRate;
    double cos_w0 = cos(w0);
    double sin_w0 = sin(w0);
    double alpha = sin_w0 / (2.0 * q_factor);

    // 根据 Audio EQ Cookbook 的公式
    double a0_inv;

    switch (type) {
        case Type::Peaking:
            b0 = 1.0 + alpha * A;
            b1 = -2.0 * cos_w0;
            b2 = 1.0 - alpha * A;
            a0_inv = 1.0 / (1.0 + alpha / A);
            a1 = -2.0 * cos_w0;
            a2 = 1.0 - alpha / A;
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
            
        case Type::HighPass:
            b0 = (1.0 + cos_w0) / 2.0;
            b1 = -(1.0 + cos_w0);
            b2 = (1.0 + cos_w0) / 2.0;
            a0_inv = 1.0 / (1.0 + alpha);
            a1 = -2.0 * cos_w0;
            a2 = 1.0 - alpha;
            break;

        case Type::LowPass:
            b0 = (1.0 - cos_w0) / 2.0;
            b1 = 1.0 - cos_w0;
            b2 = (1.0 - cos_w0) / 2.0;
            a0_inv = 1.0 / (1.0 + alpha);
            a1 = -2.0 * cos_w0;
            a2 = 1.0 - alpha;
            break;

        case Type::BandPass:
            // Standard BandPass (peak gain Q)
            b0 = alpha;
            b1 = 0.0;
            b2 = -alpha;
            a0_inv = 1.0 / (1.0 + alpha);
            a1 = -2.0 * cos_w0;
            a2 = 1.0 - alpha;
            break;
    }

    // 归一化系数
    b0 *= a0_inv;
    b1 *= a0_inv;
    b2 *= a0_inv;
    a1 *= a0_inv;
    a2 *= a0_inv;
}

float BiquadFilter::process(float in) {
    // BUG-03: bypass mode — zero computation, zero noise
    if (!mIsActive) return in;

    double in_d = (double)in;
    double out = b0 * in_d + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
    
    // 防止 denormal 數字導致的效能崩潰 (在更新狀態前處理)
    if (std::abs(out) < 1.17549435e-38) {
        out = 0.0;
    }

    x2 = x1;
    x1 = in_d;
    y2 = y1;
    y1 = out;
    
    return (float)out;
}