import os
import math
import numpy as np
import scipy.io.wavfile as wavfile
from test_signal_chain import FullChain, soft_clip

BASE_DIR = "/Users/shrruei/Desktop/Gemini CLI/Hark"
m4a_path = os.path.join(BASE_DIR, "tests", "dsp_whitebox", "audio_test_data", "classroom_quiet_lecturer", "earpods_raw.m4a")

# Convert m4a to wav first
wav_path = "/Users/shrruei/Desktop/Gemini CLI/Hark/tests/dsp_whitebox/earpods_raw_diagnose_tmp.wav"
if os.path.exists(wav_path):
    os.remove(wav_path)

import subprocess
subprocess.run(["afconvert", "-f", "WAVE", "-d", "LEI16@48000", "-c", "1", m4a_path, wav_path], check=True)

fs, data = wavfile.read(wav_path)
samples = data.astype(np.float32) / 32768.0

def run_limiter_1_simulation(samples):
    chain = FullChain(fs)
    chain.ns.set_enabled(True)
    chain.set_ui_gains([0, 0, 2, 4, 6, 8, 10, 12, 14, 16, 16, 16, 16, 16, 16, 16]) # Slope_HighFreq_Boost
    chain.set_wdrc_parameters(-30.0, 1.5, -40.0, 0.25, 5.0, 200.0, fs)
    
    # We will manually intercept the limiter and set its UPDATE_INTERVAL to 1
    chain.limiter.UPDATE_INTERVAL = 1
    
    out_samples = []
    for x in samples[:fs * 5]: # first 5 seconds
        s = chain.ns.process(x)
        s = chain.pinna2.process(chain.pinna1.process(s))
        bands = chain.tree.process(s)
        targets = chain.get_prescription_gains()
        
        # WDRC bands
        out_sum = 0.0
        for b in range(8):
            out_sum += chain.wdrc[b].process(bands[b]) * targets[b]
            
        s = out_sum
        
        # Limiter with UPDATE_INTERVAL = 1 (we process using chain.limiter.process)
        s = chain.limiter.process(s)
        s = s * chain.master_gain
        s = soft_clip(s)
        out_samples.append(s)
        
    return np.array(out_samples)

print("Running simulation with Limiter UPDATE_INTERVAL = 1...")
y_lim1 = run_limiter_1_simulation(samples)

diffs = np.diff(y_lim1)
jumps = np.sum(np.abs(diffs) >= 0.2)
max_jump = np.max(np.abs(diffs))
print(f"Limiter Update=1 | Sharp jumps (>= 0.2): {jumps} | Max jump: {max_jump:.4f}")

if os.path.exists(wav_path):
    os.remove(wav_path)
