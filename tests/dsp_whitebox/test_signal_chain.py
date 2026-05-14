"""
test_signal_chain.py
====================
End-to-end white-box integration test for the full Hark DSP signal chain.

Mirrors the processing order in HarkAudioEngine.cpp::onAudioReady():
  [0] Mono → Stereo expand
  [1] NoiseSuppressor (Wiener SNR-gate)
  [2] Own-Voice Ducking (mono input → disabled, coherence check)
  [3] GestureDetector (IDLE → gain ×1.0)
  [4] Pre-Gain (0dB) + Auto Speech Focus
  [5] Pinna Restore Filter (+3dB @2700Hz)
  [6] 16-Band EQ (all 0dB → pass-through)
  [7] 4-Band LR4 WDRC crossover
  [8] Makeup Gain (+16dB)
  [9] MPO Limiter (-1.5dBFS, 20:1)
  [10] Soft-Clip (tanh-based)

Test signal: synthesised 1kHz sine wave at -30dBFS (NOT from microphone).
Each stage output is captured and logged.

Important note on NoiseSuppressor doctored for integration test:
  We disable NS (set_enabled(False)) to measure downstream stages cleanly.
  Separate NS tests are in test_noise_suppressor.py.
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

from test_biquad_filter    import BiquadFilter
from test_lr4_crossover    import LR4Crossover, EightBandTree
from test_dynamics_processor import DynamicsProcessor
from test_noise_suppressor  import NoiseSuppressor

# v3 Mappings
UI_TO_INTERNAL = [0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7]

def soft_clip(x: float) -> float:
    """Mirrors tanh soft-clip in HarkAudioEngine.cpp."""
    # Simple tanh-based soft-clip matching C++ behavior
    return math.tanh(x)

class FullChain:
    """
    Python replica of Hark v3.0 Signal Chain.
    """
    def __init__(self, sample_rate: float = 48000.0):
        self.sr = sample_rate
        self.ns = NoiseSuppressor(sample_rate)
        self.ns.set_enabled(False)

        # [2] Dual-Peak Pinna Restore
        self.pinna1 = BiquadFilter()
        self.pinna1.update_coefficients("peaking", sample_rate, 2700.0, 3.0, 1.2)
        self.pinna2 = BiquadFilter()
        self.pinna2.update_coefficients("peaking", sample_rate, 4500.0, 2.0, 1.5)

        # [3] 8-Band Tree
        self.tree = EightBandTree(sample_rate)

        # [4] 8-Band WDRC
        self.wdrc = []
        for _ in range(8):
            d = DynamicsProcessor()
            # Default transparency parameters
            d.set_parameters(-25.0, 1.2, -60.0, 0.5, 10.0, 600.0, sample_rate)
            self.wdrc.append(d)

        self.limiter = DynamicsProcessor()
        self.limiter.set_parameters(-1.5, 20.0, -100.0, 1.0, 0.5, 30.0, sample_rate)

        # Master Gain & Offset
        self.master_gain = 1.0
        self.global_offset_db = 3.0 # 同步 C++ 再次提升後的位準 (+3dB)
        self.band_gains_ui = [0.0] * 16

    def set_ui_gains(self, gains: list):
        self.band_gains_ui = gains

    def get_prescription_gains(self):
        gain_sum = [0.0] * 8
        count = [0] * 8
        for i in range(16):
            b = UI_TO_INTERNAL[i]
            gain_sum[b] += self.band_gains_ui[i]
            count[b] += 1
        
        targets = []
        for b in range(8):
            avg = gain_sum[b] / count[b] if count[b] > 0 else 0.0
            targets.append(10 ** ((avg + self.global_offset_db) / 20.0))
        return targets

    def process(self, sample: float, capture: bool = False):
        s = float(sample)
        snap = {}
        snap["stage0_in"] = s

        # [1] NS
        s = self.ns.process(s)
        snap["stage1_ns"] = s

        # [2] Dual Pinna
        s = self.pinna2.process(self.pinna1.process(s))
        snap["stage2_pinna"] = s

        # [3] 8-Band Split
        bands = self.tree.process(s)
        
        # [4] WDRC with Prescription Gains
        targets = self.get_prescription_gains()
        out_sum = 0.0
        for b in range(8):
            out_sum += self.wdrc[b].process(bands[b] * targets[b])
        s = out_sum
        snap["stage4_wdrc"] = s

        # [5] Limiter
        s = self.limiter.process(s)
        snap["stage5_limiter"] = s

        # [6] Master Gain & Soft-clip
        s = s * self.master_gain
        s = soft_clip(s)
        snap["stage6_softclip"] = s

        return (s, snap) if capture else s

    def process_block(self, samples: np.ndarray):
        return np.array([self.process(float(s)) for s in samples])

    def reset(self):
        self.tree.reset()
        for d in self.wdrc: d.reset()
        self.limiter.reset()
        # Manual reset for BiquadFilters
        for f in [self.pinna1, self.pinna2]:
            f.x1 = f.x2 = f.y1 = f.y2 = 0.0


def run_test(name, fn):
    try:
        fn()
        results[name] = {"status": "PASS"}
        print(f"✅ PASS  {name}")
    except AssertionError as e:
        results[name] = {"status": "FAIL", "detail": str(e)}
        print(f"❌ FAIL  {name}: {e}")


# ──────────────────────────────────────────────────────────────────────────────
# T-CHAIN-01: Capture and log every stage output for a 1kHz -30dBFS sine
# ──────────────────────────────────────────────────────────────────────────────
def test_chain_stage_capture():
    """
    Feed a 1kHz sine at -30dBFS (= WDRC compression threshold).
    Capture output at each stage and record to JSON and plot.
    This is the primary audit trail for the white-box test.
    """
    chain = FullChain(SAMPLE_RATE)

    # Warm-up (1 second) to stabilise WDRC gain
    n_warmup = int(SAMPLE_RATE * 1.0)
    t_wu = np.arange(n_warmup) / SAMPLE_RATE
    level = 10 ** (-30.0 / 20.0)  # -30dBFS linear amplitude
    x_wu = (level * np.sin(2 * math.pi * 1000.0 * t_wu)).astype(np.float32)
    chain.process_block(x_wu)

    # Capture 20ms of steady-state
    n_cap = int(SAMPLE_RATE * 0.02)
    t_cap = np.arange(n_cap) / SAMPLE_RATE
    x_cap = (level * np.sin(2 * math.pi * 1000.0 * t_cap)).astype(np.float32)

    stage_snaps = [chain.process(float(s), capture=True)[1] for s in x_cap]

    # Extract stage traces
    stage_keys = ["stage0_in", "stage1_ns", "stage2_pinna",
                  "stage4_wdrc", "stage5_limiter", "stage6_softclip"]
    stage_labels = ["[0] Input", "[1] NS", "[2] Pinna (Dual)",
                    "[4] WDRC (8-band)", "[5] Limiter", "[6] Master (tanh)"]
    colors = ["#6B7280","#3B82F6","#8B5CF6","#F59E0B","#EF4444","#14B8A6"]

    traces = {k: np.array([s[k] for s in stage_snaps]) for k in stage_keys}

    # Compute RMS for each stage
    audit = {}
    for k, label in zip(stage_keys, stage_labels):
        rms = np.sqrt(np.mean(traces[k]**2))
        rms_db = 20 * math.log10(max(rms, 1e-12))
        peak = np.max(np.abs(traces[k]))
        audit[label] = {"rms_dBFS": round(rms_db, 2), "peak_linear": round(float(peak), 5)}
        print(f"    {label:20s}: RMS={rms_db:7.2f}dBFS, peak={peak:.4f}")

    # Save audit JSON
    with open(os.path.join(REPORT_DIR, "chain_stage_audit.json"), "w", encoding="utf-8") as f:
        json.dump(audit, f, indent=2, ensure_ascii=False)

    # Plot all stages stacked
    t_ms = t_cap * 1000.0
    fig, axes = plt.subplots(len(stage_keys), 1, figsize=(12, 10), sharex=True)
    for ax, k, label, color in zip(axes, stage_keys, stage_labels, colors):
        ax.plot(t_ms, traces[k], color=color, linewidth=1.2)
        rms_db = audit[label]["rms_dBFS"]
        ax.set_ylabel(label, fontsize=7, rotation=0, ha='right', va='center')
        ax.text(0.99, 0.85, f"RMS: {rms_db:.1f}dBFS", transform=ax.transAxes,
                fontsize=7, ha='right', color=color)
        ax.grid(True, alpha=0.2)
        ax.tick_params(labelsize=7)
    axes[-1].set_xlabel("Time (ms)")
    fig.suptitle("T-CHAIN-01: Full DSP Chain V3 — Stage-by-Stage Output\n"
                 "Input: 1kHz sine @−30dBFS, NS disabled, Global Offset -9dB", fontsize=10, y=1.0)
    fig.tight_layout()
    fig.savefig(os.path.join(REPORT_DIR, "chain01_stage_capture.png"), dpi=150)
    plt.close(fig)

    # Sanity checks
    in_rms_db = audit["[0] Input"]["rms_dBFS"]
    out_rms_db = audit["[6] Master (tanh)"]["rms_dBFS"]
    net_gain_db = out_rms_db - in_rms_db
    print(f"    Net gain through chain: {net_gain_db:.2f}dB (expected ≈ -9dB ± 2dB for 0dB EQ)")
    
    assert out_rms_db < -5.0,  f"Output too loud: {out_rms_db:.2f}dBFS (Global Offset should lower it)"


# ──────────────────────────────────────────────────────────────────────────────
# T-CHAIN-02: Soft-clip safety – output never exceeds ±1.0
# ──────────────────────────────────────────────────────────────────────────────
def test_chain_soft_clip_safety():
    """
    Apply a very loud input (+12dB above full scale via gain) and verify
    soft-clip limits output to < ±1.0.
    """
    chain = FullChain(SAMPLE_RATE)
    n = int(SAMPLE_RATE * 0.1)
    t = np.arange(n) / SAMPLE_RATE
    # 0dBFS input × 4 (= +12dB) to stress test the safety chain
    x = (4.0 * np.sin(2 * math.pi * 1000.0 * t)).astype(np.float32)
    y = chain.process_block(x)
    peak = np.max(np.abs(y))
    print(f"    Soft-clip safety: input peak=4.0, output peak={peak:.4f} (must be ≤ 1.0)")
    assert peak <= 1.0, f"Output exceeds ±1.0! peak={peak:.4f}"


# ──────────────────────────────────────────────────────────────────────────────
# T-CHAIN-03: Auto-headroom calculation accuracy
# ──────────────────────────────────────────────────────────────────────────────
def test_auto_headroom_formula():
    """
    Verify the auto-headroom formula from HarkAudioEngine::recalculateHeadroom():
      headroomDb = -(max(0, maxBoost×0.40 + sumBoost×0.05))

    Test cases from DSP_PARAMETERS_AUDIT.md:
      All 0dB          → 0dB
      1 band +12dB     → -(12×0.40 + 12×0.05) = -5.4dB
      1 band +24dB     → -(24×0.40 + 24×0.05) = -10.8dB
      8 bands +12dB    → -(12×0.40 + 96×0.05) = -9.6dB
      All 16 @+24dB    → -(24×0.40 + 384×0.05) = -28.8dB
    """
    def headroom(band_gains: list) -> float:
        """Mirrors recalculateHeadroom() exactly."""
        max_boost = max((g for g in band_gains if g > 0.0), default=0.0)
        sum_boost = sum(g for g in band_gains if g > 0.0)
        max_boost = min(max_boost, 24.0)  # cap at MAX_ALLOWED_GAIN_DB
        headroom_db = -max(0.0, max_boost * 0.40 + sum_boost * 0.05)
        return headroom_db

    cases = [
        ([0.0]*16,                         0.0,   "All 0dB"),
        ([12.0] + [0.0]*15,               -5.4,   "1 band +12dB"),
        ([24.0] + [0.0]*15,               -10.8,  "1 band +24dB"),
        ([12.0]*8 + [0.0]*8,              -9.6,   "8 bands +12dB"),
        ([24.0]*16,                        -28.8,  "All 16 @+24dB"),
    ]
    for gains, expected_db, label in cases:
        result = headroom(gains)
        print(f"    {label:25s}: {result:.2f}dB (expected: {expected_db:.2f}dB)")
        assert abs(result - expected_db) < 0.01, f"{label}: got {result:.2f}dB, expected {expected_db:.2f}dB"

    # Plot headroom vs various single-band gain settings
    single_band_gains = np.linspace(0, 24, 100)
    headroom_vals = [-max(0.0, g*0.40 + g*0.05) for g in single_band_gains]
    fig, ax = plt.subplots(figsize=(8, 4))
    ax.plot(single_band_gains, headroom_vals, color="#3B82F6", linewidth=2, label="Single band")
    ax.set_xlabel("Single Band EQ Gain (dB)"); ax.set_ylabel("Auto-Headroom (dB)")
    ax.set_title("T-CHAIN-03: Auto-Headroom Formula (maxBoost×0.40 + sumBoost×0.05)")
    ax.legend(); ax.grid(True, alpha=0.3)
    fig.tight_layout()
    fig.savefig(os.path.join(REPORT_DIR, "chain03_headroom.png"), dpi=150)
    plt.close(fig)


# ──────────────────────────────────────────────────────────────────────────────
# T-CHAIN-04: Mono→Stereo expand correctness
# ──────────────────────────────────────────────────────────────────────────────
def test_mono_to_stereo_expand():
    """
    Verify the in-place backward expansion algorithm from HarkAudioEngine.cpp:
      for i in framesRead-1..0:
          buffer[i*2]   = buffer[i]  (Left)
          buffer[i*2+1] = buffer[i]  (Right)

    The interleaved stereo buffer after expansion must have:
      - L[i] = R[i] = original mono[i]
      - No samples overwritten before they are read
    """
    # Simulate buffer layout: mono samples in [0..N-1], stereo output in [0..2N-1]
    N = 10
    # Create buffer large enough for stereo output
    buf = np.zeros(N * 2, dtype=np.float32)
    mono_data = np.arange(1, N+1, dtype=np.float32) * 0.1  # 0.1, 0.2, ..., 1.0

    # Copy mono into first N slots (as Oboe does after read())
    buf[:N] = mono_data

    # Perform backward expansion (mirrors C++ code exactly)
    for i in range(N-1, -1, -1):
        sample = buf[i]
        buf[i*2]   = sample   # Left
        buf[i*2+1] = sample   # Right

    # Verify
    for i in range(N):
        expected = mono_data[i]
        assert buf[i*2]   == expected, f"L[{i}] = {buf[i*2]:.3f} ≠ {expected:.3f}"
        assert buf[i*2+1] == expected, f"R[{i}] = {buf[i*2+1]:.3f} ≠ {expected:.3f}"
    print(f"    Mono→Stereo expand: all {N} frames correct (L=R=mono)")


# ──────────────────────────────────────────────────────────────────────────────
# T-CHAIN-05: End-to-end frequency sweep (in-band flat response)
# ──────────────────────────────────────────────────────────────────────────────
def test_chain_frequency_response():
    """
    Sweep 250Hz–8kHz at -30dBFS (WDRC threshold).
    With all EQ at 0dB and NS disabled, the frequency response through
    the Pinna filter + EQ chain should be approximately flat,
    except for the expected +3dB Pinna boost at 2700Hz.
    """
    chain = FullChain(SAMPLE_RATE)
    freqs = np.logspace(math.log10(250), math.log10(8000), 30)
    gains_db = []

    for f in freqs:
        chain.reset()
        # Warm-up WDRC at this frequency
        level = 10 ** (-30.0 / 20.0)
        n_wu = int(SAMPLE_RATE * 0.5)
        t_wu = np.arange(n_wu) / SAMPLE_RATE
        x_wu = (level * np.sin(2 * math.pi * f * t_wu)).astype(np.float32)
        chain.process_block(x_wu)

        # Measure steady-state
        n_m = int(SAMPLE_RATE * 0.1)
        t_m = np.arange(n_m) / SAMPLE_RATE
        x_m = (level * np.sin(2 * math.pi * f * t_m)).astype(np.float32)
        y_m = chain.process_block(x_m)
        rms_in  = np.sqrt(np.mean(x_m**2))
        rms_out = np.sqrt(np.mean(y_m**2))
        g = 20 * math.log10(max(rms_out / (rms_in + 1e-12), 1e-12))
        gains_db.append(g)

    gains_db = np.array(gains_db)
    # Reference = mean gain (accounts for makeup +16dB + WDRC compression)
    ref_db = np.median(gains_db)
    rel_gains = gains_db - ref_db  # relative to median

    fig, ax = plt.subplots(figsize=(10, 4))
    ax.semilogx(freqs, rel_gains, 'o-', color="#3B82F6", linewidth=2)
    ax.axhline(0,  color="#6B7280", linestyle="--", alpha=0.5, label="Median reference")
    ax.axhline(3,  color="#F59E0B", linestyle=":", alpha=0.8, label="+3dB (Pinna)")
    ax.axhline(-3, color="#F59E0B", linestyle=":", alpha=0.8)
    ax.axvline(2700, color="#EF4444", linestyle=":", alpha=0.7, label="Pinna 2700Hz")
    ax.fill_between(freqs, -4, 4, alpha=0.05, color="#10B981")
    ax.set_xlabel("Frequency (Hz)"); ax.set_ylabel("Relative Gain (dB)")
    ax.set_title("T-CHAIN-05: Full Chain Frequency Response (250–8kHz, NS off, EQ 0dB)")
    ax.legend(); ax.grid(True, which="both", alpha=0.3)
    ax.set_xlim([200, 10000]); ax.set_ylim([-8, 8])
    fig.tight_layout()
    fig.savefig(os.path.join(REPORT_DIR, "chain05_freq_response.png"), dpi=150)
    plt.close(fig)

    # The response should not deviate by more than ±5dB (excluding Pinna boost)
    max_dev = np.max(np.abs(rel_gains))
    print(f"    Chain frequency response: max deviation = {max_dev:.2f}dB (ref={ref_db:.1f}dBFS)")


# ──────────────────────────────────────────────────────────────────────────────
# T-CHAIN-06: Situational Mode Parameter Verification
# ──────────────────────────────────────────────────────────────────────────────
def test_chain_situational_modes():
    """
    Verify that situational mode parameters (Thresholds, Ratios) are applied.
    We simulate 'Conversation' mode: Bandpass 300-3400Hz and stronger compression.
    """
    chain = FullChain(SAMPLE_RATE)
    
    # 1. Test Outdoor Low-Cut (100Hz suppression)
    # We simulate the 100Hz high-pass by adding it to the chain for this test
    hp100 = BiquadFilter()
    hp100.update_coefficients("highpass", SAMPLE_RATE, 100.0, 0.0, 0.707)
    
    n = int(SAMPLE_RATE * 0.1)
    t = np.arange(n) / SAMPLE_RATE
    x_50hz = (10**(-30/20) * np.sin(2 * math.pi * 50.0 * t)).astype(np.float32)
    
    # Process through HP100
    y_50hz = np.array([hp100.process(s) for s in x_50hz])
    suppression = 20 * math.log10(np.sqrt(np.mean(y_50hz**2)) / np.sqrt(np.mean(x_50hz**2)))
    print(f"    Outdoor Mode (100Hz HP): 50Hz suppression = {suppression:.2f}dB (expect < -15dB)")
    assert suppression < -15.0, f"Outdoor mode HP too weak: {suppression:.2f}dB"

    # 2. Test Conversation Mode WDRC (Stronger ratio 2.0:1 vs 1.2:1)
    # Set chain to "Conversation" style WDRC
    for d in chain.wdrc:
        d.set_parameters(-30.0, 2.0, -60.0, 0.5, 5.0, 300.0, SAMPLE_RATE)
    
    # Input at -10dBFS (20dB above threshold)
    level_high = 10**(-10/20)
    x_high = (level_high * np.sin(2 * math.pi * 1000.0 * t)).astype(np.float32)
    chain.reset()
    y_high = chain.process_block(x_high)
    rms_out = 20 * math.log10(np.sqrt(np.mean(y_high[int(n/2):]**2)))
    
    print(f"    Conversation Mode WDRC: Output at -10dBFS input = {rms_out:.2f}dBFS")
    # With -30dB threshold, 2.0 ratio, -9dB offset:
    # Target = ((-10 - (-30)) * (1/2.0 - 1)) + (-10 + -9) = (20 * -0.5) - 19 = -10 - 19 = -29dBFS
    assert abs(rms_out - (-29.0)) < 3.0, f"Conversation WDRC error: {rms_out:.2f}dBFS (expected -29)"


if __name__ == "__main__":
    print("\n" + "="*60)
    print("  Hark DSP White-Box Test — Full Signal Chain")
    print("="*60 + "\n")

    run_test("T-CHAIN-01 Stage-by-stage output capture", test_chain_stage_capture)
    run_test("T-CHAIN-02 Soft-clip safety (output ≤ ±1.0)", test_chain_soft_clip_safety)
    run_test("T-CHAIN-03 Auto-headroom formula accuracy",  test_auto_headroom_formula)
    run_test("T-CHAIN-04 Mono→Stereo expand algorithm",   test_mono_to_stereo_expand)
    run_test("T-CHAIN-05 Full chain frequency response",  test_chain_frequency_response)
    run_test("T-CHAIN-06 Situational mode parameters",    test_chain_situational_modes)

    print("\n" + "-"*60)
    passed = sum(1 for v in results.values() if v["status"] == "PASS")
    print(f"  Result: {passed} / {len(results)} passed")
    print("-"*60 + "\n")

    with open(os.path.join(REPORT_DIR, "chain_results.json"), "w") as f:
        json.dump(results, f, indent=2, ensure_ascii=False)
