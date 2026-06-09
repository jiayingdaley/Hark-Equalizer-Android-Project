#include <iostream>
#include <cmath>
#include <vector>
#include <cassert>
#include <iomanip>

#include "../../app/src/main/cpp/DynamicsProcessor.h"
#include "../../app/src/main/cpp/BiquadFilter.h"
#include "../../app/src/main/cpp/LinkwitzRileyCrossover.h"

// Replicate transparent_clip from HarkAudioEngine
inline float transparent_clip(float x) {
    if (x > 0.90f) {
        float d = x - 0.90f;
        float saturated = 0.90f + d / (1.0f + d * 5.0f);
        return saturated > 0.97f ? 0.97f : saturated;
    }
    if (x < -0.90f) {
        float d = x + 0.90f;
        float saturated = -0.90f + d / (1.0f - d * 5.0f);
        return saturated < -0.97f ? -0.97f : saturated;
    }
    return x;
}

// Test Case 1: MPO Protection & Clipper Coordination Test
void testMpoLimiterClipperCoordination() {
    std::cout << "[Test 1] Starting MPO Limiter & Clipper Coordination Test..." << std::endl;
    
    double sampleRate = 48000.0;
    DynamicsProcessor limiter;
    // Set Limiter threshold to -4.5 dBFS, ratio to 20.0, very fast attack 0.5ms, release 30ms
    limiter.setParameters(-4.5f, 20.0f, -100.0f, 1.0f, 0.5f, 30.0f, sampleRate);

    // Feed a huge transient impulse (+18 dB, amplitude 4.0f)
    // We run it for enough samples for the envelope follower to detect it and compress
    std::vector<float> input(512, 4.0f);
    std::vector<float> output(512, 0.0f);

    float maxOutputAmp = 0.0f;
    for (size_t i = 0; i < input.size(); ++i) {
        float limited = limiter.process(input[i]);
        float clipped = transparent_clip(limited);
        output[i] = clipped;
        if (std::abs(clipped) > maxOutputAmp) {
            maxOutputAmp = std::abs(clipped);
        }
    }

    std::cout << "  - Input amplitude: 4.00f (+18 dB)" << std::endl;
    std::cout << "  - Max output amplitude after Limiter & Clipper: " << maxOutputAmp << "f" << std::endl;
    
    // Safety baseline check: Output must NEVER exceed 0.97f
    assert(maxOutputAmp <= 0.97f);
    
    // Make sure the limiter target gain is active and attenuating heavily
    float targetGain = limiter.getTargetGain();
    std::cout << "  - Limiter final target gain: " << targetGain << " (attenuation = " << 20.0f * std::log10(targetGain) << " dB)" << std::endl;
    assert(targetGain < 0.25f); // Must attenuate by more than -12dB for a 4.0f input

    std::cout << "[Test 1] PASSED." << std::endl << std::endl;
}

// Test Case 2: Linkwitz-Riley 8-Band Tolerance Test
void testLinkwitzRileyCrossover() {
    std::cout << "[Test 2] Starting Linkwitz-Riley 8-Band Crossover Unity Gain Test..." << std::endl;
    
    double sampleRate = 48000.0;
    
    // Define crossovers matching the engine configuration
    LinkwitzRileyCrossover mXoverMid, mXoverLow, mXoverHigh, mXoverVLow, mXoverLMid, mXoverHMid, mXoverVHi;
    const double xoFreqs[7] = { 1500.0, 500.0, 4500.0, 250.0, 1000.0, 2500.0, 6000.0 };
    
    mXoverMid.setFrequency(xoFreqs[0], sampleRate);
    mXoverLow.setFrequency(xoFreqs[1], sampleRate);
    mXoverHigh.setFrequency(xoFreqs[2], sampleRate);
    mXoverVLow.setFrequency(xoFreqs[3], sampleRate);
    mXoverLMid.setFrequency(xoFreqs[4], sampleRate);
    mXoverHMid.setFrequency(xoFreqs[5], sampleRate);
    mXoverVHi.setFrequency(xoFreqs[6], sampleRate);

    // Feed a Dirac delta pulse input (1.0f followed by zeros)
    // We verify unity energy reconstruction. Note: crossover filters have phase delay,
    // so we verify sample-by-sample sum against original input.
    std::vector<float> input(1024, 0.0f);
    input[0] = 1.0f;
    
    std::vector<float> reconstructed(1024, 0.0f);
    
    for (size_t i = 0; i < input.size(); ++i) {
        float s = input[i];
        
        auto mid = mXoverMid.process(s);
        auto low = mXoverLow.process(mid.low);
        auto high = mXoverHigh.process(mid.high);
        auto vl = mXoverVLow.process(low.low);
        auto lm = mXoverLMid.process(low.high);
        auto hm = mXoverHMid.process(high.low);
        auto vh = mXoverVHi.process(high.high);
        
        float bands[8] = {
            vl.low, vl.high,
            lm.low, lm.high,
            hm.low, hm.high,
            vh.low, vh.high
        };
        
        float sum = 0.0f;
        for (int b = 0; b < 8; ++b) {
            sum += bands[b];
        }
        reconstructed[i] = sum;
    }
    
    // Check that energy of reconstructed matches input
    double inputEnergy = 0.0;
    double outputEnergy = 0.0;
    for (size_t i = 0; i < input.size(); ++i) {
        inputEnergy += input[i] * input[i];
        outputEnergy += reconstructed[i] * reconstructed[i];
    }
    
    std::cout << "  - Input energy: " << inputEnergy << std::endl;
    std::cout << "  - Reconstructed energy: " << outputEnergy << std::endl;
    std::cout << "  - Reconstruction difference: " << std::abs(inputEnergy - outputEnergy) << std::endl;
    
    // In a multi-stage tree filterbank, different branches experience different phase shifts,
    // causing minor reconstruction ripple (~0.7 dB or ~15% energy deviation).
    // We verify it stays within a reasonable tree-tolerance of 0.15.
    assert(std::abs(inputEnergy - outputEnergy) < 0.15);
    
    // Also verify that a SINGLE stage LR4 crossover reconstructed sum is mathematically perfect (unity amplitude)
    LinkwitzRileyCrossover singleXover;
    singleXover.setFrequency(1000.0, sampleRate);
    double singleInputEnergy = 0.0;
    double singleOutputEnergy = 0.0;
    for (size_t i = 0; i < input.size(); ++i) {
        float s = input[i];
        auto out = singleXover.process(s);
        float sum = out.low + out.high;
        singleInputEnergy += s * s;
        singleOutputEnergy += sum * sum;
    }
    std::cout << "  - Single-stage LR4 Input energy: " << singleInputEnergy << std::endl;
    std::cout << "  - Single-stage LR4 Reconstructed energy: " << singleOutputEnergy << std::endl;
    std::cout << "  - Single-stage LR4 Difference: " << std::abs(singleInputEnergy - singleOutputEnergy) << std::endl;
    assert(std::abs(singleInputEnergy - singleOutputEnergy) < 1e-5);
    
    std::cout << "[Test 2] PASSED." << std::endl << std::endl;
}

// Test Case 3: Dynamics Processor Expansion Test
void testDynamicsProcessorExpansion() {
    std::cout << "[Test 3] Starting Dynamics Processor Expansion Test..." << std::endl;
    
    double sampleRate = 48000.0;
    DynamicsProcessor expander;
    // Set parameters: compressThreshold=0dBFS (no compression), expanderThreshold=-60dBFS, expanderRatio=0.5 (2:1 Expansion)
    expander.setParameters(0.0f, 1.0f, -60.0f, 0.5f, 10.0f, 200.0f, sampleRate);
    
    // Feed a constant low-level signal at -70 dBFS (amplitude = 10^(-70/20) = 0.0003162277f)
    float lowLevelAmp = std::pow(10.0f, -70.0f / 20.0f);
    // Use 16384 samples to ensure envelope has fully converged to input level
    std::vector<float> input(16384, lowLevelAmp);
    
    // Process until gain settles
    float finalOutputAmp = 0.0f;
    for (size_t i = 0; i < input.size(); ++i) {
        finalOutputAmp = std::abs(expander.process(input[i]));
    }
    
    float inputDb = 20.0f * std::log10(lowLevelAmp);
    float outputDb = 20.0f * std::log10(finalOutputAmp);
    float gainDb = outputDb - inputDb;
    
    std::cout << "  - Input Level: " << inputDb << " dBFS (Amp: " << lowLevelAmp << ")" << std::endl;
    std::cout << "  - Output Level: " << outputDb << " dBFS (Amp: " << finalOutputAmp << ")" << std::endl;
    std::cout << "  - Expansion Attenuation: " << gainDb << " dB" << std::endl;
    
    // Under 2:1 expansion, a signal 10dB below threshold (-60dB) should receive 10dB of attenuation,
    // resulting in an output at -80dB. Let's verify this with a strict tolerance of +/-0.1 dB.
    assert(outputDb < -79.9f && outputDb > -80.1f);
    assert(gainDb < -9.9f && gainDb > -10.1f);
    
    std::cout << "[Test 3] PASSED." << std::endl << std::endl;
}

int main() {
    std::cout << "=========================================" << std::endl;
    std::cout << "     Hark DSP Native White-Box Tests     " << std::endl;
    std::cout << "=========================================" << std::endl << std::endl;
    
    testMpoLimiterClipperCoordination();
    testLinkwitzRileyCrossover();
    testDynamicsProcessorExpansion();
    
    std::cout << "All DSP White-Box Tests PASSED successfully!" << std::endl;
    return 0;
}
