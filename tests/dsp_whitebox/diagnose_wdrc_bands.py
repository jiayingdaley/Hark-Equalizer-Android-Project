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
chain.set_wdrc_parameters(-30.0, 1.5, -40.0, 0.25, 5.0, 200.0, fs)
chain.ns.set_enabled(True)
chain.set_ui_gains([0, 0, 2, 4, 6, 8, 10, 12, 14, 16, 16, 16, 16, 16, 16, 16]) # Slope_HighFreq_Boost

# Let's process the first 5 seconds and check the signal after each WDRC band
# before adding it to the sum, and also the band input
band_input_jumps = [0]*8
band_output_jumps = [0]*8
band_gain_jumps = [0]*8

targets = chain.get_prescription_gains()

print("Analyzing WDRC bands...")
for x in samples[:48000 * 5]:
    # Run NS and Pinna
    s = chain.ns.process(x)
    s = chain.pinna2.process(chain.pinna1.process(s))
    
    # Crossover split
    bands = chain.tree.process(s)
    
    for b in range(8):
        band_in = bands[b]
        # Run WDRC
        d = chain.wdrc[b]
        band_out = d.process(band_in)
        
        # We accumulate the values to compute diffs later
        if not hasattr(d, "inputs_history"):
            d.inputs_history = []
            d.outputs_history = []
            d.gains_history = []
        d.inputs_history.append(band_in)
        d.outputs_history.append(band_out)
        d.gains_history.append(d.current_gain)

# Now check diffs
print("\n--- Band-by-Band Discontinuity Analysis ---")
for b in range(8):
    d = chain.wdrc[b]
    diff_in = np.diff(np.array(d.inputs_history))
    diff_out = np.diff(np.array(d.outputs_history))
    diff_gain = np.diff(np.array(d.gains_history))
    
    jumps_in = np.sum(np.abs(diff_in) >= 0.05)
    jumps_out = np.sum(np.abs(diff_out) >= 0.05)
    jumps_gain = np.sum(np.abs(diff_gain) >= 0.05)
    
    max_diff_in = np.max(np.abs(diff_in))
    max_diff_out = np.max(np.abs(diff_out))
    max_diff_gain = np.max(np.abs(diff_gain))
    
    print(f"Band {b} (Target Gain={targets[b]:.2f}):")
    print(f"  - Input  diffs >= 0.05: {jumps_in:<5} | Max: {max_diff_in:.4f}")
    print(f"  - Output diffs >= 0.05: {jumps_out:<5} | Max: {max_diff_out:.4f}")
    print(f"  - Gain   diffs >= 0.05: {jumps_gain:<5} | Max: {max_diff_gain:.4f}")

if os.path.exists(wav_path):
    os.remove(wav_path)
