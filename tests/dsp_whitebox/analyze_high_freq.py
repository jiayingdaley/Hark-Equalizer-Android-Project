import os
import math
import numpy as np
import scipy.io.wavfile as wavfile

BASE_DIR = "/Users/shrruei/Desktop/Gemini CLI/Hark"
DIR_PATH = os.path.join(BASE_DIR, "tests", "dsp_whitebox", "audio_test_data", "classroom_quiet_lecturer")

files = [
    "airpods_raw_processed_Slope_HighFreq_Boost.wav",
    "cks330_raw_processed_Slope_HighFreq_Boost.wav",
    "earpods_raw_processed_Slope_HighFreq_Boost.wav"
]

print("="*60)
print("   Analyzing High Frequency Saturation & Clipping")
print("="*60)

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
    # Count samples exceeding -2dBFS (0.794 linear)
    high_amp_samples = np.sum(np.abs(float_samples) >= 0.794)
    pct_high = 100.0 * high_amp_samples / len(float_samples)
    
    # Simple high-pass: y[n] = x[n] - x[n-1]
    hp_diff = np.diff(float_samples)
    hp_peak = np.max(np.abs(hp_diff))
    
    # Sharp jumps (discontinuities)
    sharp_jumps = np.sum(np.abs(hp_diff) >= 0.2)
    
    print(f"\n📁 File: {f}")
    print(f"  - Peak amplitude: {peak:.4f} ({20*math.log10(peak):.2f} dBFS)")
    print(f"  - Samples >= -2dBFS: {high_amp_samples} ({pct_high:.4f}%)")
    print(f"  - Max difference (high-pass proxy): {hp_peak:.4f}")
    print(f"  - Sharp jumps (discontinuities >= 0.2): {sharp_jumps}")

print("\n"+"="*60)
