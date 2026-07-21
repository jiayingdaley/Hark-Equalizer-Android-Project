import numpy as np
import soundfile as sf
import librosa
import os

# ---------------------------------------------------------
# Hark - Scene Generation Script 2 (ISTS-based Scenes)
# ---------------------------------------------------------
# Settings based on experiment_scenes/README.md
SR = 16000          # 16 kHz
DURATION = 60       # 60 seconds
TARGET_RMS_DBFS = -20.0
ISTS_FILE = "ISTS - International Speech Test Signal (10 minutes).mp3"
SCENE1_FILE = "scene1_babble.wav"

def adjust_rms(audio, target_dbfs):
    """Adjust the RMS level of the audio to the target dBFS."""
    rms = np.sqrt(np.mean(audio**2))
    if rms == 0:
        return audio
    
    current_dbfs = 20 * np.log10(rms)
    gain = 10 ** ((target_dbfs - current_dbfs) / 20)
    return audio * gain

def save_wav(filename, audio):
    """Save audio as 16-bit PCM WAV."""
    audio = adjust_rms(audio, TARGET_RMS_DBFS)
    audio = np.clip(audio, -1.0, 1.0)
    sf.write(filename, audio, SR, subtype='PCM_16')
    print(f"Saved: {filename}")

def get_rms(audio):
    return np.sqrt(np.mean(audio**2))

def mix_with_snr(signal, noise, snr_db):
    """Mix signal and noise with a specific Signal-to-Noise Ratio (dB)."""
    # Ensure they are the same length
    min_len = min(len(signal), len(noise))
    signal = signal[:min_len]
    noise = noise[:min_len]
    
    # Calculate current RMS
    sig_rms = get_rms(signal)
    noise_rms = get_rms(noise)
    
    if noise_rms == 0:
        return signal
    if sig_rms == 0:
        return noise
        
    # Scale noise to achieve desired SNR
    # SNR = 20 * log10(sig_rms / new_noise_rms)
    # new_noise_rms = sig_rms / (10 ** (snr_db / 20))
    target_noise_rms = sig_rms / (10 ** (snr_db / 20))
    
    noise_scaled = noise * (target_noise_rms / noise_rms)
    return signal + noise_scaled

def generate_scene2_speech_quiet():
    """
    Scene 2: 安靜下真實語音
    ISTS (片段 A, 60–120 s)
    """
    print("Generating Scene 2: Speech Quiet...")
    if not os.path.exists(ISTS_FILE):
        print(f"Error: {ISTS_FILE} not found. Please download the ISTS file to the current directory.")
        return
        
    # Load segment from 60s to 120s
    audio, _ = librosa.load(ISTS_FILE, sr=SR, mono=True, offset=60.0, duration=DURATION)
    
    # Pad with silence if the file is shorter than expected (just in case)
    if len(audio) < SR * DURATION:
        audio = np.pad(audio, (0, SR * DURATION - len(audio)))
        
    save_wav("scene2_speech_quiet.wav", audio)

def generate_scene5_speech_in_babble():
    """
    Scene 5: 語音 + 多人背景 (SNR +5 dB)
    ISTS (片段 B, 300–360 s) + babble (from scene 1)
    """
    print("Generating Scene 5: Speech in Babble (SNR +5 dB)...")
    if not os.path.exists(ISTS_FILE):
        print(f"Error: {ISTS_FILE} not found. Please download the ISTS file to the current directory.")
        return
        
    if not os.path.exists(SCENE1_FILE):
        print(f"Error: {SCENE1_FILE} not found. Please run gen_scenes.py first to generate the babble noise.")
        return
        
    # Load ISTS segment from 300s to 360s
    speech, _ = librosa.load(ISTS_FILE, sr=SR, mono=True, offset=300.0, duration=DURATION)
    
    # Load babble noise generated in scene 1
    babble, _ = librosa.load(SCENE1_FILE, sr=SR, mono=True, duration=DURATION)
    
    # Pad if necessary
    if len(speech) < SR * DURATION:
        speech = np.pad(speech, (0, SR * DURATION - len(speech)))
    if len(babble) < SR * DURATION:
        babble = np.pad(babble, (0, SR * DURATION - len(babble)))
        
    # Mix with +5 dB SNR
    mixed = mix_with_snr(speech, babble, snr_db=5.0)
    
    save_wav("scene5_speech_in_babble_5dB.wav", mixed)

if __name__ == "__main__":
    print("Starting generation of ISTS-based scenes (2, 5)...")
    generate_scene2_speech_quiet()
    generate_scene5_speech_in_babble()
    print("Done!")
