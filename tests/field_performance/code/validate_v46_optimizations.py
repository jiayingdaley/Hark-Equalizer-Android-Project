import os
import sys
import wave
import numpy as np
import subprocess
import math

# Paths
BASE_DIR = "/Users/shrruei/Desktop/Gemini CLI/Hark"
AUDIO_DIR = os.path.join(BASE_DIR, "tests", "dsp_whitebox", "audio_test_data", "classroom_quiet_lecturer")
sys.path.append(os.path.join(BASE_DIR, "tests", "dsp_whitebox"))
from test_signal_chain import FullChain

HEADPHONES = {
    "earpods": os.path.join(AUDIO_DIR, "earpods_raw.m4a"),
    "airpods": os.path.join(AUDIO_DIR, "airpods_raw.m4a"),
    "cks330": os.path.join(AUDIO_DIR, "cks330_raw.m4a"),
    "pixel9": os.path.join(AUDIO_DIR, "pixel9_raw.m4a")
}

def convert_m4a_to_wav(m4a_path):
    wav_path = m4a_path.replace(".m4a", "_temp_val.wav")
    if os.path.exists(wav_path):
        os.remove(wav_path)
    cmd = ["afconvert", "-f", "WAVE", "-d", "LEI16@48000", "-c", "1", m4a_path, wav_path]
    subprocess.run(cmd, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return wav_path

def load_wav(filepath):
    from scipy.io import wavfile
    fs, samples = wavfile.read(filepath)
    if samples.dtype == np.int16:
        float_samples = samples.astype(np.float32) / 32768.0
    else:
        float_samples = samples.astype(np.float32)
    if len(float_samples.shape) > 1:
        float_samples = np.mean(float_samples, axis=1)
    return float_samples, fs

def dynamic_vad(samples, sample_rate, frame_ms=50.0, hop_ms=25.0):
    frame_len = int(frame_ms * sample_rate / 1000.0)
    hop_len = int(hop_ms * sample_rate / 1000.0)
    n_samples = len(samples)
    rms_values = []
    frame_indices = []
    for start in range(0, n_samples - frame_len, hop_len):
        frame = samples[start:start+frame_len]
        rms = np.sqrt(np.mean(frame**2))
        rms_values.append(rms)
        frame_indices.append((start, start+frame_len))
    rms_values = np.array(rms_values)
    noise_floor_rms = np.percentile(rms_values, 15)
    speech_thresh = max(noise_floor_rms * 3.0, 0.005)
    speech_mask = np.zeros(n_samples, dtype=bool)
    for i, (start, end) in enumerate(frame_indices):
        rms = rms_values[i]
        if rms > speech_thresh:
            speech_mask[start:end] = True
    return speech_mask

def run_simulation(samples, fs, version="v4.6"):
    chain = FullChain(fs)
    # Enable NS
    chain.ns.set_enabled(True)
    # Set UI Curve (Slope)
    ui_curve = [0, 0, 2, 4, 6, 8, 10, 12, 14, 16, 16, 16, 16, 16, 16, 16]
    chain.set_ui_gains(ui_curve)
    
    if version == "v4.5":
        # Force old expander parameters (no speech-band relaxation)
        # We override set_wdrc_parameters behaviour by directly setting them
        for b in range(8):
            band_exp_thresh = -40.0
            band_exp_ratio = 0.25 # 4:1
            if b == 0:
                band_exp_thresh = -32.0
                band_exp_ratio = 0.40
            elif b == 1:
                band_exp_thresh = -35.0
                band_exp_ratio = 0.45
            elif b == 7:
                band_exp_thresh = -36.0
                band_exp_ratio = 0.40
            chain.wdrc[b].set_parameters(-30.0, 1.5, band_exp_thresh, band_exp_ratio, 5.0, 200.0, fs)
            
        # Force old static headroom (no input rms level-dependency)
        def get_gains_static():
            # Original get_prescription_gains with fixed scaling = 1.0
            gain_sum = [0.0] * 8
            count = [0] * 8
            for i in range(16):
                from test_signal_chain import UI_TO_INTERNAL
                b = UI_TO_INTERNAL[i]
                gain_sum[b] += chain.band_gains_ui[i]
                count[b] += 1
            max_boost = 16.0 # Slope curve max boost
            sum_boost = sum([d for d in ui_curve if d > 0])
            headroom_db = -max(0.0, max_boost * 0.40 + sum_boost * 0.05)
            headroom_linear = 10 ** (headroom_db / 20.0)
            targets = []
            for b in range(8):
                if b == 0:
                    first_band_db = chain.band_gains_ui[0]
                    val = 10 ** ((first_band_db * 0.8 + 4.0) / 20.0) * headroom_linear
                else:
                    avg = gain_sum[b] / count[b] if count[b] > 0 else 0.0
                    val = 10 ** ((avg + chain.global_offset_db) / 20.0) * headroom_linear
                targets.append(val)
            return targets
        chain.get_prescription_gains = get_gains_static

    # Process samples
    out = np.zeros_like(samples)
    for i in range(len(samples)):
        out[i] = chain.process(samples[i])
    return out

def main():
    print("=========================================================================")
    print("                 DSP v4.5 vs v4.6 Verification Test                      ")
    print("=========================================================================")
    
    success = True
    for name, m4a_path in HEADPHONES.items():
        if not os.path.exists(m4a_path):
            continue
        wav_path = convert_m4a_to_wav(m4a_path)
        samples, fs = load_wav(wav_path)
        speech_mask = dynamic_vad(samples, fs)
        
        def rms_db(x_sig):
            sub = x_sig[speech_mask]
            rms = np.sqrt(np.mean(sub**2)) if len(sub) > 0 else 0.0
            return 20 * np.log10(rms) if rms > 0 else -99.0
        
        in_db = rms_db(samples)
        
        # 1. Run v4.5 Baseline
        out_v45 = run_simulation(samples, fs, "v4.5")
        gain_v45 = rms_db(out_v45) - in_db
        clip_v45 = np.sum(np.abs(out_v45) >= 0.99) / len(out_v45) * 100.0
        
        # 2. Run v4.6 Optimized
        out_v46 = run_simulation(samples, fs, "v4.6")
        gain_v46 = rms_db(out_v46) - in_db
        clip_v46 = np.sum(np.abs(out_v46) >= 0.99) / len(out_v46) * 100.0
        
        delta_gain = gain_v46 - gain_v45
        
        print(f"\n🎙️ Device: {name.upper()}")
        print(f"  - Input Speech RMS    : {in_db:.2f} dBFS")
        print(f"  - v4.5 Speech Gain    : {gain_v45:.2f} dB  (Clip: {clip_v45:.3f}%)")
        print(f"  - v4.6 Speech Gain    : {gain_v46:.2f} dB  (Clip: {clip_v46:.3f}%)")
        print(f"  - Speech Gain Delta   : {delta_gain:+.2f} dB")
        
        # Assertions for verification
        target_delta = 8.0
        if in_db > -25.0:
            target_delta = 1.5
        elif in_db > -30.0:
            target_delta = 5.0
            
        if delta_gain < target_delta:
            print(f"  ❌ FAIL: Speech gain improvement too low ({delta_gain:.2f} dB < {target_delta:.2f} dB)")
            success = False
        else:
            print(f"  ✅ PASS: Speech gain improvement satisfies target (>= {target_delta:.2f} dB)")
            
        if clip_v46 > 0.05:
            print(f"  ❌ FAIL: Clipping rate too high ({clip_v46:.3f}% > 0.05%)")
            success = False
        else:
            print(f"  ✅ PASS: Clipping rate is within safe bounds ({clip_v46:.3f}%)")
            
        if os.path.exists(wav_path):
            os.remove(wav_path)
            
    if success:
        print("\n🎉 ALL v4.6 OPTIMIZATIONS VALIDATED SUCCESSFULLY ON THE DATASET!")
        sys.exit(0)
    else:
        print("\n❌ SOME OPTIMIZATION CHECKS FAILED.")
        sys.exit(1)

if __name__ == "__main__":
    main()
