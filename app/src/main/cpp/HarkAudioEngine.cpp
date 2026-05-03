#include "HarkAudioEngine.h"
#include <android/log.h>

#define LOG_TAG "HarkAudioEngine"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define DEFAULT_MAKEUP_GAIN_DB 10.0f // 調回 10dB 以確保 0dB 時有足夠音量
#define DEFAULT_PRE_GAIN_DB 0.0f     // 保持 0dB，動態由 AGC-O 處理

const int NUM_SHELF_FILTERS = 3;
const int NUM_PEAK_FILTERS = 16;
const int TOTAL_FILTERS = NUM_SHELF_FILTERS + NUM_PEAK_FILTERS;
const int CHANNEL_COUNT = 2;

const double centerFrequencies[] = {
    250.0,  315.0,  400.0,  500.0,  630.0,  800.0,  1000.0, 1250.0,
    1600.0, 2000.0, 2500.0, 3150.0, 4000.0, 5000.0, 6300.0, 8000.0};

HarkAudioEngine::HarkAudioEngine()
    : mFilterChainLeft(TOTAL_FILTERS), mFilterChainRight(TOTAL_FILTERS),
      sampleRate(48000.0), mBandGains(NUM_PEAK_FILTERS, 0.0f),
      mBandQs(NUM_PEAK_FILTERS, 1.8f), mInputDeviceId(oboe::kUnspecified),
      mIsRunning(false) {
  setPreGain(DEFAULT_PRE_GAIN_DB);
  setMakeupGain(DEFAULT_MAKEUP_GAIN_DB);

  updateDSPParameters();
}

void HarkAudioEngine::updateDSPParameters() {
  // 移除強制濾波器：為了達到真正的「通透模式」，預設不改變任何頻響
  // 0: Low-Shelf 200Hz
  mFilterChainLeft.updateBand(0, BiquadFilter::Type::LowShelf, sampleRate,
                              200.0, 0.0, 0.707);
  mFilterChainRight.updateBand(0, BiquadFilter::Type::LowShelf, sampleRate,
                               200.0, 0.0, 0.707);
  // 1: Low-Shelf 300Hz
  mFilterChainLeft.updateBand(1, BiquadFilter::Type::LowShelf, sampleRate,
                              300.0, 0.0, 0.707);
  mFilterChainRight.updateBand(1, BiquadFilter::Type::LowShelf, sampleRate,
                               300.0, 0.0, 0.707);
  
  // 計算 Auto-Headroom (AGC-O 策略)：防止過多頻段 boost 導致的數位破音
  float maxBoostDb = 0.0f;
  for (float gain : mBandGains) {
    if (gain > maxBoostDb) maxBoostDb = gain;
  }
  // 如果有頻段 boost 超過 3dB，我們按比例調降 Master Headroom (例如最高 +12dB 時預留 9dB 空間)
  float headroomDb = -fmaxf(0.0f, maxBoostDb * 0.75f);
  mAutoHeadroomLinear = powf(10.0f, headroomDb / 20.0f);

  // 2-17: Peaking EQs (這部分由外部 UI 覆蓋，保留預設平坦)
  for (int i = 0; i < NUM_PEAK_FILTERS; ++i) {
    mFilterChainLeft.updateBand(i + 2, BiquadFilter::Type::Peaking, sampleRate,
                                centerFrequencies[i], mBandGains[i],
                                mBandQs[i]);
    mFilterChainRight.updateBand(i + 2, BiquadFilter::Type::Peaking, sampleRate,
                                 centerFrequencies[i], mBandGains[i],
                                 mBandQs[i]);
  }
  // 18: High-Shelf 4000Hz 
  mFilterChainLeft.updateBand(TOTAL_FILTERS - 1, BiquadFilter::Type::HighShelf,
                              sampleRate, 4000.0, 0.0, 0.707);
  mFilterChainRight.updateBand(TOTAL_FILTERS - 1, BiquadFilter::Type::HighShelf,
                               sampleRate, 4000.0, 0.0, 0.707);

  // WDRC: Compress > -24dB (ratio 2.0:1), Expand < -60dB (ratio 0.66 -> 1:1.5 expansion for natural sound), Attack 5ms, Release 200ms
  mWdrcLeft.setParameters(-24.0f, 2.0f, -60.0f, 0.66f, 5.0f, 200.0f, sampleRate);
  mWdrcRight.setParameters(-24.0f, 2.0f, -60.0f, 0.66f, 5.0f, 200.0f, sampleRate);

  // Limiter: Compress > -1.0dB (ratio 10:1), Disable expander (-100dB, ratio 1.0), Attack 1ms, Release 50ms
  mLimiterLeft.setParameters(-1.0f, 10.0f, -100.0f, 1.0f, 1.0f, 50.0f, sampleRate);
  mLimiterRight.setParameters(-1.0f, 10.0f, -100.0f, 1.0f, 1.0f, 50.0f, sampleRate);
}

HarkAudioEngine::~HarkAudioEngine() { stop(); }

void HarkAudioEngine::setPreGain(float gainDb) {
  mPreGainLinear = powf(10.0f, gainDb / 20.0f);
}

void HarkAudioEngine::setInputDeviceId(int32_t deviceId) {
  // It's generally safer to re-create streams if the device ID changes while
  // running. However, if the engine is stopped, just update the ID for the next
  // start.
  if (mIsRunning) {
    LOGD("Input device ID changed while running. Stopping and will restart "
         "with new device.");
    // Consider a more robust mechanism to restart with the new device ID.
    // For now, just logging. A full restart might be needed.
    // stop(); // This might be too abrupt or lead to complex restart logic
    // here. A better approach might be to signal the calling layer
    // (Java/Kotlin) to stop and then start the engine again with the new
    // device.
  }
  mInputDeviceId = deviceId;
}

void HarkAudioEngine::setMakeupGain(float gainDb) {
  mMakeupGainDb = gainDb;
  mMakeupGainLinear = powf(10.0f, mMakeupGainDb / 20.0f);
  LOGD("Makeup gain set to %.2f dB (Linear: %.4f)", mMakeupGainDb,
       mMakeupGainLinear);
}

void HarkAudioEngine::start() {
  if (mIsRunning) {
    LOGD("HarkAudioEngine is already running.");
    return;
  }
  LOGD("Starting HarkAudioEngine...");

  if (setupStreams()) { // 讓 setupStreams 返回 bool 表示成功與否
    mIsRunning = true;
    LOGD("HarkAudioEngine started successfully.");

    // 成功啟動後，可以查詢並記錄實際的 burst sizes
    if (mInputStream) {
      LOGD("Input stream frames per burst: %d",
           mInputStream->getFramesPerBurst());
      LOGD("Input stream buffer size in frames: %d",
           mInputStream->getBufferSizeInFrames());
    }
    if (mOutputStream) {
      LOGD("Output stream frames per burst: %d",
           mOutputStream->getFramesPerBurst());
      LOGD("Output stream buffer size in frames: %d",
           mOutputStream->getBufferSizeInFrames());
    }
  } else {
    LOGE("Failed to start HarkAudioEngine because setupStreams failed.");
    // 確保資源在 setupStreams 失敗時被清理
    stop(); // stop() 內部應能處理部分初始化的情況
  }
}

bool HarkAudioEngine::isEngineRunning() const {
  // 只要 mOutputStream 存在且正在串流，就代表引擎在運作
  return (mOutputStream &&
          mOutputStream->getState() != oboe::StreamState::Closed);
}

bool HarkAudioEngine::setupStreams() {
  oboe::AudioStreamBuilder outBuilder;
  // The output stream is a "push" stream, so it needs a callback.
  outBuilder.setDirection(oboe::Direction::Output)
      ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
      ->setSharingMode(oboe::SharingMode::Exclusive)
      ->setFormat(oboe::AudioFormat::Float)
      ->setChannelCount(CHANNEL_COUNT)
      ->setUsage(oboe::Usage::VoiceCommunication) // Routes output to BT communication device
      ->setContentType(oboe::ContentType::Speech)  // Tag as speech for audio policy
      ->setDataCallback(this)                       // onAudioReady() DSP callback
      ->setErrorCallback(this);                     // onErrorAfterClose() disconnect callback

  oboe::Result result = outBuilder.openStream(&mOutputStream);
  if (result != oboe::Result::OK ||
      !mOutputStream) { // 檢查 mOutputStream 是否為 nullptr
    LOGE("Failed to open output stream. Error: %s",
         oboe::convertToText(result));
    return false;
  }

  // Get the negotiated sample rate
  sampleRate = mOutputStream->getSampleRate();
  LOGD("Output stream negotiated sample rate: %.0f", sampleRate);

  // Update DSP parameters with the new sample rate
  updateDSPParameters();

  // 使用 output stream 的 burst size 作為參考
  int32_t framesPerBurst = mOutputStream->getFramesPerBurst();
  LOGD("Output stream framesPerBurst: %d. Using this for buffer sizes.",
       framesPerBurst);

  // Now, create the input stream.
  oboe::AudioStreamBuilder inBuilder;
  inBuilder.setDirection(oboe::Direction::Input)
      ->setDeviceId(
          mInputDeviceId) // Never use output device ID for input stream
      // 藍牙耳機在 MMAP (LowLatency) 模式下經常無法提供硬體時間戳，導致無聲與
      // logcat 狂刷。 使用 VoiceCommunication 預設值配合 Shared/None
      // 模式，能最穩定地支援 BLE Audio 雙向通訊
      ->setInputPreset(oboe::InputPreset::VoiceCommunication)
      ->setPerformanceMode(
          oboe::PerformanceMode::None) // 放棄強制
                                       // MMAP，改用系統最穩定的傳統路徑
      ->setSharingMode(oboe::SharingMode::Shared)
      ->setFormat(oboe::AudioFormat::Float)
      // 修改：強制使用單聲道 (Mono) 作為麥克風輸入
      // 因為大部分藍牙通話耳機 (SCO/BLE) 只有單聲道，若強制要立體聲，Oboe 可能無法開啟或給出錯誤資料！
      ->setChannelCount(oboe::ChannelCount::Mono)
      ->setSampleRate(static_cast<int32_t>(sampleRate))
      ->setSampleRateConversionQuality(
          oboe::SampleRateConversionQuality::Medium);

  result = inBuilder.openStream(&mInputStream);
  if (result != oboe::Result::OK ||
      !mInputStream) { // 檢查 mInputStream 是否為 nullptr
    LOGE("Failed to open input stream. Error: %s", oboe::convertToText(result));
    if (mOutputStream) {
      mOutputStream->close();
      mOutputStream = nullptr;
    }
    return false;
  }

  // 設定內部緩衝區大小
  // 對於 LowLatency，這個設定很重要
  oboe::Result resInput = mInputStream->setBufferSizeInFrames(framesPerBurst);
  if (resInput != oboe::Result::OK) {
    LOGW("Warning: Failed to set input stream buffer size to %d. Error: %s",
         framesPerBurst, oboe::convertToText(resInput));
  } else {
    LOGD("Input stream buffer size successfully set to %d frames",
         mInputStream->getBufferSizeInFrames());
  }

  oboe::Result resOutput = mOutputStream->setBufferSizeInFrames(framesPerBurst);
  if (resOutput != oboe::Result::OK) {
    LOGW("Warning: Failed to set output stream buffer size to %d. Error: %s",
         framesPerBurst, oboe::convertToText(resOutput));
  } else {
    LOGD("Output stream buffer size successfully set to %d frames",
         mOutputStream->getBufferSizeInFrames());
  }

  result = mInputStream->requestStart();
  if (result != oboe::Result::OK) {
    LOGE("Failed to start input stream. Error: %s",
         oboe::convertToText(result));
    // 清理
    if (mOutputStream) {
      mOutputStream->close();
      mOutputStream = nullptr;
    }
    if (mInputStream) {
      mInputStream->close();
      mInputStream = nullptr;
    }
    return false;
  }

  result = mOutputStream->requestStart();
  if (result != oboe::Result::OK) {
    LOGE("Failed to start output stream. Error: %s",
         oboe::convertToText(result));
    // 清理
    if (mInputStream) {
      mInputStream->requestStop();
      mInputStream->close();
      mInputStream = nullptr;
    }
    if (mOutputStream) {
      mOutputStream->close();
      mOutputStream = nullptr;
    } // mOutputStream 可能未 stop
    return false;
  }

  LOGD("HarkAudioEngine started successfully.");
  return true;
}

void HarkAudioEngine::stop() {
  // Bug fix: Do NOT gate on mIsRunning. When Oboe streams disconnect due to
  // device unplug or OS interruption, the streams may still hold resources
  // even though mIsRunning was never set to false (e.g. if stop() was called
  // on a partially-initialized engine). Always attempt resource cleanup.
  if (!mIsRunning && !mInputStream && !mOutputStream) {
    return; // Truly nothing to clean up
  }
  LOGD("Stopping HarkAudioEngine...");

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
  mIsRunning = false;
  LOGD("HarkAudioEngine stopped.");
}

void HarkAudioEngine::setBandGain(int bandIndex, float gainDb) {
  std::lock_guard<std::mutex> lock(mDSPMutex);
  if (bandIndex < 0 || bandIndex >= NUM_PEAK_FILTERS) {
    LOGW("Invalid band index %d", bandIndex);
    return;
  }
  mBandGains[bandIndex] = gainDb;
  mFilterChainLeft.updateBand(bandIndex + 2, BiquadFilter::Type::Peaking,
                              sampleRate, centerFrequencies[bandIndex],
                              mBandGains[bandIndex], mBandQs[bandIndex]);
  mFilterChainRight.updateBand(bandIndex + 2, BiquadFilter::Type::Peaking,
                               sampleRate, centerFrequencies[bandIndex],
                               mBandGains[bandIndex], mBandQs[bandIndex]);
}

void HarkAudioEngine::setBandQ(int bandIndex, float q_factor) {
  std::lock_guard<std::mutex> lock(mDSPMutex);
  if (bandIndex < 0 || bandIndex >= NUM_PEAK_FILTERS) {
    LOGW("Invalid band index %d", bandIndex);
    return;
  }
  mBandQs[bandIndex] = q_factor;
  mFilterChainLeft.updateBand(bandIndex + 2, BiquadFilter::Type::Peaking,
                              sampleRate, centerFrequencies[bandIndex],
                              mBandGains[bandIndex], mBandQs[bandIndex]);
  mFilterChainRight.updateBand(bandIndex + 2, BiquadFilter::Type::Peaking,
                               sampleRate, centerFrequencies[bandIndex],
                               mBandGains[bandIndex], mBandQs[bandIndex]);
}

void HarkAudioEngine::setWdrcParameters(float thresholdDb, float ratio,
                                        float attackMs, float releaseMs) {
  std::lock_guard<std::mutex> lock(mDSPMutex);
  // Pass default expander parameters (-50dB threshold, 1:2 ratio) to maintain WDRC signature
  mWdrcLeft.setParameters(thresholdDb, ratio, -50.0f, 0.5f, attackMs, releaseMs, sampleRate);
  mWdrcRight.setParameters(thresholdDb, ratio, -50.0f, 0.5f, attackMs, releaseMs, sampleRate);
}

void HarkAudioEngine::setLimiterParameters(float thresholdDb, float ratio,
                                           float attackMs, float releaseMs) {
  std::lock_guard<std::mutex> lock(mDSPMutex);
  // Disable expander for limiter (-100dB, ratio 1.0)
  mLimiterLeft.setParameters(thresholdDb, ratio, -100.0f, 1.0f, attackMs, releaseMs,
                             sampleRate);
  mLimiterRight.setParameters(thresholdDb, ratio, -100.0f, 1.0f, attackMs, releaseMs,
                              sampleRate);
}

void HarkAudioEngine::setBypassMode(bool bypass) {
  std::lock_guard<std::mutex> lock(mDSPMutex);
  mBypassMode = bypass;
  if (bypass) {
    LOGD("BYPASS MODE ENABLED - Direct passthrough (no processing)");
  } else {
    LOGD("NORMAL MODE - DSP processing enabled");
  }
}

// --- 最關鍵的修改：更新 onAudioReady 處理鏈 ---
oboe::DataCallbackResult
HarkAudioEngine::onAudioReady(oboe::AudioStream *oboeStream, void *audioData,
                              int32_t numFrames) {
  if (!mInputStream || mInputStream->getState() != oboe::StreamState::Started) {
    // 如果輸入流還沒完全啟動（例如還在 Starting 階段），直接填入靜音並返回。
    // 這可以避免 AAudio 在尚未獲取硬體時間戳時被頻繁讀取，從而防止 logcat 狂刷
    // "wait for valid timestamps"
    memset(audioData, 0, sizeof(float) * numFrames * CHANNEL_COUNT);
    return oboe::DataCallbackResult::Continue;
  }

  // 設置合理的超時時間，避免 input stream 因為稍微的延遲就回傳 0 幀（造成靜音或嚴重斷續）
  int64_t timeoutNanos = (int64_t)(numFrames * 1e9 / sampleRate) * 2; 
  auto result = mInputStream->read(audioData, numFrames, timeoutNanos);

  int32_t framesRead = 0;
  if (!result) {
    LOGE("Input stream read error: %s", oboe::convertToText(result.error()));
    memset(audioData, 0, sizeof(float) * numFrames * CHANNEL_COUNT);
    return oboe::DataCallbackResult::Continue;
  } else {
    framesRead = result.value();
  }

  auto *buffer = static_cast<float *>(audioData);
  int32_t inputChannelCount = mInputStream->getChannelCount();

  // --- 重大修正：聲道擴充與記憶體佈局 ---
  // 如果麥克風是單聲道 (1 channel)，而我們需要立體聲輸出 (CHANNEL_COUNT = 2)
  // read() 會把 framesRead 個樣本寫入 buffer 的前半段 (index 0 ~ framesRead-1)
  // 我們必須從後面往前把資料展開為立體聲 (L, R, L, R...)，避免覆蓋還沒展開的資料
  if (inputChannelCount == 1 && CHANNEL_COUNT == 2) {
      for (int i = framesRead - 1; i >= 0; --i) {
          float sample = buffer[i];
          buffer[i * 2] = sample;     // Left
          buffer[i * 2 + 1] = sample; // Right
      }
  } else if (inputChannelCount > 2) {
      // 若萬一裝置有多個聲道，只取第一聲道
      for (int i = framesRead - 1; i >= 0; --i) {
          float sample = buffer[i * inputChannelCount];
          buffer[i * 2] = sample;
          buffer[i * 2 + 1] = sample;
      }
  }

  // 如果读取的帧数少于请求的帧数（例如发生 under-run），用静音填充剩余部分 (注意：此時 buffer 已經是立體聲佈局)
  if (framesRead < numFrames) {
    int32_t remainingFrames = numFrames - framesRead;
    memset(buffer + framesRead * CHANNEL_COUNT, 0,
           sizeof(float) * remainingFrames * CHANNEL_COUNT);
  }

  {
    std::lock_guard<std::mutex> lock(mDSPMutex);

    // 通透模式：直接通过，最多做防溢出处理
    if (mBypassMode) {
      for (int i = 0; i < numFrames * CHANNEL_COUNT; ++i) {
        float sample = buffer[i];

        // 简单的防溢出（只在样本过大时削波）
        if (sample > 1.0f)
          sample = 1.0f;
        else if (sample < -1.0f)
          sample = -1.0f;

        buffer[i] = sample;
      }
      return oboe::DataCallbackResult::Continue;
    }

    // 正常模式：完整的DSP處理鏈
    for (int i = 0; i < numFrames * CHANNEL_COUNT; i += CHANNEL_COUNT) {
      float sampleL = buffer[i];
      float sampleR = (CHANNEL_COUNT > 1) ? buffer[i + 1] : buffer[i];

      // --- 1. Own Voice Ducking (Simple VAD) ---
      // 檢測輸入音量包絡，若音量極大（例如 User 在唱歌/說話），平滑調降增益
      float currentInputLevel = (fabsf(sampleL) + fabsf(sampleR)) * 0.5f;
      mInputEnvelope = 0.999f * mInputEnvelope + 0.001f * currentInputLevel;
      
      float vadThreshold = 0.17f; // 約 -15dBFS
      if (mInputEnvelope > vadThreshold) {
          mDuckingGain = 0.99f * mDuckingGain + 0.01f * 0.3f; // 快速降到 30% 增益
      } else {
          mDuckingGain = 0.999f * mDuckingGain + 0.001f * 1.0f; // 緩慢恢復
      }

      // --- 2. AGC-O Pre-gain & Headroom ---
      sampleL = sampleL * mPreGainLinear * mAutoHeadroomLinear * mDuckingGain;
      sampleR = sampleR * mPreGainLinear * mAutoHeadroomLinear * mDuckingGain;

      // --- 3. EQ & WDRC ---
      sampleL = mFilterChainLeft.process(sampleL);
      sampleR = mFilterChainRight.process(sampleR);
      sampleL = mWdrcLeft.process(sampleL);
      sampleR = mWdrcRight.process(sampleR);

      // --- 4. Makeup Gain & Limiter ---
      sampleL = sampleL * mMakeupGainLinear;
      sampleR = sampleR * mMakeupGainLinear;
      sampleL = mLimiterLeft.process(sampleL);
      sampleR = mLimiterRight.process(sampleR);

      // --- 5. Safety Soft-Clipping (FDA-compliant low distortion) ---
      // 使用 tanh-like soft clipping 代替生硬的 hard clipping，減少高頻諧波雜訊
      auto softClip = [](float x) {
          if (x > 0.9f) return 0.9f + 0.1f * tanhf((x - 0.9f) / 0.1f);
          if (x < -0.9f) return -0.9f + 0.1f * tanhf((x + 0.9f) / 0.1f);
          return x;
      };
      
      sampleL = softClip(sampleL);
      sampleR = softClip(sampleR);

      buffer[i] = sampleL;
      if (CHANNEL_COUNT > 1) {
        buffer[i + 1] = sampleR;
      }
    }
  }

  return oboe::DataCallbackResult::Continue;
}

// ---------------------------------------------------------------------------
// Error callback
// ---------------------------------------------------------------------------

/**
 * onErrorAfterClose – called by Oboe's internal error-handling thread AFTER the
 * stream has been disconnected and closed (e.g. AudioDeviceInfo routing change
 * when Bluetooth SCO link becomes active, changing devIds 2 → 49).
 *
 * Root cause this fixes:
 *   1. Engine opens output to built-in speaker (devIds=2) because BT SCO is
 *      not yet established when startEngine() is called.
 *   2. ~1 s later BT SCO connects → Android re-routes audio → output devIds
 *      changes (2→49) → Oboe fires ErrorDisconnected and closes the stream.
 *   3. WITHOUT this handler mIsRunning stays true, so the next start() call
 *      returns early ("already running") even though the stream is dead.
 *   4. WITH this handler we reset state immediately so the Kotlin layer can
 *      restart the engine cleanly via checkAndSetAudioDevice().
 *
 * Thread safety: this runs on Oboe's error thread, NOT the audio thread.
 * We take mDSPMutex to safely null mOutputStream/mInputStream.
 *
 * Ref: Oboe ErrorHandling –
 *   https://github.com/google/oboe/blob/main/docs/GettingStarted.md#handling-errors
 */
void HarkAudioEngine::onErrorAfterClose(oboe::AudioStream *oboeStream,
                                        oboe::Result error) {
  LOGE("onErrorAfterClose: stream %p disconnected: %s", oboeStream,
       oboe::convertToText(error));

  std::lock_guard<std::mutex> lock(mDSPMutex);

  // Oboe has already closed the stream; null our pointers to avoid use-after-free.
  if (oboeStream == mOutputStream) {
    LOGD("Nulling mOutputStream (was %p)", mOutputStream);
    mOutputStream = nullptr;
  }
  if (oboeStream == mInputStream) {
    LOGD("Nulling mInputStream (was %p)", mInputStream);
    mInputStream = nullptr;
  }

  // Reset running flag so the next start() call can proceed without bailing
  // out on the "already running" guard.
  mIsRunning = false;
  LOGD("onErrorAfterClose: engine state reset. Ready for restart by Kotlin layer.");
}

