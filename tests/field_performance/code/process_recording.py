#!/usr/bin/env python3
"""
process_recording.py
====================
A utility to process real microphone recordings (WAV format) through the Hark DSP chain.
This allows developers to run simulations of different headsets (Apple EarPods, AirPods Pro, ATH-CKS330NC)
and verify signal processing features (Noise Suppressor, WDRC, Transient Suppressor)
with actual acoustic data.

Usage:
    python3 tests/dsp_whitebox/process_recording.py <input_wav_file> [output_wav_file] [options]
"""

import sys
import os
import math
import struct
import wave
import numpy as np
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import subprocess
import tempfile

# Ensure the script directory is in path to import other test modules
script_dir = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, script_dir)

from test_signal_chain import FullChain
from test_noise_suppressor import NoiseSuppressor

class TransientSuppressor:
    """Python replica of the Native TransientSuppressor (v2 with smooth attack)"""
    def __init__(self, sample_rate=48000.0):
        self.sr = sample_rate
        self.enabled = True
        self.threshold_db = 16.0         # Raised from 12.0 to prevent triggering on normal speech transients
        self.energy_threshold_db = -35.0 # Only trigger if the absolute signal is above -35dBFS
        self.attenuation_db = -15.0
        self.hold_ms = 15.0
        self.release_ms = 50.0
        
        self.env_fast = 0.0
        self.env_slow = 0.0
        self.current_gain = 1.0
        self.hold_counter = 0
        self.update_coefficients()

    def update_coefficients(self):
        self.fast_coeff = math.exp(-1.0 / (self.sr * 0.001))
        self.slow_coeff = math.exp(-1.0 / (self.sr * 0.100))
        self.attack_coeff = math.exp(-1.0 / (self.sr * 0.0005)) # ~0.5ms smooth attack
        self.release_coeff = math.exp(-1.0 / (self.sr * (self.release_ms / 1000.0)))
        self.hold_samples = int(self.sr * (self.hold_ms / 1000.0))

    def process(self, x):
        if not self.enabled:
            return x
        abs_val = abs(x)
        self.env_fast = self.fast_coeff * self.env_fast + (1.0 - self.fast_coeff) * abs_val
        self.env_slow = self.slow_coeff * self.env_slow + (1.0 - self.slow_coeff) * abs_val

        # Avoid denormals
        if self.env_fast < 1e-30: self.env_fast = 0.0
        if self.env_slow < 1e-30: self.env_slow = 0.0

        ratio = 1.0
        if self.env_slow > 1e-6:
            ratio = self.env_fast / self.env_slow
        ratio_db = 20.0 * math.log10(ratio) if ratio > 1e-6 else 0.0

        target_gain = 1.0
        min_level = 10.0 ** (self.energy_threshold_db / 20.0)
        if self.env_fast > min_level and ratio_db > self.threshold_db:
            self.hold_counter = self.hold_samples

        if self.hold_counter > 0:
            target_gain = 10.0 ** (self.attenuation_db / 20.0)
            self.hold_counter -= 1

        if target_gain < self.current_gain:
            # Smooth attack (~0.5ms) to prevent pops
            self.current_gain = self.attack_coeff * self.current_gain + (1.0 - self.attack_coeff) * target_gain
        else:
            self.current_gain = self.release_coeff * self.current_gain + (1.0 - self.release_coeff) * target_gain

        return x * self.current_gain

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
    # Clamp to prevent clipping
    clamped = np.clip(float_samples, -1.0, 1.0)
    int_samples = (clamped * 32767.0).astype(np.int16)
    
    with wave.open(filepath, 'wb') as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(framerate)
        w.writeframes(int_samples.tobytes())

def process_audio(input_samples, sample_rate, enable_ns=True, enable_ts=True, ns_strength=1.0):
    """Runs the samples through the complete DSP simulation chain."""
    chain = FullChain(sample_rate)
    
    # Configure WDRC for CONVERSATION mode (optimized to suppress noise) using band-specific tuning
    chain.set_wdrc_parameters(-30.0, 1.5, -40.0, 0.25, 5.0, 200.0, sample_rate)
    
    # Configure Noise Suppressor
    chain.ns.set_enabled(enable_ns)
    chain.ns.gain_floor = 0.10  # Lowered from 0.20 to 0.10 (-20dB) for stronger noise reduction
    
    ts = TransientSuppressor(sample_rate)
    ts.enabled = enable_ts
    
    output_samples = np.zeros_like(input_samples)
    gains_ts = []
    gains_wdrc = []
    
    print("Processing audio samples through simulation chain...")
    for i in range(len(input_samples)):
        s = input_samples[i]
        
        # 1. Transient Suppressor (Impulse Noise)
        s = ts.process(s)
        gains_ts.append(ts.current_gain)
        
        # 2. Main Chain (NS, Pinna, Crossover/WDRC, Limiter, Clipper)
        s_out, snap = chain.process(s, capture=True)
        output_samples[i] = s_out
        
        # Extract dynamic gains for plotting
        gains_wdrc.append(chain.wdrc[4].current_gain) # Monitor band 4 WDRC gain
        
    return output_samples, np.array(gains_ts), np.array(gains_wdrc)

def plot_analysis(input_samples, output_samples, gains_ts, gains_wdrc, sample_rate, fig_path):
    """Generates analysis plots showing waveform and spectral comparisons."""
    duration = len(input_samples) / sample_rate
    t = np.linspace(0, duration, len(input_samples))
    
    fig, axes = plt.subplots(4, 1, figsize=(12, 10), sharex=True)
    
    # Plot 1: Waveforms
    axes[0].plot(t, input_samples, color='#6B7280', alpha=0.7, label='Raw Input')
    axes[0].plot(t, output_samples, color='#3B82F6', alpha=0.8, label='DSP Output')
    axes[0].set_ylabel('Amplitude')
    axes[0].set_title('Waveform Comparison')
    axes[0].legend(loc='upper right')
    axes[0].grid(True, alpha=0.3)
    
    # Plot 2: Spectrogram of Input
    axes[1].specgram(input_samples, Fs=sample_rate, NFFT=1024, noverlap=512, cmap='viridis')
    axes[1].set_ylabel('Frequency (Hz)')
    axes[1].set_title('Raw Input Spectrogram')
    
    # Plot 3: Spectrogram of Output
    axes[2].specgram(output_samples, Fs=sample_rate, NFFT=1024, noverlap=512, cmap='viridis')
    axes[2].set_ylabel('Frequency (Hz)')
    axes[2].set_title('DSP Output Spectrogram')
    
    # Plot 4: Transient Suppressor & WDRC Gain Trace
    axes[3].plot(t, gains_ts, color='#EF4444', label='Transient Gain')
    axes[3].plot(t, gains_wdrc, color='#F59E0B', label='Band 4 WDRC Gain')
    axes[3].set_ylabel('Gain Factor')
    axes[3].set_xlabel('Time (seconds)')
    axes[3].set_title('DSP Gain Modulation Traces')
    axes[3].set_ylim([-0.1, 1.2])
    axes[3].legend(loc='lower right')
    axes[3].grid(True, alpha=0.3)
    
    plt.tight_layout()
    plt.savefig(fig_path, dpi=150)
    plt.close()

def main():
    if len(sys.argv) < 2:
        print("Error: Missing input file.")
        print("Usage: python3 process_recording.py <input_file> [output_file]")
        sys.exit(1)
        
    input_path = sys.argv[1]
    if not os.path.exists(input_path):
        print(f"Error: File '{input_path}' does not exist.")
        sys.exit(1)
        
    # Check if input is .m4a or .aac
    base, ext = os.path.splitext(input_path)
    is_m4a = ext.lower() in ['.m4a', '.aac', '.mp4']
    
    temp_wav_path = None
    if is_m4a:
        print(f"M4A/AAC file detected. Automatically converting to 48kHz WAV using macOS afconvert...")
        temp_wav_file = tempfile.NamedTemporaryFile(suffix=".wav", delete=False)
        temp_wav_path = temp_wav_file.name
        temp_wav_file.close()
        
        # Execute afconvert command - force single channel (mono) to avoid WAVE_FORMAT_EXTENSIBLE header
        cmd = ["afconvert", "-f", "WAVE", "-d", "LEI16@48000", "-c", "1", input_path, temp_wav_path]
        try:
            subprocess.run(cmd, check=True)
            print(f"Successfully converted to temporary WAV: {temp_wav_path}")
            load_path = temp_wav_path
        except Exception as e:
            print(f"Error executing afconvert: {e}")
            print("Please make sure you are running on macOS and afconvert is available.")
            if os.path.exists(temp_wav_path):
                os.unlink(temp_wav_path)
            sys.exit(1)
    else:
        load_path = input_path

    # Force output to be .wav to prevent AAC compression loss during evaluation
    output_path = f"{base}_processed.wav"
    fig_path = f"{base}_processed_plot.png"
    
    print(f"Loading raw recording: {load_path}")
    try:
        samples, fs = load_wav(load_path)
    except Exception as e:
        print(f"Error loading WAV: {e}")
        if temp_wav_path and os.path.exists(temp_wav_path):
            os.unlink(temp_wav_path)
        sys.exit(1)
        
    print(f"Sample Rate: {fs} Hz, Duration: {len(samples)/fs:.2f} seconds")
    
    if fs != 48000:
        print("Warning: Hark DSP runs at 48000Hz. Using non-48k sample rate in simulation might shift filter frequencies.")
    
    # Process
    output, g_ts, g_wdrc = process_audio(samples, fs, enable_ns=True, enable_ts=True)
    
    print(f"Saving processed recording: {output_path}")
    save_wav(output_path, output, fs)
    
    print(f"Saving spectral analysis plot: {fig_path}")
    plot_analysis(samples, output, g_ts, g_wdrc, fs, fig_path)
    
    # Clean up temporary file if created
    if temp_wav_path and os.path.exists(temp_wav_path):
        os.unlink(temp_wav_path)
        
    print("\nProcessing completed successfully!")
    print(f"Output WAV: {output_path}")
    print(f"Analysis Plot: {fig_path}")

if __name__ == '__main__':
    main()
