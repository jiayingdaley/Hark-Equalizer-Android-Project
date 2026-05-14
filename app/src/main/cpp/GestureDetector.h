#pragma once
#include <vector>

enum class GestureState {
    IDLE,
    SCANNING,
    LOCKED
};

class GestureDetector {
public:
    GestureDetector(double sampleRate);
    
    // Analyzes the sample and updates the state machine
    GestureState update(float inputL, float inputR);
    
    // Returns the captured profile (16 bands)
    const std::vector<float>& getLockedProfile() const { return mLockedProfile; }
    
    void reset() { mState = GestureState::IDLE; }

private:
    double mSampleRate;
    GestureState mState = GestureState::IDLE;
    
    // Detection parameters
    float mLowMidEnergy = 0.0f;
    float mTotalEnergy = 0.0f;
    
    // Timing
    int mScanningCounter = 0;
    int mScanDurationSamples; // 0.5 seconds @ mSampleRate (加快手勢檢測)
    
    // Profiling
    std::vector<float> mCurrentProfile; // Current average during scan
    std::vector<float> mLockedProfile;  // The frozen profile
    
    // Thresholds
    const float SWELL_THRESHOLD = 2.5f; // Energy ratio boost to trigger
};
