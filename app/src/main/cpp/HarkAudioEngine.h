#pragma once

#include <oboe/Oboe.h>
#include "FilterChain.h"
#include "DynamicsProcessor.h"
#include <memory>
#include <vector>
#include <mutex>

/**
 * HarkAudioEngine inherits two Oboe callback interfaces:
 *   AudioStreamDataCallback – called by the audio thread (onAudioReady)
 *   AudioStreamErrorCallback – called by Oboe's error thread on disconnect
 *
 * Ref: Oboe ErrorHandling – https://github.com/google/oboe/blob/main/docs/GettingStarted.md#handling-errors
 */
class HarkAudioEngine : public oboe::AudioStreamDataCallback,
                        public oboe::AudioStreamErrorCallback {
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
    void setWdrcParameters(float thresholdDb, float ratio, float attackMs, float releaseMs);
    void setLimiterParameters(float thresholdDb, float ratio, float attackMs, float releaseMs);
    void setMakeupGain(float gainDb);
    void setPreGain(float gainDb);
    void setBypassMode(bool bypass);  // 通透模式 (true=直通, false=正常处理)

    // --- Oboe Callbacks ---

    /** Audio processing callback – called on the realtime audio thread. */
    oboe::DataCallbackResult onAudioReady(
        oboe::AudioStream *oboeStream, void *audioData, int32_t numFrames) override;

    /**
     * Error callback – called by Oboe's internal error thread AFTER the stream
     * has been closed due to disconnection (e.g. Bluetooth routing change).
     *
     * Critical: resets mIsRunning so a subsequent start() call succeeds instead
     * of returning early with "already running" on a dead stream.
     */
    void onErrorAfterClose(
        oboe::AudioStream *oboeStream, oboe::Result error) override;

private:
    // 删除拷贝和赋值操作符 - 防止意外的浅拷贝
    HarkAudioEngine(const HarkAudioEngine&) = delete;
    HarkAudioEngine& operator=(const HarkAudioEngine&) = delete;
    HarkAudioEngine(HarkAudioEngine&&) = delete;
    HarkAudioEngine& operator=(HarkAudioEngine&&) = delete;
    void updateDSPParameters();

    oboe::AudioStream *mInputStream = nullptr;
    oboe::AudioStream *mOutputStream = nullptr;
    // 獨立處理左右聲道
    FilterChain mFilterChainLeft;
    FilterChain mFilterChainRight;
    double sampleRate;
    std::vector<float> mBandGains;
    std::vector<float> mBandQs;
    bool mIsRunning = false;
    int32_t mInputDeviceId = oboe::kUnspecified;
    int32_t mOutputDeviceId = oboe::kUnspecified;

    // 动态处理器 (左右耳獨立)
    DynamicsProcessor mWdrcLeft;
    DynamicsProcessor mWdrcRight;
    DynamicsProcessor mLimiterLeft;
    DynamicsProcessor mLimiterRight;
    float mMakeupGainDb;
    float mMakeupGainLinear;
    float mPreGainLinear;
    float mAutoHeadroomLinear = 1.0f; // Automatic headroom for AGC-O
    float mDuckingGain = 1.0f;        // Ducking for own voice suppression
    float mInputEnvelope = 0.0f;      // Smoothed input level for VAD

    // 线程安全 - 保护 DSP 参数的竞态访问
    std::mutex mDSPMutex;
    
    // 诊断模式
    bool mBypassMode = false;  // true=直通（无处理），false=正常DSP处理
};