# DSP 設計參數說明

此文件彙整專案中使用或建議的 DSP 設計參數，包含壓縮（WDRC）、擴展器、Attack/Release 時間、MPO/limiter 類型、信號鏈 (signal chain) 與驗證步驟。目標：提供工程可重現的參數表、預設值、可調範圍以及測試方法。

---

## 1. 總覽（Signal Chain）

建議的預設信號鏈（自輸入到輸出）：

1. Input Gain / Pre-gain
2. 16-band EQ（FilterBank）
3. Expander / Noise Gate
4. WDRC (Wide Dynamic Range Compression)
5. Makeup Gain
6. MPO Limiter（硬限制/brickwall 或 具有 look-ahead 的 limiter）
7. Soft Clipping / Output Protection

執行環境假設：採樣率 48 kHz（支援 16/32/48kHz），處理區塊大小視 Oboe 設定而定（例如 64 / 128 frames），以低延遲為優先。

---

## 2. 參數命名與單位約定

- Level / Threshold: dBFS 或 dB（相對全尺度）；程式內統一以 dB 為單位表示
- Ratio: 壓縮或擴展比率（例如 2:1、10:1）
- Attack / Release: 毫秒（ms）
- Knee: 硬 knee 或 軟 knee（半寬度以 dB 表示）
- Look-ahead: 毫秒（ms）

---

## 3. Expander / Noise Gate

- 目的：在低電平時降低噪音地板，或作為 downward expander 去除背景噪音
- 預設參數（來自現有實作建議）:
  - Threshold: -70 dB
  - Ratio: 1.1:1 (downward expansion; 輕微擴展) — 可設定為 1.5:1~4:1 以強化效果
  - Attack: 5 ms
  - Release: 100 ms
  - Knee: 硬 knee（可選軟 knee 1~6 dB）

註：若目標為聲音事件保留（例如語音），請使用較短的 attack（<5ms）與中等 release（>50ms）。

---

## 4. WDRC（多段或單段壓縮）

設計要點：
- Detector type: RMS vs Peak（建議：語音主導情境採用 RMS 檢測，time constant 對應到攻釋時間）
- Knee: 軟 knee 可使過渡平滑；硬 knee 動態更可預測
- Make-up Gain: 壓縮後補償，需注意避免推上限位器觸發

預設（從現有程式碼及工程建議彙整）：

- Mode: Single-band WDRC（可擴展為 multi-band）
- Compression Threshold (CT): -40 dB
- Compression Ratio (CR): 2:1
- Soft Knee Width: 3 dB（可設定為 0 表示硬 knee）
- Attack Time: 10 ms
- Release Time: 80 ms
- Detector: RMS (window 10-30 ms)；備選：Peak
- Look-ahead: 0 ms（若實作 look-ahead，建議 1-5 ms 以降低瞬態過度壓縮）

建議範圍：
- CT: -60 dB ～ -10 dB
- CR: 1.2:1 ～ 8:1（語音常用 1.5~3）
- Attack: 0.5 ms ～ 50 ms
- Release: 20 ms ～ 500 ms

壓縮曲線（示意）:

- 當輸入 level < CT：增益不變
- 當輸入 level >= CT：輸出增益按 ratio 降低

公式（線性 dB 表示）:

如果輸入為 L_in (dB)，輸出 L_out 為：

$$
L_{out} = CT + \frac{L_{in} - CT}{CR}
$$

軟 knee 可在 CT ± (kneeWidth/2) 內做平滑過渡（使用二次插值或常見的平滑函數）。

---

## 5. MPO Limiter（最大輸出限制器）

目的：限制最終輸出不超過系統允許的最大輸出（避免失真或傷害聽力裝置）。

常見類型：
- Brickwall limiter（硬限制）: 緊急保護，ratio >= 50:1
- Look-ahead soft limiter: 使用緩衝預測瞬態，attack 極短，較平滑
- Soft knee limiter: 較平滑的壓縮到最大輸出點

預設（程式碼/專案建議）：
- Threshold: -3 dBFS（相對於 full-scale，表示在接近滿幅時開始限制）
- Ratio: 20:1（若需要更強保護，使用 50:1 或 brickwall）
- Attack: 0.5 ms
- Release: 30 ms
- Look-ahead: 建議 0.5 ~ 2 ms（若系統延遲允許）

MPO 與系統級別：MPO 可對應到具體 SPL 目標（需透過校定），此文件內以 dBFS 為基準，最終對應值須由硬體校正流程決定。

---

## 6. Soft-Clipping / Output Protection

- 在 limiter 之後再加入一級 soft-clipping，可用於更平滑地處理小量過衝，防止突發失真。
- 參數：threshold = -0.5 dBFS; curvature = 0.2（0~1 控制軟化程度）。

---

## 7. 檢測器與時間常數建議

- RMS 檢測時間（integration window）：10 ~ 30 ms（語音）
- Peak 檢測器：短 attack (<1 ms)，用於 limiter 或瞬態偵測
- Attack 與 Release 的設計應同時考量聽感與技術指標（避免 pumping 與聽覺不適）

範例時序設計（語音優化）：
- Expander attack 5 ms / release 100 ms
- Compressor attack 10 ms / release 80 ms
- Limiter attack 0.5 ms / release 30 ms

---

## 8. 多段壓縮（若採用）

- 每一頻段應獨立設定 Threshold、Ratio、Attack/Release、Knee
- 建議頻段劃分：低頻(<500Hz)、中低(500-1500Hz)、中高(1500-4000Hz)、高頻(>4000Hz)
- 交叉濾波使用相位考量（最小相位或相位校正）

---

## 9. 預設值匯總表

| 模組 | 參數 | 預設值 | 可調範圍 | 備註 |
|------|------:|:------:|:--------:|------|
| Expander | Threshold | -70 dB | -90 ~ -40 dB | downward expander |
| Expander | Ratio | 1.1:1 | 1.0:1 ~ 4:1 | 輕微至強烈 |
| Expander | Attack | 5 ms | 0.5 ~ 50 ms | |
| Expander | Release | 100 ms | 20 ~ 500 ms | |
| WDRC | Threshold (CT) | -40 dB | -60 ~ -10 dB | 可依語音/佔位調整 |
| WDRC | Ratio | 2:1 | 1.2:1 ~ 8:1 | 1 = bypass |
| WDRC | Attack | 10 ms | 0.5 ~ 50 ms | RMS detector 建議 10~30 ms window |
| WDRC | Release | 80 ms | 20 ~ 500 ms | |
| Limiter | Threshold | -3 dBFS | -6 ~ 0 dBFS | 最終保護 |
| Limiter | Ratio | 20:1 | 20:1 ~ brickwall | |
| Limiter | Attack | 0.5 ms | 0.1 ~ 5 ms | 包含 look-ahead 時可更短 |
| Makeup Gain | Gain | 0 ~ +10 dB | -10 ~ +20 dB | 小心觸發 limiter |

---

## 10. 參數暴露與 Presets

建議在 UI/設定層提供三個預設：
- Conservative: 輕微壓縮（CR 1.5:1，CT -35 dB），較長 release
- Normal: 預設值（CR 2:1，CT -40 dB）
- Aggressive: 強壓縮（CR 3~4:1，CT -45 dB），短 release

此外提供自訂模式（advanced）供工程師調整所有參數。

---

## 11. 驗證與測試流程

靜態與動態驗證要點：

1. 單元測試（DynamicsProcessor）
   - 針對正弦訊號輸入不同 dBFS 級別，量測輸出 level 與 gain reduction
   - 檢驗公式符合預期（輸入 > CT 時輸出符合 CR）

2. 功能測試（整體 signal chain）
   - 使用 sweep/pink noise，紀錄輸出頻譜、THD 與 SNR
   - 帶入脈衝訊號檢查瞬態響應（limiter 與 look-ahead 行為）

3. 實機測試（Android）
   - 在測試裝置上播放標準測試訊號（sine tones, pink noise），錄製輸出並分析
   - 紀錄 gain reduction 曲線、最大未失真輸出（headroom）、與感知失真

範例測試指令（桌面工具示意）：

```bash
# 產生 1kHz -10dBFS tone（使用 SoX）
sox -n -r 48000 tone.wav synth 5 sine 1000 vol 0.3162

# 使用 ffmpeg 播放到裝置或管道處理（視系統而定）
ffmpeg -re -i tone.wav -f alsa default
```

（Android 上可以用自製 APK 播放測試檔與記錄輸出；或用 adb 與錄音工具收集樣本）

測量指標：平均 Gain Reduction、峰值輸出、THD @1kHz、SNR、感知主觀評分。

---

## 12. 程式碼對應建議

- `DynamicsProcessor.h/.cpp`：對應 WDRC 與 limiter 參數，請加入註解記錄此文件中的預設值與單位
- `HarkAudioEngine.cpp`：在初始化階段明確由設定檔或 DataStore 載入預設值
- `EqSettingsRepository.kt`：將 Preset 存為 JSON/Datastore，供 UI 與 native 層同步

---

## 13. 附註與建議

- 若要精確對應實際輸出 SPL（耳機/助聽器），必須執行校正程序並在系統層將 dBFS 映射到 dB SPL
- 對於醫療或法規需求，MPO 與最大輸出級別需依標準執行紀錄與驗證

---

如果你同意，我會把此文件加入專案並在 `README.md` 加一個連結；接著我可以：

1. 萃取並比對現有程式碼中的實際預設值（`DynamicsProcessor`、`HarkAudioEngine`）
2. 產生單元測試範例以驗證增益還原曲線

請回覆是否直接將此文件 commit 到專案（我可協助 commit）。
