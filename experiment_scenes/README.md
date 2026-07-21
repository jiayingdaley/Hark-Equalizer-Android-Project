# 環境輔聽問卷用情境音（喇叭播放）

16 kHz mono 16-bit，各 60 秒，RMS 統一 −20 dBFS（各情境經同一喇叭同一音量播放時，
長期平均聲壓級一致）。含語音成分的情境改用 **ISTS（International Speech Test
Signal）**，其餘（babble／穩態噪音／突發聲）為固定隨機種子 20260708 之合成訊號。

| 檔案 | 情境 | 訊號來源 | 施測目的 |
|---|---|---|---|
| scene1_babble.wav | 多人交談背景（10 軌語料疊加）| 合成（詞語語料）| 噪境舒適度、降噪體驗 |
| scene2_speech_quiet.wav | 安靜下真實語音 | **ISTS**（片段 A，60–120 s）| 清晰度、延遲/回音感 |
| scene3_steady_noise.wav | 穩態粉紅噪音（冷氣/車流感）| 合成 | 穩態降噪、底噪感受 |
| scene4_transients.wav | 低噪背景+每 4–6 s 突發脈衝聲 | 合成 | 瞬態抑制、驚嚇感 |
| scene5_speech_in_babble_5dB.wav | 語音+多人背景（SNR +5 dB）| **ISTS**（片段 B，300–360 s）+ babble | 綜合聽辨與舒適度 |

## 為何用 ISTS

ISTS 是 EHIMA／HAPI 發展、國際聽力學界廣泛用於助聽器評估的標準化語音刺激
（六種語言錄音剪接而成，語音頻譜與調變包絡皆保留，但不具可懂度，避免測試者
因聽懂內容而產生語意/學習效應，僅反映音質與可懂度感受本身）。
引用：Holube, I., Fredelake, S., Vlaming, M., & Kollmeier, B. (2010).
*Development and analysis of an International Speech Test Signal (ISTS).*
International Journal of Audiology, 49(12), 891–903.

情境 1／3／4（babble、穩態噪音、突發聲）需要與語音統計特性不同的訊號類型
（多語者疊加、粉紅噪音、脈衝），故維持合成產生（見 `gen_scenes.py`，
固定種子 20260708 可重現）；情境 2／5 的語音成分改為真實 ISTS 片段
（`gen_scenes_v2.py`，自 10 分鐘母源檔切取兩段互不重疊的 60 秒片段）。

## 施測建議
- 喇叭與測試者距離固定（建議 1 m、正前方），播放裝置音量固定並記錄。
- 每個情境（v2 比較制問卷）：先「輔聽 OFF」聽約 30 秒 → 切「輔聽 ON」聽約 30 秒 →
  立即回答該情境一題「ON 相對 OFF」比較題（−3～+3，0＝沒差別）。情境 4 由實驗者
  於固定距離現場拍手（不播 scene4 檔亦可，兩者擇一並記錄）。
- 產生腳本：`scratchpad/gen_scenes.py` + `scratchpad/gen_scenes_v2.py`（conda Datamining 環境）。
- 母源檔 `ISTS - International Speech Test Signal (10 minutes).mp3` 僅供產生素材使用，
  不隨 App 散布；正式論文方法段落可直接引用上述文獻與 EHIMA 標準訊號來源。
