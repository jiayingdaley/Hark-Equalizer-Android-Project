#pragma once
#include <cmath>
#include <algorithm>

class DynamicsProcessor {
public:
    DynamicsProcessor();

    // 設定壓縮器參數
    void setParameters(float thresholdDb, float ratio, float attackMs, float releaseMs, double sampleRate);
    void setSampleRate(double sampleRate);

    // 處理單一樣本
    float process(float inputSample);

private:
    // 參數
    float mThreshold;   // 線性振幅 (Linear amplitude)
    float mRatio;
    float mAttackCoeff;
    float mReleaseCoeff;

    // 狀態
    float mEnvelope; // 包絡檢測器的當前值
};