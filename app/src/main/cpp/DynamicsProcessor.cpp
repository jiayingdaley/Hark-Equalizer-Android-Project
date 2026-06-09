#include "DynamicsProcessor.h"
#include <cmath>

#ifdef __ANDROID__
#include <android/log.h>
#define LOG_TAG "DynamicsProcessor"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#else
#include <cstdio>
#define LOGE(...) do { std::fprintf(stderr, __VA_ARGS__); std::fprintf(stderr, "\n"); } while(0)
#define LOGW(...) do { std::fprintf(stderr, __VA_ARGS__); std::fprintf(stderr, "\n"); } while(0)
#endif

DynamicsProcessor::DynamicsProcessor() :
        mCompressThreshold(1.0f),   // Will be set via setParameters()
        mCompressRatio(1.0f),
        mExpanderThreshold(1e-4f),  // Bug fix: MUST NOT be 0.0f (log10(0)=-inf). Default ~-80dB
        mExpanderRatio(1.0f),
        mNoiseFloorDb(-60.0f),
        mExpanderThresholdDbPreset(-50.0f),
        mAlphaNoiseUp(0.99995f),
        mAlphaNoiseDown(0.999f),
        mAttackCoeff(1.0f),
        mReleaseCoeff(1.0f),
        mCurrentGain(1.0f),
        mTargetGain(1.0f),
        mCounter(0),
        mUpdateInterval(16),
        mEnvelope(0.0f) {}

void DynamicsProcessor::setSampleRate(double sampleRate) {
    // 占位 - 参数在 setParameters 中处理
}

void DynamicsProcessor::setParameters(float compressThresholdDb, float compressRatio, 
                                      float expanderThresholdDb, float expanderRatio,
                                      float attackMs, float releaseMs, double sampleRate) {
    // 参数验证
    if (sampleRate <= 0.0) {
        LOGE("Invalid sampleRate: %.0f", sampleRate);
        return;
    }
    if (compressRatio <= 0.0f || expanderRatio <= 0.0f) {
        LOGE("Invalid ratio. compressRatio: %.2f, expanderRatio: %.2f", compressRatio, expanderRatio);
        return;
    }
    if (attackMs < 0.0f || releaseMs < 0.0f) {
        LOGW("Negative attack/release times. Clamping to 0");
        attackMs = fmaxf(0.0f, attackMs);
        releaseMs = fmaxf(0.0f, releaseMs);
    }

    mCompressThreshold = powf(10.0f, compressThresholdDb / 20.0f);
    mCompressRatio = compressRatio;
    
    // Save preset expander threshold for adaptive tracking
    mExpanderThresholdDbPreset = expanderThresholdDb;
    mExpanderThreshold = powf(10.0f, expanderThresholdDb / 20.0f);
    mExpanderRatio = expanderRatio;

    // Limiters with very fast attack time need sample-by-sample update to prevent transient leakage
    if (attackMs < 1.0f) {
        mUpdateInterval = 1;
    } else {
        mUpdateInterval = 16;
    }

    // 计算起音和释放时间的系数 (一阶低通滤波器)
    if (attackMs > 0.0f) {
        mAttackCoeff = expf(-1.0f / (sampleRate * (attackMs / 1000.0f)));
    } else {
        mAttackCoeff = 0.0f;  // 瞬时起音
    }

    if (releaseMs > 0.0f) {
        mReleaseCoeff = expf(-1.0f / (sampleRate * (releaseMs / 1000.0f)));
    } else {
        mReleaseCoeff = 0.0f;  // 瞬时释放
    }
}

float DynamicsProcessor::process(float inputSample) {
    // 1. 包络检测 (Envelope Detection)
    float inputLevel = fabsf(inputSample);

    // 比较当前信号位准和上一个包络值，决定使用 attack 还是 release
    if (inputLevel > mEnvelope) {
        // Attack: 信号变强，包络快速跟上
        mEnvelope = mAttackCoeff * mEnvelope + (1.0f - mAttackCoeff) * inputLevel;
    } else {
        // Release: 信号变弱，包络缓慢下降
        mEnvelope = mReleaseCoeff * mEnvelope + (1.0f - mReleaseCoeff) * inputLevel;
    }

    // 防止 denormal 数字导致的性能崩溃
    if (fabsf(mEnvelope) < 1.175494e-38f) {  // 最小正规数
        mEnvelope = 0.0f;
    }

    // 2. Performance Optimization: Only recompute gain every N samples
    if (mCounter++ >= mUpdateInterval) {
        mCounter = 0;
        float gain = 1.0f;

        // Calculate current envelope in dB for precise processing
        float envelopeDb = (mEnvelope > 1e-9f) ? 20.0f * log10f(mEnvelope) : -180.0f;
        
        // Asymmetric Noise Floor Tracking
        if (envelopeDb < mNoiseFloorDb) {
            mNoiseFloorDb = mAlphaNoiseDown * mNoiseFloorDb + (1.0f - mAlphaNoiseDown) * envelopeDb;
        } else {
            mNoiseFloorDb = mAlphaNoiseUp * mNoiseFloorDb + (1.0f - mAlphaNoiseUp) * envelopeDb;
        }
        if (mNoiseFloorDb > -20.0f) mNoiseFloorDb = -20.0f;
        if (mNoiseFloorDb < -80.0f) mNoiseFloorDb = -80.0f;

        // Dynamic expander threshold: preset ceiling or noise_floor + 5.0 dB
        float finalExpanderThresholdDb = mExpanderThresholdDbPreset;
        if (mExpanderThresholdDbPreset > -95.0f) {
            float adaptiveThresholdDb = mNoiseFloorDb + 5.0f;
            finalExpanderThresholdDb = (mExpanderThresholdDbPreset < adaptiveThresholdDb) 
                                       ? mExpanderThresholdDbPreset 
                                       : adaptiveThresholdDb;
        }
        mExpanderThreshold = powf(10.0f, finalExpanderThresholdDb / 20.0f);

        float compressThresholdDb = 20.0f * log10f(mCompressThreshold);
        float expanderThresholdDb = finalExpanderThresholdDb;

        if (envelopeDb > compressThresholdDb - mKneeDb) {
            // Soft-Knee Downward Compression
            float overshootDb = envelopeDb - compressThresholdDb;
            float gainReductionDb = 0.0f;

            if (overshootDb < mKneeDb) {
                // Inside the knee region: quadratic interpolation
                gainReductionDb = (1.0f - 1.0f / mCompressRatio) * 
                                  (overshootDb + mKneeDb) * (overshootDb + mKneeDb) / (4.0f * mKneeDb);
            } else {
                // Above the knee: linear compression
                gainReductionDb = overshootDb * (1.0f - (1.0f / mCompressRatio));
            }
            gain = powf(10.0f, -gainReductionDb / 20.0f);
        } else if (envelopeDb < expanderThresholdDb) {
            // Downward Expansion (Noise Gate)
            float undershootDb = expanderThresholdDb - envelopeDb;
            float gainReductionDb = undershootDb * (1.0f / mExpanderRatio - 1.0f);
            gain = powf(10.0f, -gainReductionDb / 20.0f);
        }
        
        mTargetGain = gain;
    }

    // 3. Smooth Gain Transition
    // Faster smoothing (0.7/0.3 vs 0.9/0.1) to track signal better and avoid
    // the oscillation artefact that sounds like "waterfall" or radio noise.
    mCurrentGain = 0.7f * mCurrentGain + 0.3f * mTargetGain;

    // 4. Apply Gain
    return inputSample * mCurrentGain;
}