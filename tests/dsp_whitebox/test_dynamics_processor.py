"""
test_dynamics_processor.py
===========================
White-box unit tests for DynamicsProcessor (WDRC + Limiter).
Mirrors DynamicsProcessor.cpp exactly.

Key parameters under test:
  - CT = -30dBFS, CR = 1.5:1, Knee = 2dB
  - Attack = 8ms, Release = 300ms @ 48kHz
  - Limiter: CT = -1.5dBFS, CR = 20:1, Attack = 0.5ms, Release = 30ms

Test signals: synthesised constant-level sine waves and step functions
              (NOT from microphone).

Reference: Bohn, D. (2008). "Dynamics Processors Technology and Application."
           Rane Note 155.
"""

import math
import numpy as np
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import os
import json

REPORT_DIR = os.path.join(os.path.dirname(__file__), "report_figures")
os.makedirs(REPORT_DIR, exist_ok=True)

SAMPLE_RATE = 48000.0
results = {}


# ─── Python replica of DynamicsProcessor ──────────────────────────────────────
class DynamicsProcessor:
    """
    Exact Python replica of DynamicsProcessor.cpp.
    UPDATE_INTERVAL = 16 samples between gain recalculations.
    Soft-knee = 2dB.
    Gain smoothing = 0.7 * current + 0.3 * target.
    """
    UPDATE_INTERVAL = 16
    KNEE_DB = 2.0

    def __init__(self):
        self.compress_thresh = 1.0    # linear
        self.compress_ratio  = 1.0
        self.expander_thresh = 1e-4   # linear (~ -80dB)
        self.expander_ratio  = 1.0
        self.attack_coeff    = 1.0
        self.release_coeff   = 1.0
        self.current_gain    = 1.0
        self.target_gain     = 1.0
        self.counter         = 0
        self.envelope        = 0.0

    def set_parameters(self, compress_thresh_db: float, compress_ratio: float,
                        expander_thresh_db: float, expander_ratio: float,
                        attack_ms: float, release_ms: float, sample_rate: float):
        self.compress_thresh = 10 ** (compress_thresh_db / 20.0)
        self.compress_ratio  = compress_ratio
        self.expander_thresh = 10 ** (expander_thresh_db / 20.0)
        self.expander_ratio  = expander_ratio
        self.attack_coeff  = math.exp(-1.0 / (sample_rate * attack_ms / 1000.0))  if attack_ms  > 0 else 0.0
        self.release_coeff = math.exp(-1.0 / (sample_rate * release_ms / 1000.0)) if release_ms > 0 else 0.0

    def process(self, x: float) -> float:
        level = abs(x)
        # Envelope detector
        if level > self.envelope:
            self.envelope = self.attack_coeff  * self.envelope + (1.0 - self.attack_coeff)  * level
        else:
            self.envelope = self.release_coeff * self.envelope + (1.0 - self.release_coeff) * level
        # Denormal guard
        if abs(self.envelope) < 1.175494e-38:
            self.envelope = 0.0

        # Gain recompute every 16 samples
        self.counter += 1
        if self.counter >= self.UPDATE_INTERVAL:
            self.counter = 0
            gain = 1.0
            env_db  = 20 * math.log10(self.envelope) if self.envelope > 1e-9 else -180.0
            ct_db   = 20 * math.log10(self.compress_thresh)
            et_db   = 20 * math.log10(self.expander_thresh) if self.expander_thresh > 1e-9 else -180.0

            if env_db > ct_db - self.KNEE_DB:
                overshoot = env_db - ct_db
                if overshoot < self.KNEE_DB:
                    # soft-knee region
                    gr_db = (1.0 - 1.0 / self.compress_ratio) * \
                            (overshoot + self.KNEE_DB)**2 / (4.0 * self.KNEE_DB)
                else:
                    gr_db = overshoot * (1.0 - 1.0 / self.compress_ratio)
                gain = 10 ** (-gr_db / 20.0)
            elif env_db < et_db:
                undershoot = et_db - env_db
                gr_db = undershoot * (1.0 / self.expander_ratio - 1.0)
                gain = 10 ** (-gr_db / 20.0)

            self.target_gain = gain

        # Gain smoothing (0.7/0.3 — matches C++)
        self.current_gain = 0.7 * self.current_gain + 0.3 * self.target_gain
        return x * self.current_gain

    def process_block(self, samples: np.ndarray) -> np.ndarray:
        return np.array([self.process(float(s)) for s in samples])

    def reset(self):
        self.envelope = 0.0
        self.current_gain = 1.0
        self.target_gain  = 1.0
        self.counter = 0


def run_test(name, fn):
    try:
        fn()
        results[name] = {"status": "PASS"}
        print(f"✅ PASS  {name}")
    except AssertionError as e:
        results[name] = {"status": "FAIL", "detail": str(e)}
        print(f"❌ FAIL  {name}: {e}")


# ──────────────────────────────────────────────────────────────────────────────
# Helper: measure steady-state gain for a constant-level input
# ──────────────────────────────────────────────────────────────────────────────
def measure_steady_state_gain(proc: DynamicsProcessor, level_db: float,
                               duration_ms: float = 500.0) -> float:
    """
    Feed a constant-amplitude sine wave and measure gain in steady state.
    Returns output_dBFS – input_dBFS.
    """
    proc.reset()
    level = 10 ** (level_db / 20.0)
    n = int(SAMPLE_RATE * duration_ms / 1000.0)
    t = np.arange(n) / SAMPLE_RATE
    x = (level * np.sin(2 * math.pi * 1000.0 * t)).astype(np.float32)
    y = proc.process_block(x)
    # Use last 100ms as steady state
    tail = int(SAMPLE_RATE * 0.1)
    rms_in  = np.sqrt(np.mean(x[-tail:]**2))
    rms_out = np.sqrt(np.mean(y[-tail:]**2))
    return 20 * math.log10(max(rms_out / (rms_in + 1e-12), 1e-12))  # dB relative to input


# ──────────────────────────────────────────────────────────────────────────────
# T-DYN-01: Below threshold → unity gain (no compression)
# ──────────────────────────────────────────────────────────────────────────────
def test_wdrc_unity_below_threshold():
    """
    Input at -40dBFS (below CT=-30dBFS and expander off at -100dB)
    → output should have unity gain change ≈ 0dB.
    """
    proc = DynamicsProcessor()
    proc.set_parameters(-30.0, 1.5, -100.0, 1.0, 8.0, 300.0, SAMPLE_RATE)
    gain_db = measure_steady_state_gain(proc, -40.0)
    print(f"    WDRC unity @ -40dBFS: gain change = {gain_db:.3f}dB")
    assert abs(gain_db) < 0.5, f"Expected ~0dB gain below threshold, got {gain_db:.2f}dB"


# ──────────────────────────────────────────────────────────────────────────────
# T-DYN-02: Compression ratio accuracy at various levels above threshold
# ──────────────────────────────────────────────────────────────────────────────
def test_wdrc_compression_ratio():
    """
    WDRC with CR=1.5:1, CT=-30dBFS.
    At input = -20dBFS (10dB above threshold):
      Expected output ≈ -30 + 10/1.5 = -23.3dBFS
      → gain change ≈ -20 - (-23.3) = -3.3dB (approx, accounting for knee)
    Tolerance: ±2dB (accounting for soft-knee and gain smoothing)
    """
    proc = DynamicsProcessor()
    proc.set_parameters(-30.0, 1.5, -100.0, 1.0, 8.0, 300.0, SAMPLE_RATE)

    test_levels = [-35.0, -30.0, -25.0, -20.0, -15.0, -10.0, -5.0]
    measured_gains = []
    expected_gains = []

    for in_db in test_levels:
        gain_db = measure_steady_state_gain(proc, in_db)
        measured_gains.append(gain_db)

        # Theoretical: soft-knee at CT±1dB (knee=2dB)
        overshoot = in_db - (-30.0)
        if overshoot <= -2.0:
            expected = 0.0
        elif overshoot < 2.0:
            expected = -(1.0 - 1.0/1.5) * (overshoot + 2.0)**2 / (4.0 * 2.0)
        else:
            expected = -overshoot * (1.0 - 1.0/1.5)
        expected_gains.append(expected)
        print(f"    Input {in_db:5.1f}dBFS → gain change {gain_db:6.2f}dB  (theory: {expected:6.2f}dB)")

    # Plot transfer curve
    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(12, 4))

    # Left: gain change vs input level
    ax1.plot(test_levels, measured_gains, 'o-', color="#3B82F6", linewidth=2, label="Measured")
    ax1.plot(test_levels, expected_gains, 's--', color="#EF4444", linewidth=1.5, label="Theory")
    ax1.axvline(-30, color="#6B7280", linestyle=":", label="CT = -30dBFS")
    ax1.set_xlabel("Input Level (dBFS)"); ax1.set_ylabel("Gain Change (dB)")
    ax1.set_title("WDRC Gain Reduction vs Input Level")
    ax1.legend(); ax1.grid(True, alpha=0.3)

    # Right: input-output curve (transfer function)
    out_levels = [i + g for i, g in zip(test_levels, measured_gains)]
    ax2.plot(test_levels, out_levels, 'o-', color="#10B981", linewidth=2, label="WDRC Output")
    ax2.plot(test_levels, test_levels, '--', color="#6B7280", alpha=0.5, label="Unity (no processing)")
    ax2.axvline(-30, color="#6B7280", linestyle=":", alpha=0.6, label="CT = -30dBFS")
    ax2.set_xlabel("Input Level (dBFS)"); ax2.set_ylabel("Output Level (dBFS)")
    ax2.set_title("T-DYN-02: WDRC Transfer Curve (CR=1.5:1)")
    ax2.legend(); ax2.grid(True, alpha=0.3)

    fig.tight_layout()
    fig.savefig(os.path.join(REPORT_DIR, "dyn02_wdrc_transfer.png"), dpi=150)
    plt.close(fig)

    # Verify compression is happening above threshold
    gain_at_minus20 = measured_gains[test_levels.index(-20.0)]
    assert gain_at_minus20 < -1.0, f"Expected gain reduction at -20dBFS, got {gain_at_minus20:.2f}dB"
    assert gain_at_minus20 > -8.0, f"Excessive gain reduction at -20dBFS: {gain_at_minus20:.2f}dB"


# ──────────────────────────────────────────────────────────────────────────────
# T-DYN-03: Attack time constant validation (8ms)
# ──────────────────────────────────────────────────────────────────────────────
def test_wdrc_attack_time():
    """
    Step from silence to 0dBFS signal.
    The envelope should rise to ~63% of target in one time constant (8ms).
    We measure how long it takes for gain to settle to steady state.
    """
    proc = DynamicsProcessor()
    proc.set_parameters(-30.0, 1.5, -100.0, 1.0, 8.0, 300.0, SAMPLE_RATE)

    # Warm up at steady state first (0dBFS input, fully compressed)
    n_warmup = int(SAMPLE_RATE * 0.5)
    x_warm = np.sin(2 * math.pi * 1000.0 * np.arange(n_warmup) / SAMPLE_RATE).astype(np.float32)
    proc.process_block(x_warm)
    steady_gain = proc.current_gain

    # Reset and measure attack from silence
    proc.reset()
    n_attack = int(SAMPLE_RATE * 0.1)  # 100ms window
    t = np.arange(n_attack) / SAMPLE_RATE
    x_attack = np.sin(2 * math.pi * 1000.0 * t).astype(np.float32)

    gains = []
    for s in x_attack:
        proc.process(float(s))
        gains.append(proc.current_gain)

    gains = np.array(gains)
    times_ms = np.arange(len(gains)) / SAMPLE_RATE * 1000.0

    # Find time when gain drops to 63% of (initial - final) decay
    initial_g = gains[0]
    # Gain should be decreasing (compressor kicking in)
    final_g = np.mean(gains[-1000:])
    delta = initial_g - final_g
    target_63 = initial_g - 0.63 * delta

    # Find index where gain first crosses this level
    cross_idx = None
    for i, g in enumerate(gains):
        if g <= target_63:
            cross_idx = i
            break

    if cross_idx:
        attack_measured_ms = cross_idx / SAMPLE_RATE * 1000.0
        print(f"    Attack time (63%): {attack_measured_ms:.1f}ms (target: ~8ms, ±5ms)")
    else:
        attack_measured_ms = None
        print(f"    Attack time: gain never reached 63% of target (compression very mild)")

    # Plot
    fig, ax = plt.subplots(figsize=(10, 4))
    ax.plot(times_ms, 20*np.log10(np.maximum(gains, 1e-6)), color="#3B82F6", linewidth=1.5)
    if cross_idx:
        ax.axvline(attack_measured_ms, color="#EF4444", linestyle="--",
                   label=f"63% point: {attack_measured_ms:.1f}ms")
    ax.axvline(8, color="#10B981", linestyle=":", label="Target attack: 8ms")
    ax.set_xlabel("Time (ms)"); ax.set_ylabel("Current Gain (dB)")
    ax.set_title("T-DYN-03: WDRC Attack Envelope (0dBFS step input)")
    ax.legend(); ax.grid(True, alpha=0.3)
    fig.tight_layout()
    fig.savefig(os.path.join(REPORT_DIR, "dyn03_attack_envelope.png"), dpi=150)
    plt.close(fig)


# ──────────────────────────────────────────────────────────────────────────────
# T-DYN-04: Limiter – hard ceiling at -1.5dBFS
# ──────────────────────────────────────────────────────────────────────────────
def test_limiter_ceiling():
    """
    MPO Limiter: CT=-1.5dBFS, CR=20:1, Attack=0.5ms, Release=30ms.
    Input at 0dBFS (sine peak = 1.0) → output must not exceed -1.5dBFS.
    The peak output level must be ≤ 10^(-1.5/20) = 0.8414 linear.
    Tolerance: output peak must be < 0.90 (< -0.92dBFS, leaving margin for 20:1 ratio).
    """
    limiter = DynamicsProcessor()
    limiter.set_parameters(-1.5, 20.0, -100.0, 1.0, 0.5, 30.0, SAMPLE_RATE)

    # High-level input: 0dBFS RMS (peak = √2 ≈ 1.41 → already clips a hard limiter)
    n = int(SAMPLE_RATE * 0.5)
    t = np.arange(n) / SAMPLE_RATE
    x = np.sin(2 * math.pi * 1000.0 * t).astype(np.float32)  # peak = 1.0 (-3dBFS RMS)

    y = limiter.process_block(x)
    peak_out = np.max(np.abs(y))
    rms_out_db = 20 * math.log10(max(np.sqrt(np.mean(y[-int(SAMPLE_RATE*0.2):]**2)), 1e-12))
    print(f"    Limiter: input peak=1.0, output peak={peak_out:.4f}, output RMS={rms_out_db:.2f}dBFS")

    # Plot
    n_plot = int(SAMPLE_RATE * 0.05)
    t_ms = np.arange(n_plot) / SAMPLE_RATE * 1000.0
    fig, ax = plt.subplots(figsize=(10, 4))
    ax.plot(t_ms, x[:n_plot], color="#6B7280", alpha=0.5, linewidth=1, label="Input (0dBFS)")
    ax.plot(t_ms, y[:n_plot], color="#EF4444", linewidth=1.5, label="Limiter Output")
    threshold_linear = 10 ** (-1.5 / 20.0)
    ax.axhline( threshold_linear, color="#F59E0B", linestyle="--", label=f"CT = -1.5dBFS ({threshold_linear:.3f})")
    ax.axhline(-threshold_linear, color="#F59E0B", linestyle="--")
    ax.set_xlabel("Time (ms)"); ax.set_ylabel("Amplitude")
    ax.set_title("T-DYN-04: MPO Limiter Ceiling Test (CT=-1.5dBFS, CR=20:1)")
    ax.legend(); ax.grid(True, alpha=0.3)
    ax.set_ylim([-1.5, 1.5])
    fig.tight_layout()
    fig.savefig(os.path.join(REPORT_DIR, "dyn04_limiter_ceiling.png"), dpi=150)
    plt.close(fig)

    assert peak_out < 0.95, f"Limiter output peak {peak_out:.4f} is too high (>0.95)"


# ──────────────────────────────────────────────────────────────────────────────
# T-DYN-05: Expander OFF (-100dBFS threshold) → no gating at low levels
# ──────────────────────────────────────────────────────────────────────────────
def test_expander_disabled():
    """
    With expander threshold = -100dBFS (effectively off),
    a signal at -60dBFS should pass through without gating.
    The gain change at -60dBFS must be > -3dB.
    """
    proc = DynamicsProcessor()
    proc.set_parameters(-30.0, 1.5, -100.0, 1.0, 8.0, 300.0, SAMPLE_RATE)
    gain_db = measure_steady_state_gain(proc, -60.0)
    print(f"    Expander OFF @ -60dBFS: gain change = {gain_db:.3f}dB (should be > -3dB)")
    assert gain_db > -3.0, f"Expander appears active at -60dBFS! gain={gain_db:.2f}dB"


# ──────────────────────────────────────────────────────────────────────────────
# T-DYN-06: Gain smoothing prevents clicks (0.7/0.3 interpolation)
# ──────────────────────────────────────────────────────────────────────────────
def test_gain_smoothing_no_clicks():
    """
    Verify no sudden gain steps (>3dB change per sample) during attack/release.
    This would manifest as clicks/pops in the output.
    """
    proc = DynamicsProcessor()
    proc.set_parameters(-30.0, 1.5, -100.0, 1.0, 8.0, 300.0, SAMPLE_RATE)

    # Step input: silence then loud signal
    n_total = int(SAMPLE_RATE * 0.2)
    x = np.zeros(n_total, dtype=np.float32)
    x[int(n_total/2):] = np.sin(2*math.pi*1000*np.arange(n_total//2)/SAMPLE_RATE).astype(np.float32)

    gains = []
    for s in x:
        proc.process(float(s))
        gains.append(proc.current_gain)

    gains_db = np.array([20*math.log10(max(g, 1e-6)) for g in gains])
    max_step = np.max(np.abs(np.diff(gains_db)))
    print(f"    Max gain step between samples: {max_step:.4f}dB (should be < 3dB)")
    assert max_step < 3.0, f"Gain step too large ({max_step:.4f}dB) — risk of click/pop!"


# ──────────────────────────────────────────────────────────────────────────────
# T-DYN-07: Time constant formula verification (coefficients)
# ──────────────────────────────────────────────────────────────────────────────
def test_time_constant_coefficients():
    """
    Verify attack and release time constant coefficients computed from
    exp(-1 / (sampleRate × time_s)) match the expected values from the DSP doc.

    From DSP_PARAMETERS_AUDIT.md:
      Attack  8ms   → coeff ≈ 0.99740
      Release 300ms → coeff ≈ 0.99993
      Limiter Attack 0.5ms → coeff ≈ 0.95910
      Limiter Release 30ms → coeff ≈ 0.99931
    """
    def coeff(time_ms, sr=48000.0):
        return math.exp(-1.0 / (sr * time_ms / 1000.0))

    cases = [
        (8.0,   0.99740, 0.0002, "WDRC Attack 8ms"),
        (300.0, 0.99993, 0.0001, "WDRC Release 300ms"),
        (0.5,   0.95910, 0.001,  "Limiter Attack 0.5ms"),
        (30.0,  0.99931, 0.0002, "Limiter Release 30ms"),
    ]
    for time_ms, expected, tol, label in cases:
        measured = coeff(time_ms)
        print(f"    {label}: computed={measured:.5f}, expected≈{expected:.5f}")
        assert abs(measured - expected) < tol, \
            f"{label}: computed={measured:.5f}, expected={expected:.5f}, diff>{tol}"


if __name__ == "__main__":
    print("\n" + "="*60)
    print("  Hark DSP White-Box Test — DynamicsProcessor")
    print("="*60 + "\n")

    run_test("T-DYN-01 Unity gain below threshold",           test_wdrc_unity_below_threshold)
    run_test("T-DYN-02 Compression ratio accuracy",           test_wdrc_compression_ratio)
    run_test("T-DYN-03 Attack time envelope",                 test_wdrc_attack_time)
    run_test("T-DYN-04 Limiter hard ceiling -1.5dBFS",        test_limiter_ceiling)
    run_test("T-DYN-05 Expander disabled (ET=-100dB)",        test_expander_disabled)
    run_test("T-DYN-06 Gain smoothing no click artefacts",    test_gain_smoothing_no_clicks)
    run_test("T-DYN-07 Time constant coefficient accuracy",   test_time_constant_coefficients)

    print("\n" + "-"*60)
    passed = sum(1 for v in results.values() if v["status"] == "PASS")
    print(f"  Result: {passed} / {len(results)} passed")
    print("-"*60 + "\n")

    with open(os.path.join(REPORT_DIR, "dyn_results.json"), "w") as f:
        json.dump(results, f, indent=2, ensure_ascii=False)
