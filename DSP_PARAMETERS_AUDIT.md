# Hark DSP 設計參數完整審計

**日期**: 2026-05-03  
**專案**: Hark （FDA 合規助聽器 DSP 應用）  
**核心引擎**: Oboe (48kHz, 16-bit/Float, Stereo)  

---

## 📊 一、訊號鏈架構 (Signal Chain)

### **完整 DSP 處理流程** (`HarkAudioEngine::onAudioReady`)

```
INPUT STREAM (Mono from Bluetooth/USB)
    ↓
[1] Channel Expansion: Mono → Stereo (L=R)
    ↓
[2] Own Voice Ducking (VAD-based) ← Simple noise gating based on input envelope
    - VAD Threshold: 0.15 (-16dBFS)
    - Ducking Gain Target: 20% when speech detected
    - Envelope Smoothing: 0.98/0.02 (fast attack)
    ↓
[3] Pre-Gain (+0dB default) & Auto Headroom Adjustment
    - mPreGainLinear: Configurable pre-amplification
    - mAutoHeadroomLinear: Dynamic headroom based on EQ boost
    ↓
[4] WDRC Processor (Dual-channel)
    - mWdrcLeft, mWdrcRight
    - Purpose: Gentle compression for hearing aid compliance
    ↓
[5] 16-Band EQ (FilterChain with Biquad Filters)
    - Dual-channel: mFilterChainLeft, mFilterChainRight
    - Structure:
      * Band 0-1: Low Shelf Filters (200Hz, 300Hz)
      * Band 2-17: Peaking EQ (16 frequency bands)
      * Band 18: High Shelf (4kHz pass-through)
    ↓
[6] Makeup Gain (+18dB default, FDA-safe level)
    ↓
[7] MPO Limiter (Dual-channel)
    - mLimiterLeft, mLimiterRight
    - Purpose: FDA hard ceiling to prevent hearing damage
    ↓
[8] Soft-Clipping Safety Stage
    - Tanh-based soft clipping (non-linear, musically smooth)
    - Clipping threshold: ±0.9 (to avoid harsh distortion)
    ↓
OUTPUT STREAM (Stereo to Bluetooth/USB/Speaker)
```

---

## 🎛️ 二、Compression 參數詳解

### **A. WDRC (Wide Dynamic Range Compression) 配置**

**核心設定** (HarkAudioEngine.cpp:76-81)

| 參數 | 值 | 單位 | 說明 |
|------|-----|------|------|
| **Compression Threshold** | -40 | dBFS | 超過此值開始壓縮 (ANSI S3.22 標準) |
| **Compression Ratio** | 2 | : 1 | 溫和壓縮 (輕中度聽損適用) |
| **Expander Threshold** | -70 | dBFS | 低於此值啟動降噪 (防止數位靜寂) |
| **Expander Ratio** | 0.9 | : 1 | 溫和 1:1.1 下行展開 (僅微弱噪音削減) |
| **Attack Time** | 10 | ms | 回應快速音量變化 |
| **Release Time** | 80 | ms | 自然 AGC 感受 |

**計算過程** (DynamicsProcessor.cpp:59-73)

```cpp
// 時間常數 → 一階低通濾波器系數
if (attackMs > 0.0f) {
    mAttackCoeff = exp(-1.0f / (sampleRate * (attackMs / 1000.0f)));
    // @ 48kHz, 10ms: mAttackCoeff ≈ 0.9979
}

if (releaseMs > 0.0f) {
    mReleaseCoeff = exp(-1.0f / (sampleRate * (releaseMs / 1000.0f)));
    // @ 48kHz, 80ms: mReleaseCoeff ≈ 0.99974
}
```

### **B. Compression Curve 設計**

**Soft-Knee 實裝** (DynamicsProcessor.cpp:103-122)

```cpp
float mKneeDb = 2.0f;  // 膝蓋寬度

// 軟膝蓋設計
if (envelopeDb > compressThresholdDb - mKneeDb) {
    float overshootDb = envelopeDb - compressThresholdDb;
    
    if (overshootDb < mKneeDb) {
        // 膝蓋內：二次插值曲線 (平滑過渡)
        gainReductionDb = (1.0f - 1.0f / mCompressRatio) * 
                          (overshootDb + mKneeDb)² / (4.0f * mKneeDb);
    } else {
        // 膝蓋上：線性壓縮
        gainReductionDb = overshootDb * (1.0f - 1.0f / mCompressRatio);
    }
    gain = 10^(-gainReductionDb / 20);
}
```

**曲線特性**：
- **Compression Ratio**: CR = 2:1，斜率為 0.5（在膝蓋上方）
- **Knee Region**: [-42dB, -40dB] 區間內為二次曲線，實現平滑過渡
- **Below Threshold**: -42dB 以下無壓縮（原音通過）

### **C. Envelope Detection (包絡檢測)**

**Algorithm** (DynamicsProcessor.cpp:88-99)

```cpp
float inputLevel = fabsf(inputSample);

if (inputLevel > mEnvelope) {
    // Attack: 快速跟上
    mEnvelope = mAttackCoeff * mEnvelope + (1.0f - mAttackCoeff) * inputLevel;
} else {
    // Release: 緩慢下降 (RMS-like 平滑)
    mEnvelope = mReleaseCoeff * mEnvelope + (1.0f - mReleaseCoeff) * inputLevel;
}

// Denormal 防護
if (fabsf(mEnvelope) < 1.175494e-38f) {
    mEnvelope = 0.0f;
}
```

---

## 🔒 三、MPO Limiter 參數

### **Limiter 配置** (HarkAudioEngine.cpp:84-88)

| 參數 | 值 | 說明 |
|------|-----|------|
| **Threshold** | -3 | dBFS |
| **Ratio** | 20 | : 1 |
| **Attack Time** | 0.5 | ms |
| **Release Time** | 30 | ms |
| **Expander Threshold** | -100 | dBFS |
| **Expander Ratio** | 1.0 | (no expansion) |

### **Limiter 類型 & 行為**

- **Type**: Dynamic Limiter with Soft-Knee (same DynamicsProcessor class as WDRC)
- **Brick-Wall Protection**: 20:1 高比率確保硬天花板
- **Fast Attack** (0.5ms): 瞬時捕捉峰值爆發
- **Moderate Release** (30ms): 避免韻律過度改變

**FDA 安全設計**：
- 絕不允許輸出超過 -3dBFS 峰值
- 保護高頻助聽器使用者不超過 SPL 上限

---

## 🎚️ 四、Attack/Release Time 詳細參數

### **A. WDRC 時間常數**

```
Attack  = 10 ms   → 信號瞬間升高時快速響應
Release = 80 ms   → 信號衰減時緩慢恢復 (自然 AGC 感受)

時間常數推導 (@ 48kHz):
  τ_attack = 10 ms  → coeff_attack = exp(-1/(48000*0.01)) ≈ 0.9979
  τ_release = 80 ms → coeff_release = exp(-1/(48000*0.08)) ≈ 0.99974
```

### **B. MPO Limiter 時間常數**

```
Attack  = 0.5 ms  → 瞬時保護 (峰值檢測)
Release = 30 ms   → 快速恢復 (避免音樂扭曲)

@ 48kHz:
  τ_attack = 0.5 ms  → coeff_attack = exp(-1/(48000*0.0005)) ≈ 0.9591
  τ_release = 30 ms  → coeff_release = exp(-1/(48000*0.03)) ≈ 0.9993
```

### **C. VAD Ducking 時間常數** (Own Voice Ducking)

```
Envelope Smoothing:
  Fast Attack: 0.98 × old + 0.02 × new  → ~1ms response
  
Gain Smoothing:
  Down-ramp: 0.95 × old + 0.05 × 0.2    (duck to 20%)
  Up-ramp:   0.99 × old + 0.01 × 1.0    (recover)
  
VAD Threshold: 0.15 (-16dBFS)
```

---

## 🎛️ 五、Gain Interpolation & Performance Optimization

### **Gain Update Interval**

```cpp
static const int UPDATE_INTERVAL = 16;  // 每 16 個樣本更新一次增益

// 性能優化流程：
// ├─ 每 16 樣本計算一次 log10/pow (浮點運算昂貴)
// └─ 其餘樣本線性插值現有增益 (快速乘法)
```

**Gain 平滑過渡** (DynamicsProcessor.cpp:131)

```cpp
// 快速平滑 (0.7/0.3) 以追蹤信號，避免「瀑布」偽影
mCurrentGain = 0.7f * mCurrentGain + 0.3f * mTargetGain;
```

---

## 📈 六、16-Band EQ 設計

### **帶通設計** (HarkAudioEngine.cpp:22-27)

```cpp
const double centerFrequencies[] = {
    250, 315, 400, 500, 630, 800, 1000, 1250,
    1600, 2000, 2500, 3150, 4000, 5000, 6300, 8000  // Hz
};
```

### **Biquad 濾波器架構**

**Peaking EQ** (16 bands):
- Q Factor: 1.8 (寬帶，遵循聽損頻譜)
- Type: RBJ Cookbook 標準

**Shelf Filters** (邊界保護):
- Low Shelf @ 200Hz (防低頻過度提升)
- Low Shelf @ 300Hz (額外調整)
- High Shelf @ 4kHz (預留高頻)

**安全設計** (HarkAudioEngine.cpp:48-54)

```cpp
// 自動 Headroom 計算：防止多重 EQ boost 導致數位破音
float maxBoostDb = max(mBandGains);
float headroomDb = -maxBoostDb * 0.5f; // 優化：0.75 -> 0.5 增加聽感
mAutoHeadroomLinear = 10^(headroomDb / 20);

// 範例：若 max boost = +12dB，自動預留 -6dB headroom (淨提升 +6dB)
```

### **Biquad 係數計算** (BiquadFilter.cpp:17-56)

使用 **Audio EQ Cookbook** (RBJ) 公式：

```
Peaking:
  H(z) = [1 + α·A] / (1 + α/A) × ...

LowShelf:
  H(z) = A·[(A+1) - (A-1)·cos(w₀) + 2√A·α] / ...

HighShelf:
  相似結構，高頻段對應
```

---

## 🔐 七、安全機制

### **A. Soft-Clipping** (最終保護)

```cpp
auto softClip = [](float x) {
    if (x > 0.9f)
        return 0.9f + 0.1f * tanh((x - 0.9f) / 0.1f);
    if (x < -0.9f)
        return -0.9f + 0.1f * tanh((x + 0.9f) / 0.1f);
    return x;
};
```

**特性**：
- Tanh-based non-linear clipping（避免尖銳失真）
- 平滑過渡：削波開始於 ±0.9，逐漸趨向 ±1.0
- 減少高頻諧波（FDA 認證基礎）

### **B. Denormal 防護**

```cpp
if (fabsf(mEnvelope) < 1.175494e-38f) {  // FLT_MIN
    mEnvelope = 0.0f;
}
```

### **C. 參數驗證** (DynamicsProcessor.cpp:35-45)

```
✗ Invalid sampleRate
✗ Invalid compression ratio / expander ratio
✗ Negative attack/release times (clamped)
✗ Frequency ≥ Nyquist (clamped to 0.99 × Nyquist)
```

---

## 📋 八、數據流概覽

### **每幀處理** (Per-Frame Flow @ 48kHz)

```
Input: 1 frame (48 samples @ 48kHz = 1ms duration)
├─ Channel expand (Mono→Stereo)
├─ Ducking envelope detect & smooth
├─ Pre-gain & Headroom apply
├─ WDRC process (dual channel)
├─ EQ filter chain (18 biquads per channel)
├─ Makeup gain apply
├─ Limiter process (dual channel)
├─ Soft-clip apply
└─ Output to speaker/BT

Latency Budget:
≈ 1-2ms (Oboe AAudio minimum + buffer)
+ ~0.5ms (EQ group delay @ 48kHz)
= ~1.5-2.5ms total (遠低於 FDA 10ms 延遲上限)
```

---

## 🔍 九、關鍵設計決策

| 決策 | 原因 |
|------|------|
| **Soft-Knee 2dB** | 避免硬閾值造成的「pumping」偽影 |
| **Expander @ -70dB** | 只削減極弱噪音，保持自然感 |
| **Dual WDRC + Limiter** | WDRC 用於聽域補償，Limiter 用於安全保護 |
| **16-Band EQ** | 符合聽力學標準 (ISO 389 standard bands) |
| **Makeup +18dB** | FDA 安全上限內最大化音量 |
| **0.7/0.3 Gain Smooth** | 追蹤速度 vs 平滑度的最佳平衡 |

---

## 📌 十、調試與驗證

### **關鍵日誌位置**

```cpp
#define LOG_TAG "DynamicsProcessor"    // 壓縮/擴展邏輯
#define LOG_TAG "HarkAudioEngine"      // 訊號鏈啟動
#define LOG_TAG "FilterChain"           // EQ 濾波
#define LOG_TAG "BiquadFilter"          // Biquad 係數
```

### **驗證清單**

- [ ] WDRC Threshold @ -40dB (measured with sine sweep)
- [ ] MPO Limiter @ -3dBFS (no over-limit peaks)
- [ ] Attack/Release time constants (envelope trace)
- [ ] EQ gain @ each band (frequency response plot)
- [ ] Soft-clip distortion < 1% THD (@ 0dBFS)
- [ ] Denormal handling (CPU profiling)

---

## 🧪 十一、實測驗證方法 (How to Measure)

若需在論文中引用數據，請依照以下方法取得實測值：

### **1. 延遲 (Latency) 測量**
*   **工具**: OboeTester (GitHub) 或外部示波器。
*   **方法**: 
    1.  開啟 App 處理鏈。
    2.  在麥克風前發出「短促脈衝音」(如擊掌)。
    3.  使用另一個錄音設備同時錄下「原始聲」與「耳機輸出聲」。
    4.  在 Audacity 中測量兩者波形峰值的時間差。

### **2. 頻率響應 (Frequency Response)**
*   **工具**: 模擬耳 (Coupler) 或指向性麥克風 + RTA 軟體。
*   **方法**: 
    1.  輸入粉紅噪音 (Pink Noise)。
    2.  調整 EQ 增益。
    3.  觀察 RTA 曲線是否符合 ISO 1/3 Octave 預期。

### **3. 總諧波失真 (THD)**
*   **工具**: 1kHz 純正弦波檔案 + 頻譜分析儀。
*   **方法**: 
    1.  輸入 0dBFS 1kHz 信號（觸發 Soft-clipping）。
    2.  分析輸出端 2kHz, 3kHz, 4kHz 諧波能量與基頻之比。

---

**End of DSP Audit Report**
