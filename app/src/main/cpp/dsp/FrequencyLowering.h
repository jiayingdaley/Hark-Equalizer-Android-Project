#pragma once

#include <array>

/**
 * FrequencyLowering — 非線性頻率壓縮 (Non-linear Frequency Compression, NFC/NLFC)
 *
 * 針對高頻聽損（含華語高頻擦音/塞擦音 /s/ㄙ、/tɕʰ/ㄑ，能量落在 6–9 kHz）將
 * 截止頻率 (cutoff) 以上的頻譜往下壓縮到聽力較好的區域，cutoff 以下不動、
 * 保留母音與聲調基頻 (F0)。這是 Phonak SoundRecover 所用之方法，臨床上針對
 * 高頻輔音可聽度而設計；相較線性移頻不會破壞低頻諧波比例。
 *
 * 以串流式短時傅立葉 (STFT) 實作：analysis Hann 窗、50% overlap、hop = N/2，
 * 週期性 Hann 在 50% overlap 之窗重疊和為常數 1.0，故僅需 analysis 窗即可
 * 完美重建（未修改時）。每輸入一樣本輸出一樣本，固定延遲約一個音框（N 樣本，
 * 256 點 @ 48 kHz ≈ 5.3 ms）。
 *
 * 頻率映射（輸出頻率 fo → 來源頻率 fs）：
 *   fo ≤ fc              : fs = fo               （低頻原樣保留）
 *   fo > fc              : fs = fc + CR·(fo − fc) （高頻壓縮之影像）
 *   fs > Nyquist         : 該輸出頻段清零（能量已下移）
 * 以複數 bin 直接搬移；擦音為似噪音訊號，相位不連續之聽感影響甚小。
 */
class FrequencyLowering {
public:
    static constexpr int kFftSize = 256;
    static constexpr int kHop = kFftSize / 2;        // 50% overlap
    static constexpr int kBins = kFftSize / 2 + 1;

    FrequencyLowering();

    void setSampleRate(double sampleRate);
    /** cutoffHz：起始壓縮頻率；ratio：壓縮比 (>1)。 */
    void setParameters(float cutoffHz, float ratio);
    void reset();

    /** 串流：每輸入一樣本回傳一樣本（延遲約 kFftSize 樣本）。 */
    float process(float x);

private:
    double mSampleRate;
    float mCutoffHz;
    float mRatio;

    std::array<float, kFftSize> mWindow;             // Hann analysis window
    std::array<int, kBins> mSrcBin;                  // 預算之來源 bin 對映（−1 = 清零）

    // 串流緩衝（每實例獨立，供 L/R 兩聲道各持一份）
    std::array<float, kHop> mInHop;                  // 累積中的一個 hop 的輸入
    std::array<float, kFftSize> mHist;               // 最近 N 樣本（分析窗）
    std::array<float, kFftSize> mOutAccum;           // overlap-add 累加器
    std::array<float, kHop> mOutQueue;               // 待輸出的一個 hop
    int mInPos;                                      // 輸入 hop 寫入位置
    int mQueuePos;                                   // 輸出 queue 讀取位置

    // FFT 工作區
    std::array<float, kFftSize> mRe;
    std::array<float, kFftSize> mIm;

    void rebuildMapping();
    void processFrame();
    static void fft(float* re, float* im, int n, bool inverse);
};
