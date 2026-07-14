#pragma once

#include "CrossoverBank8.h"
#include "FrequencySmearing.h"

#include <array>
#include <vector>

/**
 * HearingLossSimulator — 感音神經性聽損模擬器
 *
 * 目的：讓「聽力正常」的測試者體驗到聽損者的知覺缺損，藉此在其身上檢驗
 * 補償演算法的效益。若不模擬聽損，補償的對象並不存在——正常耳沒有落在
 * 聽閾以下的線索可救，A/B 對照的期望效果為零。
 *
 * ★ 訊號鏈順序（效度核心）★
 *   真實情境是：助聽器先處理聲音 → 聲音才進入受損的耳蝸。
 *   故本模擬器必須置於 DSP 補償「之後」、輸出至（正常）耳朵之前：
 *       未輔助：語音 →              [本模擬器] → 正常耳
 *       輔助：  語音 → [DSP 補償] → [本模擬器] → 正常耳
 *   反過來（模擬在補償之前）檢驗的會是「訊號還原」問題，而非助聽器問題。
 *
 * 兩層模擬（參考 3D Tune-In Toolkit / HAHLSimulation 之公開架構）：
 *
 *   Layer 1 — 頻譜模糊 FrequencySmearing（選用）
 *       耳蝸聽覺濾波器變寬 → 頻率選擇性下降 → 「聽得見但聽不懂」。
 *       此層放大與壓縮皆無法還原（Baer & Moore, 1993）。
 *
 *   Layer 2 — 多頻帶擴展器 MultibandExpander（核心）
 *       聽閾上升 + 響度重振，兩者以「同一條線性映射」一併重建：
 *
 *         設頻率 f 的模擬損失量為 HL(f)、不舒適閾為 UCL（以 dB SL 表示），
 *         把受損耳的動態範圍 [HL(f), UCL] 線性映射回正常耳的 [0, UCL]：
 *
 *             SL' = (SL − HL(f)) × UCL / (UCL − HL(f))
 *
 *         其中 SL = 訊號位準相對「該測試者本人聽閾」的感覺級（dB SL）。
 *           · SL < HL(f) → SL' < 0 → 落在聽閾以下 → 聽不見（閾值提升 ✓）
 *           · SL = UCL   → SL' = UCL → 一樣吵（響度重振 ✓）
 *
 *         增益形式（k = UCL / (UCL − HL)，即擴展比）：
 *             gain_dB = SL·(k − 1) − k·HL      （恆 ≤ 0，只衰減不放大）
 *
 *         k 恰為 WDRC 壓縮比的反函數：50 dB 損失 → 2:1 擴展。故「線性放大
 *         不足」與「WDRC 之必要性」可在同一實驗中一併檢驗。
 *
 * 位準基準：本模擬器以「測試者自身聽閾」為零點（dB SL），不需絕對聲學校正
 * （人工耳／聲級計）。thresholdsDbfs[] 由自調式快速純音測驗直接量得。
 */
class HearingLossSimulator {
public:
    static constexpr int kNumBands = 8;

    HearingLossSimulator();

    /**
     * @param sampleRate      取樣率
     * @param thresholdsDbfs  該測試者本人於 8 個 DSP 頻帶的聽閾（dBFS，負值）
     * @param lossDb          各頻帶的模擬損失量 HL(f)（dB，≥ 0）
     * @param uclDb           不舒適閾（dB SL），預設 100
     * @param broadenFactor   聽覺濾波器加寬倍數（1.0 = 不模糊）
     */
    void configure(double sampleRate,
                   const float* thresholdsDbfs,
                   const float* lossDb,
                   float uclDb,
                   float broadenFactor);

    void reset();

    /** 串流處理一個樣本。 */
    float process(float x);

    /** 總演算延遲（樣本數）：頻譜模糊 + 擴展器前瞻。 */
    int latencySamples() const {
        return (mSmearing.isActive() ? FrequencySmearing::latency() : 0) + mLookahead;
    }

    /**
     * 純音專用的閉式增益：純音為單一頻率之穩態訊號，經擴展器的作用僅是位準映射，
     * 不需跑濾波器組與封包追蹤。供「模擬聽損—純音測試」直接於音源振幅上施行。
     *
     * @return 應施加於該純音的增益（dB，≤ 0）
     */
    static float toneGainDb(float levelDbfs, float thresholdDbfs,
                            float lossDb, float uclDb);

private:
    struct Band {
        float thresholdDbfs = -100.0f;   // 測試者本人聽閾
        float lossDb = 0.0f;             // 模擬損失量
        float ratio = 1.0f;              // k = UCL / (UCL − HL)
        float envSq = 1e-12f;            // 封包追蹤（均方值，線性域）
        float gainDb = 0.0f;             // 平滑後的增益
    };

    /**
     * 擴展器前瞻時間（ms）。
     *
     * 字前的靜音把包絡壓到 −120 dB、增益掉到夾限底（−90 dB）；字一進來，增益
     * 得從那裡爬回來。若讓聲音與增益同步，字頭就落在「增益還沒爬上來」的那段
     * 裡被吃掉——聽起來像人工的漸強，而高頻子音（ㄙ、ㄒ）正好都在字頭。
     * 前瞻讓包絡「先看到」聲音、增益先就位，音訊才延後送出，字頭完整保留。
     *
     * 真實耳蝸的位準相依增益本就近乎瞬時（靜態非線性），不存在這種爬升。
     */
    static constexpr float kLookaheadMs = 15.0f;

    double mSampleRate = 48000.0;
    float mUclDb = 100.0f;
    bool mActive = false;

    // 前瞻延遲線（逐頻帶；所有頻帶延遲相同，故重建不受影響）
    int mLookahead = 0;
    int mDelayPos = 0;
    std::vector<float> mDelayBuf;

    std::array<Band, kNumBands> mBands;

    // 8 頻帶 LR4 分頻器組（與即時引擎共用同一份實作，含相位補償重建）
    CrossoverBank8 mBank;

    FrequencySmearing mSmearing;

    // 封包追蹤與增益平滑之係數
    float mEnvAttack = 0.0f, mEnvRelease = 0.0f;
    float mGainAttack = 0.0f, mGainRelease = 0.0f;
};
