#!/usr/bin/env python3
"""Surgically revise the thesis DOCX while preserving Zotero field XML.

The script works directly on OOXML so Zotero ADDIN fields and unrelated
formatting remain untouched. It rewrites selected prose, repairs appendix
hierarchy, rebuilds caption-number fields/bookmarks, converts plain body
figure/table mentions to REF fields, and requests field refresh on open.
"""

from __future__ import annotations

import argparse
import copy
import re
import zipfile
from pathlib import Path

from lxml import etree


W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
XML = "http://www.w3.org/XML/1998/namespace"
NS = {"w": W}
QN = lambda name: f"{{{W}}}{name}"


REPLACEMENTS: dict[int, str | list[str]] = {
    31: (
        "Methods: Hark uses a Kotlin user interface and a low-latency C++ audio "
        "engine built with Oboe and AAudio. In the real-time microphone path, "
        "16 user gain settings are mapped to eight Linkwitz–Riley fourth-order "
        "(LR4) bands. Each band then undergoes wide dynamic range compression "
        "(WDRC), after which the mapped prescription gain is applied. The media "
        "path is separate and uses Android DynamicsProcessing with a 16-band "
        "pre-equalizer and multiband compression. Both paths include output "
        "protection, and the microphone path can also use optional speech-"
        "enhancement modules. Personalized gain was generated from pure-tone "
        "thresholds using a simplified method based on the DSL v5 concept. The "
        "system also included a Mandarin two-syllable word test and an error "
        "analysis method that linked incorrectly recognized words to related "
        "frequency ranges. End-to-end audio delay was measured using a loopback "
        "method to compare the traditional Java AudioTrack path with the Oboe/"
        "AAudio path. Fifteen adults who reported no hearing problems and for "
        "whom the simulated loss was correctly applied participated in the "
        "behavioral analysis (one further participant was excluded entirely "
        "because the simulated loss was not correctly applied). Mild high-"
        "frequency hearing loss (a steeply sloping S1 configuration) was "
        "simulated through signal processing. Each participant completed "
        "unaided and aided tests. The 50% word recognition sensation level "
        "(SL50) was measured in quiet, and the 50% speech reception threshold "
        "(SRT50) was measured in speech-shaped noise. An additional frequency-"
        "lowering benefit test compared, at a fixed presentation level, three "
        "conditions—simulated loss alone, with non-linear frequency compression "
        "(NLFC), and with NLFC plus full compensation—to separate frequency "
        "lowering from full compensation. Field recordings were also used to "
        "test the automatic environmental mode classification rules."
    ),
    244: (
        "本系統分析標準化詞表（如 HSE List 4）的錯誤模式，目的不是直接改寫處方，"
        "而是指出可能需要留意的頻率範圍，供使用者手動調整。"
    ),
    246: (
        "全詞計分要求整個詞都答對，優點是容易執行，缺點是看不出錯在哪裡。"
        "例如把「發（ㄈㄚ）」聽成「阿（ㄚ）」，全詞計分只會記為錯誤，"
        "無法指出問題是聲母遺漏而非韻母辨識。"
    ),
    247: (
        "音素層級分析可進一步標示聲母、韻母或聲調的差異，因此比全詞分數提供更多線索。"
        "本系統先比對目標詞與誤選詞的注音差異，再以兩個完整錄音的頻譜差異判斷可能涉及的"
        "頻率範圍；不以單一音素查表直接決定增益。"
    ),
    248: (
        "因此，本研究同時保留全詞正確率與音素差異：前者回答「整體表現如何」，"
        "後者協助回答「可能錯在哪裡」。"
    ),
    249: "混淆模式與頻譜差異",
    250: (
        "混淆分析用來整理哪些詞經常被誤選；頻譜分析則比較目標詞與誤選詞的能量差異。"
        "兩者合併後，系統才能提出有依據、但仍屬探索性的頻帶建議。"
    ),
    253: (
        "母音也會改變擦音的頻譜。例如 /u/（ㄨ）的圓唇動作可能產生較窄的高頻峰值。"
        "因此，同一聲母在不同母音環境下的錯誤不能一概而論，本系統以實際整詞錄音分析，"
        "避免只依音素名稱判斷。"
    ),
    307: (
        "本章先說明系統架構與操作流程，再介紹聽力檢測、錯題分析及音訊處理。"
        "重點是交代 Kotlin、資料儲存、JNI 與 C++ 引擎如何連接，以及聽力圖如何轉為即時處理參數。"
    ),
    308: "系統架構與資料流",
    309: (
        "Hark 採四層架構：展示層、資料層、橋接層與核心音訊層，如圖 3-1。"
        "四層是邏輯分工；展示層與資料層雖同在 Android 應用程式中，責任仍分開。"
    ),
    310: (
        "展示層負責畫面與操作流程；資料層保存增益設定、聽力圖與測驗紀錄；"
        "橋接層以 JNI 傳遞控制參數；核心音訊層負責低延遲即時處理。"
        "這樣可讓介面與資料管理容易維護，也把有時限要求的運算留在 C++ 音訊執行緒。"
    ),
    311: (
        "本節說明四層之間的資料流。Oboe 串流、獨佔模式、MMAP 與無鎖緩衝區等"
        "通用低延遲技術另整理於附錄 D，避免在方法章重複展開。"
    ),
    314: (
        "系統可選擇手機麥克風或耳機麥克風收音。手機麥克風通常能利用裝置的麥克風陣列，"
        "但輸出需搭配有線或低延遲耳機；耳機麥克風配戴較簡單，收音品質則受耳機本身與"
        "麥克風位置影響。使用者在啟動時選擇輸入源，系統並記錄該路徑供後續分析。"
    ),
    315: (
        "展示層與資料層（Kotlin）：介面採 MVVM。Jetpack Compose 與 Activity 負責主畫面"
        "及測驗流程；DataStore 保存增益、聽力圖與模式設定；SQLite schema v16 保存語詞、"
        "噪音語詞、A/B、問卷與 NLFC 效益拆解等逐題資料。純音結果另匯出含校正資訊的 CSV。"
    ),
    316: (
        "橋接層（JNI）：HarkAudioBridge 將 Kotlin 計算的 16 段目標增益送入 Native 引擎，"
        "並負責其他控制參數的型別轉換與無鎖傳遞。詳細映射方式見附錄 D.7。"
    ),
    317: "核心音訊層（C++）：管理串流並執行即時 DSP。",
    318: "Oboe 串流管理器：建立、啟停與重建低延遲音訊串流，並優先申請獨佔模式。",
    319: "DSP 處理順序如下，與 HarkAudioEngine.cpp 的實作一致：",
    324: (
        "8 頻帶 LR4 分頻與 WDRC：訊號先分成 8 帶，各帶分別執行 WDRC。"
        "接著才施加由 16 段設定映射而來的處方增益，以免先放大底噪而影響下擴展。"
        "之後再套用自身語音衰減，最後合成各頻帶。"
    ),
    332: (
        "系統提供全向、人聲、戶外與影音四組模式。自動分類只會在全向、人聲與戶外之間切換；"
        "影音模式必須由使用者手動選擇。"
    ),
    333: [
        (
            "四組模式的差異主要在 WDRC 強度與降噪開關。人聲模式壓縮最強"
            "（−30 dBFS、1.5:1），戶外次之（−25 dBFS、1.3:1），全向較溫和"
            "（−20 dBFS、1.2:1），影音最接近原始動態（−15 dBFS、1.1:1）。"
        ),
        (
            "人聲與戶外採較快的 5/200 ms 啟動／釋放時間，並開啟下擴展與降噪。"
            "全向與影音使用較長時間常數且關閉降噪，以減少抽吸感；影音模式尤其重視保留音樂起音。"
        ),
        (
            "自動分類每 250 ms 取得五個頻帶的能量，以 5 秒窗計算總能量與包絡調變。"
            "規則依序判斷安靜、戶外高能量與人聲調變；連續兩個視窗結果相同才切換，"
            "最短切換間隔為 10 秒。"
        ),
    ],
    334: [
        (
            "分類門檻由 4.6 節的實地錄音校定。低頻占比無法穩定區分情境，因此最後只使用"
            "總能量與包絡調變。三類準確率為全向 76%、人聲 75%、戶外 68%。"
        ),
        (
            "影音不納入自動分類，因為音樂與人聲的包絡統計重疊，強行分類容易誤判。"
            "此外，安靜門檻仍需在實機上依麥克風與自動增益狀態校定。"
        ),
    ],
    337: [
        (
            "Hark 有兩條音訊路徑。第一條是麥克風即時輔聽：聲音進入 C++／Oboe 引擎，"
            "依序完成降噪、選用的 NLFC、8 頻帶 LR4、逐帶 WDRC、映射後處方增益、"
            "自身語音衰減、限幅與軟削波。這條路徑不串接一組完整的 16 段 peaking EQ。"
        ),
        (
            "第二條是媒體音訊補償。Android 不允許本 App 直接攔截其他 App 的音訊樣本，"
            "因此改用系統 DynamicsProcessing：先套用 16 段 preEQ，再進入系統的 8 頻帶壓縮處理。"
        ),
    ],
    338: (
        "兩條路徑共用同一組 16 段使用者設定，但套用方式不同。Native 路徑先把 16 段設定"
        "映射為 8 個頻帶增益，並在各帶 WDRC 之後施加；媒體路徑則保留 16 段 preEQ。"
        "此差異是實作路徑的限制與取捨，不代表兩組處方。元件差異見附錄 D。"
    ),
    339: "系統操作與參數映射",
    340: (
        "系統流程為「檢測—補償—驗證—建議」。系統先產生初始設定，測驗後只提供文字建議；"
        "是否手動調整由使用者決定。"
    ),
    343: (
        "初始化與環境檢查：App 先確認採樣率與緩衝區，建立 Oboe 低延遲串流，再檢查環境噪音。"
        "若環境過吵，系統提示測驗可能受影響。使用者同時選擇手機或耳機麥克風，"
        "系統載入對應的前端設定。"
    ),
    344: (
        "基礎聽力評估：系統依改良式 Hughson–Westlake 程序，分耳測量 250–8000 Hz 的"
        "氣導聽閾，建立聽力圖。"
    ),
    345: (
        "處方生成與映射：聽力閾值先經 DSL v5 成人目標概念的簡化查表，得到 16 段目標設定。"
        "Native 輔聽路徑再把這 16 段映射為 8 個 LR4 頻帶增益；媒體路徑則送入 16 段 preEQ。"
        "WDRC 膝點、壓縮比與數位輸出上限採固定預設，不由處方計算。"
    ),
    346: (
        "即時補償：Native 引擎將麥克風訊號分成 8 帶，各帶先執行 WDRC，再施加映射後的處方增益，"
        "最後合成並輸出至耳機。"
    ),
    347: (
        "錯題回饋：使用者完成助聽後語詞測驗後，系統分析錯誤詞對的頻譜差異。"
    ),
    348: (
        "錯題分析：系統比對目標詞與誤選詞的「詞彙—頻率特徵矩陣」，整理低、中、高頻的"
        "過度代表情形。"
    ),
    349: (
        "建議與修正：結果只以探索性文字呈現，例如提醒高頻錯誤比例偏高。"
        "系統不自動套用 EQ，也未在本研究中完成「依建議調整後再次測驗」的效益驗證。"
    ),
    395: "錯題分析與文字建議流程",
    396: (
        "錯題分析的用途是補充總分，而不是自動驗配。系統比較答錯詞與目標詞的頻譜差異，"
        "找出可能偏多的低、中或高頻錯誤，再輸出文字建議。"
    ),
    397: (
        "本版本不會改寫使用者參數。為降低誤判，分析會降低高度相似詞對的權重，"
        "以題目誘答分布校正猜測偏差，並把「不確定」另行計數。有效錯題少於 5 筆，"
        "或三個頻帶差異不明顯時，不提供特定頻帶建議。"
    ),
    398: (
        "離線處理：本研究以 Praat 分析 HSE List 4 的 200 個實際錄音，取得 250–8000 Hz、"
        "16 個三分之一倍頻帶的能量分布。風險頻帶因此來自錄音量測，而非人工指定。"
    ),
    399: (
        "線上分析：答錯後，系統讀取詞彙特徵，排除「不確定」，再依詞對相似度與誘答分布"
        "修正權重，最後累計低、中、高頻錯誤比例。"
    ),
    400: "輸出條件：有效錯題至少 5 筆時，系統才顯示探索性文字建議。",
    401: (
        "輸出內容只指出可能需要留意的頻率範圍，不會自動計算並寫入一組已驗證的增益值。"
    ),
    402: (
        "因此，目前流程是「檢測—建議—人為確認」。自動套用 EQ、連動調整壓縮參數，"
        "以及調整後複測的效益驗證，均列為未來工作。"
    ),
    403: "音訊處理演算法與實作順序",
    404: (
        "本節依實際程式順序說明 Native 即時輔聽與媒體音訊兩條路徑，並交代各模組的功能與取捨。"
    ),
    405: "Native 的 16 段設定與 8 頻帶處理",
    406: (
        "系統保存 250–8000 Hz 共 16 段、左右耳分開的目標增益，範圍為 −24 至 +30 dB。"
        "這 16 段是使用者設定與資料儲存的解析度；Native 即時輔聽並不依序執行 16 顆"
        "peaking EQ。"
    ),
    407: (
        "Native 路徑會先把 16 段設定依頻率歸屬平均成 8 個增益值。訊號經 8 頻帶 LR4 分頻後，"
        "各帶先執行 WDRC，再施加對應的處方增益，最後合成。"
    ),
    408: (
        "正確順序為：前級處理 → 選用的 NLFC → 8 頻帶 LR4 分頻 → 各帶 WDRC →"
        " 8 帶處方增益 → 自身語音衰減 → 合成 → 限幅與軟削波。"
    ),
    409: (
        "媒體路徑則不同。Android DynamicsProcessing 先以 16 段 preEQ 做頻率塑形，"
        "再交由系統的 8 頻帶壓縮器處理。圖 3-11(a) 是此 16 段 preEQ／設定解析度的示意，"
        "不是 Native 路徑的前級串接模組。"
    ),
    410: (
        "Native 使用 8 頻帶 Linkwitz–Riley 四階（LR4）濾波器組。七個分頻點為 250、500、"
        "1000、1500、2500、4500 與 6000 Hz；相鄰低通與高通在分頻點同相，各為 −6 dB，"
        "合成後維持平坦。"
    ),
    411: (
        "LR4 濾波器以二階 IIR（biquad）組成，音訊格式為 48 kHz、32-bit 浮點立體聲。"
        "控制參數以原子變數送入音訊執行緒，並以平滑更新避免增益突變。其他 DSP 參數見表 3-1。"
    ),
    476: (
        "介面與儲存層保留 16 段設定；使用者模式把相鄰設定合併顯示為 8 個控制點，"
        "實驗模式可查看 16 段。Native 輔聽會把 16 段映射為 8 個 LR4 頻帶增益，"
        "媒體路徑才使用完整 16 段 preEQ。因此，8／16 段切換是操作與設定解析度的差異，"
        "不是 Native 內部串接兩組等化器。"
    ),
    603: (
        "HSE List 4 能涵蓋常見華語聲韻組合，但樣本與口音範圍仍有限。錯題頻譜歸因只使用"
        "單一語者的整詞錄音，得到的是聲學相似度代理值，不是真實聽損者的混淆矩陣；"
        "整詞分析也可能稀釋短暫的高頻聲母線索。SII、STOI 與 HASPI 在本研究僅作理論介紹，"
        "尚未產生可列入結果章的客觀分數。"
    ),
    608: "結論、研究限制與未來展望",
    610: "本研究完成 Android 低延遲輔聽系統 Hark。依第五章結果，主要貢獻有三項：",
    611: (
        "第一，完成 Android 即時輔聽原型。系統整合 Kotlin、JNI、C++ 與 Oboe／AAudio；"
        "Native 路徑把 16 段設定映射至 8 個 LR4 頻帶，各帶先做 WDRC 再施加處方增益。"
        "端到端延遲由傳統 Java 路徑約 208 ms 降至 92 ms。"
    ),
    612: (
        "第二，完成「檢測—處方—補償—驗證」的半自動流程。App 整合自調式純音測驗、"
        "簡化 DSL v5 處方、華語語詞測驗與聽損模擬。正式行為分析納入 15 位受試者；"
        "另有 1 位因模擬未正確套用而整筆排除。"
    ),
    613: (
        "第三，得到可聽度改善與噪音效益邊界的初步證據。安靜情境中，14 位有有效基準線的"
        "受試者 SL50 均改善，平均為 10.2 dB；噪音情境平均改善為 0.0 dB。單獨 NLFC 的"
        "辨識率平均增加 1.3%，NLFC 加完整補償則平均增加 35.0%。"
    ),
    614: (
        "此外，自動情境分類在全向、人聲與戶外三類的準確率分別為 76%、75% 與 68%；"
        "影音模式維持手動選擇。耳機 ETSPL 校正鏈已完成系統實作，但尚未完成絕對量測。"
    ),
    616: (
        "本研究的限制分為樣本、校正與分析三方面。正式行為驗證只有 15 位聽力正常受試者，"
        "樣本小且未做推論統計，因此結果只能支持工程可行性，不能直接推論真實聽損族群。"
    ),
    617: (
        "聽損模擬以數位衰減實現，受約 90 dB 餘裕限制，只有 S1、N1、N2 等輕度組態可在"
        "全頻率合理呈現。本研究以 S1 為主；N2 對照尚未完成。"
    ),
    618: (
        "耳機尚未完成 ETSPL 與 OSPL90 絕對校正，因此結果以個人聽閾為基準的 dB SL 表示，"
        "不能當作跨裝置的絕對 dB HL，也不能宣稱符合 OSPL90 上限。現行處方也只是 65 dB SPL"
        "單一輸入級的靜態近似，並非完整 DSL v5 多輸入級演算法。"
    ),
    619: (
        "安靜與噪音測驗均固定先未輔助、後輔助，可能高估輔助條件；兩半詞表的難度也未獨立驗證。"
        "NLFC 效益拆解只在單一呈現位準測試，且未做音素層級混淆分析。"
    ),
    620: (
        "錯題分析只輸出探索性文字建議，不會自動套用 EQ；「依建議調整後再測」也尚未驗證。"
        "SII、STOI 與 HASPI 目前只有理論介紹與附錄規劃，第五章沒有實際結果。"
    ),
    622: (
        "未來首先應招募真實感音神經性聽損者並擴大樣本，進行推論統計，確認補償效益能否"
        "推廣到目標族群。"
    ),
    623: (
        "其次，應完成耳機 ETSPL 與 OSPL90 絕對校正，並把處方擴充為 DSL v5 的多輸入級目標，"
        "取代現行 65 dB SPL 靜態近似。"
    ),
    624: (
        "錯題分析必須先完成「文字建議—手動調整—再次測驗」的前後測驗證，才能評估是否適合"
        "進一步自動化。NLFC 的截止頻率與壓縮比也應依個別聽力狀況調整。"
    ),
    625: (
        "實驗方面，應補做 N2 對照、人工音素分割與即時聽損模擬問卷，以分開頻率塑形、"
        "整體放大及主觀感受的影響。"
    ),
    626: (
        "系統方面，可加入方向性處理或更進階的降噪以改善訊雜比，並實際計算 SII、STOI 與 HASPI，"
        "補充行為測驗以外的量化證據。"
    ),
    672: "附錄",
    673: "附錄 A　實驗用消費性耳機型號清單",
    675: "附錄 B　華語語詞測驗語料",
    679: "附錄 C　其他訊號鏈模組與情境驗證實驗設計",
    681: "C.1　雙輸入源延遲與頻率響應比較實驗設計",
    683: "C.2　聲學安全與客觀可懂度驗證規劃（SII、STOI、HASPI）",
    685: "C.3　聽力檢測演算法模擬驗證實驗設計",
    687: "C.4　DSP 訊號鏈逐模組功能驗證實驗設計",
    689: "C.5　動態範圍壓縮與限幅安全性驗證實驗設計",
    691: "C.6　情境模式參數驗證實驗設計",
    693: "附錄 D　Android 低延遲音訊技術實作細節",
    695: "D.1　傳統音訊堆疊的延遲來源",
    697: "D.2　以 Oboe 建立低延遲串流",
    699: "D.3　獨佔模式與記憶體映射（MMAP）",
    701: "D.4　即時回呼的程式設計原則",
    703: "D.5　無鎖 SPSC 環形緩衝區",
    705: "D.6　媒體音訊路徑的系統層等化",
    707: "D.7　橋接層資料轉換與增益映射",
    717: "附錄 E　消費性耳機 ETSPL 校正與輸出安全上限（OSPL90）",
    719: "E.1　消費性耳機的等效閾值聲壓級校正（ETSPL）",
    723: "E.2　輸出聲壓安全上限（OSPL90）與法規符合性",
    726: "附錄 F　DSL v5 by-Hand 處方查表依據",
}


CAPTION_TITLE_OVERRIDES = {
    413: (
        "兩條路徑的濾波器示意：(a) 媒體路徑的 16 段 preEQ／設定解析度；"
        "(b) Native 路徑的 8 頻帶 LR4 分頻器。兩者不在 Native 路徑串接"
    ),
    467: "主畫面：使用者模式（左）與實驗模式（右）",
    558: "S1 模擬聽損之補償前後等效聽閾平均（15 位受試者）",
}


def p_text(p: etree._Element) -> str:
    return "".join(p.xpath(".//w:t/text()", namespaces=NS))


def p_style(p: etree._Element) -> str:
    values = p.xpath("./w:pPr/w:pStyle/@w:val", namespaces=NS)
    return values[0] if values else ""


def plain_run(text: str, rpr: etree._Element | None = None) -> etree._Element:
    r = etree.Element(QN("r"))
    if rpr is not None:
        r.append(copy.deepcopy(rpr))
    t = etree.SubElement(r, QN("t"))
    if text.startswith(" ") or text.endswith(" "):
        t.set(f"{{{XML}}}space", "preserve")
    t.text = text
    return r


def field_runs(instruction: str, result: str) -> list[etree._Element]:
    begin = etree.Element(QN("r"))
    fc = etree.SubElement(begin, QN("fldChar"))
    fc.set(QN("fldCharType"), "begin")
    fc.set(QN("dirty"), "true")

    instr_run = etree.Element(QN("r"))
    instr = etree.SubElement(instr_run, QN("instrText"))
    instr.set(f"{{{XML}}}space", "preserve")
    instr.text = f" {instruction} "

    separate = etree.Element(QN("r"))
    sf = etree.SubElement(separate, QN("fldChar"))
    sf.set(QN("fldCharType"), "separate")

    value = plain_run(result)

    end = etree.Element(QN("r"))
    ef = etree.SubElement(end, QN("fldChar"))
    ef.set(QN("fldCharType"), "end")
    return [begin, instr_run, separate, value, end]


def replace_paragraph(
    p: etree._Element, replacement: str | list[str], *, style_id: str | None = None
) -> None:
    texts = replacement if isinstance(replacement, list) else [replacement]
    parent = p.getparent()
    insert_at = parent.index(p)
    template_ppr = p.find(QN("pPr"))
    for offset, text in enumerate(texts):
        new_p = etree.Element(QN("p"))
        if template_ppr is not None:
            ppr = copy.deepcopy(template_ppr)
            if style_id is not None:
                style = ppr.find(QN("pStyle"))
                if style is None:
                    style = etree.SubElement(ppr, QN("pStyle"))
                style.set(QN("val"), style_id)
            new_p.append(ppr)
        new_p.append(plain_run(text))
        parent.insert(insert_at + offset, new_p)
    parent.remove(p)


def rebuild_caption(
    p: etree._Element,
    paragraph_index: int,
    bookmark_id: int,
) -> tuple[str, str] | None:
    text = p_text(p)
    match = re.match(r"^(圖|表格)\s*(\d)(\d+)、(.*)$", text)
    if not match:
        return None
    label, chapter, item, title = match.groups()
    display_label = "表" if label == "表格" else "圖"
    sequence = "表格" if label == "表格" else "圖"
    kind = "tbl" if label == "表格" else "fig"
    bookmark = f"{kind}_{chapter}_{item}"
    title = CAPTION_TITLE_OVERRIDES.get(paragraph_index, title)

    ppr = p.find(QN("pPr"))
    if ppr is None:
        ppr = etree.Element(QN("pPr"))
        p.insert(0, ppr)
    style = ppr.find(QN("pStyle"))
    if style is None:
        style = etree.SubElement(ppr, QN("pStyle"))
    style.set(QN("val"), "afd")
    for child in list(p):
        if child is not ppr:
            p.remove(child)

    p.append(plain_run(f"{display_label} "))
    start = etree.SubElement(p, QN("bookmarkStart"))
    start.set(QN("id"), str(bookmark_id))
    start.set(QN("name"), bookmark)
    for run in field_runs(r"STYLEREF 1 \s", chapter):
        p.append(run)
    p.append(plain_run("-"))
    for run in field_runs(fr"SEQ {sequence} \* ARABIC \s 1", item):
        p.append(run)
    end = etree.SubElement(p, QN("bookmarkEnd"))
    end.set(QN("id"), str(bookmark_id))
    p.append(plain_run(f"、{title}"))
    return f"{display_label} {chapter}-{item}", bookmark


REF_RE = re.compile(r"(圖|表)\s*(\d+)[-–](\d+)")


def convert_plain_refs(
    paragraphs: list[etree._Element],
    targets: dict[str, str],
) -> int:
    converted = 0
    for p in paragraphs:
        style = p_style(p)
        if style in {"afd", "afe", "a2", "A0", "C1", "af3"}:
            continue
        for r in list(p.xpath("./w:r", namespaces=NS)):
            if r.xpath(".//w:instrText|.//w:fldChar", namespaces=NS):
                continue
            texts = r.xpath("./w:t", namespaces=NS)
            if len(texts) != 1 or texts[0].text is None:
                continue
            text = texts[0].text
            matches = list(REF_RE.finditer(text))
            if not matches:
                continue
            parent = r.getparent()
            pos = parent.index(r)
            rpr = r.find(QN("rPr"))
            cursor = 0
            new_nodes: list[etree._Element] = []
            for match in matches:
                key = f"{match.group(1)} {match.group(2)}-{match.group(3)}"
                bookmark = targets.get(key)
                if bookmark is None:
                    continue
                if match.start() > cursor:
                    new_nodes.append(plain_run(text[cursor : match.start()], rpr))
                new_nodes.extend(field_runs(fr"REF {bookmark} \h", key))
                cursor = match.end()
                converted += 1
            if cursor == 0:
                continue
            if cursor < len(text):
                new_nodes.append(plain_run(text[cursor:], rpr))
            parent.remove(r)
            for offset, node in enumerate(new_nodes):
                parent.insert(pos + offset, node)
    return converted


def convert_paragraph_refs_flat(
    paragraphs: list[etree._Element],
    targets: dict[str, str],
) -> int:
    """Convert references split across several runs without touching field paragraphs."""
    converted = 0
    for p in paragraphs:
        style = p_style(p)
        if style in {"afd", "afe", "a2", "A0", "C1", "af3"}:
            continue
        if p.xpath(".//w:instrText|.//w:fldChar|.//w:drawing|.//w:pict", namespaces=NS):
            continue
        text = p_text(p)
        matches = [
            match
            for match in REF_RE.finditer(text)
            if f"{match.group(1)} {match.group(2)}-{match.group(3)}" in targets
        ]
        if not matches:
            continue
        ppr = p.find(QN("pPr"))
        for child in list(p):
            if child is not ppr:
                p.remove(child)
        cursor = 0
        for match in matches:
            if match.start() > cursor:
                p.append(plain_run(text[cursor : match.start()]))
            key = f"{match.group(1)} {match.group(2)}-{match.group(3)}"
            p.extend(field_runs(fr"REF {targets[key]} \h", key))
            cursor = match.end()
            converted += 1
        if cursor < len(text):
            p.append(plain_run(text[cursor:]))
    return converted


def remove_numbering_and_set_outline(styles_root: etree._Element) -> None:
    for style_id, level in (("A0", "1"), ("C1", "2")):
        styles = styles_root.xpath(
            f'./w:style[@w:styleId="{style_id}"]', namespaces=NS
        )
        if not styles:
            raise RuntimeError(f"Missing appendix style {style_id}")
        style = styles[0]
        ppr = style.find(QN("pPr"))
        if ppr is None:
            ppr = etree.SubElement(style, QN("pPr"))
        for numpr in ppr.findall(QN("numPr")):
            ppr.remove(numpr)
        outline = ppr.find(QN("outlineLvl"))
        if outline is None:
            outline = etree.SubElement(ppr, QN("outlineLvl"))
        outline.set(QN("val"), level)


def ensure_update_fields(settings_root: etree._Element) -> None:
    update = settings_root.find(QN("updateFields"))
    if update is None:
        update = etree.SubElement(settings_root, QN("updateFields"))
    update.set(QN("val"), "true")


def normalize_revision_red_text(document_root: etree._Element) -> int:
    """Return manually red thesis text to the document's normal black color."""
    changed = 0
    for color in document_root.xpath(
        './/w:rPr/w:color[translate(@w:val, "abcdef", "ABCDEF")="FF0000"]',
        namespaces=NS,
    ):
        color.set(QN("val"), "000000")
        changed += 1
    return changed


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--figure-3-1", type=Path, required=True)
    args = parser.parse_args()

    with zipfile.ZipFile(args.source) as zin:
        files = {name: zin.read(name) for name in zin.namelist()}

    document = etree.fromstring(files["word/document.xml"])
    styles = etree.fromstring(files["word/styles.xml"])
    settings = etree.fromstring(files["word/settings.xml"])
    body = document.find(QN("body"))
    paragraphs = [child for child in body if child.tag == QN("p")]

    for index, replacement in REPLACEMENTS.items():
        p = paragraphs[index]
        if p.xpath(".//w:instrText", namespaces=NS):
            raise RuntimeError(
                f"Refusing to replace P{index:04d}; it contains a field"
            )
        style_override = "A0" if index == 726 else None
        replace_paragraph(p, replacement, style_id=style_override)

    # Work from the revised document for captions and cross-references.
    revised_paragraphs = [child for child in body if child.tag == QN("p")]
    max_bookmark = max(
        [int(x) for x in document.xpath(".//w:bookmarkStart/@w:id", namespaces=NS)]
        or [0]
    )
    targets: dict[str, str] = {}
    caption_count = 0
    # Caption indexes refer to the original stable paragraph list.
    for original_index, p in enumerate(paragraphs):
        if p.getparent() is None or (
            p_style(p) != "afd" and original_index != 467
        ):
            continue
        max_bookmark += 1
        rebuilt = rebuild_caption(p, original_index, max_bookmark)
        if rebuilt:
            visible, bookmark = rebuilt
            targets[visible] = bookmark
            caption_count += 1

    revised_paragraphs = [child for child in body if child.tag == QN("p")]
    ref_count = convert_plain_refs(revised_paragraphs, targets)
    ref_count += convert_paragraph_refs_flat(revised_paragraphs, targets)
    remove_numbering_and_set_outline(styles)
    ensure_update_fields(settings)
    red_count = normalize_revision_red_text(document)

    files["word/document.xml"] = etree.tostring(
        document, xml_declaration=True, encoding="UTF-8", standalone="yes"
    )
    files["word/styles.xml"] = etree.tostring(
        styles, xml_declaration=True, encoding="UTF-8", standalone="yes"
    )
    files["word/settings.xml"] = etree.tostring(
        settings, xml_declaration=True, encoding="UTF-8", standalone="yes"
    )
    files["word/media/image1.png"] = args.figure_3_1.read_bytes()

    args.output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(args.output, "w", zipfile.ZIP_DEFLATED) as zout:
        for name, content in files.items():
            zout.writestr(name, content)

    print(
        f"Wrote {args.output}\n"
        f"Rebuilt captions: {caption_count}\n"
        f"Converted body references: {ref_count}\n"
        f"Cross-reference targets: {len(targets)}\n"
        f"Normalized red text runs: {red_count}"
    )


if __name__ == "__main__":
    main()
