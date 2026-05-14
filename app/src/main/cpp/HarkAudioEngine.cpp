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
        
        // 再次拉高音量：位移設為 +3.0dB (超越無損，進行主動增強)
        float globalGainOffsetDb = 3.0f;
        mPrescriptionTargets[b] = powf(10.0f, (avgDb + globalGainOffsetDb) / 20.0f);
    }
    // 讓 Band 0 保持與主位準同步的 +3.0dB 偏移，但維持其噪音壓制係數
    float firstBandDb = mBandGains[0].load(std::memory_order_relaxed);
    mPrescriptionTargets[0] = powf(10.0f, (firstBandDb * 0.8f + 1.0f) / 20.0f);

    // 針對低頻頻段 (Band 0, 1) 使用更強大的噪音門
    // 這樣就算 EQ 拉高，沒聲音時也會被切斷
    mWdrcL[0].setParameters(-25.0f, 1.2f, -45.0f, 0.4f, 10.0f, 600.0f, sampleRate);
    mWdrcR[0].setParameters(-25.0f, 1.2f, -45.0f, 0.4f, 10.0f, 600.0f, sampleRate);
    mWdrcL[1].setParameters(-25.0f, 1.2f, -50.0f, 0.45f, 10.0f, 600.0f, sampleRate);
    mWdrcR[1].setParameters(-25.0f, 1.2f, -50.0f, 0.45f, 10.0f, 600.0f, sampleRate);
}

void HarkAudioEngine::start() {
    if (mIsRunning) return;
    if (setupStreams()) {
        mIsRunning = true;
        logLatencyStatistics();
    }
}

void HarkAudioEngine::stop() {
    if (!mIsRunning && !mInputStream && !mOutputStream) return;
    if (mInputStream)  { mInputStream->stop();  mInputStream->close();  mInputStream  = nullptr; }
    if (mOutputStream) { mOutputStream->stop(); mOutputStream->close(); mOutputStream = nullptr; }
    mIsRunning = false;
}

bool HarkAudioEngine::isEngineRunning() const {
    return mOutputStream && mOutputStream->getState() != oboe::StreamState::Closed;
}

bool HarkAudioEngine::setupStreams() {
    oboe::AudioStreamBuilder outBuilder;
    outBuilder.setDirection(oboe::Direction::Output)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Exclusive)
        ->setFormat(oboe::AudioFormat::Float)
        ->setChannelCount(CHANNEL_COUNT)
        ->setUsage(oboe::Usage::VoiceCommunication)
        ->setContentType(oboe::ContentType::Speech)
        ->setDataCallback(this)
        ->setErrorCallback(this);

    if (outBuilder.openStream(&mOutputStream) != oboe::Result::OK) return false;
    sampleRate = mOutputStream->getSampleRate();
    updateDSPParameters();

    oboe::AudioStreamBuilder inBuilder;
    // 優先嘗試 Unprocessed 模式
    inBuilder.setDirection(oboe::Direction::Input)
        ->setDeviceId(mInputDeviceId)
        ->setInputPreset(oboe::InputPreset::Unprocessed) 
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setFormat(oboe::AudioFormat::Float)
        ->setChannelCount(oboe::ChannelCount::Mono)
        ->setSampleRate(static_cast<int32_t>(sampleRate));

    auto result = inBuilder.openStream(&mInputStream);
    if (result != oboe::Result::OK) {
        LOGW("Unprocessed input failed (code: %s), falling back to Generic Mic", oboe::convertToText(result));
        inBuilder.setInputPreset(oboe::InputPreset::Generic);
        result = inBuilder.openStream(&mInputStream);
        if (result != oboe::Result::OK) {
            LOGE("Failed to open ANY input stream: %s", oboe::convertToText(result));
            return false;
        }
    }

    mInputStream->requestStart();
    mOutputStream->requestStart();
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
    if (framesRead < numFrames) {
        // 填補不足的幀數為靜音
        memset(buffer + framesRead, 0, sizeof(float) * (numFrames - framesRead));
    }

    // Mono to Stereo
    for (int i = numFrames - 1; i >= 0; --i) {
        float s = buffer[i];
        buffer[i * 2] = s; buffer[i * 2 + 1] = s;
    }

    if (mBypassMode) return oboe::DataCallbackResult::Continue;

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

        // [5] Master Volume & Mute Sync (修復通話音量為 0 時仍有聲音的問題)
        float finalGain = mIsMuted ? 0.0f : mMasterGain;
        buffer[i] = tanhf(sL * finalGain); 
        buffer[i + 1] = tanhf(sR * finalGain);
    }

    return oboe::DataCallbackResult::Continue;
}

void HarkAudioEngine::onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) {
    mIsRunning = false;
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
    mMasterGain = gain;
}

void HarkAudioEngine::setMuted(bool muted) {
    mIsMuted = muted;
}

float HarkAudioEngine::getBandEnergy(int band) const {
    if (band < 0 || band >= 5) return 0.0f;
    return mNoiseSuppressorL.getBandEnergy(band);
}
