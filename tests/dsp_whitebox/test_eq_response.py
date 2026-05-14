"""
test_eq_response.py
===================
Verify if UI EQ adjustments (16-band) correctly reflect in the 8-band WDRC output.
Tests if WDRC is "cancelling out" the EQ or if it's applying correctly.
"""
import math
import numpy as np
import matplotlib.pyplot as plt
import os
import json

from test_signal_chain import FullChain

SAMPLE_RATE = 48000.0
REPORT_DIR = os.path.join(os.path.dirname(__file__), "report_figures")

def test_eq_audibility():
    """
    Test 1: Set 1kHz band to +20dB, others 0dB. 
    Measure 1kHz sine output vs baseline.
    """
    chain = FullChain(SAMPLE_RATE)
    n = int(SAMPLE_RATE * 0.2)
    t = np.arange(n) / SAMPLE_RATE
    x = (10**(-40/20) * np.sin(2 * math.pi * 1000.0 * t)).astype(np.float32) # Quiet signal to avoid WDRC limiting
    
    # Baseline (All 0dB)
    chain.reset()
    chain.set_ui_gains([0.0] * 16)
    y_base = chain.process_block(x)
    rms_base = 20 * math.log10(np.sqrt(np.mean(y_base[int(n/2):]**2)))
    
    # Boost 1kHz (UI Band 6 & 7 map to Internal Band 3)
    chain.reset()
    gains = [0.0] * 16
    gains[6] = 20.0; gains[7] = 20.0
    chain.set_ui_gains(gains)
    y_boost = chain.process_block(x)
    rms_boost = 20 * math.log10(np.sqrt(np.mean(y_boost[int(n/2):]**2)))
    
    diff = rms_boost - rms_base
    print(f"    EQ Audibility Test (1kHz +20dB): Gain Diff = {diff:.2f}dB")
    
    # With -40dBFS input and -30dB threshold, the +20dB boost will push it to -20dBFS.
    # WDRC Ratio is 1.2:1. 
    # Input excess = 10dB. Output excess = 10 / 1.2 = 8.33dB.
    # Net gain should be around +18dB (due to WDRC) or similar.
    assert diff > 5.0, f"EQ boost ignored or cancelled by WDRC! Only got {diff:.2f}dB change."

if __name__ == "__main__":
    test_eq_audibility()
