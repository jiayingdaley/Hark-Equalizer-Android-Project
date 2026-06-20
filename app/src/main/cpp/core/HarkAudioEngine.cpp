/**
 * HarkAudioEngine.cpp — Hark 助聽器 DSP 音訊引擎 (Refactored v3, 2026-05-12)
 *
 * 重大更新:
 *   - [Pinna] 升級為雙峰補強模型 (2.7kHz 主峰, 4.5kHz 次峰)。
 *   - [Mic] 麥克風改用 Unprocessed 原始模式，繞過系統濾波。
 *   - [Gain] 修復 Double-Gain Bug (16-Band EQ 現僅作為 UI 控制，實際增益併入
 * WDRC 前端)。
 *   - [Mode] 增加 SituationalMode::AUTO 支援。
 */
#include "HarkAudioEngine.h"
#include "HarkDspConfig.h"
#include <android/log.h>
#include <chrono>
#include <cmath>
#include <cstring>
#include <thread>

#define LOG_TAG "HarkAudioEngine"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static constexpr int CHANNEL_COUNT = HarkDspConfig::CHANNEL_COUNT;

// UI 16 Band center frequencies
static const double UI_CENTER_FREQS[16] = {
    HarkDspConfig::UI_CENTER_FREQS[0],  HarkDspConfig::UI_CENTER_FREQS[1],
    HarkDspConfig::UI_CENTER_FREQS[2],  HarkDspConfig::UI_CENTER_FREQS[3],
    HarkDspConfig::UI_CENTER_FREQS[4],  HarkDspConfig::UI_CENTER_FREQS[5],
    HarkDspConfig::UI_CENTER_FREQS[6],  HarkDspConfig::UI_CENTER_FREQS[7],
    HarkDspConfig::UI_CENTER_FREQS[8],  HarkDspConfig::UI_CENTER_FREQS[9],
    HarkDspConfig::UI_CENTER_FREQS[10], HarkDspConfig::UI_CENTER_FREQS[11],
    HarkDspConfig::UI_CENTER_FREQS[12], HarkDspConfig::UI_CENTER_FREQS[13],
    HarkDspConfig::UI_CENTER_FREQS[14], HarkDspConfig::UI_CENTER_FREQS[15]
};



HarkAudioEngine::HarkAudioEngine()
    : sampleRate(HarkDspConfig::SAMPLE_RATE), mNoiseSuppressorL(HarkDspConfig::SAMPLE_RATE),
      mNoiseSuppressorR(HarkDspConfig::SAMPLE_RATE), mGestureDetector(HarkDspConfig::SAMPLE_RATE),
      mEqLeft(NUM_UI_BANDS), mEqRight(NUM_UI_BANDS) {

  mMediaAudioQueue = std::make_unique<LockFreeQueue<float>>(
      static_cast<int>(HarkDspConfig::SAMPLE_RATE) * 2 * 2); // 2 seconds of stereo float buffer

  for (int i = 0; i < NUM_UI_BANDS; ++i) {
    mBandGainsL[i].store(0.0f, std::memory_order_relaxed);
    mBandGainsR[i].store(0.0f, std::memory_order_relaxed);
    mGainDirty[i].store(false, std::memory_order_relaxed);
    mBandQs[i] = HarkDspConfig::DEFAULT_BAND_Q;
  }
  for (int b = 0; b < NUM_INTERNAL_BANDS; ++b) {
    mPrescriptionGainsL[b] = 1.0f;
    mPrescriptionGainsR[b] = 1.0f;
    mPrescriptionTargetsL[b] = 1.0f;
    mPrescriptionTargetsR[b] = 1.0f;
  }
  // Initialize default double-buffered snapshot parameters
  updateWdrcParameters(HarkDspConfig::DEFAULT_WDRC_COMP_THRESH_DB,
                       HarkDspConfig::DEFAULT_WDRC_COMP_RATIO,
                       HarkDspConfig::DEFAULT_WDRC_EXP_THRESH_DB,
                       HarkDspConfig::DEFAULT_WDRC_EXP_RATIO,
                       HarkDspConfig::DEFAULT_WDRC_ATTACK_MS,
                       HarkDspConfig::DEFAULT_WDRC_RELEASE_MS);
  updateDSPParameters();
}

HarkAudioEngine::~HarkAudioEngine() { stop(); }

void HarkAudioEngine::updateDSPParameters() {
  // LR4 Crossover setup
  const double xoFreqs[7] = {
      HarkDspConfig::XO_MID_HZ,
      HarkDspConfig::XO_LOW_HZ,
      HarkDspConfig::XO_HIGH_HZ,
      HarkDspConfig::XO_VLOW_HZ,
      HarkDspConfig::XO_LMID_HZ,
      HarkDspConfig::XO_HMID_HZ,
      HarkDspConfig::XO_VHI_HZ
  };
  mXoverMidL.setFrequency(xoFreqs[0], sampleRate);
  mXoverMidR.setFrequency(xoFreqs[0], sampleRate);
  mXoverLowL.setFrequency(xoFreqs[1], sampleRate);
  mXoverLowR.setFrequency(xoFreqs[1], sampleRate);
  mXoverHighL.setFrequency(xoFreqs[2], sampleRate);
  mXoverHighR.setFrequency(xoFreqs[2], sampleRate);
  mXoverVLowL.setFrequency(xoFreqs[3], sampleRate);
  mXoverVLowR.setFrequency(xoFreqs[3], sampleRate);
  mXoverLMidL.setFrequency(xoFreqs[4], sampleRate);
  mXoverLMidR.setFrequency(xoFreqs[4], sampleRate);
  mXoverHMidL.setFrequency(xoFreqs[5], sampleRate);
  mXoverHMidR.setFrequency(xoFreqs[5], sampleRate);
  mXoverVHiL.setFrequency(xoFreqs[6], sampleRate);
  mXoverVHiR.setFrequency(xoFreqs[6], sampleRate);

  // WDRC (8 bands)
  const DspParameterSnapshot &params =
      mParamsBuffers[mActiveParamsIndex.load(std::memory_order_relaxed)];
  for (int b = 0; b < NUM_INTERNAL_BANDS; ++b) {
    float bandExpThresh = params.expanderThresholdDb;
    float bandExpRatio = params.expanderRatio;
    if (b >= 2 && b <= 5) {
      bandExpThresh = HarkDspConfig::WDRC_SPEECH_BANDS_EXP_THRESH_DB;
      bandExpRatio = HarkDspConfig::WDRC_SPEECH_BANDS_EXP_RATIO;
    } else {
      if (params.expanderThresholdDb > HarkDspConfig::WDRC_SPECIALIZATION_BOUNDARY_DB) {
        if (b == 0) {
          bandExpThresh = fmaxf(params.expanderThresholdDb, HarkDspConfig::WDRC_BAND0_EXP_THRESH_DB);
          bandExpRatio = HarkDspConfig::WDRC_BAND0_EXP_RATIO;
        } else if (b == 1) {
          bandExpThresh = fmaxf(params.expanderThresholdDb, HarkDspConfig::WDRC_BAND1_EXP_THRESH_DB);
          bandExpRatio = HarkDspConfig::WDRC_BAND1_EXP_RATIO;
        } else if (b == 7) {
          bandExpThresh = fmaxf(params.expanderThresholdDb, HarkDspConfig::WDRC_BAND7_EXP_THRESH_DB);
          bandExpRatio = HarkDspConfig::WDRC_BAND7_EXP_RATIO;
        }
      }
    }
    mWdrcL[b].setParameters(params.compressThresholdDb, params.compressRatio,
                            bandExpThresh, bandExpRatio,
                            params.attackMs, params.releaseMs, sampleRate);
    mWdrcR[b].setParameters(params.compressThresholdDb, params.compressRatio,
                            bandExpThresh, bandExpRatio,
                            params.attackMs, params.releaseMs, sampleRate);
  }

  // Limiter (Strict Dynamic Ceiling of -4.5 dBFS for MPO User Protection)
  mLimiterL.setParameters(HarkDspConfig::LIMITER_THRESHOLD_DB,
                          HarkDspConfig::LIMITER_RATIO,
                          HarkDspConfig::LIMITER_EXP_THRESH,
                          HarkDspConfig::LIMITER_EXP_RATIO,
                          HarkDspConfig::LIMITER_ATTACK_MS,
                          HarkDspConfig::LIMITER_RELEASE_MS, sampleRate);
  mLimiterR.setParameters(HarkDspConfig::LIMITER_THRESHOLD_DB,
                          HarkDspConfig::LIMITER_RATIO,
                          HarkDspConfig::LIMITER_EXP_THRESH,
                          HarkDspConfig::LIMITER_EXP_RATIO,
                          HarkDspConfig::LIMITER_ATTACK_MS,
                          HarkDspConfig::LIMITER_RELEASE_MS, sampleRate);

  // 16-Band EQ (Used for UI mapping, bypassed in process loop to avoid double
  // gain)
  for (int i = 0; i < NUM_UI_BANDS; ++i) {
    mEqLeft.updateBand(i, BiquadFilter::Type::Peaking, sampleRate,
                       UI_CENTER_FREQS[i], 0.0f, mBandQs[i]);
    mEqRight.updateBand(i, BiquadFilter::Type::Peaking, sampleRate,
                        UI_CENTER_FREQS[i], 0.0f, mBandQs[i]);
  }

  mTransientSuppressorL.setSampleRate(sampleRate);
  mTransientSuppressorR.setSampleRate(sampleRate);
  mOwnVoiceDetectorL.setSampleRate(sampleRate);
  mOwnVoiceDetectorR.setSampleRate(sampleRate);
}

void HarkAudioEngine::recomputePrescriptionGains() {
  calculatePrescriptionGains(mBandGainsL, mPrescriptionBaseTargetsL, mMaxBoostDbL, mSumBoostDbL);
  calculatePrescriptionGains(mBandGainsR, mPrescriptionBaseTargetsR, mMaxBoostDbR, mSumBoostDbR);

  // Load the active snapshot parameters and apply them consistently to all 8
  // bands
  const DspParameterSnapshot &params =
      mParamsBuffers[mActiveParamsIndex.load(std::memory_order_relaxed)];
  for (int b = 0; b < NUM_INTERNAL_BANDS; ++b) {
    float bandExpThresh = params.expanderThresholdDb;
    float bandExpRatio = params.expanderRatio;
    if (b >= 2 && b <= 5) {
      bandExpThresh = HarkDspConfig::WDRC_SPEECH_BANDS_EXP_THRESH_DB;
      bandExpRatio = HarkDspConfig::WDRC_SPEECH_BANDS_EXP_RATIO;
    } else {
      if (params.expanderThresholdDb > HarkDspConfig::WDRC_SPECIALIZATION_BOUNDARY_DB) {
        if (b == 0) {
          bandExpThresh = fmaxf(params.expanderThresholdDb, HarkDspConfig::WDRC_BAND0_EXP_THRESH_DB);
          bandExpRatio = HarkDspConfig::WDRC_BAND0_EXP_RATIO;
        } else if (b == 1) {
          bandExpThresh = fmaxf(params.expanderThresholdDb, HarkDspConfig::WDRC_BAND1_EXP_THRESH_DB);
          bandExpRatio = HarkDspConfig::WDRC_BAND1_EXP_RATIO;
        } else if (b == 7) {
          bandExpThresh = fmaxf(params.expanderThresholdDb, HarkDspConfig::WDRC_BAND7_EXP_THRESH_DB);
          bandExpRatio = HarkDspConfig::WDRC_BAND7_EXP_RATIO;
        }
      }
    }
    mWdrcL[b].setParameters(params.compressThresholdDb, params.compressRatio,
                            bandExpThresh, bandExpRatio,
                            params.attackMs, params.releaseMs, sampleRate);
    mWdrcR[b].setParameters(params.compressThresholdDb, params.compressRatio,
                            bandExpThresh, bandExpRatio,
                            params.attackMs, params.releaseMs, sampleRate);
  }
}

void HarkAudioEngine::start() {
  if (mIsRunning)
    return;
  mAutoRecoveryEnabled.store(true);
  if (setupStreams()) {
    mIsRunning = true;
    logLatencyStatistics();
  }
}

void HarkAudioEngine::stop(bool disableRecovery) {
  if (disableRecovery) {
    mAutoRecoveryEnabled.store(false);
  }
  if (!mIsRunning && !mInputStream && !mOutputStream)
    return;
  if (mInputStream) {
    mInputStream->stop();
    mInputStream->close();
    mInputStream = nullptr;
  }
  if (mOutputStream) {
    mOutputStream->stop();
    mOutputStream->close();
    mOutputStream = nullptr;
  }
  if (mMediaAudioQueue) {
    mMediaAudioQueue->clear();
  }
  mIsRunning = false;
}

bool HarkAudioEngine::isEngineRunning() const {
  return mOutputStream &&
         mOutputStream->getState() != oboe::StreamState::Closed;
}

bool HarkAudioEngine::setupStreams() {
  if (!mHeadphonesConnected.load(std::memory_order_relaxed)) {
    LOGW("setupStreams blocked: headphones are not connected. Preventing phone "
         "speaker playback!");
    return false;
  }
  bool useHeadset = mUseHeadsetMic.load();
  // 為了極致低延遲與跨時脈物理穩定性：
  // 1. 當使用耳機收音時，輸入與輸出在同一個實體設備/時鐘源上，開啟 AAudio
  // Exclusive 獨占低延遲模式以追求極限。
  // 2. 當強制使用手機麥克風收音時，因為輸入（手機內建
  // Codec）與輸出（藍牙耳機晶片）在不同的物理時鐘源上，
  //    獨占模式會因為時脈不同步或 HAL 驅動拒絕而崩潰無聲。我們必須使用 Shared
  //    共享模式，讓系統 AudioFlinger 自動協調跨時區重採樣與時脈同步！
  int sharingOverride = mSharingModeOverride.load(std::memory_order_relaxed);
  oboe::SharingMode targetSharingMode =
      useHeadset ? oboe::SharingMode::Exclusive : oboe::SharingMode::Shared;
  if (sharingOverride == 1) {
    targetSharingMode = oboe::SharingMode::Shared;
  } else if (sharingOverride == 2) {
    targetSharingMode = oboe::SharingMode::Exclusive;
  }

  // Output Usage determines which audio policy path Android uses:
  // - Usage::Media → routed by Android to whatever is plugged in as a media
  // output (A2DP, wired, speaker).
  //   This is correct for a hearing aid: the OS routes to Bluetooth A2DP
  //   automatically under MODE_NORMAL.
  // - Usage::VoiceCommunication → forces the telephony SCO profile
  // (earpiece-quality), causing the
  //   "sounds like a phone call" bug. NEVER use this for Bluetooth output in a
  //   hearing aid app.
  // - Usage::Game → low-latency path, appropriate for wired/USB headsets only.
  bool isBluetooth = mIsBluetoothInput.load(std::memory_order_relaxed);
  oboe::Usage targetUsage;
  if (isBluetooth && useHeadset) {
    // Bluetooth SCO mode: MUST use VoiceCommunication to route output to SCO
    // headset
    targetUsage = oboe::Usage::VoiceCommunication;
  } else {
    // Wired / USB / Bluetooth A2DP: use Game mode for low-latency
    targetUsage = oboe::Usage::Game;
  }

  oboe::AudioStreamBuilder outBuilder;
  outBuilder.setDirection(oboe::Direction::Output)
      ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
      ->setSharingMode(targetSharingMode)
      ->setAudioApi(oboe::AudioApi::AAudio)
      ->setFormat(oboe::AudioFormat::Float)
      ->setChannelCount(CHANNEL_COUNT)
      ->setUsage(targetUsage)
      ->setSampleRate(48000) // 強制通用 48000Hz
                             // 輸出以對齊麥克風，消除一切取樣率轉換延遲與衝突
      ->setDataCallback(this)
      ->setErrorCallback(this);

  if (outBuilder.openStream(&mOutputStream) != oboe::Result::OK) {
    outBuilder.setAudioApi(oboe::AudioApi::Unspecified);
    outBuilder.setSharingMode(oboe::SharingMode::Shared);
    if (outBuilder.openStream(&mOutputStream) != oboe::Result::OK)
      return false;
  }

  sampleRate = mOutputStream->getSampleRate();
  int32_t outBurst = mOutputStream->getFramesPerBurst();
  // 4x burst size to tolerate scheduling jitter without underruns
  mOutputStream->setBufferSizeInFrames(outBurst * 4);

  LOGD("Output: SR=%.0f, Burst=%d, Buffer=%d, API=%d, Usage=Game, Sharing=%d",
       sampleRate, outBurst, mOutputStream->getBufferSizeInFrames(),
       (int)mOutputStream->getAudioApi(), (int)mOutputStream->getSharingMode());

  // Reset diagnostics and Full-Duplex state machine counters on stream
  // initialization
  mCountCallbacksToDrain.store(20, std::memory_order_relaxed);
  mCountInputBurstsCushion.store(
      2, std::memory_order_relaxed); // 2-burst cushion (192 frames) for safety
  mCountCallbacksToDiscard.store(10, std::memory_order_relaxed);

  mDiagRawInputPeakLinear.store(0.0f, std::memory_order_relaxed);
  mDiagOutputPeakLinear.store(0.0f, std::memory_order_relaxed);
  mDiagWouldBlockCount.store(0, std::memory_order_relaxed);
  mDiagCallbackCount.store(0, std::memory_order_relaxed);
  mDiagInputXRunCount.store(0, std::memory_order_relaxed);
  mDiagOutputXRunCount.store(0, std::memory_order_relaxed);

  updateDSPParameters();

  bool isMediaMode = mMediaCaptureMode.load();

  if (!isMediaMode) {
    // 為了極致低延遲與物理強行路由：
    // 1. 如果是使用耳機收音，我們保持 Device ID 為
    // oboe::kUnspecified，這能讓系統自動選用預設最優路由，並開啟 AAudio
    // Exclusive 獨占低延遲模式！
    // 2. 如果是強制使用手機收音，我們必須傳入精確的手機麥克風實體 ID
    // (mInputDeviceId), 強制 Android 繞過耳機，直接打開手機內建麥克風收音！
    int32_t targetInputDeviceId =
        useHeadset ? (int32_t)oboe::kUnspecified : mInputDeviceId;

    int presetOverride = mInputPresetOverride.load(std::memory_order_relaxed);
    oboe::InputPreset targetPreset = useHeadset
                                         ? oboe::InputPreset::VoiceCommunication
                                         : oboe::InputPreset::Camcorder;

    // 如果是藍牙耳機麥克風收音，為了確保物理路由正確建立，必須強制使用
    // VoiceCommunication
    if (mIsBluetoothInput.load(std::memory_order_relaxed)) {
      targetPreset = oboe::InputPreset::VoiceCommunication;
    } else if (presetOverride > 0) {
      if (presetOverride == 1)
        targetPreset = oboe::InputPreset::VoiceCommunication;
      else if (presetOverride == 2)
        targetPreset = oboe::InputPreset::VoiceRecognition;
      else if (presetOverride == 3)
        targetPreset = oboe::InputPreset::Camcorder;
      else if (presetOverride == 4)
        targetPreset = oboe::InputPreset::Unprocessed;
    }

    oboe::AudioStreamBuilder inBuilder;
    inBuilder.setDirection(oboe::Direction::Input)
        ->setDeviceId(targetInputDeviceId)
        ->setInputPreset(targetPreset)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(targetSharingMode)
        ->setAudioApi(oboe::AudioApi::AAudio)
        ->setFormat(oboe::AudioFormat::Float)
        ->setChannelCount(oboe::ChannelCount::Mono)
        ->setSampleRate(sampleRate) // Match opened output stream sample rate to
                                    // prevent conversion latency/drift
        ->setBufferCapacityInFrames(mOutputStream->getBufferCapacityInFrames() *
                                    2) // Expand capacity to prevent overruns
        ->setErrorCallback(this);      // 綁定錯誤回呼以偵測麥克風斷線

    auto result = inBuilder.openStream(&mInputStream);
    if (result != oboe::Result::OK) {
      LOGW("AAudio Explicit Exclusive failed: %s. Trying Shared mode with "
           "explicit ID...",
           oboe::convertToText(result));
      inBuilder.setSharingMode(oboe::SharingMode::Shared);
      result = inBuilder.openStream(&mInputStream);
    }
    if (result != oboe::Result::OK) {
      LOGW("AAudio Explicit Shared failed. Trying Unspecified device ID "
           "(kUnspecified) as safety fallback...");
      inBuilder.setDeviceId(oboe::kUnspecified);
      inBuilder.setSharingMode(oboe::SharingMode::Shared);
      result = inBuilder.openStream(&mInputStream);
    }
    if (result != oboe::Result::OK) {
      LOGW("AAudio Unspecified failed. Trying OpenSL ES with unspecified "
           "device...");
      inBuilder.setAudioApi(oboe::AudioApi::OpenSLES);
      inBuilder.setDeviceId(oboe::kUnspecified);
      inBuilder.setSharingMode(oboe::SharingMode::Shared);
      result = inBuilder.openStream(&mInputStream);
    }
    if (result != oboe::Result::OK) {
      LOGW(
          "All inputs failed. Trying ultimate generic unspecified fallback...");
      inBuilder.setAudioApi(oboe::AudioApi::AAudio);
      inBuilder.setDeviceId(oboe::kUnspecified);
      inBuilder.setSharingMode(oboe::SharingMode::Shared);
      inBuilder.setInputPreset(oboe::InputPreset::Generic);
      inBuilder.setSampleRate(sampleRate);
      result = inBuilder.openStream(&mInputStream);
    }

    if (result == oboe::Result::OK) {
      int32_t inBurst = mInputStream->getFramesPerBurst();
      // Set input buffer size to a safe value (e.g., 8 bursts) to allow room
      // for jitter while preventing excessive buffering latency.
      int32_t targetBufferSize = inBurst * 8;
      if (targetBufferSize > mInputStream->getBufferCapacityInFrames()) {
        targetBufferSize = mInputStream->getBufferCapacityInFrames();
      }
      mInputStream->setBufferSizeInFrames(targetBufferSize);
      LOGD("Input opened successfully: SR=%d, Burst=%d, Buffer=%d, "
           "Capacity=%d, API=%d, Sharing=%d",
           mInputStream->getSampleRate(), inBurst,
           mInputStream->getBufferSizeInFrames(),
           mInputStream->getBufferCapacityInFrames(),
           (int)mInputStream->getAudioApi(),
           (int)mInputStream->getSharingMode());
    } else {
      LOGE("Failed to open input stream completely. Cleaning up output stream "
           "to prevent zombie state!");
      if (mOutputStream) {
        mOutputStream->stop();
        mOutputStream->close();
        mOutputStream = nullptr;
      }
      return false;
    }
    mInputStream->requestStart();
  } else {
    LOGD("Media Capture Mode enabled. Bypassing Oboe Microphone Input.");
  }

  mOutputStream->requestStart();

  auto latency = mOutputStream->calculateLatencyMillis();
  if (latency)
    LOGD("Oboe Latency－－: %.2f ms", latency.value());

  return true;
}

void HarkAudioEngine::setBandGain(int ear, int bandIndex, float gainDb) {
  if (bandIndex < 0 || bandIndex >= NUM_UI_BANDS)
    return;
  if (ear == 0) {
    mBandGainsL[bandIndex].store(gainDb, std::memory_order_relaxed);
  } else {
    mBandGainsR[bandIndex].store(gainDb, std::memory_order_relaxed);
  }
  mGainDirty[bandIndex].store(true, std::memory_order_release);
  recomputePrescriptionGains();
}

void HarkAudioEngine::updateWdrcParameters(float compThresh, float compRatio,
                                           float expThresh, float expRatio,
                                           float attackMs, float releaseMs) {
  // 1. Update tracking atomics
  mWdrcCompressThresholdDb.store(compThresh, std::memory_order_relaxed);
  mWdrcCompressRatio.store(compRatio, std::memory_order_relaxed);
  mCurrentExpanderThresholdDb.store(expThresh, std::memory_order_relaxed);
  mWdrcExpanderRatio.store(expRatio, std::memory_order_relaxed);
  mWdrcAttackMs.store(attackMs, std::memory_order_relaxed);
  mWdrcReleaseMs.store(releaseMs, std::memory_order_relaxed);

  // 2. Write to inactive snapshot buffer
  int inactive = 1 - mActiveParamsIndex.load(std::memory_order_relaxed);
  mParamsBuffers[inactive].compressThresholdDb = compThresh;
  mParamsBuffers[inactive].compressRatio = compRatio;
  mParamsBuffers[inactive].expanderThresholdDb = expThresh;
  mParamsBuffers[inactive].expanderRatio = expRatio;
  mParamsBuffers[inactive].attackMs = attackMs;
  mParamsBuffers[inactive].releaseMs = releaseMs;

  // 3. Swap indices atomically (Publish)
  mActiveParamsIndex.store(inactive, std::memory_order_release);

  // 4. Apply to all bands with band-specific noise gate optimization
  for (int b = 0; b < NUM_INTERNAL_BANDS; ++b) {
    float bandExpThresh = expThresh;
    float bandExpRatio = expRatio;

    if (b >= 2 && b <= 5) {
      bandExpThresh = -55.0f;
      bandExpRatio = 0.66f;
    } else {
      // 針對降噪模式 (例如 CONVERSATION)，進行通道特異性調整
      if (expThresh > -55.0f) {
        if (b == 0) {
          // Band 0 (<250Hz)：將門檻拉高至 -32dBFS 且比例設為 2.5:1
          // (0.40f)，平滑閘控教室空調與環境低頻轟鳴噪聲
          bandExpThresh = std::max(expThresh, -32.0f);
          bandExpRatio = 0.40f;
        } else if (b == 1) {
          // Band 1 (250-500Hz)：將門檻拉高至 -35dBFS，比例設為 2.2:1 (0.45f)
          bandExpThresh = std::max(expThresh, -35.0f);
          bandExpRatio = 0.45f;
        } else if (b == 7) {
          // Band 7 (>6000Hz)：將門檻設為 -36dBFS，比例設為 2.5:1 (0.40f)
          // 平滑隔離麥克風高頻熱雜訊 (Hiss)，防止其被 Slope 處方增益強行放大
          bandExpThresh = std::max(expThresh, -36.0f);
          bandExpRatio = 0.40f;
        }
      }
    }

    mWdrcL[b].setParameters(compThresh, compRatio, bandExpThresh, bandExpRatio,
                            attackMs, releaseMs, sampleRate);
    mWdrcR[b].setParameters(compThresh, compRatio, bandExpThresh, bandExpRatio,
                            attackMs, releaseMs, sampleRate);
  }
}

void HarkAudioEngine::resetAudioChannels() {
  mDcLastInL = 0.0f;
  mDcLastInR = 0.0f;
  mDcLastOutL = 0.0f;
  mDcLastOutR = 0.0f;
}

void HarkAudioEngine::setSituationalMode(SituationalMode mode) {
  std::lock_guard<std::mutex> lock(mDSPMutex);
  mCurrentMode = mode;

  if (mode != SituationalMode::AUTO) {
    SituationalPreset preset = getPresetForMode(mode);
    updateWdrcParameters(
        preset.wdrc.compThresh,
        preset.wdrc.compRatio,
        preset.wdrc.expThresh,
        preset.wdrc.expRatio,
        preset.wdrc.attackMs,
        preset.wdrc.releaseMs
    );
    mNoiseReductionEnabled.store(preset.noiseReductionEnabled, std::memory_order_relaxed);
    mNoiseSuppressorL.setEnabled(preset.noiseReductionEnabled);
    mNoiseSuppressorR.setEnabled(preset.noiseReductionEnabled);
  } else {
    LOGD("Auto Mode active");
  }
  resetAudioChannels();
}

void HarkAudioEngine::setInputGainOffset(float gainDb) {
  mInputGainFactor.store(powf(10.0f, gainDb / 20.0f));
}

float HarkAudioEngine::getInputGainOffset() const {
  float factor = mInputGainFactor.load();
  return (factor > 1e-9f) ? 20.0f * log10f(factor) : -120.0f;
}

void HarkAudioEngine::setUseHeadsetMic(bool useHeadset) {
  mUseHeadsetMic.store(useHeadset);
}

inline float transparent_clip(float x) {
  if (x > HarkDspConfig::CLIP_SOFT_KNEE) {
    float d = x - HarkDspConfig::CLIP_SOFT_KNEE;
    float saturated = HarkDspConfig::CLIP_SOFT_KNEE + d / (1.0f + d * HarkDspConfig::CLIP_SLOPE);
    return saturated > HarkDspConfig::CLIP_HARD_LIMIT ? HarkDspConfig::CLIP_HARD_LIMIT : saturated;
  }
  if (x < -HarkDspConfig::CLIP_SOFT_KNEE) {
    float d = x + HarkDspConfig::CLIP_SOFT_KNEE;
    float saturated = -HarkDspConfig::CLIP_SOFT_KNEE + d / (1.0f - d * HarkDspConfig::CLIP_SLOPE);
    return saturated < -HarkDspConfig::CLIP_HARD_LIMIT ? -HarkDspConfig::CLIP_HARD_LIMIT : saturated;
  }
  return x;
}

oboe::DataCallbackResult
HarkAudioEngine::onAudioReady(oboe::AudioStream * /*stream*/, void *audioData,
                              int32_t numFrames) {
  // Load the active snapshot parameters atomically at the beginning of the
  // audio callback
  const DspParameterSnapshot &params =
      mParamsBuffers[mActiveParamsIndex.load(std::memory_order_acquire)];

  auto *buffer = static_cast<float *>(audioData);
  bool isMediaMode = mMediaCaptureMode.load(std::memory_order_relaxed);
  int32_t framesRead = 0;
  bool wouldBlock = false;

  if (isMediaMode) {
    if (mMediaAudioQueue) {
      size_t floatsNeeded = numFrames * CHANNEL_COUNT;
      size_t floatsRead = mMediaAudioQueue->pop(buffer, floatsNeeded);
      if (floatsRead < floatsNeeded) {
        memset(buffer + floatsRead, 0,
               (floatsNeeded - floatsRead) * sizeof(float));
      }
    } else {
      memset(buffer, 0, numFrames * CHANNEL_COUNT * sizeof(float));
    }
  } else {
    if (!mInputStream)
      return oboe::DataCallbackResult::Continue;

    // Full-Duplex synchronization logic
    int drainCount = mCountCallbacksToDrain.load(std::memory_order_relaxed);
    if (drainCount > 0) {
      // Phase 1: Drain input stream buffer
      float tempBuf[96 * 4];
      int32_t totalDrained = 0;
      while (true) {
        auto res = mInputStream->read(
            tempBuf,
            std::min(numFrames, (int32_t)(sizeof(tempBuf) / sizeof(float))), 0);
        if (res && res.value() > 0) {
          totalDrained += res.value();
        } else {
          break;
        }
      }
      // Only decrement when we actually drain some frames (waiting for input
      // stream to start)
      if (totalDrained > 0) {
        mCountCallbacksToDrain.store(drainCount - 1, std::memory_order_relaxed);
      }
      // Return silent frames during startup
      memset(buffer, 0, numFrames * sizeof(float));
      framesRead = 0;
    } else {
      int cushionCount =
          mCountInputBurstsCushion.load(std::memory_order_relaxed);
      if (cushionCount > 0) {
        // Phase 2: Cushioning - wait for input hardware to build buffer
        mCountInputBurstsCushion.store(cushionCount - 1,
                                       std::memory_order_relaxed);
        memset(buffer, 0, numFrames * sizeof(float));
        framesRead = 0;
      } else {
        int discardCount =
            mCountCallbacksToDiscard.load(std::memory_order_relaxed);
        // Phase 3 & 4: Read input data (2.0ms timeout to stabilize scheduling
        // jitter and clock drift)
        auto result = mInputStream->read(buffer, numFrames, 2000000);
        if (!result) {
          memset(buffer, 0, numFrames * sizeof(float));
          framesRead = 0;
          wouldBlock = true;
        } else {
          framesRead = result.value();
          if (framesRead == 0) {
            wouldBlock = true;
          }
          if (discardCount > 0) {
            // Phase 3: Discarding during stabilization
            mCountCallbacksToDiscard.store(discardCount - 1,
                                           std::memory_order_relaxed);
            memset(buffer, 0, numFrames * sizeof(float));
            framesRead = 0;
          }
        }
      }
    }

    // Apply Input Gain Offset (Compensation)
    float inputFactor = mInputGainFactor.load(std::memory_order_relaxed);
    if (inputFactor != 1.0f && framesRead > 0) {
      for (int i = 0; i < framesRead; ++i) {
        buffer[i] *= inputFactor;
      }
    }

    // Silent padding
    if (framesRead < numFrames) {
      memset(buffer + framesRead, 0, sizeof(float) * (numFrames - framesRead));
    }

    // Diagnostic raw input level tracking (before duplicate to stereo)
    float localInputPeak = 0.0f;
    for (int k = 0; k < framesRead; ++k) {
      float val = fabsf(buffer[k]);
      if (val > localInputPeak) {
        localInputPeak = val;
      }
    }
    // Accumulate max peak atomically
    auto currentMax = mDiagRawInputPeakLinear.load(std::memory_order_relaxed);
    while (localInputPeak > currentMax &&
           !mDiagRawInputPeakLinear.compare_exchange_weak(
               currentMax, localInputPeak, std::memory_order_relaxed))
      ;

    // Mono to Stereo (Microphone input is Mono, duplicate to LR)
    for (int i = numFrames - 1; i >= 0; --i) {
      float s = buffer[i];
      buffer[i * 2] = s;
      buffer[i * 2 + 1] = s;
    }
  }

  float finalGain = mIsMuted.load(std::memory_order_relaxed)
                        ? 0.0f
                        : mMasterGain.load(std::memory_order_relaxed);

  if (mBypassMode) {
    if (finalGain != 1.0f) {
      for (int i = 0; i < numFrames * CHANNEL_COUNT; ++i) {
        buffer[i] *= finalGain;
      }
    }
    return oboe::DataCallbackResult::Continue;
  }

  // Track input RMS slowly
  float blockSumSq = 0.0f;
  int totalSamples = numFrames * CHANNEL_COUNT;
  for (int i = 0; i < totalSamples; ++i) {
    float val = buffer[i];
    blockSumSq += val * val;
  }
  float blockRms = std::sqrt(blockSumSq / (float)totalSamples);
  float alphaRms = HarkDspConfig::INPUT_RMS_ALPHA; // Slow EMA with block-level tracking
  float currentRms = mInputRmsSlow.load(std::memory_order_relaxed);
  float nextRms = alphaRms * blockRms + (1.0f - alphaRms) * currentRms;
  mInputRmsSlow.store(nextRms, std::memory_order_relaxed);

  // Compute level-dependent headroom db (separate for Left and Right)
  float inputRmsDb = 20.0f * std::log10(std::max(nextRms, HarkDspConfig::INPUT_RMS_MIN));
  float scaling = 1.0f;
  if (inputRmsDb < HarkDspConfig::HEADROOM_QUIET_THRESH_DB) {
    scaling = 0.0f;
  } else if (inputRmsDb < HarkDspConfig::HEADROOM_LOUD_THRESH_DB) {
    scaling = (inputRmsDb - HarkDspConfig::HEADROOM_QUIET_THRESH_DB) /
              (HarkDspConfig::HEADROOM_LOUD_THRESH_DB - HarkDspConfig::HEADROOM_QUIET_THRESH_DB);
  }

  // Left Channel Headroom
  float headroomDbL = -fmaxf(0.0f, mMaxBoostDbL * HarkDspConfig::HEADROOM_MAX_BOOST_WEIGHT + mSumBoostDbL * HarkDspConfig::HEADROOM_SUM_BOOST_WEIGHT);
  float appliedHeadroomDbL = headroomDbL * scaling;
  float headroomLinearL = powf(10.0f, appliedHeadroomDbL / 20.0f);

  // Right Channel Headroom
  float headroomDbR = -fmaxf(0.0f, mMaxBoostDbR * HarkDspConfig::HEADROOM_MAX_BOOST_WEIGHT + mSumBoostDbR * HarkDspConfig::HEADROOM_SUM_BOOST_WEIGHT);
  float appliedHeadroomDbR = headroomDbR * scaling;
  float headroomLinearR = powf(10.0f, appliedHeadroomDbR / 20.0f);

  // Smooth prescription gains using base targets scaled by dynamic headroom
  for (int b = 0; b < NUM_INTERNAL_BANDS; ++b) {
    float targetL = mPrescriptionBaseTargetsL[b] * headroomLinearL;
    mPrescriptionGainsL[b] =
        GAIN_SMOOTH_ALPHA * mPrescriptionGainsL[b] +
        (1.0f - GAIN_SMOOTH_ALPHA) * targetL;

    float targetR = mPrescriptionBaseTargetsR[b] * headroomLinearR;
    mPrescriptionGainsR[b] =
        GAIN_SMOOTH_ALPHA * mPrescriptionGainsR[b] +
        (1.0f - GAIN_SMOOTH_ALPHA) * targetR;
  }

  for (int i = 0; i < numFrames * CHANNEL_COUNT; i += CHANNEL_COUNT) {
    float sL = buffer[i];
    float sR = buffer[i + 1];

    // Transient Suppressor (Impulse Noise suppression at the very input)
    if (mTransientSuppressorEnabled.load(std::memory_order_relaxed)) {
      sL = mTransientSuppressorL.process(sL);
      sR = mTransientSuppressorR.process(sR);
    }

    // [0] Simple DC Blocker to prevent IIR instability
    if (mDcBlockerEnabled.load(std::memory_order_relaxed)) {
      float curInL = sL;
      float curInR = sR;
      sL = curInL - mDcLastInL + HarkDspConfig::DC_BLOCKER_POLE * mDcLastOutL;
      sR = curInR - mDcLastInR + HarkDspConfig::DC_BLOCKER_POLE * mDcLastOutR;
      mDcLastInL = curInL;
      mDcLastInR = curInR;
      mDcLastOutL = sL;
      mDcLastOutR = sR;
    }

    // [1] Noise Suppressor
    sL = mNoiseSuppressorL.process(sL);
    sR = mNoiseSuppressorR.process(sR);

    // [3] 8-Band Filterbank with Integrated UI Gain & Own Voice Detection
    if (mCrossoverWdrcEnabled.load(std::memory_order_relaxed)) {
      auto midL = mXoverMidL.process(sL);
      auto lowL = mXoverLowL.process(midL.low);
      auto highL = mXoverHighL.process(midL.high);
      auto vlL = mXoverVLowL.process(lowL.low);
      auto lmL = mXoverLMidL.process(lowL.high);
      auto hmL = mXoverHMidL.process(highL.low);
      auto vhL = mXoverVHiL.process(highL.high);

      float bandsL[8] = {vlL.low, vlL.high, lmL.low, lmL.high,
                         hmL.low, hmL.high, vhL.low, vhL.high};

      if (mOwnVoiceDetectorEnabled.load(std::memory_order_relaxed)) {
        mOwnVoiceDetectorL.updateEnergy(bandsL);
      }

      sL = 0.0f;
      for (int b = 0; b < 8; ++b) {
        // 將處方增益移至 WDRC 處理之後，避免底噪在 WDRC
        // 前被放大，從而正確觸發下擴展降噪
        float processed = mWdrcL[b].process(bandsL[b]) * mPrescriptionGainsL[b];
        if (mOwnVoiceDetectorEnabled.load(std::memory_order_relaxed)) {
          processed *= mOwnVoiceDetectorL.getOcclusionGain(b);
        }
        sL += processed;
      }

      auto midR = mXoverMidR.process(sR);
      auto lowR = mXoverLowR.process(midR.low);
      auto highR = mXoverHighR.process(midR.high);
      auto vlR = mXoverVLowR.process(lowR.low);
      auto lmR = mXoverLMidR.process(lowR.high);
      auto hmR = mXoverHMidR.process(highR.low);
      auto vhR = mXoverVHiR.process(highR.high);

      float bandsR[8] = {vlR.low, vlR.high, lmR.low, lmR.high,
                         hmR.low, hmR.high, vhR.low, vhR.high};

      if (mOwnVoiceDetectorEnabled.load(std::memory_order_relaxed)) {
        mOwnVoiceDetectorR.updateEnergy(bandsR);
      }

      sR = 0.0f;
      for (int b = 0; b < 8; ++b) {
        // 同步右聲道：將處方增益移至 WDRC 處理之後，確保雙耳處理一致性
        float processed = mWdrcR[b].process(bandsR[b]) * mPrescriptionGainsR[b];
        if (mOwnVoiceDetectorEnabled.load(std::memory_order_relaxed)) {
          processed *= mOwnVoiceDetectorR.getOcclusionGain(b);
        }
        sR += processed;
      }
    }

    // [4] Limiter
    if (mLimiterEnabled.load(std::memory_order_relaxed)) {
      sL = mLimiterL.process(sL);
      sR = mLimiterR.process(sR);
    }

    // [5] Master Volume & Algebraic Soft Clip (Preventing hardware protection
    // gate)
    buffer[i] = transparent_clip(sL * finalGain);
    buffer[i + 1] = transparent_clip(sR * finalGain);
  }

  // Accumulate diagnostic counters
  mDiagCallbackCount.fetch_add(1, std::memory_order_relaxed);
  if (wouldBlock) {
    mDiagWouldBlockCount.fetch_add(1, std::memory_order_relaxed);
  }

  float localOutputPeak = 0.0f;
  for (int i = 0; i < numFrames * CHANNEL_COUNT; ++i) {
    float val = fabsf(buffer[i]);
    if (val > localOutputPeak) {
      localOutputPeak = val;
    }
  }
  auto currentOutMax = mDiagOutputPeakLinear.load(std::memory_order_relaxed);
  while (localOutputPeak > currentOutMax &&
         !mDiagOutputPeakLinear.compare_exchange_weak(
             currentOutMax, localOutputPeak, std::memory_order_relaxed))
    ;

  return oboe::DataCallbackResult::Continue;
}

void HarkAudioEngine::onErrorAfterClose(oboe::AudioStream *stream,
                                        oboe::Result error) {
  LOGW("Oboe stream disconnected! Error: %s. Spawning auto-recovery thread...",
       oboe::convertToText(error));
  mIsRunning = false;

  // 建立分離的後台線程進行自動自我修復重啟
  std::thread([this]() {
    LOGD("Auto-recovery thread started. Resetting streams...");
    stop(false); // 僅重置流，不要關閉自癒標記！
    // 給予物理系統 500ms 的緩衝穩定時間，確保硬體變更完全就緒
    std::this_thread::sleep_for(std::chrono::milliseconds(500));

    if (mAutoRecoveryEnabled.load()) {
      LOGD("Re-starting audio engine on new routing...");
      start();
    } else {
      LOGD("Auto-recovery bypassed: engine was stopped by user or system.");
    }
  }).detach();
}

void HarkAudioEngine::logLatencyStatistics() {
  if (mOutputStream) {
    auto latency = mOutputStream->calculateLatencyMillis();
    if (latency)
      LOGD("Latency: %.1f ms", latency.value());
  }
}

void HarkAudioEngine::calibrateNoiseSuppressor() {
  // Logic for noise floor calibration
}

void HarkAudioEngine::setBandQ(int bandIndex, float q_factor) {
  if (bandIndex >= 0 && bandIndex < NUM_UI_BANDS)
    mBandQs[bandIndex] = q_factor;
}

void HarkAudioEngine::setBandWdrcParameters(int band, float thresholdDb,
                                            float ratio, float attackMs,
                                            float releaseMs) {
  if (band >= 0 && band < 8) {
    float expanderThresh =
        mCurrentExpanderThresholdDb.load(std::memory_order_relaxed);
    float expanderRatio = mWdrcExpanderRatio.load(std::memory_order_relaxed);
    mWdrcL[band].setParameters(thresholdDb, ratio, expanderThresh,
                               expanderRatio, attackMs, releaseMs, sampleRate);
    mWdrcR[band].setParameters(thresholdDb, ratio, expanderThresh,
                               expanderRatio, attackMs, releaseMs, sampleRate);
  }
}

void HarkAudioEngine::setWdrcParameters(float thresholdDb, float ratio,
                                        float attackMs, float releaseMs) {
  float expThresh = mCurrentExpanderThresholdDb.load(std::memory_order_relaxed);
  float expRatio = mWdrcExpanderRatio.load(std::memory_order_relaxed);
  updateWdrcParameters(thresholdDb, ratio, expThresh, expRatio, attackMs,
                       releaseMs);
}

void HarkAudioEngine::setLimiterParameters(float thresholdDb, float ratio,
                                           float attackMs, float releaseMs) {
  mLimiterThresholdDb.store(thresholdDb, std::memory_order_relaxed);
  mLimiterReleaseMs.store(releaseMs, std::memory_order_relaxed);
  mLimiterL.setParameters(thresholdDb, ratio, -100.0f, 1.0f, attackMs,
                          releaseMs, sampleRate);
  mLimiterR.setParameters(thresholdDb, ratio, -100.0f, 1.0f, attackMs,
                          releaseMs, sampleRate);
}

void HarkAudioEngine::setInputDeviceId(int32_t deviceId) {
  mInputDeviceId = deviceId;
}
void HarkAudioEngine::resetGesture() { mGestureDetector.reset(); }

void HarkAudioEngine::setMediaCaptureMode(bool enabled) {
  if (mMediaCaptureMode.load() != enabled) {
    mMediaCaptureMode.store(enabled);
    if (mMediaAudioQueue) {
      mMediaAudioQueue->clear();
    }
    // Restart engine to apply routing change
    LOGD("setMediaCaptureMode to %d, restarting engine...", enabled);
    stop(false);
    start();
  }
}

void HarkAudioEngine::pushMediaAudioData(const float *data, int numFrames) {
  if (mMediaCaptureMode.load() && mMediaAudioQueue) {
    mMediaAudioQueue->push(
        data, numFrames * CHANNEL_COUNT); // Assuming stereo float input
  }
}

void HarkAudioEngine::setBypassMode(bool bypass) { mBypassMode = bypass; }

void HarkAudioEngine::setNoiseReductionEnabled(bool enabled) {
  mNoiseReductionEnabled.store(enabled, std::memory_order_relaxed);
  mNoiseSuppressorL.setEnabled(enabled);
  mNoiseSuppressorR.setEnabled(enabled);
}

void HarkAudioEngine::setMasterGain(float gain) { mMasterGain.store(gain); }

void HarkAudioEngine::setMuted(bool muted) { mIsMuted.store(muted); }

float HarkAudioEngine::getBandEnergy(int band) const {
  if (band < 0 || band >= 5)
    return 0.0f;
  return mNoiseSuppressorL.getBandEnergy(band);
}

void HarkAudioEngine::setDcBlockerEnabled(bool enabled) {
  mDcBlockerEnabled.store(enabled, std::memory_order_relaxed);
}

void HarkAudioEngine::setCrossoverWdrcEnabled(bool enabled) {
  mCrossoverWdrcEnabled.store(enabled, std::memory_order_relaxed);
}

void HarkAudioEngine::setLimiterEnabled(bool enabled) {
  mLimiterEnabled.store(enabled, std::memory_order_relaxed);
}

void HarkAudioEngine::setTransientSuppressorEnabled(bool enabled) {
  mTransientSuppressorEnabled.store(enabled, std::memory_order_relaxed);
  mTransientSuppressorL.setEnabled(enabled);
  mTransientSuppressorR.setEnabled(enabled);
}

void HarkAudioEngine::setOwnVoiceDetectorEnabled(bool enabled) {
  mOwnVoiceDetectorEnabled.store(enabled, std::memory_order_relaxed);
  mOwnVoiceDetectorL.setEnabled(enabled);
  mOwnVoiceDetectorR.setEnabled(enabled);
}

void HarkAudioEngine::setWdrcExpanderThreshold(float thresholdDb) {
  float compThresh = mWdrcCompressThresholdDb.load(std::memory_order_relaxed);
  float compRatio = mWdrcCompressRatio.load(std::memory_order_relaxed);
  float expRatio = mWdrcExpanderRatio.load(std::memory_order_relaxed);
  float att = mWdrcAttackMs.load(std::memory_order_relaxed);
  float rel = mWdrcReleaseMs.load(std::memory_order_relaxed);
  updateWdrcParameters(compThresh, compRatio, thresholdDb, expRatio, att, rel);
}

void HarkAudioEngine::setStreamOverrides(int sharingMode, int inputPreset) {
  mSharingModeOverride.store(sharingMode, std::memory_order_relaxed);
  mInputPresetOverride.store(inputPreset, std::memory_order_relaxed);
}

void HarkAudioEngine::getStreamOverrides(int &sharingMode,
                                         int &inputPreset) const {
  sharingMode = mSharingModeOverride.load(std::memory_order_relaxed);
  inputPreset = mInputPresetOverride.load(std::memory_order_relaxed);
}

void HarkAudioEngine::getDiagnosticMetrics(float *outMetrics) {
  // 1. Raw Input Peak (dBFS)
  float inPeakLin =
      mDiagRawInputPeakLinear.exchange(0.0f, std::memory_order_relaxed);
  outMetrics[0] = (inPeakLin > 1e-9f) ? 20.0f * log10f(inPeakLin) : -120.0f;

  // 2. Output Peak (dBFS)
  float outPeakLin =
      mDiagOutputPeakLinear.exchange(0.0f, std::memory_order_relaxed);
  outMetrics[1] = (outPeakLin > 1e-9f) ? 20.0f * log10f(outPeakLin) : -120.0f;

  // 3. WouldBlock Rate (%)
  int callbacks = mDiagCallbackCount.exchange(0, std::memory_order_relaxed);
  int wouldBlocks = mDiagWouldBlockCount.exchange(0, std::memory_order_relaxed);
  outMetrics[2] = (callbacks > 0)
                      ? (static_cast<float>(wouldBlocks) / callbacks) * 100.0f
                      : 0.0f;

  // 4. Input XRuns
  if (mInputStream) {
    auto res = mInputStream->getXRunCount();
    outMetrics[3] = (res.error() == oboe::Result::OK) ? static_cast<float>(res.value()) : 0.0f;
  } else {
    outMetrics[3] = 0.0f;
  }

  // 5. Output XRuns
  if (mOutputStream) {
    auto res = mOutputStream->getXRunCount();
    outMetrics[4] = (res.error() == oboe::Result::OK) ? static_cast<float>(res.value()) : 0.0f;
  } else {
    outMetrics[4] = 0.0f;
  }
}
