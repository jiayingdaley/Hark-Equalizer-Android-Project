#include "CrossoverBank8.h"
#include "HarkDspConfig.h"

#include <cmath>

namespace {
    constexpr double kPi = 3.14159265358979323846;
    // Butterworth Q —— LR4 = 兩級串接的 Butterworth，其 LP+HP 之全通亦為此 Q
    const double kQ = 0.70710678118654752440;
}

void CrossoverBank8::Allpass2::setFrequency(double fc, double sampleRate) {
    const double w0 = 2.0 * kPi * fc / sampleRate;
    const double cw = std::cos(w0);
    const double alpha = std::sin(w0) / (2.0 * kQ);

    const double a0 = 1.0 + alpha;
    b0 = (1.0 - alpha) / a0;
    b1 = (-2.0 * cw) / a0;
    b2 = 1.0;                       // (1 + alpha) / a0
    a1 = (-2.0 * cw) / a0;
    a2 = (1.0 - alpha) / a0;
    reset();
}

float CrossoverBank8::Allpass2::process(float x) {
    const double y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
    x2 = x1; x1 = x;
    y2 = y1; y1 = static_cast<float>(y);
    return static_cast<float>(y);
}

void CrossoverBank8::setSampleRate(double sampleRate) {
    mXoMid.setFrequency(HarkDspConfig::XO_MID_HZ, sampleRate);
    mXoLow.setFrequency(HarkDspConfig::XO_LOW_HZ, sampleRate);
    mXoHigh.setFrequency(HarkDspConfig::XO_HIGH_HZ, sampleRate);
    mXoVLow.setFrequency(HarkDspConfig::XO_VLOW_HZ, sampleRate);
    mXoLMid.setFrequency(HarkDspConfig::XO_LMID_HZ, sampleRate);
    mXoHMid.setFrequency(HarkDspConfig::XO_HMID_HZ, sampleRate);
    mXoVHi.setFrequency(HarkDspConfig::XO_VHI_HZ, sampleRate);

    // 第三層：補上「兄弟節點所用分頻器」的全通相位
    mApLMid.setFrequency(HarkDspConfig::XO_LMID_HZ, sampleRate);   // 給 b0,b1
    mApVLow.setFrequency(HarkDspConfig::XO_VLOW_HZ, sampleRate);   // 給 b2,b3
    mApVHi.setFrequency(HarkDspConfig::XO_VHI_HZ, sampleRate);     // 給 b4,b5
    mApHMid.setFrequency(HarkDspConfig::XO_HMID_HZ, sampleRate);   // 給 b6,b7

    // 第二層：低支補高支整條鏈，高支補低支整條鏈
    mApHiChain1.setFrequency(HarkDspConfig::XO_HIGH_HZ, sampleRate);
    mApHiChain2.setFrequency(HarkDspConfig::XO_HMID_HZ, sampleRate);
    mApHiChain3.setFrequency(HarkDspConfig::XO_VHI_HZ, sampleRate);

    mApLoChain1.setFrequency(HarkDspConfig::XO_LOW_HZ, sampleRate);
    mApLoChain2.setFrequency(HarkDspConfig::XO_VLOW_HZ, sampleRate);
    mApLoChain3.setFrequency(HarkDspConfig::XO_LMID_HZ, sampleRate);
}

void CrossoverBank8::reset() {
    mApLMid.reset(); mApVLow.reset(); mApVHi.reset(); mApHMid.reset();
    mApHiChain1.reset(); mApHiChain2.reset(); mApHiChain3.reset();
    mApLoChain1.reset(); mApLoChain2.reset(); mApLoChain3.reset();
}

void CrossoverBank8::split(float in, float* bands) {
    LinkwitzRileyCrossover::Output mid = mXoMid.process(in);

    LinkwitzRileyCrossover::Output lo = mXoLow.process(mid.low);
    LinkwitzRileyCrossover::Output b01 = mXoVLow.process(lo.low);     // 250
    LinkwitzRileyCrossover::Output b23 = mXoLMid.process(lo.high);    // 1000

    LinkwitzRileyCrossover::Output hi = mXoHigh.process(mid.high);
    LinkwitzRileyCrossover::Output b45 = mXoHMid.process(hi.low);     // 2500
    LinkwitzRileyCrossover::Output b67 = mXoVHi.process(hi.high);     // 6000

    bands[0] = b01.low;    // < 250
    bands[1] = b01.high;   // 250–500
    bands[2] = b23.low;    // 500–1000
    bands[3] = b23.high;   // 1000–1500
    bands[4] = b45.low;    // 1500–2500
    bands[5] = b45.high;   // 2500–4500
    bands[6] = b67.low;    // 4500–6000
    bands[7] = b67.high;   // > 6000
}

float CrossoverBank8::recombine(const float* b) {
    // 第三層：同一個父節點的兄弟頻帶先相加（它們相位一致），
    // 再補上「另一個父節點所用分頻器」的全通。
    const float lowA = mApLMid.process(b[0] + b[1]);   // 出自 250 分頻 → 補 AP(1000)
    const float lowB = mApVLow.process(b[2] + b[3]);   // 出自 1000 分頻 → 補 AP(250)
    const float hiA  = mApVHi.process(b[4] + b[5]);    // 出自 2500 分頻 → 補 AP(6000)
    const float hiB  = mApHMid.process(b[6] + b[7]);   // 出自 6000 分頻 → 補 AP(2500)

    // 第二層：低支已帶 AP(250)·AP(1000)·AP(500)，高支已帶 AP(2500)·AP(6000)·AP(4500)。
    // 互補對方的鏈，兩支才會帶著同一組全通、在 1500 Hz 交界同調相加。
    float low = lowA + lowB;
    low = mApHiChain1.process(low);
    low = mApHiChain2.process(low);
    low = mApHiChain3.process(low);

    float high = hiA + hiB;
    high = mApLoChain1.process(high);
    high = mApLoChain2.process(high);
    high = mApLoChain3.process(high);

    return low + high;
}
