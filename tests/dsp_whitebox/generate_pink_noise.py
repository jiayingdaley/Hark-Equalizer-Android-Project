#!/usr/bin/env python3
"""
generate_pink_noise.py
======================
Generates a calibrated 10-second Pink Noise WAV file at -18dBFS RMS, 
which is ideal for acoustic testing of headset microphones.

Usage:
    python3 tests/dsp_whitebox/generate_pink_noise.py [output_path]
"""

import sys
import os
import wave
import numpy as np

def generate_pink_noise(duration=10.0, fs=48000, rms_db=-18.0):
    n_samples = int(duration * fs)
    # Generate white noise first
    uneven = np.random.randn(n_samples)
    
    # Perform FFT to filter white noise into pink noise (1/f spectral density)
    uneven_fft = np.fft.rfft(uneven)
    freqs = np.fft.rfftfreq(n_samples, d=1.0/fs)
    # Avoid division by zero at DC
    freqs[0] = 1.0
    
    # Pink noise filter response is 1/sqrt(f) in amplitude spectrum
    response = 1.0 / np.sqrt(freqs)
    pink_fft = uneven_fft * response
    
    # Transform back to time domain
    pink = np.fft.irfft(pink_fft, n_samples)
    
    # Normalize to target RMS
    current_rms = np.sqrt(np.mean(pink**2))
    target_rms = 10**(rms_db / 20.0)
    pink = pink * (target_rms / current_rms)
    
    # Clip to prevent peaks exceeding [-1.0, 1.0]
    pink = np.clip(pink, -1.0, 1.0)
    return pink

def main():
    output_path = "tests/dsp_whitebox/pink_noise_reference.wav"
    if len(sys.argv) >= 2:
        output_path = sys.argv[1]
        
    os.makedirs(os.path.dirname(output_path), exist_ok=True)
    fs = 48000
    
    print(f"Generating pink noise (10s, 48kHz)...")
    samples = generate_pink_noise(duration=10.0, fs=fs)
    
    # Convert to 16-bit integers
    int_samples = (samples * 32767.0).astype(np.int16)
    
    print(f"Saving to: {output_path}")
    with wave.open(output_path, 'wb') as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(fs)
        w.writeframes(int_samples.tobytes())
        
    print("Pink noise reference generated successfully!")

if __name__ == '__main__':
    main()
