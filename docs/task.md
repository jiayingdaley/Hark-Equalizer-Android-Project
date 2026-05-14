# Hark DSP 架構重構任務追蹤清單

本清單依據 **Consumer Hearable DSP v3 最終藍圖**，嚴格劃分開發階段，確保每一步的 Phase、Latency 與 CPU 消耗都在可控範圍內。

## Step 1: 8-Band LR4 Filterbank 基礎建設

- [ ] 移除 `HarkAudioEngine` 中舊版的 16-Band Peaking EQ 鏈。
- [ ] 實作 16-Band UI 控制與內部 8-Band 的參數 Mapping 機制。
- [ ] 實作 8-Band 對稱式 Cascaded Linkwitz-Riley Tree (LR4) 架構。
- [ ] 實作各頻段訊號切割與路由 (Routing) 機制。
- [ ] 實作 Band Recombination 加總與 Output Normalization。
- [ ] 驗證：Impulse Response 測試 (Phase-coherent 驗證) 與 Latency 稽核。

## Step 2: Per-Band 響度映射 (WDRC)

- [ ] 將原本的 4-Band WDRC 升級並套用至 8-Band 架構。
- [ ] 實作 DC Block -> Optional Input AGC 流程，統一麥克風輸入基準。
- [ ] 設定每個頻段獨立的 WDRC 參數 (Threshold, Ratio, Attack, Release)。
- [ ] 設定 MPO Limiter (Output Stage)。
- [ ] 驗證：不同環境音量下的響度曲線與 Clipping 防護。

## Step 3: 情境模式與 Noise Reduction

- [ ] 實作 Per-Band Adaptive Noise Reduction。
- [ ] 調校 NR 的超慢速平滑參數 (Attack: 5~20ms, Release: 300~1000ms)，杜絕水底音與抽吸感。
- [ ] 實作基礎的 Situational Modes: `Ambient Mode` (日常) 與 `Conversation Mode` (對話)。
- [ ] 移除舊版的 SpeechFocus 與 Gesture Boost。
- [ ] 驗證：自己語音聽感 (Own Voice Perception) 與真實場景對話測試。

## Step 4: 自適應回授消除 (AFC) 與無鎖化

- [ ] 實作基於 NLMS 的 Adaptive Feedback Canceller。
- [ ] 建立 AFC 迴路：提取 Speaker Output -> Delay Alignment -> 自 Mic Input 減去。
- [ ] 將 Audio Engine 全面改為 Lock-free Double-buffer 參數架構。
- [ ] 實作 DSP State Transition Policy (Crossfade / Parameter Smoothing)。
- [ ] 驗證：高增益下的抗嘯叫能力與長時間穩定性。
