import os
import math
import numpy as np
import scipy.io.wavfile as wavfile

BASE_DIR = "/Users/shrruei/Desktop/Gemini CLI/Hark"
DIR_PATH = os.path.join(BASE_DIR, "tests", "dsp_whitebox", "audio_test_data", "classroom_quiet_lecturer")

files = [
    "airpods_raw_processed_Slope_HighFreq_Boost.wav",
    "cks330_raw_processed_Slope_HighFreq_Boost.wav",
    "earpods_raw_processed_Slope_HighFreq_Boost.wav",
    "pixel9_raw_processed_Slope_HighFreq_Boost.wav"
]

print("="*80)
print("             Processed Audio Signal Analysis Report")
print("="*80)

for f in files:
    path = os.path.join(DIR_PATH, f)
    if not os.path.exists(path):
        print(f"⚠️ {f} not found!")
        continue
    
    fs, samples = wavfile.read(path)
    if samples.dtype == np.int16:
        float_samples = samples.astype(np.float32) / 32768.0
    else:
        float_samples = samples.astype(np.float32)
        
    peak = np.max(np.abs(float_samples))
    rms = np.sqrt(np.mean(float_samples**2))
    crest_factor = peak / (rms + 1e-9)
    
    # Calculate energy in high frequency (>4kHz)
    # Simple 2nd order HPF: y[n] = x[n] - 2x[n-1] + x[n-2]
    hp_sig = float_samples[2:] - 2*float_samples[1:-1] + float_samples[:-2]
    hp_rms = np.sqrt(np.mean(hp_sig**2))
    
    # Check for hard clipping
    # Count samples that are very close to 0.9 or 1.0 (depending on normalization)
    clip_thresh = peak * 0.99
    clipped_samples = np.sum(np.abs(float_samples) >= clip_thresh)
    pct_clipped = 100.0 * clipped_samples / len(float_samples)
    
    print(f"\n📁 File: {f}")
    print(f"  - Peak Amplitude  : {peak:.4f} ({20*math.log10(peak + 1e-12):.2f} dBFS)")
    print(f"  - RMS Amplitude   : {rms:.4f} ({20*math.log10(rms + 1e-12):.2f} dBFS)")
    print(f"  - Crest Factor    : {crest_factor:.2f} (High value = punchy/dynamic, Low value = heavily compressed)")
    print(f"  - HF Energy (RMS) : {hp_rms:.6f}")
    print(f"  - Flat Peaks (>99% of peak): {clipped_samples} ({pct_clipped:.3f}%)")

print("\n"+"="*80)
print("Please run this script in your terminal to see the metrics: ")
print("python3 tests/dsp_whitebox/check_wav_data.py")
print("="*80)
