import os
import sys
import numpy as np

# Ensure tests/dsp_whitebox is in path
script_dir = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, script_dir)

from process_recording import load_wav, process_audio

recordings = {
    "ATH-CKS330NC": "audio_test_data/cks330_raw_processed.wav",
    "AirPods": "audio_test_data/airpods_raw_processed.wav",
    "EarPods": "audio_test_data/earpods_raw_processed.wav"
}

print("=== Analyzing Processed Recordings and Modulated Gains ===")

for name, rel_path in recordings.items():
    full_path = os.path.join(script_dir, rel_path)
    # Check if files exist
    if not os.path.exists(full_path):
        print(f"File not found: {full_path}")
        continue
        
    # Re-run simulation to get raw gain traces
    raw_path = os.path.join(script_dir, rel_path.replace("_processed.wav", ".m4a"))
    
    # We must convert to wav first if it is m4a, just like process_recording.py
    import subprocess
    import tempfile
    
    temp_wav_file = tempfile.NamedTemporaryFile(suffix=".wav", delete=False)
    temp_wav_path = temp_wav_file.name
    temp_wav_file.close()
    
    cmd = ["afconvert", "-f", "WAVE", "-d", "LEI16@48000", "-c", "1", raw_path, temp_wav_path]
    subprocess.run(cmd, check=True)
    
    samples, fs = load_wav(temp_wav_path)
    output, g_ts, g_wdrc = process_audio(samples, fs, enable_ns=True, enable_ts=True)
    
    os.unlink(temp_wav_path)
    
    print(f"\nHeadset: {name}")
    print(f"  Input length: {len(samples)} samples ({len(samples)/fs:.2f}s)")
    print(f"  Transient Gain stats: Min={g_ts.min():.4f}, Max={g_ts.max():.4f}, Mean={g_ts.mean():.4f}")
    print(f"  WDRC Band 4 Gain stats: Min={g_wdrc.min():.4f}, Max={g_wdrc.max():.4f}, Mean={g_wdrc.mean():.4f}")
    
    # Check if gains are entirely static
    if g_ts.min() == g_ts.max():
         print("  WARNING: Transient Gain is completely STATIC!")
    if g_wdrc.min() == g_wdrc.max():
         print("  WARNING: WDRC Gain is completely STATIC!")
