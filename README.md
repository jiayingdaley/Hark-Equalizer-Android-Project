# Hark — 專業助聽器音訊等化器與 DSP 引擎

Hark 是一款專為**輕中度聽損者**設計的高階 Android 助聽應用程式。  
結合實時音訊 DSP（C++ + Oboe）與現代 Android 開發範式（Jetpack Compose + MVVM），  
目標是提供低延遲、高保真且符合 FDA OTC 助聽器安全標準的聽力輔助體驗。

---

## 專案願景與目標

| 目標 | 技術實現 |
|------|---------|
| 低延遲處理 | Oboe (C++) AAudio 後端，~2-3ms 往返延遲 |
| 醫療級 DSP | 8-Band LR4 分頻 + 多頻段 WDRC + MPO 限制器 |
| FDA 合規 | MPO Limiter @ -4.5 dBFS，符合 OTC 聽力輔具安全規範 |
| 多場景支援 | 情境模式（全向 / 人聲 / 戶外 / 影音 / 自動）自動切換 |
| 媒體 DSP | 透過 `SystemDspManager` 對手機內部影音串流套用 EQ |

---

## 快速開始

### 1. 克隆與初始化子模組

```bash
git clone https://github.com/yourusername/Hark.git
cd Hark
git submodule update --init --recursive   # 初始化 Oboe 子模組
```

### 2. 在 Android Studio 中開啟

- **File → Open → 選擇 `Hark/` 目錄**
- 等待 Gradle 同步（首次 3-5 分鐘，會自動下載 NDK 與 CMake）

### 3. 構建與部署

```bash
# 除錯版本（開發用）
./gradlew :app:assembleDebug

# 構建並直接安裝到設備
./gradlew :app:installDebug
```

### 4. 使用方式

1. 授予**麥克風**與**通知**權限
2. 連接藍牙耳機或有線耳機（未連接耳機時引擎不會啟動）
3. 在主畫面調整 16 段 EQ 補償個人聽力損失
4. 選擇情境模式（全向 / 人聲 / 戶外 / 影音）或啟用自動切換
5. 切換「手機影音」頁籤可對媒體播放套用 EQ

---

## 開發環境需求

```
Android Studio Hedgehog 2024.x 或更新版本
Android SDK 36 (API Level 36)
NDK 25.1.8937393（Gradle 自動下載）
CMake 3.22.1（Gradle 自動下載）
Java 11+
```

### 驗證環境

```bash
java -version              # 需要 ≥ 11
./gradlew --version        # 驗證 Gradle Wrapper
```

---

## 構建指令速查

```bash
# Debug 版本（含除錯符號）
./gradlew :app:assembleDebug

# Release 版本（ProGuard + 優化）
./gradlew :app:assembleRelease

# 構建並安裝
./gradlew :app:installDebug

# 清理重建（遇到奇怪 CMake 錯誤時）
./gradlew clean :app:assembleDebug

# 即時 Logcat 監控
adb logcat -s HarkAudioEngine DynamicsProcessor NoiseSuppressor SceneManager
```

---

## 專案目錄結構

```
Hark/
├── app/
│   ├── src/main/
│   │   ├── AndroidManifest.xml          # 權限宣告、Service / Activity 註冊
│   │   │
│   │   ├── cpp/                         # C++ DSP 引擎 (Oboe + NEON)
│   │   │   ├── CMakeLists.txt           # CMake 構建腳本
│   │   │   ├── native-lib.cpp           # JNI 入口：所有 Kotlin ↔ C++ 橋接函式
│   │   │   ├── HarkAudioEngine.cpp/.h   # 核心引擎：Oboe 流管理 + DSP 管線排程
│   │   │   ├── DynamicsProcessor.cpp/.h # WDRC 壓縮器 & Expander (Noise Gate)
│   │   │   ├── BiquadFilter.cpp/.h      # RBJ 雙二階濾波器（低通/高通/Peaking）
│   │   │   ├── FilterChain.cpp/.h       # 16 段串聯 Biquad EQ
│   │   │   ├── LinkwitzRileyCrossover.cpp/.h  # LR4 對稱分頻器（8 頻段樹狀）
│   │   │   ├── NoiseSuppressor.cpp/.h   # Wiener Filter 譜減法自動降噪
│   │   │   ├── TransientSuppressor.cpp/.h    # 時域脈衝雜訊極速抑制
│   │   │   ├── OwnVoiceDetector.cpp/.h  # 自我語音偵測（堵耳效應管理）
│   │   │   ├── GestureDetector.cpp/.h   # 遮耳手勢偵測（音能特徵分析）
│   │   │   └── LockFreeQueue.h          # 無鎖環形緩衝佇列（音訊執行緒安全）
│   │   │
│   │   ├── java/com/wcy/hark/
│   │   │   ├── HarkApplication.kt       # Application：Timber 初始化、Repository DI
│   │   │   ├── MainActivity.kt          # 權限、音訊裝置偵測、引擎生命週期
│   │   │   ├── EqViewModel.kt           # ViewModel：EQ 狀態、DSP 診斷輪詢、持久化
│   │   │   │
│   │   │   ├── audio/
│   │   │   │   ├── HarkAudioBridge.kt   # JNI object：所有 native external fun 宣告
│   │   │   │   ├── HarkAudioService.kt  # 前景服務：保持引擎在背景運行
│   │   │   │   ├── SceneManager.kt      # 情境模式智慧切換（Auto / Manual）
│   │   │   │   ├── MediaSessionObserver.kt  # 監聽媒體播放狀態（驅動 CINEMA 模式）
│   │   │   │   ├── HarkNotificationListener.kt  # NotificationListenerService
│   │   │   │   ├── SystemDspManager.kt  # 對外部 AudioSession 掛載 DynamicsProcessing
│   │   │   │   ├── FloatingEqService.kt # 浮動 EQ 服務（手機媒體模式）
│   │   │   │   └── AudioEffectReceiver.kt   # 廣播接收器：OPEN/CLOSE_AUDIO_EFFECT
│   │   │   │
│   │   │   ├── data/
│   │   │   │   └── EqSettingsRepository.kt  # DataStore：16 段 EQ 增益持久化
│   │   │   │
│   │   │   └── ui/
│   │   │       ├── screen/
│   │   │       │   ├── MainScreen.kt    # 主畫面：EQ、情境模式、收音來源切換
│   │   │       │   └── DspTestScreen.kt # DSP 除錯面板：各模組 bypass、診斷儀表
│   │   │       ├── components/
│   │   │       │   ├── EqualizerCurveDisplay.kt  # 可拖曳 EQ 曲線 Canvas
│   │   │       │   └── SystemVolumeSlider.kt     # 系統音量滑桿整合
│   │   │       └── theme/
│   │   │           ├── Color.kt / Theme.kt / Type.kt  # Material 3 設計系統
│   │   │
│   │   └── res/                         # 圖示、字串資源
│   │
│   ├── build.gradle.kts                 # 模組 Gradle（依賴、NDK 版本、compileSdk）
│   └── proguard-rules.pro
│
├── oboe/                                # Google Oboe 音訊庫（Git Submodule）
├── gradle/libs.versions.toml            # Version Catalog（依賴版本集中管理）
├── build.gradle.kts                     # 根 Gradle
├── settings.gradle.kts
│
├── docs/                                # 設計文件與分析報告
│   ├── AUDIO_ARCHITECTURE.md            # 音訊架構總覽
│   ├── API_REFERENCE.md                 # JNI 函式簽名完整參考
│   ├── DSP_PARAMETERS_AUDIT.md          # WDRC / Limiter 參數數學推導
│   └── whitebox_test/                   # 白箱測試報告與圖表
│       └── DSP_WHITEBOX_TEST_REPORT.md
│
└── tests/
    └── dsp_whitebox/                    # Python 離線 DSP 白箱測試腳本
        ├── run_all_tests.py             # 一鍵執行所有測試
        ├── test_signal_chain.py         # 信號鏈端對端測試
        ├── test_lr4_crossover.py        # LR4 分頻器頻率響應驗證
        ├── test_dynamics_processor.py   # WDRC 壓縮特性曲線測試
        ├── test_noise_suppressor.py     # 降噪 SNR 改善量測
        ├── test_biquad_filter.py        # Biquad 濾波器特性驗證
        ├── test_filterbank_8band.py     # 8 頻段濾波器組驗證
        └── evaluate_system_performance.py  # 系統整體效能評估
```

---

## DSP 信號鏈架構

```
Microphone Input (48 kHz, Mono → Stereo Expand)
    ↓
[0] Input Gain Compensation        (+15 dB 手機內建麥克風補償 / 耳機 0 dB)
    ↓
[1] DC Blocker                     (消除直流偏移與上電爆音，可動態關閉)
    ↓
[2] Transient Suppressor           (時域脈衝雜訊極速抑制，保護 WDRC 不觸發)
    ↓
[3] NoiseSuppressor (Wiener Filter)(譜減法自動環境降噪，可動態開關)
    ↓
[4] OwnVoiceDetector               (低高頻能量比偵測自我語音，動態抑制低頻堵耳)
    ↓
[5] 16-Band Parametric EQ          (UI 滑桿控制，預設 Bypass；僅在 SystemDsp 模式啟用)
    ↓
[6] 8-Band LR4 Symmetric Tree      (對稱式 Linkwitz-Riley 4th-order 分頻樹)
    │   分頻點: ~200 / 500 / 1000 / 1500 / 2500 / 4500 / 6000 Hz
    ↓
[7] Multi-band WDRC (×8 頻段)      (各頻段獨立動態壓縮，含 Expander Noise Gate)
    │   ├─ 核心語音頻段 (Bands 2-5): 放寬閾值至 -55 dBFS，保護語音可懂度
    │   ├─ 低頻 / 極高頻 (Bands 0/1/7): 高閾值噪音門，抑制風聲與環境低頻
    │   └─ 處方增益偏移: +8.0 dB 全域位移（基於 UI EQ 均值計算）
    ↓
[8] Level-dependent Auto-Headroom  (依輸入電平動態縮放 Headroom，防止過載)
    ↓
[9] MPO Limiter (FDA Safety)       (主限幅器 @ -4.5 dBFS, 20:1 硬壓縮，0.5ms 起振)
    ↓
[10] Master Volume & Soft-Clip    (三次方音量曲線 + Tanh 飽和保護)
    ↓
Speaker Output (Bluetooth A2DP / BLE / Wired / USB, Stereo 48 kHz)
```

### GestureDetector（手勢記憶系統）
- **操作**：用手遮住耳朵 3 秒鐘進行「聲學掃描」
- **效果**：系統鎖定當前語音特徵並維持該情境模式，放手後依然有效
- **Reset**：呼叫 `HarkAudioBridge.resetGesture()` 或切換情境模式

---

## 音訊來源與情境模式

### 情境模式（SituationalMode）

| 模式 | 說明 | 適用場景 |
|------|------|---------|
| **全向 (TRANSPARENCY)** | 輕壓縮，降噪關，保留環境真實音 | 靜態室內、一般日常 |
| **人聲 (CONVERSATION)** | 帶通 300-3400 Hz，降噪強，提升語音辨識 | 面對面交談 |
| **戶外 (OUTDOOR)** | 100 Hz 以下陡切，防風噪 | 戶外步行、騎車 |
| **影音 (CINEMA)** | V 形 EQ（低高音補強），寬動態範圍 | 看影片、聽音樂 |
| **自動 (AUTO)** | `SceneManager` 每 5 秒分析頻譜，自動切換 | 懶人模式 |

### 收音來源

| 來源 | 裝置選擇優先順序 |
|------|---------------|
| **耳機麥克風** | BLE/SCO → USB 耳機 → 有線 3.5mm |
| **手機內建麥克風** | 強制使用 `TYPE_BUILTIN_MIC`（+15 dB 補償） |
| **手機影音（內部音訊）** | `SystemDspManager` 掛載 `DynamicsProcessing` 到媒體 Session |

---

## 關鍵技術說明

### 藍牙 SCO 狀態機
藍牙耳機收音需要 SCO 連結（通話品質，非 A2DP）。`MainActivity` 包含完整的 SCO 狀態機：
1. 偵測到藍牙輸出 → 啟動 SCO 連結 / 設定 `MODE_IN_COMMUNICATION`
2. 等待 `SCO_AUDIO_STATE_CONNECTED` 廣播
3. 建立連結後重新設定 Oboe 輸入串流

### Lock-Free 參數更新
EQ 滑桿調整透過 `std::atomic` 陣列（`mBandGains`、`mGainDirty`）直接從 UI 執行緒寫入，音訊回調執行緒讀取，**零鎖競爭**。

### 前景服務設計
`HarkAudioService` 在使用者開啟引擎後，以前景服務方式常駐，確保 Android 系統不會在背景殺掉引擎進程。`SceneManager` 的生命週期與 Service 綁定。

---

## 修改 DSP 參數指南

### 修改情境模式 WDRC 參數

編輯 `app/src/main/cpp/HarkAudioEngine.cpp` 的 `setSituationalMode()` 函式：

```cpp
// updateWdrcParameters(CompressThresholdDb, CompressRatio, ExpanderThresholdDb,
//                       ExpanderRatio, AttackMs, ReleaseMs)
case SituationalMode::TRANSPARENCY:
    updateWdrcParameters(-20.0f, 1.2f, -60.0f, 0.4f, 10.0f, 600.0f);
    break;
case SituationalMode::CONVERSATION:
    updateWdrcParameters(-30.0f, 1.8f, -55.0f, 0.5f, 5.0f, 80.0f);
    break;
```

> **注意**：核心語音頻段 (Bands 2-5) 會在 `updateWdrcParameters` 中被強制放寬至 `-55.0f dBFS / 0.66f (1.5:1)` 以保護語音可懂度。

### 修改 MPO 限制器

編輯 `HarkAudioEngine.cpp` 的 `updateDSPParameters()` 函式：

```cpp
// setParameters(ThresholdDb, Ratio, ExpanderThresholdDb, ExpanderRatio,
//               AttackMs, ReleaseMs, SampleRate)
mLimiterL.setParameters(-4.5f, 20.0f, -100.0f, 1.0f, 0.5f, 30.0f, sampleRate);
mLimiterR.setParameters(-4.5f, 20.0f, -100.0f, 1.0f, 0.5f, 30.0f, sampleRate);
```

### 修改處方增益基準偏移

編輯 `HarkAudioEngine.cpp` 的 `recomputePrescriptionGains()` 函式：

```cpp
float globalGainOffsetDb = 8.0f;  // 全域增益偏移（dB），正值 = 增強整體響度
mPrescriptionBaseTargets[b] = powf(10.0f, (avgDb + globalGainOffsetDb) / 20.0f);
```

---

## 常見問題排查

| 問題 | 症狀 | 解決方案 |
|------|------|---------|
| **引擎未啟動** | 狀態顯示「請連接耳機」 | 需先插入有線/藍牙耳機，引擎才會啟動 |
| **Oboe 找不到** | CMake 錯誤：`oboe/Oboe.h: No such file` | 執行 `git submodule update --init --recursive` |
| **NDK 版本錯誤** | 編譯失敗「toolchain not found」 | 確認 `local.properties` 有 `ndk.dir=` 路徑，或讓 Gradle 自動下載 25.1 |
| **無聲音輸出** | 麥克風有收音但耳機無聲 | 1. 確認 RECORD_AUDIO 權限已授予<br>2. 重啟 App |
| **藍牙連接後無聲** | 連接 5 秒後音訊停止 | 確認耳機支援 A2DP；SCO 連結需 2-3 秒初始化 |
| **音訊削波失真** | 聲音嚴重失真 | 降低 `globalGainOffsetDb`（HarkAudioEngine.cpp） |
| **EQ 修改無效** | 修改 C++ 後仍使用舊參數 | `./gradlew clean :app:assembleDebug` 強制重新編譯 |
| **診斷 XRun 高** | `diagInputXRun` 持續上升 | 切換至「獨占模式」或降低 CPU 負載 |

---

## 技術棧

| 層級 | 技術 | 說明 |
|------|------|------|
| **UI Framework** | Jetpack Compose | 響應式 UI，Material Design 3 |
| **Architecture** | MVVM + StateFlow | 單向資料流，`EqViewModel` 集中管理狀態 |
| **Audio Engine** | Oboe C++ | Google 高效能低延遲音訊庫（Git Submodule） |
| **Audio API Stack** | AAudio → OpenSL ES | 自動 fallback 到 OpenSL ES（舊設備相容） |
| **DSP Processing** | C++ + NEON SIMD | 8-Band LR4 + WDRC + Wiener Filter |
| **Interop** | JNI | `HarkAudioBridge.kt` ↔ `native-lib.cpp` |
| **Persistence** | Jetpack DataStore | EQ 設定持久化（`EqSettingsRepository`） |
| **Background** | Foreground Service | `HarkAudioService` 保持引擎背景常駐 |
| **Media DSP** | Android DynamicsProcessing | `SystemDspManager` 掛載到媒體 Session |
| **Logging** | Timber | Debug Tree / CrashReportingTree |
| **Monitoring** | Firebase Crashlytics + Analytics | 生產環境崩潰回報與使用分析 |
| **Build System** | Gradle 8.x + CMake 3.22 | Version Catalog 集中管理依賴 |

---

## 白箱測試（離線 DSP 驗證）

`tests/dsp_whitebox/` 包含一套 Python 離線測試腳本，可在不需要 Android 設備的情況下驗證 DSP 演算法行為：

```bash
cd tests/dsp_whitebox/

# 安裝依賴（numpy, scipy, matplotlib）
pip install numpy scipy matplotlib soundfile

# 執行所有測試
python run_all_tests.py

# 個別測試
python test_lr4_crossover.py       # LR4 分頻器頻率響應
python test_dynamics_processor.py  # WDRC 壓縮特性
python test_signal_chain.py        # 端對端信號鏈驗證
python test_noise_suppressor.py    # 降噪 SNR 量測
```

測試報告輸出至 `tests/dsp_whitebox/report_figures/`。

---

## 深入文件

| 文件 | 說明 |
|------|------|
| [AUDIO_ARCHITECTURE.md](docs/AUDIO_ARCHITECTURE.md) | 音訊架構總覽與設計決策 |
| [API_REFERENCE.md](docs/API_REFERENCE.md) | 完整 JNI 函式簽名與 Kotlin 呼叫範例 |
| [DSP_PARAMETERS_AUDIT.md](docs/DSP_PARAMETERS_AUDIT.md) | WDRC / Limiter 參數數學推導與校準記錄 |
| [DSP_WHITEBOX_TEST_REPORT.md](docs/whitebox_test/DSP_WHITEBOX_TEST_REPORT.md) | 白箱測試結果報告 |

---

## 許可

本專案採 MIT 許可。詳見 [LICENSE](LICENSE) 文件。

---

## 貢獻指南

1. Fork 本倉庫
2. 建立特性分支 (`git checkout -b feature/your-feature`)
3. 提交更改 (`git commit -m 'feat: Add your feature'`)
4. 推送至分支 (`git push origin feature/your-feature`)
5. 開啟 Pull Request

---

## 聯絡方式

**主要開發者**: Hark Audio Team  
**問題回報**: GitHub Issues  
**建議與反饋**: GitHub Discussions
