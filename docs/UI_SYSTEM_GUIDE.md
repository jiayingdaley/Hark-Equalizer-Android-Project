# Hark 助聽應用程式 — UI 介面設計與互動邏輯指南

本指南詳細說明了 **Hark 應用程式中所有使用者介面 (UI)** 的版面配置、視覺美學、操作元件、與底層音訊資料庫的資料流綁定（Data Binding）機制。

---

## 🎨 視覺美學系統與人機互動設計 (Design Aesthetics)

為了給使用者（尤其是輕中度聽力損失者）提供尊榮、直覺、防眩光的頂級體驗，全專案遵循以下介面美學：
- **防眩光背景底色**：XML 介面全域採用 `#F5F7FA`（優雅淺灰藍色），降低亮色模式下的刺眼感；Compose 頁面採用 HSL 細緻漸變底色。
- **高對比控制項**：以 Hark 主色藍紫色（`#536DFE`）與粉紅色（`#FF4081`）作為主要按鈕及狀態激活色，提高視覺可辨識度。
- **沉浸式頂部安全欄**：Compose 啟用透明狀態列，將標題控制元件透過 `statusBarsPadding()` 下推；XML 根 Layout 透過 `fitsSystemWindows="true"` 防禦相機劉海與狀態欄圖標遮擋，杜絕白色色塊。
- **繁體中文語系**：介面完全採用臺灣在地化助聽與聽力學術語（例如：「聽力檢測」、「等化器微調」、「語詞辨識度」）。

---

## 📱 第一部分：Compose 互動畫面 (4 大面板)

Compose 畫面全部基於單向資料流 (UDF) 架構，所有狀態均保留在 `EqViewModel` 中，狀態變更即時反應至 UI 樹。

### 1. 助聽主控制面板 (HarkMainScreen)
- **程式檔案**：[HarkMainScreen.kt](file:///Users/shrruei/Desktop/Gemini%20CLI/Hark/app/src/main/java/com/wcy/hark/ui/screen/HarkMainScreen.kt)
- **畫面定位**：App 啟動預設首頁。
- **UI 元件與功能解析**：
  1. **Hark 品牌標題**：頂部大字體 "Hark" 品牌文字（已套用狀態列 Padding 防止遮擋）。
  2. **助聽器總開關 (Master Switch Card)**：
     - 一個大尺寸的切換卡片。開啟時底色變為主題漸變色，並伴隨「狀態：正在偵測音訊裝置...」或「狀態：已啟用」的即時文字回饋。
     - **底層綁定**：綁定 `viewModel.statusText` 與 `isEngineOn`。點擊時會啟動/關閉前景服務 `HarkAudioService`，並調用 C++ 引擎的 `start()` / `stop()`。
  3. **音訊收音來源選擇 Tab (Audio Source Selector)**：
     - **環境助聽 (麥克風)**：強制調用耳機或手機麥克風進行實時現場聲音採集與放大。
     - **手機影音 (內部音訊)**：停用實時麥克風，開啟 `SystemDspManager` 懸浮球對背景媒體播放（YouTube/Spotify）套用等化補償。
  4. **情境模式卡片網格 (Situational Mode Grid)**：
     - 包含 4 個精緻卡片按鈕：**全向模式、人聲模式、戶外模式、影音模式**，以及一個大尺寸的 **自動智慧切換 (AUTO) 開關**。
     - 自動模式開啟時，會顯示動態呼吸燈動畫，提示使用者 `SceneManager` 正在每 5 秒進行頻譜特徵比對並自動調整參數。
  5. **耳機連接警告橫幅**：若未檢測到耳機插入，主開關下方會彈出警告卡片，提示用戶「請插入耳機或連接藍牙耳機以啟用助聽功能」。
  6. **功能跳轉按鈕組**：
     - **聽力檢測**：點擊開啟 `TestSelectActivity`。
     - **等化器微調**：切換至等化器微調畫面。
     - **實驗調試面板**：切換至 DSP 調試儀表板。

---

### 2. 等化器微調面板 (HarkEqualizerScreen)
- **程式檔案**：[HarkEqualizerScreen.kt](file:///Users/shrruei/Desktop/Gemini%20CLI/Hark/app/src/main/java/com/wcy/hark/ui/screen/HarkEqualizerScreen.kt)
- **畫面定位**：用於手動微調 16 個頻段（31 Hz 至 16 kHz）的個人聽力損失補償增益。
- **UI 元件與功能解析**：
  1. **返回標題欄**：頂部帶有返回箭頭與當前模式提示的標題列。
  2. **16 段 EQ 曲線 Canvas (EqualizerCurveDisplay)**：
     - 一個自定義繪製的貝氏曲線 Canvas。當拖動下方的滑桿時，曲線會跟隨即時平滑起伏，直觀地向用戶顯示當前音頻頻譜的增益補償曲線。
  3. **16 個垂直滑桿組 (Vertical Sliders)**：
     - 對應 16 個標準頻率點（31, 62, 125, 250, 500, 1k, 2k, 4k, 8k, 16k Hz 等）。
     - 增益範圍為 `-12 dB` 至 `+12 dB`，滑動時會有震動微回饋，並即時寫入 C++ 的 `mBandGains` 進行無鎖參數更新。
  4. **預設效果器按鈕組 (Preset Buttons)**：
     - 提供「重設（全 0 dB）」、「人聲增強」、「高頻補償」等一鍵預設配置。
  5. **保存設定按鈕**：點擊後將增益陣列寫入 Jetpack DataStore，確保下次開啟 App 時自動加載。

---

### 3. 測試歷史紀錄面板 (TestHistoryScreen)
- **程式檔案**：[TestHistoryActivity.kt](file:///Users/shrruei/Desktop/Gemini%20CLI/Hark/app/src/main/java/com/wcy/hark/audiometry/TestHistoryActivity.kt)
- **畫面定位**：檢視使用者以往所有的聽力評估報告。
- **UI 元件與功能解析**：
  1. **雙測試 Tab 分頁**：
     - **純音聽力紀錄**：從外部儲存區加載所有 `PureTone` 格式的 CSV 檔案。
     - **語詞辨識紀錄**：從本地 SQLite 資料庫讀取所有語詞測驗記錄。
  2. **歷史紀錄清單**：卡片式列表，清晰展示測試日期、測試耳別（左耳/右耳/雙耳）、答對率或信度警告狀態。
  3. **純音明細 Dialog (內嵌動態聽力圖)**：
     - 點選純音卡片後彈出。
     - **⚠️ 信度警告卡片**：如果 1000 Hz 兩次測試差值超過 10 dB，對話框頂部會以鮮明橘黃色顯示警告，提示該次數據不夠可靠。
     - **環境噪音分析**：顯示測試時偵測到的背景分貝值（例如 `32 dB SPL`）及臨床環境適合度評語。
     - **內嵌聽力圖 (Embedded Audiogram)**：對話框中心嵌入 `AudiogramView` 畫布，直接以紅圈(O)藍叉(X)加粗繪製出當次檢測的聽力圖，並配有滾動條防溢出。
     - **數值對照表**：以表格方式列出各個頻率（250 Hz 至 8k Hz）對應的聽力閾值（dB HL）。
  4. **語詞錯題診斷 Dialog**：
     - 點選語詞紀錄後彈出。
     - 列表顯示該次測試中，答錯的題目明細（正確語詞 vs 使用者誤選語詞），協助研究人員進行語音聲學錯題分析。

---

### 4. 實驗調試面板 (DspTestScreen)
- **程式檔案**：[DspTestScreen.kt](file:///Users/shrruei/Desktop/Gemini%20CLI/Hark/app/src/main/java/com/wcy/hark/ui/screen/DspTestScreen.kt)
- **畫面定位**：研發與學術測試專用，提供 DSP 引擎內部狀態的實時監控。
- **UI 元件與功能解析**：
  1. **實時診斷儀表板 (Diagnostic Dashboard)**：
     - 動態刷新顯示底層 C++ 引擎的運作指標：**XRun 數值**（音訊斷音緩衝溢出次數）、**I/O Latency**（實時延遲毫秒數）、**CPU 負載百分比**、**實時輸入/輸出音量分貝值**。
     - **底層綁定**：綁定 JNI 的 `getDiagnosticData()` 方法，每 300 毫秒輪詢更新。
  2. **DSP 模組 Bypass 開關組**：
     - 提供 DC Blocker、降噪器 (NS)、自我語音偵測 (OVD)、WDRC 壓縮器、Limiter 限幅器的獨立開關，便於單獨進行演算法效果對比。
  3. **OVD 自我語音偵測儀表**：
     - 顯示實時 OVD 激活狀態（使用者說話時亮起綠色指示燈，底層低頻增益自動衰減）。
  4. **手勢掃描狀態燈**：
     - 顯示 GestureDetector 的實時特徵（手遮耳朵時狀態轉為 `SCANNING`，3秒後鎖定為 `LOCKED`）。

---

## 📝 第二部分：XML 聽力評估介面 (7 大 Activities)

這些頁面是遵循臨床醫學聽力檢測（ISO 8253-1）設計的傳統 Layout，以嚴謹、無干擾的交互引導用戶完成測試。

### 5. 測驗選擇介面 (TestSelectActivity)
- **佈局檔案**：`activity_test_select.xml`
- **元件與邏輯**：
  - 一張簡潔的歡迎卡片。
  - **純音聽力測試按鈕**：點擊跳轉至 `SelectEarActivity` 開始聽力圖檢測。
  - **語音語詞測試按鈕**：點擊跳轉至 `SpeechAudiometryExplanationActivity` 開始 SRT 字詞測驗。
  - **返回鍵**：回退至 Compose 主面板。

---

### 6. 測試前噪音偵測與耳別選擇介面 (SelectEarActivity)
- **佈局檔案**：`activity_select_ear.xml`
- **元件與邏輯**：
  - **左耳/右耳選擇按鈕**：高亮大尺寸按鈕，供用戶點擊選擇本次測試從哪隻耳朵開始。
  - **即時環境噪音計 (Noise Meter)**：
    - 一個由綠、橘、紅三色組成的長條標尺（進度條）。
    - 頂部指針（`noise_indicator`）會根據實體麥克風採集到的 dB SPL 數值即時左右滑動。
    - **邏輯**：若雜訊高於 50 dB SPL（指針滑向橘/紅區），介面會顯示文字警告：「環境噪音過大，請移至安靜處測試」，引導用戶前往合規的測試環境。

---

### 7. 純音聽閾測試介面 (PureToneTestActivity)
- **佈局檔案**：`activity_pure_tone_test.xml`
- **元件與邏輯**：
  - **當前測試頻率與耳別**：例如居中超大字體顯示的 "左耳 - 1000 Hz"。
  - **巨大圓形響應觸控區 (Response Card)**：
    - 一個覆蓋螢幕中央大半區域的圓形卡片.
    - **交互**：當純音播放時，使用者只要「聽到聲音」，就必須立刻用手指觸摸或按住該區域。按住時會有藍色漣漪動畫，鬆開或超時則判定為未聽到。
  - **測試總進度條**：橫跨底部的 Progress Bar，即時顯示當前測試已完成的頻率百分比（共 7 個頻率階段）。
  - **暫停按鈕**：點擊可以暫停純音發聲與倒數，便於使用者在測試中進行短暫休息。

---

### 8. 語詞辨識測試介面 (SRTTestActivity)
- **佈局檔案**：`activity_srt_test.xml`
- **元件與邏輯**：
  - **題數進度指示**：顯示 "第 3 / 25 題" 及總答對題數。
  - **4 選 1 繁體中文字詞按鈕網格**：
    - 系統以隨機分貝播放一題語音（如「衣服」），使用者必須從 4 個選項按鈕（例如「衣服、皮膚、西瓜、蘋果」）中點選聽到的詞。
    - 4 個按鈕大小適中、字體清晰，適合輕度手部抖動或高齡使用者點擊。
  - **「聽不清楚 / 沒聽到」按鈕**：下方顯眼的灰色按鈕，供使用者在無法辨識時點擊，避免強行亂猜影響語音辨識率 (SRT) 的真實數據。
  - **提前結束與暫停按鈕**：供緊急情況下退出測試。

---

### 9. 聽力圖結果呈現介面 (AudiogramActivity)
- **佈局檔案**：`activity_audiogram.xml`
- **元件與邏輯**：
  - **自定義聽力圖 Canvas (AudiogramView)**：
    - 佔據畫面 2/3 的專業聽力學圖表。
    - 橫軸代表頻率（125 Hz 至 8k Hz），縱軸代表聽力損失分貝（-10 dB HL 至 120 dB HL，注意：聽力學縱軸是**倒置的**，0 在上方，120 在下方）。
    - 藍色「X」代表左耳，以藍折線連接；紅色「O」代表右耳，以紅折線連接。
    - 符號與連接線顯著加粗，精準對齊，無任何偏斜。
  - **雙耳平均聽損等級摘要卡片**：
    - 依據 500, 1k, 2k, 4k Hz 計算四頻率平均聽閾（PTA），並給出「正常聽力 / 輕度聽損 / 中度聽損」的臨床分級評語。
  - **完成按鈕**：點擊保存 CSV 並退出，返回主畫面。

---

### 10. 語詞測驗結果頁 (SRTTestResultActivity)
- **佈局檔案**：`activity_srt_test_result.xml`
- **元件與邏輯**：
  - **答對率環形指示**：大字體顯示例如 `88%` 的答對率，並顯示 `22 / 25` 的答對題數。
  - **語詞理解能力臨床建議卡片**：
    - 根據答對率分級給出評語（例如：`答對率 > 90%` 顯示「語音辨識良好，社交溝通無明顯困難」；`50% ~ 80%` 顯示「在嘈雜環境下辨識率會下降，建議搭配助聽器以增強字詞可懂度」）。
  - **完成按鈕**：返回測驗選擇首頁。

---

### 11. 語詞測驗說明頁 (SpeechAudiometryExplanationActivity)
- **佈局檔案**：`activity_speech_audiometry_explanation.xml`
- **元件與邏輯**：
  - **學術科普卡片**：說明純音檢測與語詞檢測的差異，向使用者解釋為什麼測了純音聽力圖，還要做字詞理解率測驗。
  - **DSP 預調整開關 (Apply DSP Prep Switch)**：
    - 讓用戶選擇在語詞測試時，是否要套用先前純音聽力圖所計算出的 EQ 聽損增益進行補償。這對於驗證「助聽等化演算法是否能切實改善用戶的語詞辨識率」這項學術論文核心假說非常關鍵。
  - **開始調整音量按鈕**：點擊彈出 `VolumeAdjustmentDialogFragment` 滑桿，引導用戶調整到最舒服的主觀字詞播放音量後，正式進入 SRT 測驗。

---

## 🛠️ 給接手開發者的 UI 維護與擴展指南

1. **若要更換 Compose 介面顏色**，請直接編輯 [Color.kt](file:///Users/shrruei/Desktop/Gemini%20CLI/Hark/app/src/main/java/com/wcy/hark/ui/theme/Color.kt) 與 [Theme.kt](file:///Users/shrruei/Desktop/Gemini%20CLI/Hark/app/src/main/java/com/wcy/hark/ui/theme/Theme.kt)，勿在各畫面散落 Hardcoded 顏色數值。
2. **在 XML 頁面新增控制項時，請確保根容器的 `android:fitsSystemWindows="true"` 以及 `android:background="#F5F7FA"` 屬性未被移除**，否則會出現頂部遮擋或突兀白邊。
3. **在 `AudiogramView` 中若需要新增繪製點別（如骨導氣導、遮蔽點）**，請修改 `drawEarResults` 內的幾何計算公式，並確保符號線寬 `strokeWidth` 設在 `5f` 以上以維持一致的高畫質清晰度。
