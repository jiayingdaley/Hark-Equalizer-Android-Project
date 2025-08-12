#pragma once

#include <oboe/Oboe.h>
#include "FilterChain.h"
#include "DynamicsProcessor.h"
#include <memory>
#include <vector>

class HarkAudioEngine : public oboe::AudioStreamDataCallback {
public:
    bool setupStreams();
    bool isEngineRunning() const;

    HarkAudioEngine();
    ~HarkAudioEngine();

    void start();
    void stop();
    void setBandGain(int bandIndex, float gainDb);
    void setBandQ(int bandIndex, float q_factor);
    void setInputDeviceId(int32_t deviceId);
    void setOutputDeviceId(int32_t deviceId);
    // 用於從 Kotlin 控制壓縮器參數的函數
    void setWdrcParameters(float thresholdDb, float ratio, float attackMs, float releaseMs);
    void setLimiterParameters(float thresholdDb, float ratio, float attackMs, float releaseMs);
    // 設定補償增益的函數 (可選，我們先在內部設定)
    void setMakeupGain(float gainDb);

    // Oboe callback
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *oboeStream, void *audioData, int32_t numFrames) override;

private:

    oboe::AudioStream *mInputStream = nullptr;
    oboe::AudioStream *mOutputStream = nullptr;
    FilterChain filterChain;
    double sampleRate;
    std::vector<float> mBandGains;
    std::vector<float> mBandQs;
    bool mIsRunning = false;
    int32_t mInputDeviceId = oboe::kUnspecified;
    int32_t mOutputDeviceId = oboe::kUnspecified;

    // --- 動態處理器 ---
    DynamicsProcessor mWdrc;
    DynamicsProcessor mLimiter;
    // --- 補償增益成員 ---
    float mMakeupGainDb;      // 以 dB 為單位的值，方便理解和調整
    float mMakeupGainLinear;  // 線性乘數，用於實際計算
};