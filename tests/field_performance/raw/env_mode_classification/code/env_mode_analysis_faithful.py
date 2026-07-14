"""
env_mode_analysis_faithful.py
──────────────────────────────
Faithful re-implementation of the ACTUAL on-device algorithm (not an
approximation), so the thesis figure/numbers for section 4.6 are traceable
to the real code:

  - Band energy: NoiseSuppressor.h/.cpp — 5 RBJ-cookbook constant-skirt-gain
    BandPass biquads at 500/1k/2k/3k/4k Hz, Q=1.2 (HarkDspConfig::NS_BAND_Q),
    sample rate 48 kHz (HarkDspConfig::SAMPLE_RATE), rectified and tracked
    with a per-SAMPLE EMA: energy[n] = 0.95*energy[n-1] + 0.05*|bandpass[n]|
    (NS_ALPHA_SIGNAL = 0.95). This is NOT a block PSD estimate — it's a fast
    envelope follower evaluated at 48 kHz.
  - SceneManager.kt: reads that continuously-updated energy[] snapshot every
    250 ms (SAMPLE_INTERVAL_MS), accumulates 20 samples into a 5 s window
    (SAMPLES_PER_WINDOW), then computes meanTotal / lowRatio / modStd exactly
    as in evaluateWindow().

Usage: conda run -n DataMining python3 env_mode_analysis_faithful.py
"""
from __future__ import annotations
import re
import warnings
from pathlib import Path
from typing import Dict, List

import numpy as np
import librosa
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

warnings.filterwarnings("ignore")

SCRIPT_DIR = Path(__file__).resolve().parent
RAW_DIR = SCRIPT_DIR.parent
FIGURES_DIR = SCRIPT_DIR / "figures"
FIGURES_DIR.mkdir(exist_ok=True)

FS = 48000.0                       # HarkDspConfig::SAMPLE_RATE
BAND_FREQS = [500, 1000, 2000, 3000, 4000]
BAND_Q = 1.2                       # HarkDspConfig::NS_BAND_Q
ALPHA_SIGNAL = 0.95                # HarkDspConfig::NS_ALPHA_SIGNAL

SAMPLE_INTERVAL_S = 0.25           # SceneManager.SAMPLE_INTERVAL_MS
SAMPLES_PER_WINDOW = 20            # SceneManager.SAMPLES_PER_WINDOW (5 s window)

QUIET_TOTAL = 0.001
MOD_STD_CONV = 4.0
LOW_RATIO_OUTDOOR = 0.6

MODE_FOLDERS = {
    "mode_transparency_quiet": "TRANSPARENCY",
    "mode_conversation": "CONVERSATION",
    "mode_outdoor": "OUTDOOR",
    "mode_cinema_media": "CINEMA",
}
MODES = ["TRANSPARENCY", "CONVERSATION", "OUTDOOR", "CINEMA"]

HEADSET_ALIASES = {
    "airpods pro2": "AirPods Pro2", "airpods": "AirPods Pro2",
    "earpods": "EarPods", "hd 400u": "HD 400U", "hd400u": "HD 400U",
    "hd400": "HD 400U", "jbl": "JBL", "pixel9": "Pixel 9",
    "pixel 9": "Pixel 9", "sony": "Sony",
}


def normalise_headset(filename: str) -> str:
    stem = Path(filename).stem.lower()
    stem = re.sub(r"[-_ ](outdoor|conversation|cinema|transparency|quiet|pink|white|log|ists|raw).*", "", stem)
    stem = stem.strip("-_ ")
    for alias, canonical in HEADSET_ALIASES.items():
        if alias in stem:
            return canonical
    return stem.title()


def load_audio(path: Path, sr: float = FS) -> np.ndarray:
    y, _ = librosa.load(str(path), sr=int(sr), mono=True)
    return y.astype(np.float64)


# ── RBJ Audio EQ Cookbook: constant 0 dB peak-gain BandPass (matches BiquadFilter.cpp exactly) ──
def bandpass_biquad_coeffs(center_hz: float, q: float, fs: float):
    w0 = 2.0 * np.pi * center_hz / fs
    cos_w0, sin_w0 = np.cos(w0), np.sin(w0)
    alpha = sin_w0 / (2.0 * q)
    b0, b1, b2 = alpha, 0.0, -alpha
    a0 = 1.0 + alpha
    a1 = -2.0 * cos_w0
    a2 = 1.0 - alpha
    return (b0 / a0, b1 / a0, b2 / a0), (1.0, a1 / a0, a2 / a0)


def biquad_filter(x: np.ndarray, b, a) -> np.ndarray:
    from scipy.signal import lfilter
    return lfilter(b, a, x)


def band_energy_envelope(x: np.ndarray, center_hz: float) -> np.ndarray:
    """Bandpass -> rectify -> per-sample EMA (alpha=0.95), exactly like
    NoiseSuppressor::process(). Returns the envelope array, same length as x."""
    b, a = bandpass_biquad_coeffs(center_hz, BAND_Q, FS)
    filtered = biquad_filter(x, b, a)
    absx = np.abs(filtered)
    env = np.empty_like(absx)
    e = 0.0
    one_minus = 1.0 - ALPHA_SIGNAL
    for i in range(len(absx)):
        e = ALPHA_SIGNAL * e + one_minus * absx[i]
        env[i] = e
    return env



def compute_reference_offsets() -> Dict[str, np.ndarray]:
    """Per-headset band-energy gain offset from the pink-noise reference
    recording (mode_transparency_quiet/*pink*), using the SAME faithful
    band_energy_envelope() pipeline, so the correction is consistent with
    the rest of this analysis."""
    quiet_dir = RAW_DIR / "mode_transparency_quiet"
    pink_files = sorted(quiet_dir.glob("*pink*"))
    if not pink_files:
        return {}
    headset_bands = {}
    for f in pink_files:
        hs = normalise_headset(f.name)
        y = load_audio(f)
        y_trim = y[len(y) // 3:]
        bands = np.array([band_energy_envelope(y_trim, fr).mean() for fr in BAND_FREQS])
        headset_bands[hs] = np.log10(bands + 1e-12)
    mean_bands = np.stack(list(headset_bands.values())).mean(axis=0)
    return {hs: (mean_bands - b) for hs, b in headset_bands.items()}


def compute_windows_for_file(y: np.ndarray, headset: str = "", offsets: dict = None) -> List[dict]:
    """Simulate SceneManager's 250ms-sample / 5s-window collection exactly."""
    n = len(y)
    envs = np.stack([band_energy_envelope(y, f) for f in BAND_FREQS], axis=1)  # (n, 5)
    if offsets is not None and headset in offsets:
        off = offsets[headset]
        envs = 10.0 ** (np.log10(envs + 1e-12) + off[np.newaxis, :])

    interval_samples = int(SAMPLE_INTERVAL_S * FS)
    n_samples = n // interval_samples
    if n_samples < SAMPLES_PER_WINDOW:
        return []

    # Kotlin "sample": snapshot of the envelope at the END of each 250ms tick
    kotlin_samples = envs[interval_samples - 1::interval_samples][:n_samples]  # (n_samples, 5)

    results = []
    for start in range(0, n_samples - SAMPLES_PER_WINDOW + 1, SAMPLES_PER_WINDOW):
        win = kotlin_samples[start:start + SAMPLES_PER_WINDOW]  # (20, 5)
        band_sums = win.sum(axis=0)  # matches sampleBandSums accumulation
        total_per_sample = win.sum(axis=1)  # per-250ms-sample total energy
        mean_total = band_sums.sum() / SAMPLES_PER_WINDOW
        low_ratio = (band_sums[0] + band_sums[1]) / (band_sums.sum() + 1e-12)
        totals_db = 10 * np.log10(total_per_sample + 1e-12)
        mod_std = float(np.std(totals_db, ddof=0))
        detected = (
            "TRANSPARENCY" if mean_total < QUIET_TOTAL else
            "CONVERSATION" if mod_std > MOD_STD_CONV else
            "OUTDOOR" if low_ratio > LOW_RATIO_OUTDOOR else
            "TRANSPARENCY"
        )
        results.append({
            "mean_total": float(mean_total),
            "low_ratio": float(low_ratio),
            "mod_std": mod_std,
            "pred_mode": detected,
        })
    return results


def grid_search(all_rows):
    """3-way grid search (TRANSPARENCY/CONVERSATION/OUTDOOR — CINEMA stays
    manual-only per design, excluded from spectral-rule optimisation, same
    convention as the original env_mode_analysis.py)."""
    rows3 = [r for r in all_rows if r["true_mode"] != "CINEMA"]
    quiet_range = np.percentile([r["mean_total"] for r in rows3], [5, 10, 15, 20, 25, 30])
    outdoor_range = np.percentile([r["mean_total"] for r in rows3], [50, 60, 70, 75, 80, 85, 90])
    conv_range = np.arange(2.0, 4.6, 0.2)

    best = (-1.0, None)
    for q in quiet_range:
        for o in outdoor_range:
            if o <= q:
                continue
            for c in conv_range:
                correct = 0
                for r in rows3:
                    mt, ms = r["mean_total"], r["mod_std"]
                    if mt < q:
                        pred = "TRANSPARENCY"
                    elif mt > o:
                        pred = "OUTDOOR"
                    elif ms > c:
                        pred = "CONVERSATION"
                    else:
                        pred = "TRANSPARENCY"
                    correct += int(pred == r["true_mode"])
                acc = correct / len(rows3)
                if acc > best[0]:
                    best = (acc, (q, o, c))
    return best


def main():
    print("Faithful on-device SceneManager replication (48 kHz, RBJ biquad, per-sample EMA)\n")
    print("Computing per-headset gain offsets from pink-noise reference...")
    offsets = compute_reference_offsets()
    print(f"  offsets for {len(offsets)} headsets\n")
    all_rows = []
    for folder, true_mode in MODE_FOLDERS.items():
        folder_path = RAW_DIR / folder
        files = sorted(folder_path.glob("*.m4a")) + sorted(folder_path.glob("*.wav"))
        files = [f for f in files if "pink" not in f.name.lower()]
        for f in files:
            y = load_audio(f)
            if true_mode == "TRANSPARENCY":
                y = y[int(1 * FS): int(0.5 * len(y))]
            else:
                y = y[min(int(3 * FS), len(y) // 3):]
            wins = compute_windows_for_file(y, headset=normalise_headset(f.name), offsets=offsets)
            for w in wins:
                w["true_mode"] = true_mode
                w["headset"] = normalise_headset(f.name)
                w["file"] = f.name
            all_rows.extend(wins)
            print(f"{true_mode:14s} {f.name:35s} -> {len(wins)} windows")

    print(f"\nTotal windows: {len(all_rows)}\n")

    # ── Summary stats table ──
    print(f"{'mode':14s} {'n':>4s} {'meanTotal':>12s} {'lowRatio (IQR)':>22s} {'modStd dB (IQR)':>22s}")
    stats = {}
    for mode in MODES:
        rows = [r for r in all_rows if r["true_mode"] == mode]
        if not rows:
            continue
        mt = np.array([r["mean_total"] for r in rows])
        lr = np.array([r["low_ratio"] for r in rows])
        ms = np.array([r["mod_std"] for r in rows])
        stats[mode] = dict(mean_total=mt, low_ratio=lr, mod_std=ms)
        print(f"{mode:14s} {len(rows):4d} {np.median(mt):12.2e} "
              f"{np.median(lr):8.2f} ({np.percentile(lr,25):.2f}-{np.percentile(lr,75):.2f})   "
              f"{np.median(ms):8.1f} ({np.percentile(ms,25):.1f}-{np.percentile(ms,75):.1f})")

    print("\nGrid-searching new thresholds (meanTotal-based, replacing lowRatio) ...")
    best_acc, best_params = grid_search(all_rows)
    q, o, c = best_params
    print(f"  Best: QUIET_TOTAL<{q:.2e}  OUTDOOR_TOTAL>{o:.2e}  MOD_STD_CONV>{c:.2f}  -> 3-way acc={best_acc:.1%}")
    global QUIET_TOTAL_NEW, OUTDOOR_TOTAL_NEW, MOD_STD_CONV_NEW
    QUIET_TOTAL_NEW, OUTDOOR_TOTAL_NEW, MOD_STD_CONV_NEW = q, o, c

    # ── Confusion matrix: current thresholds (modStd>4dB, lowRatio>0.6) ──
    idx = {m: i for i, m in enumerate(MODES)}
    mat = np.zeros((4, 4), dtype=int)
    for r in all_rows:
        t, p = idx[r["true_mode"]], idx.get(r["pred_mode"], -1)
        if p >= 0:
            mat[t, p] += 1
    header = f"{'':14s}" + "".join(f"{m:>14s}" for m in MODES)
    print("\nConfusion matrix (current thresholds: modStd>4dB, lowRatio>0.6):")
    print(header)
    for i, m in enumerate(MODES):
        print(f"{m:14s}" + "".join(f"{mat[i,j]:>14d}" for j in range(4)))
    for i, m in enumerate(MODES):
        tot = mat[i].sum()
        if tot:
            print(f"  {m:14s} accuracy = {mat[i,i]/tot:.1%}")

    # ── Confusion matrix: NEW thresholds ──
    def classify_new(r):
        mt, ms = r["mean_total"], r["mod_std"]
        if mt < q: return "TRANSPARENCY"
        if mt > o: return "OUTDOOR"
        if ms > c: return "CONVERSATION"
        return "TRANSPARENCY"

    for r in all_rows:
        r["pred_mode_new"] = classify_new(r)

    mat_new = np.zeros((4, 4), dtype=int)
    for r in all_rows:
        t = idx[r["true_mode"]]; p = idx[r["pred_mode_new"]]
        mat_new[t, p] += 1
    print("\nConfusion matrix (NEW thresholds):")
    print(header)
    for i, m in enumerate(MODES):
        print(f"{m:14s}" + "".join(f"{mat_new[i,j]:>14d}" for j in range(4)))
    for i, m in enumerate(MODES):
        tot = mat_new[i].sum()
        if tot:
            print(f"  {m:14s} accuracy = {mat_new[i,i]/tot:.1%}")

    # ── Figures ──
    # Feature distributions: meanTotal (new discriminator) and modStd; lowRatio
    # kept for transparency but shown as non-discriminating (no threshold line).
    fig, axes = plt.subplots(1, 2, figsize=(12, 5))
    data_mt = [stats[m]["mean_total"] if m in stats else [] for m in MODES]
    bplot = axes[0].boxplot(data_mt, labels=MODES, patch_artist=True)
    colors = ["#4FC3F7", "#81C784", "#FFB74D", "#E57373"]
    for patch, col in zip(bplot["boxes"], colors):
        patch.set_facecolor(col)
    axes[0].set_yscale("log")
    axes[0].axhline(QUIET_TOTAL_NEW, color="red", linestyle="--", linewidth=1.3, label=f"Quiet threshold {QUIET_TOTAL_NEW:.1e}")
    axes[0].axhline(OUTDOOR_TOTAL_NEW, color="purple", linestyle="--", linewidth=1.3, label=f"Outdoor threshold {OUTDOOR_TOTAL_NEW:.1e}")
    axes[0].set_title("meanTotal (new: replaces lowRatio as OUTDOOR criterion)")
    axes[0].legend(fontsize=8)
    axes[0].tick_params(axis='x', rotation=15)

    data_ms = [stats[m]["mod_std"] if m in stats else [] for m in MODES]
    bplot2 = axes[1].boxplot(data_ms, labels=MODES, patch_artist=True)
    for patch, col in zip(bplot2["boxes"], colors):
        patch.set_facecolor(col)
    axes[1].axhline(MOD_STD_CONV_NEW, color="red", linestyle="--", linewidth=1.3, label=f"CONVERSATION threshold {MOD_STD_CONV_NEW:.2f} dB (recalibrated)")
    axes[1].set_title("modStd (5 s window dB std-dev)")
    axes[1].legend(fontsize=8)
    axes[1].tick_params(axis='x', rotation=15)

    fig.suptitle("SceneManager on-device feature distributions (faithful RBJ biquad + per-sample EMA replication, recalibrated thresholds)", fontsize=13)
    plt.tight_layout()
    out = FIGURES_DIR / "faithful_feature_distributions.png"
    fig.savefig(out, dpi=150)
    print(f"\nSaved {out}")

    # Confusion matrices: current (broken) vs new, side by side
    fig2, (ax2a, ax2b) = plt.subplots(1, 2, figsize=(12, 5.5))
    for ax2, m2, title in [
        (ax2a, mat, "Original thresholds (modStd>4dB, lowRatio>0.6)\nCONVERSATION/OUTDOOR accuracy far too low"),
        (ax2b, mat_new, "New thresholds (meanTotal tiering + recalibrated modStd)"),
    ]:
        im = ax2.imshow(m2, cmap="Blues")
        ax2.set_xticks(range(4)); ax2.set_xticklabels(MODES, rotation=30, ha="right")
        ax2.set_yticks(range(4)); ax2.set_yticklabels(MODES)
        ax2.set_xlabel("Predicted mode"); ax2.set_ylabel("True mode (recording label)")
        ax2.set_title(title, fontsize=10)
        for i in range(4):
            for j in range(4):
                ax2.text(j, i, str(m2[i, j]), ha="center", va="center",
                          color="white" if m2[i, j] > m2.max() * 0.6 else "black",
                          fontsize=11, fontweight="bold")
        fig2.colorbar(im, ax=ax2, fraction=0.046, pad=0.04)
    plt.tight_layout()
    out2 = FIGURES_DIR / "faithful_confusion_matrix.png"
    fig2.savefig(out2, dpi=150)
    print(f"Saved {out2}")


if __name__ == "__main__":
    main()
