#pragma once

#include <atomic>
#include <mutex>
#include <oboe/Oboe.h>

#include "BiquadFilter.h"
#include "DynamicsProcessor.h"
#include "FilterChain.h"
#include "GestureDetector.h"
#include "LinkwitzRileyCrossover.h"
#include "LockFreeQueue.h"
#include "NoiseSuppressor.h"
#include "TransientSuppressor.h"
#include "OwnVoiceDetector.h"
#include <memory>

/**
 * HarkAudioEngine — Hark 助聽器 DSP 音訊引擎 (Refactored v4, 2026-06-07)
 *
 * 架構優化:
 *   - 麥克風：優先使用 Unprocessed (全向/原始)，繞過系統染色。
 *   - 增益：修復 Double-Gain Bug，UI 滑桿直接映射至 WDRC 輸入端。
 *   - 模式：新增 AUTO 自動模式，支援跨場景淡入淡出。
 *   - 脈衝降噪 (Transient Suppressor)：時域波峰/波谷快速包絡檢測與極速抑制。
 *   - 自我語音偵測 (Own Voice Detector)：偵測低高頻能量比以動態抑制低頻 (堵耳效應管理)。
 * 
 * TODO: 
 *   - 頻率降低/移頻技術 (Frequency Lowering)：建議採用時域非線性頻域壓縮或快速 FFT 頻譜重映射。
 * 
 * 技術排除說明 (Exclusion Notes):
 *   - 迴授音消除 (Feedback Cancellation): 
 *     因自適應濾波器 (NLMS/LMS) 在閉環系統中對音樂或單一頻率人聲易產生發散與誤判 (把外界音樂當作回授消除)，在此予以排除。
 *   - 聲束成形 (Beamforming): 
 *     因 Android 系統硬體 HAL 多樣性與限制 (戴耳機時強行覆蓋為單麥錄音，且無法繞過廠商內置之雙麥處理)，在此予以排除。
 */
class HarkAudioEngine : public oboe::AudioStreamDataCallback,
                        public oboe::AudioStreamErrorCallback {
public:
  HarkAudioEngine();
  ~HarkAudioEngine();

  // Non-copyable / non-movable
  HarkAudioEngine(const HarkAudioEngine &) = delete;
  HarkAudioEngine &operator=(const HarkAudioEngine &) = delete;
  HarkAudioEngine(HarkAudioEngine &&) = delete;
  HarkAudioEngine &operator=(HarkAudioEngine &&) = delete;

  // --- Lifecycle ---
  void start();
  void stop(bool disableRecovery = true);
  bool isEngineRunning() const;

  // --- Situational Modes ---
  enum class SituationalMode {
    TRANSPARENCY, // 全向/透明：輕壓縮，NS關，保留真實環境音
    CONVERSATION, // 人聲增強：帶通300-3400Hz，NS強，適合對話
    OUTDOOR,      // 戶外防風：100Hz以下陡切，保護聽感
    CINEMA,       // 影音模式：V形EQ（低高音補強），寬動態範圍
    AUTO          // 自動模式：根據環境特徵自動切換
  };
  void setSituationalMode(SituationalMode mode);
  void setMasterGain(float gain);
  void setMuted(bool muted);
  bool isMuted() const { return mIsMuted; }

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
  void setInputGainOffset(float gainDb);  // 調整輸入來源補償增益
  void setUseHeadsetMic(bool useHeadset); // 設定是否使用耳機麥克風

  // --- Media Capture Mode ---
  void setMediaCaptureMode(bool enabled);
  void pushMediaAudioData(const float *data, int numFrames);

  // --- WDRC / Limiter ---
  void setWdrcParameters(float thresholdDb, float ratio, float attackMs,
                         float releaseMs);
  void setLimiterParameters(float thresholdDb, float ratio, float attackMs,
                            float releaseMs);

  // --- Oboe callbacks ---
  oboe::DataCallbackResult onAudioReady(oboe::AudioStream *oboeStream,
                                        void *audioData,
                                        int32_t numFrames) override;
  void onErrorAfterClose(oboe::AudioStream *oboeStream,
                         oboe::Result error) override;

  // --- Environment Monitoring ---
  float getBandEnergy(int band) const;

private:
  // --- Internal helpers ---
  bool setupStreams();
  void updateDSPParameters();
  void recomputePrescriptionGains();

  // --- Streams ---
  oboe::AudioStream *mInputStream = nullptr;
  oboe::AudioStream *mOutputStream = nullptr;
  double sampleRate = 48000.0;
  bool mIsRunning = false;
  std::atomic<bool> mAutoRecoveryEnabled{true};
  int32_t mInputDeviceId = oboe::kUnspecified;
  std::atomic<bool> mUseHeadsetMic{true};

  // --- Media Capture ---
  std::atomic<bool> mMediaCaptureMode{false};
  std::unique_ptr<LockFreeQueue<float>> mMediaAudioQueue;

  // --- State ---
  SituationalMode mCurrentMode = SituationalMode::TRANSPARENCY;

  // --- Gains & EQ ---
  static constexpr int NUM_UI_BANDS = 16;
  std::atomic<float> mBandGains[NUM_UI_BANDS];
  std::atomic<bool> mGainDirty[NUM_UI_BANDS];
  float mBandQs[NUM_UI_BANDS];

  // 16-Band EQ is now BYPASSED by default to avoid double gain.
  FilterChain mEqLeft;
  FilterChain mEqRight;

  static constexpr int NUM_INTERNAL_BANDS = 8;
  float mPrescriptionGains[NUM_INTERNAL_BANDS];
  float mPrescriptionTargets[NUM_INTERNAL_BANDS];
  float mPrescriptionBaseTargets[NUM_INTERNAL_BANDS] = {};
  float mMaxBoostDb = 0.0f;
  float mSumBoostDb = 0.0f;
  std::atomic<float> mInputRmsSlow{0.001f};
  static constexpr float GAIN_SMOOTH_ALPHA =
      0.8f; // 加快響應速度 (原為 0.9995f，導致更新過慢)

  // --- Crossover & WDRC ---
  LinkwitzRileyCrossover mXoverMidL, mXoverMidR;
  LinkwitzRileyCrossover mXoverLowL, mXoverLowR;
  LinkwitzRileyCrossover mXoverHighL, mXoverHighR;
  LinkwitzRileyCrossover mXoverVLowL, mXoverVLowR;
  LinkwitzRileyCrossover mXoverLMidL, mXoverLMidR;
  LinkwitzRileyCrossover mXoverHMidL, mXoverHMidR;
  LinkwitzRileyCrossover mXoverVHiL, mXoverVHiR;

  DynamicsProcessor mWdrcL[NUM_INTERNAL_BANDS];
  DynamicsProcessor mWdrcR[NUM_INTERNAL_BANDS];
  DynamicsProcessor mLimiterL, mLimiterR;

  // --- Front-end ---
  NoiseSuppressor mNoiseSuppressorL, mNoiseSuppressorR;
  TransientSuppressor mTransientSuppressorL, mTransientSuppressorR;
  OwnVoiceDetector mOwnVoiceDetectorL, mOwnVoiceDetectorR;

  // DC Blocker state variables per channel
  float mDcLastInL = 0.0f;
  float mDcLastInR = 0.0f;
  float mDcLastOutL = 0.0f;
  float mDcLastOutR = 0.0f;

  GestureDetector mGestureDetector;
  GestureState mCurrentGestureState = GestureState::IDLE;

  bool mBypassMode = false;
  std::atomic<float> mMasterGain{1.0f};
  std::atomic<float> mInputGainFactor{1.0f};
  std::atomic<bool> mIsMuted{false};
  std::mutex mDSPMutex;

public:
  struct DspParameterSnapshot {
    float compressThresholdDb = -20.0f;
    float compressRatio = 1.2f;
    float expanderThresholdDb = -72.0f;
    float expanderRatio = 0.5f;
    float attackMs = 10.0f;
    float releaseMs = 600.0f;
  };

  // --- Testing and Diagnostics API ---
  void setDcBlockerEnabled(bool enabled);
  bool isDcBlockerEnabled() const {
    return mDcBlockerEnabled.load(std::memory_order_relaxed);
  }

  void setCrossoverWdrcEnabled(bool enabled);
  bool isCrossoverWdrcEnabled() const {
    return mCrossoverWdrcEnabled.load(std::memory_order_relaxed);
  }

  void setLimiterEnabled(bool enabled);
  bool isLimiterEnabled() const {
    return mLimiterEnabled.load(std::memory_order_relaxed);
  }

  void setWdrcExpanderThreshold(float thresholdDb);
  float getWdrcExpanderThreshold() const {
    return mCurrentExpanderThresholdDb.load(std::memory_order_relaxed);
  }

  void setTransientSuppressorEnabled(bool enabled);
  bool isTransientSuppressorEnabled() const {
    return mTransientSuppressorEnabled.load(std::memory_order_relaxed);
  }

  void setOwnVoiceDetectorEnabled(bool enabled);
  bool isOwnVoiceDetectorEnabled() const {
    return mOwnVoiceDetectorEnabled.load(std::memory_order_relaxed);
  }

  void setStreamOverrides(int sharingMode, int inputPreset);
  void getStreamOverrides(int &sharingMode, int &inputPreset) const;

  void getDiagnosticMetrics(float *outMetrics);
  void resetAudioChannels();
  void setIsBluetoothInput(bool isBluetooth) { mIsBluetoothInput.store(isBluetooth, std::memory_order_relaxed); }
  void setHeadphonesConnected(bool connected) { mHeadphonesConnected.store(connected, std::memory_order_relaxed); }

private:
  void updateWdrcParameters(float compThresh, float compRatio, float expThresh,
                            float expRatio, float attackMs, float releaseMs);
  DspParameterSnapshot mParamsBuffers[2];
  std::atomic<int> mActiveParamsIndex{0};
  // Bypass flags
  std::atomic<bool> mDcBlockerEnabled{true};
  std::atomic<bool> mCrossoverWdrcEnabled{true};
  std::atomic<bool> mLimiterEnabled{true};
  std::atomic<bool> mTransientSuppressorEnabled{true};
  std::atomic<bool> mOwnVoiceDetectorEnabled{true};
  std::atomic<float> mCurrentExpanderThresholdDb{-72.0f};

  // Override settings - default to Default (0) and Unprocessed (4)
  std::atomic<int> mSharingModeOverride{
      0}; // 0 = Default, 1 = Shared, 2 = Exclusive
  std::atomic<int> mInputPresetOverride{
      4}; // 0 = Default, 1 = VoiceComm, 2 = VoiceRec, 3 = Camcorder, 4 =
          // Unprocessed
  std::atomic<bool> mIsBluetoothInput{false};
  std::atomic<bool> mHeadphonesConnected{false};

  // WDRC tracking variables
  std::atomic<float> mWdrcCompressThresholdDb{-20.0f};
  std::atomic<float> mWdrcCompressRatio{1.2f};
  std::atomic<float> mWdrcAttackMs{10.0f};
  std::atomic<float> mWdrcReleaseMs{600.0f};
  std::atomic<float> mWdrcExpanderRatio{0.5f};

  // Diagnostics registers (Lock-Free)
  std::atomic<float> mDiagRawInputPeakLinear{0.0f};
  std::atomic<float> mDiagOutputPeakLinear{0.0f};
  std::atomic<int> mDiagWouldBlockCount{0};
  std::atomic<int> mDiagCallbackCount{0};
  std::atomic<int> mDiagInputXRunCount{0};
  std::atomic<int> mDiagOutputXRunCount{0};

  // Full-Duplex synchronization states
  std::atomic<int> mCountCallbacksToDrain{0};
  std::atomic<int> mCountInputBurstsCushion{0};
  std::atomic<int> mCountCallbacksToDiscard{0};
};