// 驗證聽損模擬器的數學行為（獨立於 Android 編譯）
#include "HearingLossSimulator.h"
#include "CrossoverBank8.h"
#include <cstdio>
#include <cmath>
#include <vector>

static float rmsDb(const std::vector<float>& x) {
    double s = 0;
    for (float v : x) s += double(v) * v;
    return 20.0f * std::log10(std::sqrt(s / x.size()) + 1e-12);
}

int main() {
    const float UCL = 100.0f;
    int fails = 0;
    auto check = [&](const char* name, bool ok, const char* detail) {
        printf("%s %-52s %s\n", ok ? "PASS" : "FAIL", name, detail);
        if (!ok) fails++;
    };

    printf("=== 1. 純音閉式增益 toneGainDb ===\n");
    const float T = -75.0f;    // 測試者聽閾 dBFS
    const float HL = 50.0f;    // 模擬損失

    // 訊號在 SL = HL 時，輸出應恰好落在測試者原本的聽閾上（剛好聽得見）
    {
        float level = T + HL;                                   // SL = 50
        float g = HearingLossSimulator::toneGainDb(level, T, HL, UCL);
        float out = level + g;
        char buf[128];
        snprintf(buf, sizeof buf, "in=%.0f g=%.1f out=%.1f (期望 out=%.0f)", level, g, out, T);
        check("SL = HL  →  輸出 = 測試者聽閾（剛好聽得見）",
              std::fabs(out - T) < 0.5f, buf);
    }
    // 訊號在 SL = UCL 時，增益應為 0（一樣吵 → 響度重振）
    {
        float level = T + UCL;
        float g = HearingLossSimulator::toneGainDb(level, T, HL, UCL);
        char buf[128];
        snprintf(buf, sizeof buf, "g=%.2f (期望 0)", g);
        check("SL = UCL →  增益 = 0（響度重振：強音一樣吵）",
              std::fabs(g) < 0.5f, buf);
    }
    // 擴展比：50 dB 損失 → 2:1
    {
        float g1 = HearingLossSimulator::toneGainDb(T + 60, T, HL, UCL);
        float g2 = HearingLossSimulator::toneGainDb(T + 70, T, HL, UCL);
        float outDelta = (70 + g2) - (60 + g1);   // 輸入 +10 dB → 輸出變化
        char buf[128];
        snprintf(buf, sizeof buf, "輸入+10dB → 輸出+%.1fdB (期望 +20 → 2:1 擴展)", outDelta);
        check("擴展比 = UCL/(UCL−HL) = 2:1（WDRC 壓縮之反函數）",
              std::fabs(outDelta - 20.0f) < 0.5f, buf);
    }
    // SL < HL → 應大幅衰減（聽不見）
    {
        float level = T + 20;   // SL=20 < HL=50
        float g = HearingLossSimulator::toneGainDb(level, T, HL, UCL);
        float out = level + g;
        char buf[128];
        snprintf(buf, sizeof buf, "SL=20 → out=%.1f dBFS，比聽閾 %.0f 低 %.1f dB", out, T, T - out);
        check("SL < HL  →  落在聽閾以下（聽不見 = 閾值提升）",
              out < T - 20.0f, buf);
    }
    // 無損失 → 增益 0（不模擬時完全透明）
    {
        float g = HearingLossSimulator::toneGainDb(-50, T, 0.0f, UCL);
        check("HL = 0   →  增益 = 0（不模擬時完全透明）",
              std::fabs(g) < 1e-6f, "g=0");
    }

    printf("\n=== 2. 串流處理（8 頻帶擴展器）===\n");
    // S1 陡降型（Bisgaard et al., 2010）：低頻幾乎無損、高頻重損。
    // 已內插到 8 個 DSP 頻帶的代表頻率（180/354/707/1225/1936/3354/5196/7000 Hz）。
    float thr[8]  = {-70, -72, -75, -75, -74, -72, -70, -68};
    float lossS1[8] = {10, 10, 10, 11, 14, 40, 66, 70};
    float noLoss[8] = {0, 0, 0, 0, 0, 0, 0, 0};

    auto runTone = [&](float freqHz, float levelDbfs, const float* loss, float broaden) {
        HearingLossSimulator sim;
        sim.configure(48000.0, thr, loss, UCL, broaden);
        sim.reset();
        std::vector<float> out;
        const int N = 48000;                       // 1 秒
        const float amp = std::pow(10.0f, levelDbfs / 20.0f) * std::sqrt(2.0f);
        for (int i = 0; i < N; ++i) {
            float x = amp * std::sin(2.0f * 3.14159265f * freqHz * i / 48000.0f);
            float y = sim.process(x);
            if (i > N / 2) out.push_back(y);      // 跳過起始暫態
        }
        return rmsDb(out);
    };

    /** 寬頻噪音（語音的合理代理）——每個頻帶都有實質能量，不受裙擺洩漏支配。 */
    auto runNoise = [&](float levelDbfs, const float* loss) {
        HearingLossSimulator sim;
        sim.configure(48000.0, thr, loss, UCL, 1.0f);
        sim.reset();
        std::vector<float> out;
        const int N = 96000;
        const float amp = std::pow(10.0f, levelDbfs / 20.0f) * std::sqrt(3.0f);
        unsigned s = 12345;
        for (int i = 0; i < N; ++i) {
            s = s * 1103515245u + 12345u;
            float x = amp * (((s >> 16) & 0x7fff) / 16384.0f - 1.0f);
            float y = sim.process(x);
            if (i > N / 2) out.push_back(y);
        }
        return rmsDb(out);
    };

    // 不模擬 → 直通
    {
        float in = -40.0f;
        float out = runTone(1000.0f, in, noLoss, 1.0f);
        char buf[128];
        snprintf(buf, sizeof buf, "in=%.0f out=%.1f dBFS", in, out);
        check("不模擬（HL=0, 無模糊）→ 訊號直通不變", std::fabs(out - in) < 1.0f, buf);
    }
    // ★ 絕對量級檢驗（回歸測試）★
    // 串流路徑（濾波器組 + 封包追蹤）算出的衰減量，必須與閉式公式一致。
    // 只比較「高頻衰減 > 低頻衰減」的相對關係會漏掉系統性偏差：
    // 曾有一版在 dB 域對單一取樣值平滑，正弦過零時 log(≈0) 俯衝到 −120 dB，
    // 位準被嚴重低估，10 dB 的損失被誤施為 41.7 dB 的衰減——相對關係卻仍成立。
    {
        float flat20[8] = {20, 20, 20, 20, 20, 20, 20, 20};
        const float in = -40.0f;
        float out = runTone(1000.0f, in, flat20, 1.0f);
        float measured = out - in;                                  // 實際增益（負）
        float expected = HearingLossSimulator::toneGainDb(in, thr[2], 20.0f, UCL);
        char buf[160];
        snprintf(buf, sizeof buf, "串流 %.1f dB vs 閉式 %.1f dB（差 %+.1f dB）",
                 measured, expected, measured - expected);
        check("串流增益 == 閉式增益（1kHz、HL=20、−40 dBFS）",
              std::fabs(measured - expected) < 3.0f, buf);
    }

    // S1：4 kHz（HL≈55）的衰減應遠大於 500 Hz（HL=10）
    {
        float lowIn = -40.0f, hiIn = -40.0f;
        float lowOut = runTone(500.0f, lowIn, lossS1, 1.0f);
        float hiOut = runTone(4000.0f, hiIn, lossS1, 1.0f);
        float lowAtten = lowIn - lowOut;
        float hiAtten = hiIn - hiOut;
        char buf[160];
        snprintf(buf, sizeof buf, "500Hz 衰減 %.1f dB / 4kHz 衰減 %.1f dB（高頻應衰減更多）",
                 lowAtten, hiAtten);
        check("S1 陡降型：高頻衰減 >> 低頻衰減（高頻子音消失）",
              hiAtten > lowAtten + 15.0f, buf);
    }
    // 響度重振：大聲的訊號衰減比小聲的少（動態範圍窄化）
    //
    // 這裡刻意用「寬頻噪音」而非純音來量擴展律。純音經 LR4 分頻時，裙擺會把一部分
    // 能量漏進鄰近頻帶；若鄰帶的損失量小很多（陡降型正是如此），該帶只被輕微衰減的
    // 漏出能量就會蓋過重損頻帶的輸出，把量到的大小聲差距壓縮掉——這是任何多頻帶
    // 擴展器對窄頻訊號的固有限制（裙擺抑制量決定了可達到的最大衰減）。
    //
    // 實際刺激不受此限：純音走 toneGainDb() 的閉式路徑、根本不經過濾波器組；
    // 語音是寬頻訊號，每個頻帶都有實質能量，漏出量不會成為主體。
    //
    // 以平坦 40 dB 損失（k = 100/60 = 1.667）驗證：
    //   輸入相差 40 dB → 衰減量應相差 (k−1)·40 ≈ 26.7 dB
    {
        float flat40[8] = {40, 40, 40, 40, 40, 40, 40, 40};
        float softOut = runNoise(-60.0f, flat40);
        float loudOut = runNoise(-20.0f, flat40);
        float softAtten = -60.0f - softOut;
        float loudAtten = -20.0f - loudOut;
        char buf[160];
        snprintf(buf, sizeof buf, "小聲(-60) 衰減 %.1f dB / 大聲(-20) 衰減 %.1f dB（差 %.1f，期望 ≈26.7）",
                 softAtten, loudAtten, softAtten - loudAtten);
        check("響度重振：擴展律 (k−1)·ΔL（動態範圍窄化）",
              std::fabs((softAtten - loudAtten) - 26.7f) < 4.0f, buf);
    }

    // ★ 起音保留（回歸測試）★
    // 字前有靜音 → 包絡掉到 −120 dB、增益被壓到夾限底。若增益「爬升」很慢，
    // 每個字的字頭都要花數百毫秒才爬到正確增益，聽起來是人工漸強，字頭
    // （高頻子音所在）被吃掉——實測回報「第一個字沒聲音」。
    // 檢驗：靜音後突然開始的穩態音，其「前 20 ms」的位準必須已接近穩態值。
    {
        float flat30[8] = {30, 30, 30, 30, 30, 30, 30, 30};
        HearingLossSimulator sim;
        sim.configure(48000.0, thr, flat30, UCL, 1.0f);
        sim.reset();

        const int silence = 4800;               // 100 ms 前置靜音
        const int tone = 48000;                 // 1 s 音
        const float amp = std::pow(10.0f, -40.0f / 20.0f) * std::sqrt(2.0f);
        std::vector<float> onset, steady;
        for (int i = 0; i < silence + tone; ++i) {
            float x = (i < silence) ? 0.0f
                    : amp * std::sin(2.0f * 3.14159265f * 1000.0f * (i - silence) / 48000.0f);
            float y = sim.process(x);
            // 前瞻讓輸出延後 latencySamples()（離線 JNI 也是這樣補償的）：
            // 輸出的第 i 個樣本對應輸入的第 i − latency 個。
            int t = i - silence - sim.latencySamples();   // 音開始後的樣本數（已對齊）
            if (t >= 0 && t < 960) onset.push_back(y);            // 前 20 ms
            if (t > tone / 2) steady.push_back(y);                // 穩態段
        }
        float onsetDb = rmsDb(onset);
        float steadyDb = rmsDb(steady);
        char buf[160];
        snprintf(buf, sizeof buf, "字頭前 20ms %.1f dB vs 穩態 %.1f dB（差 %+.1f，應 > −6）",
                 onsetDb, steadyDb, onsetDb - steadyDb);
        check("起音保留：字頭不被增益爬升吃掉（無人工漸強）",
              onsetDb - steadyDb > -6.0f, buf);
    }

    // ★ 雜訊閘抖動（回歸測試）★
    // 訊號落在模擬聽閾邊緣時，若增益「關門」太快，包絡的每次起伏都讓增益
    // 全關/半開地猛烈開關——輸出變成一顆顆爆發（實測聽感：「拍手聲」）。
    // 檢驗：穩態噪音置於門檻邊緣，輸出以 20 ms 視窗計 RMS，視窗間的位準
    // 標準差不得過大（抖動時可達 10+ dB）。
    {
        float flat50[8] = {50, 50, 50, 50, 50, 50, 50, 50};
        HearingLossSimulator sim;
        sim.configure(48000.0, thr, flat50, UCL, 1.0f);
        sim.reset();
        // 位準 ≈ 門檻邊緣（thr + HL ± 包絡起伏）
        const float lvl = -25.0f;   // thr[2](−75) + 50 = −25
        const int N = 96000;
        const float amp = std::pow(10.0f, lvl / 20.0f) * std::sqrt(3.0f);
        unsigned rs = 777;
        std::vector<float> win;
        std::vector<double> winDb;
        for (int i = 0; i < N; ++i) {
            rs = rs * 1103515245u + 12345u;
            float x = amp * (((rs >> 16) & 0x7fff) / 16384.0f - 1.0f);
            float y = sim.process(x);
            if (i > N / 2) {
                win.push_back(y);
                if ((int)win.size() == 960) {              // 20 ms
                    winDb.push_back(rmsDb(win));
                    win.clear();
                }
            }
        }
        double mean = 0; for (double v : winDb) mean += v; mean /= winDb.size();
        double var = 0; for (double v : winDb) var += (v - mean) * (v - mean);
        double sd = std::sqrt(var / winDb.size());
        char buf[160];
        snprintf(buf, sizeof buf, "20ms 視窗位準標準差 %.2f dB（抖動時 >5）", sd);
        check("門檻邊緣的穩態噪音：無雜訊閘抖動（拍手聲）", sd < 3.0, buf);
    }

    // ★ 開頭爆音（回歸測試）★
    // 增益初始狀態必須「全關」。曾設 0 dB（全開）：訊號在門檻下方時，
    // 配上 150 ms 的慢關門，播放開頭的噪音以近全音量漏出約 150 ms 才被
    // 壓下去——每次播放起頭一聲「啪」。
    // 檢驗：門檻下方 15 dB 的噪音（穩態應被強烈衰減），reset 後前 100 ms
    // 的輸出位準不得高於穩態超過 6 dB。
    {
        float flat50[8] = {50, 50, 50, 50, 50, 50, 50, 50};
        HearingLossSimulator sim;
        sim.configure(48000.0, thr, flat50, UCL, 1.0f);
        sim.reset();
        const float lvl = -40.0f;   // 門檻邊緣（−25）下方 15 dB → 穩態近全關
        const int N = 96000;
        const float amp = std::pow(10.0f, lvl / 20.0f) * std::sqrt(3.0f);
        unsigned rs = 4242;
        std::vector<float> head, tail;
        for (int i = 0; i < N; ++i) {
            rs = rs * 1103515245u + 12345u;
            float x = amp * (((rs >> 16) & 0x7fff) / 16384.0f - 1.0f);
            float y = sim.process(x);
            if (i < 4800) head.push_back(y);          // 前 100 ms
            else if (i > N / 2) tail.push_back(y);    // 穩態
        }
        float headDb = rmsDb(head), tailDb = rmsDb(tail);
        char buf[160];
        snprintf(buf, sizeof buf, "前 100ms %.1f dB vs 穩態 %.1f dB（差 %+.1f，應 < 6）",
                 headDb, tailDb, headDb - tailDb);
        check("門檻下方噪音：播放開頭不爆音（增益全關起步）", headDb - tailDb < 6.0f, buf);
    }

    printf("\n=== 3. 濾波器組重建（相位補償）===\n");
    // 8 頻帶拆開後原樣相加，必須還原成輸入訊號。
    // 未做相位補償前，樹狀分頻的各支帶著不同的全通相位，在 500 Hz 與 4500 Hz
    // 交界處互相抵消（實測 −25.6 dB / −8.0 dB）——EQ 全平也會在母音第一共振峰
    // 挖出一個大洞。這個檢驗直接鎖住該行為。
    {
        float worst = 0.0f;
        float worstF = 0.0f;
        const float probes[] = {200, 300, 400, 500, 600, 800, 1000, 1500,
                                2500, 3500, 4500, 5000, 6000, 8000};
        for (float f : probes) {
            CrossoverBank8 bank;
            bank.setSampleRate(48000.0);
            bank.reset();
            const int N = 48000;
            double acc = 0; int n = 0; float b[8];
            for (int i = 0; i < N; ++i) {
                float x = std::sin(2.0f * 3.14159265f * f * i / 48000.0f);
                bank.split(x, b);
                float y = bank.recombine(b);
                if (i > N / 2) { acc += double(y) * y; n++; }
            }
            float db = 20.0f * std::log10(std::sqrt(acc / n) / std::sqrt(0.5) + 1e-12);
            if (std::fabs(db) > std::fabs(worst)) { worst = db; worstF = f; }
        }
        char buf[128];
        snprintf(buf, sizeof buf, "最大誤差 %+.2f dB @ %.0f Hz", worst, worstF);
        check("8 頻帶拆開後原樣相加 == 原訊號（±0.5 dB）",
              std::fabs(worst) < 0.5f, buf);
    }

    printf("\n=== 4. 頻譜模糊（Baer–Moore）===\n");
    // 實際刺激是語音（寬頻、稠密頻譜）。純音走 toneGainDb() 的閉式路徑，
    // 根本不經過 STFT，故此處以寬頻噪音（語音的合理代理）檢驗。
    auto whiteNoise = [](int n) {
        std::vector<float> v(n);
        unsigned s = 1;
        for (int i = 0; i < n; ++i) {
            s = s * 1103515245u + 12345u;
            v[i] = 0.1f * (((s >> 16) & 0x7fff) / 16384.0f - 1.0f);
        }
        return v;
    };
    {
        // 模糊層只該改變頻譜「形狀」，不該改變響度（響度由擴展器負責）
        HearingLossSimulator sim;
        sim.configure(48000.0, thr, noLoss, UCL, 3.0f);   // 只開模糊、不開損失
        sim.reset();
        auto x = whiteNoise(48000);
        std::vector<float> in, out;
        for (size_t i = 0; i < x.size(); ++i) {
            float y = sim.process(x[i]);
            if (i > x.size() / 2) { in.push_back(x[i]); out.push_back(y); }
        }
        float inDb = rmsDb(in), outDb = rmsDb(out);
        char buf[128];
        snprintf(buf, sizeof buf, "in=%.1f out=%.1f dBFS（差 %+.1f dB）", inDb, outDb, outDb - inDb);
        check("頻譜模糊：寬頻訊號（語音代理）之響度不變（±1.5 dB）",
              std::fabs(outDb - inDb) < 1.5f, buf);
    }
    {
        // 核心功能檢驗：模糊「確實把頻譜抹平了」嗎？
        // 造一個有明顯頻譜峰谷的訊號（梳狀：一個窄頻帶有能量、隔壁沒有），
        // 模糊後峰谷對比應顯著下降——這正是共振峰結構糊掉、
        // 「聽得見但聽不懂」的來源。
        const int N = 4096;
        std::vector<float> peak(N), valley(N);
        HearingLossSimulator plain, blur;
        plain.configure(48000.0, thr, noLoss, UCL, 1.0f);   // 不模糊
        blur.configure(48000.0, thr, noLoss, UCL, 4.0f);    // 模糊
        plain.reset(); blur.reset();

        // 2 kHz 純音 + 2 kHz 附近的「空隙」→ 量測 2.4 kHz（隔壁）漏進多少能量
        auto bandEnergyAt = [&](HearingLossSimulator& s, float probeHz) {
            double re = 0, im = 0;
            const int M = 48000;
            for (int i = 0; i < M; ++i) {
                float x = 0.3f * std::sin(2 * 3.14159265f * 2000.0f * i / 48000.0f);
                float y = s.process(x);
                if (i > M / 2) {
                    double ph = 2 * 3.14159265 * probeHz * i / 48000.0;
                    re += y * std::cos(ph);
                    im += y * std::sin(ph);
                }
            }
            return 20.0 * std::log10(std::sqrt(re * re + im * im) / (M / 2) + 1e-12);
        };
        float leakPlain = bandEnergyAt(plain, 2400.0f);
        float leakBlur = bandEnergyAt(blur, 2400.0f);
        char buf[160];
        snprintf(buf, sizeof buf,
                 "2kHz 純音在 2.4kHz 處的能量：不模糊 %.1f dB → 模糊 %.1f dB（上升 %.1f dB）",
                 leakPlain, leakBlur, leakBlur - leakPlain);
        check("頻譜模糊：能量確實擴散到鄰近頻率（頻率選擇性下降）",
              leakBlur > leakPlain + 6.0f, buf);
    }

    printf("\n%s (%d fail)\n", fails == 0 ? "✅ 全部通過" : "❌ 有失敗項", fails);
    return fails;
}
