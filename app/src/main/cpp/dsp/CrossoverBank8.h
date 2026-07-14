#pragma once

#include "LinkwitzRileyCrossover.h"

/**
 * CrossoverBank8 — 8 頻帶 LR4 分頻器組（含相位補償重建）
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * 為什麼需要相位補償（這是個真實踩過的坑，不是理論潔癖）
 * ─────────────────────────────────────────────────────────────────────────────
 * 單一 LR4 分頻器的 low + high 相加為「全通」——幅度完全平坦（實測 0.00 dB），
 * 但相位會隨頻率旋轉。這在兩頻帶時無害，串成樹狀之後就會出事：
 *
 *     x ──xo(1500)──┬── low ──xo(500)──┬── low ──xo(250)──→ b0, b1
 *                   │                  └── high ─xo(1000)─→ b2, b3
 *                   └── high ─xo(4500)─┬── low ──xo(2500)─→ b4, b5
 *                                      └── high ─xo(6000)─→ b6, b7
 *
 * b0+b1 帶著 250 Hz 分頻的全通相位；b2+b3 帶著 1000 Hz 分頻的全通相位。兩者
 * 相位不同，在它們的交界（500 Hz）相加時無法同調 —— 直接互相抵消。
 *
 * 未補償前的實測重建誤差（8 頻帶全部原樣相加，理想應為 0 dB）：
 *       400 Hz  −7.5 dB
 *       500 Hz  −25.6 dB   ← 母音第一共振峰正好落在這裡
 *       600 Hz  −9.5 dB
 *      4500 Hz  −8.0 dB
 * 也就是說：即使 EQ 全部設平、WDRC 全部旁通，這組濾波器組本身就會在語音最
 * 重要的頻段挖出一個 25 dB 的洞。
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * 補償方法（對稱二元樹的標準解）
 * ─────────────────────────────────────────────────────────────────────────────
 * 讓每一支在相加前都累積「完全相同」的全通相位：把對方的全通補到自己身上。
 *
 *   第三層：{b0,b1} 出自 250 分頻 → 補 AP(1000)；{b2,b3} 出自 1000 分頻 → 補 AP(250)
 *           {b4,b5} 出自 2500 分頻 → 補 AP(6000)；{b6,b7} 出自 6000 分頻 → 補 AP(2500)
 *   第二層：低支（b0–b3）補 AP(2500)·AP(6000)·AP(4500)
 *           高支（b4–b7）補 AP(250)·AP(1000)·AP(500)
 *
 * 補完之後每一支都帶著同一組全通 P = AP(250)·AP(500)·AP(1000)·AP(2500)·AP(4500)·AP(6000)，
 * 總和為 P·AP(1500)·x —— 幅度完全平坦，只剩一個整體的相位旋轉（聽覺上無害）。
 *
 * 補償濾波器施加在「各節點的加總」上而非逐頻帶，故成本只有 10 個 biquad／聲道，
 * 與頻帶數無關。
 *
 * LR4（= 兩級串接的 Butterworth）之 LP+HP 恰等於 fc 相同、Q = 1/√2 的二階全通，
 * 故補償器直接以該係數實作。
 */
class CrossoverBank8 {
public:
    static constexpr int kNumBands = 8;

    void setSampleRate(double sampleRate);

    /** 拆成 8 個頻帶（頻帶邊界見 HarkDspConfig 的 XO_* 常數）。 */
    void split(float in, float* bands);

    /**
     * 把（已各自施加增益／WDRC 的）8 個頻帶重新合成，並補償樹狀結構造成的
     * 相位失配。務必與 split() 成對使用；直接把 bands[] 加起來會產生上述凹陷。
     */
    float recombine(const float* bands);

    void reset();

private:
    /** 二階全通（RBJ），Q = 1/√2 —— 對應 LR4 分頻器 low+high 的相位。 */
    struct Allpass2 {
        double b0 = 1, b1 = 0, b2 = 0, a1 = 0, a2 = 0;
        float x1 = 0, x2 = 0, y1 = 0, y2 = 0;

        void setFrequency(double fc, double sampleRate);
        float process(float x);
        void reset() { x1 = x2 = y1 = y2 = 0; }
    };

    LinkwitzRileyCrossover mXoMid, mXoLow, mXoHigh;
    LinkwitzRileyCrossover mXoVLow, mXoLMid, mXoHMid, mXoVHi;

    // 第三層交叉補償
    Allpass2 mApLMid, mApVLow, mApVHi, mApHMid;
    // 第二層交叉補償（低支補高支的鏈，高支補低支的鏈）
    Allpass2 mApHiChain1, mApHiChain2, mApHiChain3;
    Allpass2 mApLoChain1, mApLoChain2, mApLoChain3;
};
