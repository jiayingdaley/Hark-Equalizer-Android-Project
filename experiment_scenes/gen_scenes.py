import numpy as np
import soundfile as sf
import os

# ---------------------------------------------------------
# Hark - Scene Generation Script 1 (Synthetic Scenes)
# ---------------------------------------------------------
# Settings based on experiment_scenes/README.md
SR = 16000          # 16 kHz
DURATION = 60       # 60 seconds
SEED = 20260708
TARGET_RMS_DBFS = -20.0

def set_seed():
    np.random.seed(SEED)

def adjust_rms(audio, target_dbfs):
    """Adjust the RMS level of the audio to the target dBFS."""
    rms = np.sqrt(np.mean(audio**2))
    if rms == 0:
        return audio
    
    # 0 dBFS is defined as a full-scale square wave (RMS = 1.0)
    # A full-scale sine wave has RMS = 0.707 (-3 dBFS)
    # Standard practice: dBFS = 20 * log10(RMS)
    current_dbfs = 20 * np.log10(rms)
    gain = 10 ** ((target_dbfs - current_dbfs) / 20)
    return audio * gain

def save_wav(filename, audio):
    """Save audio as 16-bit PCM WAV."""
    audio = adjust_rms(audio, TARGET_RMS_DBFS)
    # Clip to avoid overflow before 16-bit conversion
    audio = np.clip(audio, -1.0, 1.0)
    sf.write(filename, audio, SR, subtype='PCM_16')
    print(f"Saved: {filename}")

def generate_pink_noise(length):
    """Generate pink noise using Paul Kellet's economy method."""
    white = np.random.randn(length)
    pink = np.zeros(length)
    b0 = b1 = b2 = b3 = b4 = b5 = b6 = 0.0
    for i in range(length):
        white_val = white[i]
        b0 = 0.99886 * b0 + white_val * 0.0555179
        b1 = 0.99332 * b1 + white_val * 0.0750759
        b2 = 0.96900 * b2 + white_val * 0.1538520
        b3 = 0.86650 * b3 + white_val * 0.3104856
        b4 = 0.55000 * b4 + white_val * 0.5329522
        b5 = -0.7616 * b5 - white_val * 0.0168980
        pink[i] = b0 + b1 + b2 + b3 + b4 + b5 + b6 + white_val * 0.5362
        b6 = white_val * 0.115926
    return pink

def generate_scene1_babble():
    """
    Scene 1: 多人交談背景 (Babble)
    Synthetic babble: mixed modulated noise to simulate crowd chatter.
    """
    print("Generating Scene 1: Babble...")
    set_seed()
    length = SR * DURATION
    # Mix several independent amplitude-modulated noise streams
    babble = np.zeros(length)
    for _ in range(10): # 10 "talkers"
        noise = generate_pink_noise(length)
        # Lowpass filter the envelope for speech-like modulation (around 1-4 Hz)
        t = np.arange(length) / SR
        mod_rate = np.random.uniform(1.0, 4.0)
        envelope = np.sin(2 * np.pi * mod_rate * t + np.random.rand() * 2 * np.pi) * 0.5 + 0.5
        babble += noise * envelope
    
    save_wav("scene1_babble.wav", babble)

def generate_scene3_steady_noise():
    """
    Scene 3: 穩態粉紅噪音 (Steady Noise - A/C or Traffic)
    """
    print("Generating Scene 3: Steady Noise...")
    set_seed()
    length = SR * DURATION
    noise = generate_pink_noise(length)
    save_wav("scene3_steady_noise.wav", noise)

def generate_scene4_transients():
    """
    Scene 4: 低噪背景 + 每 4-6 秒突發脈衝聲 (Transients)
    """
    print("Generating Scene 4: Transients...")
    set_seed()
    length = SR * DURATION
    
    # Low background noise (-20 dB relative to final signal, we'll scale it manually)
    bg_noise = generate_pink_noise(length) * 0.1 
    
    signal = bg_noise.copy()
    
    # Add transients every 4 to 6 seconds
    current_time = 2.0  # First impact around 2s
    while current_time < DURATION:
        sample_idx = int(current_time * SR)
        if sample_idx < length:
            # Generate an impulse (like a clap or door slam)
            # Fast attack, exponential decay
            decay_len = int(0.2 * SR)
            t = np.arange(decay_len) / SR
            impulse = np.random.randn(decay_len) * np.exp(-t * 30)
            
            end_idx = min(sample_idx + decay_len, length)
            actual_len = end_idx - sample_idx
            signal[sample_idx:end_idx] += impulse[:actual_len] * 5.0 # High amplitude
        
        current_time += np.random.uniform(4.0, 6.0)
        
    save_wav("scene4_transients.wav", signal)

if __name__ == "__main__":
    print("Starting generation of synthetic scenes (1, 3, 4)...")
    generate_scene1_babble()
    generate_scene3_steady_noise()
    generate_scene4_transients()
    print("Done!")
