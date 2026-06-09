# Hark DSP 聲學效能與穩定度基準測試報告

本報告呈現優化後之 C++ 核心 DSP 在三款耳機（EarPods、AirPods Pro 2、ATH-CKS330NC）於 classroom（包含空調底噪、電腦粉紅噪音與自我語音）實測錄音下的效能數據，並模擬多種 UI 增益曲線及長時間連續運行（3分鐘以上）下的表現。

## 1. 效能評估指標說明
- **信噪比提升 (SNRI, SNR Improvement)**: 目標發聲源（電腦粉紅噪音）與背景空調噪聲之比值在輸出端相較輸入端的提升量（dB）。數值越高代表降噪且人聲凸顯效果越好。
- **背景噪聲衰減量 (BNA, Background Noise Attenuation)**: 在教室空調安靜期間，輸出相較輸入之電平衰減量（dB）。數值越高代表安靜時背景越乾淨。
- **語音總體增益 (Speech Gain)**: 自我語音說話期間，輸出與輸入之比值（dB）。反映處方增益放大及 Own Voice Detector (OVD) 作用後的實際表現。
- **長時間穩定度 (Stability)**: 長時間連續運作下，雙耳 Wiener 噪聲地板追蹤與 WDRC 係數是否會發散（NaN 或無限漂移）。

## 2. 基準測試數據表

| 耳機型號 | UI 增益曲線設計 | 信噪比提升 (SNRI) | 背景噪聲衰減 (BNA) | 語音總體增益 | 長時間穩定度 | 破音率 (Clip %) |
| :--- | :--- | :---: | :---: | :---: | :---: | :---: |
| EARPODS | Flat_0dB | 64.04 dB | 111.71 dB | -7.30 dB | STABLE | 0.00% |
| EARPODS | Slope_HighFreq_Boost | 65.73 dB | 103.02 dB | 1.23 dB | STABLE | 0.00% |
| EARPODS | Reverse_Slope_Bass_Boost | 63.14 dB | 101.42 dB | 5.95 dB | STABLE | 0.00% |
| AIRPODS | Flat_0dB | 25.75 dB | 80.13 dB | -2.46 dB | STABLE | 0.00% |
| AIRPODS | Slope_HighFreq_Boost | 25.77 dB | 64.23 dB | 8.76 dB | STABLE | 0.00% |
| AIRPODS | Reverse_Slope_Bass_Boost | 25.37 dB | 79.45 dB | 6.05 dB | STABLE | 0.00% |
| CKS330 | Flat_0dB | 103.02 dB | 115.09 dB | -20.10 dB | STABLE | 0.00% |
| CKS330 | Slope_HighFreq_Boost | 100.14 dB | 104.98 dB | -12.51 dB | STABLE | 0.00% |
| CKS330 | Reverse_Slope_Bass_Boost | 104.29 dB | 105.54 dB | -9.40 dB | STABLE | 0.00% |

