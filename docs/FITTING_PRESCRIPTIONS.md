# 聽力圖處方增益計算方法（DSL v5 / NAL-R）

本文件說明 Hark 等化器「套用聽力圖」功能的兩種處方公式實作：
**DSL v5 by Hand（成人修正靜態近似）** 與 **NAL-R**。
程式碼位於 `app/src/main/java/com/wcy/hark/audio/fitting/Prescriptions.kt`，
由 `EqViewModel.applyFitting()` 呼叫。

## 共同流程

1. 讀取純音聽力測驗儲存的左右耳聽力圖閾值（dB HL），測試頻率：
   250, 500, 1000, 2000, 3000, 4000, 6000, 8000 Hz。
2. **缺測頻率內插**：未測的頻率以左右鄰近已測值在**對數頻率軸上線性內插**；
   超出已測範圍取最近端點值。整耳皆未測則該耳不套用。
3. 對 16 段 EQ 的每個中心頻率（250–8000 Hz, 1/3 倍頻程）計算處方插入增益。
4. 增益下限 0 dB（處方為負不衰減）、上限 **+30 dB**（設備數位增益上限，
   輸出端有 limiter 防削波）。有頻段觸頂時以 Toast 揭露
   「未能完全達到處方目標」，供實驗記錄。
5. 使用者模式按鈕固定套用 DSL v5；實驗模式可下拉選擇 DSL v5 / NAL-R。

> **增益的物理意義**：本 app 的 EQ 增益為數位增益（dB）。在耳機頻率響應
> 近似平坦的假設下，數位增益變化 ≈ 耳道內聲壓級變化，故以處方之
> 「插入增益」直接對映。絕對輸出級請配合耳機逐頻率校準表
> （見 `docs/` 內校準相關文件）詮釋。

---

## DSL v5 by Hand（成人修正靜態近似）

依據 Western University 國家聽力學中心（NCA）公開的
**「DSL v5 by Hand」** 文件實作。該文件提供 DSL v5 的查表版目標，
與軟體版處方高度相近（差異列於其 Appendix 1）。

### 計算式

```
gain(f) = REAR65(HTL, f) − InputSPL65(f) − 7 − 3·[雙耳]
```

- **REAR65(HTL, f)**：by-Hand **Table 7**（Pediatric Targets for Mid Speech,
  65 dB SPL 輸入）查得的目標真耳輸出（dB SPL re: ear canal）。
  整張 23 列（閾值 0–110 dB HL，5 dB 一階）× 9 頻率
  （250–6000 Hz）嵌入程式為常數陣列。
  - 閾值落在表列之間時**線性內插**兩列。
  - EQ 中心頻率落在表格頻率之間時於**對數頻率軸線性內插**；
    8000 Hz 超出表格範圍，沿用 6000 Hz 欄位值。
- **InputSPL65(f)**：worksheet「Subtract input SPL」列，即 65 dB SPL
  語音在各頻帶的輸入聲壓級：

  | Hz | 250 | 500 | 750 | 1000 | 1500 | 2000 | 3000 | 4000 | 6000 |
  |---|---|---|---|---|---|---|---|---|---|
  | dB SPL | 55 | 57 | 52 | 50 | 48 | 44 | 42 | 41 | 40 |

- **−7 dB 成人修正**：Table 7 為小兒（Pediatric）目標；by-Hand Appendix 1
  第 1 點指出成人目標對中度聽損約低 7 dB。本 app 受眾為成人，固定套用。
- **−3 dB 雙耳修正**：Appendix 1 第 4–5 點，成人雙耳配戴時語音目標再減
  3 dB。左右耳聽力圖皆存在時套用；僅單耳有資料時不套用。

### 計算示例（閾值 50 dB HL、雙耳）

| 頻率 | REAR65 | InputSPL | −7 | −3 | 增益 |
|---|---|---|---|---|---|
| 250 Hz | 81 | 55 | −7 | −3 | **16 dB** |
| 1000 Hz | 77 | 50 | −7 | −3 | **17 dB** |
| 4000 Hz | 82 | 41 | −7 | −3 | **31 → 夾至 30 dB** |

### 已揭露的簡化（論文撰寫時應載明）

- 僅取 65 dB SPL（中等語音）單一輸入級的目標 → **靜態線性增益**；
  原始 DSL v5 為輸入級相依（55/65/75 dB 各有目標，需逐頻段 WDRC）。
- 未實作 RECD 與麥克風位置效應修正：兩者用於將真耳目標換算至 2cc
  耦合腔以驗證助聽器；本情境為耳機直接播放且另有逐頻率耳機校準，
  視為 0。
- 未實作 by-Hand Appendix 1 之目標頻率平滑化、傳導性聽損修正。
- 8000 Hz 目標沿用 6000 Hz 欄位（表格未提供）。

---

## NAL-R（Byrne & Dillon, 1986）

澳洲國家聲學實驗室的線性處方公式，公開發表、廣泛用於成人線性放大。

### 計算式

```
H3FA  = (HTL500 + HTL1000 + HTL2000) / 3
X     = 0.15 × H3FA
IG(f) = X + 0.31 × HTL(f) + k(f)
```

- **H3FA**：500/1000/2000 Hz 三頻平均閾值（缺測時同樣內插）。
- **k(f)** 頻率修正項（dB）：

  | Hz | 250 | 500 | 750 | 1000 | 1500 | 2000 | 3000 | 4000 | 6000 | 8000 |
  |---|---|---|---|---|---|---|---|---|---|---|
  | k | −17 | −8 | −3 | +1 | +1 | −1 | −2 | −2 | −2 | −2* |

  \* 原始論文至 6000 Hz，8000 Hz 沿用高頻值。
  EQ 中心頻率落在表列之間時於對數頻率軸線性內插。

### 計算示例（平坦 50 dB HL 聽損）

H3FA = 50，X = 7.5。

| 頻率 | X | 0.31×HTL | k | 增益 |
|---|---|---|---|---|
| 250 Hz | 7.5 | 15.5 | −17 | **6 dB** |
| 1000 Hz | 7.5 | 15.5 | +1 | **24 dB** |
| 4000 Hz | 7.5 | 15.5 | −2 | **21 dB** |

可見 NAL-R 天生壓低低頻增益（避免低頻遮蔽語音），高頻依聽損給足。

---

## 兩處方比較（同一 50 dB HL 平坦聽損、雙耳）

| 頻率 | DSL v5（本實作） | NAL-R |
|---|---|---|
| 250 Hz | 16 dB | 6 dB |
| 1000 Hz | 17 dB | 24 dB |
| 4000 Hz | 30 dB（夾頂） | 21 dB |

DSL 目標整體較高（源自「最大可聽度」理念），NAL 追求「等響度／舒適」，
低頻明顯保守。實驗模式可切換兩者比較主觀偏好與語詞辨識表現。

## 參考文獻

- *DSL v5 by Hand*. Child Amplification Lab, National Centre for Audiology,
  Western University, London, ON, Canada.
- Bagatto, M., Moodie, S., Scollie, S., et al. (2005). Clinical protocols for
  hearing instrument fitting in the Desired Sensation Level method.
  *Trends in Amplification*, 9(4), 199–226.
- Scollie, S., Seewald, R. C., Cornelisse, L., et al. (2005). The Desired
  Sensation Level Multistage Input/Output Algorithm.
  *Trends in Amplification*, 9(4), 159–197.
- Byrne, D., & Dillon, H. (1986). The National Acoustic Laboratories' (NAL)
  new procedure for selecting the gain and frequency response of a hearing
  aid. *Ear and Hearing*, 7(4), 257–265.
