#pragma once

#include <atomic>

/**
 * OwnVoiceDetector - 自我語音偵測與堵耳效應管理 (Own Voice Detector & Occlusion Manager)
 * 
 * 戴著密閉耳塞說話時，低頻骨傳導共振會在耳道內造成嚴重的嗡嗡聲（堵耳效應）。
 * 本模組藉由即時監控 Crossover 分頻後的低頻 (Band 0+1, <500Hz) 與高頻 (Band 6+7, >4500Hz) 能量。
 * 當低頻能量顯著大於高頻能量且超過靜音門檻時，判定為「配戴者正在說話」，
 * 此時動態衰減 Band 0 與 Band 1 的低頻增益，以減輕堵耳的轟鳴感，提升自我語音聽感舒適度。
 */
class OwnVoiceDetector {
public:
    OwnVoiceDetector();

    void setSampleRate(double sampleRate);
    void setParameters(float occlusionGainDb, float ratioThresholdDb, float energyThresholdDb);
    void setEnabled(bool enabled);

    // 更新能量估計 (每樣本調用)
    // bands: 8個頻段的即時浮點樣本值
    void updateEnergy(const float* bands);

    // 取得指定頻段的堵耳補償增益 (乘數)
    float getOcclusionGain(int bandIndex) const;

    bool isOwnVoiceDetected() const { return mIsOwnVoice; }

private:
    double mSampleRate = 48000.0;
    bool mEnabled = true;

    // Parameters
    float mOcclusionGainDb = -9.0f;     // 自我說話時，低頻衰減量 (dB)
    float mRatioThresholdDb = 15.0f;    // 低頻相較高頻的超額比值 (dB)，用於區分語音與環境噪聲
    float mEnergyThresholdDb = -35.0f;  // 啟動能量門檻 (dBFS)，低於此值視為環境安靜

    // Calculated thresholds (linear)
    float mMinEnergyLinear = 0.0178f;   // -35dBFS

    // Coefficients
    float mEnergyCoeff = 0.9996f;       // ~50ms smoothing at 48kHz
    float mGainCoeff = 0.9996f;         // ~50ms gain smoothing

    // State Variables
    float mLowFreqEnergy = 0.0f;
    float mHighFreqEnergy = 0.0f;
    bool mIsOwnVoice = false;
    int mHoldSamples = 4800;            // 100ms hold time
    int mHoldCounter = 0;

    float mCurrentOcclusionGain = 1.0f;
    float mTargetOcclusionGain = 1.0f;

    void updateCoefficients();
};
