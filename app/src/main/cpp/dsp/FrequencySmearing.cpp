#include "FrequencySmearing.h"

#include <algorithm>
#include <cmath>

namespace {
    constexpr float kPi = 3.14159265358979323846f;

    /** Glasberg & Moore (1990) 等效矩形頻寬（Hz）。 */
    inline float erbHz(float fHz) {
        return 24.7f * (4.37f * fHz / 1000.0f + 1.0f);
    }
}

FrequencySmearing::FrequencySmearing()
        : mWindow(kFftSize, 0.0f),
          mInHop(kHop, 0.0f),
          mHist(kFftSize, 0.0f),
          mOutAccum(kFftSize, 0.0f),
          mOutQueue(kHop, 0.0f),
          mRe(kFftSize, 0.0f),
          mIm(kFftSize, 0.0f),
          mPow(kBins, 0.0f),
          mPowSmeared(kBins, 0.0f) {
    // 加權重疊相加（WOLA）：分析與合成同用 √Hann。
    // 修改幅度譜之後，若只有分析窗、無合成窗，音框邊界會出現不連續，
    // 重疊相加時相鄰音框無法同調相加，時域能量會流失（實測窄頻訊號掉 5 dB）。
    // √Hann × √Hann = Hann，而週期性 Hann 於 50% 重疊之窗和為常數 1，
    // 故此組合既能完美重建、又能以合成窗抑制邊界不連續。
    for (int i = 0; i < kFftSize; ++i) {
        const float hann = 0.5f * (1.0f - std::cos(2.0f * kPi * static_cast<float>(i) / kFftSize));
        mWindow[i] = std::sqrt(hann);
    }
}

void FrequencySmearing::configure(double sampleRate, float broadenFactor) {
    mSampleRate = sampleRate;
    mBroaden = broadenFactor;
    // 1.0 倍寬 = 正常耳，不需任何處理（也避免 sqrt(B²−1) 為 0）
    mActive = (broadenFactor > 1.01f);
    if (mActive) buildSmearMatrix();
    reset();
}

void FrequencySmearing::reset() {
    std::fill(mInHop.begin(), mInHop.end(), 0.0f);
    std::fill(mHist.begin(), mHist.end(), 0.0f);
    std::fill(mOutAccum.begin(), mOutAccum.end(), 0.0f);
    std::fill(mOutQueue.begin(), mOutQueue.end(), 0.0f);
    mInPos = 0;
    mQueuePos = 0;
}

void FrequencySmearing::buildSmearMatrix() {
    mSmearRow.assign(static_cast<size_t>(kBins) * kBins, 0.0f);

    // 正常耳自身已有一次聽覺濾波；欲使總等效寬度為 mBroaden 倍，
    // 此處施加之核寬度取 sqrt(B² − 1) 倍 ERB（寬度近似平方相加）。
    const float b2 = mBroaden * mBroaden - 1.0f;
    const float kernelWiden = std::sqrt(std::max(b2, 0.01f));

    const float binHz = static_cast<float>(mSampleRate) / kFftSize;

    for (int i = 0; i < kBins; ++i) {
        const float fc = std::max(static_cast<float>(i) * binHz, 50.0f);   // 避免 DC 附近除零
        const float erb = erbHz(fc) * kernelWiden;
        // roex(p) 濾波器：p = 4·fc / ERB
        const float p = 4.0f * fc / std::max(erb, 1.0f);

        float sum = 0.0f;
        float* row = &mSmearRow[static_cast<size_t>(i) * kBins];
        for (int j = 0; j < kBins; ++j) {
            const float fj = static_cast<float>(j) * binHz;
            const float g = std::fabs(fj - fc) / fc;         // 正規化偏移
            if (g > 2.0f) continue;                          // 核外一律 0（省算）
            const float w = (1.0f + p * g) * std::exp(-p * g);
            row[j] = w;
            sum += w;
        }
        // 功率守恆：每列正規化為 1
        if (sum > 1e-12f) {
            const float inv = 1.0f / sum;
            for (int j = 0; j < kBins; ++j) row[j] *= inv;
        } else {
            row[i] = 1.0f;
        }
    }
}

void FrequencySmearing::processFrame() {
    for (int i = 0; i < kFftSize; ++i) {
        mRe[i] = mHist[i] * mWindow[i];
        mIm[i] = 0.0f;
    }
    fft(mRe.data(), mIm.data(), kFftSize, false);

    for (int i = 0; i < kBins; ++i) {
        mPow[i] = mRe[i] * mRe[i] + mIm[i] * mIm[i];
    }

    // 以加寬之聽覺濾波器對功率譜做平滑
    float inPow = 0.0f, outPow = 0.0f;
    for (int i = 0; i < kBins; ++i) {
        const float* row = &mSmearRow[static_cast<size_t>(i) * kBins];
        float acc = 0.0f;
        for (int j = 0; j < kBins; ++j) acc += row[j] * mPow[j];
        mPowSmeared[i] = acc;
        inPow += mPow[i];
        outPow += acc;
    }

    // 逐音框能量正規化：模糊矩陣逐列正規化（每個輸出 bin 的權重和為 1），
    // 但對整段頻譜而言並非功率守恆——頻譜峰被抹平後總能量會流失。
    // 此處把音框總功率拉回輸入值。
    //
    // 這同時是生理上正確的行為：耳蝸濾波器變寬影響的是「頻率解析度」而非
    // 「響度」。位準的病理（聽閾上升、響度重振）由後級的多頻帶擴展器負責，
    // 模糊層只該改變頻譜的「形狀」。若讓它一併衰減音量，兩層的作用就混在
    // 一起，實驗也無從歸因。
    //
    // 殘餘誤差（實測，broadenFactor = 3.0）：
    //   · 寬頻訊號（白噪音、語音）：−0.4 dB —— 可忽略
    //   · 純音：−3.2 dB —— 被抹到鄰近 bin 的能量沿用該 bin 原本的（洩漏）
    //     相位，跨音框重疊相加時無法同調，時域能量因而低於頻域能量。
    //     這是「保留原相位之幅度修改」的固有性質（Baer & Moore 原法亦然）。
    //   純音之所以不必在意：本系統的純音測驗走 toneGainDb() 的閉式位準映射，
    //   根本不經過這條 STFT 路徑；會經過的只有語詞刺激，而語音（尤其是模糊
    //   效應最關鍵的擦音）頻譜稠密，屬上面那個 −0.4 dB 的情形。
    if (outPow > 1e-20f && inPow > 1e-20f) {
        const float corr = inPow / outPow;
        for (int i = 0; i < kBins; ++i) mPowSmeared[i] *= corr;
    }

    // 保留原相位、以平滑後之幅度重建
    for (int i = 0; i < kBins; ++i) {
        const float oldMag = std::sqrt(std::max(mPow[i], 1e-20f));
        const float newMag = std::sqrt(std::max(mPowSmeared[i], 0.0f));
        const float scale = newMag / oldMag;
        mRe[i] *= scale;
        mIm[i] *= scale;
        // 共軛對稱（實數訊號）
        if (i > 0 && i < kFftSize - i) {
            mRe[kFftSize - i] = mRe[i];
            mIm[kFftSize - i] = -mIm[i];
        }
    }

    fft(mRe.data(), mIm.data(), kFftSize, true);

    // 合成窗（同為 √Hann）→ 與分析窗相乘得 Hann，50% 重疊之窗和為 1
    for (int i = 0; i < kFftSize; ++i) mOutAccum[i] += mRe[i] * mWindow[i];

    for (int i = 0; i < kHop; ++i) mOutQueue[i] = mOutAccum[i];

    for (int i = 0; i < kFftSize - kHop; ++i) mOutAccum[i] = mOutAccum[i + kHop];
    for (int i = kFftSize - kHop; i < kFftSize; ++i) mOutAccum[i] = 0.0f;
}

float FrequencySmearing::process(float x) {
    if (!mActive) return x;

    mInHop[mInPos++] = x;
    const float y = mOutQueue[mQueuePos++];

    if (mInPos >= kHop) {
        // 滑入新的一個 hop
        for (int i = 0; i < kFftSize - kHop; ++i) mHist[i] = mHist[i + kHop];
        for (int i = 0; i < kHop; ++i) mHist[kFftSize - kHop + i] = mInHop[i];
        processFrame();
        mInPos = 0;
        mQueuePos = 0;
    }
    return y;
}

/** 原地基數-2 FFT（n 必須為 2 的冪次）。 */
void FrequencySmearing::fft(float* re, float* im, int n, bool inverse) {
    // 位元反轉重排
    for (int i = 1, j = 0; i < n; ++i) {
        int bit = n >> 1;
        for (; j & bit; bit >>= 1) j ^= bit;
        j ^= bit;
        if (i < j) {
            std::swap(re[i], re[j]);
            std::swap(im[i], im[j]);
        }
    }
    for (int len = 2; len <= n; len <<= 1) {
        const float ang = 2.0f * kPi / static_cast<float>(len) * (inverse ? 1.0f : -1.0f);
        const float wRe = std::cos(ang);
        const float wIm = std::sin(ang);
        for (int i = 0; i < n; i += len) {
            float curRe = 1.0f, curIm = 0.0f;
            for (int k = 0; k < len / 2; ++k) {
                const float uRe = re[i + k];
                const float uIm = im[i + k];
                const float vRe = re[i + k + len / 2] * curRe - im[i + k + len / 2] * curIm;
                const float vIm = re[i + k + len / 2] * curIm + im[i + k + len / 2] * curRe;
                re[i + k] = uRe + vRe;
                im[i + k] = uIm + vIm;
                re[i + k + len / 2] = uRe - vRe;
                im[i + k + len / 2] = uIm - vIm;
                const float nxtRe = curRe * wRe - curIm * wIm;
                curIm = curRe * wIm + curIm * wRe;
                curRe = nxtRe;
            }
        }
    }
    if (inverse) {
        const float inv = 1.0f / static_cast<float>(n);
        for (int i = 0; i < n; ++i) {
            re[i] *= inv;
            im[i] *= inv;
        }
    }
}
