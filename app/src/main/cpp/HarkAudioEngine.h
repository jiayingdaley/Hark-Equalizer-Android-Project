#pragma once

#include <oboe/Oboe.h>
#include "FilterChain.h"
#include <memory>
#include <vector>

class HarkAudioEngine : public oboe::AudioStreamDataCallback {
public:
    bool setupStreams();
    HarkAudioEngine();
    ~HarkAudioEngine();

    void start();
    void stop();
    void setBandGain(int bandIndex, float gainDb);
    void setBandQ(int bandIndex, float q_factor);
    void setInputDeviceId(int32_t deviceId);

    // Oboe callback
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *oboeStream, void *audioData, int32_t numFrames) override;

private:

    oboe::AudioStream *mInputStream = nullptr;
    oboe::AudioStream *mOutputStream = nullptr;
    FilterChain filterChain;
    double sampleRate;
    std::vector<float> mBandGains;
    std::vector<float> mBandQs;
    bool mIsRunning = false;
    int32_t mInputDeviceId = oboe::kUnspecified;
};