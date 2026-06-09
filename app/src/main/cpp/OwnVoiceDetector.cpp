#include "OwnVoiceDetector.h"
#include <cmath>
#include <algorithm>

OwnVoiceDetector::OwnVoiceDetector() {
    updateCoefficients();
}

void OwnVoiceDetector::setSampleRate(double sampleRate) {
    if (sampleRate > 0.0) {
        mSampleRate = sampleRate;
        updateCoefficients();
    }
}

void OwnVoiceDetector::setParameters(float occlusionGainDb, float ratioThresholdDb, float energyThresholdDb) {
    mOcclusionGainDb = occlusionGainDb;
    mRatioThresholdDb = ratioThresholdDb;
    mEnergyThresholdDb = energyThresholdDb;
    mMinEnergyLinear = std::pow(10.0f, energyThresholdDb / 20.0f);
    updateCoefficients();
}

void OwnVoiceDetector::setEnabled(bool enabled) {
    mEnabled = enabled;
    if (!enabled) {
        mCurrentOcclusionGain = 1.0f;
        mTargetOcclusionGain = 1.0f;
        mIsOwnVoice = false;
        mHoldCounter = 0;
    }
}

void OwnVoiceDetector::updateCoefficients() {
    // 50ms smoothing for energy
    mEnergyCoeff = std::exp(-1.0f / (static_cast<float>(mSampleRate) * 0.050f));
    
    // 50ms smoothing for gain transition
    mGainCoeff = std::exp(-1.0f / (static_cast<float>(mSampleRate) * 0.050f));
    
    // 100ms hold time
    mHoldSamples = static_cast<int>(mSampleRate * 0.100f);
}

void OwnVoiceDetector::updateEnergy(const float* bands) {
    if (!mEnabled) {
        mIsOwnVoice = false;
        mTargetOcclusionGain = 1.0f;
        mCurrentOcclusionGain = 1.0f;
        return;
    }

    // Low Freq Level (Band 0 & 1: <500Hz)
    float lowLevel = std::fabs(bands[0]) + std::fabs(bands[1]);
    
    // High Freq Level (Band 6 & 7: >4500Hz)
    float highLevel = std::fabs(bands[6]) + std::fabs(bands[7]);

    // Apply smoothing to energy tracking
    mLowFreqEnergy = mEnergyCoeff * mLowFreqEnergy + (1.0f - mEnergyCoeff) * lowLevel;
    mHighFreqEnergy = mEnergyCoeff * mHighFreqEnergy + (1.0f - mEnergyCoeff) * highLevel;

    // Avoid denormals
    if (mLowFreqEnergy < 1.175494e-38f) mLowFreqEnergy = 0.0f;
    if (mHighFreqEnergy < 1.175494e-38f) mHighFreqEnergy = 0.0f;

    // Calculate ratio in dB
    float ratio = 1.0f;
    if (mHighFreqEnergy > 1e-6f) {
        ratio = mLowFreqEnergy / mHighFreqEnergy;
    }
    float ratioDb = (ratio > 1e-6f) ? (20.0f * std::log10(ratio)) : 0.0f;

    // Own Voice Detection Rule:
    // 1. Low frequency energy must exceed the minimum energy threshold
    // 2. Low-to-high energy ratio must exceed the ratio threshold
    bool voiceCondition = (mLowFreqEnergy > mMinEnergyLinear) && (ratioDb > mRatioThresholdDb);

    if (voiceCondition) {
        mHoldCounter = mHoldSamples; // Reset / Hold OVD active state
        mIsOwnVoice = true;
    } else {
        if (mHoldCounter > 0) {
            mHoldCounter--;
            mIsOwnVoice = true;
        } else {
            mIsOwnVoice = false;
        }
    }

    // Set target occlusion gain based on voice state
    if (mIsOwnVoice) {
        mTargetOcclusionGain = std::pow(10.0f, mOcclusionGainDb / 20.0f);
    } else {
        mTargetOcclusionGain = 1.0f;
    }

    // Smooth gain transition
    mCurrentOcclusionGain = mGainCoeff * mCurrentOcclusionGain + (1.0f - mGainCoeff) * mTargetOcclusionGain;
}

float OwnVoiceDetector::getOcclusionGain(int bandIndex) const {
    if (!mEnabled) {
        return 1.0f;
    }
    // Only apply low frequency attenuation to Band 0 and Band 1
    if (bandIndex == 0 || bandIndex == 1) {
        return mCurrentOcclusionGain;
    }
    return 1.0f;
}
