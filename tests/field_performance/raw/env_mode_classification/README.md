# 環境模式自動判斷（SceneManager）實地錄音驗證

Hark 的自動環境判斷（`SceneManager.runAutoAnalysis()`）目前用一組寫死的門檻值，
每 5 秒取一次 `HarkAudioBridge.getEnvironmentEnergy()`（5 個頻段：500/1k/2k/3k/4k Hz
能量）做分類：

```
lowFreq  = band[500Hz] + band[1kHz]
highFreq = band[3kHz] + band[4kHz]
total    = 全部 5 頻段總和

lowFreq  > total * 0.7  → OUTDOOR       （低頻能量佔比過高：風切/交通噪音）
highFreq > total * 0.4  → CONVERSATION  （高頻能量明顯：語音清晰度需求）
total    < 0.001        → TRANSPARENCY  （極安靜）
其餘                     → TRANSPARENCY  （預設）
```

`CINEMA` 目前不是靠這個頻譜判斷觸發，而是由 `MediaSessionObserver` 偵測到
系統正在播放媒體（例如音樂/影片 App）時直接切換。

這兩個門檻值（0.7、0.4）是憑經驗設的，從未用真實環境資料驗證過。這批錄音
的目的：**用多支耳機在真實場域錄下對應每個模式的聲音，回頭驗證/校正這兩個
門檻值，並檢查不同耳機麥克風的頻率響應會不會讓同一個環境被判成不同模式。**

## 錄音協定

### 1. 參考音（`reference_tone/`）——每支耳機錄一次，用來校正麥克風靈敏度差異

不同耳機內建麥克風的靈敏度/頻率響應不同，同一個環境錄出來的「能量比例」會
因耳機而有落差。錄一段參考音，之後可以把每支耳機的錄音正規化到同一個基準
再比較，才不會把「麥克風差異」誤判成「環境判斷不準」。

- 訊號：手機播放 1kHz、−20 dBFS 純音，或使用 [寬頻粉紅噪音](../../stimuli/pink_noise_30s.wav) 進行多頻段（500/1k/2k/3k/4k Hz）麥克風頻譜響應校正（推薦，以扣除不同耳機的高低頻靈敏度偏置差異）。
- 距離：喇叭正前方 1 公尺
- 每支耳機錄 10–15 秒
- 檔名：`<耳機型號>_raw.m4a`，例如 `airpods_raw.m4a`、`hd400u_raw.m4a`


### 2. 各模式環境音——每支耳機、每個模式錄一次

**重點：這裡不要播測試訊號，要錄真實環境音**——因為分類器吃的是真實場域的
頻譜能量分布，不是要測耳機的頻率響應（那是校正畫面的工作）。錄音時耳機要
實際戴著（跟平常使用時的麥克風位置一致），每段建議 2–3 分鐘：

| 資料夾 | 對應模式 | 錄音方式 |
|---|---|---|
| `mode_transparency_quiet/` | TRANSPARENCY | 安靜室內，不說話，純環境底噪 |
| `mode_conversation/` | CONVERSATION | 與真人面對面對話，距離約 0.5–1 公尺（一般對話距離），錄真實對話而非唸稿 |
| `mode_outdoor/` | OUTDOOR | 戶外街道/有風的地方，正常走動配戴，錄真實風切/交通噪音 |
| `mode_cinema_media/` | CINEMA | 手機或喇叭播放一段電影/影片聲音，耳機正常配戴收音 |

檔名同樣是 `<耳機型號>_raw.m4a`，放進對應資料夾。

## 之後怎麼分析

1. 用每支耳機的 `reference_tone` recording 算出相對於基準的麥克風增益偏移，
   之後所有頻段能量都先扣掉這個偏移再比較。
2. 對每段模式錄音做逐秒（或每 5 秒，對齊 App 實際判斷週期）分幀，用跟
   `HarkAudioBridge.getEnvironmentEnergy()` 相同的 5 頻段（500/1k/2k/3k/4k Hz）
   算 `lowFreq`／`highFreq`／`total` 比例。
3. 用「錄音時的真實標籤」（這段本來就是在錄 OUTDOOR）當 ground truth，套用
   現有的 0.7／0.4 門檻，看有多少比例的幀會被錯判——做出一張耳機 × 真實模式
   的混淆矩陣。
4. 如果混淆矩陣顯示現有門檻在某些耳機上系統性偏移，用資料重新掃描找出
   更好的門檻值（或提出用比例分布而非單一門檻的分類方式）。
5. 順便畫出各模式的環境音量（相對 dB，用參考音校正），了解每個模式下 App
   預設的音量/壓縮參數是否合理。

分析腳本之後會放在 `../code/`（沿用專案 conda「Datamining」環境），所需的測試刺激音訊（Chirp, Pink Noise, Chinese Pseudo-speech）已統一整理在 [../../stimuli](../../stimuli/) 目錄中。

