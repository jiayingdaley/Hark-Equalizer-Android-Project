#include "TransientSuppressor.h"
#include <cmath>
#include <algorithm>

TransientSuppressor::TransientSuppressor() {
    updateCoefficients();
}

void TransientSuppressor::setSampleRate(double sampleRate) {
    if (sampleRate > 0.0) {
        mSampleRate = sampleRate;
        updateCoefficients();
    }
}

void TransientSuppressor::setParameters(float thresholdDb, float attenuationDb, float holdMs, float releaseMs) {
    mThresholdDb = thresholdDb;
    mAttenuationDb = attenuationDb;
    mHoldMs = std::max(0.0f, holdMs);
    mReleaseMs = std::max(0.0f, releaseMs);
    updateCoefficients();
}

void TransientSuppressor::setEnabled(bool enabled) {
    mEnabled = enabled;
    if (!enabled) {
        mCurrentGain = 1.0f;
    }
}

void TransientSuppressor::updateCoefficients() {
    // Fast Env: ~1ms
    mFastCoeff = std::exp(-1.0f / (static_cast<float>(mSampleRate) * 0.001f));
    
    // Slow Env: ~100ms
    mSlowCoeff = std::exp(-1.0f / (static_cast<float>(mSampleRate) * 0.100f));
    
    // Fast Attack: ~0.5ms to be extremely responsive but smooth to prevent clicks/pops
    mAttackCoeff = std::exp(-1.0f / (static_cast<float>(mSampleRate) * 0.0005f));
    
    // Release Coeff
    if (mReleaseMs > 0.0f) {
        mReleaseCoeff = std::exp(-1.0f / (static_cast<float>(mSampleRate) * (mReleaseMs / 1000.0f)));
    } else {
        mReleaseCoeff = 0.0f;
    }
    
    // Hold Samples
    mHoldSamples = static_cast<int>(mSampleRate * (mHoldMs / 1000.0f));
}

float TransientSuppressor::process(float inputSample) {
    if (!mEnabled) {
        return inputSample;
    }

    float absVal = std::fabs(inputSample);

    // 1. Update fast and slow envelopes
    mEnvFast = mFastCoeff * mEnvFast + (1.0f - mFastCoeff) * absVal;
    mEnvSlow = mSlowCoeff * mEnvSlow + (1.0f - mSlowCoeff) * absVal;

    // Avoid denormal numbers causing high CPU load
    if (mEnvFast < 1.175494e-38f) mEnvFast = 0.0f;
    if (mEnvSlow < 1.175494e-38f) mEnvSlow = 0.0f;

    // 2. Crest Factor Calculation
    float ratio = 1.0f;
    if (mEnvSlow > 1e-6f) {
        ratio = mEnvFast / mEnvSlow;
    }
    
    float ratioDb = (ratio > 1e-6f) ? (20.0f * std::log10(ratio)) : 0.0f;

    // 3. Trigger Suppression
    float targetGain = 1.0f;
    float minLevel = std::pow(10.0f, mMinLevelDb / 20.0f);
    if (mEnvFast > minLevel && ratioDb > mThresholdDb) {
        mHoldCounter = mHoldSamples; // Trigger / Reset hold
    }

    if (mHoldCounter > 0) {
        targetGain = std::pow(10.0f, mAttenuationDb / 20.0f);
        mHoldCounter--;
    }

    // 4. Smooth Gain Transition
    if (targetGain < mCurrentGain) {
        // Fast but smooth attack (~0.5ms) to prevent clicks/pop artifacts
        mCurrentGain = mAttackCoeff * mCurrentGain + (1.0f - mAttackCoeff) * targetGain;
    } else {
        // Release phase
        mCurrentGain = mReleaseCoeff * mCurrentGain + (1.0f - mReleaseCoeff) * targetGain;
    }

    return inputSample * mCurrentGain;
}
