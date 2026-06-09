#!/usr/bin/env python3
"""
evaluate_classroom_quiet_lecturer.py
====================================
Automated evaluation pipeline for the quiet classroom distant lecturer dataset.
Uses an energy-based dynamic Voice Activity Detector (VAD) to identify speech
and silence segments in arbitrary recordings without fixed time windows.

Calculates:
1. Speech Gain (dB): How much the distant lecturer's speech is boosted.
2. Background Noise Attenuation (BNA, dB): Quiet ambience attenuation.
3. SNR Improvement (SNRI, dB): SNR(Output) - SNR(Input).
4. Clipped %: Safety check to ensure no clipping occurs.
"""

import os
import sys
import wave
import math
import subprocess
import numpy as np
import matplotlib.pyplot as plt

# Add current directory to path
sys.path.append(os.path.dirname(os.path.abspath(__file__)))
from test_signal_chain import FullChain

# Paths
BASE_DIR = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
AUDIO_DIR = os.path.join(BASE_DIR, "tests", "dsp_whitebox", "audio_test_data", "classroom_quiet_lecturer")
REPORT_DIR = os.path.join(BASE_DIR, "tests", "dsp_whitebox", "report_figures")
os.makedirs(REPORT_DIR, exist_ok=True)

# Headphones configuration
HEADPHONES = {
    "earpods": os.path.join(AUDIO_DIR, "earpods_raw.m4a"),
    "airpods": os.path.join(AUDIO_DIR, "airpods_raw.m4a"),
    "cks330": os.path.join(AUDIO_DIR, "cks330_raw.m4a"),
    "pixel9": os.path.join(AUDIO_DIR, "pixel9_raw.m4a")
}

# UI/Prescription curves to simulate (16 bands)
UI_CURVES = {
    "Flat_0dB": [0.0] * 16,
    "Slope_HighFreq_Boost": [0, 0, 2, 4, 6, 8, 10, 12, 14, 16, 16, 16, 16, 16, 16, 16], # High frequency loss
    "Reverse_Slope_Bass_Boost": [16, 16, 16, 14, 12, 10, 8, 6, 4, 2, 0, 0, 0, 0, 0, 0] # Low frequency loss
}

def convert_m4a_to_wav(m4a_path):
    """Converts M4A to temporary WAV using macOS afconvert."""
    wav_path = m4a_path.replace(".m4a", "_temp.wav")
    if os.path.exists(wav_path):
        os.remove(wav_path)
    cmd = ["afconvert", "-f", "WAVE", "-d", "LEI16@48000", "-c", "1", m4a_path, wav_path]
    subprocess.run(cmd, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return wav_path

def load_wav(filepath):
    """Loads a WAV file using scipy.io.wavfile if available, otherwise falling back to the built-in wave module."""
    try:
        from scipy.io import wavfile
        fs, samples = wavfile.read(filepath)
        # Convert to float32 in [-1.0, 1.0]
        if samples.dtype == np.int16:
            float_samples = samples.astype(np.float32) / 32768.0
        elif samples.dtype == np.float32 or samples.dtype == np.float64:
            float_samples = samples.astype(np.float32)
        else:
            raise ValueError(f"Scipy read unsupported type: {samples.dtype}")
        
        # If stereo/multichannel, mix to mono
        if len(float_samples.shape) > 1:
            nchannels = float_samples.shape[1]
            print(f"{nchannels}-channel file detected. Mixing down to mono.")
            float_samples = np.mean(float_samples, axis=1)
            
        return float_samples, fs
    except Exception as scipy_err:
        # Fallback to built-in wave module
        with wave.open(filepath, 'rb') as w:
            params = w.getparams()
            nchannels, sampwidth, framerate, nframes = params[:4]
            if sampwidth != 2:
                raise ValueError(f"Only 16-bit PCM WAV files are supported. Got sample width: {sampwidth} bytes.")
            
            raw_data = w.readframes(nframes)
            # Convert to 16-bit integers
            samples = np.frombuffer(raw_data, dtype=np.int16)
            
            # Convert to float32 in [-1.0, 1.0]
            float_samples = samples.astype(np.float32) / 32768.0
            
            # If stereo, mix to mono for our single-channel processing pipeline
            if nchannels == 2:
                print("Stereo file detected. Mixing down to mono for processing.")
                float_samples = (float_samples[0::2] + float_samples[1::2]) * 0.5
                
            return float_samples, framerate

def save_wav(filepath, float_samples, framerate):
    """Saves float32 samples in [-1.0, 1.0] to a 16-bit PCM mono WAV file."""
    clamped = np.clip(float_samples, -1.0, 1.0)
    int_samples = (clamped * 32767.0).astype(np.int16)
    with wave.open(filepath, 'wb') as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(framerate)
        w.writeframes(int_samples.tobytes())

def run_simulation(input_samples, sample_rate, ui_curve):
    """Processes input signal through the complete DSP chain."""
    chain = FullChain(sample_rate)
    
    # Configure WDRC for CONVERSATION mode (optimized to suppress noise) using band-specific tuning
    chain.set_wdrc_parameters(-30.0, 1.5, -40.0, 0.25, 5.0, 200.0, sample_rate)
    
    # Enable Noise Suppressor with optimized gain floor and SNR-dependent Wiener filter
    chain.ns.set_enabled(True)
    chain.set_ui_gains(ui_curve)
    
    output_samples = np.zeros_like(input_samples)
    
    # Process sample-by-sample
    for i in range(len(input_samples)):
        s = input_samples[i]
        s_out = chain.process(s, capture=False)
        output_samples[i] = s_out
            
    return output_samples

def dynamic_vad(samples, sample_rate, frame_ms=50.0, hop_ms=25.0):
    """
    Energy-based Voice Activity Detector (VAD).
    Returns boolean arrays for speech and silence frames mapped back to sample level.
    """
    frame_len = int(frame_ms * sample_rate / 1000.0)
    hop_len = int(hop_ms * sample_rate / 1000.0)
    
    n_samples = len(samples)
    rms_values = []
    frame_indices = []
    
    # Calculate short-time RMS
    for start in range(0, n_samples - frame_len, hop_len):
        frame = samples[start:start+frame_len]
        rms = np.sqrt(np.mean(frame**2))
        rms_values.append(rms)
        frame_indices.append((start, start+frame_len))
        
    rms_values = np.array(rms_values)
    if len(rms_values) == 0:
        return np.zeros(n_samples, dtype=bool), np.zeros(n_samples, dtype=bool)
        
    # Estimate noise floor using the 15th percentile
    noise_floor_rms = np.percentile(rms_values, 15)
    
    # Speech threshold: 3.0x noise floor (+9.5dB)
    speech_thresh = max(noise_floor_rms * 3.0, 0.005)
    # Silence threshold: 1.3x noise floor (+2.3dB)
    silence_thresh = max(noise_floor_rms * 1.3, 0.002)
    
    speech_mask = np.zeros(n_samples, dtype=bool)
    silence_mask = np.zeros(n_samples, dtype=bool)
    
    for i, (start, end) in enumerate(frame_indices):
        rms = rms_values[i]
        if rms > speech_thresh:
            speech_mask[start:end] = True
        elif rms < silence_thresh:
            silence_mask[start:end] = True
            
    return speech_mask, silence_mask

def analyze_performance(in_samples, out_samples, speech_mask, silence_mask):
    """Calculates SNRI, BNA, Speech Gain, and Clipped % based on VAD masks."""
    # Safety checks
    if not np.any(speech_mask) or not np.any(silence_mask):
        return 0.0, 0.0, 0.0, 0.0
        
    # Speech RMS
    rms_in_speech = np.sqrt(np.mean(in_samples[speech_mask]**2))
    rms_out_speech = np.sqrt(np.mean(out_samples[speech_mask]**2))
    
    # Silence RMS
    rms_in_silence = np.sqrt(np.mean(in_samples[silence_mask]**2))
    rms_out_silence = np.sqrt(np.mean(out_samples[silence_mask]**2))
    
    # 1. Speech Gain (dB)
    speech_gain = 20 * math.log10(max(rms_out_speech / (rms_in_speech + 1e-12), 1e-12))
    
    # 2. Background Noise Attenuation (BNA)
    bna = 20 * math.log10(max(rms_in_silence / (rms_out_silence + 1e-12), 1e-12))
    
    # 3. SNR Improvement (SNRI)
    snr_in = 20 * math.log10(max(rms_in_speech / (rms_in_silence + 1e-12), 1e-12))
    snr_out = 20 * math.log10(max(rms_out_speech / (rms_out_silence + 1e-12), 1e-12))
    snri = snr_out - snr_in
    
    # 4. Clip %
    clip_count = np.sum(np.abs(out_samples) >= 0.99)
    clip_pct = 100.0 * clip_count / len(out_samples)
    
    return snri, bna, speech_gain, clip_pct

def main():
    print("==========================================================")
    print("      Hark DSP Distant Lecturer Performance Benchmark     ")
    print("==========================================================\n")
    
    # Check if any audio files exist
    files_found = False
    for hp_name, path in HEADPHONES.items():
        if os.path.exists(path):
            files_found = True
            print(f"✅ Found recording: {hp_name} ({os.path.basename(path)})")
        else:
            print(f"❌ Missing recording: {hp_name} (Expected at: {path})")
            
    if not files_found:
        print("\n⚠️ No recording files found in tests/dsp_whitebox/audio_test_data/classroom_quiet_lecturer/.")
        print("Please place your recorded .m4a files there and re-run this script.")
        sys.exit(0)
        
    results = []
    
    for hp_name, path in HEADPHONES.items():
        if not os.path.exists(path):
            continue
            
        print(f"\n🎙️ Processing {hp_name}...")
        
        # Convert M4A to WAV
        temp_wav = convert_m4a_to_wav(path)
        
        try:
            samples, fs = load_wav(temp_wav)
            print(f"  - Length: {len(samples)/fs:.2f} seconds @ {fs}Hz")
            
            # Run dynamic VAD to detect speech and silence frames
            speech_mask, silence_mask = dynamic_vad(samples, fs)
            speech_pct = 100.0 * np.sum(speech_mask) / len(samples)
            silence_pct = 100.0 * np.sum(silence_mask) / len(samples)
            print(f"  - VAD detected Speech: {speech_pct:.1f}%, Silence: {silence_pct:.1f}%")
            
            for curve_name, curve_gains in UI_CURVES.items():
                print(f"  ⚡ Simulating UI Curve: {curve_name}...")
                
                # Run DSP simulation
                out_samples = run_simulation(samples, fs, curve_gains)
                
                # Save processed WAV file to classroom_quiet_lecturer directory
                out_wav_path = path.replace(".m4a", f"_processed_{curve_name}.wav")
                save_wav(out_wav_path, out_samples, fs)
                
                # Analyze performance
                snri, bna, speech_gain, clip_pct = analyze_performance(samples, out_samples, speech_mask, silence_mask)
                
                results.append({
                    "headphone": hp_name.upper(),
                    "curve": curve_name,
                    "snri": snri,
                    "bna": bna,
                    "speech_gain": speech_gain,
                    "clip": clip_pct
                })
                
                print(f"    -> SNRI: {snri:.2f} dB | BNA: {bna:.2f} dB | Speech Gain: {speech_gain:.2f} dB | Clip: {clip_pct:.2f}%")
                
                # Generate a VAD & Waveform visualization plot for the Slope curve
                if curve_name == "Slope_HighFreq_Boost":
                    fig, axes = plt.subplots(3, 1, figsize=(11, 7), sharex=True)
                    t = np.arange(len(samples)) / fs
                    
                    # Input Waveform
                    axes[0].plot(t, samples, color="#9CA3AF", alpha=0.8, label="Raw Input")
                    # Overlay VAD decision
                    speech_plot = np.where(speech_mask, samples, np.nan)
                    axes[0].plot(t, speech_plot, color="#EF4444", alpha=0.9, label="Detected Speech")
                    axes[0].set_title(f"{hp_name.upper()} - Raw Input & VAD Speech Detection")
                    axes[0].legend(loc="upper right", fontsize=8)
                    axes[0].grid(True, alpha=0.2)
                    
                    # Output Waveform
                    axes[1].plot(t, out_samples, color="#3B82F6", alpha=0.8, label="DSP Output (Slope EQ)")
                    axes[1].set_title("DSP Enhanced Output")
                    axes[1].legend(loc="upper right", fontsize=8)
                    axes[1].grid(True, alpha=0.2)
                    
                    # VAD Decision trace
                    vad_trace = np.zeros_like(t)
                    vad_trace[speech_mask] = 1.0
                    vad_trace[silence_mask] = -1.0
                    axes[2].fill_between(t, vad_trace, color="#10B981", alpha=0.4, label="Speech (1) / Silence (-1)")
                    axes[2].set_title("VAD Decision Mask")
                    axes[2].set_xlabel("Time (seconds)")
                    axes[2].set_ylim([-1.5, 1.5])
                    axes[2].legend(loc="upper right", fontsize=8)
                    axes[2].grid(True, alpha=0.2)
                    
                    fig.tight_layout()
                    plot_path = os.path.join(REPORT_DIR, f"lecturer_vad_plot_{hp_name}.png")
                    fig.savefig(plot_path, dpi=150)
                    plt.close(fig)
                    
        finally:
            # Cleanup temp WAV
            if os.path.exists(temp_wav):
                os.remove(temp_wav)
                
    # Write Markdown Report
    report_path = os.path.join(REPORT_DIR, "lecturer_performance_report.md")
    with open(report_path, "w") as f:
        f.write("# 遠端同學發言場景（無強冷氣雜音）DSP 效能評估報告\n\n")
        f.write("本報告利用**能量自適應語音活動檢測 (VAD)**，動態切割台上同學說話（Speech）與安靜停頓（Silence）段落，評估 C++ 核心 DSP 的增益補償與底噪控制表現。\n\n")
        f.write("## 1. 數據基準測試表\n\n")
        f.write("| 耳機/麥克風來源 | UI 增益曲線設計 | 信噪比提升 (SNRI) | 背景噪聲衰減 (BNA) | 語音總體增益 | 破音率 (Clip %) |\n")
        f.write("| :--- | :--- | :---: | :---: | :---: | :---: |\n")
        for r in results:
            f.write(f"| {r['headphone']} | {r['curve']} | {r['snri']:.2f} dB | {r['bna']:.2f} dB | {r['speech_gain']:.2f} dB | {r['clip']:.2f}% |\n")
            
        f.write("\n## 2. 聲學指標意義解讀\n\n")
        f.write("- **語音總體增益 (Speech Gain)**：台上同學講話時的輸出/輸入 RMS 比值。在 Slope (高頻聽損補償) 下，由於遠端人聲的高頻衰減被補強，語音增益應呈現明顯的正值（通常為 +3dB 至 +12dB），證明人聲被成功放大且聽起來更清晰。\n")
        f.write("- **背景噪聲衰減 (BNA)**：台上同學停頓（Silence）時，環境細微噪聲或耳機熱噪被噪聲閘壓低的分貝數。數值越高代表安靜空檔時校正越乾淨。\n")
        f.write("- **信噪比提升 (SNRI)**：輸出端相較輸入端的信噪比改進。反映降噪演算法動態壓制環境背景干擾、凸顯語音人聲的能力。\n")
        
    print("\n📊 Generated performance report:")
    print(f"  - Markdown: {report_path}")
    print(f"  - VAD Plots in: {REPORT_DIR}/")

if __name__ == '__main__':
    main()
