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

def run_custom_simulation(samples, config_name):
    chain = FullChain(fs)
    chain.ns.set_enabled(True)
    chain.set_ui_gains([0, 0, 2, 4, 6, 8, 10, 12, 14, 16, 16, 16, 16, 16, 16, 16]) # Slope_HighFreq_Boost
    
    if config_name == "no_expander":
        # Set expander ratio to 1.0 (disabled)
        chain.set_wdrc_parameters(-30.0, 1.5, -40.0, 1.0, 5.0, 200.0, fs)
    elif config_name == "slower_smoothing":
        chain.set_wdrc_parameters(-30.0, 1.5, -40.0, 0.25, 5.0, 200.0, fs)
        # We will manually intercept processing to apply slower smoothing
        pass
    else:
        chain.set_wdrc_parameters(-30.0, 1.5, -40.0, 0.25, 5.0, 200.0, fs)
        
    out_samples = []
    
    for x in samples[:fs * 5]: # first 5 seconds
        if config_name == "slower_smoothing":
            # Process manually with slow gain smoothing
            s = chain.ns.process(x)
            s = chain.pinna2.process(chain.pinna1.process(s))
            bands = chain.tree.process(s)
            targets = chain.get_prescription_gains()
            out_sum = 0.0
            for b in range(8):
                # Run WDRC but force slower smoothing in Python replica
                d = chain.wdrc[b]
                # We replicate process() but with slower gain smoothing (0.95/0.05)
                # First, envelope detector
                level = abs(bands[b])
                if level > d.envelope:
                    d.envelope = d.attack_coeff * d.envelope + (1.0 - d.attack_coeff) * level
                else:
                    d.envelope = d.release_coeff * d.envelope + (1.0 - d.release_coeff) * level
                if abs(d.envelope) < 1.175494e-38: d.envelope = 0.0
                
                d.counter += 1
                if d.counter >= d.update_interval:
                    d.counter = 0
                    gain = 1.0
                    env_db = 20 * math.log10(d.envelope) if d.envelope > 1e-9 else -180.0
                    
                    # Track noise floor
                    if env_db < d.noise_floor_db:
                        d.noise_floor_db = d.alpha_noise_down * d.noise_floor_db + (1.0 - d.alpha_noise_down) * env_db
                    else:
                        d.noise_floor_db = d.alpha_noise_up * d.noise_floor_db + (1.0 - d.alpha_noise_up) * env_db
                    if d.noise_floor_db > -20.0: d.noise_floor_db = -20.0
                    if d.noise_floor_db < -80.0: d.noise_floor_db = -80.0
                    
                    final_et_db = d.expander_thresh_db_preset
                    if d.expander_thresh_db_preset > -95.0:
                        adaptive_thresh_db = d.noise_floor_db + 5.0
                        final_et_db = min(d.expander_thresh_db_preset, adaptive_thresh_db)
                    
                    ct_db = 20 * math.log10(d.compress_thresh)
                    et_db = final_et_db
                    
                    if env_db > ct_db - d.KNEE_DB:
                        overshoot = env_db - ct_db
                        if overshoot < d.KNEE_DB:
                            gr_db = (1.0 - 1.0 / d.compress_ratio) * (overshoot + d.KNEE_DB)**2 / (4.0 * d.KNEE_DB)
                        else:
                            gr_db = overshoot * (1.0 - 1.0 / d.compress_ratio)
                        gain = 10 ** (-gr_db / 20.0)
                    elif env_db < et_db:
                        undershoot = et_db - env_db
                        gr_db = undershoot * (1.0 / d.expander_ratio - 1.0)
                        gain = 10 ** (-gr_db / 20.0)
                    d.target_gain = gain
                
                # SLOW SMOOTHING: 0.98 / 0.02
                d.current_gain = 0.98 * d.current_gain + 0.02 * d.target_gain
                out_sum += bands[b] * d.current_gain * targets[b]
            
            s = out_sum
            # Limiter
            s = chain.limiter.process(s)
            s = s * chain.master_gain
            s = soft_clip(s)
            out_samples.append(s)
        else:
            s = chain.process(x)
            out_samples.append(s)
            
    return np.array(out_samples)

print("Running baseline...")
y_base = run_custom_simulation(samples, "baseline")
print("Running without WDRC expanders...")
y_no_exp = run_custom_simulation(samples, "no_expander")
print("Running with slower WDRC gain smoothing...")
y_slow = run_custom_simulation(samples, "slower_smoothing")

for name, y in [("Baseline", y_base), ("No Expander", y_no_exp), ("Slower Smoothing", y_slow)]:
    diffs = np.diff(y)
    jumps = np.sum(np.abs(diffs) >= 0.2)
    max_jump = np.max(np.abs(diffs))
    print(f"Config: {name:<20} | Sharp jumps (>= 0.2): {jumps:<5} | Max jump: {max_jump:.4f}")

if os.path.exists(wav_path):
    os.remove(wav_path)
