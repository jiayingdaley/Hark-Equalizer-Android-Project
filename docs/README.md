# Hark 文件索引

閱讀順序建議：先看根目錄 [README.md](../README.md)（功能總覽、目錄結構、建置方式），
再依主題深入以下文件。

## 架構與 API

| 文件 | 內容 |
|------|------|
| [architecture/AUDIO_ARCHITECTURE.md](architecture/AUDIO_ARCHITECTURE.md) | 音訊架構總覽：Oboe 引擎、信號鏈、執行緒模型與設計決策 |
| [api/API_REFERENCE.md](api/API_REFERENCE.md) | JNI 函式簽名完整參考（`HarkAudioBridge` ↔ `native-lib.cpp`） |
| [UI_SYSTEM_GUIDE.md](UI_SYSTEM_GUIDE.md) | UI 系統與畫面導覽 |

## DSP 與測試

| 文件 | 內容 |
|------|------|
| [dsp/DSP_PARAMETERS_AUDIT.md](dsp/DSP_PARAMETERS_AUDIT.md) | WDRC / Limiter 參數數學推導與校準記錄 |
| [whitebox_test/DSP_WHITEBOX_TEST_REPORT.md](whitebox_test/DSP_WHITEBOX_TEST_REPORT.md) | Python 離線白箱測試結果（腳本在 `tests/`） |
| [figures/](figures/) | 信號鏈、LR4 分頻、WDRC 曲線圖 |

## 聽力檢測與研究方法

| 文件 | 內容 |
|------|------|
| [SSN_GENERATION.md](SSN_GENERATION.md) | Speech-shaped noise 產生方法（LTAS 塑形）、SNR 混音公式、可重現腳本、論文方法段落建議 |

## 校正與實驗（程式內對應）

- 逐頻率耳機校正表：`app/src/main/java/com/wcy/hark/data/experiment/EarphoneCalibrationRepository.kt`
  （schema v2：`refDbfs` + `measuredDbSpl`，RETSPL 採 ISO 389-1；種子檔 `app/src/main/assets/earphone_calibration.json`）
- 校準操作畫面：`ui/screen/EarphoneCalibrationScreen.kt`（實驗模式）
- 學術實驗面板（校正音 / WDRC I/O 掃頻 / Tone Burst / OSPL90 / 雙麥錄音）：
  `ui/screen/CalibrationTestScreen.kt` + `ui/viewmodel/ExperimentViewModel.kt`

## 其他

- `.ai_collaboration/`：開發過程的 AI 協作筆記與路線圖（非正式文件）
- `tests/`：Python 離線 DSP 白箱測試（執行方式見根 README「白箱測試」一節）
