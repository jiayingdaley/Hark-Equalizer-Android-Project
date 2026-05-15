# Hark - 專業助聽器音訊等化器與 DSP 引擎

Hark 是一款專為聽損者設計的高階 Android 助聽應用程式。它結合了實時音訊處理技術 (DSP) 與現代 Android 開發範式，旨在提供低延遲、高保真且符合醫療級標準的聽力輔助體驗。

---

## 專案願景與目標

*   **低延遲處理**：利用 Oboe (C++) 實作實時音訊處理，將延遲降至最低 (~2-3ms)
*   **醫療級 DSP**：整合 WDRC (寬動態範圍壓縮)、噪音閘門 (Expander) 與自動增益控制
*   **FDA 合規設計**：遵循 FDA 對 OTC 助聽器的安全性要求 (MPO Limiter @ -3dBFS)
*   **極簡現代 UI**：使用 Jetpack Compose 打造 16 段等化器控制介面

---

## 快速開始 (5 分鐘)

### 1. 克隆與配置

```bash
git clone https://github.com/yourusername/Hark.git
cd Hark

# 檢查 Java 版本 (需要 11+)
java -version
```

### 2. 打開 Android Studio

- File → Open → 選擇 Hark/ 目錄
- 等待 Gradle 同步 (首次 3-5 分鐘)

### 3. 構建與部署

```bash
# 連接 Android 設備

# 構建並安裝
./gradlew :app:installDebug

# 或在 Android Studio 中按 Run (Shift + F10)
```

### 4. 使用

1. 授予麥克風權限
2. 連接藍牙耳機（會自動切換）
3. 調整 16 段 EQ 以補償聽力損失
4. 點「重設」恢復通透模式

---

## 開發環境設置

### 系統需求

```
• Android Studio (2024.x 或更新)
• Android SDK 36+ (API Level 36)
• NDK 25.1 (自動下載)
• CMake 3.22.1 (自動下載)
• Java 11+
```

### 驗證環境

```bash
java -version              # 確認版本 ≥ 11
./gradlew --version        # Gradle Wrapper
```

---

## 構建與部署

### 構建變體

```bash
# Debug 版本 (快速開發)
./gradlew :app:assembleDebug

# Release 版本（簽名與優化）
./gradlew :app:assembleRelease

# 構建並立即安裝到設備
./gradlew :app:installDebug
```

### 即時監控

```bash
# 查看實時 Logcat
adb logcat -s HarkAudioEngine DynamicsProcessor FilterChain

# 或使用 Android Studio Logcat 工具
```

---

## 專案目錄結構

```
Hark/
├── app/                          # Android 應用主模組
│   ├── src/main/kotlin/          # Kotlin UI 程式碼 (Compose)
│   ├── src/main/cpp/             # C++ DSP 引擎
│   │   ├── HarkAudioEngine.cpp   # 核心 Oboe 管理與 DSP 管線
│   │   ├── DynamicsProcessor.cpp # WDRC 壓縮器 & 擴展器 (Noise Gate)
│   │   ├── FilterChain.cpp       # 16 段等化器 (Cascaded Biquad)
│   │   └── BiquadFilter.cpp      # RBJ 雙二階濾波器實作
│   ├── build.gradle.kts          # Gradle 構建配置
│   └── CMakeLists.txt            # CMake 配置（C++ 編譯）
├── oboe/                         # Google Oboe 音訊庫 (Git Submodule)
│   └── include/oboe/             # Oboe 標頭檔
├── gradle/                       # Gradle 配置文件
├── build.gradle.kts              # 根 Gradle 構建文件
└── README.md                     # 本文件
```

---

## DSP 信號鏈架構

```
Microphone Input (48 kHz, Mono → Stereo Expand)
    ↓
[0] Input Gain Compensation        [手機麥克風自動 +15dB 補償]
    ↓
[1] DC Blocker                     [消除直流偏移與開機爆音]
    ↓
[2] NoiseSuppressor (Wiener Filter) [自動環境降噪]
    ↓
[3] Dual-Peak Pinna Restore        [2.7k + 4.5k 耳廓空間感補正]
    ↓
[4] 8-Band LR4 Symmetric Tree      [對稱式分頻處理]
    ↓
[5] Multi-band WDRC                [8 頻段獨立動態壓縮]
    │   ├─ 基礎增益: +3.0 dB (全域位移)
    │   └─ 低頻噪音門: Band 0/1 特殊壓制
    ↓
[6] MPO Limiter (Output Protection) [FDA 安全限幅 -1.5dBFS]
    ↓
[7] Master Volume & Soft-Clip      [三次方音量曲線 & 飽和失真保護]
    ↓
Speaker Output (Bluetooth/Wired Stereo 48 kHz)
```

---

## 🧠 智慧型 DSP 功能

### 1. 譜減法自動降噪 (Noise Suppression)
*   **原理**：即時估算環境背景噪音並從頻譜中減除。
*   **優點**：有效濾除冷氣聲、車流聲等穩定噪音，提升語音辨識度。

### 2. 數位耳廓補償 (Pinna Restore)
*   **原理**：根據 2023 最新聽力學研究，補償 2700Hz 附近的 17dB 缺失。
*   **效果**：消除耳機的「悶塞感」，找回自然的方位感。

### 3. 手勢記憶系統 (Gesture Memory)
*   **操作**：用手遮住耳朵 3 秒鐘進行「聲學掃描」。
*   **效果**：系統會自動鎖定並強化當前對話者的聲音，放手後依然維持該效果。
```

---

## 修改 DSP 參數指南

### 修改 WDRC 參數

編輯 [app/src/main/cpp/HarkAudioEngine.cpp](app/src/main/cpp/HarkAudioEngine.cpp) **第 74-75 行**：

```cpp
// mWdrcLeft.setParameters(Threshold, Ratio, ExpanderThreshold, ExpanderRatio, Attack, Release, SampleRate)
mWdrcLeft.setParameters(-40.0f, 2.0f, -70.0f, 0.9f, 10.0f, 80.0f, sampleRate);
mWdrcRight.setParameters(-40.0f, 2.0f, -70.0f, 0.9f, 10.0f, 80.0f, sampleRate);
```

### 修改 MPO 限制器參數

編輯 [app/src/main/cpp/HarkAudioEngine.cpp](app/src/main/cpp/HarkAudioEngine.cpp) **第 81-82 行**：

```cpp
// mLimiterLeft.setParameters(Threshold, Ratio, ExpanderThreshold, ExpanderRatio, Attack, Release, SampleRate)
mLimiterLeft.setParameters(-3.0f, 20.0f, -100.0f, 1.0f, 0.5f, 30.0f, sampleRate);
mLimiterRight.setParameters(-3.0f, 20.0f, -100.0f, 1.0f, 0.5f, 30.0f, sampleRate);
```

### 修改 EQ 頻率與增益

編輯 [app/src/main/cpp/FilterChain.cpp](app/src/main/cpp/FilterChain.cpp)，修改各頻段的 **增益值** (dB)。16 段中心頻率固定：

```
250 Hz, 315 Hz, 400 Hz, 500 Hz, 630 Hz, 800 Hz, 1000 Hz, 1250 Hz,
1600 Hz, 2000 Hz, 2500 Hz, 3150 Hz, 4000 Hz, 5000 Hz, 6300 Hz, 8000 Hz
```

重新編譯後立即生效：

```bash
./gradlew :app:installDebug
```

---

## 常見問題與故障排查

| 問題 | 症狀 | 解決方案 |
|------|------|---------|
| **NDK 版本錯誤** | CMake 編譯失敗，報錯 "toolchain not found" | 驗證 `local.properties` 末尾：`ndk.dir=/path/to/ndk/25.1` |
| **沒有聲音輸出** | 麥克風獲取音訊，但耳機無聲 | 1. 檢查藍牙連接 2. 在 Android 設置「應用權限」中授予麥克風 & 音訊精焦權限 3. 重啟應用 |
| **音訊削波/扭曲** | 聲音嚴重失真，尤其大聲輸入 | 1. 升高 MPO 限制器閾值 (編輯 HarkAudioEngine.cpp 第 84 行) 2. 降低預增益 |
| **CMake 無法找到 Oboe** | 編譯錯誤：`oboe/OboeSingleBufferQueue.h: No such file` | 執行 `git submodule update --init --recursive` 以初始化 Oboe 子模組 |
| **藍牙連接斷開** | 連接 5 秒後音訊停止 | 1. 確認藍牙耳機支援 A2DP 立體聲 2. 更新耳機固件 3. 重新啟動 Android 藍牙服務 |
| **EQ 預設無效** | 修改 `FilterChain.cpp` 後仍使用舊參數 | 清理並重新編譯：`./gradlew clean :app:assembleDebug` |

---

## 技術棧

| 層級 | 技術 | 說明 |
|------|------|------|
| **UI Framework** | Jetpack Compose | 響應式 UI，基於 Material Design 3 |
| **Architecture** | MVVM + StateFlow | 單向數據流，簡化測試與狀態管理 |
| **Audio Engine** | Oboe C++ | Google 提供的高效能低延遲庫 |
| **Audio API Stack** | AAudio → OpenSL ES | 自動回落到 OpenSL ES (古老設備) |
| **DSP Processing** | C++ + NEON SIMD | 優化的動態壓縮與濾波運算 |
| **Interop** | JNI (Java Native Interface) | Kotlin ↔ C++ 邊界 |
| **Build System** | Gradle 8.x + CMake 3.22 | 自動化原生代碼編譯 |
| **Documentation** | Markdown | 易維護的知識庫 |

---

## 深入學習資源

### API 參考與 JNI 綁定

完整的 JNI 函數簽名、Kotlin 調用示例與 C++ 實作詳節，詳見：

👉 **[API_REFERENCE.md](API_REFERENCE.md)**

### DSP 設計參數與數學推導

WDRC 軟膝特性、噪音閘門校準、MPO 限制器動態、時常數計算、濾波器設計等深入分析：

👉 **[DSP_PARAMETERS_AUDIT.md](DSP_PARAMETERS_AUDIT.md)**

### 訊號處理可視化

視覺化壓縮曲線、時域響應與頻率特性：

- [dsp_compression_curves.png](dsp_compression_curves.png) — 4 面板壓縮曲線分析
- [dsp_time_response.png](dsp_time_response.png) — 包絡檢測器時域響應
- [visualize_dsp_parameters.py](visualize_dsp_parameters.py) — 可重現的 Python 分析腳本

---

## 許可

本專案採 MIT 許可。詳見 [LICENSE](LICENSE) 文件。

---

## 貢獻指南

我們歡迎對 Hark 的貢獻！請按照以下步驟：

1. Fork 本倉庫
2. 建立特性分支 (`git checkout -b feature/amazing-feature`)
3. 提交更改 (`git commit -m 'Add amazing feature'`)
4. 推送至分支 (`git push origin feature/amazing-feature`)
5. 開啟 Pull Request

---

## 聯絡方式

**主要開發者**: Hark Audio Team  
**問題回報**: GitHub Issues  
**建議與反饋**: GitHub Discussions
