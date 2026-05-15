#pragma once

#include <oboe/Oboe.h>
#include <atomic>
#include <mutex>

#include "DynamicsProcessor.h"
#include "NoiseSuppressor.h"
#include "GestureDetector.h"
#include "BiquadFilter.h"
#include "FilterChain.h"
#include "LinkwitzRileyCrossover.h"

/**
 * HarkAudioEngine — Hark 助聽器 DSP 音訊引擎 (Refactored v3, 2026-05-12)
 *
 * 架構優化:
 *   - 麥克風：優先使用 Unprocessed (全向/原始)，繞過系統染色。
 *   - Pinna Restore：升級為 2.7kHz & 4.5kHz 雙峰補強模型。
 *   - 增益：修復 Double-Gain Bug，UI 滑桿直接映射至 WDRC 輸入端。
 *   - 模式：新增 AUTO 自動模式，支援跨場景淡入淡出。
 */
class HarkAudioEngine : public oboe::AudioStreamDataCallback,
                        public oboe::AudioStreamErrorCallback {
public:
    HarkAudioEngine();
    ~HarkAudioEngine();

    // Non-copyable / non-movable
    HarkAudioEngine(const HarkAudioEngine&)            = delete;
    HarkAudioEngine& operator=(const HarkAudioEngine&) = delete;
    HarkAudioEngine(HarkAudioEngine&&)                 = delete;
    HarkAudioEngine& operator=(HarkAudioEngine&&)      = delete;

    // --- Lifecycle ---
    void start();
    void stop();
    bool isEngineRunning() const;

    // --- Situational Modes ---
    enum class SituationalMode {
        TRANSPARENCY,  // 全向/透明：輕壓縮，NS關，保留真實環境音
        CONVERSATION,  // 人聲增強：帶通300-3400Hz，NS強，適合對話
        OUTDOOR,       // 戶外防風：100Hz以下陡切，保護聽感
        CINEMA,        // 影音模式：V形EQ（低高音補強），寬動態範圍
        AUTO           // 自動模式：根據環境特徵自動切換
    };
    void setSituationalMode(SituationalMode mode);
    void setMasterGain(float gain);
    void setMuted(bool muted);
    bool isMuted() const { return mIsMuted; }
    void setPinnaEnabled(bool enabled);

    // --- EQ Control (Lock-free) ---
    void setBandGain(int bandIndex, float gainDb);
    void setBandQ(int bandIndex, float q_factor);

    // --- Per-band WDRC individualisation ---
    void setBandWdrcParameters(int band, float thresholdDb, float ratio,
                               float attackMs, float releaseMs);

    // --- DSP Control ---
    void setInputDeviceId(int32_t deviceId);
    void setBypassMode(bool bypass);
    void setNoiseReductionEnabled(bool enabled);
    void calibrateNoiseSuppressor();
    void resetGesture();
    void logLatencyStatistics();
    void setInputGainOffset(float gainDb); // 調整輸入來源補償增益

    // --- WDRC / Limiter ---
    void setWdrcParameters(float thresholdDb, float ratio,
                           float attackMs,    float releaseMs);
    void setLimiterParameters(float thresholdDb, float ratio,
                              float attackMs,    float releaseMs);

    // --- Oboe callbacks ---
    oboe::DataCallbackResult onAudioReady(
        oboe::AudioStream* oboeStream, void* audioData, int32_t numFrames) override;
    void onErrorAfterClose(
        oboe::AudioStream* oboeStream, oboe::Result error) override;

    // --- Environment Monitoring ---
    float getBandEnergy(int band) const;

private:
    // --- Internal helpers ---
    bool setupStreams();
    void updateDSPParameters();
    void recomputePrescriptionGains();

    // --- Streams ---
    oboe::AudioStream* mInputStream  = nullptr;
    oboe::AudioStream* mOutputStream = nullptr;
    double sampleRate = 48000.0;
    bool   mIsRunning = false;
    int32_t mInputDeviceId  = oboe::kUnspecified;

    // --- State ---
    SituationalMode mCurrentMode = SituationalMode::TRANSPARENCY;
    bool mPinnaEnabled = true;

    // --- Gains & EQ ---
    static constexpr int NUM_UI_BANDS = 16;
    std::atomic<float> mBandGains[NUM_UI_BANDS];
    std::atomic<bool>  mGainDirty[NUM_UI_BANDS];
    float mBandQs[NUM_UI_BANDS];

    // 16-Band EQ is now BYPASSED by default to avoid double gain.
    FilterChain mEqLeft;
    FilterChain mEqRight;

    static constexpr int NUM_INTERNAL_BANDS = 8;
    float mPrescriptionGains[NUM_INTERNAL_BANDS];
    float mPrescriptionTargets[NUM_INTERNAL_BANDS];
    static constexpr float GAIN_SMOOTH_ALPHA = 0.8f; // 加快響應速度 (原為 0.9995f，導致更新過慢)

    // --- Crossover & WDRC ---
    LinkwitzRileyCrossover mXoverMidL,  mXoverMidR;
    LinkwitzRileyCrossover mXoverLowL,  mXoverLowR;
    LinkwitzRileyCrossover mXoverHighL, mXoverHighR;
    LinkwitzRileyCrossover mXoverVLowL, mXoverVLowR;
    LinkwitzRileyCrossover mXoverLMidL, mXoverLMidR;
    LinkwitzRileyCrossover mXoverHMidL, mXoverHMidR;
    LinkwitzRileyCrossover mXoverVHiL,  mXoverVHiR;

    DynamicsProcessor mWdrcL[NUM_INTERNAL_BANDS];
    DynamicsProcessor mWdrcR[NUM_INTERNAL_BANDS];
    DynamicsProcessor mLimiterL, mLimiterR;

    // --- Front-end ---
    NoiseSuppressor mNoiseSuppressorL, mNoiseSuppressorR;
    
    // Dual-Peak Pinna Restore
    BiquadFilter mPinnaPrimaryL,   mPinnaPrimaryR;
    BiquadFilter mPinnaSecondaryL, mPinnaSecondaryR;

    GestureDetector mGestureDetector;
    GestureState    mCurrentGestureState = GestureState::IDLE;

    bool mBypassMode = false;
    std::atomic<float> mMasterGain{1.0f};
    std::atomic<float> mInputGainFactor{1.0f};
    std::atomic<bool>  mIsMuted{false};
    std::mutex mDSPMutex;
};