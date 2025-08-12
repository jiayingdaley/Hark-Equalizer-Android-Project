#include "DynamicsProcessor.h"
#include <android/log.h>

DynamicsProcessor::DynamicsProcessor() :
        mThreshold(1.0f),
        mRatio(1.0f),
        mAttackCoeff(1.0f),
        mReleaseCoeff(1.0f),
        mEnvelope(0.0f) {}

void DynamicsProcessor::setSampleRate(double sampleRate) {
    // 重新計算起音和釋放係數，如果需要的話
    // 這裡我們在 setParameters 中處理
}

void DynamicsProcessor::setParameters(float thresholdDb, float ratio, float attackMs, float releaseMs, double sampleRate) {
    mThreshold = powf(10.0f, thresholdDb / 20.0f);
    mRatio = ratio;

    // 計算起音和釋放時間的係數 (一階低通濾波器)
    // 這是數位壓縮器中非常標準的作法
    if (attackMs > 0) {
        mAttackCoeff = expf(-1.0f / (sampleRate * (attackMs / 1000.0f)));
    } else {
        mAttackCoeff = 0.0f; // 瞬時起音
    }

    if (releaseMs > 0) {
        mReleaseCoeff = expf(-1.0f / (sampleRate * (releaseMs / 1000.0f)));
    } else {
        mReleaseCoeff = 0.0f; // 瞬時釋放
    }
}

float DynamicsProcessor::process(float inputSample) {
    // 1. 包絡檢測 (Envelope Detection)
    // 取得訊號的絕對值，作為瞬時音量的參考
    float inputLevel = fabsf(inputSample);

    // 比較當前訊號位準和上一個包絡值，決定使用 attack 還是 release
    if (inputLevel > mEnvelope) {
        // Attack: 訊號變強，包絡快速跟上
        mEnvelope = mAttackCoeff * mEnvelope + (1.0f - mAttackCoeff) * inputLevel;
    } else {
        // Release: 訊號變弱，包絡緩慢下降
        mEnvelope = mReleaseCoeff * mEnvelope + (1.0f - mReleaseCoeff) * inputLevel;
    }

    // 2. 增益計算 (Gain Computation)
    float gain = 1.0f;
    if (mEnvelope > mThreshold) {
        // 訊號超過閾值，計算需要降低多少音量
        float overshootDb = 20.0f * log10f(mEnvelope / mThreshold);
        float gainReductionDb = overshootDb * (1.0f - (1.0f / mRatio));
        gain = powf(10.0f, -gainReductionDb / 20.0f);
    }

    // 3. 應用增益
    return inputSample * gain;
}