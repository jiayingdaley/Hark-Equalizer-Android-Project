#pragma once

enum class SituationalMode {
    TRANSPARENCY = 0, // 全向/透明：輕壓縮，NS關，保留真實環境音
    CONVERSATION = 1, // 人聲增強：帶通300-3400Hz，NS強，適合對話
    OUTDOOR = 2,      // 戶外防風：100Hz以下陡切，保護聽感
    CINEMA = 3,       // 影音模式：V形EQ（低高音補強），寬動態範圍，NS關（保留音樂動態，不被降噪誤判弱音為噪音地板）
    AUTO = 4          // 自動模式：根據環境特徵自動切換
};

struct WdrcParameters {
    float compThresh;
    float compRatio;
    float expThresh;
    float expRatio;
    float attackMs;
    float releaseMs;
};

struct SituationalPreset {
    WdrcParameters wdrc;
    bool noiseReductionEnabled;
};

/**
 * Returns the preset values (WDRC + Noise Reduction enable state) for a given scenario mode.
 */
SituationalPreset getPresetForMode(SituationalMode mode);
