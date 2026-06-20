#include "NoiseSuppressor.h"
#include "HarkDspConfig.h"
#include <cmath>
#include <algorithm>

NoiseSuppressor::NoiseSuppressor(double sampleRate) 
: mSampleRate(sampleRate), mAnalysisFilters(NUM_BANDS) {
    
    // 初始化風噪過濾器 (150Hz High Pass)
    mWindNoiseFilter.updateCoefficients(
        BiquadFilter::Type::HighPass, sampleRate, HarkDspConfig::NS_WIND_FILTER_HZ, 0.0, HarkDspConfig::NS_WIND_FILTER_Q);

    for (int i = 0; i < NUM_BANDS; ++i) {
        // 使用 BandPass 濾波器真正隔離頻帶，才能精確分析該頻帶的能量
        mAnalysisFilters[i].updateCoefficients(
            BiquadFilter::Type::BandPass, sampleRate, HarkDspConfig::NS_BAND_FREQS[i], 0.0, HarkDspConfig::NS_BAND_Q);
        mSignalEnergy[i] = 0.0f;
        // Bug fix (BUG-02): Pre-initialise to typical room ambient level (-40dBFS)
        // instead of -60dBFS (0.001f). This makes the Wiener gate effective from
        // the very first second without requiring explicit calibration, while still
        // allowing calibrateNoiseFloor() to override with the actual environment.
        mNoiseFloor[i] = HarkDspConfig::NS_NOISE_FLOOR_INIT;
        mBandGains[i] = 1.0f;
    }

    // 解決「吹風機聲」與「底噪過大」：參數微調
    mAlphaNoise = HarkDspConfig::NS_ALPHA_NOISE;  // 增加底噪追蹤穩定度，避免誤判語音為噪音
    mAlphaSignal = HarkDspConfig::NS_ALPHA_SIGNAL;   // 稍微放慢能量追蹤，減少毛刺感
    mAlphaGain = HarkDspConfig::NS_ALPHA_GAIN;     // 設為 0.85f 以平滑增益過渡，避免產生氣泡聲或音樂噪音
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
        
        // 頻帶目標增益 (動態信噪比 Wiener Filter)
        // 依據信噪比動態調整抑制因子與增益底限，保護語音的同時在安靜時強力降噪
        float suppressionFactor = HarkDspConfig::NS_SUPPRESSION_MID;
        float gainFloor = HarkDspConfig::NS_GAIN_FLOOR_MID;
        
        if (snr < HarkDspConfig::NS_SNR_LOW_THRESH) {
            // 低信噪比 (純噪音區)：增強抑制並降低底限至 -26dB
            suppressionFactor = HarkDspConfig::NS_SUPPRESSION_LOW;
            gainFloor = HarkDspConfig::NS_GAIN_FLOOR_LOW;
        } else if (snr > HarkDspConfig::NS_SNR_HIGH_THRESH) {
            // 高信噪比 (清晰語音區)：減小抑制並保留語音振幅
            suppressionFactor = HarkDspConfig::NS_SUPPRESSION_HIGH;
            gainFloor = HarkDspConfig::NS_GAIN_FLOOR_HIGH;
        } else {
            // SNR 介於 2.0 與 5.0 之間：連續線性插值以消除增益階梯突變
            float t = (snr - HarkDspConfig::NS_SNR_LOW_THRESH) / (HarkDspConfig::NS_SNR_HIGH_THRESH - HarkDspConfig::NS_SNR_LOW_THRESH);
            suppressionFactor = HarkDspConfig::NS_SUPPRESSION_LOW - (HarkDspConfig::NS_SUPPRESSION_LOW - HarkDspConfig::NS_SUPPRESSION_HIGH) * t;
            gainFloor = HarkDspConfig::NS_GAIN_FLOOR_LOW + (HarkDspConfig::NS_GAIN_FLOOR_HIGH - HarkDspConfig::NS_GAIN_FLOOR_LOW) * t;
        }
        
        float targetGain = snr / (snr + suppressionFactor);
        if (targetGain < gainFloor) targetGain = gainFloor; 
  
        // 增益平滑
        mBandGains[i] = mAlphaGain * mBandGains[i] + (1.0f - mAlphaGain) * targetGain;

        // 背景噪音地板追蹤：使用極慢速度 (0.9999) 防止追蹤到語音或產生調變雜訊
        if (mSignalEnergy[i] < mNoiseFloor[i] * HarkDspConfig::NS_NOISE_FLOOR_UPDATE_RATIO) {
            mNoiseFloor[i] = mAlphaNoise * mNoiseFloor[i] + (1.0f - mAlphaNoise) * mSignalEnergy[i];
        }

        // 權重分配：中頻語音段 (1k-3k) 權重更高
        float weight = (i >= 1 && i <= 3) ? HarkDspConfig::NS_SPEECH_BAND_WEIGHT : HarkDspConfig::NS_NON_SPEECH_WEIGHT;
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
    windTmp.updateCoefficients(BiquadFilter::Type::HighPass, mSampleRate, HarkDspConfig::NS_WIND_FILTER_HZ, 0.0, HarkDspConfig::NS_WIND_FILTER_Q);

    // One temporary BandPass filter per band
    BiquadFilter analysisTmp[NUM_BANDS];
    for (int i = 0; i < NUM_BANDS; ++i) {
        analysisTmp[i].updateCoefficients(
            BiquadFilter::Type::BandPass, mSampleRate, HarkDspConfig::NS_BAND_FREQS[i], 0.0, HarkDspConfig::NS_BAND_Q);
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
