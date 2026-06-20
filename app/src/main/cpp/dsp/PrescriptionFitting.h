#pragma once
#include <atomic>

// UI to Internal index mappings
static const int UI_TO_INTERNAL_MAP[16] = {
    1, 1, 1, // 250, 315, 400   → Band 1 (250–500 Hz)
    2, 2, 2, // 500, 630, 800   → Band 2 (500–1000 Hz)
    3, 3,    // 1000, 1250      → Band 3 (1000–1500 Hz)
    4, 4,    // 1600, 2000      → Band 4 (1500–2500 Hz)
    5, 5, 5, // 2500, 3150, 4000→ Band 5 (2500–4500 Hz)
    6,       // 5000            → Band 6 (4500–6000 Hz)
    7, 7     // 6300, 8000      → Band 7 (> 6000 Hz)
};

/**
 * Calculates prescription targets and headroom parameters based on the 16 parametric EQ band gains.
 */
void calculatePrescriptionGains(
    const std::atomic<float>* uiGains,
    float* outBaseTargets,
    float& outMaxBoostDb,
    float& outSumBoostDb
);
