#include "HarkAudioEngine.h"
#include <android/log.h>

#define LOG_TAG "HarkAudioEngine"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

const int NUM_BANDS = 16;
const double centerFrequencies[] = {
    250.0, 315.0, 400.0, 500.0, 630.0, 800.0, 1000.0, 1250.0,
    1600.0, 2000.0, 2500.0, 3150.0, 4000.0, 5000.0, 6300.0, 8000.0
};

HarkAudioEngine::HarkAudioEngine() : filterChain(NUM_BANDS), sampleRate(48000.0), mBandGains(NUM_BANDS, 0.0f), mBandQs(NUM_BANDS, 1.8f) {
    // Initialize filters with default values
    for (int i = 0; i < NUM_BANDS; ++i) {
        filterChain.updateBand(i, sampleRate, centerFrequencies[i], mBandGains[i], mBandQs[i]);
    }
}

HarkAudioEngine::~HarkAudioEngine() {
    stop();
}

void HarkAudioEngine::start() {
    if (mIsRunning) return;
    LOGD("Starting HarkAudioEngine...");
    setupStreams();
}

void HarkAudioEngine::setupStreams() {
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
    if (result != oboe::Result::OK) {
        LOGE("Failed to open output stream. Error: %s", oboe::convertToText(result));
        return;
    }

    // Now, create the input stream, matching the output stream's device.
    int32_t outputDeviceId = mOutputStream->getDeviceId();

    oboe::AudioStreamBuilder inBuilder;
    inBuilder.setDirection(oboe::Direction::Input)
            ->setDeviceId(outputDeviceId) // Match the output device
            ->setInputPreset(oboe::InputPreset::VoiceRecognition)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(oboe::SharingMode::Exclusive)
            ->setFormat(oboe::AudioFormat::Float)
            ->setChannelCount(1)
            ->setSampleRate(static_cast<int32_t>(sampleRate));

    result = inBuilder.openStream(&mInputStream);
    if (result != oboe::Result::OK) {
        LOGE("Failed to open input stream. Error: %s", oboe::convertToText(result));
        mOutputStream->close(); // Clean up the successfully opened output stream
        mOutputStream = nullptr;
        return;
    }

    // Set a small buffer size for low latency. This is a critical step.
    int32_t framesPerBurst = mOutputStream->getFramesPerBurst();
    mInputStream->setBufferSizeInFrames(framesPerBurst);
    mOutputStream->setBufferSizeInFrames(framesPerBurst);
    LOGD("Buffer size set to %d frames", framesPerBurst);

    result = mInputStream->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("Failed to start input stream. Error: %s", oboe::convertToText(result));
        return;
    }

    result = mOutputStream->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("Failed to start output stream. Error: %s", oboe::convertToText(result));
        return;
    }

    mIsRunning = true;
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
