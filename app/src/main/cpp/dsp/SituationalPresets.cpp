#include "SituationalPresets.h"
#include "HarkDspConfig.h"

SituationalPreset getPresetForMode(SituationalMode mode) {
    switch (mode) {
        case SituationalMode::TRANSPARENCY:
            return { {HarkDspConfig::PRESET_TRANS_COMP_THRESH, HarkDspConfig::PRESET_TRANS_COMP_RATIO,
                       HarkDspConfig::PRESET_TRANS_EXP_THRESH, HarkDspConfig::PRESET_TRANS_EXP_RATIO,
                       HarkDspConfig::PRESET_TRANS_ATTACK_MS, HarkDspConfig::PRESET_TRANS_RELEASE_MS}, false };
        case SituationalMode::CONVERSATION:
            // High compression, expander threshold @ -40dBFS with 4:1 slope (0.25f) to silence earbud floor noise
            return { {HarkDspConfig::PRESET_CONV_COMP_THRESH, HarkDspConfig::PRESET_CONV_COMP_RATIO,
                       HarkDspConfig::PRESET_CONV_EXP_THRESH, HarkDspConfig::PRESET_CONV_EXP_RATIO,
                       HarkDspConfig::PRESET_CONV_ATTACK_MS, HarkDspConfig::PRESET_CONV_RELEASE_MS}, true };
        case SituationalMode::OUTDOOR:
            // Moderate compression, expander threshold @ -40dBFS with 4:1 slope to block low-frequency wind noise
            return { {HarkDspConfig::PRESET_OUT_COMP_THRESH, HarkDspConfig::PRESET_OUT_COMP_RATIO,
                       HarkDspConfig::PRESET_OUT_EXP_THRESH, HarkDspConfig::PRESET_OUT_EXP_RATIO,
                       HarkDspConfig::PRESET_OUT_ATTACK_MS, HarkDspConfig::PRESET_OUT_RELEASE_MS}, true };
        case SituationalMode::CINEMA:
            // Cinema mode with wider dynamic headroom, low compression.
            // NR must stay OFF: the Wiener SNR-gate tracks a noise floor assuming
            // speech-like pauses. Music has no true "silence" floor — quiet
            // passages/decays get misread as noise floor and gated out, killing
            // the melody. Same rationale as TRANSPARENCY (preserve the real signal).
            return { {HarkDspConfig::PRESET_CIN_COMP_THRESH, HarkDspConfig::PRESET_CIN_COMP_RATIO,
                       HarkDspConfig::PRESET_CIN_EXP_THRESH, HarkDspConfig::PRESET_CIN_EXP_RATIO,
                       HarkDspConfig::PRESET_CIN_ATTACK_MS, HarkDspConfig::PRESET_CIN_RELEASE_MS}, false };
        case SituationalMode::AUTO:
        default:
            // Default to transparency presets
            return { {HarkDspConfig::PRESET_TRANS_COMP_THRESH, HarkDspConfig::PRESET_TRANS_COMP_RATIO,
                       HarkDspConfig::PRESET_TRANS_EXP_THRESH, HarkDspConfig::PRESET_TRANS_EXP_RATIO,
                       HarkDspConfig::PRESET_TRANS_ATTACK_MS, HarkDspConfig::PRESET_TRANS_RELEASE_MS}, false };
    }
}
