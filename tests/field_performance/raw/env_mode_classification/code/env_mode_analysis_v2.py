"""
env_mode_analysis_v2.py
───────────────────────
Re-analyse the same field recordings using the CURRENT SceneManager.kt logic
(meanTotal / lowRatio / modStd, 250 ms sampling inside a 5 s rolling window),
instead of the old lowFreq/highFreq ratio rule that env_mode_analysis.py
still uses. Goal: check whether CINEMA (music/media) recordings have a
distinguishable spectral signature that could let SceneManager auto-detect
this mode too, instead of relying solely on MediaSessionObserver (which can
only see the phone's OWN media playback — useless for a live concert or a
shop's external speaker).

Usage: conda run -n DataMining python3 env_mode_analysis_v2.py
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
from scipy.signal import welch

warnings.filterwarnings("ignore")

SCRIPT_DIR = Path(__file__).resolve().parent
RAW_DIR = SCRIPT_DIR.parent
FIGURES_DIR = SCRIPT_DIR / "figures"
FIGURES_DIR.mkdir(exist_ok=True)

BANDS_HZ = [500, 1000, 2000, 3000, 4000]
BAND_WIDTH_RATIO = 0.15

SAMPLE_S = 0.25   # matches SceneManager's 250 ms sampling
WINDOW_N = 20     # 20 samples = 5 s rolling window

MODE_FOLDERS = {
    "mode_transparency_quiet": "TRANSPARENCY",
    "mode_conversation": "CONVERSATION",
    "mode_outdoor": "OUTDOOR",
    "mode_cinema_media": "CINEMA",
}

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


def load_audio(path: Path, sr: int = 16000) -> np.ndarray:
    y, _ = librosa.load(str(path), sr=sr, mono=True)
    return y.astype(np.float32)


def band_energy(y: np.ndarray, sr: int, centre_hz: float) -> float:
    f, psd = welch(y, fs=sr, nperseg=min(len(y), 512))
    lo = centre_hz * (1.0 - BAND_WIDTH_RATIO)
    hi = centre_hz * (1.0 + BAND_WIDTH_RATIO)
    mask = (f >= lo) & (f <= hi)
    if not mask.any():
        return 0.0
    return float(np.mean(psd[mask]))


def compute_5band(y: np.ndarray, sr: int) -> np.ndarray:
    return np.array([band_energy(y, sr, hz) for hz in BANDS_HZ])


def sliding_windows(y: np.ndarray, sr: int, hs: str, offsets: dict) -> List[dict]:
    """250 ms samples -> 5 s rolling window (20 samples), non-overlapping windows
    (hop = 1 window, matching how the app's hysteresis logic buckets time)."""
    sample_len = int(SAMPLE_S * sr)
    n_samples = len(y) // sample_len
    if n_samples < WINDOW_N:
        return []

    per_sample_bands = []
    for i in range(n_samples):
        frame = y[i * sample_len:(i + 1) * sample_len]
        b = compute_5band(frame, sr)
        if hs in offsets:
            b = 10.0 ** (np.log10(b + 1e-12) + offsets[hs])
        per_sample_bands.append(b)
    per_sample_bands = np.array(per_sample_bands)   # (n_samples, 5)

    results = []
    for start in range(0, n_samples - WINDOW_N + 1, WINDOW_N):
        win = per_sample_bands[start:start + WINDOW_N]   # (20, 5)
        totals = win.sum(axis=1)                          # (20,) linear total energy per 250ms sample
        mean_total = totals.mean()
        low_ratio = (win[:, 0].mean() + win[:, 1].mean()) / (win.mean(axis=0).sum() + 1e-12)
        totals_db = 10 * np.log10(totals + 1e-12)
        mod_std = float(np.std(totals_db))
        results.append({
            "headset": hs,
            "mean_total_db": float(10 * np.log10(mean_total + 1e-12)),
            "low_ratio": float(low_ratio),
            "mod_std": mod_std,
        })
    return results


def compute_reference_offsets(sr: int = 16000) -> Dict[str, np.ndarray]:
    quiet_dir = RAW_DIR / "mode_transparency_quiet"
    pink_files = sorted(quiet_dir.glob("*pink*"))
    if not pink_files:
        return {}
    headset_bands = {}
    for f in pink_files:
        hs = normalise_headset(f.name)
        y = load_audio(f, sr)
        y_trim = y[len(y) // 3:]
        headset_bands[hs] = np.log10(compute_5band(y_trim, sr) + 1e-12)
    mean_bands = np.stack(list(headset_bands.values())).mean(axis=0)
    return {hs: (mean_bands - b) for hs, b in headset_bands.items()}


def main():
    sr = 16000
    offsets = compute_reference_offsets(sr)
    print(f"Gain offsets computed for {len(offsets)} headsets.\n")

    all_rows = []
    for folder, true_mode in MODE_FOLDERS.items():
        folder_path = RAW_DIR / folder
        files = sorted(folder_path.glob("*.m4a")) + sorted(folder_path.glob("*.wav"))
        files = [f for f in files if "pink" not in f.name.lower()]
        for f in files:
            y = load_audio(f, sr)
            # Skip first 3s settling, and for quiet-mode files also try to skip
            # the played test-signal tail by just taking the first 60% of file
            # (ambient prefix) — good enough for a coarse feature comparison.
            if true_mode == "TRANSPARENCY":
                y = y[int(1 * sr): int(0.5 * len(y))]
            else:
                y = y[min(3 * sr, len(y) // 3):]
            wins = sliding_windows(y, sr, normalise_headset(f.name), offsets)
            for w in wins:
                w["true_mode"] = true_mode
                w["file"] = f.name
            all_rows.extend(wins)
            print(f"{true_mode:14s} {f.name:35s} -> {len(wins)} windows")

    print(f"\nTotal windows: {len(all_rows)}\n")

    print(f"{'mode':14s} {'n':>4s} {'meanTotal(dB)':>15s} {'lowRatio':>10s} {'modStd(dB)':>11s}")
    stats = {}
    for mode in MODE_FOLDERS.values():
        rows = [r for r in all_rows if r["true_mode"] == mode]
        if not rows:
            print(f"{mode:14s}  (no data)")
            continue
        mt = np.array([r["mean_total_db"] for r in rows])
        lr = np.array([r["low_ratio"] for r in rows])
        ms = np.array([r["mod_std"] for r in rows])
        stats[mode] = dict(mean_total=mt, low_ratio=lr, mod_std=ms)
        print(f"{mode:14s} {len(rows):4d} "
              f"{np.median(mt):8.1f} (IQR {np.percentile(mt,25):.1f}~{np.percentile(mt,75):.1f}) "
              f"{np.median(lr):8.2f} (IQR {np.percentile(lr,25):.2f}~{np.percentile(lr,75):.2f}) "
              f"{np.median(ms):7.1f} (IQR {np.percentile(ms,25):.1f}~{np.percentile(ms,75):.1f})")

    # box plots
    fig, axes = plt.subplots(1, 3, figsize=(16, 5))
    modes = list(MODE_FOLDERS.values())
    for ax, key, label in [
        (axes[0], "mean_total", "meanTotal (dB, relative)"),
        (axes[1], "low_ratio", "lowRatio = (500+1k)/total"),
        (axes[2], "mod_std", "modStd (dB, 5s window std)"),
    ]:
        data = [stats[m][key] if m in stats else [] for m in modes]
        bplot = ax.boxplot(data, labels=modes, patch_artist=True)
        colors = ["#4FC3F7", "#81C784", "#FFB74D", "#E57373"]
        for patch, c in zip(bplot["boxes"], colors):
            patch.set_facecolor(c)
        ax.set_title(label)
        ax.tick_params(axis='x', rotation=20)
    fig.suptitle("Current SceneManager features (meanTotal/lowRatio/modStd) by true mode", fontsize=13)
    plt.tight_layout()
    out = FIGURES_DIR / "v2_feature_distributions.png"
    fig.savefig(out, dpi=150)
    print(f"\nSaved {out}")


if __name__ == "__main__":
    main()
