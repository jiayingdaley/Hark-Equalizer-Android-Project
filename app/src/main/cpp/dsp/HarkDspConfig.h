#pragma once

namespace HarkDspConfig {

  // ── 1. 全域音訊系統 ─────────────────────────────────────
  constexpr double SAMPLE_RATE = 48000.0;
  constexpr int    CHANNEL_COUNT = 2;

  // ── 2. UI 16-Band EQ ─────────────────────────────────────
  constexpr double UI_CENTER_FREQS[16] = {
      250, 315, 400, 500, 630, 800,
      1000, 1250, 1600, 2000, 2500, 3150,
      4000, 5000, 6300, 8000
  };
  constexpr float DEFAULT_BAND_Q = 1.4f;  // Default Q for all 16 EQ bands

  // ── 3. LR4 Crossover 分頻點 (Hz) ──────────────────────────
  // 分頻樹 (7 crossovers → 8 bands):
  //   xoMid(1500) → Low < 1500 / High > 1500
  //   xoLow(500)  → VLow < 500 / LMid < 1500
  //   xoHigh(4500)→ HMid < 4500 / VHi > 4500
  //   xoVLow(250) → Band0 < 250 / Band1 250-500
  //   xoLMid(1000)→ Band2 500-1000 / Band3 1000-1500
  //   xoHMid(2500)→ Band4 1500-2500 / Band5 2500-4500
  //   xoVHi(6000) → Band6 4500-6000 / Band7 > 6000
  constexpr double XO_MID_HZ   = 1500.0;
  constexpr double XO_LOW_HZ   = 500.0;
  constexpr double XO_HIGH_HZ  = 4500.0;
  constexpr double XO_VLOW_HZ  = 250.0;
  constexpr double XO_LMID_HZ  = 1000.0;
  constexpr double XO_HMID_HZ  = 2500.0;
  constexpr double XO_VHI_HZ   = 6000.0;

  // ── 4. 處方增益 (Prescription Fitting) ────────────────────
  constexpr float PRESCRIPTION_GLOBAL_OFFSET_DB  = 8.0f;   // 全頻段基礎增益偏移
  constexpr float BAND0_COMPRESS_RATIO = 0.8f;              // Band 0 UI增益壓縮比例
  constexpr float BAND0_FIXED_BOOST_DB = 4.0f;              // Band 0 固定低頻補償

  // ── 5. 動態 Headroom ──────────────────────────────────────
  constexpr float HEADROOM_MAX_BOOST_WEIGHT = 0.40f;   // maxBoost 貢獻係數
  constexpr float HEADROOM_SUM_BOOST_WEIGHT = 0.05f;   // sumBoost 貢獻係數
  constexpr float HEADROOM_QUIET_THRESH_DB  = -45.0f;  // 低於此值 scaling = 0.0 (不套用headroom)
  constexpr float HEADROOM_LOUD_THRESH_DB   = -20.0f;  // 高於此值 scaling = 1.0 (全套用)

  // ── 6. Prescription Gain 平滑（GAIN_SMOOTH_ALPHA）─────────
  constexpr float GAIN_SMOOTH_ALPHA = 0.8f;  // 每callback平滑係數（0.8*old + 0.2*new）

  // ── 7. Input RMS 慢速追蹤 ─────────────────────────────────
  constexpr float INPUT_RMS_ALPHA  = 0.01f;   // Block-level EMA 係數（慢速追蹤）
  constexpr float INPUT_RMS_MIN    = 1e-5f;   // 防止 log10(0) 的地板值

  // ── 8. 輸出保護：transparent_clip ─────────────────────────
  constexpr float CLIP_SOFT_KNEE   = 0.90f;   // 軟削波起點振幅 (Linear)
  constexpr float CLIP_HARD_LIMIT  = 0.97f;   // 硬限幅最大振幅 (Linear)
  constexpr float CLIP_SLOPE       = 5.0f;    // 軟削波斜率（越大越陡）

  // ── 9. DC Blocker ─────────────────────────────────────────
  constexpr float DC_BLOCKER_POLE  = 0.995f;  // 一階 IIR DC Blocker 極點

  // ── 10. MPO Limiter（Output Protection）───────────────────
  constexpr float LIMITER_THRESHOLD_DB = -4.5f;   // MPO 保護上限
  constexpr float LIMITER_RATIO        = 20.0f;   // 20:1 磚牆限制比
  constexpr float LIMITER_EXP_THRESH   = -100.0f; // 限制器 Expander 閾值（停用）
  constexpr float LIMITER_EXP_RATIO    = 1.0f;    // 限制器 Expander 比率（停用）
  constexpr float LIMITER_ATTACK_MS    = 0.5f;    // 起音時間（快速保護）
  constexpr float LIMITER_RELEASE_MS   = 30.0f;   // 釋放時間

  // ── 11. 預設 WDRC 啟動參數 ────────────────────────────────
  constexpr float DEFAULT_WDRC_COMP_THRESH_DB = -20.0f;
  constexpr float DEFAULT_WDRC_COMP_RATIO     = 1.2f;
  constexpr float DEFAULT_WDRC_EXP_THRESH_DB  = -72.0f;
  constexpr float DEFAULT_WDRC_EXP_RATIO      = 0.5f;
  constexpr float DEFAULT_WDRC_ATTACK_MS      = 10.0f;
  constexpr float DEFAULT_WDRC_RELEASE_MS     = 600.0f;

  // ── 12. 8-Band WDRC 頻段特化噪音閘參數 ────────────────────
  // Band 2-5（500Hz–4500Hz，人聲核心頻段）：放寬閘控
  constexpr float WDRC_SPEECH_BANDS_EXP_THRESH_DB = -55.0f;
  constexpr float WDRC_SPEECH_BANDS_EXP_RATIO     = 0.66f;   // ~1.5:1 expansion
  // Band 0（< 250Hz）：強力壓制低頻空調噪音
  constexpr float WDRC_BAND0_EXP_THRESH_DB        = -32.0f;
  constexpr float WDRC_BAND0_EXP_RATIO            = 0.40f;   // 2.5:1
  // Band 1（250–500Hz）：中度壓制低頻人聲諧波以外的噪音
  constexpr float WDRC_BAND1_EXP_THRESH_DB        = -35.0f;
  constexpr float WDRC_BAND1_EXP_RATIO            = 0.45f;   // ~2.2:1
  // Band 7（> 6000Hz）：抑制麥克風高頻熱雜訊 (Hiss)
  constexpr float WDRC_BAND7_EXP_THRESH_DB        = -36.0f;
  constexpr float WDRC_BAND7_EXP_RATIO            = 0.40f;   // 2.5:1
  // 觸發頻段特化的 expThresh 判斷閾值
  constexpr float WDRC_SPECIALIZATION_BOUNDARY_DB = -55.0f;

  // ── 13. DynamicsProcessor 內部演算法常數 ─────────────────
  constexpr float DYNAMICS_KNEE_DB            = 2.0f;     // Soft-Knee 寬度
  constexpr int   DYNAMICS_GAIN_UPDATE_INTERVAL_NORMAL = 16; // 一般模式：每16樣本重算增益
  constexpr int   DYNAMICS_GAIN_UPDATE_INTERVAL_FAST   = 1;  // 快速限制器：逐樣本重算
  constexpr float DYNAMICS_FAST_ATTACK_THRESH_MS       = 1.0f; // < 1ms 時切換為 FAST 模式
  // Adaptive Noise Floor 追蹤係數
  constexpr float DYNAMICS_NOISE_ALPHA_UP    = 0.99995f;  // 上升（慢）
  constexpr float DYNAMICS_NOISE_ALPHA_DOWN  = 0.999f;    // 下降（較快）
  constexpr float DYNAMICS_NOISE_FLOOR_INIT  = -60.0f;    // 初始噪音地板估計
  constexpr float DYNAMICS_NOISE_FLOOR_MAX   = -20.0f;    // 噪音地板上限 (dBFS)
  constexpr float DYNAMICS_NOISE_FLOOR_MIN   = -80.0f;    // 噪音地板下限 (dBFS)
  constexpr float DYNAMICS_ADAPTIVE_DISABLE_THRESH = -95.0f; // 低於此值停用自適應閘控
  constexpr float DYNAMICS_ADAPTIVE_OFFSET_DB = 5.0f;     // 自適應閾 = noiseFloor + 5dB
  // 增益平滑（per-sample EMA）
  constexpr float DYNAMICS_GAIN_SMOOTH_CURR  = 0.7f;      // 保留舊值的比重
  constexpr float DYNAMICS_GAIN_SMOOTH_NEW   = 0.3f;      // 採用新目標的比重

  // ── 14. NoiseSuppressor（Wiener SNR Gate）─────────────────
  // 風噪高通濾波器
  constexpr double NS_WIND_FILTER_HZ = 150.0;
  constexpr double NS_WIND_FILTER_Q  = 0.7;
  // 5個分析頻帶（語音核心頻段，平衡 CPU）
  constexpr double NS_BAND_FREQS[5]  = {500, 1000, 2000, 3000, 4000};
  constexpr double NS_BAND_Q         = 1.2;
  // 初始噪音地板
  constexpr float NS_NOISE_FLOOR_INIT = 0.01f;   // ~-40dBFS
  // EMA 追蹤係數
  constexpr float NS_ALPHA_NOISE      = 0.9998f;  // 噪音地板（極慢）
  constexpr float NS_ALPHA_SIGNAL     = 0.95f;    // 訊號能量
  constexpr float NS_ALPHA_GAIN       = 0.85f;    // 增益平滑
  // SNR 動態 Wiener 參數（三段線性插值）
  constexpr float NS_SNR_LOW_THRESH   = 2.0f;   // < 2.0 = 高雜訊區
  constexpr float NS_SNR_HIGH_THRESH  = 5.0f;   // > 5.0 = 清晰語音區
  constexpr float NS_SUPPRESSION_LOW  = 4.0f;   // 高雜訊抑制因子
  constexpr float NS_SUPPRESSION_MID  = 2.0f;   // 中等抑制因子（預設）
  constexpr float NS_SUPPRESSION_HIGH = 1.0f;   // 清晰語音抑制因子（最輕）
  constexpr float NS_GAIN_FLOOR_LOW   = 0.25f;  // 高雜訊時增益底限（-12dB，原 0.05f）
  constexpr float NS_GAIN_FLOOR_MID   = 0.35f;  // 中等增益底限（-9.1dB，原 0.10f）
  constexpr float NS_GAIN_FLOOR_HIGH  = 0.50f;  // 清晰語音增益底限（-6.0dB，原 0.20f）
  // 噪音地板追蹤觸發比值（能量低於地板1.5倍才更新）
  constexpr float NS_NOISE_FLOOR_UPDATE_RATIO = 1.5f;
  // 頻帶權重（中頻語音段 1k-3k 更重要）
  constexpr float NS_SPEECH_BAND_WEIGHT  = 1.0f;  // 中間頻段 (band 1-3)
  constexpr float NS_NON_SPEECH_WEIGHT   = 0.5f;  // 兩側頻段 (band 0, 4)

  // ── 15. TransientSuppressor（脈衝噪音抑制）───────────────
  constexpr float TS_THRESHOLD_DB   = 16.0f;   // Crest Factor 觸發閾（防誤觸語音，從12dB提升）
  constexpr float TS_MIN_LEVEL_DB   = -35.0f;  // 絕對能量最低觸發門檻
  constexpr float TS_ATTENUATION_DB = -15.0f;  // 觸發時衰減量
  constexpr float TS_HOLD_MS        = 15.0f;   // 增益維持時間
  constexpr float TS_RELEASE_MS     = 50.0f;   // 釋放時間
  constexpr float TS_FAST_ENV_MS    = 1.0f;    // Fast Envelope 時間常數
  constexpr float TS_SLOW_ENV_MS    = 100.0f;  // Slow Envelope 時間常數
  constexpr float TS_ATTACK_MS      = 0.5f;    // 增益壓低 Attack 時間（防 Click）

  // ── 16. OwnVoiceDetector（自我語音 & 堵耳效應管理）──────
  constexpr float OVD_OCCLUSION_GAIN_DB    = -9.0f;   // 自我說話時低頻衰減量
  constexpr float OVD_RATIO_THRESHOLD_DB   = 15.0f;   // 低/高頻能量比閾（dB）
  constexpr float OVD_ENERGY_THRESHOLD_DB  = -35.0f;  // 啟動能量閾（dBFS）
  constexpr float OVD_ENERGY_SMOOTH_MS     = 50.0f;   // 能量平滑時間常數（ms）
  constexpr float OVD_GAIN_SMOOTH_MS       = 50.0f;   // 增益平滑時間常數（ms）
  constexpr float OVD_HOLD_MS             = 100.0f;  // 自我語音 Hold 時間（ms）

  // ── 17. SituationalPresets（場景預設）────────────────────
  // TRANSPARENCY（透明/全向模式）
  constexpr float PRESET_TRANS_COMP_THRESH = -20.0f;
  constexpr float PRESET_TRANS_COMP_RATIO  = 1.2f;
  constexpr float PRESET_TRANS_EXP_THRESH  = -60.0f;
  constexpr float PRESET_TRANS_EXP_RATIO   = 0.40f;
  constexpr float PRESET_TRANS_ATTACK_MS   = 10.0f;
  constexpr float PRESET_TRANS_RELEASE_MS  = 600.0f;
  // CONVERSATION（人聲增強模式）
  constexpr float PRESET_CONV_COMP_THRESH  = -30.0f;
  constexpr float PRESET_CONV_COMP_RATIO   = 1.5f;
  constexpr float PRESET_CONV_EXP_THRESH   = -40.0f;
  constexpr float PRESET_CONV_EXP_RATIO    = 0.25f;  // 4:1 Expansion
  constexpr float PRESET_CONV_ATTACK_MS    = 5.0f;
  constexpr float PRESET_CONV_RELEASE_MS   = 200.0f;
  // OUTDOOR（戶外防風模式）
  constexpr float PRESET_OUT_COMP_THRESH   = -25.0f;
  constexpr float PRESET_OUT_COMP_RATIO    = 1.3f;
  constexpr float PRESET_OUT_EXP_THRESH    = -40.0f;
  constexpr float PRESET_OUT_EXP_RATIO     = 0.25f;
  constexpr float PRESET_OUT_ATTACK_MS     = 5.0f;
  constexpr float PRESET_OUT_RELEASE_MS    = 200.0f;
  // CINEMA（影音模式）
  constexpr float PRESET_CIN_COMP_THRESH   = -15.0f;
  constexpr float PRESET_CIN_COMP_RATIO    = 1.1f;
  constexpr float PRESET_CIN_EXP_THRESH    = -65.0f;
  constexpr float PRESET_CIN_EXP_RATIO     = 0.40f;
  constexpr float PRESET_CIN_ATTACK_MS     = 20.0f;
  constexpr float PRESET_CIN_RELEASE_MS    = 600.0f;

  // ── 18. GestureDetector（手勢偵測）──────────────────────
  constexpr float GESTURE_ENERGY_EMA_COEFF     = 0.98f;   // 能量追蹤 EMA 係數
  constexpr float GESTURE_TRIGGER_RATIO        = 2.0f;    // 觸發：瞬間能量 > 均值×2
  constexpr float GESTURE_TRIGGER_MIN_LEVEL    = 0.03f;   // 絕對最小觸發能量（防靜音誤觸）
  constexpr float GESTURE_SCAN_DURATION_SEC    = 0.5f;    // 掃描持續時間（秒）
  constexpr float GESTURE_EARLY_EXIT_RATIO     = 1.3f;    // 提前退出：能量跌至均值×1.3以下
  constexpr float GESTURE_EARLY_EXIT_MIN_FRAC  = 0.5f;    // 提前退出最短比例（須超過掃描50%）

} // namespace HarkDspConfig
