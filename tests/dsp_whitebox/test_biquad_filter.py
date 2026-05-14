"""
test_biquad_filter.py
=====================
White-box unit tests for BiquadFilter (mirrors BiquadFilter.cpp).

Reference: Audio EQ Cookbook by Robert Bristow-Johnson (RBJ)
           https://www.w3.org/TR/audio-eq-cookbook/

Test signals are synthetically generated (NOT from microphone).
All intermediate values are captured and logged for audit purposes.

Usage:
    python test_biquad_filter.py
"""

import math
import numpy as np
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import os
import json

# ─── Output directory ─────────────────────────────────────────────────────────
REPORT_DIR = os.path.join(os.path.dirname(__file__), "report_figures")
os.makedirs(REPORT_DIR, exist_ok=True)

# ─── Python replica of BiquadFilter (double-precision, matching C++) ──────────
class BiquadFilter:
    """
    Exact Python replica of BiquadFilter.cpp.
    - Coefficients: double (float64)
    - State (x1,x2,y1,y2): double
    - I/O: cast float32 → float64 on input, float64 → float32 on output
    Reference: RBJ Audio EQ Cookbook
    """
    def __init__(self):
        self.b0 = 1.0; self.b1 = 0.0; self.b2 = 0.0
        self.a1 = 0.0; self.a2 = 0.0
        self.x1 = 0.0; self.x2 = 0.0
        self.y1 = 0.0; self.y2 = 0.0

    def update_coefficients(self, filter_type: str, sample_rate: float,
                             center_hz: float, gain_db: float, q: float):
        """Compute biquad coefficients (RBJ Cookbook, normalised by a0)."""
        # parameter guards (mirror C++ guards)
        assert sample_rate > 0.0
        assert center_hz > 0.0
        assert q > 0.0

        nyquist = sample_rate / 2.0
        if center_hz >= nyquist:
            center_hz = nyquist * 0.99

        A = 10 ** (gain_db / 40.0)       # linear amplitude factor (RBJ)
        w0 = 2.0 * math.pi * center_hz / sample_rate
        cos_w0 = math.cos(w0)
        sin_w0 = math.sin(w0)
        alpha = sin_w0 / (2.0 * q)

        if filter_type == "peaking":
            b0 = 1.0 + alpha * A
            b1 = -2.0 * cos_w0
            b2 = 1.0 - alpha * A
            a0 = 1.0 + alpha / A
            a1 = -2.0 * cos_w0
            a2 = 1.0 - alpha / A
        elif filter_type == "lowshelf":
            b0 = A * ((A+1) - (A-1)*cos_w0 + 2*math.sqrt(A)*alpha)
            b1 = 2*A * ((A-1) - (A+1)*cos_w0)
            b2 = A * ((A+1) - (A-1)*cos_w0 - 2*math.sqrt(A)*alpha)
            a0 = (A+1) + (A-1)*cos_w0 + 2*math.sqrt(A)*alpha
            a1 = -2.0 * ((A-1) + (A+1)*cos_w0)
            a2 = (A+1) + (A-1)*cos_w0 - 2*math.sqrt(A)*alpha
        elif filter_type == "highshelf":
            b0 = A * ((A+1) + (A-1)*cos_w0 + 2*math.sqrt(A)*alpha)
            b1 = -2*A * ((A-1) + (A+1)*cos_w0)
            b2 = A * ((A+1) + (A-1)*cos_w0 - 2*math.sqrt(A)*alpha)
            a0 = (A+1) - (A-1)*cos_w0 + 2*math.sqrt(A)*alpha
            a1 = 2.0 * ((A-1) - (A+1)*cos_w0)
            a2 = (A+1) - (A-1)*cos_w0 - 2*math.sqrt(A)*alpha
        elif filter_type == "highpass":
            b0 = (1.0 + cos_w0) / 2.0
            b1 = -(1.0 + cos_w0)
            b2 = (1.0 + cos_w0) / 2.0
            a0 = 1.0 + alpha
            a1 = -2.0 * cos_w0
            a2 = 1.0 - alpha
        elif filter_type == "lowpass":
            b0 = (1.0 - cos_w0) / 2.0
            b1 = 1.0 - cos_w0
            b2 = (1.0 - cos_w0) / 2.0
            a0 = 1.0 + alpha
            a1 = -2.0 * cos_w0
            a2 = 1.0 - alpha
        elif filter_type == "bandpass":
            b0 = alpha
            b1 = 0.0
            b2 = -alpha
            a0 = 1.0 + alpha
            a1 = -2.0 * cos_w0
            a2 = 1.0 - alpha
        else:
            raise ValueError(f"Unknown filter type: {filter_type}")

        # Normalise (divide by a0)
        inv = 1.0 / a0
        self.b0 = b0 * inv; self.b1 = b1 * inv; self.b2 = b2 * inv
        self.a1 = a1 * inv; self.a2 = a2 * inv
        # Reset state on coefficient update
        self.x1 = self.x2 = self.y1 = self.y2 = 0.0

    def process(self, sample_f32: float) -> float:
        """Process one sample – mirrors BiquadFilter::process() in C++."""
        x = float(sample_f32)                            # cast to double
        y = self.b0*x + self.b1*self.x1 + self.b2*self.x2 \
          - self.a1*self.y1 - self.a2*self.y2
        # denormal guard (FLT_MIN = 1.17549435e-38)
        if abs(y) < 1.17549435e-38:
            y = 0.0
        self.x2 = self.x1; self.x1 = x
        self.y2 = self.y1; self.y1 = y
        return float(y)                                   # cast back to float

    def process_block(self, samples: np.ndarray) -> np.ndarray:
        return np.array([self.process(float(s)) for s in samples], dtype=np.float32)


# ─── Helper: compute frequency response via swept sine ────────────────────────
def measure_freq_response(filter_obj: BiquadFilter, freqs: np.ndarray,
                           sample_rate: float = 48000.0, n_cycles: int = 30) -> np.ndarray:
    """
    Measure the filter's amplitude response at each frequency by driving it
    with a pure sine wave and computing RMS in/out ratio.
    This is an end-to-end black-box measurement of a white-box implementation.
    """
    gains_db = []
    for f in freqs:
        t = np.arange(int(n_cycles * sample_rate / f)) / sample_rate
        x = np.sin(2 * math.pi * f * t).astype(np.float32)
        # fresh state for each frequency
        filter_obj.x1 = filter_obj.x2 = filter_obj.y1 = filter_obj.y2 = 0.0
        y = filter_obj.process_block(x)
        # skip transient (first half)
        half = len(x) // 2
        rms_in  = np.sqrt(np.mean(x[half:]**2))
        rms_out = np.sqrt(np.mean(y[half:]**2))
        gain = rms_out / (rms_in + 1e-12)
        gains_db.append(20 * math.log10(max(gain, 1e-12)))
    return np.array(gains_db)


# ─── Test cases ──────────────────────────────────────────────────────────────
SAMPLE_RATE = 48000.0
PASS_MARK = "✅ PASS"
FAIL_MARK = "❌ FAIL"
results = {}

def run_test(name, fn):
    try:
        fn()
        results[name] = {"status": "PASS"}
        print(f"{PASS_MARK}  {name}")
    except AssertionError as e:
        results[name] = {"status": "FAIL", "detail": str(e)}
        print(f"{FAIL_MARK}  {name}: {e}")


# ──────────────────────────────────────────────────────────────────────────────
# T-BQ-01: Identity / Pass-through (no update_coefficients called)
# ──────────────────────────────────────────────────────────────────────────────
def test_bq_identity():
    """A default-constructed filter must be identity (b0=1, rest=0)."""
    filt = BiquadFilter()
    x = np.array([0.5, -0.3, 0.1, 0.9, -0.7], dtype=np.float32)
    y = filt.process_block(x)
    np.testing.assert_allclose(y, x, atol=1e-6,
        err_msg="Default filter must be identity (pass-through)")


# ──────────────────────────────────────────────────────────────────────────────
# T-BQ-02: Peaking EQ – gain accuracy at centre frequency
# ──────────────────────────────────────────────────────────────────────────────
def test_bq_peaking_gain_accuracy():
    """
    A +12dB peaking EQ at 1000Hz should measure approximately +12dB at 1kHz
    and 0dB far from the peak.
    Tolerance: ±1dB (matches hearing-aid fitting requirement)
    """
    filt = BiquadFilter()
    filt.update_coefficients("peaking", SAMPLE_RATE, 1000.0, 12.0, 1.4)

    freqs = np.array([200.0, 500.0, 1000.0, 2000.0, 8000.0])
    gains = measure_freq_response(filt, freqs, SAMPLE_RATE)

    print(f"    Peaking +12dB @1kHz → measured gains: {dict(zip(freqs.astype(int), np.round(gains,2)))}")
    assert abs(gains[2] - 12.0) < 1.0, f"1kHz gain={gains[2]:.2f}dB, expected ~+12dB"
    assert abs(gains[0]) < 1.5,        f"200Hz pass-band gain={gains[0]:.2f}dB, should be ~0"
    assert abs(gains[4]) < 1.5,        f"8kHz pass-band gain={gains[4]:.2f}dB, should be ~0"

    # ── Plot ──────────────────────────────────────────────────────────────────
    freqs_sweep = np.logspace(math.log10(80), math.log10(20000), 200)
    filt.update_coefficients("peaking", SAMPLE_RATE, 1000.0, 12.0, 1.4)
    gains_sweep = measure_freq_response(filt, freqs_sweep, SAMPLE_RATE)

    fig, ax = plt.subplots(figsize=(8,4))
    ax.semilogx(freqs_sweep, gains_sweep, linewidth=2, color="#3B82F6")
    ax.axhline(12, color="#EF4444", linestyle="--", label="+12dB target")
    ax.axhline(0,  color="#6B7280", linestyle=":", alpha=0.6)
    ax.set_xlabel("Frequency (Hz)"); ax.set_ylabel("Gain (dB)")
    ax.set_title("T-BQ-02: Peaking EQ +12dB @ 1kHz  (Q=1.4, Fs=48kHz)")
    ax.legend(); ax.grid(True, which="both", alpha=0.3)
    ax.set_xlim([80, 20000]); ax.set_ylim([-3, 15])
    fig.tight_layout()
    fig.savefig(os.path.join(REPORT_DIR, "bq02_peaking_eq.png"), dpi=150)
    plt.close(fig)


# ──────────────────────────────────────────────────────────────────────────────
# T-BQ-03: HighPass @ 150 Hz (NoiseSuppressor wind-noise filter)
# ──────────────────────────────────────────────────────────────────────────────
def test_bq_highpass_150hz():
    """
    HighPass @150Hz Q=0.7 (wind-noise filter in NoiseSuppressor).
    Expectation:
        - 150Hz: -3dB ± 1dB  (Butterworth -3dB point)
        - 50Hz:  < -10dB      (significant attenuation)
        - 1000Hz: ~0dB ± 1dB  (pass-band)
    """
    filt = BiquadFilter()
    filt.update_coefficients("highpass", SAMPLE_RATE, 150.0, 0.0, 0.7)

    freqs = np.array([50.0, 150.0, 300.0, 1000.0, 5000.0])
    gains = measure_freq_response(filt, freqs, SAMPLE_RATE)
    print(f"    HP @150Hz → {dict(zip(freqs.astype(int), np.round(gains,2)))}")

    assert gains[0] < -10.0, f"50Hz gain={gains[0]:.2f}dB, should be < -10dB"
    assert abs(gains[1] - (-3.0)) < 1.5, f"150Hz gain={gains[1]:.2f}dB, expected ≈ -3dB"
    assert abs(gains[3]) < 1.5,          f"1kHz gain={gains[3]:.2f}dB, should be ≈ 0dB"

    freqs_sweep = np.logspace(math.log10(20), math.log10(8000), 200)
    filt.update_coefficients("highpass", SAMPLE_RATE, 150.0, 0.0, 0.7)
    gains_sweep = measure_freq_response(filt, freqs_sweep, SAMPLE_RATE)
    fig, ax = plt.subplots(figsize=(8,4))
    ax.semilogx(freqs_sweep, gains_sweep, linewidth=2, color="#10B981")
    ax.axvline(150, color="#EF4444", linestyle="--", label="150Hz cutoff")
    ax.axhline(-3, color="#F59E0B", linestyle=":", label="-3dB")
    ax.set_xlabel("Frequency (Hz)"); ax.set_ylabel("Gain (dB)")
    ax.set_title("T-BQ-03: HighPass @150Hz Q=0.7 (NoiseSuppressor Wind Filter)")
    ax.legend(); ax.grid(True, which="both", alpha=0.3)
    ax.set_xlim([20, 8000]); ax.set_ylim([-40, 3])
    fig.tight_layout()
    fig.savefig(os.path.join(REPORT_DIR, "bq03_highpass_150hz.png"), dpi=150)
    plt.close(fig)


# ──────────────────────────────────────────────────────────────────────────────
# T-BQ-04: Pinna Restore – Peaking +3dB @ 2700Hz
# ──────────────────────────────────────────────────────────────────────────────
def test_bq_pinna_restore():
    """
    Pinna Restore filter: Peaking +3dB @2700Hz Q=1.2
    Expectation:
        - 2700Hz: +3dB ± 1dB
        - 500Hz, 8kHz: ~0dB ± 1dB
    """
    filt = BiquadFilter()
    filt.update_coefficients("peaking", SAMPLE_RATE, 2700.0, 3.0, 1.2)

    freqs = np.array([500.0, 1500.0, 2700.0, 4000.0, 8000.0])
    gains = measure_freq_response(filt, freqs, SAMPLE_RATE)
    print(f"    Pinna +3dB @2700Hz → {dict(zip(freqs.astype(int), np.round(gains,2)))}")

    assert abs(gains[2] - 3.0) < 1.0, f"2700Hz gain={gains[2]:.2f}dB, expected ~+3dB"
    assert abs(gains[0]) < 1.0,       f"500Hz={gains[0]:.2f}dB, expected ≈0"
    assert abs(gains[4]) < 1.0,       f"8kHz={gains[4]:.2f}dB, expected ≈0"

    freqs_sweep = np.logspace(math.log10(200), math.log10(20000), 300)
    filt.update_coefficients("peaking", SAMPLE_RATE, 2700.0, 3.0, 1.2)
    gains_sweep = measure_freq_response(filt, freqs_sweep, SAMPLE_RATE)
    fig, ax = plt.subplots(figsize=(8,4))
    ax.semilogx(freqs_sweep, gains_sweep, linewidth=2, color="#8B5CF6")
    ax.axvline(2700, color="#EF4444", linestyle="--", label="2700Hz centre")
    ax.axhline(3, color="#F59E0B", linestyle=":", label="+3dB target")
    ax.set_xlabel("Frequency (Hz)"); ax.set_ylabel("Gain (dB)")
    ax.set_title("T-BQ-04: Pinna Restore Filter +3dB @2700Hz  (Q=1.2)")
    ax.legend(); ax.grid(True, which="both", alpha=0.3)
    ax.set_xlim([200, 20000]); ax.set_ylim([-2, 6])
    fig.tight_layout()
    fig.savefig(os.path.join(REPORT_DIR, "bq04_pinna_restore.png"), dpi=150)
    plt.close(fig)


# ──────────────────────────────────────────────────────────────────────────────
# T-BQ-05: LowShelf 0dB pass-through (EQ Band 0 & 1 at 0dB)
# ──────────────────────────────────────────────────────────────────────────────
def test_bq_lowshelf_zero_gain():
    """
    LowShelf @200Hz with gain=0dB must be exactly pass-through across all freqs.
    """
    filt = BiquadFilter()
    filt.update_coefficients("lowshelf", SAMPLE_RATE, 200.0, 0.0, 0.707)

    freqs = np.array([50.0, 200.0, 1000.0, 4000.0, 10000.0])
    gains = measure_freq_response(filt, freqs, SAMPLE_RATE)
    print(f"    LowShelf 0dB @200Hz → {dict(zip(freqs.astype(int), np.round(gains,2)))}")

    for f, g in zip(freqs, gains):
        assert abs(g) < 0.5, f"LowShelf 0dB: gain at {f:.0f}Hz = {g:.2f}dB (should be 0)"


# ──────────────────────────────────────────────────────────────────────────────
# T-BQ-06: Denormal guard – near-zero signal must not produce denormals
# ──────────────────────────────────────────────────────────────────────────────
def test_bq_denormal_guard():
    """
    Feed a very tiny signal (below FLT_MIN) and verify output is 0, not a
    denormal number that would cause CPU performance collapse.
    """
    filt = BiquadFilter()
    filt.update_coefficients("lowpass", SAMPLE_RATE, 500.0, 0.0, 0.707)
    # Prime the filter so state is non-zero
    for _ in range(100):
        filt.process(1e-30)
    tiny = 1e-40  # below FLT_MIN
    out = filt.process(tiny)
    # The output must be exactly 0 (clamped by denormal guard)
    assert out == 0.0, f"Denormal guard failed: out={out}"


# ──────────────────────────────────────────────────────────────────────────────
# T-BQ-07: Full 16-band EQ at 0dB → flat frequency response
# ──────────────────────────────────────────────────────────────────────────────
def test_bq_16band_eq_flat():
    """
    Apply all 16 peaking EQ bands at 0dB in series.
    The total response must remain flat (±1dB) across 250Hz–8kHz.
    """
    center_freqs = [250, 315, 400, 500, 630, 800, 1000, 1250,
                    1600, 2000, 2500, 3150, 4000, 5000, 6300, 8000]

    filters = []
    for fc in center_freqs:
        f = BiquadFilter()
        f.update_coefficients("peaking", SAMPLE_RATE, float(fc), 0.0, 1.4)
        filters.append(f)

    # Add shelf filters (bands 0 and 1 in engine: 200Hz and 300Hz low-shelf)
    shelf0 = BiquadFilter()
    shelf0.update_coefficients("lowshelf", SAMPLE_RATE, 200.0, 0.0, 0.707)
    shelf1 = BiquadFilter()
    shelf1.update_coefficients("lowshelf", SAMPLE_RATE, 300.0, 0.0, 0.707)
    shelf_high = BiquadFilter()
    shelf_high.update_coefficients("highshelf", SAMPLE_RATE, 4000.0, 0.0, 0.707)

    all_filters = [shelf0, shelf1] + filters + [shelf_high]

    test_freqs = np.logspace(math.log10(250), math.log10(8000), 60)
    gains = []
    for f_test in test_freqs:
        t = np.arange(int(20 * SAMPLE_RATE / f_test)) / SAMPLE_RATE
        x = np.sin(2 * math.pi * f_test * t).astype(np.float32)
        # reset all states
        for filt in all_filters:
            filt.x1 = filt.x2 = filt.y1 = filt.y2 = 0.0
        y = x.copy()
        for filt in all_filters:
            y = filt.process_block(y)
        half = len(y) // 2
        rms_in  = np.sqrt(np.mean(x[half:]**2))
        rms_out = np.sqrt(np.mean(y[half:]**2))
        g = 20 * math.log10(max(rms_out / (rms_in + 1e-12), 1e-12))
        gains.append(g)

    gains = np.array(gains)
    max_dev = np.max(np.abs(gains))
    print(f"    16-band EQ @0dB flat test: max deviation = {max_dev:.3f}dB")
    assert max_dev < 1.0, f"16-band EQ not flat at 0dB! Max deviation: {max_dev:.2f}dB"

    fig, ax = plt.subplots(figsize=(10,4))
    ax.semilogx(test_freqs, gains, linewidth=2, color="#F59E0B")
    ax.axhline(0, color="#6B7280", linestyle="--", label="0dB reference")
    ax.fill_between(test_freqs, -1, 1, alpha=0.15, color="#10B981", label="±1dB tolerance")
    ax.set_xlabel("Frequency (Hz)"); ax.set_ylabel("Gain (dB)")
    ax.set_title("T-BQ-07: Full 16-Band EQ (all 0dB) – Should Be Flat")
    ax.legend(); ax.grid(True, which="both", alpha=0.3)
    ax.set_xlim([200, 10000]); ax.set_ylim([-3, 3])
    fig.tight_layout()
    fig.savefig(os.path.join(REPORT_DIR, "bq07_eq_flat.png"), dpi=150)
    plt.close(fig)


# ─── Run all tests ────────────────────────────────────────────────────────────
if __name__ == "__main__":
    print("\n" + "="*60)
    print("  Hark DSP White-Box Test — BiquadFilter")
    print("="*60 + "\n")

    run_test("T-BQ-01 Identity / pass-through",          test_bq_identity)
    run_test("T-BQ-02 Peaking EQ +12dB @ 1kHz",          test_bq_peaking_gain_accuracy)
    run_test("T-BQ-03 HighPass @150Hz (wind filter)",     test_bq_highpass_150hz)
    run_test("T-BQ-04 Pinna Restore +3dB @2700Hz",        test_bq_pinna_restore)
    run_test("T-BQ-05 LowShelf 0dB pass-through",         test_bq_lowshelf_zero_gain)
    run_test("T-BQ-06 Denormal guard",                    test_bq_denormal_guard)
    run_test("T-BQ-07 16-Band EQ all-0dB flat",           test_bq_16band_eq_flat)

    print("\n" + "-"*60)
    passed = sum(1 for v in results.values() if v["status"] == "PASS")
    print(f"  Result: {passed} / {len(results)} passed")
    print("-"*60 + "\n")

    # Save results JSON for the report
    with open(os.path.join(REPORT_DIR, "bq_results.json"), "w") as f:
        json.dump(results, f, indent=2, ensure_ascii=False)
