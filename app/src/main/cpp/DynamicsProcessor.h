#pragma once

class DynamicsProcessor {
public:
    DynamicsProcessor();

    // Configure dynamic range parameters (Compression and Expansion/Noise Gate)
    void setParameters(float compressThresholdDb, float compressRatio, 
                       float expanderThresholdDb, float expanderRatio,
                       float attackMs, float releaseMs, double sampleRate);
    void setSampleRate(double sampleRate);

    // 處理單一樣本
    float process(float inputSample);

private:
    // Parameters
    float mCompressThreshold; // Linear amplitude for compression
    float mCompressRatio;
    float mExpanderThreshold; // Linear amplitude for expansion (Noise Gate)
    float mExpanderRatio;
    
    float mAttackCoeff;
    float mReleaseCoeff;

    // FDA/Quality: Soft-Knee (2.0dB knee width)
    float mKneeDb = 2.0f;

    // Performance: Gain interpolation to reduce log10/pow frequency
    float mCurrentGain = 1.0f;
    float mTargetGain = 1.0f;
    int mCounter = 0;
    static const int UPDATE_INTERVAL = 16; // Update gain every 16 samples

    // State
    float mEnvelope; // Envelope detector current value
};