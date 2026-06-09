# 遠端同學發言場景（無強冷氣雜音）DSP 效能評估報告

本報告利用**能量自適應語音活動檢測 (VAD)**，動態切割台上同學說話（Speech）與安靜停頓（Silence）段落，評估 C++ 核心 DSP 的增益補償與底噪控制表現。

## 1. 數據基準測試表

| 耳機/麥克風來源 | UI 增益曲線設計 | 信噪比提升 (SNRI) | 背景噪聲衰減 (BNA) | 語音總體增益 | 破音率 (Clip %) |
| :--- | :--- | :---: | :---: | :---: | :---: |
| EARPODS | Flat_0dB | 3.88 dB | 5.75 dB | -1.86 dB | 0.00% |
| EARPODS | Slope_HighFreq_Boost | 4.56 dB | 8.03 dB | -3.46 dB | 0.00% |
| EARPODS | Reverse_Slope_Bass_Boost | 1.10 dB | 6.33 dB | -5.24 dB | 0.00% |
| AIRPODS | Flat_0dB | 1.24 dB | -1.07 dB | 2.31 dB | 0.00% |
| AIRPODS | Slope_HighFreq_Boost | 1.10 dB | -1.48 dB | 2.58 dB | 0.00% |
| AIRPODS | Reverse_Slope_Bass_Boost | 1.17 dB | 7.60 dB | -6.43 dB | 0.00% |
| CKS330 | Flat_0dB | 8.06 dB | 7.07 dB | 1.00 dB | 0.00% |
| CKS330 | Slope_HighFreq_Boost | 10.06 dB | 10.51 dB | -0.45 dB | 0.00% |
| CKS330 | Reverse_Slope_Bass_Boost | 3.64 dB | 7.46 dB | -3.81 dB | 0.00% |
| PIXEL9 | Flat_0dB | 6.75 dB | 8.87 dB | -2.12 dB | 0.00% |
| PIXEL9 | Slope_HighFreq_Boost | 8.01 dB | 11.22 dB | -3.22 dB | 0.00% |
| PIXEL9 | Reverse_Slope_Bass_Boost | 3.14 dB | 9.75 dB | -6.61 dB | 0.00% |

## 2. 聲學指標意義解讀

- **語音總體增益 (Speech Gain)**：台上同學講話時的輸出/輸入 RMS 比值。在 Slope (高頻聽損補償) 下，由於遠端人聲的高頻衰減被補強，語音增益應呈現明顯的正值（通常為 +3dB 至 +12dB），證明人聲被成功放大且聽起來更清晰。
- **背景噪聲衰減 (BNA)**：台上同學停頓（Silence）時，環境細微噪聲或耳機熱噪被噪聲閘壓低的分貝數。數值越高代表安靜空檔時校正越乾淨。
- **信噪比提升 (SNRI)**：輸出端相較輸入端的信噪比改進。反映降噪演算法動態壓制環境背景干擾、凸顯語音人聲的能力。
