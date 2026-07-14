#pragma once

#include <vector>

/**
 * FrequencySmearing — 頻譜模糊（模擬耳蝸聽覺濾波器變寬）
 *
 * 感音神經性聽損除聽閾上升與響度重振外，尚有第三項病理特徵：外毛細胞受損
 * 使耳蝸的頻率選擇性下降（聽覺濾波器變寬）。其知覺後果是「聽得見但聽不懂」——
 * 音量夠大，但相鄰頻率成分互相塗抹，共振峰結構糊掉。這一層無法由任何放大或
 * 壓縮還原，正是「聽得到不等於聽得懂」的聲學根源。
 *
 * 實作依 Baer & Moore (1993) 之頻譜模糊，並參考 3D Tune-In Toolkit
 * (HAHLSimulation/BaerMooreFrequencySmearing) 之公開架構：
 *   1. STFT（Hann 窗、50% overlap）取功率譜
 *   2. 以「加寬之 roex 聽覺濾波器」對功率譜做平滑：
 *        W(g) = (1 + p·g)·exp(−p·g),  g = |f − fc| / fc,  p = 4·fc / ERB(fc)
 *        ERB(f) = 24.7·(4.37·f/1000 + 1)                    (Glasberg & Moore)
 *   3. 保留原相位、以平滑後之幅度重建、overlap-add 輸出
 *
 * 關於加寬倍數：正常耳本身已有一次聽覺濾波，若直接以 B 倍寬之濾波器捲積，
 * 等效寬度會是 sqrt(1 + B²)（兩次濾波之寬度近似平方相加）。為使「模擬後的
 * 正常耳」總等效寬度恰為 B 倍 ERB，此處施加之核寬度取 sqrt(B² − 1)·ERB。
 * broadenFactor = 1.0 時不做任何處理（無模糊）。
 */
class FrequencySmearing {
public:
    static constexpr int kFftSize = 512;
    static constexpr int kHop = kFftSize / 2;     // 50% overlap
    static constexpr int kBins = kFftSize / 2 + 1;

    FrequencySmearing();

    /** broadenFactor：聽覺濾波器加寬倍數（1.0 = 不模糊；3.0 ≈ 中重度感音神經性聽損）。 */
    void configure(double sampleRate, float broadenFactor);
    void reset();

    /** 串流：每輸入一樣本回傳一樣本（延遲 kFftSize 樣本）。 */
    float process(float x);

    bool isActive() const { return mActive; }
    static constexpr int latency() { return kFftSize; }

private:
    double mSampleRate = 48000.0;
    float mBroaden = 1.0f;
    bool mActive = false;

    std::vector<float> mWindow;                  // Hann analysis window
    std::vector<float> mSmearRow;                // 攤平之模糊矩陣 (kBins × kBins)

    std::vector<float> mInHop;
    std::vector<float> mHist;
    std::vector<float> mOutAccum;
    std::vector<float> mOutQueue;
    int mInPos = 0;
    int mQueuePos = 0;

    std::vector<float> mRe, mIm;
    std::vector<float> mPow, mPowSmeared;

    void buildSmearMatrix();
    void processFrame();
    static void fft(float* re, float* im, int n, bool inverse);
};
