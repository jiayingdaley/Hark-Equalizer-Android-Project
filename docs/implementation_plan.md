# Hark DSP 架構重構計畫 (Consumer Hearable DSP)

## 目標與核心定位

將 Hark DSP 確立為 **「Ultra-Low-Latency Consumer Hearable DSP for Situational Hearing Assistance」**（面向情境輔助的超低延遲消費級聽覺 DSP）。
我們不追求做傳統醫療助聽器的替代品，而是賦予消費型耳機強大的助聽能力。
核心原則是：**高穩定、低延遲、長時間佩戴舒適、語音清晰、不刺耳、無回授**。減少不必要的 processing，追求長久使用的自然聽感。
> The DSP architecture prioritizes low listening fatigue and perceptual stability over aggressive enhancement.

## User Review Required

> [!IMPORTANT]
> 此版本（v3 最終藍圖）已整合您的深度工程建議。確立了 **8-Band Internal Processing Filterbank with 16-Band User-Control Mapping**、**AFC 先於 Front-End Conditioning 的正確順序**、**超慢速 NR 平滑**，以及**四階段循序漸進的開發順序**。This architecture aims to provide a maintainable and incrementally deployable modern hearable DSP framework. 請做最終確認！

## Proposed Changes

我們將依據以下四大階段逐步推進重構。

### 第一階段：Filterbank-First 架構重構與 UI 解耦 (Priority 1)
這是本次重構的核心，將系統轉化為真正的助聽器架構。

#### [MODIFY] DSPConfig.h (新建) / HarkAudioEngine.h
- **UI 與 DSP 解耦**：建立 Mapping 機制。UI 維持 16-Band 控制，透過 **log-frequency interpolation 與 perceptual weighting** 映射至內部的 8-Band 增益目標（避免單純線性內插導致的高低頻感知落差）。

#### [MODIFY] NoiseSuppressor.cpp / HarkAudioEngine.cpp
- **架構轉換**：Remove the legacy serial 16-band peaking-EQ processing chain as the primary DSP architecture, replacing it with an internal multi-band filterbank processing core.
- **建立 8-Band Internal Processing Filterbank**：
  - 採用 Cascaded Linkwitz-Riley Tree 架構，以實現 **phase-coherent crossover summation** 與近似平坦的重建頻率響應。
  - **為何是 8-Band？** LR Tree 最適合 2 的冪次 (2, 4, 8, 16)。8-band 能保持完美的對稱性，避免非對稱 tree 帶來的複雜度爆炸、重建不平衡與相位難以追蹤的問題。
- **Microphone Front-End Level Conditioning**：
  - 放置於 DC Block 之後、Filterbank 之前。統一不同硬體麥克風的 operating range，確保後續 WDRC 行為一致（避免使用 "Input AGC" 稱呼以免與 Compressor 混淆）。
- **Per-band Processing**：
  - **NR (Noise Reduction)**: 嚴禁使用 hard gating 產生水底音。採用超慢平滑 (Attack: 20-80ms, Release: 300-1500ms)，避免 modulation distortion 與 speech bubbling。
  - 每個頻帶獨立執行 NR 衰減、WDRC 壓縮與 Prescription Fitting Gain，最後 Sum。
- **Band Recombination & Normalization**：
  - 在各頻段加總 (Band Summation) 之後，加入 **Band Summation Energy Compensation (Recombination Gain Compensation)** 階段，防止多頻段重組時的能量積累與 Crossover hump 導致 Clipping。

### 第二階段：引入自適應回授消除 AFC (Priority 2)
解決開放式/耳塞式耳機無可避免的漏音尖叫問題。

#### [NEW] AdaptiveFeedbackCanceller.cpp
- 實作基於 **NLMS (Normalized Least Mean Squares)** 的自適應濾波器。
- 初始參數規劃：Filter length = 64 或 128 taps, $\mu = 0.01$, leakage = 1e-5。

#### [MODIFY] HarkAudioEngine.cpp
- **正確的 AFC 迴路與絕對順序**：
  - **順序必須是：DC Block -> AFC -> Front-End Level Conditioning -> Filterbank**。
  - **原因**：前端 Level conditioning 會動態改變 Mic amplitude，若放在 AFC 之前，Feedback path gain 會不斷漂移，導致 NLMS 完全無法收斂。
  - 將 `Speaker Output` 訊號抽樣作為 Reference。
  - **Delay Alignment**：必須在 Reference path 與 Mic input path 之間加入精準的 delay alignment stage（補償 driver, acoustic, DSP 與 BT delay），確保 NLMS 收斂穩定。
  - **Double-talk Handling**：The AFC adaptation stage should include near-end speech protection / adaptation freeze to prevent divergence during user speech.

### 第三階段：淨化訊號鏈與情境模式 (Priority 3)
消除導致聲音浮動、不自然與聽覺疲勞的不穩定因素。

#### [MODIFY] HarkAudioEngine.cpp
- **移除多餘 Gain Stage**：刪除 SpeechFocus、Gesture Gain 的暴力增益，以及整體的 Makeup Boost。
- **單聲道優化 (Monaural Approach)**：放棄依賴雙聲道的 Coherence VAD，改為基於 Modulation analysis 與 Envelope tracking 的單耳語音強化。
- **實作情境模式 (Situational Modes)**：
  - 初期精簡模式數量，避免 Feature Creep，使用者不會一直切換：
  - **Ambient Mode** (日常)：減少 WDRC 壓縮比，讓環境音自然透入。
  - **Conversation Mode** (對話)：增加語音頻段的清晰度，並啟動輕度、超慢 release 的 NR。

### 第四階段：無鎖化音訊引擎與延遲管理 (Priority 4)
確保長時間穩定運行，並嚴格管控延遲。

#### [MODIFY] HarkAudioEngine.cpp
- **Lock-free 架構**：移除 `std::mutex` 的 `lock_guard`，實作 Double-buffer parameter system，實現參數的安全熱更新。
- **DSP State Transition Policy**：在切換模式或參數時，實施嚴格的 parameter interpolation 與 time smoothing (crossfade)，絕對禁止瞬間切換 Gain，避免產生 zipper noise, clicks 及聽覺疲勞。
- **DSP Fail-safe Policy**：If DSP instability, overflow, or processing deadline miss occurs, the system shall automatically: disable advanced processing stages, fall back to low-latency passthrough, and preserve audio continuity.
- **Latency Budget 定義與稽核**：
  - 目標總延遲 (Wired) < 15ms。
  - 預算範例：Input buffer (5ms) + DSP (2ms) + Crossover/AFC (1ms) + Output buffer (5ms)。
- **Real-time CPU Budget Monitoring**：DSP utilization shall remain below 50% of the audio callback budget under worst-case processing conditions (考量 8-band LR tree 的大量 biquad 計算負荷)。

## 漸進式開發執行順序 (Execution Phasing)

由於架構跨度巨大，為了防止系統崩潰與 Debug 地獄，我們將嚴格遵守以下實作順序：
1. **Step 1: 8-Band LR4 Filterbank Only** -> 建立基礎並測試 Phase, Latency, CPU 與 Recombination Normalization。
2. **Step 2: Per-Band WDRC** -> 加入核心的響度映射機制。
3. **Step 3: Noise Reduction** -> 加入超慢平滑的 NR。
4. **Step 4: Adaptive Feedback Cancellation (AFC)** -> 最後處理最複雜的 NLMS 與 Delay Alignment。

---

## Verification Plan

### Automated Tests
- **Lock-free 效能測試**：在頻繁切換參數時，監測 XRun 次數是否嚴格為 0。
- **NLMS 收斂測試**：測試 AFC 模型是否能在給定真實 Speaker-to-Mic impulse response 下快速收斂。

### Manual & Acoustic Verification
- **Conversational Interaction Test (超核心指標)**：
  - **自身語音 (Own Voice Perception)**：測試者開口說話時，驗證聲音是否自然，這是使用者最容易排斥助聽器的關鍵。
  - **場景切換**：實際在安靜房間、餐廳、捷運、戶外風噪環境下對話，確保聲音穩定不漂移。
- **Impulse Response & Swept Sine 測試**：
  - 量測 Impulse Response，觀察 Ringing、Pre-ringing 與 Group delay。
  - 透過 Swept sine 檢查分頻點交越處是否有 Dip 或相位抵消。
