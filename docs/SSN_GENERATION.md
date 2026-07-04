# Speech-Shaped Noise (SSN) 產生方法說明

檔案：`app/src/main/res/raw/ssn_noise.wav`（16 kHz、mono、16-bit PCM、60 秒）

## 什麼是 SSN

Speech-shaped noise（語音頻譜噪音）是一種穩態噪音，其長期平均頻譜（LTAS,
Long-Term Average Speech Spectrum）與目標語料的語音頻譜一致。因為噪音能量
在各頻帶上與語音的能量分布相同，它對語音的遮蔽（masking）在頻譜上是均勻的，
是語音噪音測驗（speech-in-noise test）最常用的競爭噪音之一
（參考：Nilsson et al., 1994, HINT; Byrne et al., 1994, LTASS）。

## 產生流程（離線，Python / Anaconda "Datamining" 環境）

輸入語料：本 App 的中文雙音節語詞錄音全集
`app/src/main/res/raw/hselist4_r*_c*.wav`（200 檔，16 kHz mono）。

1. **計算 LTAS**：對每個語詞檔，以 1024 點 FFT、50% overlap、Hanning 窗
   做短時傅立葉轉換，將所有檔案所有音框的功率譜（|FFT|²）累加平均，
   開根號得到平均幅度譜 `mag[f]`（513 個頻點，0–8000 Hz）。
2. **塑形白噪音**：產生 60 秒高斯白噪音（固定亂數種子 20260704，可重現），
   以同樣的 1024 點窗、50% overlap 做 overlap-add：每個音框 FFT 後逐頻點
   乘上 `mag[f]` 再逆 FFT 疊加。這等效於用語音 LTAS 作為濾波器的頻率響應。
3. **正規化**：峰值正規化後再降 12 dB（峰值 −12 dBFS，RMS 約 −26 dBFS），
   保留混音餘裕（headroom），避免與語音相加時削波。

## App 內的 SNR 混音（`SsnAudioMixer.kt`）

播放時（非預混）依指定 SNR 做樣本級混音：

```
noise_gain = rms(speech) / ( rms(noise_segment) × 10^(SNR/20) )
mix[i] = speech[i] + noise_gain × noise[start + i]
```

- SNR 定義為 `20·log10(rms_speech / rms_noise)`，兩者皆為全段 RMS。
- 每題從 60 秒噪音中隨機取段，噪音比語音前後各多 500 ms（noise lead/trail），
  讓使用者先聽到噪音再出現語詞。
- 若混音後峰值超過 16-bit 範圍，整段等比例縮小（軟性正規化）並記 log。
- 因 SNR 由數位混音精確控制，**與播放音量無關**，故測驗允許使用者
  先將音量調至最舒適音量（MCL）再開始。

## 重現腳本

```python
import numpy as np, wave, glob

files = sorted(glob.glob(".../res/raw/hselist4_*.wav"))
fs, nfft = 16000, 1024
ltas, count = np.zeros(nfft//2 + 1), 0
for f in files:
    w = wave.open(f)
    x = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16) / 32768.0
    w.close()
    for i in range(0, len(x) - nfft, nfft//2):
        seg = x[i:i+nfft] * np.hanning(nfft)
        ltas += np.abs(np.fft.rfft(seg))**2
        count += 1
mag = np.sqrt(ltas / count)

dur = 60.0; n = int(dur * fs)
rng = np.random.default_rng(20260704)
noise = rng.standard_normal(n + nfft)
out = np.zeros(n + 2*nfft); win = np.hanning(nfft)
for i in range(0, n, nfft//2):
    spec = np.fft.rfft(noise[i:i+nfft] * win) * mag
    out[i:i+nfft] += np.fft.irfft(spec)
out = out[:n] / np.max(np.abs(out[:n])) * 10**(-12/20)

pcm = (out * 32767).astype(np.int16)
ww = wave.open("ssn_noise.wav", "wb")
ww.setnchannels(1); ww.setsampwidth(2); ww.setframerate(fs)
ww.writeframes(pcm.tobytes()); ww.close()
```

## 論文撰寫建議

方法段落可描述為：「競爭噪音為 speech-shaped noise，由測驗語料全集
（200 個中文雙音節詞，16 kHz）之長期平均頻譜對高斯白噪音進行頻譜塑形
（1024 點 FFT、50% overlap、Hanning 窗、overlap-add）產生，長度 60 秒。
各訊噪比條件由語音與噪音之全段 RMS 於數位域精確設定。」
