import os
import sys
import wave
import numpy as np
import subprocess
from scipy.io import wavfile

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
    wav_path = m4a_path.replace(".m4a", "_temp_in.wav")
    if os.path.exists(wav_path):
        os.remove(wav_path)
    cmd = ["afconvert", "-f", "WAVE", "-d", "LEI16@48000", "-c", "1", m4a_path, wav_path]
    subprocess.run(cmd, check=True)
    return wav_path

def load_wav(filepath):
    fs, samples = wavfile.read(filepath)
    if samples.dtype == np.int16:
        float_samples = samples.astype(np.float32) / 32768.0
    else:
        float_samples = samples.astype(np.float32)
    return float_samples, fs

def main():
    print("=========================================================================")
    print("           Processing Audio for Listening (Hark v4.6 DSP)               ")
    print("=========================================================================")
    
    for name, m4a_path in HEADPHONES.items():
        if not os.path.exists(m4a_path):
            print(f"Skipping {name}: file not found.")
            continue
            
        print(f"\nProcessing {name}...")
        
        # 1. Convert input m4a to temporary wav
        temp_wav = convert_m4a_to_wav(m4a_path)
        samples, fs = load_wav(temp_wav)
        
        # 2. Setup v4.6 Simulation Chain
        chain = FullChain(fs)
        chain.ns.set_enabled(True)
        # Apply standard High-Frequency Boost curve
        ui_curve = [0, 0, 2, 4, 6, 8, 10, 12, 14, 16, 16, 16, 16, 16, 16, 16]
        chain.set_ui_gains(ui_curve)
        
        # 3. Process the entire file
        out_samples = np.zeros_like(samples)
        for i in range(len(samples)):
            out_samples[i] = chain.process(samples[i])
            
        # 4. Save processed output to WAV file
        out_wav_path = os.path.join(AUDIO_DIR, f"{name}_processed_v46.wav")
        if os.path.exists(out_wav_path):
            os.remove(out_wav_path)
            
        # Scale back to 16-bit integer WAV file
        int_samples = np.clip(out_samples * 32767.0, -32768.0, 32767.0).astype(np.int16)
        wavfile.write(out_wav_path, fs, int_samples)
        
        # Clean up temporary input wav
        if os.path.exists(temp_wav):
            os.remove(temp_wav)
            
        print(f"✅ Saved processed file to: {out_wav_path}")
        
    print("\n🎉 ALL AUDIO PROCESSING COMPLETED!")

if __name__ == "__main__":
    main()
