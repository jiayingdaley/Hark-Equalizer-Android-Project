import os
import math
import numpy as np
import scipy.io.wavfile as wavfile
from test_signal_chain import FullChain

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

chain = FullChain(fs)
# CONVERSATION parameters
chain.set_wdrc_parameters(-30.0, 1.5, -40.0, 0.25, 5.0, 200.0, fs)
chain.ns.set_enabled(True)
chain.set_ui_gains([0, 0, 2, 4, 6, 8, 10, 12, 14, 16, 16, 16, 16, 16, 16, 16]) # Slope_HighFreq_Boost

# We will capture intermediate stages
stage_data = {
    "in": [],
    "ns": [],
    "pinna": [],
    "wdrc": [],
    "limiter": [],
    "softclip": []
}

print("Running block simulation with stage capture...")
for x in samples[:48000 * 5]: # analyze first 5 seconds
    s, snap = chain.process(x, capture=True)
    stage_data["in"].append(snap["stage0_in"])
    stage_data["ns"].append(snap["stage1_ns"])
    stage_data["pinna"].append(snap["stage2_pinna"])
    stage_data["wdrc"].append(snap["stage4_wdrc"])
    stage_data["limiter"].append(snap["stage5_limiter"])
    stage_data["softclip"].append(snap["stage6_softclip"])

# Convert to np.ndarray
for k in stage_data:
    stage_data[k] = np.array(stage_data[k])

print("\n--- Discontinuity Analysis (diff >= 0.2) ---")
for k in ["in", "ns", "pinna", "wdrc", "limiter", "softclip"]:
    arr = stage_data[k]
    diffs = np.diff(arr)
    jumps = np.sum(np.abs(diffs) >= 0.2)
    max_jump = np.max(np.abs(diffs)) if len(diffs) > 0 else 0.0
    print(f"Stage: {k:<10} | Sharp jumps (>= 0.2): {jumps:<5} | Max jump: {max_jump:.4f}")

if os.path.exists(wav_path):
    os.remove(wav_path)
