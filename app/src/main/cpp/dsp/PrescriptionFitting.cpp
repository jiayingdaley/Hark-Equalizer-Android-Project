#include "PrescriptionFitting.h"
#include "HarkDspConfig.h"
#include <cmath>
#include <algorithm>

void calculatePrescriptionGains(
    const std::atomic<float>* uiGains,
    float* outBaseTargets,
    float& outMaxBoostDb,
    float& outSumBoostDb
) {
    float gainSum[8] = {};
    int count[8] = {};
    
    float maxBoost = 0.0f;
    float sumBoost = 0.0f;
    
    for (int i = 0; i < 16; ++i) {
        float db = uiGains[i].load(std::memory_order_relaxed);
        int b = UI_TO_INTERNAL_MAP[i];
        gainSum[b] += db;
        count[b]++;
        
        if (db > maxBoost) maxBoost = db;
        if (db > 0.0f) sumBoost += db;
    }
    
    outMaxBoostDb = maxBoost;
    outSumBoostDb = sumBoost;
    
    for (int b = 0; b < 8; ++b) {
        float avgDb = (count[b] > 0) ? gainSum[b] / static_cast<float>(count[b]) : 0.0f;
        float globalGainOffsetDb = HarkDspConfig::PRESCRIPTION_GLOBAL_OFFSET_DB;
        outBaseTargets[b] = powf(10.0f, (avgDb + globalGainOffsetDb) / 20.0f);
    }
    
    // Band 0 gets a specialized target rule: 80% compression offset + 4dB boost
    float firstBandDb = uiGains[0].load(std::memory_order_relaxed);
    outBaseTargets[0] = powf(10.0f, (firstBandDb * HarkDspConfig::BAND0_COMPRESS_RATIO + HarkDspConfig::BAND0_FIXED_BOOST_DB) / 20.0f);
}
