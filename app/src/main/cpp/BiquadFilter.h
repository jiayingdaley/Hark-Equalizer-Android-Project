#pragma once
// BiquadFilter: double-precision IIR biquad (RBJ Audio EQ Cookbook).
// BUG-03 fix: mIsActive bypass flag — when gainDb is within ±0.02dB of 0
// for gain-type filters (Peaking/Shelf), process() returns input unchanged
// with zero IIR computation, eliminating per-band floating-point noise.

#include <cmath>

class BiquadFilter {
public:
    enum class Type { Peaking, LowShelf, HighShelf, HighPass, LowPass, BandPass };

    BiquadFilter();

    /**
     * Compute biquad coefficients (RBJ Cookbook).
     * For Peaking/Shelf types: if |gainDb| < 0.02, sets bypass mode
     * (mIsActive = false). For HighPass/LowPass/BandPass: always active.
     */
    void updateCoefficients(Type type, double sampleRate,
                            double centerHz, double gainDb, double q_factor);

    /**
     * Process one sample.
     * If mIsActive == false (bypass mode), returns input unmodified.
     */
    float process(float in);

    /** True when the filter is performing real IIR processing. */
    bool isActive() const { return mIsActive; }

private:
    double b0, b1, b2, a1, a2;
    double x1, x2, y1, y2;
    bool   mIsActive = false;  // BUG-03: bypass when gain ≈ 0 dB
};