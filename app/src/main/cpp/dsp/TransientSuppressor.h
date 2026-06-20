#pragma once

/**
 * TransientSuppressor - 時域脈衝噪音抑制器 (Impulse Noise Suppressor)
 * 
 * 藉由計算 Fast Envelope (反應快，約 1ms) 與 Slow Envelope (反應慢，約 100ms)
 * 的比例 (Crest Factor)。當比例大於設定的門檻值 (通常為 10dB - 15dB) 時，
 * 判定為突然發生的敲擊或突發噪音，瞬間將增益壓低以保護聽損患者的耳朵。
 */
class TransientSuppressor {
public:
    TransientSuppressor();

    void setSampleRate(double sampleRate);
    void setParameters(float thresholdDb, float attenuationDb, float holdMs, float releaseMs);
    void setEnabled(bool enabled);

    // 處理單一樣本 (時域)
    float process(float inputSample);

private:
    double mSampleRate;
    bool mEnabled;

    // Parameters
    float mThresholdDb;     // Crest factor 觸發門檻 (Raised from 12.0f to avoid triggering on speech)
    float mMinLevelDb;     // 絕對能量觸發門檻 (dBFS)
    float mAttenuationDb;   // 觸發時的衰減值 (dB)
    float mHoldMs;          // 壓低增益的維持時間 (ms)
    float mReleaseMs;       // 釋放回到 1.0f 的時間 (ms)

    // Coefficients
    float mFastCoeff;       // ~1ms at 48kHz
    float mSlowCoeff;       // ~100ms at 48kHz
    float mAttackCoeff;     // ~0.5ms at 48kHz
    float mReleaseCoeff;    // ~50ms at 48kHz

    // State Variables
    float mEnvFast;
    float mEnvSlow;
    int mHoldSamples;
    int mHoldCounter;
    float mCurrentGain;

    void updateCoefficients();
};
