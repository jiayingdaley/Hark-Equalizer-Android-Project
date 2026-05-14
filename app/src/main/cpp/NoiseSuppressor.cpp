#include "NoiseSuppressor.h"
#include <cmath>
#include <algorithm>

NoiseSuppressor::NoiseSuppressor(double sampleRate) 
    : mSampleRate(sampleRate), mAnalysisFilters(NUM_BANDS) {
    
    // 5 核心語音頻帶，優化 CPU 分配
    const double centerFreqs[NUM_BANDS] = {
        500, 1000, 2000, 3000, 4000
    };
    
    // 初始化風噪過濾器 (150Hz High Pass)
    mWindNoiseFilter.updateCoefficients(
        BiquadFilter::Type::HighPass, sampleRate, 150.0, 0.0, 0.7);

    for (int i = 0; i < NUM_BANDS; ++i) {
        // 使用 BandPass 濾波器真正隔離頻帶，才能精確分析該頻帶的能量
        mAnalysisFilters[i].updateCoefficients(
            BiquadFilter::Type::BandPass, sampleRate, centerFreqs[i], 0.0, 1.2);
        mSignalEnergy[i] = 0.0f;
        // Bug fix (BUG-02): Pre-initialise to typical room ambient level (-40dBFS)
        // instead of -60dBFS (0.001f). This makes the Wiener gate effective from
        // the very first second without requiring explicit calibration, while still
        // allowing calibrateNoiseFloor() to override with the actual environment.
        mNoiseFloor[i] = 0.01f;
        mBandGains[i] = 1.0f;
    }

    // 解決「吹風機聲」與「底噪過大」：參數微調
    mAlphaNoise = 0.9998f;  // 增加底噪追蹤穩定度，避免誤判語音為噪音
    mAlphaSignal = 0.95f;   // 稍微放慢能量追蹤，減少毛刺感
    mAlphaGain = 0.70f;     // 加快增益平滑（從0.90→0.70），使音量變化更快速反應
}

float NoiseSuppressor::process(float sample) {
    if (!mEnabled) return sample;

    // 1. 強制過濾風噪與低頻轟鳴
    float filteredSample = mWindNoiseFilter.process(sample);

    float totalWeight = 0.0f;
    float weightedGain = 0.0f;

    for (int i = 0; i < NUM_BANDS; ++i) {
        // 分析頻帶能量
        float bandSignal = mAnalysisFilters[i].process(filteredSample);
        float absSignal = fabsf(bandSignal);

        // 能量追蹤平滑化
        mSignalEnergy[i] = mAlphaSignal * mSignalEnergy[i] + (1.0f - mAlphaSignal) * absSignal;

        // SNR 計算 (加入 Offset 防止除以零)
        float snr = mSignalEnergy[i] / (mNoiseFloor[i] + 0.00001f);
        
        // 頻帶目標增益 (Wiener Filter)
        // suppressionFactor 調整為 2.0f：平衡噪音抑制與音量靈活性
        float suppressionFactor = 2.0f; 
        float targetGain = snr / (snr + suppressionFactor); 
        
        // 提高底限到 0.20 (約 -14dB)：保留更多訊號，避免音量過度衰減
        // 改善音量穩定性，讓話音保持可聞度
        float gainFloor = 0.20f; 
        if (targetGain < gainFloor) targetGain = gainFloor; 

        // 增益平滑：使用 header 定義的變數 mAlphaGain (0.9f)
        mBandGains[i] = mAlphaGain * mBandGains[i] + (1.0f - mAlphaGain) * targetGain;

        // 背景噪音地板追蹤：使用極慢速度 (0.9999) 防止追蹤到語音或產生調變雜訊
        if (mSignalEnergy[i] < mNoiseFloor[i] * 1.5f) {
            mNoiseFloor[i] = mAlphaNoise * mNoiseFloor[i] + (1.0f - mAlphaNoise) * mSignalEnergy[i];
        }

        // 權重分配：中頻語音段 (1k-3k) 權重更高
        float weight = (i >= 1 && i <= 3) ? 1.0f : 0.5f;
        weightedGain += mBandGains[i] * weight;
        totalWeight += weight;
    }

    float finalGain = weightedGain / totalWeight;

    // 輸出處理後的訊號
    return filteredSample * finalGain;
}

void NoiseSuppressor::calibrateNoiseFloor(const float* samples, int numSamples) {
    // Cold-start calibration (BUG-02 fix).
    // Ref: Loizou (2007) Ch.4 – noise estimate via a priori SNR.
    //
    // We run every sample through the wind-noise filter and each analysis
    // BandPass filter, accumulate per-band sum-of-squares, then compute
    // the RMS and write it directly to mNoiseFloor[].  The analysis filter
    // states are reset afterwards so the calibration block does not affect
    // ongoing processing.
    //
    // Thread-safety: caller MUST hold mDSPMutex (see HarkAudioEngine).

    if (samples == nullptr || numSamples <= 0) return;

    // Temporary accumulators
    double bandSumSq[NUM_BANDS] = {};

    // Use a temporary wind-noise filter so we don't disturb mWindNoiseFilter state
    BiquadFilter windTmp;
    windTmp.updateCoefficients(BiquadFilter::Type::HighPass, mSampleRate, 150.0, 0.0, 0.7);

    // One temporary BandPass filter per band
    BiquadFilter analysisTmp[NUM_BANDS];
    const double centerFreqs[NUM_BANDS] = {500, 1000, 2000, 3000, 4000};
    for (int i = 0; i < NUM_BANDS; ++i) {
        analysisTmp[i].updateCoefficients(
            BiquadFilter::Type::BandPass, mSampleRate, centerFreqs[i], 0.0, 1.2);
    }

    for (int n = 0; n < numSamples; ++n) {
        float filtered = windTmp.process(samples[n]);
        for (int i = 0; i < NUM_BANDS; ++i) {
            float bandSig = analysisTmp[i].process(filtered);
            bandSumSq[i] += static_cast<double>(bandSig) * bandSig;
        }
    }

    // Write RMS of each band as the new noise floor
    for (int i = 0; i < NUM_BANDS; ++i) {
        float rms = static_cast<float>(std::sqrt(bandSumSq[i] / numSamples));
        // Guard: never set noise floor to zero (would cause division-by-zero in SNR)
        mNoiseFloor[i] = std::max(rms, 1e-6f);
        // Re-initialise signal energy and gain so the first real samples
        // are compared against the freshly calibrated noise floor
        mSignalEnergy[i] = mNoiseFloor[i];
        mBandGains[i] = 1.0f;
    }
}
