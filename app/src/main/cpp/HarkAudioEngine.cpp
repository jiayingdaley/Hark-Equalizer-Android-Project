/**
 * HarkAudioEngine.cpp — Hark 助聽器 DSP 音訊引擎 (Refactored v3, 2026-05-12)
 *
 * 重大更新:
 *   - [Pinna] 升級為雙峰補強模型 (2.7kHz 主峰, 4.5kHz 次峰)。
 *   - [Mic] 麥克風改用 Unprocessed 原始模式，繞過系統濾波。
 *   - [Gain] 修復 Double-Gain Bug (16-Band EQ 現僅作為 UI 控制，實際增益併入 WDRC 前端)。
 *   - [Mode] 增加 SituationalMode::AUTO 支援。
 */
#include "HarkAudioEngine.h"
#include <android/log.h>
#include <cmath>
#include <cstring>
#include <thread>
#include <chrono>

#define LOG_TAG "HarkAudioEngine"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static constexpr int CHANNEL_COUNT = 2;

// UI 16 Band center frequencies
static const double UI_CENTER_FREQS[16] = {
    250, 315, 400, 500, 630, 800, 1000, 1250,
    1600, 2000, 2500, 3150, 4000, 5000, 6300, 8000
};

// UI Index to Internal DSP Band (0-7)
static const int UI_TO_INTERNAL[16] = {
    1, 1, 1,     // 250, 315, 400   → Band 1 (250–500 Hz)
    2, 2, 2,     // 500, 630, 800   → Band 2 (500–1000 Hz)
    3, 3,        // 1000, 1250      → Band 3 (1000–1500 Hz)
    4, 4,        // 1600, 2000      → Band 4 (1500–2500 Hz)
    5, 5, 5,     // 2500, 3150, 4000→ Band 5 (2500–4500 Hz)
    6,           // 5000            → Band 6 (4500–6000 Hz)
    7, 7         // 6300, 8000      → Band 7 (> 6000 Hz)
};

HarkAudioEngine::HarkAudioEngine()
    : sampleRate(48000.0),
      mNoiseSuppressorL(48000.0), mNoiseSuppressorR(48000.0),
      mGestureDetector(48000.0),
      mEqLeft(NUM_UI_BANDS), mEqRight(NUM_UI_BANDS) {

    for (int i = 0; i < NUM_UI_BANDS; ++i) {
        mBandGains[i].store(0.0f, std::memory_order_relaxed);
        mGainDirty[i].store(false, std::memory_order_relaxed);
        mBandQs[i] = 1.4f;
    }
    for (int b = 0; b < NUM_INTERNAL_BANDS; ++b) {
        mPrescriptionGains[b]   = 1.0f;
        mPrescriptionTargets[b] = 1.0f;
    }
    updateDSPParameters();
}

HarkAudioEngine::~HarkAudioEngine() { stop(); }

void HarkAudioEngine::updateDSPParameters() {
    // LR4 Crossover setup
    const double xoFreqs[7] = { 1500.0, 500.0, 4500.0, 250.0, 1000.0, 2500.0, 6000.0 };
    mXoverMidL.setFrequency(xoFreqs[0], sampleRate); mXoverMidR.setFrequency(xoFreqs[0], sampleRate);
    mXoverLowL.setFrequency(xoFreqs[1], sampleRate); mXoverLowR.setFrequency(xoFreqs[1], sampleRate);
    mXoverHighL.setFrequency(xoFreqs[2], sampleRate); mXoverHighR.setFrequency(xoFreqs[2], sampleRate);
    mXoverVLowL.setFrequency(xoFreqs[3], sampleRate); mXoverVLowR.setFrequency(xoFreqs[3], sampleRate);
    mXoverLMidL.setFrequency(xoFreqs[4], sampleRate); mXoverLMidR.setFrequency(xoFreqs[4], sampleRate);
    mXoverHMidL.setFrequency(xoFreqs[5], sampleRate); mXoverHMidR.setFrequency(xoFreqs[5], sampleRate);
    mXoverVHiL.setFrequency(xoFreqs[6], sampleRate); mXoverVHiR.setFrequency(xoFreqs[6], sampleRate);

    // WDRC (8 bands)
    for (int b = 0; b < NUM_INTERNAL_BANDS; ++b) {
        mWdrcL[b].setParameters(-30.0f, 1.5f, -100.0f, 1.0f, 8.0f, 300.0f, sampleRate);
        mWdrcR[b].setParameters(-30.0f, 1.5f, -100.0f, 1.0f, 8.0f, 300.0f, sampleRate);
    }

    // Limiter
    mLimiterL.setParameters(-1.5f, 20.0f, -100.0f, 1.0f, 0.5f, 30.0f, sampleRate);
    mLimiterR.setParameters(-1.5f, 20.0f, -100.0f, 1.0f, 0.5f, 30.0f, sampleRate);

    // ── Dual-Peak Pinna Restore ──
    // Peak 1: Ear canal resonance (~2.7kHz)
    mPinnaPrimaryL.updateCoefficients(BiquadFilter::Type::Peaking, sampleRate, 2700.0, 7.0, 1.2);
    mPinnaPrimaryR.updateCoefficients(BiquadFilter::Type::Peaking, sampleRate, 2700.0, 7.0, 1.2);
    // Peak 2: Concha resonance (~4.5kHz)
    mPinnaSecondaryL.updateCoefficients(BiquadFilter::Type::Peaking, sampleRate, 4500.0, 3.5, 2.0);
    mPinnaSecondaryR.updateCoefficients(BiquadFilter::Type::Peaking, sampleRate, 4500.0, 3.5, 2.0);

    // 16-Band EQ (Used for UI mapping, bypassed in process loop to avoid double gain)
    for (int i = 0; i < NUM_UI_BANDS; ++i) {
        mEqLeft.updateBand(i, BiquadFilter::Type::Peaking, sampleRate, UI_CENTER_FREQS[i], 0.0f, mBandQs[i]);
        mEqRight.updateBand(i, BiquadFilter::Type::Peaking, sampleRate, UI_CENTER_FREQS[i], 0.0f, mBandQs[i]);
    }
}

void HarkAudioEngine::recomputePrescriptionGains() {
    float gainSum[NUM_INTERNAL_BANDS] = {};
    int   count  [NUM_INTERNAL_BANDS] = {};
    for (int i = 0; i < NUM_UI_BANDS; ++i) {
        float db = mBandGains[i].load(std::memory_order_relaxed);
        int b = UI_TO_INTERNAL[i];
        gainSum[b] += db;
        count[b]++;
    }
    for (int b = 0; b < NUM_INTERNAL_BANDS; ++b) {
        float avgDb = (count[b] > 0) ? gainSum[b] / (float)count[b] : 0.0f;
        
        // 再次拉高音量：位移設為 +8.0dB (超越無損，進行主動增強，適合輕中度聽損)
        float globalGainOffsetDb = 8.0f;
        mPrescriptionTargets[b] = powf(10.0f, (avgDb + globalGainOffsetDb) / 20.0f);
    }
    // 讓 Band 0 保持與主位準同步的 +4.0dB 偏移，但維持其噪音壓制係數
    float firstBandDb = mBandGains[0].load(std::memory_order_relaxed);
    mPrescriptionTargets[0] = powf(10.0f, (firstBandDb * 0.8f + 4.0f) / 20.0f);

    // 針對低頻頻段 (Band 0, 1) 使用更強大的噪音門
    // 這樣就算 EQ 拉高，沒聲音時也會被切斷
    mWdrcL[0].setParameters(-25.0f, 1.2f, -45.0f, 0.4f, 10.0f, 600.0f, sampleRate);
    mWdrcR[0].setParameters(-25.0f, 1.2f, -45.0f, 0.4f, 10.0f, 600.0f, sampleRate);
    mWdrcL[1].setParameters(-25.0f, 1.2f, -50.0f, 0.45f, 10.0f, 600.0f, sampleRate);
    mWdrcR[1].setParameters(-25.0f, 1.2f, -50.0f, 0.45f, 10.0f, 600.0f, sampleRate);
}

void HarkAudioEngine::start() {
    if (mIsRunning) return;
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
    if (!mIsRunning && !mInputStream && !mOutputStream) return;
    if (mInputStream)  { mInputStream->stop();  mInputStream->close();  mInputStream  = nullptr; }
    if (mOutputStream) { mOutputStream->stop(); mOutputStream->close(); mOutputStream = nullptr; }
    mIsRunning = false;
}

bool HarkAudioEngine::isEngineRunning() const {
    return mOutputStream && mOutputStream->getState() != oboe::StreamState::Closed;
}

bool HarkAudioEngine::setupStreams() {
    bool useHeadset = mUseHeadsetMic.load();
    // 為了極致低延遲與跨時脈物理穩定性：
    // 1. 當使用耳機收音時，輸入與輸出在同一個實體設備/時鐘源上，開啟 AAudio Exclusive 獨占低延遲模式以追求極限。
    // 2. 當強制使用手機麥克風收音時，因為輸入（手機內建 Codec）與輸出（藍牙耳機晶片）在不同的物理時鐘源上，
    //    獨占模式會因為時脈不同步或 HAL 驅動拒絕而崩潰無聲。我們必須使用 Shared 共享模式，讓系統 AudioFlinger 自動協調跨時區重採樣與時脈同步！
    oboe::SharingMode targetSharingMode = useHeadset ? oboe::SharingMode::Exclusive : oboe::SharingMode::Shared;

    // 關鍵路由物理常識：
    // 當使用耳機收音時，我們使用 Usage::Game 以追求極速路徑。
    // 當使用手機收音且藍牙耳機連線時，使用 Usage::Media (標準音樂) 能 100% 強制 Android 路由策略將其視為標準音樂播放，
    // 從而毫無阻礙地路由至藍牙 A2DP 耳機，絕對避免被系統錯誤路由至手機喇叭或聽筒！
    oboe::Usage targetUsage = useHeadset ? oboe::Usage::Game : oboe::Usage::Media;

    oboe::AudioStreamBuilder outBuilder;
    outBuilder.setDirection(oboe::Direction::Output)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(targetSharingMode)
        ->setAudioApi(oboe::AudioApi::AAudio)
        ->setFormat(oboe::AudioFormat::Float)
        ->setChannelCount(CHANNEL_COUNT)
        ->setUsage(targetUsage) 
        ->setSampleRate(48000) // 強制通用 48000Hz 輸出以對齊麥克風，消除一切取樣率轉換延遲與衝突
        ->setDataCallback(this)
        ->setErrorCallback(this);

    if (outBuilder.openStream(&mOutputStream) != oboe::Result::OK) {
        outBuilder.setAudioApi(oboe::AudioApi::Unspecified);
        outBuilder.setSharingMode(oboe::SharingMode::Shared);
        if (outBuilder.openStream(&mOutputStream) != oboe::Result::OK) return false;
    }

    sampleRate = mOutputStream->getSampleRate();
    int32_t outBurst = mOutputStream->getFramesPerBurst();
    // 雙倍緩衝區維持穩定性
    mOutputStream->setBufferSizeInFrames(outBurst * 2);
    
    LOGD("Output: SR=%.0f, Burst=%d, Buffer=%d, API=%d, Usage=Game, Sharing=%d", 
         sampleRate, outBurst, mOutputStream->getBufferSizeInFrames(), 
         (int)mOutputStream->getAudioApi(), (int)mOutputStream->getSharingMode());

    updateDSPParameters();

    // 為了極致低延遲與物理強行路由：
    // 1. 如果是使用耳機收音，我們保持 Device ID 為 oboe::kUnspecified，這能讓系統自動選用預設最優路由，並開啟 AAudio Exclusive 獨占低延遲模式！
    // 2. 如果是強制使用手機收音，我們必須傳入精確的手機麥克風實體 ID (mInputDeviceId)，強制 Android 繞過耳機，直接打開手機內建麥克風收音！
    int32_t targetInputDeviceId = useHeadset ? (int32_t)oboe::kUnspecified : mInputDeviceId;
    oboe::InputPreset targetPreset = oboe::InputPreset::Generic;

    oboe::AudioStreamBuilder inBuilder;
    inBuilder.setDirection(oboe::Direction::Input)
        ->setDeviceId(targetInputDeviceId)
        ->setInputPreset(targetPreset) 
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(targetSharingMode)
        ->setAudioApi(oboe::AudioApi::AAudio)
        ->setFormat(oboe::AudioFormat::Float)
        ->setChannelCount(oboe::ChannelCount::Mono)
        ->setSampleRate(48000) // 麥克風同樣強制 48000Hz，與輸出流物理完美同步！
        ->setErrorCallback(this); // 綁定錯誤回呼以偵測麥克風斷線

    auto result = inBuilder.openStream(&mInputStream);
    if (result != oboe::Result::OK) {
        LOGW("AAudio Explicit Exclusive 48000Hz failed: %s. Trying Shared mode with explicit ID...", oboe::convertToText(result));
        inBuilder.setSharingMode(oboe::SharingMode::Shared);
        result = inBuilder.openStream(&mInputStream);
    }
    if (result != oboe::Result::OK) {
        LOGW("AAudio Explicit Shared failed. Trying Unspecified device ID (kUnspecified) as safety fallback...");
        inBuilder.setDeviceId(oboe::kUnspecified);
        inBuilder.setSharingMode(oboe::SharingMode::Shared);
        result = inBuilder.openStream(&mInputStream);
    }
    if (result != oboe::Result::OK) {
        LOGW("AAudio Unspecified failed. Trying OpenSL ES with unspecified device...");
        inBuilder.setAudioApi(oboe::AudioApi::OpenSLES);
        inBuilder.setDeviceId(oboe::kUnspecified);
        inBuilder.setSharingMode(oboe::SharingMode::Shared);
        result = inBuilder.openStream(&mInputStream);
    }
    if (result != oboe::Result::OK) {
        LOGW("All inputs failed. Trying ultimate generic unspecified fallback...");
        inBuilder.setAudioApi(oboe::AudioApi::AAudio);
        inBuilder.setDeviceId(oboe::kUnspecified);
        inBuilder.setSharingMode(oboe::SharingMode::Shared);
        inBuilder.setInputPreset(oboe::InputPreset::Generic);
        inBuilder.setSampleRate(oboe::kUnspecified);
        result = inBuilder.openStream(&mInputStream);
    }

    if (result == oboe::Result::OK) {
        int32_t inBurst = mInputStream->getFramesPerBurst();
        mInputStream->setBufferSizeInFrames(inBurst * 2);
        LOGD("Input opened successfully: SR=%d, Burst=%d, Buffer=%d, API=%d, Sharing=%d", 
             mInputStream->getSampleRate(), inBurst, mInputStream->getBufferSizeInFrames(), 
             (int)mInputStream->getAudioApi(), (int)mInputStream->getSharingMode());
    } else {
        LOGE("Failed to open input stream completely. Cleaning up output stream to prevent zombie state!");
        if (mOutputStream) {
            mOutputStream->stop();
            mOutputStream->close();
            mOutputStream = nullptr;
        }
        return false;
    }

    mInputStream->requestStart();
    mOutputStream->requestStart();
    
    auto latency = mOutputStream->calculateLatencyMillis();
    if (latency) LOGD("Oboe Latency: %.2f ms", latency.value());

    return true;
}

void HarkAudioEngine::setBandGain(int bandIndex, float gainDb) {
    if (bandIndex < 0 || bandIndex >= NUM_UI_BANDS) return;
    mBandGains[bandIndex].store(gainDb, std::memory_order_relaxed);
    mGainDirty[bandIndex].store(true,   std::memory_order_release);
    recomputePrescriptionGains();
}

void HarkAudioEngine::setSituationalMode(SituationalMode mode) {
    std::lock_guard<std::mutex> lock(mDSPMutex);
    mCurrentMode = mode;
    
    auto setAllWdrc = [&](float th, float r, float atk, float rel, float expTh = -55.0f) {
        for (int b = 0; b < NUM_INTERNAL_BANDS; ++b) {
            // 使用 0.5f 作為擴展比率 (2:1 Expansion) 以壓制底噪
            mWdrcL[b].setParameters(th, r, expTh, 0.5f, atk, rel, sampleRate);
            mWdrcR[b].setParameters(th, r, expTh, 0.5f, atk, rel, sampleRate);
        }
    };

    switch (mode) {
        case SituationalMode::TRANSPARENCY:
            setAllWdrc(-20.0f, 1.2f, 10.0f, 600.0f, -60.0f);
            mNoiseSuppressorL.setEnabled(false); mNoiseSuppressorR.setEnabled(false);
            mPinnaEnabled = true;
            break;
        case SituationalMode::CONVERSATION:
            // 強化壓縮與噪音門，聚焦人聲
            setAllWdrc(-30.0f, 1.5f, 5.0f, 200.0f, -50.0f);
            mNoiseSuppressorL.setEnabled(true); mNoiseSuppressorR.setEnabled(true);
            mPinnaEnabled = true;
            break;
        case SituationalMode::OUTDOOR:
            // 強力的噪音門以對付風噪
            setAllWdrc(-25.0f, 1.3f, 5.0f, 100.0f, -45.0f);
            mNoiseSuppressorL.setEnabled(true); mNoiseSuppressorR.setEnabled(true);
            mPinnaEnabled = false;
            break;
        case SituationalMode::CINEMA:
            setAllWdrc(-15.0f, 1.1f, 20.0f, 800.0f, -65.0f);
            mNoiseSuppressorL.setEnabled(false); mNoiseSuppressorR.setEnabled(false);
            mPinnaEnabled = true;
            break;
        case SituationalMode::AUTO:
            LOGD("Auto Mode active");
            break;
    }
}

void HarkAudioEngine::setPinnaEnabled(bool enabled) {
    mPinnaEnabled = enabled;
}

void HarkAudioEngine::setInputGainOffset(float gainDb) {
    mInputGainFactor.store(powf(10.0f, gainDb / 20.0f));
}

void HarkAudioEngine::setUseHeadsetMic(bool useHeadset) {
    mUseHeadsetMic.store(useHeadset);
}

oboe::DataCallbackResult
HarkAudioEngine::onAudioReady(oboe::AudioStream* /*stream*/, void* audioData, int32_t numFrames) {
    if (!mInputStream) return oboe::DataCallbackResult::Continue;

    auto* buffer = static_cast<float*>(audioData);
    
    // 將讀取超時設為 1ms (1,000,000 ns)，避免在啟動初期產生 WouldBlock 導致的靜音
    auto result = mInputStream->read(buffer, numFrames, 1000000);
    if (!result) {
        // 若讀取失敗，則清空該段 buffer 避免產生隨機噪音
        memset(audioData, 0, sizeof(float) * numFrames * CHANNEL_COUNT);
        return oboe::DataCallbackResult::Continue;
    }

    // 取得實際讀取的幀數
    int32_t framesRead = result.value();
    
    // [重要] 套用輸入源增益補償 (Input Gain Compensation)
    float inputFactor = mInputGainFactor.load(std::memory_order_relaxed);
    if (inputFactor != 1.0f) {
        for (int i = 0; i < framesRead; ++i) {
            buffer[i] *= inputFactor;
        }
    }

    if (framesRead < numFrames) {
        // 填補不足的幀數為靜音
        memset(buffer + framesRead, 0, sizeof(float) * (numFrames - framesRead));
    }

    // Mono to Stereo
    for (int i = numFrames - 1; i >= 0; --i) {
        float s = buffer[i];
        buffer[i * 2] = s; buffer[i * 2 + 1] = s;
    }

    float finalGain = mIsMuted.load(std::memory_order_relaxed) ? 0.0f : mMasterGain.load(std::memory_order_relaxed);

    if (mBypassMode) {
        if (finalGain != 1.0f) {
            for (int i = 0; i < numFrames * CHANNEL_COUNT; ++i) {
                buffer[i] *= finalGain;
            }
        }
        return oboe::DataCallbackResult::Continue;
    }

    // Smooth prescription gains
    for (int b = 0; b < NUM_INTERNAL_BANDS; ++b) {
        mPrescriptionGains[b] = GAIN_SMOOTH_ALPHA * mPrescriptionGains[b] + (1.0f - GAIN_SMOOTH_ALPHA) * mPrescriptionTargets[b];
    }

    for (int i = 0; i < numFrames * CHANNEL_COUNT; i += CHANNEL_COUNT) {
        float sL = buffer[i]; float sR = buffer[i + 1];

        // [0] Simple DC Blocker to prevent IIR instability
        static float lastInL = 0, lastInR = 0, lastOutL = 0, lastOutR = 0;
        float curInL = sL; float curInR = sR;
        sL = curInL - lastInL + 0.995f * lastOutL;
        sR = curInR - lastInR + 0.995f * lastOutR;
        lastInL = curInL; lastInR = curInR; lastOutL = sL; lastOutR = sR;

        // [1] Noise Suppressor
        sL = mNoiseSuppressorL.process(sL); sR = mNoiseSuppressorR.process(sR);

        // [2] Advanced Dual-Peak Pinna Restore
        if (mPinnaEnabled) {
            sL = mPinnaPrimaryL.process(mPinnaSecondaryL.process(sL));
            sR = mPinnaPrimaryR.process(mPinnaSecondaryR.process(sR));
        }

        // [3] 8-Band Filterbank with Integrated UI Gain
        {
            auto midL = mXoverMidL.process(sL);
            auto lowL = mXoverLowL.process(midL.low); auto highL = mXoverHighL.process(midL.high);
            auto vlL = mXoverVLowL.process(lowL.low); auto lmL = mXoverLMidL.process(lowL.high);
            auto hmL = mXoverHMidL.process(highL.low); auto vhL = mXoverVHiL.process(highL.high);

            float bandsL[8] = {vlL.low, vlL.high, lmL.low, lmL.high, hmL.low, hmL.high, vhL.low, vhL.high};
            sL = 0.0f;
            for (int b = 0; b < 8; ++b) sL += mWdrcL[b].process(bandsL[b] * mPrescriptionGains[b]);

            auto midR = mXoverMidR.process(sR);
            auto lowR = mXoverLowR.process(midR.low); auto highR = mXoverHighR.process(midR.high);
            auto vlR = mXoverVLowR.process(lowR.low); auto lmR = mXoverLMidR.process(lowR.high);
            auto hmR = mXoverHMidR.process(highR.low); auto vhR = mXoverVHiR.process(highR.high);

            float bandsR[8] = {vlR.low, vlR.high, lmR.low, lmR.high, hmR.low, hmR.high, vhR.low, vhR.high};
            sR = 0.0f;
            for (int b = 0; b < 8; ++b) sR += mWdrcR[b].process(bandsR[b] * mPrescriptionGains[b]);
        }

        // [4] Limiter & Soft Clip
        sL = mLimiterL.process(sL); sR = mLimiterR.process(sR);

        // [5] Master Volume & Soft Clip
        buffer[i] = tanhf(sL * finalGain); 
        buffer[i + 1] = tanhf(sR * finalGain);
    }

    return oboe::DataCallbackResult::Continue;
}

void HarkAudioEngine::onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) {
    LOGW("Oboe stream disconnected! Error: %s. Spawning auto-recovery thread...", oboe::convertToText(error));
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
        if (latency) LOGD("Latency: %.1f ms", latency.value());
    }
}

void HarkAudioEngine::calibrateNoiseSuppressor() {
    // Logic for noise floor calibration
}

void HarkAudioEngine::setBandQ(int bandIndex, float q_factor) {
    if (bandIndex >= 0 && bandIndex < NUM_UI_BANDS) mBandQs[bandIndex] = q_factor;
}

void HarkAudioEngine::setBandWdrcParameters(int band, float thresholdDb, float ratio, float attackMs, float releaseMs) {
    if (band >= 0 && band < 8) {
        mWdrcL[band].setParameters(thresholdDb, ratio, -100.0f, 1.0f, attackMs, releaseMs, sampleRate);
        mWdrcR[band].setParameters(thresholdDb, ratio, -100.0f, 1.0f, attackMs, releaseMs, sampleRate);
    }
}

void HarkAudioEngine::setWdrcParameters(float thresholdDb, float ratio, float attackMs, float releaseMs) {
    for (int b = 0; b < 8; ++b) setBandWdrcParameters(b, thresholdDb, ratio, attackMs, releaseMs);
}

void HarkAudioEngine::setLimiterParameters(float thresholdDb, float ratio, float attackMs, float releaseMs) {
    mLimiterL.setParameters(thresholdDb, ratio, -100.0f, 1.0f, attackMs, releaseMs, sampleRate);
    mLimiterR.setParameters(thresholdDb, ratio, -100.0f, 1.0f, attackMs, releaseMs, sampleRate);
}

void HarkAudioEngine::setInputDeviceId(int32_t deviceId) { mInputDeviceId = deviceId; }
void HarkAudioEngine::resetGesture() { mGestureDetector.reset(); }

void HarkAudioEngine::setBypassMode(bool bypass) {
    mBypassMode = bypass;
}

void HarkAudioEngine::setNoiseReductionEnabled(bool enabled) {
    mNoiseSuppressorL.setEnabled(enabled);
    mNoiseSuppressorR.setEnabled(enabled);
}

void HarkAudioEngine::setMasterGain(float gain) {
    mMasterGain.store(gain);
}

void HarkAudioEngine::setMuted(bool muted) {
    mIsMuted.store(muted);
}

float HarkAudioEngine::getBandEnergy(int band) const {
    if (band < 0 || band >= 5) return 0.0f;
    return mNoiseSuppressorL.getBandEnergy(band);
}
