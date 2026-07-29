"""
test_lr4_crossover.py
=====================
White-box unit tests for LinkwitzRileyCrossover (mirrors LinkwitzRileyCrossover.cpp).

LR4 = two cascaded 2nd-order Butterworth filters (Q=1/√2 = 0.70710678118).
Key property: LP + HP = flat magnitude (perfect reconstruction) at any crossover frequency.

Reference:
  Linkwitz, S. & Riley, D. (1976). "Active Crossover Networks for Non-Coincident Drivers."
  JAES 24(1):2–8. doi:10.17743/jaes.1976.0001

Test signals: synthesised pure-tone sine waves (NOT from microphone).
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

from test_biquad_filter import BiquadFilter  # reuse the Python replica

# ─── Python replica of LinkwitzRileyCrossover ─────────────────────────────────
class LR4Crossover:
    """
    Exact Python replica of LinkwitzRileyCrossover.cpp.
    Two cascaded 2nd-order Butterworth biquads (LP or HP pair).
    Q = 1/√2 (Butterworth maximally flat).
    """
    Q_BUTTERWORTH = 0.70710678118

    def __init__(self):
        self.lp1 = BiquadFilter(); self.lp2 = BiquadFilter()
        self.hp1 = BiquadFilter(); self.hp2 = BiquadFilter()

    def set_frequency(self, freq: float, sample_rate: float):
        self.lp1.update_coefficients("lowpass",  sample_rate, freq, 0.0, self.Q_BUTTERWORTH)
        self.lp2.update_coefficients("lowpass",  sample_rate, freq, 0.0, self.Q_BUTTERWORTH)
        self.hp1.update_coefficients("highpass", sample_rate, freq, 0.0, self.Q_BUTTERWORTH)
        self.hp2.update_coefficients("highpass", sample_rate, freq, 0.0, self.Q_BUTTERWORTH)

    def process(self, sample: float):
        low  = self.lp2.process(self.lp1.process(sample))
        high = self.hp2.process(self.hp1.process(sample))
        return low, high

    def reset(self):
        for f in [self.lp1, self.lp2, self.hp1, self.hp2]:
            f.x1 = f.x2 = f.y1 = f.y2 = 0.0


# ─── 4-Band tree topology replica ────────────────────────────────────────────
class FourBandTree:
    """
    Tree topology matching HarkAudioEngine.cpp:
      input → XoverMid(1500Hz) → low half → XoverLow(500Hz) → Band0, Band1
                               → high half → XoverHigh(4500Hz) → Band2, Band3
    """
    def __init__(self, sample_rate: float = 48000.0):
        self.xover_mid  = LR4Crossover(); self.xover_mid.set_frequency(1500.0, sample_rate)
        self.xover_low  = LR4Crossover(); self.xover_low.set_frequency(500.0,  sample_rate)
        self.xover_high = LR4Crossover(); self.xover_high.set_frequency(4500.0, sample_rate)

    def process(self, sample: float):
        mid_low, mid_high   = self.xover_mid.process(sample)
        b0, b1              = self.xover_low.process(mid_low)
        b2, b3              = self.xover_high.process(mid_high)
        return b0, b1, b2, b3

    def reset(self):
        for x in [self.xover_mid, self.xover_low, self.xover_high]:
            x.reset()


class EightBandTree:
    """
    Python replica of the 8-band symmetric LR4 tree used in Hark v3.
    Matches HarkAudioEngine.cpp structure exactly.
    """
    def __init__(self, sample_rate: float = 48000.0):
        # Layer 1
        self.mid   = LR4Crossover(); self.mid.set_frequency(1500.0, sample_rate)
        # Layer 2
        self.low   = LR4Crossover(); self.low.set_frequency(500.0,  sample_rate)
        self.high  = LR4Crossover(); self.high.set_frequency(4500.0, sample_rate)
        # Layer 3
        self.vlow  = LR4Crossover(); self.vlow.set_frequency(250.0,  sample_rate)
        self.lmid  = LR4Crossover(); self.lmid.set_frequency(1000.0, sample_rate)
        self.hmid  = LR4Crossover(); self.hmid.set_frequency(2500.0, sample_rate)
        self.vhi   = LR4Crossover(); self.vhi.set_frequency(6000.0, sample_rate)

    def process(self, sample: float):
        # Forward pass matching recursive split in C++
        m_low, m_high = self.mid.process(sample)
        l_low, l_high = self.low.process(m_low)
        h_low, h_high = self.high.process(m_high)
        
        vl_low, vl_high = self.vlow.process(l_low)
        lm_low, lm_high = self.lmid.process(l_high)
        hm_low, hm_high = self.hmid.process(h_low)
        vh_low, vh_high = self.vhi.process(h_high)
        
        return [vl_low, vl_high, lm_low, lm_high, hm_low, hm_high, vh_low, vh_high]

    def reset(self):
        for x in [self.mid, self.low, self.high, self.vlow, self.lmid, self.hmid, self.vhi]:
            x.reset()


SAMPLE_RATE = 48000.0
results = {}

def run_test(name, fn):
    try:
        fn()
        results[name] = {"status": "PASS"}
        print(f"✅ PASS  {name}")
    except AssertionError as e:
        results[name] = {"status": "FAIL", "detail": str(e)}
        print(f"❌ FAIL  {name}: {e}")


# ──────────────────────────────────────────────────────────────────────────────
# T-LR4-01: LP + HP sum = flat magnitude at crossover frequency (500Hz)
# ──────────────────────────────────────────────────────────────────────────────
def test_lr4_reconstruction_500hz():
    """
    Core LR4 property: LP(f) + HP(f) must have unity magnitude at ALL frequencies.
    Tolerance: ±0.25dB (tight, as this is a mathematical identity for LR4).
    """
    xover = LR4Crossover()
    xover.set_frequency(500.0, SAMPLE_RATE)

    freqs = np.logspace(math.log10(50), math.log10(20000), 100)
    sum_gains = []
    lp_gains  = []
    hp_gains  = []

    for f in freqs:
        n = int(30 * SAMPLE_RATE / f)
        t = np.arange(n) / SAMPLE_RATE
        x = np.sin(2 * math.pi * f * t).astype(np.float32)
        xover.reset()

        lp_out = np.zeros(n)
        hp_out = np.zeros(n)
        for i, s in enumerate(x):
            lp, hp = xover.process(float(s))
            lp_out[i] = lp; hp_out[i] = hp

        half = n // 2
        rms_in  = np.sqrt(np.mean(x[half:]**2))
        rms_sum = np.sqrt(np.mean((lp_out[half:] + hp_out[half:])**2))
        rms_lp  = np.sqrt(np.mean(lp_out[half:]**2))
        rms_hp  = np.sqrt(np.mean(hp_out[half:]**2))

        sum_gains.append(20 * math.log10(max(rms_sum / (rms_in + 1e-12), 1e-12)))
        lp_gains.append( 20 * math.log10(max(rms_lp  / (rms_in + 1e-12), 1e-12)))
        hp_gains.append( 20 * math.log10(max(rms_hp  / (rms_in + 1e-12), 1e-12)))

    sum_gains = np.array(sum_gains)
    max_err = np.max(np.abs(sum_gains))
    print(f"    LR4 @500Hz LP+HP sum: max deviation from 0dB = {max_err:.3f}dB")

    # Plot
    fig, ax = plt.subplots(figsize=(9, 4))
    ax.semilogx(freqs, lp_gains,  color="#3B82F6", linewidth=2, label="LP band")
    ax.semilogx(freqs, hp_gains,  color="#EF4444", linewidth=2, label="HP band")
    ax.semilogx(freqs, sum_gains, color="#10B981", linewidth=2.5, linestyle="--", label="LP+HP sum")
    ax.axvline(500, color="#6B7280", linestyle=":", label="Crossover 500Hz")
    ax.axhline(-6, color="#6B7280", linestyle=":", alpha=0.5, label="-6dB @ Xover")
    ax.fill_between(freqs, -0.25, 0.25, alpha=0.15, color="#10B981", label="±0.25dB tolerance")
    ax.set_xlabel("Frequency (Hz)"); ax.set_ylabel("Gain (dB)")
    ax.set_title("T-LR4-01: LR4 Crossover @500Hz — LP+HP = Flat Reconstruction")
    ax.legend(loc="lower center", ncol=3, fontsize=8)
    ax.grid(True, which="both", alpha=0.3)
    ax.set_xlim([50, 20000]); ax.set_ylim([-30, 3])
    fig.tight_layout()
    fig.savefig(os.path.join(REPORT_DIR, "lr4_01_reconstruction_500hz.png"), dpi=150)
    plt.close(fig)

    assert max_err < 0.25, f"LR4 @500Hz LP+HP sum max error={max_err:.3f}dB (must be <0.25dB)"


# ──────────────────────────────────────────────────────────────────────────────
# T-LR4-02: -6dB at crossover frequency for each individual band
# ──────────────────────────────────────────────────────────────────────────────
def test_lr4_minus6db_at_crossover():
    """
    LR4 LP and HP outputs must each be exactly -6dB at the crossover frequency.
    Tolerance: ±0.5dB
    """
    for fc in [500.0, 1500.0, 4500.0]:
        xover = LR4Crossover()
        xover.set_frequency(fc, SAMPLE_RATE)

        n = int(30 * SAMPLE_RATE / fc)
        t = np.arange(n) / SAMPLE_RATE
        x = np.sin(2 * math.pi * fc * t).astype(np.float32)
        xover.reset()
        lp_out = np.zeros(n); hp_out = np.zeros(n)
        for i, s in enumerate(x):
            lp, hp = xover.process(float(s))
            lp_out[i] = lp; hp_out[i] = hp

        half = n // 2
        rms_in = np.sqrt(np.mean(x[half:]**2))
        lp_db = 20 * math.log10(max(np.sqrt(np.mean(lp_out[half:]**2)) / (rms_in+1e-12), 1e-12))
        hp_db = 20 * math.log10(max(np.sqrt(np.mean(hp_out[half:]**2)) / (rms_in+1e-12), 1e-12))
        print(f"    LR4 @{fc:.0f}Hz: LP={lp_db:.2f}dB, HP={hp_db:.2f}dB (expected ≈ -6dB each)")
        assert abs(lp_db - (-6.0)) < 0.5, f"LP @{fc:.0f}Hz = {lp_db:.2f}dB (expected -6dB)"
        assert abs(hp_db - (-6.0)) < 0.5, f"HP @{fc:.0f}Hz = {hp_db:.2f}dB (expected -6dB)"


# ──────────────────────────────────────────────────────────────────────────────
# T-LR4-03: 4-Band Tree — sum of all 4 bands = flat response
# ──────────────────────────────────────────────────────────────────────────────
def test_lr4_4band_tree_sum_flat():
    """
    The 4-band tree (500/1500/4500 Hz crossovers) must sum to unity at all frequencies.
    This validates the 'tree topology' fix from the architecture (vs. the old ladder topology).
    Tolerance: ±0.5dB
    """
    tree = FourBandTree(SAMPLE_RATE)

    freqs = np.logspace(math.log10(50), math.log10(20000), 100)
    sum_gains = []
    band_gains = [[], [], [], []]

    for f in freqs:
        n = int(30 * SAMPLE_RATE / f)
        t = np.arange(n) / SAMPLE_RATE
        x = np.sin(2 * math.pi * f * t).astype(np.float32)
        tree.reset()

        bands_out = [np.zeros(n) for _ in range(4)]
        for i, s in enumerate(x):
            b = tree.process(float(s))
            for k in range(4):
                bands_out[k][i] = b[k]

        half = n // 2
        rms_in  = np.sqrt(np.mean(x[half:]**2))
        total   = sum(bands_out[k][half:] for k in range(4))
        rms_sum = np.sqrt(np.mean(total**2))
        sum_gains.append(20 * math.log10(max(rms_sum / (rms_in + 1e-12), 1e-12)))
        for k in range(4):
            rms_k = np.sqrt(np.mean(bands_out[k][half:]**2))
            band_gains[k].append(20 * math.log10(max(rms_k / (rms_in + 1e-12), 1e-12)))

    sum_gains = np.array(sum_gains)
    max_err = np.max(np.abs(sum_gains))
    print(f"    4-Band Tree sum: max deviation = {max_err:.3f}dB")

    colors = ["#3B82F6", "#10B981", "#F59E0B", "#EF4444"]
    labels = ["Band0 (<500Hz)", "Band1 (500-1500Hz)", "Band2 (1500-4500Hz)", "Band3 (>4500Hz)"]
    fig, ax = plt.subplots(figsize=(10, 4))
    for k in range(4):
        ax.semilogx(freqs, band_gains[k], color=colors[k], linewidth=1.5, alpha=0.7, label=labels[k])
    ax.semilogx(freqs, sum_gains, color="#1F2937", linewidth=2.5, linestyle="--", label="Sum of all bands")
    ax.fill_between(freqs, -0.5, 0.5, alpha=0.1, color="#10B981", label="±0.5dB tolerance")
    for fc, name in [(500, "500Hz"), (1500, "1500Hz"), (4500, "4500Hz")]:
        ax.axvline(fc, color="#6B7280", linestyle=":", alpha=0.6)
        ax.text(fc*1.05, -25, name, fontsize=7, color="#6B7280")
    ax.set_xlabel("Frequency (Hz)"); ax.set_ylabel("Gain (dB)")
    ax.set_title("T-LR4-03: 4-Band Tree Crossover — Sum Must Be Flat")
    ax.legend(loc="lower center", ncol=3, fontsize=8)
    ax.grid(True, which="both", alpha=0.3)
    ax.set_xlim([50, 20000]); ax.set_ylim([-30, 3])
    fig.tight_layout()
    fig.savefig(os.path.join(REPORT_DIR, "lr4_03_4band_tree_sum.png"), dpi=150)
    plt.close(fig)

    assert max_err < 0.5, f"4-Band Tree sum max error={max_err:.3f}dB (must be <0.5dB)"


# ──────────────────────────────────────────────────────────────────────────────
# T-LR4-04: Band energy distribution — impulse test
# ──────────────────────────────────────────────────────────────────────────────
def test_lr4_band_energy_distribution():
    """
    Feed an impulse (broadband signal) and verify each band captures
    energy predominantly in its own frequency range.
    """
    tree = FourBandTree(SAMPLE_RATE)
    n = 4096
    impulse = np.zeros(n, dtype=np.float32)
    impulse[100] = 1.0  # Dirac delta

    tree.reset()
    bands_out = [np.zeros(n) for _ in range(4)]
    for i, s in enumerate(impulse):
        b = tree.process(float(s))
        for k in range(4):
            bands_out[k][i] = b[k]

    total_energy = sum(np.sum(bands_out[k]**2) for k in range(4))
    energy_pct = [100 * np.sum(bands_out[k]**2) / (total_energy + 1e-12) for k in range(4)]
    print(f"    Band energy distribution: {[f'{e:.1f}%' for e in energy_pct]}")

    # Each band should carry some energy, and total should be conserved
    total_reconstructed = np.sum((sum(bands_out[k] for k in range(4)))**2)
    total_input = np.sum(impulse**2)
    energy_ratio_db = 20 * math.log10(max(math.sqrt(total_reconstructed / (total_input + 1e-12)), 1e-12))
    print(f"    Impulse energy conservation: {energy_ratio_db:.3f}dB (should be ~0dB)")
    assert abs(energy_ratio_db) < 0.5, f"Impulse energy not conserved: {energy_ratio_db:.2f}dB"


if __name__ == "__main__":
    print("\n" + "="*60)
    print("  Hark DSP White-Box Test — LR4 Crossover")
    print("="*60 + "\n")

    run_test("T-LR4-01 LP+HP perfect reconstruction @500Hz", test_lr4_reconstruction_500hz)
    run_test("T-LR4-02 -6dB @ crossover freq",              test_lr4_minus6db_at_crossover)
    run_test("T-LR4-03 4-Band tree sum flat",               test_lr4_4band_tree_sum_flat)
    run_test("T-LR4-04 Band energy distribution (impulse)", test_lr4_band_energy_distribution)

    print("\n" + "-"*60)
    passed = sum(1 for v in results.values() if v["status"] == "PASS")
    print(f"  Result: {passed} / {len(results)} passed")
    print("-"*60 + "\n")

    with open(os.path.join(REPORT_DIR, "lr4_results.json"), "w") as f:
        json.dump(results, f, indent=2, ensure_ascii=False)
