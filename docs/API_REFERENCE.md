# 📖 Hark API 參考

**目標**: JNI 方法、C++ API、Oboe 集成  

---

## Kotlin/Java 端 (JNI 聲明)

### HarkAudioBridge.kt

```kotlin
package com.wcy.hark.audio

/**
 * JNI Bridge: Centralizes all native (C++) audio engine function declarations.
 */
object HarkAudioBridge {

    /**
     * Starts the Oboe audio streams (input + output) and begins real-time DSP processing.
     */
    external fun startEngine()

    /**
     * Stops and releases all Oboe audio streams.
     */
    external fun stopEngine()

    /**
     * Sets the gain for a specific parametric EQ band.
     *
     * @param bandIndex 0-based index (0..15)
     * @param gainDb    Gain in dB.
     */
    external fun setBandGain(bandIndex: Int, gainDb: Float)

    /**
     * Sets the Q factor for a specific EQ band.
     *
     * @param bandIndex 0-based index (0..15)
     * @param q_factor  Default: 1.8f.
     */
    external fun setBandQ(bandIndex: Int, q_factor: Float)

    /**
     * Specifies the Android audio device ID for the Oboe input stream.
     *
     * @param deviceId AudioDeviceInfo.getId() value. Use 0 for system default.
     */
    external fun setAudioInputDeviceId(deviceId: Int)

    /**
     * Queries the actual running state of the native audio streams.
     *
     * @return true if the output stream exists and is not in Closed state.
     */
    external fun isEngineActuallyRunning(): Boolean
}
```

---

## C++ 端 API

### HarkAudioEngine.h

```cpp
#pragma once
#include <oboe/Oboe.h>
#include "DynamicsProcessor.h"
#include "FilterChain.h"

class HarkAudioEngine : public oboe::AudioStreamCallback {
public:
    HarkAudioEngine();
    virtual ~HarkAudioEngine();

    // ─────────────── 生命週期 ───────────────
    /**
     * 啟動音訊引擎
     * - 設置 Oboe 流
     * - 初始化 DSP 參數
     * - 開始 audio 回調
     */
    void start();

    /**
     * 停止音訊引擎
     * - 關閉 Oboe 流
     * - 釋放資源
     */
    void stop();

    /**
     * 檢查引擎運行狀態
     *
     * @return true 若 OutputStream 已啟動
     */
    bool isEngineRunning() const;

    // ─────────────── 參數設置 ───────────────
    /**
     * 設置前置增益
     *
     * @param gainDb dB 單位增益值
     */
    void setPreGain(float gainDb);

    /**
     * 設置補妝增益
     *
     * @param gainDb dB 單位增益值 (建議 +18dB)
     * @see DEFAULT_MAKEUP_GAIN_DB
     */
    void setMakeupGain(float gainDb);

    /**
     * 設置特定 EQ 帶的增益
     *
     * @param bandIndex 0-15 (對應 16 個頻帶)
     * @param gainDb [-12, +12] dB
     * @see centerFrequencies[]
     */
    void setBandGain(int bandIndex, float gainDb);

    /**
     * 設置輸入設備 ID
     *
     * @param deviceId Oboe 標識符 (kUnspecified = -1)
     */
    void setInputDeviceId(int32_t deviceId);

    // ─────────────── Oboe 回調 ───────────────
    /**
     * 實時音訊處理回調
     * 每 1ms 調用一次 (~48 樣本 @ 48kHz)
     *
     * @param oboeStream Oboe 流指針
     * @param audioData 音訊緩衝區 (float*, stereo interleaved)
     * @param numFrames 幀數
     * @return DataCallbackResult::Continue 繼續, Stop 停止
     */
    oboe::DataCallbackResult onAudioReady(
        oboe::AudioStream *oboeStream,
        void *audioData,
        int32_t numFrames
    ) override;

    /**
     * 流錯誤回調（設備斷開時）
     *
     * @param audioStream 發生錯誤的流
     * @param error 錯誤類型 (oboe::Result::...)
     */
    void onErrorAfterClose(oboe::AudioStream *audioStream, oboe::Result error) override;

private:
    // ─────────────── 內部方法 ───────────────
    void updateDSPParameters();          // 重新初始化 DSP
    bool setupStreams();                  // 設置 Oboe 流

    // ─────────────── 狀態 ───────────────
    oboe::ManagedStream mInputStream;
    oboe::ManagedStream mOutputStream;
    bool mIsRunning = false;
    double sampleRate = 48000.0;

    // ─────────────── DSP 單元 ───────────────
    DynamicsProcessor mWdrcLeft, mWdrcRight;
    DynamicsProcessor mLimiterLeft, mLimiterRight;
    FilterChain mFilterChainLeft, mFilterChainRight;

    // ─────────────── 參數 ───────────────
    float mPreGainLinear;
    float mMakeupGainLinear;
    float mMakeupGainDb;
    std::vector<float> mBandGains;
    std::vector<float> mBandQs;
    float mAutoHeadroomLinear;

    // ─────────────── 同步 ───────────────
    std::mutex mDSPMutex;  // 保護 DSP 參數訪問
};
```

### DynamicsProcessor.h

```cpp
#pragma once

class DynamicsProcessor {
public:
    DynamicsProcessor();

    /**
     * 設置動態處理參數
     *
     * @param compressThresholdDb 壓縮閾值 (dBFS, 通常 -40)
     * @param compressRatio 壓縮比 (通常 2:1)
     * @param expanderThresholdDb 擴展閾值 (dBFS, 通常 -70)
     * @param expanderRatio 擴展比 (通常 0.9:1)
     * @param attackMs Attack 時間 (ms, 典型 10)
     * @param releaseMs Release 時間 (ms, 典型 80)
     * @param sampleRate 採樣率 (Hz, 通常 48000)
     */
    void setParameters(
        float compressThresholdDb,
        float compressRatio,
        float expanderThresholdDb,
        float expanderRatio,
        float attackMs,
        float releaseMs,
        double sampleRate
    );

    /**
     * 設置採樣率 (佔位，實際在 setParameters 中)
     */
    void setSampleRate(double sampleRate);

    /**
     * 處理單一樣本
     *
     * @param inputSample 輸入 (通常 [-1, +1])
     * @return 輸出樣本 (經過壓縮/擴展)
     */
    float process(float inputSample);

private:
    // 參數
    float mCompressThreshold;
    float mCompressRatio;
    float mExpanderThreshold;
    float mExpanderRatio;
    float mAttackCoeff;
    float mReleaseCoeff;
    float mKneeDb = 2.0f;

    // 增益平滑
    float mCurrentGain = 1.0f;
    float mTargetGain = 1.0f;
    int mCounter = 0;
    static const int UPDATE_INTERVAL = 16;

    // 包絡檢測狀態
    float mEnvelope = 0.0f;
};
```

### FilterChain.h & BiquadFilter.h

```cpp
class FilterChain {
public:
    explicit FilterChain(int numBands);

    /**
     * 更新特定頻帶的濾波器
     *
     * @param bandIndex 頻帶索引
     * @param type Peaking / LowShelf / HighShelf
     * @param sampleRate 採樣率 (Hz)
     * @param centerHz 中心頻率 (Hz)
     * @param gainDb 增益 (dB)
     * @param q_factor Q 因子 (通常 1.8)
     */
    void updateBand(
        int bandIndex,
        BiquadFilter::Type type,
        double sampleRate,
        double centerHz,
        double gainDb,
        double q_factor
    );

    /**
     * 處理輸入樣本，通過所有濾波器
     *
     * @param in 輸入樣本
     * @return 濾波後輸出樣本
     */
    float process(float in);

private:
    std::vector<BiquadFilter> filters;
};

class BiquadFilter {
public:
    enum class Type { Peaking, LowShelf, HighShelf };

    BiquadFilter();

    /**
     * 更新 Biquad 係數（RBJ Cookbook）
     */
    void updateCoefficients(
        Type type,
        double sampleRate,
        double centerHz,
        double gainDb,
        double q_factor
    );

    /**
     * 處理單一樣本
     */
    float process(float in);

private:
    // 轉移函數係數
    double b0, b1, b2, a1, a2;
    // 狀態變數
    double x1, x2, y1, y2;
};
```

---

## JNI 綁定 (native-lib.cpp)

```cpp
#include <jni.h>
#include "HarkAudioEngine.h"

// The single, static instance of our audio engine.
static HarkAudioEngine engine;

extern "C" {

JNIEXPORT void JNICALL
Java_com_wcy_hark_audio_HarkAudioBridge_startEngine(JNIEnv *env, jobject) {
    engine.start();
}

JNIEXPORT void JNICALL
Java_com_wcy_hark_audio_HarkAudioBridge_stopEngine(JNIEnv *env, jobject) {
    engine.stop();
}

JNIEXPORT void JNICALL
Java_com_wcy_hark_audio_HarkAudioBridge_setBandGain(
        JNIEnv *env, jobject, jint bandIndex, jfloat gainDb) {
    engine.setBandGain(bandIndex, gainDb);
}

JNIEXPORT void JNICALL
Java_com_wcy_hark_audio_HarkAudioBridge_setBandQ(
        JNIEnv *env, jobject, jint bandIndex, jfloat q_factor) {
    engine.setBandQ(bandIndex, q_factor);
}

JNIEXPORT void JNICALL
Java_com_wcy_hark_audio_HarkAudioBridge_setAudioInputDeviceId(
        JNIEnv *env, jobject, jint device_id) {
    engine.setInputDeviceId(device_id);
}

JNIEXPORT jboolean JNICALL
Java_com_wcy_hark_audio_HarkAudioBridge_isEngineActuallyRunning(
        JNIEnv *env, jobject) {
    return (jboolean) engine.isEngineRunning();
}

} // extern "C"
```

---

## Oboe API 集成

### AAudio vs OpenSL ES

```
Oboe 是 Google 提供的音訊抽象層:

┌─────────────────────────────────────┐
│         Oboe API (統一)              │
├─────────────────────────────────────┤
│              │                       │
│              ▼                       │
│  ┌──────────────────┐               │
│  │ AAudio (推薦)    │ (API 29+)     │
│  │ • 低延遲         │               │
│  │ • 優先級別       │               │
│  └──────────────────┘               │
│              │                       │
│              ▼                       │
│  ┌──────────────────┐               │
│  │ OpenSL ES        │ (備降)        │
│  │ • 舊版支援       │               │
│  │ • 較高延遲       │               │
│  └──────────────────┘               │
└─────────────────────────────────────┘

Hark 策略: 
  • 優先使用 AAudio (低延遲)
  • 如果失敗，自動降轉 OpenSL ES
```

### Oboe 流配置

```cpp
// Input Stream (麥克風)
oboe::AudioStreamBuilder inBuilder;
inBuilder.setDirection(oboe::Direction::Input)
    ->setInputPreset(oboe::InputPreset::VoiceCommunication)
    ->setPerformanceMode(oboe::PerformanceMode::None)  // 穩定優先
    ->setSharingMode(oboe::SharingMode::Shared)
    ->setFormat(oboe::AudioFormat::Float)
    ->setChannelCount(1)  // 單聲道 (藍牙 SCO 限制)
    ->setSampleRate(48000);

// Output Stream (揚聲器/耳機)
oboe::AudioStreamBuilder outBuilder;
outBuilder.setDirection(oboe::Direction::Output)
    ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
    ->setSharingMode(oboe::SharingMode::Exclusive)
    ->setFormat(oboe::AudioFormat::Float)
    ->setChannelCount(2)  // 立體聲
    ->setDataCallback(this)
    ->setErrorCallback(this);
```

---

## 常見使用模式

### 啟動引擎並設置 EQ

```kotlin
val bridge = HarkAudioBridge
if (bridge.startEngine()) {
    // 設置第 5 個頻帶 (1000Hz) 增益 +6dB
    bridge.setBandGain(4, 6.0f)  // 索引 0-15
} else {
    Log.e("Hark", "Failed to start engine")
}
```

### 停止引擎

```kotlin
bridge.stopEngine()  // 釋放所有資源
```

### 查詢運行狀態

```kotlin
if (bridge.isEngineActuallyRunning()) {
    // 引擎已啟動
} else {
    // 引擎已停止
}
```

---

**參考**: [ARCHITECTURE.md](ARCHITECTURE.md) | [DEVELOPMENT.md](DEVELOPMENT.md)
