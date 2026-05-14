#pragma once
#include <vector>
#include "BiquadFilter.h"

/**
 * NoiseSuppressor: A multi-band spectral noise gate.
 *
 * Algorithm: Wiener SNR-weighted filter bank using 5 Biquad BandPass
 * analysis filters (500/1k/2k/3k/4kHz). The per-band SNR is used to
 * compute a Wiener gain: G = SNR / (SNR + suppressionFactor), floored
 * at gainFloor to prevent the hearing aid from going completely silent.
 *
 * Key design parameters (actual values set in constructor):
 *   suppressionFactor = 2.0f
 *   gainFloor         = 0.20f  (~-14dB)
 *   alphaSignal       = 0.95f  (fast energy tracking)
 *   alphaNoise        = 0.9998f (very slow noise-floor tracking)
 *   alphaGain         = 0.70f  (gain smoothing, overrides header default)
 *
 * Cold-start behaviour:
 *   On first use the noise floor is pre-initialised to a typical room
 *   ambient level (-40dBFS, 0.01 linear).  For best performance, call
 *   calibrateNoiseFloor() with ~500ms of ambient audio after the engine
 *   starts so the Wiener gate is tuned to the actual environment before
 *   any speech arrives.
 *
 * Ref: Wiener filter theory – Loizou, P.C. (2007). Speech Enhancement:
 *      Theory and Practice. CRC Press. Ch. 4.
 */
class NoiseSuppressor {
public:
    explicit NoiseSuppressor(double sampleRate);

    // --- Real-time API (called from audio thread) ---

    /** Processes a single sample through the Wiener SNR-gate. */
    float process(float sample);

    // --- Control API (called from JNI / UI thread, safe under mDSPMutex) ---

    /** Enables or disables noise reduction. When disabled, returns input unchanged. */
    void setEnabled(bool enabled) { mEnabled = enabled; }

    /**
     * Cold-start calibration: bootstraps the per-band noise floor from a
     * block of ambient audio samples (e.g. 500ms captured right after the
     * engine starts, before the user speaks).
     *
     * Runs the samples through the wind-noise filter and each analysis
     * filter, computes the long-run RMS per band, and writes those values
     * directly into mNoiseFloor[], bypassing the slow alpha-tracking.
     *
     * Thread-safety: must NOT be called concurrently with process().
     * In HarkAudioEngine, call under mDSPMutex or before stream start.
     *
     * @param samples    Pointer to mono Float32 audio frames.
     * @param numSamples Number of frames (recommend >= Fs * 0.5 = 24000).
     */
    void calibrateNoiseFloor(const float* samples, int numSamples);

    // Legacy stub – kept for ABI compatibility; prefer calibrateNoiseFloor().
    void updateNoiseFloor() {}

    // --- Analysis API ---
    float getBandEnergy(int band) const {
        if (band < 0 || band >= NUM_BANDS) return 0.0f;
        return mSignalEnergy[band];
    }

private:
    double mSampleRate;
    bool   mEnabled = true;

    static const int NUM_BANDS = 5;
    std::vector<BiquadFilter> mAnalysisFilters;
    BiquadFilter mWindNoiseFilter; // 150Hz High Pass Filter

    // Per-band state
    float mSignalEnergy[NUM_BANDS];
    float mNoiseFloor[NUM_BANDS];   // Initialised to ~-40dBFS (0.01f)
    float mBandGains[NUM_BANDS];

    // Smoothing coefficients (overridden in constructor – see .cpp)
    float mAlphaNoise  = 0.999f;
    float mAlphaSignal = 0.95f;
    float mAlphaGain   = 0.9f;
};
