#include "HearingLossSimulator.h"
#include "HarkDspConfig.h"

#include <algorithm>
#include <cmath>

namespace {
    constexpr float kFloorDb = -120.0f;
    constexpr float kMaxAttenDb = -90.0f;   // 增益下限（再低已無意義，避免非正規數）

    inline float linToDb(float x) {
        return 20.0f * std::log10(std::max(std::fabs(x), 1e-6f));
    }

    inline float dbToLin(float db) {
        return std::pow(10.0f, db / 20.0f);
    }

    /** 一階平滑係數：時間常數 ms → 每樣本係數。 */
    inline float coeff(float ms, double sampleRate) {
        if (ms <= 0.0f) return 0.0f;
        return std::exp(-1.0f / (static_cast<float>(sampleRate) * ms * 0.001f));
    }

    /** std::clamp 需要 C++17；本專案為較舊標準，自行提供。 */
    inline float clampf(float v, float lo, float hi) {
        return (v < lo) ? lo : ((v > hi) ? hi : v);
    }
}

HearingLossSimulator::HearingLossSimulator() = default;

float HearingLossSimulator::toneGainDb(float levelDbfs, float thresholdDbfs,
                                       float lossDb, float uclDb) {
    if (lossDb <= 0.01f) return 0.0f;
    const float hl = std::min(lossDb, uclDb - 5.0f);       // 避免 k 發散
    const float k = uclDb / std::max(uclDb - hl, 1.0f);
    const float sl = levelDbfs - thresholdDbfs;            // 感覺級 dB SL
    const float g = sl * (k - 1.0f) - k * hl;
    return clampf(g, kMaxAttenDb, 0.0f);
}

void HearingLossSimulator::configure(double sampleRate,
                                     const float* thresholdsDbfs,
                                     const float* lossDb,
                                     float uclDb,
                                     float broadenFactor) {
    mSampleRate = sampleRate;
    mUclDb = std::max(uclDb, 20.0f);

    bool anyLoss = false;
    for (int i = 0; i < kNumBands; ++i) {
        Band& b = mBands[i];
        b.thresholdDbfs = thresholdsDbfs[i];
        b.lossDb = std::max(0.0f, std::min(lossDb[i], mUclDb - 5.0f));
        b.ratio = mUclDb / std::max(mUclDb - b.lossDb, 1.0f);
        b.envSq = 1e-12f;
        // 初始增益必須「全關」：曾設 0 dB（全開），配上 150 ms 的慢關門，
        // 播放開頭的噪音會以全音量漏出約 150 ms 才被壓下去——一聲「啪」。
        // 開門只需 5 ms + 15 ms 前瞻，從全關起步不會吃掉聲音起頭。
        b.gainDb = kMaxAttenDb;
        if (b.lossDb > 0.01f) anyLoss = true;
    }

    // 分頻器組（與即時引擎同一份實作，含相位補償重建）
    mBank.setSampleRate(sampleRate);

    // 封包追蹤：需跟得上音節包絡（~5 ms attack），但不追individual 週期
    mEnvAttack = coeff(5.0f, sampleRate);
    mEnvRelease = coeff(50.0f, sampleRate);
    // 增益平滑：開門快、關門慢（兩個方向的時間常數各有一個實測教訓）。
    //
    // 「開門」（增益爬向 0，聲音變得可聽）必須快：舊版用 80 ms，字前靜音把增益
    // 壓到夾限底後，每個字的字頭要 200–300 ms 才爬回來——人工漸強，字頭被吃。
    // 5 ms + 15 ms 前瞻（見 kLookaheadMs）讓字頭完整保留。
    //
    // 「關門」（增益摔向 −90，聲音被切掉）必須慢：曾把兩個方向都設 5 ms，結果
    // 訊號落在模擬聽閾邊緣時（N5 + DSP 補償把噪音推到門檻附近），包絡每起伏
    // 一下增益就全關/半開地猛烈開關——雜訊閘抖動（gate chattering），實測聽起來
    // 是「拍手聲」。150 ms 的關門讓包絡短暫下沉時增益不立刻摔死，抖動消失；
    // 代價只是衰減慢一點到位，聽感上是無害的短尾巴。
    mGainAttack = coeff(150.0f, sampleRate);   // target 更負（關門）時使用
    mGainRelease = coeff(5.0f, sampleRate);    // target 上升（開門）時使用

    // 前瞻：包絡先看到聲音、增益先就位，音訊才延後送出（字頭完整保留）
    mLookahead = static_cast<int>(kLookaheadMs * 0.001f * sampleRate);
    mDelayBuf.assign(static_cast<size_t>(kNumBands) * mLookahead, 0.0f);
    mDelayPos = 0;

    mSmearing.configure(sampleRate, broadenFactor);

    mActive = anyLoss || mSmearing.isActive();
}

void HearingLossSimulator::reset() {
    for (auto& b : mBands) {
        b.envSq = 1e-12f;
        b.gainDb = kMaxAttenDb;   // 全關起步，避免開頭爆音（見 configure()）
    }
    mBank.reset();
    mSmearing.reset();
    std::fill(mDelayBuf.begin(), mDelayBuf.end(), 0.0f);
    mDelayPos = 0;
}

float HearingLossSimulator::process(float x) {
    if (!mActive) return x;

    // Layer 1：頻譜模糊（耳蝸濾波器變寬）——必須在擴展器之前，
    // 因為頻率選擇性下降發生於耳蝸濾波階段，響度重振發生於其後之轉導階段。
    float s = mSmearing.process(x);

    // Layer 2：多頻帶擴展器（聽閾上升 + 響度重振）
    float bands[kNumBands];
    mBank.split(s, bands);

    float gained[kNumBands];
    for (int i = 0; i < kNumBands; ++i) {
        Band& b = mBands[i];

        // 前瞻延遲線：包絡用「當下」的樣本，輸出用「mLookahead 個樣本之前」的。
        // 所有頻帶延遲量相同，分頻重建不受影響。
        float delayed = bands[i];
        if (mLookahead > 0) {
            float* line = &mDelayBuf[static_cast<size_t>(i) * mLookahead];
            delayed = line[mDelayPos];
            line[mDelayPos] = bands[i];
        }

        if (b.lossDb <= 0.01f) {          // 該頻帶無損失，原樣通過
            gained[i] = delayed;
            continue;
        }

        // 封包追蹤：在「線性功率域」平滑均方值，再轉 dB。
        //
        // 不可在 dB 域對「單一取樣值」平滑：週期性訊號每個週期都會過零，
        // log(≈0) 會俯衝到 −120 dB，這些深谷被釋放時間常數拖進平均值裡，
        // 估到的位準遠低於真實 RMS（低頻週期長、谷底停留更久，錯得更兇）。
        // SL 因而被系統性低估、增益過負 —— 實測 500 Hz 的 10 dB 損失曾被
        // 誤施為 41.7 dB 衰減。平滑功率再開根號才會得到正確的 RMS 位準。
        const float sq = bands[i] * bands[i];
        const float ec = (sq > b.envSq) ? mEnvAttack : mEnvRelease;
        b.envSq = sq + ec * (b.envSq - sq);
        const float envDb = 10.0f * std::log10(std::max(b.envSq, 1e-12f));

        // 目標增益：gain = SL·(k − 1) − k·HL，SL 為相對測試者自身聽閾之感覺級
        const float sl = envDb - b.thresholdDbfs;
        float target = sl * (b.ratio - 1.0f) - b.ratio * b.lossDb;
        target = clampf(target, kMaxAttenDb, 0.0f);

        // 增益平滑：開門快（保字頭）、關門慢（防雜訊閘抖動），見 configure()
        const float gc = (target < b.gainDb) ? mGainAttack : mGainRelease;
        b.gainDb = target + gc * (b.gainDb - target);

        gained[i] = delayed * dbToLin(b.gainDb);
    }
    if (mLookahead > 0) mDelayPos = (mDelayPos + 1) % mLookahead;

    // 必須用 recombine() 而非直接相加：樹狀分頻的各支相位不同，
    // 直接相加會在 500 Hz 與 4500 Hz 交界處抵消（詳見 CrossoverBank8.h）。
    return mBank.recombine(gained);
}
