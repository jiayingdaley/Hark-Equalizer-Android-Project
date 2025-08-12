#include "HarkAudioEngine.h"
#include <android/log.h>

#define LOG_TAG "HarkAudioEngine"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define DEFAULT_MAKEUP_GAIN_DB 6.0f // <--- 在這裡調整音量！試試 6.0 到 20.0 之間的值
#define DEFAULT_PRE_GAIN_DB 15.0f

const int NUM_SHELF_FILTERS = 2;
const int NUM_PEAK_FILTERS = 16; // 這個對應到原本的 NUM_BANDS
const int TOTAL_FILTERS = NUM_SHELF_FILTERS + NUM_PEAK_FILTERS; // 總共 18 個並聯濾波器

const double centerFrequencies[] = {
        250.0, 315.0, 400.0, 500.0, 630.0, 800.0, 1000.0, 1250.0,
        1600.0, 2000.0, 2500.0, 3150.0, 4000.0, 5000.0, 6300.0, 8000.0
};

HarkAudioEngine::HarkAudioEngine() : filterChain(NUM_PEAK_FILTERS), sampleRate(48000.0), mBandGains(NUM_PEAK_FILTERS, 0.0f), mBandQs(NUM_PEAK_FILTERS, 1.8f), mInputDeviceId(oboe::kUnspecified), mIsRunning(false) { // Initialize mIsRunning
    // --- 初始化前級增益 ---
    setPreGain(DEFAULT_PRE_GAIN_DB);

    // --- 初始化並聯濾波器組 ---
    // 0: Low-Shelf
    filterChain.updateBand(0, BiquadFilter::Type::LowShelf, sampleRate, 250.0, -3.0, 0.707); // 預設稍微降低低頻雜音
    // 1 to 16: Peaking EQs (使用者可調)
    for (int i = 0; i < NUM_PEAK_FILTERS; ++i) {
        // UI 控制的頻段現在索引是 i + 1
        filterChain.updateBand(i + 1, BiquadFilter::Type::Peaking, sampleRate, centerFrequencies[i], mBandGains[i], mBandQs[i]);
    }
    // 17: High-Shelf
    filterChain.updateBand(TOTAL_FILTERS - 1, BiquadFilter::Type::HighShelf, sampleRate, 8000.0, 0.0, 0.707); // 預設保留高頻細節

    // --- 修改 WDRC 閾值 ---
    // 提高閾值，只處理較大的聲音
    mWdrc.setParameters(-25.0f, 2.5f, 5.0f, 100.0f, sampleRate);

    // Limiter 和 Makeup Gain 初始化保持不變
    mLimiter.setParameters(-1.0f, 20.0f, 1.0f, 50.0f, sampleRate);
    setMakeupGain(DEFAULT_MAKEUP_GAIN_DB);

}

HarkAudioEngine::~HarkAudioEngine() {
    stop();
}

void HarkAudioEngine::setPreGain(float gainDb) {
    mPreGainLinear = powf(10.0f, gainDb / 20.0f);
}

void HarkAudioEngine::setInputDeviceId(int32_t deviceId) {
    // It's generally safer to re-create streams if the device ID changes while running.
    // However, if the engine is stopped, just update the ID for the next start.
    if (mIsRunning) {
        LOGD("Input device ID changed while running. Stopping and will restart with new device.");
        // Consider a more robust mechanism to restart with the new device ID.
        // For now, just logging. A full restart might be needed.
        // stop(); // This might be too abrupt or lead to complex restart logic here.
        // A better approach might be to signal the calling layer (Java/Kotlin)
        // to stop and then start the engine again with the new device.
    }
    mInputDeviceId = deviceId;
}

void HarkAudioEngine::setMakeupGain(float gainDb) {
    mMakeupGainDb = gainDb;
    mMakeupGainLinear = powf(10.0f, mMakeupGainDb / 20.0f);
    LOGD("Makeup gain set to %.2f dB (Linear: %.4f)", mMakeupGainDb, mMakeupGainLinear);
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
            LOGD("Input stream frames per burst: %d", mInputStream->getFramesPerBurst());
            LOGD("Input stream buffer size in frames: %d", mInputStream->getBufferSizeInFrames());
        }
        if (mOutputStream) {
            LOGD("Output stream frames per burst: %d", mOutputStream->getFramesPerBurst());
            LOGD("Output stream buffer size in frames: %d", mOutputStream->getBufferSizeInFrames());
        }
    } else {
        LOGE("Failed to start HarkAudioEngine because setupStreams failed.");
        // 確保資源在 setupStreams 失敗時被清理
        stop(); // stop() 內部應能處理部分初始化的情況
    }
}

bool HarkAudioEngine::isEngineRunning() const {
    // 只要 mOutputStream 存在且正在串流，就代表引擎在運作
    return (mOutputStream && mOutputStream->getState() != oboe::StreamState::Closed);
}

bool HarkAudioEngine::setupStreams() {
    oboe::AudioStreamBuilder outBuilder;
    // The output stream is a "push" stream, so it needs a callback.
    outBuilder.setDirection(oboe::Direction::Output)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(oboe::SharingMode::Exclusive)
            ->setFormat(oboe::AudioFormat::Float)
            ->setChannelCount(1)
            ->setSampleRate(static_cast<int32_t>(sampleRate))
            ->setDataCallback(this);

    oboe::Result result = outBuilder.openStream(&mOutputStream);
    if (result != oboe::Result::OK || !mOutputStream) { // 檢查 mOutputStream 是否為 nullptr
        LOGE("Failed to open output stream. Error: %s", oboe::convertToText(result));
        return false;
    }

    // 使用 output stream 的 burst size 作為參考
    int32_t framesPerBurst = mOutputStream->getFramesPerBurst();
    LOGD("Output stream framesPerBurst: %d. Using this for buffer sizes.", framesPerBurst);

    // Now, create the input stream.
    oboe::AudioStreamBuilder inBuilder;
    inBuilder.setDirection(oboe::Direction::Input)
                    // If mInputDeviceId is not kUnspecified, use it. Otherwise, match the output device.
            ->setDeviceId(mInputDeviceId == oboe::kUnspecified ? mOutputStream->getDeviceId() : mInputDeviceId)
            ->setInputPreset(oboe::InputPreset::VoiceRecognition) // Using VoiceRecognition as in original code, can be changed to VoiceCommunication if needed
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(oboe::SharingMode::Exclusive)
            ->setFormat(oboe::AudioFormat::Float)
            ->setChannelCount(1)
            ->setSampleRate(static_cast<int32_t>(sampleRate));

    result = inBuilder.openStream(&mInputStream);
    if (result != oboe::Result::OK || !mInputStream) { // 檢查 mInputStream 是否為 nullptr
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
        LOGW("Warning: Failed to set input stream buffer size to %d. Error: %s", framesPerBurst, oboe::convertToText(resInput));
    } else {
        LOGD("Input stream buffer size successfully set to %d frames", mInputStream->getBufferSizeInFrames());
    }

    oboe::Result resOutput = mOutputStream->setBufferSizeInFrames(framesPerBurst);
    if (resOutput != oboe::Result::OK) {
        LOGW("Warning: Failed to set output stream buffer size to %d. Error: %s", framesPerBurst, oboe::convertToText(resOutput));
    } else {
        LOGD("Output stream buffer size successfully set to %d frames", mOutputStream->getBufferSizeInFrames());
    }

    result = mInputStream->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("Failed to start input stream. Error: %s", oboe::convertToText(result));
        // 清理
        if (mOutputStream) { mOutputStream->close(); mOutputStream = nullptr; }
        if (mInputStream) { mInputStream->close(); mInputStream = nullptr; }
        return false;
    }

    result = mOutputStream->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("Failed to start output stream. Error: %s", oboe::convertToText(result));
        // 清理
        if (mInputStream) { mInputStream->requestStop(); mInputStream->close(); mInputStream = nullptr; }
        if (mOutputStream) { mOutputStream->close(); mOutputStream = nullptr; } // mOutputStream 可能未 stop
        return false;
    }

    return true;
    LOGD("HarkAudioEngine started successfully.");
}

void HarkAudioEngine::stop() {
    if (!mIsRunning) return;
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

// 注意索引偏移
void HarkAudioEngine::setBandGain(int bandIndex, float gainDb) {
    if (bandIndex >= 0 && bandIndex < NUM_PEAK_FILTERS) {
        mBandGains[bandIndex] = gainDb;
        // 注意索引 +1，因為第 0 個濾波器是 Low-Shelf
        filterChain.updateBand(bandIndex + 1, BiquadFilter::Type::Peaking, sampleRate, centerFrequencies[bandIndex], mBandGains[bandIndex], mBandQs[bandIndex]);
    }
}

void HarkAudioEngine::setBandQ(int bandIndex, float q_factor) {
    if (bandIndex >= 0 && bandIndex < NUM_PEAK_FILTERS) {
        mBandQs[bandIndex] = q_factor; // 更新 mBandQs 陣列中的 Q 值
        // 確保傳遞正確的參數數量和順序
        // Peaking EQ 從濾波器鏈的索引 1 開始，對應 UI 的 bandIndex 0
        filterChain.updateBand(
            bandIndex + 1,                 // 1. 濾波器在鏈中的實際索引 (+1)
            BiquadFilter::Type::Peaking,   // 2. 濾波器類型
            sampleRate,                    // 3. 取樣率
            centerFrequencies[bandIndex],  // 4. 中心頻率
            mBandGains[bandIndex],         // 5. 該頻段的增益 (從 mBandGains 獲取)
            mBandQs[bandIndex]             // 6. 新的 Q 值 (剛剛在上面這行賦值的)
        );
    }
}

// --- 新增參數設定函數的實作 ---
void HarkAudioEngine::setWdrcParameters(float thresholdDb, float ratio, float attackMs, float releaseMs) {
    mWdrc.setParameters(thresholdDb, ratio, attackMs, releaseMs, sampleRate);
}

void HarkAudioEngine::setLimiterParameters(float thresholdDb, float ratio, float attackMs, float releaseMs) {
    mLimiter.setParameters(thresholdDb, ratio, attackMs, releaseMs, sampleRate);
}


// --- 最關鍵的修改：更新 onAudioReady 處理鏈 ---
oboe::DataCallbackResult HarkAudioEngine::onAudioReady(oboe::AudioStream *oboeStream, void *audioData, int32_t numFrames) {
    auto result = mInputStream->read(audioData, numFrames, 0);

    if (!result) {
        LOGE("Input stream read error: %s", oboe::convertToText(result.error()));
        memset(audioData, 0, sizeof(float) * numFrames); // 將緩衝區填滿靜音以避免錯誤時產生雜訊
        return oboe::DataCallbackResult::Continue;
    }

    auto *buffer = static_cast<float *>(audioData);
    for (int i = 0; i < numFrames; ++i) {
        float sample = buffer[i];

        // Stage 1: Pre-Gain
        sample = sample * mPreGainLinear;

        // Stage 2: Parallel Filter Bank
        sample = filterChain.process(sample);

        // Stage 3 & 4: WDRC and Limiter
        sample = mWdrc.process(sample);
        sample = mLimiter.process(sample);

        // Stage 5: Post-Gain (Makeup)
        sample = sample * mMakeupGainLinear;

        buffer[i] = sample;
    }
    return oboe::DataCallbackResult::Continue;
}
