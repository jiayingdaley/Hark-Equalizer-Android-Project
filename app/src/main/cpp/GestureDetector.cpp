#include "GestureDetector.h"
#include <cmath>
#include <algorithm>

GestureDetector::GestureDetector(double sampleRate) 
    : mSampleRate(sampleRate), 
      mCurrentProfile(16, 0.0f), 
      mLockedProfile(16, 0.0f),
      mScanDurationSamples(static_cast<int>(sampleRate * 0.5)) {}

GestureState GestureDetector::update(float inputL, float inputR) {
    float absL = fabsf(inputL);
    float absR = fabsf(inputR);
    float currentSampleEnergy = (absL + absR) * 0.5f;

    // 1. Smooth energy tracking (更快速的追蹤，0.95 改為 0.98 從 0.999)
    mTotalEnergy = 0.98f * mTotalEnergy + 0.02f * currentSampleEnergy;

    // 2. State Machine - 簡化為快速能量型偵測
    switch (mState) {
        case GestureState::IDLE:
            // 偵測瞬間能量跳躍（手靠近耳朵時的聲學變化）
            // 門檻從 2.5x 降為 2.0x，更容易觸發
            if (currentSampleEnergy > mTotalEnergy * 2.0f && currentSampleEnergy > 0.03f) {
                mState = GestureState::SCANNING;
                mScanningCounter = 0;
                // 快速掃描：只需 500ms 而不是 2 秒
                std::fill(mCurrentProfile.begin(), mCurrentProfile.end(), 0.0f);
            }
            break;

        case GestureState::SCANNING:
            mScanningCounter++;
            // 簡化掃描：直接用能量信封代替 FFT（無延遲，CPU 友善）
            // 累積能量作為 "profile"（實際上就是信號強度）
            mCurrentProfile[0] = currentSampleEnergy;  // 累積能量樣本
            
            // 快速鎖定：一旦掃描完成或手移開就立即鎖定
            if (mScanningCounter >= mScanDurationSamples) {
                mLockedProfile[0] = currentSampleEnergy; // 記錄最終能量
                mState = GestureState::LOCKED;
            }
            
            // 提前退出：如果能量驟降，可能手已移開 (0.25s)
            if (currentSampleEnergy < mTotalEnergy * 1.3f && mScanningCounter > (mScanDurationSamples / 2)) {
                 mLockedProfile[0] = currentSampleEnergy;
                 mState = GestureState::LOCKED;
            }
            break;

        case GestureState::LOCKED:
            // 鎖定狀態保持，直到 reset() 被呼叫
            // 這允許用戶手勢的增益在被 reset() 前持續有效
            break;
    }

    return mState;
}
