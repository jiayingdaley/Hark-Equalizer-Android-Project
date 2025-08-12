#include "HarkAudioEngine.h"
#include <android/log.h>

#define LOG_TAG "HarkAudioEngine"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)


const int NUM_BANDS = 16;
const double centerFrequencies[] = {
        250.0, 315.0, 400.0, 500.0, 630.0, 800.0, 1000.0, 1250.0,
        1600.0, 2000.0, 2500.0, 3150.0, 4000.0, 5000.0, 6300.0, 8000.0
};

HarkAudioEngine::HarkAudioEngine() : filterChain(NUM_BANDS), sampleRate(48000.0), mBandGains(NUM_BANDS, 0.0f), mBandQs(NUM_BANDS, 1.8f), mInputDeviceId(oboe::kUnspecified), mIsRunning(false) { // Initialize mIsRunning
    // Initialize filters with default values
    for (int i = 0; i < NUM_BANDS; ++i) {
        filterChain.updateBand(i, sampleRate, centerFrequencies[i], mBandGains[i], mBandQs[i]);
    }
}

HarkAudioEngine::~HarkAudioEngine() {
    stop();
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

void HarkAudioEngine::setOutputDeviceId(int32_t deviceId) {
    mOutputDeviceId = deviceId;
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

void HarkAudioEngine::setBandGain(int bandIndex, float gainDb) {
    if (bandIndex >= 0 && bandIndex < NUM_BANDS) {
        mBandGains[bandIndex] = gainDb;
        filterChain.updateBand(bandIndex, sampleRate, centerFrequencies[bandIndex], mBandGains[bandIndex], mBandQs[bandIndex]);
    }
}

void HarkAudioEngine::setBandQ(int bandIndex, float q_factor) {
    if (bandIndex >= 0 && bandIndex < NUM_BANDS) {
        mBandQs[bandIndex] = q_factor;
        filterChain.updateBand(bandIndex, sampleRate, centerFrequencies[bandIndex], mBandGains[bandIndex], mBandQs[bandIndex]);
    }
}

oboe::DataCallbackResult HarkAudioEngine::onAudioReady(oboe::AudioStream *oboeStream, void *audioData, int32_t numFrames) {
    // Read audio from the input stream directly into the output buffer.
    auto result = mInputStream->read(audioData, numFrames, 0);

    if (!result) { // Note: Oboe V3 uses !result to check for errors.
        LOGE("Input stream read error: %s", oboe::convertToText(result.error()));
        // Fill buffer with silence in case of an error to avoid loud noise.
        memset(audioData, 0, sizeof(float) * numFrames);
    }

    // Apply the filter chain.
    auto *outputData = static_cast<float *>(audioData);
    for (int i = 0; i < numFrames; ++i) {
        outputData[i] = filterChain.process(outputData[i]);
    }

    return oboe::DataCallbackResult::Continue;
}
