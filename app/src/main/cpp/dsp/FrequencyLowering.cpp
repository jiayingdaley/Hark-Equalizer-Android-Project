#include "FrequencyLowering.h"

#include <algorithm>
#include <cmath>
#include <utility>

namespace {
constexpr float kPi = 3.14159265358979323846f;
}

FrequencyLowering::FrequencyLowering()
    : mSampleRate(48000.0), mCutoffHz(4500.0f), mRatio(2.0f),
      mInPos(0), mQueuePos(0) {
    // 週期性 Hann：w[n] = 0.5 - 0.5 cos(2πn/N)，50% overlap 之窗重疊和 = 1.0
    for (int n = 0; n < kFftSize; ++n) {
        mWindow[n] = 0.5f - 0.5f * std::cos(2.0f * kPi * n / kFftSize);
    }
    reset();
    rebuildMapping();
}

void FrequencyLowering::setSampleRate(double sampleRate) {
    mSampleRate = sampleRate;
    rebuildMapping();
}

void FrequencyLowering::setParameters(float cutoffHz, float ratio) {
    mCutoffHz = cutoffHz;
    mRatio = ratio < 1.0f ? 1.0f : ratio;   // <1 無意義，夾為線性
    rebuildMapping();
}

void FrequencyLowering::reset() {
    mInHop.fill(0.0f);
    mHist.fill(0.0f);
    mOutAccum.fill(0.0f);
    mOutQueue.fill(0.0f);
    mInPos = 0;
    mQueuePos = 0;
}

void FrequencyLowering::rebuildMapping() {
    const float binHz = static_cast<float>(mSampleRate) / kFftSize;
    const float nyquist = static_cast<float>(mSampleRate) * 0.5f;
    for (int ko = 0; ko < kBins; ++ko) {
        const float fo = ko * binHz;
        if (fo <= mCutoffHz) {
            mSrcBin[ko] = ko;                                // 低頻原樣
        } else {
            const float fs = mCutoffHz + mRatio * (fo - mCutoffHz);
            if (fs > nyquist) {
                mSrcBin[ko] = -1;                            // 超出，清零
            } else {
                int ks = static_cast<int>(fs / binHz + 0.5f);
                mSrcBin[ko] = (ks >= 0 && ks < kBins) ? ks : -1;
            }
        }
    }
}

float FrequencyLowering::process(float x) {
    // 每輸入一樣本，輸出前一音框處理好的一樣本；集滿一個 hop 便處理下一音框。
    const float out = mOutQueue[mQueuePos];
    mQueuePos++;
    mInHop[mInPos] = x;
    mInPos++;
    if (mInPos >= kHop) {
        processFrame();       // 填入下一個 hop 的 mOutQueue
        mInPos = 0;
        mQueuePos = 0;
    }
    return out;
}

void FrequencyLowering::processFrame() {
    // 歷史滑動窗：往前移 kHop，接上剛累積的一個 hop → 最近 N 樣本
    for (int i = 0; i < kFftSize - kHop; ++i) mHist[i] = mHist[i + kHop];
    for (int i = 0; i < kHop; ++i) mHist[kFftSize - kHop + i] = mInHop[i];

    for (int n = 0; n < kFftSize; ++n) {
        mRe[n] = mHist[n] * mWindow[n];
        mIm[n] = 0.0f;
    }
    fft(mRe.data(), mIm.data(), kFftSize, false);

    // 依映射搬移複數 bin，維持共軛對稱使 IFFT 為實數
    std::array<float, kFftSize> yr{};
    std::array<float, kFftSize> yi{};
    for (int ko = 0; ko < kBins; ++ko) {
        const int ks = mSrcBin[ko];
        float re = 0.0f, im = 0.0f;
        if (ks >= 0) {
            re = mRe[ks];
            im = mIm[ks];
        }
        yr[ko] = re;
        yi[ko] = im;
        if (ko > 0 && ko < kFftSize / 2) {       // 共軛鏡像
            yr[kFftSize - ko] = re;
            yi[kFftSize - ko] = -im;
        }
    }

    fft(yr.data(), yi.data(), kFftSize, true);

    // Overlap-add：累加器左移 kHop（露出的尾端清零），加入本框，輸出前 kHop 樣本。
    for (int i = 0; i < kFftSize - kHop; ++i) mOutAccum[i] = mOutAccum[i + kHop];
    for (int i = kFftSize - kHop; i < kFftSize; ++i) mOutAccum[i] = 0.0f;
    for (int n = 0; n < kFftSize; ++n) mOutAccum[n] += yr[n];
    for (int i = 0; i < kHop; ++i) mOutQueue[i] = mOutAccum[i];
}

// 就地 radix-2 Cooley–Tukey FFT（n 必為 2 的冪）。inverse=true 時輸出已 /n。
void FrequencyLowering::fft(float* re, float* im, int n, bool inverse) {
    // 位元反轉排列
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
        const float ang = 2.0f * kPi / len * (inverse ? 1.0f : -1.0f);
        const float wr = std::cos(ang);
        const float wi = std::sin(ang);
        for (int i = 0; i < n; i += len) {
            float cr = 1.0f, ci = 0.0f;
            for (int k = 0; k < len / 2; ++k) {
                const int a = i + k;
                const int b = i + k + len / 2;
                const float ur = re[a], ui = im[a];
                const float vr = re[b] * cr - im[b] * ci;
                const float vi = re[b] * ci + im[b] * cr;
                re[a] = ur + vr;
                im[a] = ui + vi;
                re[b] = ur - vr;
                im[b] = ui - vi;
                const float ncr = cr * wr - ci * wi;
                ci = cr * wi + ci * wr;
                cr = ncr;
            }
        }
    }
    if (inverse) {
        for (int i = 0; i < n; ++i) {
            re[i] /= n;
            im[i] /= n;
        }
    }
}
