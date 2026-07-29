import os
import math
import numpy as np
import scipy.io.wavfile as wavfile
from test_signal_chain import FullChain

SAMPLE_RATE = 48000.0

def measure_thd(freq, amp_dbfs):
    """
    Generates a pure sine wave at `freq` with peak amplitude `amp_dbfs`,
    runs it through the FullChain, and computes the THD (Total Harmonic Distortion)
    of the processed output.
    """
    t = np.arange(int(SAMPLE_RATE * 0.5)) / SAMPLE_RATE
    amp = 10 ** (amp_dbfs / 20.0)
    x = amp * np.sin(2 * math.pi * freq * t).astype(np.float32)
    
    chain = FullChain(SAMPLE_RATE)
    # CONVERSATION parameters
    chain.set_wdrc_parameters(-30.0, 1.5, -40.0, 0.25, 5.0, 200.0, SAMPLE_RATE)
    chain.ns.set_enabled(True)
    # Slope_HighFreq_Boost
    chain.set_ui_gains([0, 0, 2, 4, 6, 8, 10, 12, 14, 16, 16, 16, 16, 16, 16, 16])
    
    y = chain.process_block(x)
    
    # Analyze steady state (last 0.2 seconds)
    y_steady = y[-int(SAMPLE_RATE * 0.2):]
    
    # Compute FFT
    N = len(y_steady)
    w = np.hanning(N)
    Y = np.fft.rfft(y_steady * w)
    Y_mag = np.abs(Y)
    
    # Find fundamental bin
    freqs = np.fft.rfftfreq(N, 1.0/SAMPLE_RATE)
    fund_bin = np.argmin(np.abs(freqs - freq))
    
    # Measure fundamental amplitude (sum of bins around the fundamental peak to account for window leakage)
    fund_mag = np.sum(Y_mag[fund_bin-3:fund_bin+4])
    
    # Measure harmonics (2nd, 3rd, 4th, 5th)
    harmonic_mags = []
    for h in [2, 3, 4, 5]:
        h_freq = freq * h
        if h_freq < SAMPLE_RATE / 2:
            h_bin = np.argmin(np.abs(freqs - h_freq))
            h_mag = np.sum(Y_mag[h_bin-3:h_bin+4])
            harmonic_mags.append(h_mag)
            
    if fund_mag > 1e-12:
        thd = np.sqrt(np.sum(np.array(harmonic_mags)**2)) / fund_mag
    else:
        thd = 0.0
        
    peak_out = np.max(np.abs(y_steady))
    return thd * 100.0, peak_out

print("="*60)
print("   Testing Harmonic Distortion (THD) on High Frequencies")
print("="*60)

for freq in [1000, 2000, 3000, 4000, 6000]:
    for amp in [-35.0, -25.0, -15.0]:
        thd_val, peak_out = measure_thd(freq, amp)
        print(f"Freq: {freq:<5} Hz | Input: {amp:<5} dBFS | Output Peak: {peak_out:.4f} | THD: {thd_val:.2f}%")

print("="*60)
