# 雙麥克風環境收音實驗與環境模式參數調校指南
*(Dual-Microphone Characterisation and Environment Tuning Guide)*

本文件為 **5 副耳機雙通道錄音實驗** 的標準操作指南。旨在通過分析「耳機麥克風 (Headset Mic)」與「手機麥克風 (Phone Mic)」在多種聲學場景下的相對響應，建立特徵數據，用以優化與調整 Hark 助聽器 APP 的四種環境模式參數（安靜、對話、嘈雜、戶外）。

---

## 1. 實驗核心概念

當使用者戴著耳麥時，系統擁有兩個收音端點：
1. **手機麥克風**：通常離使用者有一定距離，多處於自由聲場（Free Field）或擴散聲場（Diffuse Field）中，具有較高且平坦的環境音敏感度。
2. **耳機麥克風**：緊鄰耳道或面頰，會受到頭部陰影效應（Head Shadow Effect）、耳屏/面部反射以及呼吸氣流（風噪）的顯著影響。

藉由雙通道錄音，我們可以提取**兩者之間的相對傳遞函數（Relative Transfer Function, RTF）**與**能量譜差值（Spectral Level Difference, SLD）**，這對於調校 DSP 的麥克風混音權重（Beamforming/Mixing）與降噪濾波器（Noise Reduction）至關重要。

---

## 2. 設備與空間配置

* **測試距離**：外部播放喇叭與受試者（或佩戴耳機的模擬人頭）的**正前方距離為 1 公尺**。
* **設備連接**：
  * 手機開啟錄音 App（或利用 Hark 的 JNI debug 錄音模式），將音訊輸入設為立體聲（Stereo/Dual-Mono）：**左聲道錄取手機內建麥克風，右聲道錄取耳機麥克風**。
  * 外部播放設備連接高品質喇叭，播放預製測試音，且喇叭軸線對齊受試者雙耳中點。

---

## 3. 測試音訊信號設計

外部喇叭應依序播放以下三種測試音（可在 1 米處校準音量至 65 dB SPL 中等強度）：

### 信號一：對數正弦掃頻 (Logarithmic Sine Sweep / Chirp)
* **規格**：100 Hz 至 10,000 Hz，時長 10 秒，前後加 1 秒靜音。
* **物理意圖**：用於計算兩路麥克風在極高精度下的頻率響應差與群延遲（Group Delay）。掃頻信號因具備時間與頻率的單調對應關係，能通過「解卷積（Deconvolution）」完全濾除房間背景反射與不相關雜音。
* **Python 產生掃頻信號腳本**：
  ```python
  import numpy as np
  import wave

  fs = 44100
  duration = 10.0
  f0 = 100.0
  f1 = 10000.0

  t = np.linspace(0, duration, int(fs * duration), endpoint=False)
  # Log sweep formula
  sweep = np.sin(2 * np.pi * f0 * duration * ((f1 / f0) ** (t / duration) - 1.0) / np.log(f1 / f0))

  # Apply 50ms cosine ramps to prevent click transients
  ramp_len = int(fs * 0.05)
  ramp = np.ones_like(sweep)
  ramp[:ramp_len] = 0.5 * (1.0 - np.cos(np.pi * np.arange(ramp_len) / ramp_len))
  ramp[-ramp_len:] = 0.5 * (1.0 - np.cos(np.pi * np.arange(ramp_len)[::-1] / ramp_len))
  sweep *= ramp

  # Convert to 16-bit PCM WAV
  sweep_int = (sweep * 32767.0).astype(np.int16)
  with wave.open("log_sweep_10s.wav", "w") as f:
      f.setnchannels(1)
      f.setsampwidth(2)
      f.setframerate(fs)
      f.writeframes(sweep_int.tobytes())
  ```

### 信號二：粉紅噪音 (Pink Noise)
* **規格**：穩態粉紅噪音，時長 30 秒。
* **物理意圖**：粉紅噪音的能量密度與頻率成反比（-3 dB/Octave），與人類耳蝸的對數臨界頻帶（Critical Bands）能量分布一致。適合用來直接比對兩路麥克風在 1/3 倍頻程（1/3 Octave Bands）上的**穩態功率譜差值**。

---

## 4. 測量場景與參數調校對應表

戴著 5 副耳機時，依序在以下四個環境下播放測試音，錄製雙通道 PCM：

### 場景 1：安靜房間（Quiet Room）
* **環境特徵**：背景噪聲低於 35 dBA。
* **測試意圖**：量測 5 副耳麥在無外界干擾時的**固有電氣底噪（System Noise Floor）**。
* **參數調校應用**：
  * 用於微調「安靜模式」中的 **擴充器閾值（WDRC Expansion Threshold）**。
  * 若某副耳麥的電氣底噪過大，應調高其 Expansion Threshold（例如由 -65 dBFS 調高至 -55 dBFS），以防靜音時耳機傳出持續的「嘶嘶聲」。

### 場景 2：擴散噪聲場（Diffuse Noise / Restaurant）
* **環境特徵**：外部喇叭播放多人談話雜音（Babble Noise）或粉紅噪聲，聲級 65–70 dBA。
* **測試意圖**：模擬嘈雜餐廳。分析在此場景下，手機麥克風（身外）與耳麥（面部）收到的噪聲頻譜和能量相關性。
* **參數調校應用**：
  * 用於調校「嘈雜模式」中的 **降噪濾波器（Spectral Subtraction / Wiener Filter）增益限制**。
  * 由於耳機麥克風多具有全向性（Omnidirectional）且貼近面部，在此嘈雜環境下，若分析發現耳麥的噪聲能量在高頻段（2k-6k Hz）顯著高於手機麥克風，說明耳廓反射或漏音嚴重。此時應在嘈雜模式的 DSP 中，將耳麥高頻段的降噪衰減量額外增加 3-6 dB。

### 場景 3：前方單一對話聲源（Target Speech with Reverberation）
* **環境特徵**：外部喇叭在 1 公尺播放 ISTS 語音，背景無雜音。
* **測試意圖**：模擬面對面安靜交談。
* **參數調校應用**：
  * 用於微調「對話模式」下的 **高頻增益補償（High-frequency Boost）**。
  * 手機麥克風與耳麥在 1 米距離收錄前方語音時，耳麥因頭部陰影（Head Shadow）效應，高頻細節通常會衰減。分析雙通道的語音頻譜後，我們可以為這 5 副耳機在對話模式下，客製化一個「高頻等化補償值（例如在 3k-8k Hz 提升 4 dB）」，以補償頭部阻擋造成的輔音損失，提升語音辨識度。

### 場景 4：風噪干擾環境（Windy / Outdoor）
* **環境特徵**：使用小型風扇以低速從側面或正面吹向佩戴耳機的受試者，背景播放街道交通噪聲。
* **測試意圖**：量測風速在麥克風膜片上產生的極低頻湍流噪聲（Turbulence Noise）。
* **參數調校應用**：
  * 用於調校「戶外模式」中的 **高通濾波器截止頻率（High-Pass Filter Cutoff, HPF）**。
  * 耳麥的防風罩性能通常不同。若錄音顯示某副耳機麥克風在風吹時，100Hz-300Hz 的能量飆高了 20 dB 且產生削波，那麼在切換至「戶外模式」時，DSP 的高通濾波器截止頻率應從預設的 150 Hz 動態調高至 **300 Hz**，並拉大低頻衰減斜率，以徹底濾除風阻撞擊麥克風產生的轟鳴聲。

---

## 5. 數據處理與分析指標建議

錄音完成後，將立體聲 WAV 檔案匯入 Python，可透過以下指標來調整 DSP 參數：

1. **頻譜級差 (Spectral Level Difference, SLD)**:
   $$\text{SLD}(f) = 10 \log_{10} \frac{P_{\text{headset}}(f)}{P_{\text{phone}}(f)}$$
   * 當 $\text{SLD}(f) > 0$，代表該頻帶下耳麥敏感度較高；若 $< 0$，則手機麥克風敏感度較高。
   * 此數值可直接輸入到 `EqViewModel` 或 C++ 引擎的麥克風輸入平衡增益中。

2. **相干性分析 (Coherence Function)**:
   用以測量兩路麥克風信號在頻域上的相關程度。如果相干性在特定高頻段極低，說明該頻段多為非相干噪聲，此時 DSP 應降低對該頻段的權重，以防產生人工梳狀濾波效應（Comb Filtering）。
