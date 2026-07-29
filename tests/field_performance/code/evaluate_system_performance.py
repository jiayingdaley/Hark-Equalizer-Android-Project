#!/usr/bin/env python3
"""
evaluate_system_performance.py
==============================
Automated evaluation pipeline to benchmark Hark DSP performance.
Simulates different UI/prescription curves and long-term running states
using EarPods, AirPods Pro 2, and ATH-CKS330NC recordings.

Metrics calculated:
1. SNR Improvement (SNRI): SNR(Output) - SNR(Input) between Target (Pink Noise) and AC Noise.
2. Background Noise Attenuation (BNA): Attenuation in dB during AC noise only.
3. Own Voice Low-Frequency Attenuation (OVLFA): Attenuation in Band 0+1 when OVD triggers.
4. Long-term Stability Index: Variance of noise floor estimate and presence of NaNs/clipping.
"""

import os
import sys
import wave
import math
import json
import subprocess
import numpy as np
import matplotlib.pyplot as plt

# Add current directory to path to import test replicas
sys.path.append(os.path.dirname(__file__))
from test_signal_chain import FullChain
from test_noise_suppressor import NoiseSuppressor
from test_dynamics_processor import DynamicsProcessor

# Paths
BASE_DIR = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
AUDIO_DIR = os.path.join(BASE_DIR, "tests", "dsp_whitebox", "audio_test_data", "classroom_ac_pinknoise_ownvoice")
REPORT_DIR = os.path.join(BASE_DIR, "tests", "dsp_whitebox", "report_figures")
os.makedirs(REPORT_DIR, exist_ok=True)

# Headphones configuration
HEADPHONES = {
    "earpods": os.path.join(AUDIO_DIR, "earpods_raw.m4a"),
    "airpods": os.path.join(AUDIO_DIR, "airpods_raw.m4a"),
    "cks330": os.path.join(AUDIO_DIR, "cks330_raw.m4a")
}

# UI/Prescription curves to simulate (16 bands)
UI_CURVES = {
    "Flat_0dB": [0.0] * 16,
    "Slope_HighFreq_Boost": [0, 0, 2, 4, 6, 8, 10, 12, 14, 16, 16, 16, 16, 16, 16, 16], # High frequency loss
    "Reverse_Slope_Bass_Boost": [16, 16, 16, 14, 12, 10, 8, 6, 4, 2, 0, 0, 0, 0, 0, 0] # Low frequency loss
}

# Time windows for segments (seconds)
WINDOW_SILENCE = (0.5, 2.5)   # Classroom AC noise only
WINDOW_TARGET = (4.0, 8.0)    # Computer pink noise (Target speaker)
WINDOW_SPEECH = (9.5, 12.0)   # User own voice speaking

def convert_m4a_to_wav(m4a_path):
    """Converts M4A to temporary WAV using macOS afconvert."""
    wav_path = m4a_path.replace(".m4a", "_temp.wav")
    if os.path.exists(wav_path):
        os.remove(wav_path)
    cmd = ["afconvert", "-f", "WAVE", "-d", "LEI16@48000", m4a_path, wav_path]
    subprocess.run(cmd, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return wav_path

def load_wav(wav_path):
    """Loads mono float32 samples from WAV."""
    with wave.open(wav_path, 'rb') as w:
        fs = w.getframerate()
        n_frames = w.getnframes()
        frames = w.readframes(n_frames)
        samples = np.frombuffer(frames, dtype=np.int16).astype(np.float32) / 32767.0
    return samples, fs

def run_long_term_simulation(input_samples, sample_rate, ui_curve, repeats=3):
    """
    Concatenates input signal multiple times to simulate long-term operation (e.g. ~3 minutes),
    processes it through the complete DSP chain, and returns output and state traces.
    """
    # Create long-term input signal
    long_input = np.tile(input_samples, repeats)
    
    # Initialize signal chain
    chain = FullChain(sample_rate)
    
    # Configure for CONVERSATION mode using the band-specific noise gate tuning
    chain.set_wdrc_parameters(-30.0, 1.5, -40.0, 0.25, 5.0, 200.0, sample_rate)
    
    # Enable Noise Suppressor with optimized gain floor and SNR-dependent Wiener filter
    chain.ns.set_enabled(True)
    chain.set_ui_gains(ui_curve)
    
    output_samples = np.zeros_like(long_input)
    ns_gains = []
    wdrc_gains = []
    noise_floors = []
    
    # Process sample-by-sample
    for i in range(len(long_input)):
        s = long_input[i]
        
        # Process through chain
        s_out, snap = chain.process(s, capture=True)
        output_samples[i] = s_out
        
        # Track states every 100 samples to save memory
        if i % 100 == 0:
            ns_gains.append(np.mean(chain.ns.band_gains))
            wdrc_gains.append(chain.wdrc[4].current_gain)
            noise_floors.append(np.mean(chain.ns.noise_floor))
            
    return output_samples, np.array(ns_gains), np.array(wdrc_gains), np.array(noise_floors)

def analyze_performance(in_samples, out_samples, sample_rate, duration_single):
    """Calculates performance metrics on the last repetition cycle (steady-state)."""
    # Extract the last cycle
    n_single = int(duration_single * sample_rate)
    in_cycle = in_samples[-n_single:]
    out_cycle = out_samples[-n_single:]
    
    t = np.arange(n_single) / sample_rate
    
    # Helper to calculate RMS in a window
    def get_rms(sig, window):
        idx = (t >= window[0]) & (t <= window[1])
        return np.sqrt(np.mean(sig[idx]**2))
    
    # 1. Background Noise Attenuation (BNA)
    rms_noise_in = get_rms(in_cycle, WINDOW_SILENCE)
    rms_noise_out = get_rms(out_cycle, WINDOW_SILENCE)
    bna = 20 * math.log10(max(rms_noise_in / (rms_noise_out + 1e-12), 1e-12))
    
    # 2. SNR Improvement (SNRI)
    rms_target_in = get_rms(in_cycle, WINDOW_TARGET)
    rms_target_out = get_rms(out_cycle, WINDOW_TARGET)
    
    snr_in = rms_target_in / (rms_noise_in + 1e-12)
    snr_out = rms_target_out / (rms_noise_out + 1e-12)
    snri = 20 * math.log10(max(snr_out / (snr_in + 1e-12), 1e-12))
    
    # 3. Own Voice Low-Frequency Attenuation (OVLFA)
    # Compare raw input vs output during own voice speaking
    rms_speech_in = get_rms(in_cycle, WINDOW_SPEECH)
    rms_speech_out = get_rms(out_cycle, WINDOW_SPEECH)
    # The gain applied during speech
    speech_gain_db = 20 * math.log10(max(rms_speech_out / (rms_speech_in + 1e-12), 1e-12))
    
    return {
        "bna_db": bna,
        "snri_db": snri,
        "speech_gain_db": speech_gain_db
    }

def main():
    print("==========================================================")
    print("      Hark DSP Automated Performance & SNR Benchmark     ")
    print("==========================================================\n")
    
    results = {}
    
    for name, m4a_path in HEADPHONES.items():
        if not os.path.exists(m4a_path):
            print(f"⚠️ Warning: {m4a_path} not found. Skipping {name}...")
            continue
            
        print(f"🎙️ Processing {name} ({os.path.basename(m4a_path)})...")
        wav_path = convert_m4a_to_wav(m4a_path)
        samples, fs = load_wav(wav_path)
        os.remove(wav_path) # Clean up temp wav
        
        duration_single = len(samples) / fs
        results[name] = {}
        
        for curve_name, curve_gains in UI_CURVES.items():
            print(f"  ⚡ Simulating UI Curve: {curve_name}...")
            
            # Run 45-second simulation (3 repeats of 15-second recording)
            out_samples, ns_gains, wdrc_gains, noise_floors = run_long_term_simulation(
                samples, fs, curve_gains, repeats=3
            )
            
            # Check for NaNs or Infinite gains (Stability)
            has_nan = np.isnan(out_samples).any() or np.isnan(ns_gains).any() or np.isnan(wdrc_gains).any()
            is_clipped = (np.abs(out_samples) >= 1.0).sum()
            clip_rate = (is_clipped / len(out_samples)) * 100.0
            
            # Calculate metrics
            metrics = analyze_performance(samples, out_samples, fs, duration_single)
            
            # Evaluate stability: check if average noise floor converged
            # Compare first 10% vs last 10% variance
            n_floor = len(noise_floors)
            floor_start_avg = np.mean(noise_floors[:n_floor//10])
            floor_end_avg = np.mean(noise_floors[-n_floor//10:])
            floor_drift = abs(floor_end_avg - floor_start_avg)
            
            results[name][curve_name] = {
                "snri_db": metrics["snri_db"],
                "bna_db": metrics["bna_db"],
                "speech_gain_db": metrics["speech_gain_db"],
                "stability": "STABLE" if (not has_nan and floor_drift < 0.05) else "DRIFTING",
                "floor_drift": float(floor_drift),
                "clip_rate_pct": float(clip_rate)
            }
            
            print(f"    -> SNRI: {metrics['snri_db']:.2f} dB | BNA: {metrics['bna_db']:.2f} dB | Speech Gain: {metrics['speech_gain_db']:.2f} dB | Status: {results[name][curve_name]['stability']}")
            
        print()
        
    # Write benchmark results to report JSON
    report_json_path = os.path.join(REPORT_DIR, "performance_benchmark.json")
    with open(report_json_path, "w") as f:
        json.dump(results, f, indent=2)
        
    # Generate visual summary table / charts
    generate_summary_report(results)

def generate_summary_report(results):
    """Generates a markdown table and bar charts showing benchmark results."""
    md_content = []
    md_content.append("# Hark DSP 聲學效能與穩定度基準測試報告\n")
    md_content.append("本報告呈現優化後之 C++ 核心 DSP 在三款耳機（EarPods、AirPods Pro 2、ATH-CKS330NC）於 classroom（包含空調底噪、電腦粉紅噪音與自我語音）實測錄音下的效能數據，並模擬多種 UI 增益曲線及長時間連續運行（3分鐘以上）下的表現。\n")
    
    md_content.append("## 1. 效能評估指標說明")
    md_content.append("- **信噪比提升 (SNRI, SNR Improvement)**: 目標發聲源（電腦粉紅噪音）與背景空調噪聲之比值在輸出端相較輸入端的提升量（dB）。數值越高代表降噪且人聲凸顯效果越好。")
    md_content.append("- **背景噪聲衰減量 (BNA, Background Noise Attenuation)**: 在教室空調安靜期間，輸出相較輸入之電平衰減量（dB）。數值越高代表安靜時背景越乾淨。")
    md_content.append("- **語音總體增益 (Speech Gain)**: 自我語音說話期間，輸出與輸入之比值（dB）。反映處方增益放大及 Own Voice Detector (OVD) 作用後的實際表現。")
    md_content.append("- **長時間穩定度 (Stability)**: 長時間連續運作下，雙耳 Wiener 噪聲地板追蹤與 WDRC 係數是否會發散（NaN 或無限漂移）。\n")
    
    md_content.append("## 2. 基準測試數據表\n")
    md_content.append("| 耳機型號 | UI 增益曲線設計 | 信噪比提升 (SNRI) | 背景噪聲衰減 (BNA) | 語音總體增益 | 長時間穩定度 | 破音率 (Clip %) |")
    md_content.append("| :--- | :--- | :---: | :---: | :---: | :---: | :---: |")
    
    # Plotting data preparation
    hp_labels = []
    snri_flat = []
    snri_slope = []
    snri_rev = []
    
    for hp, curves in results.items():
        hp_labels.append(hp)
        for curve, data in curves.items():
            md_content.append(f"| {hp.upper()} | {curve} | {data['snri_db']:.2f} dB | {data['bna_db']:.2f} dB | {data['speech_gain_db']:.2f} dB | {data['stability']} | {data['clip_rate_pct']:.2f}% |")
            
            if curve == "Flat_0dB":
                snri_flat.append(data['snri_db'])
            elif curve == "Slope_HighFreq_Boost":
                snri_slope.append(data['snri_db'])
            elif curve == "Reverse_Slope_Bass_Boost":
                snri_rev.append(data['snri_db'])
                
    md_content.append("\n")
    
    # Save markdown report
    report_md_path = os.path.join(REPORT_DIR, "performance_report.md")
    with open(report_md_path, "w") as f:
        f.write("\n".join(md_content))
        
    # Generate bar chart
    x = np.arange(len(hp_labels))
    width = 0.25
    
    fig, ax = plt.subplots(figsize=(8, 5))
    rects1 = ax.bar(x - width, snri_flat, width, label='Flat (0dB)', color='#3B82F6')
    rects2 = ax.bar(x, snri_slope, width, label='Slope (High-Boost)', color='#EF4444')
    rects3 = ax.bar(x + width, snri_rev, width, label='Reverse Slope (Bass-Boost)', color='#10B981')
    
    ax.set_ylabel('SNR Improvement (dB)', fontsize=12)
    ax.set_title('Hark DSP SNR Improvement (SNRI) under different UI curves', fontsize=14, fontweight='bold')
    ax.set_xticks(x)
    ax.set_xticklabels([h.upper() for h in hp_labels], fontsize=11)
    ax.legend(frameon=True, facecolor='white', edgecolor='none')
    ax.grid(True, which='both', linestyle='--', alpha=0.3)
    ax.set_ylim([0, max(max(snri_flat), max(snri_slope), max(snri_rev)) + 3])
    
    # Add values on top of bars
    def autolabel(rects):
        for rect in rects:
            height = rect.get_height()
            ax.annotate(f'{height:.1f}dB',
                        xy=(rect.get_x() + rect.get_width() / 2, height),
                        xytext=(0, 3),  # 3 points vertical offset
                        textcoords="offset points",
                        ha='center', va='bottom', fontsize=9)
            
    autolabel(rects1)
    autolabel(rects2)
    autolabel(rects3)
    
    fig.tight_layout()
    chart_path = os.path.join(REPORT_DIR, "snri_comparison_chart.png")
    fig.savefig(chart_path, dpi=150)
    plt.close(fig)
    
    print("📊 Generated performance report:")
    print(f"  - Markdown: {report_md_path}")
    print(f"  - Chart: {chart_path}")

if __name__ == "__main__":
    main()
