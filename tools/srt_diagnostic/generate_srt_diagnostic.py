# -*- coding: utf-8 -*-
"""
srt_diagnostic_data.json 生成腳本（取代舊版來源不明的 600 筆資料）
================================================================================
背景：
  舊版 assets/srt_diagnostic_data.json 為一次性 commit 的成品檔，無生成腳本、
  confusion_score 與 risky_frequencies 查無文獻或推導依據（風險頻率在 16 個頻帶
  上近乎均勻分布，不符語音學結構）。本腳本自 wordlist_zhuyin.csv（人工標注、
  可校對之注音表）重新推導全部欄位，並新增 contrasts 欄（實際差異音素），
  供 App 顯示「真正聽混的注音」而非寫死的範例。

自動化校對（本腳本執行時強制檢查，任何一項失敗即中止）：
  1. wordlist.csv 的 200 個詞，每一個都必須在 wordlist_zhuyin.csv 有注音。
  2. 每個詞的注音音節數必須等於字數（雙字詞＝2 音節）。
  3. 每個音節必須可拆成（聲母∈21聲母∪空、韻母非空、聲調∈1..5）。

頻帶對應依據（華語聲學語音學之一般常識，工程近似；非逐對量測值）：
  - 齒齦擦/塞擦音 ㄗㄘㄙ：噪音頻譜重心最高（約 5–9 kHz）。
  - 捲舌音 ㄓㄔㄕㄖ：頻譜重心較低（約 2–4 kHz）。
  - 齦顎音 ㄐㄑㄒ：介於其間（約 3–6 kHz）。
  - 塞音爆發（burst）頻譜隨構音部位：雙唇 ㄅㄆ 偏低頻、齒齦 ㄉㄊ 偏高頻、
    軟顎 ㄍㄎ 居中。
  - 送氣對比（ㄅ/ㄆ、ㄉ/ㄊ、ㄍ/ㄎ、ㄓ/ㄔ、ㄗ/ㄘ、ㄐ/ㄑ）：主線索為 VOT 與
    送氣噪音（寬頻、約 1–4 kHz）。
  - 鼻音 ㄇㄋ 與邊音 ㄌ：低頻共鳴為主。
  - ㄈ 為弱寬頻擦音；ㄏ 為喉/軟顎擦音（中頻）。
  - 韻母（母音）差異：F1（約 250–800 Hz）與 F2（約 800–2500 Hz）。
  - 鼻韻尾 -ㄣ/-ㄥ（-n/-ŋ）差異：鼻腔共鳴（低頻）＋ F2/F3 過渡（約 1.6–2.5 kHz）。
  - 聲調差異：基頻（F0）輪廓線索，非頻譜共振線索——「不」納入 EQ 頻帶歸因，
    僅在 contrasts 中標示，避免把音高問題誤導為某頻段增益不足。

confusion_score（相似度加權）之替代：
  查證結果：舊值無文獻出處。本腳本改用「音韻特徵距離」啟發式（透明、可重現）：
    每音節 sim = 0.4*聲母相似 + 0.4*韻母相似 + 0.2*聲調相似，雙音節取平均。
    聲母：相同=1.0；僅送氣之別=0.85；同部位或同方式=0.6；其餘=0.25；
          有無聲母之別=0.3。
    韻母：相同=1.0；僅鼻韻尾 n/ŋ 之別=0.85；僅介音之別=0.7；其餘=0.3。
    聲調：相同=1.0；不同=0.5。
  ※ 此為特徵式啟發（非文獻量測值），僅用於「聲學上本就極相似之詞對，其錯聽
    的診斷資訊較少」之降權，程式端沿用 (1 − confusion) 加權，語意不變。

執行：python3 generate_srt_diagnostic.py
輸出：../../app/src/main/assets/srt_diagnostic_data.json（覆寫）
"""
import csv, json, os, sys, itertools

HERE = os.path.dirname(os.path.abspath(__file__))
APP_ASSETS = os.path.normpath(os.path.join(HERE, '..', '..', 'app', 'src', 'main', 'assets'))
WORDLIST = os.path.join(APP_ASSETS, 'wordlist.csv')
# 人工校對後之注音表（寬表：每列一題，word_1..4 / zhuyin_1..4，與 wordlist.csv 列序對齊）
ZHUYIN = os.path.join(HERE, 'wordlist_Bopomofo.csv')
OUT = os.path.join(APP_ASSETS, 'srt_diagnostic_data.json')

# ── 注音拆解 ──────────────────────────────────────────────────────────────
INITIALS = ['ㄅ','ㄆ','ㄇ','ㄈ','ㄉ','ㄊ','ㄋ','ㄌ','ㄍ','ㄎ','ㄏ',
            'ㄐ','ㄑ','ㄒ','ㄓ','ㄔ','ㄕ','ㄖ','ㄗ','ㄘ','ㄙ']
TONE_MARKS = {'ˊ': 2, 'ˇ': 3, 'ˋ': 4, '˙': 5}

def parse_syllable(s):
    """'ㄉㄨㄥˋ' -> (聲母'ㄉ', 韻母'ㄨㄥ', 聲調4)；聲母可為 ''（零聲母）。"""
    s = s.strip()
    tone = 1
    if s and s[-1] in TONE_MARKS:
        tone = TONE_MARKS[s[-1]]; s = s[:-1]
    if s and s[0] in TONE_MARKS:            # 輕聲寫在前的慣例
        tone = TONE_MARKS[s[0]]; s = s[1:]
    init = s[0] if s and s[0] in INITIALS else ''
    final = s[len(init):]
    if not final and init in 'ㄓㄔㄕㄖㄗㄘㄙ':
        final = 'ㄭ'          # 空韻（舌尖元音，如「師、失、資」），注音慣例不寫
    assert final, f'韻母為空: {s!r}'
    return init, final, tone

# ── 頻帶對應表（單位 Hz；限用 App EQ 之 16 個中心頻率） ─────────────────────
INITIAL_BANDS = {
    'ㄅ': [500, 800, 1250],   'ㄆ': [500, 800, 1250],      # 雙唇爆發偏低頻
    'ㄉ': [3150, 4000, 5000], 'ㄊ': [3150, 4000, 5000],    # 齒齦爆發偏高頻
    'ㄍ': [1600, 2000, 2500], 'ㄎ': [1600, 2000, 2500],    # 軟顎爆發中頻
    'ㄇ': [250, 315, 400],    'ㄋ': [250, 315, 400],       # 鼻音低頻共鳴
    'ㄌ': [315, 500, 800],                                  # 邊音低中頻
    'ㄈ': [1600, 2500, 4000, 6300],                         # 弱寬頻擦音
    'ㄏ': [1000, 1600, 2500],                               # 喉/軟顎擦音
    'ㄐ': [3150, 4000, 5000, 6300], 'ㄑ': [3150, 4000, 5000, 6300],
    'ㄒ': [3150, 4000, 5000, 6300],                         # 齦顎
    'ㄓ': [2000, 2500, 3150, 4000], 'ㄔ': [2000, 2500, 3150, 4000],
    'ㄕ': [2000, 2500, 3150, 4000], 'ㄖ': [2000, 2500, 3150, 4000],  # 捲舌
    'ㄗ': [5000, 6300, 8000], 'ㄘ': [5000, 6300, 8000],
    'ㄙ': [5000, 6300, 8000],                               # 齒齦擦（重心最高）
    '':  [],                                                # 零聲母
}
ASPIRATION_PAIRS = {frozenset(p) for p in
                    [('ㄅ','ㄆ'), ('ㄉ','ㄊ'), ('ㄍ','ㄎ'), ('ㄓ','ㄔ'), ('ㄗ','ㄘ'), ('ㄐ','ㄑ')]}
ASPIRATION_BANDS = [1000, 1600, 2500, 4000]     # 送氣噪音（寬頻中高頻）＋VOT
VOWEL_BANDS      = [400, 500, 630, 800, 1250, 2000]   # F1+F2 核心
MEDIAL_BANDS     = [800, 1250, 2000, 2500]             # 介音差異＝F2 大位移
NASAL_CODA_BANDS = [315, 400, 1600, 2000, 2500]        # -n/-ŋ：鼻共鳴＋F2/F3 過渡

PLACE = {  # 構音部位（聲母相似度用）
    'ㄅ':'唇','ㄆ':'唇','ㄇ':'唇','ㄈ':'唇齒',
    'ㄉ':'齒齦','ㄊ':'齒齦','ㄋ':'齒齦','ㄌ':'齒齦',
    'ㄍ':'軟顎','ㄎ':'軟顎','ㄏ':'軟顎',
    'ㄐ':'齦顎','ㄑ':'齦顎','ㄒ':'齦顎',
    'ㄓ':'捲舌','ㄔ':'捲舌','ㄕ':'捲舌','ㄖ':'捲舌',
    'ㄗ':'齒齦擦','ㄘ':'齒齦擦','ㄙ':'齒齦擦',
}
MANNER = {  # 構音方式
    'ㄅ':'塞','ㄆ':'塞','ㄉ':'塞','ㄊ':'塞','ㄍ':'塞','ㄎ':'塞',
    'ㄇ':'鼻','ㄋ':'鼻','ㄌ':'邊','ㄈ':'擦','ㄏ':'擦','ㄒ':'擦','ㄕ':'擦','ㄙ':'擦',
    'ㄐ':'塞擦','ㄑ':'塞擦','ㄓ':'塞擦','ㄔ':'塞擦','ㄗ':'塞擦','ㄘ':'塞擦','ㄖ':'擦',
}

def final_parts(final):
    """韻母拆（介音, 主體）。介音 ∈ ㄧㄨㄩ（可為空）。"""
    med = final[0] if final and final[0] in 'ㄧㄨㄩ' and len(final) > 1 else ''
    return med, final[len(med):]

def nasal_coda(final):
    return 'n' if final.endswith('ㄣ') or final.endswith('ㄢ') else \
           ('ng' if final.endswith('ㄥ') or final.endswith('ㄤ') else '')

def strip_coda(final):
    for c in ('ㄣ','ㄢ','ㄥ','ㄤ'):
        if final.endswith(c):
            return final[:-1], c
    return final, ''

# ── 相似度（特徵距離啟發式；非文獻量測值） ─────────────────────────────────
def initial_sim(a, b):
    if a == b: return 1.0
    if frozenset((a, b)) in ASPIRATION_PAIRS: return 0.85
    if (a == '') != (b == ''): return 0.3
    if a and b and (PLACE.get(a) == PLACE.get(b) or MANNER.get(a) == MANNER.get(b)):
        return 0.6
    return 0.25

def final_sim(a, b):
    if a == b: return 1.0
    abody, acoda = strip_coda(a); bbody, bcoda = strip_coda(b)
    if abody == bbody and {acoda, bcoda} <= {'ㄣ','ㄥ'} | {'ㄢ','ㄤ'} and acoda != bcoda:
        return 0.85                     # 僅鼻韻尾之別（如 ㄣ↔ㄥ）
    amed, arest = final_parts(a); bmed, brest = final_parts(b)
    if arest == brest and amed != bmed:
        return 0.7                      # 僅介音之別
    return 0.3

def syllable_contrasts(idx, sa, sb):
    """回傳 (contrast 描述 list, 風險頻帶 set)。idx 為第幾字（1/2）。"""
    (ia, fa, ta), (ib, fb, tb) = sa, sb
    contrasts, bands = [], set()
    if ia != ib:
        if frozenset((ia, ib)) in ASPIRATION_PAIRS:
            contrasts.append(f'第{idx}字聲母 {ia}↔{ib}（送氣對比）')
            bands.update(ASPIRATION_BANDS)
        else:
            da = ia if ia else '∅'; db = ib if ib else '∅'
            contrasts.append(f'第{idx}字聲母 {da}↔{db}')
            bands.update(INITIAL_BANDS.get(ia, [])); bands.update(INITIAL_BANDS.get(ib, []))
    if fa != fb:
        abody, acoda = strip_coda(fa); bbody, bcoda = strip_coda(fb)
        if abody == bbody and acoda != bcoda:      # 含「有無鼻韻尾」之別（如 ㄧ↔ㄧㄥ）
            contrasts.append(f'第{idx}字韻母 {fa}↔{fb}（鼻韻尾）')
            bands.update(NASAL_CODA_BANDS)
        else:
            amed, arest = final_parts(fa); bmed, brest = final_parts(fb)
            if arest == brest and amed != bmed:
                contrasts.append(f'第{idx}字韻母 {fa}↔{fb}（介音）')
                bands.update(MEDIAL_BANDS)
            else:
                contrasts.append(f'第{idx}字韻母 {fa}↔{fb}')
                bands.update(VOWEL_BANDS)
    if ta != tb:
        # 聲調＝基頻線索，不納入頻帶歸因（見檔頭說明）
        contrasts.append(f'第{idx}字聲調 {ta}↔{tb}（基頻線索，不作 EQ 歸因）')
    return contrasts, bands

def word_similarity(pa, pb):
    sims = []
    for (ia, fa, ta), (ib, fb, tb) in zip(pa, pb):
        s = 0.4 * initial_sim(ia, ib) + 0.4 * final_sim(fa, fb) + 0.2 * (1.0 if ta == tb else 0.5)
        sims.append(s)
    return sum(sims) / len(sims)

# ── 讀檔＋自動校對 ─────────────────────────────────────────────────────────
rows = []
with open(WORDLIST, encoding='utf-8-sig') as f:
    rd = csv.reader(f)
    header = next(rd)
    for r in rd:
        r = [x.strip() for x in r if x.strip()]
        if len(r) == 4:
            rows.append(r)

# 寬表：每列一題（word_1..4 / zhuyin_1..4），列序須與 wordlist.csv 完全對齊
zhuyin = {}
zrows = []
with open(ZHUYIN, encoding='utf-8-sig') as f:
    for row in csv.DictReader(f):
        zr = []
        for i in (1, 2, 3, 4):
            w = row[f'word_{i}'].strip()
            zy = row[f'zhuyin_{i}'].strip()
            zr.append(w)
            zhuyin[w] = [parse_syllable(s) for s in zy.split()]
        zrows.append(zr)

errors = []
if len(zrows) != len(rows):
    errors.append(f'題數不符: wordlist {len(rows)} 列 vs 注音表 {len(zrows)} 列')
for qi, (r, zr) in enumerate(zip(rows, zrows), start=1):
    for ci, (w, zw) in enumerate(zip(r, zr), start=1):
        if w != zw:
            errors.append(f'第{qi}題第{ci}欄 詞不一致: wordlist「{w}」 vs 注音表「{zw}」')
for r in rows:
    for w in r:
        if w not in zhuyin:
            errors.append(f'缺注音: {w}')
        elif len(zhuyin[w]) != len(w):
            errors.append(f'音節數({len(zhuyin[w])})≠字數({len(w)}): {w}')
if errors:
    print('❌ 自動校對失敗:'); [print('  ', e) for e in errors]; sys.exit(1)
print(f'✓ 自動校對通過：{len(rows)} 題 × 4 詞 = {sum(len(r) for r in rows)} 詞，與 wordlist.csv 逐題逐欄一致')

# ── 生成 600 筆有序詞對 ────────────────────────────────────────────────────
out = {}
for qid, r in enumerate(rows, start=1):
    for correct, heard in itertools.permutations(r, 2):
        contrasts, bands = [], set()
        for i, (sa, sb) in enumerate(zip(zhuyin[correct], zhuyin[heard]), start=1):
            c, b = syllable_contrasts(i, sa, sb)
            contrasts += c; bands |= b
        sim = word_similarity(zhuyin[correct], zhuyin[heard])
        out[f'{correct},{heard}'] = {
            'question_id': qid,
            'correct_word': correct,
            'heard_word': heard,
            'risky_frequencies': sorted(bands),
            'confusion_score': round(sim, 4),
            'contrasts': contrasts,
        }

with open(OUT, 'w', encoding='utf-8') as f:
    json.dump(out, f, ensure_ascii=False, indent=1)
print(f'✓ 已輸出 {len(out)} 筆 → {OUT}')

# 抽樣印出供人工檢視
for k in ['堡壘,跑壘', '動身,眾生', '隊員,會員', '公司,燈絲', '希望,聽話']:
    v = out[k]
    print(f'  {k}: conf={v["confusion_score"]} bands={v["risky_frequencies"]}')
    for c in v['contrasts']:
        print(f'      {c}')
