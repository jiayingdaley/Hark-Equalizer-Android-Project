"""
env_mode_analysis.py
────────────────────
Analyse the env_mode_classification recordings to:

1. Compute per-headset mic gain offset using the reference pink-noise
   recording in `mode_transparency_quiet/` (5-band: 500/1k/2k/3k/4k Hz).
2. For every recording (transparency / conversation / outdoor / cinema)
   run the same 5-band energy analyser that SceneManager uses, frame by frame
   (frame = 5 s, hop = 5 s, matching App cycle).
3. Apply the existing hard-coded thresholds (lowFreq>0.7 → OUTDOOR,
   highFreq>0.4 → CONVERSATION, else TRANSPARENCY / CINEMA) and tally
   per-frame predictions vs. ground-truth labels.
4. Output:
   - Confusion matrix (headset × true-mode, predicted-mode counts)
   - Per-headset per-band energy box-plots for each true mode
   - Recommended updated thresholds (scan grid search)
   - PNG figures saved to ../figures/

Usage
─────
  conda run -n DataMining python3 env_mode_analysis.py

All paths are relative to this script's location.
"""

# ── standard library ──────────────────────────────────────────────────────────
from __future__ import annotations
import os
import glob
import re
import warnings
from pathlib import Path
from typing import Optional, List, Dict

# ── third-party ───────────────────────────────────────────────────────────────
import numpy as np
import librosa
import matplotlib
matplotlib.use("Agg")  # non-interactive backend for server / script runs
import matplotlib.pyplot as plt
from scipy.signal import welch
from itertools import product

warnings.filterwarnings("ignore")

# ═══════════════════════════════════════════════════════════════════════════════
# CONFIGURATION
# ═══════════════════════════════════════════════════════════════════════════════
SCRIPT_DIR   = Path(__file__).resolve().parent          # .../env_mode_classification/code/
RAW_DIR      = SCRIPT_DIR.parent                        # .../env_mode_classification/
FIGURES_DIR  = SCRIPT_DIR / "figures"
FIGURES_DIR.mkdir(exist_ok=True)

# The 5 centre frequencies used by HarkAudioBridge.getEnvironmentEnergy()
BANDS_HZ = [500, 1000, 2000, 3000, 4000]

# Band half-width used for integration (±1/3 octave ≈ ±12 %)
BAND_WIDTH_RATIO = 0.15   # ±15 % of centre freq → flat top window

# Frame parameters (must match SceneManager: 5 s window)
FRAME_S = 5.0             # seconds per analysis frame
HOP_S   = 5.0             # non-overlapping frames

# Current App thresholds
THRESH_LOW_FREQ   = 0.70   # lowFreq / total  > this → OUTDOOR
THRESH_HIGH_FREQ  = 0.40   # highFreq / total > this → CONVERSATION

# Mapping from folder names to ground-truth labels
MODE_FOLDERS = {
    "mode_transparency_quiet":  "TRANSPARENCY",
    "mode_conversation":        "CONVERSATION",
    "mode_outdoor":             "OUTDOOR",
    "mode_cinema_media":        "CINEMA",
}

# Headset canonical name normalisation (lowercase → canonical)
HEADSET_ALIASES = {
    "airpods pro2":  "AirPods Pro2",
    "airpods":       "AirPods Pro2",
    "earpods":       "EarPods",
    "hd 400u":       "HD 400U",
    "hd400u":        "HD 400U",
    "hd400":         "HD 400U",
    "jbl":           "JBL",
    "pixel9":        "Pixel 9",
    "pixel 9":       "Pixel 9",
    "sony":          "Sony",
}

# ═══════════════════════════════════════════════════════════════════════════════
# UTILITY HELPERS
# ═══════════════════════════════════════════════════════════════════════════════

def load_audio(path: Path, target_sr: int = 16000) -> np.ndarray:
    """Load any audio file to mono float32 resampled to target_sr."""
    y, _ = librosa.load(str(path), sr=target_sr, mono=True)
    return y.astype(np.float32)


def split_ambient_prefix(y: np.ndarray, sr: int) -> tuple[np.ndarray, np.ndarray]:
    """
    The mode_transparency_quiet recordings start with ~5 s of true room
    ambient noise BEFORE the test signal (pink/white/sweep/ISTS) is played.
    Detect the signal onset via a short-term RMS jump and split the file:
      returns (ambient_prefix, signal_part).
    The ambient prefix is the real TRANSPARENCY ground truth; the signal
    part is only useful for mic-gain reference (pink noise).
    """
    blk = int(0.1 * sr)                       # 100 ms blocks
    n = len(y) // blk
    empty = np.array([], dtype=y.dtype)
    if n < 30:
        return empty, y
    rms = np.array([np.sqrt(np.mean(y[i*blk:(i+1)*blk]**2)) + 1e-12 for i in range(n)])
    db = 20 * np.log10(rms)
    base = np.median(db[:min(10, n)])

    # Bluetooth mics (AirPods / Sony) gate silence to digital zero at the
    # start — that is NOT room ambient. Discard the prefix in that case.
    if base < -120.0:
        return empty, y

    # Onset = first block that stays >8 dB above baseline for ≥1 s.
    # (+8 dB, not +15: phone AGC compresses the played noise close to the
    #  boosted ambient level on some devices.)
    above = db > base + 8.0
    onset = -1
    for i in range(n - 10):
        if above[i] and above[i:i+10].all():
            onset = i
            break
    if onset < 0:
        # No clear signal onset (AGC flattened the recording, e.g. EarPods /
        # HD 400U). We cannot separate ambient from signal → unusable prefix.
        return empty, y
    cut_amb = max(0, (onset - 3) * blk)       # 0.3 s guard before onset
    cut_sig = min(len(y), (onset + 3) * blk)
    return y[:cut_amb], y[cut_sig:]


def band_energy(y: np.ndarray, sr: int, centre_hz: float) -> float:
    """
    Compute the mean power in a narrow band around centre_hz using
    Welch's PSD estimate, matching HarkAudioBridge's band-energy logic.
    """
    f, psd = welch(y, fs=sr, nperseg=min(len(y), 1024))
    lo = centre_hz * (1.0 - BAND_WIDTH_RATIO)
    hi = centre_hz * (1.0 + BAND_WIDTH_RATIO)
    mask = (f >= lo) & (f <= hi)
    if not mask.any():
        return 0.0
    return float(np.mean(psd[mask]))


def compute_5band(y: np.ndarray, sr: int) -> np.ndarray:
    """Return array of shape (5,) with per-band energies for [500,1k,2k,3k,4k]."""
    return np.array([band_energy(y, sr, hz) for hz in BANDS_HZ])


def classify_frame(bands: np.ndarray) -> str:
    """
    Replicate SceneManager's hard-coded logic:
      lowFreq  = band[500] + band[1k]
      highFreq = band[3k]  + band[4k]
      total    = sum of all 5 bands
    """
    low_freq   = bands[0] + bands[1]   # 500 Hz + 1 kHz
    high_freq  = bands[3] + bands[4]   # 3 kHz + 4 kHz
    total      = bands.sum()

    if total < 1e-12:
        return "TRANSPARENCY"
    if low_freq / total > THRESH_LOW_FREQ:
        return "OUTDOOR"
    if high_freq / total > THRESH_HIGH_FREQ:
        return "CONVERSATION"
    return "TRANSPARENCY"


def normalise_headset(filename: str) -> str:
    """Extract and normalise the headset name from a filename."""
    stem = Path(filename).stem.lower()
    # Strip trailing mode suffix (e.g. "-outdoor1", "-conversation", "-pink noise" …)
    stem = re.sub(r"[-_ ](outdoor|conversation|cinema|transparency|quiet|pink|white|log|ists|raw).*", "", stem)
    stem = stem.strip("-_ ")
    for alias, canonical in HEADSET_ALIASES.items():
        if alias in stem:
            return canonical
    return stem.title()   # fallback: title-case the raw stem


# ═══════════════════════════════════════════════════════════════════════════════
# STEP 1  –  Reference gain offset from mode_transparency_quiet pink-noise recs
# ═══════════════════════════════════════════════════════════════════════════════

def compute_reference_offsets(sr: int = 16000) -> Dict[str, np.ndarray]:
    """
    For each headset, load the pink-noise recording from mode_transparency_quiet/
    and compute the 5-band energy vector.  The per-headset offset is defined as:
        offset(headset) = bands_headset_pink - bands_mean_all_headsets_pink
    (log-domain subtraction → energy ratio), so that all headsets are normalised
    to the same sensitivity.

    Returns dict  headset_name → offset_array (shape 5,) in log10 energy space.
    """
    quiet_dir = RAW_DIR / "mode_transparency_quiet"
    pink_files = sorted(quiet_dir.glob("*pink*"))

    if not pink_files:
        print("⚠  No pink-noise recordings found in mode_transparency_quiet/. "
              "Skipping gain correction (offset = 0 for all headsets).")
        return {}

    headset_bands: Dict[str, np.ndarray] = {}
    for f in pink_files:
        hs = normalise_headset(f.name)
        y  = load_audio(f, target_sr=sr)
        # Recording protocol: ~5 s of room ambient FIRST, then the pink noise.
        # Use only the post-onset (signal) part for the mic-gain reference.
        _, y_sig = split_ambient_prefix(y, sr)
        y_trim = y_sig if len(y_sig) >= 2 * sr else y[len(y)//3:]
        bands  = compute_5band(y_trim, sr)
        headset_bands[hs] = np.log10(bands + 1e-12)   # log domain

    # Mean across headsets as the reference baseline
    all_bands = np.stack(list(headset_bands.values()))   # (N_headsets, 5)
    mean_bands = all_bands.mean(axis=0)

    offsets = {hs: (mean_bands - b) for hs, b in headset_bands.items()}
    print(f"✓  Computed gain offsets for {len(offsets)} headsets from pink-noise reference.")
    return offsets


# ═══════════════════════════════════════════════════════════════════════════════
# STEP 2  –  Frame-level energy analysis for every recording
# ═══════════════════════════════════════════════════════════════════════════════

def analyse_recording(
    path:    Path,
    true_mode: str,
    offsets: dict[str, np.ndarray],
    sr:      int = 16000,
    quiet_protocol: bool = False,
) -> list[dict]:
    """
    Slice the recording into FRAME_S-second non-overlapping frames,
    compute corrected 5-band energies, run the classifier, and collect
    per-frame result dicts.

    Returns a list of dicts, each containing:
      headset, true_mode, pred_mode, bands (corrected, linear), low_ratio, high_ratio
    """
    hs = normalise_headset(path.name)
    y  = load_audio(path, target_sr=sr)

    frame_len = int(FRAME_S * sr)

    if quiet_protocol:
        # mode_transparency_quiet protocol: ~5 s of TRUE room ambient first,
        # then a test signal (pink/white/sweep/ISTS). Only the ambient prefix
        # is TRANSPARENCY ground truth; the signal part is excluded here
        # (it is used only by compute_reference_offsets).
        y, _ = split_ambient_prefix(y, sr)
        if len(y) < int(2.5 * sr):
            return []          # onset too early → no usable ambient
        # The prefix is often slightly shorter than one 5 s frame; accept it
        # as a single (shorter) frame so the data is not discarded.
        if len(y) < frame_len:
            frame_len = len(y)
    else:
        # Skip the first 3 s (experimenter settling / positioning noise).
        y = y[min(3 * sr, len(y) // 3):]

    results = []

    for start in range(0, len(y) - frame_len + 1, frame_len):
        frame = y[start: start + frame_len]

        # Raw 5-band energy (linear)
        bands_lin = compute_5band(frame, sr)

        # Apply headset gain correction in log domain → back to linear
        if hs in offsets:
            bands_log_corr = np.log10(bands_lin + 1e-12) + offsets[hs]
            bands_corr = 10.0 ** bands_log_corr
        else:
            bands_corr = bands_lin

        pred = classify_frame(bands_corr)

        total = bands_corr.sum()
        low_r  = (bands_corr[0] + bands_corr[1]) / (total + 1e-12)
        high_r = (bands_corr[3] + bands_corr[4]) / (total + 1e-12)

        results.append({
            "headset":   hs,
            "true_mode": true_mode,
            "pred_mode": pred,
            "bands":     bands_corr,
            "low_ratio": float(low_r),
            "high_ratio":float(high_r),
            "correct":   pred == true_mode,
        })

    return results


# ═══════════════════════════════════════════════════════════════════════════════
# STEP 3  –  Confusion matrix + threshold grid search
# ═══════════════════════════════════════════════════════════════════════════════
MODES = ["TRANSPARENCY", "CONVERSATION", "OUTDOOR", "CINEMA"]

def build_confusion(all_results: list[dict]) -> np.ndarray:
    """Return a 4×4 confusion matrix [true_mode, pred_mode]."""
    idx = {m: i for i, m in enumerate(MODES)}
    mat = np.zeros((len(MODES), len(MODES)), dtype=int)
    for r in all_results:
        t = idx.get(r["true_mode"], -1)
        p = idx.get(r["pred_mode"], -1)
        if t >= 0 and p >= 0:
            mat[t, p] += 1
    return mat


def grid_search_thresholds(
    all_results: List[dict],
    low_range:  Optional[np.ndarray] = None,
    high_range: Optional[np.ndarray] = None,
) -> tuple:
    """
    Sweep lowFreq threshold (0.4 – 0.9) and highFreq threshold (0.2 – 0.7)
    to find the combination that maximises overall accuracy.

    Returns (best_low, best_high, best_accuracy)
    """
    if low_range  is None: low_range  = np.arange(0.40, 0.91, 0.05)
    if high_range is None: high_range = np.arange(0.20, 0.71, 0.05)

    best_acc = -1.0
    best_low  = THRESH_LOW_FREQ
    best_high = THRESH_HIGH_FREQ

    for tl, th in product(low_range, high_range):
        correct = total = 0
        for r in all_results:
            if r["true_mode"] == "CINEMA":
                continue   # CINEMA is triggered by MediaSession, not spectrum
            low_r  = r["low_ratio"]
            high_r = r["high_ratio"]
            if low_r  > tl:  pred = "OUTDOOR"
            elif high_r > th: pred = "CONVERSATION"
            else:             pred = "TRANSPARENCY"
            correct += int(pred == r["true_mode"])
            total   += 1

        acc = correct / total if total else 0.0
        if acc > best_acc:
            best_acc  = acc
            best_low  = tl
            best_high = th

    return best_low, best_high, best_acc


# ═══════════════════════════════════════════════════════════════════════════════
# STEP 4  –  Plotting
# ═══════════════════════════════════════════════════════════════════════════════

def plot_confusion(mat: np.ndarray, title: str, suffix: str = ""):
    fig, ax = plt.subplots(figsize=(6, 5))
    im = ax.imshow(mat, cmap="Blues")
    ax.set_xticks(range(len(MODES))); ax.set_xticklabels(MODES, rotation=30, ha="right")
    ax.set_yticks(range(len(MODES))); ax.set_yticklabels(MODES)
    ax.set_xlabel("Predicted Mode"); ax.set_ylabel("True Mode")
    ax.set_title(title)
    for i in range(len(MODES)):
        for j in range(len(MODES)):
            ax.text(j, i, str(mat[i, j]), ha="center", va="center",
                    color="white" if mat[i, j] > mat.max() * 0.6 else "black",
                    fontsize=11, fontweight="bold")
    fig.colorbar(im, ax=ax, fraction=0.046, pad=0.04)
    plt.tight_layout()
    out = FIGURES_DIR / f"confusion{suffix}.png"
    fig.savefig(out, dpi=150)
    plt.close(fig)
    print(f"   → saved {out.name}")


def plot_ratio_distributions(all_results: list[dict]):
    """Box-plot of low_ratio and high_ratio grouped by true mode."""
    fig, axes = plt.subplots(1, 2, figsize=(12, 5))
    for ax, key, label, thresh, thresh_label in [
        (axes[0], "low_ratio",  "Low-Freq Ratio (500+1k / total)",
         THRESH_LOW_FREQ, f"OUTDOOR threshold ({THRESH_LOW_FREQ})"),
        (axes[1], "high_ratio", "High-Freq Ratio (3k+4k / total)",
         THRESH_HIGH_FREQ, f"CONVERSATION threshold ({THRESH_HIGH_FREQ})"),
    ]:
        data = [[r[key] for r in all_results if r["true_mode"] == m] for m in MODES]
        bplot = ax.boxplot(data, labels=MODES, patch_artist=True, notch=False)
        colors = ["#4FC3F7", "#81C784", "#FFB74D", "#E57373"]
        for patch, color in zip(bplot["boxes"], colors):
            patch.set_facecolor(color)
        ax.axhline(thresh, color="red", linestyle="--", linewidth=1.5, label=thresh_label)
        ax.set_ylabel(label)
        ax.set_title(label)
        ax.legend(fontsize=9)
    fig.suptitle("Energy Ratio Distributions by True Mode (gain-corrected)", fontsize=13)
    plt.tight_layout()
    out = FIGURES_DIR / "ratio_distributions.png"
    fig.savefig(out, dpi=150)
    plt.close(fig)
    print(f"   → saved {out.name}")


def plot_per_headset_ratios(all_results: list[dict]):
    """Scatter plot of each frame: low_ratio vs high_ratio, coloured by true mode."""
    fig, axes = plt.subplots(1, 2, figsize=(14, 6))
    color_map = {"TRANSPARENCY":"#4FC3F7","CONVERSATION":"#81C784",
                 "OUTDOOR":"#FFB74D","CINEMA":"#E57373"}

    for ax, title, mode_filter in [
        (axes[0], "All modes (colour = true mode)", None),
        (axes[1], "Excl. CINEMA (spectral classifiable)", ["TRANSPARENCY","CONVERSATION","OUTDOOR"]),
    ]:
        for m in (mode_filter or MODES):
            pts = [(r["low_ratio"], r["high_ratio"])
                   for r in all_results if r["true_mode"] == m]
            if pts:
                lr, hr = zip(*pts)
                ax.scatter(lr, hr, label=m, alpha=0.55, s=25, color=color_map[m])
        ax.axvline(THRESH_LOW_FREQ,  color="orange", linestyle="--", linewidth=1.3,
                   label=f"Outdoor thresh={THRESH_LOW_FREQ}")
        ax.axhline(THRESH_HIGH_FREQ, color="purple", linestyle="--", linewidth=1.3,
                   label=f"Conv thresh={THRESH_HIGH_FREQ}")
        ax.set_xlabel("Low-Freq Ratio (500+1k / total)")
        ax.set_ylabel("High-Freq Ratio (3k+4k / total)")
        ax.set_title(title)
        ax.legend(fontsize=8)
        ax.set_xlim(0, 1); ax.set_ylim(0, 1)

    fig.suptitle("Frame-level energy ratios  ─  SceneManager feature space", fontsize=13)
    plt.tight_layout()
    out = FIGURES_DIR / "feature_scatter.png"
    fig.savefig(out, dpi=150)
    plt.close(fig)
    print(f"   → saved {out.name}")


def plot_band_heatmap(all_results: list[dict]):
    """Mean 5-band energy profile per true mode (heatmap)."""
    band_labels = ["500 Hz", "1 kHz", "2 kHz", "3 kHz", "4 kHz"]
    matrix = []
    for m in MODES:
        bands = np.array([r["bands"] for r in all_results if r["true_mode"] == m])
        mean_bands = bands.mean(axis=0) if len(bands) else np.zeros(5)
        # Normalise each row by its max so patterns are visible
        mean_bands = mean_bands / (mean_bands.max() + 1e-12)
        matrix.append(mean_bands)
    matrix = np.array(matrix)  # (4, 5)

    fig, ax = plt.subplots(figsize=(8, 4))
    im = ax.imshow(matrix, cmap="YlOrRd", aspect="auto", vmin=0, vmax=1)
    ax.set_xticks(range(5)); ax.set_xticklabels(band_labels)
    ax.set_yticks(range(len(MODES))); ax.set_yticklabels(MODES)
    ax.set_title("Normalised mean 5-band energy profile by true mode")
    for i in range(len(MODES)):
        for j in range(5):
            ax.text(j, i, f"{matrix[i,j]:.2f}", ha="center", va="center",
                    color="black" if matrix[i,j] < 0.7 else "white", fontsize=10)
    fig.colorbar(im, ax=ax, fraction=0.02, pad=0.04)
    plt.tight_layout()
    out = FIGURES_DIR / "band_heatmap.png"
    fig.savefig(out, dpi=150)
    plt.close(fig)
    print(f"   → saved {out.name}")


# ═══════════════════════════════════════════════════════════════════════════════
# MAIN
# ═══════════════════════════════════════════════════════════════════════════════

def main():
    sr = 16000   # resample to 16 kHz (matches App audio pipeline)

    print("=" * 60)
    print("  SceneManager threshold validation analysis")
    print("=" * 60)

    # Step 1 ─ reference gain offsets
    print("\n[1/4] Computing per-headset gain offsets …")
    offsets = compute_reference_offsets(sr=sr)

    # Step 2 ─ collect all frame results
    print("\n[2/4] Analysing recordings frame by frame …")
    all_results: List[dict] = []
    for folder, true_mode in MODE_FOLDERS.items():
        folder_path = RAW_DIR / folder
        if not folder_path.exists():
            print(f"  ⚠  {folder}/ not found, skipping.")
            continue
        files = sorted(folder_path.glob("*.m4a")) + sorted(folder_path.glob("*.wav"))
        if not files:
            print(f"  ⚠  No audio files in {folder}/")
            continue
        print(f"  Mode: {true_mode:20s}  ({len(files)} files)")
        for f in files:
            frames = analyse_recording(f, true_mode, offsets, sr=sr,
                                       quiet_protocol=(folder == "mode_transparency_quiet"))
            all_results.extend(frames)
            corr = sum(r["correct"] for r in frames if r["true_mode"] != "CINEMA")
            tot  = sum(1 for r in frames if r["true_mode"] != "CINEMA")
            pct  = f"{100*corr/tot:.0f}%" if tot else "N/A"
            print(f"      {f.name:40s}  {len(frames)} frames  acc={pct}")

    total_frames = len(all_results)
    print(f"\n  Total frames collected: {total_frames}")

    # Step 3a ─ confusion matrix with current thresholds
    print("\n[3/4] Confusion matrix (current thresholds) …")
    mat_current = build_confusion(all_results)
    plot_confusion(mat_current, "Confusion – current thresholds (0.70 / 0.40)", "_current")

    # Print confusion matrix to console
    header = f"{'':20s}" + "".join(f"{m:>16s}" for m in MODES)
    print(f"\n  {header}")
    for i, m in enumerate(MODES):
        row = f"  {m:20s}" + "".join(f"{mat_current[i,j]:>16d}" for j in range(len(MODES)))
        print(row)

    # Per-mode accuracy (exclude CINEMA from spectral evaluation)
    print()
    for i, m in enumerate(MODES):
        total_m = mat_current[i].sum()
        if total_m == 0:
            continue
        acc = mat_current[i, i] / total_m
        note = " [not spectral-classified]" if m == "CINEMA" else ""
        print(f"  {m:20s}  accuracy = {acc:.1%}{note}")

    # Step 3b ─ grid search better thresholds
    print("\n  Grid-searching optimal thresholds …")
    best_low, best_high, best_acc = grid_search_thresholds(all_results)
    print(f"  Best thresholds →  lowFreq > {best_low:.2f}  |  highFreq > {best_high:.2f}  "
          f" →  accuracy = {best_acc:.1%}")

    # Rebuild confusion with best thresholds and save
    # Temporarily override thresholds for confusion re-build
    orig_low, orig_high = THRESH_LOW_FREQ, THRESH_HIGH_FREQ
    results_optimised = []
    for r in all_results:
        r2 = r.copy()
        lr, hr = r["low_ratio"], r["high_ratio"]
        if r["true_mode"] == "CINEMA":
            r2["pred_mode"] = "CINEMA"
        elif lr > best_low:  r2["pred_mode"] = "OUTDOOR"
        elif hr > best_high: r2["pred_mode"] = "CONVERSATION"
        else:                r2["pred_mode"] = "TRANSPARENCY"
        r2["correct"] = r2["pred_mode"] == r2["true_mode"]
        results_optimised.append(r2)
    mat_opt = build_confusion(results_optimised)
    plot_confusion(mat_opt, f"Confusion – optimised thresholds ({best_low:.2f} / {best_high:.2f})",
                   "_optimised")

    # Step 4 ─ plots
    print("\n[4/4] Generating figures …")
    plot_ratio_distributions(all_results)
    plot_per_headset_ratios(all_results)
    plot_band_heatmap(all_results)

    # ── Summary report ─────────────────────────────────────────────────────────
    print("\n" + "=" * 60)
    print("  SUMMARY")
    print("=" * 60)
    print(f"  Recordings analysed  : {len(MODE_FOLDERS)} modes × multiple headsets")
    print(f"  Total analysis frames: {total_frames}")
    print(f"  Current thresholds   : lowFreq > {orig_low}  |  highFreq > {orig_high}")
    print(f"  Recommended          : lowFreq > {best_low:.2f} |  highFreq > {best_high:.2f}")
    print(f"  Accuracy gain        : → {best_acc:.1%} (spectral modes only)")
    print(f"  Figures saved to     : {FIGURES_DIR}")
    print(f"\n  ⚠  Notes:")
    print(f"    - Outdoor recordings may contain user speech; those frames may")
    print(f"      appear near the CONVERSATION boundary (expected).")
    print(f"    - Conversation dataset lacks Pixel 9; results still valid for")
    print(f"      5/6 headsets.")
    print(f"    - CINEMA mode cannot be validated by spectral threshold alone")
    print(f"      (it is triggered by MediaSessionObserver, not SceneManager).")
    print("=" * 60)


if __name__ == "__main__":
    main()
