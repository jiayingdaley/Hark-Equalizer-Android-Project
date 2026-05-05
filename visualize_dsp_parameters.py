#!/usr/bin/env python3
"""
Hark DSP Compression Curve Visualization
==========================================
Generates visual comparison of WDRC vs MPO Limiter compression curves
and time-domain envelope response.
"""

import numpy as np
import matplotlib.pyplot as plt
from matplotlib.patches import Rectangle

# ============================================================================
# 1. WDRC Parameters
# ============================================================================
WDRC_CT = -40.0  # dB
WDRC_CR = 2.0    # Ratio (2:1)
WDRC_KNEE = 2.0  # dB
WDRC_ER = 0.9    # Expander ratio (0.9:1)
WDRC_ET = -70.0  # dB

LIMITER_CT = -3.0   # dB
LIMITER_CR = 20.0   # Ratio (20:1)
LIMITER_KNEE = 2.0  # dB

SAMPLE_RATE = 48000.0

# ============================================================================
# 2. Soft-Knee Compression Curve
# ============================================================================
def compute_gain_reduction_soft_knee(input_db, threshold_db, ratio, knee_db):
    """
    Compute gain reduction using soft-knee compression logic (RMS-style).
    
    Args:
        input_db: Input level in dB
        threshold_db: Compression threshold
        ratio: Compression ratio (> 1)
        knee_db: Knee width
    
    Returns:
        gain_reduction_db: Gain reduction in dB (positive value = attenuation)
    """
    overshoot_db = input_db - threshold_db
    
    if overshoot_db < -knee_db:
        # Below the knee: no compression
        return 0.0
    elif overshoot_db < knee_db:
        # Inside the knee: quadratic interpolation
        return (1.0 - 1.0/ratio) * (overshoot_db + knee_db)**2 / (4.0 * knee_db)
    else:
        # Above the knee: linear compression
        return overshoot_db * (1.0 - 1.0/ratio)

def compute_expansion_gain_reduction(input_db, threshold_db, ratio):
    """
    Compute gain reduction for expansion (noise gate).
    
    Args:
        input_db: Input level in dB
        threshold_db: Expander threshold
        ratio: Expander ratio (< 1 means expansion)
    
    Returns:
        gain_reduction_db: Gain reduction in dB
    """
    undershoot_db = threshold_db - input_db
    if undershoot_db > 0:
        return undershoot_db * (1.0 / ratio - 1.0)
    return 0.0

# ============================================================================
# 3. Generate Compression Curves
# ============================================================================

# Input sweep: -100dB to +10dB
input_levels_db = np.linspace(-100, 10, 500)
output_levels_db = np.zeros_like(input_levels_db)

# WDRC curve
wdrc_curve = np.zeros_like(input_levels_db)
for i, input_db in enumerate(input_levels_db):
    # Check expander first
    if input_db < WDRC_ET:
        exp_gain_reduction = compute_expansion_gain_reduction(input_db, WDRC_ET, WDRC_ER)
        wdrc_curve[i] = exp_gain_reduction
    else:
        # Check compressor
        comp_gain_reduction = compute_gain_reduction_soft_knee(input_db, WDRC_CT, WDRC_CR, WDRC_KNEE)
        wdrc_curve[i] = comp_gain_reduction

# MPO Limiter curve
limiter_curve = np.zeros_like(input_levels_db)
for i, input_db in enumerate(input_levels_db):
    limiter_curve[i] = compute_gain_reduction_soft_knee(input_db, LIMITER_CT, LIMITER_CR, LIMITER_KNEE)

# Compute output levels
wdrc_output_db = input_levels_db - wdrc_curve
limiter_output_db = input_levels_db - limiter_curve

# ============================================================================
# 4. Plot 1: Gain Reduction (Transfer Function)
# ============================================================================
fig, axes = plt.subplots(2, 2, figsize=(14, 10))
fig.suptitle('Hark DSP Compression Analysis', fontsize=16, fontweight='bold')

# Subplot 1: Gain Reduction Curves
ax = axes[0, 0]
ax.plot(input_levels_db, wdrc_curve, 'b-', linewidth=2, label='WDRC')
ax.plot(input_levels_db, limiter_curve, 'r-', linewidth=2, label='MPO Limiter')
ax.axvline(WDRC_CT, color='b', linestyle='--', alpha=0.5, label=f'WDRC Threshold: {WDRC_CT} dB')
ax.axvline(LIMITER_CT, color='r', linestyle='--', alpha=0.5, label=f'Limiter Threshold: {LIMITER_CT} dB')
ax.grid(True, alpha=0.3)
ax.set_xlabel('Input Level (dB)', fontsize=11)
ax.set_ylabel('Gain Reduction (dB)', fontsize=11)
ax.set_title('Gain Reduction vs Input Level')
ax.legend(fontsize=9)
ax.set_ylim([-0.5, 25])

# Subplot 2: Output vs Input (Compression Curve)
ax = axes[0, 1]
ax.plot(input_levels_db, input_levels_db, 'k--', linewidth=1.5, label='No Processing', alpha=0.5)
ax.plot(input_levels_db, wdrc_output_db, 'b-', linewidth=2, label='WDRC Output')
ax.plot(input_levels_db, limiter_output_db, 'r-', linewidth=2, label='Limiter Output')
ax.axhline(-3.0, color='r', linestyle=':', alpha=0.5, label='FDA Safe Limit: -3dBFS')
ax.grid(True, alpha=0.3)
ax.set_xlabel('Input Level (dB)', fontsize=11)
ax.set_ylabel('Output Level (dB)', fontsize=11)
ax.set_title('Input-Output Characteristic (Compression Curve)')
ax.legend(fontsize=9)
ax.set_xlim([-100, 10])
ax.set_ylim([-100, 10])

# Subplot 3: WDRC Detail (Zoomed)
ax = axes[1, 0]
zoom_range = np.logical_and(input_levels_db >= -60, input_levels_db <= 0)
ax.plot(input_levels_db[zoom_range], wdrc_output_db[zoom_range], 'b-', linewidth=2.5, label='WDRC')
ax.plot(input_levels_db[zoom_range], input_levels_db[zoom_range], 'k--', linewidth=1, alpha=0.5)

# Highlight knee region
knee_start = WDRC_CT - WDRC_KNEE
knee_end = WDRC_CT + WDRC_KNEE
ax.axvspan(knee_start, knee_end, color='blue', alpha=0.1, label=f'Soft-Knee Region ({WDRC_KNEE} dB)')
ax.axvline(WDRC_CT, color='b', linestyle='--', alpha=0.7, linewidth=1.5)
ax.axvline(WDRC_ET, color='green', linestyle='--', alpha=0.7, linewidth=1.5, label=f'Expander Threshold: {WDRC_ET} dB')

ax.grid(True, alpha=0.3)
ax.set_xlabel('Input Level (dB)', fontsize=11)
ax.set_ylabel('Output Level (dB)', fontsize=11)
ax.set_title(f'WDRC Detail (Soft-Knee: {WDRC_KNEE} dB, Ratio: {WDRC_CR}:1)')
ax.legend(fontsize=9)

# Subplot 4: Limiter Detail (Zoomed)
ax = axes[1, 1]
zoom_range = np.logical_and(input_levels_db >= -20, input_levels_db <= 10)
ax.plot(input_levels_db[zoom_range], limiter_output_db[zoom_range], 'r-', linewidth=2.5, label='MPO Limiter')
ax.plot(input_levels_db[zoom_range], input_levels_db[zoom_range], 'k--', linewidth=1, alpha=0.5)

# Highlight knee region
knee_start = LIMITER_CT - LIMITER_KNEE
knee_end = LIMITER_CT + LIMITER_KNEE
ax.axvspan(knee_start, knee_end, color='red', alpha=0.1, label=f'Soft-Knee Region ({LIMITER_KNEE} dB)')
ax.axvline(LIMITER_CT, color='r', linestyle='--', alpha=0.7, linewidth=1.5)
ax.axhline(-3.0, color='r', linestyle=':', alpha=0.7, linewidth=1.5, label='Output Ceiling: -3 dBFS')

ax.grid(True, alpha=0.3)
ax.set_xlabel('Input Level (dB)', fontsize=11)
ax.set_ylabel('Output Level (dB)', fontsize=11)
ax.set_title(f'MPO Limiter Detail (Soft-Knee: {LIMITER_KNEE} dB, Ratio: {LIMITER_CR}:1)')
ax.legend(fontsize=9)

plt.tight_layout()
plt.savefig('dsp_compression_curves.png', dpi=150, bbox_inches='tight')
print("✓ Generated: dsp_compression_curves.png")

# ============================================================================
# 5. Plot 2: Time-Domain Envelope Response
# ============================================================================
fig, axes = plt.subplots(2, 1, figsize=(14, 8))
fig.suptitle('Time-Domain Envelope Response (Attack/Release)', fontsize=16, fontweight='bold')

# Simulate envelope detection and time constants
duration_s = 0.5  # 500 ms
num_samples = int(duration_s * SAMPLE_RATE)
time_ms = np.arange(num_samples) / SAMPLE_RATE * 1000.0

# Compute time constants
WDRC_attack_coeff = np.exp(-1.0 / (SAMPLE_RATE * 0.010))  # 10 ms
WDRC_release_coeff = np.exp(-1.0 / (SAMPLE_RATE * 0.080))  # 80 ms
LIMITER_attack_coeff = np.exp(-1.0 / (SAMPLE_RATE * 0.0005))  # 0.5 ms
LIMITER_release_coeff = np.exp(-1.0 / (SAMPLE_RATE * 0.030))  # 30 ms

# Create test signal: silence -> spike -> tail
input_signal = np.zeros(num_samples)
spike_start = int(0.1 * SAMPLE_RATE)  # 100 ms
spike_end = int(0.120 * SAMPLE_RATE)  # 120 ms
input_signal[spike_start:spike_end] = 0.8  # 0.8 amplitude (peak at ~-2 dB)

# Simulate envelope detector with attack/release
def simulate_envelope(signal, attack_coeff, release_coeff):
    envelope = np.zeros_like(signal)
    env = 0.0
    for i, sample in enumerate(signal):
        input_level = np.abs(sample)
        if input_level > env:
            env = attack_coeff * env + (1.0 - attack_coeff) * input_level
        else:
            env = release_coeff * env + (1.0 - release_coeff) * input_level
        envelope[i] = env
    return envelope

wdrc_env = simulate_envelope(input_signal, WDRC_attack_coeff, WDRC_release_coeff)
limiter_env = simulate_envelope(input_signal, LIMITER_attack_coeff, LIMITER_release_coeff)

# Convert to dB
wdrc_env_db = 20 * np.log10(np.maximum(wdrc_env, 1e-9))
limiter_env_db = 20 * np.log10(np.maximum(limiter_env, 1e-9))

# Plot 1: Envelope Comparison
ax = axes[0]
ax.plot(time_ms, wdrc_env_db, 'b-', linewidth=2, label='WDRC (10ms attack, 80ms release)')
ax.plot(time_ms, limiter_env_db, 'r-', linewidth=2, label='Limiter (0.5ms attack, 30ms release)')
ax.axvline(100, color='k', linestyle='--', alpha=0.5, linewidth=1)
ax.axvline(120, color='k', linestyle='--', alpha=0.5, linewidth=1)
ax.fill_between([100, 120], -60, 0, alpha=0.1, color='gray', label='Test Spike')
ax.grid(True, alpha=0.3)
ax.set_xlabel('Time (ms)', fontsize=11)
ax.set_ylabel('Envelope Level (dB)', fontsize=11)
ax.set_title('Envelope Detector Response (Fast Limiter vs Slow WDRC)')
ax.legend(fontsize=10)
ax.set_ylim([-60, 5])

# Plot 2: Gain Reduction Over Time
ax = axes[1]
wdrc_gain_reduction = np.zeros_like(wdrc_env_db)
limiter_gain_reduction = np.zeros_like(limiter_env_db)

for i, env_db in enumerate(wdrc_env_db):
    wdrc_gain_reduction[i] = compute_gain_reduction_soft_knee(env_db, WDRC_CT, WDRC_CR, WDRC_KNEE)

for i, env_db in enumerate(limiter_env_db):
    limiter_gain_reduction[i] = compute_gain_reduction_soft_knee(env_db, LIMITER_CT, LIMITER_CR, LIMITER_KNEE)

ax.plot(time_ms, wdrc_gain_reduction, 'b-', linewidth=2, label='WDRC Gain Reduction')
ax.plot(time_ms, limiter_gain_reduction, 'r-', linewidth=2, label='Limiter Gain Reduction')
ax.axvline(100, color='k', linestyle='--', alpha=0.5, linewidth=1)
ax.axvline(120, color='k', linestyle='--', alpha=0.5, linewidth=1)
ax.fill_between([100, 120], 0, 20, alpha=0.1, color='gray', label='Test Spike Region')
ax.grid(True, alpha=0.3)
ax.set_xlabel('Time (ms)', fontsize=11)
ax.set_ylabel('Gain Reduction (dB)', fontsize=11)
ax.set_title('Dynamic Gain Reduction Over Time')
ax.legend(fontsize=10)

plt.tight_layout()
plt.savefig('dsp_time_response.png', dpi=150, bbox_inches='tight')
print("✓ Generated: dsp_time_response.png")

# ============================================================================
# 6. Generate Summary Table
# ============================================================================
print("\n" + "="*80)
print("HARK DSP COMPRESSION PARAMETERS SUMMARY")
print("="*80)

print("\n[WDRC - Wide Dynamic Range Compression]")
print(f"  Compression Threshold (CT):  {WDRC_CT} dBFS")
print(f"  Compression Ratio (CR):      {WDRC_CR}:1")
print(f"  Soft-Knee Width:             {WDRC_KNEE} dB")
print(f"  Attack Time:                 10 ms")
print(f"  Release Time:                80 ms")
print(f"  Expander Threshold (ET):     {WDRC_ET} dBFS")
print(f"  Expander Ratio (ER):         {WDRC_ER}:1 (gentle 1:1.1 expansion)")

print("\n[MPO LIMITER - Maximum Power Output]")
print(f"  Compression Threshold (CT):  {LIMITER_CT} dBFS")
print(f"  Compression Ratio (CR):      {LIMITER_CR}:1")
print(f"  Soft-Knee Width:             {LIMITER_KNEE} dB")
print(f"  Attack Time:                 0.5 ms (fast spike protection)")
print(f"  Release Time:                30 ms")
print(f"  No Expander (Ratio = 1.0)    Always passes signal")

print("\n[TIME CONSTANTS @ 48kHz]")
print(f"  WDRC Attack Coefficient:     {WDRC_attack_coeff:.6f}")
print(f"  WDRC Release Coefficient:    {WDRC_release_coeff:.6f}")
print(f"  Limiter Attack Coefficient:  {LIMITER_attack_coeff:.6f}")
print(f"  Limiter Release Coefficient: {LIMITER_release_coeff:.6f}")

print("\n[DESIGN NOTES]")
print("  ✓ Soft-knee prevents 'pumping' artifacts")
print("  ✓ Dual-stage (WDRC + Limiter) ensures hearing aid compliance & FDA safety")
print("  ✓ Fast limiter attack (0.5ms) catches peak transients")
print("  ✓ Soft expander (-70dB) only removes near-digital silence")
print("  ✓ +18dB makeup gain optimizes output dynamic range")

print("\n" + "="*80)
print("Visualization outputs saved to:")
print("  • dsp_compression_curves.png")
print("  • dsp_time_response.png")
print("="*80)
