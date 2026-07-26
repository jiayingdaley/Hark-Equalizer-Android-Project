# -*- coding: utf-8 -*-
"""
以 Praat（parselmouth）對 200 個詞之實際錄音（app/src/main/res/raw/hselist4_r{row}_c{col}.wav）
進行聲學分析，取代先前之音韻規則啟發式，作為 srt_diagnostic_data.json 之量測依據。

方法：
  1. 逐詞計算「16 頻帶能量剖面」：以 Praat 頻譜（To Spectrum）取得功率譜，
     對齊 App 等化器之 16 個三分之一倍頻中心頻率（250–8000 Hz），每頻帶取
     中心頻率 × 2^(±1/6) 範圍內之功率總和（1/3 倍頻寬），轉為 dB 後對整段
     錄音之總能量正規化（每詞剖面總和為 1，使不同錄音之音量差異不影響比較）。
  2. 詞對之 risky_frequencies：兩詞 16 頻帶剖面之絕對差值最大的前 5 個頻帶
     ——即兩詞在錄音上差異最大、最具鑑別力之頻帶。
  3. 詞對之 confusion_score：兩詞 16 頻帶剖面之餘弦相似度（cosine similarity），
     ∈[0,1]，量化整體聲學相似程度（不含前述之特徵距離啟發式，這裡取代為真實
     量測值）。
  4. 摩擦音起始頻譜重心（供 3.3.3 節與 Lee (2011) 文獻數據比對驗證）：對聲母
     為 ㄙ／ㄕ／ㄒ 之音節，以 Praat 之聲門週期性（voicing）偵測找出濁音起始
     時間點（第一個可測得基頻之時間），將起始靜音後至濁音起始前之區段視為
     擦音噪音段，計算其功率譜之頻譜重心（第一頻譜矩）。此為近似量測（未做
     人工音素分割 TextGrid），僅用於驗證本研究語料之聲學階層順序是否與文獻
     一致，非用於逐詞之風險頻帶計算。

執行：python3 analyze_word_spectra.py
輸出：
  - srt_diagnostic_data.json 之更新版本（同 schema：question_id/correct_word/
    heard_word/risky_frequencies/confusion_score/contrasts；risky_frequencies
    與 confusion_score 改為量測值，contrasts 仍沿用音韻規則推導文字說明）
  - fricative_centroid_measurements.csv（逐詞量測結果，供人工複核）
  - spectrogram_ㄙㄕㄒ.png（頻譜圖，供論文 3.3.3 節插圖）
"""
import os, sys, csv, json, itertools
import numpy as np
import parselmouth
from parselmouth.praat import call

HERE = os.path.dirname(os.path.abspath(__file__))
APP_ASSETS = os.path.normpath(os.path.join(HERE, '..', '..', 'app', 'src', 'main', 'assets'))
RAW_DIR = os.path.normpath(os.path.join(HERE, '..', '..', 'app', 'src', 'main', 'res', 'raw'))
WORDLIST = os.path.join(APP_ASSETS, 'wordlist.csv')
ZHUYIN = os.path.join(HERE, 'wordlist_Bopomofo.csv')
OUT_JSON = os.path.join(APP_ASSETS, 'srt_diagnostic_data.json')
OUT_CSV = os.path.join(HERE, 'fricative_centroid_measurements.csv')

EQ16 = [250, 315, 400, 500, 630, 800, 1000, 1250, 1600, 2000, 2500, 3150, 4000, 5000, 6300, 8000]

# ── 讀 wordlist（決定每詞所在的 row/col，藉以找到對應 wav 檔） ──────────────
rows = []
with open(WORDLIST, encoding='utf-8-sig') as f:
    rd = csv.reader(f)
    next(rd)
    for r in rd:
        r = [x.strip() for x in r if x.strip()]
        if len(r) == 4:
            rows.append(r)

word_wav = {}  # word -> wav path（若重複詞取第一次出現）
# 檔名列號＝CSV 資料列 0-indexed + 2（含表頭之檔案行號；見 WordProvider.kt
# generateQuestionFromRow：outputAudioRowNumber = originalCsvRowIndex_0_based + 2）
for ri0, r in enumerate(rows):
    for ci, w in enumerate(r, start=1):
        wav = os.path.join(RAW_DIR, f'hselist4_r{ri0 + 2}_c{ci}.wav')
        if os.path.exists(wav) and w not in word_wav:
            word_wav[w] = wav

missing = [w for r in rows for w in r if w not in word_wav]
if missing:
    print('❌ 缺少錄音檔:', missing); sys.exit(1)
print(f'✓ {len(word_wav)} 個詞之錄音檔皆存在')

# ── 注音（用於 contrasts 文字說明與擦音起首詞篩選；沿用 generate_srt_diagnostic.py 之解析） ──
INITIALS = ['ㄅ','ㄆ','ㄇ','ㄈ','ㄉ','ㄊ','ㄋ','ㄌ','ㄍ','ㄎ','ㄏ',
            'ㄐ','ㄑ','ㄒ','ㄓ','ㄔ','ㄕ','ㄖ','ㄗ','ㄘ','ㄙ']
TONE_MARKS = {'ˊ': 2, 'ˇ': 3, 'ˋ': 4, '˙': 5}
def parse_syllable(s):
    s = s.strip(); tone = 1
    if s and s[-1] in TONE_MARKS: tone = TONE_MARKS[s[-1]]; s = s[:-1]
    if s and s[0] in TONE_MARKS: tone = TONE_MARKS[s[0]]; s = s[1:]
    init = s[0] if s and s[0] in INITIALS else ''
    final = s[len(init):]
    if not final and init in 'ㄓㄔㄕㄖㄗㄘㄙ': final = 'ㄭ'
    assert final, f'韻母為空: {s!r}'
    return init, final, tone

zhuyin = {}
zrows = []
with open(ZHUYIN, encoding='utf-8-sig') as f:
    for row in csv.DictReader(f):
        zr = []
        for i in (1, 2, 3, 4):
            w = row[f'word_{i}'].strip(); zy = row[f'zhuyin_{i}'].strip()
            zr.append(w); zhuyin[w] = [parse_syllable(s) for s in zy.split()]
        zrows.append(zr)
for r, zr in zip(rows, zrows):
    assert r == zr, f'wordlist 與注音表不一致: {r} vs {zr}'
print('✓ 注音表與 wordlist 逐題逐欄一致')

# ── (1) 16 頻帶能量剖面：逐詞計算 ──────────────────────────────────────────
def band_profile(wav_path, bands=EQ16):
    snd = parselmouth.Sound(wav_path)
    spec = snd.to_spectrum()
    freqs = np.array(spec.xs())
    # 功率譜密度（Pa^2/Hz 近似）：實部^2+虛部^2
    values = spec.values
    power = values[0, :] ** 2 + values[1, :] ** 2
    energies = []
    for f0 in bands:
        lo, hi = f0 / (2 ** (1/6)), f0 * (2 ** (1/6))
        mask = (freqs >= lo) & (freqs < hi)
        energies.append(float(power[mask].sum()) if mask.any() else 0.0)
    energies = np.array(energies)
    total = energies.sum()
    return energies / total if total > 0 else energies

print('計算全部詞之 16 頻帶能量剖面 ...')
profiles = {w: band_profile(p) for w, p in word_wav.items()}
print(f'✓ 完成 {len(profiles)} 詞')

def cosine_sim(a, b):
    na, nb = np.linalg.norm(a), np.linalg.norm(b)
    if na == 0 or nb == 0: return 0.0
    return float(np.clip(np.dot(a, b) / (na * nb), 0.0, 1.0))

# ── (2) 差異音素對比文字（沿用音韻規則推導，僅作顯示用途，非頻帶計算依據） ──
ASPIRATION_PAIRS = {frozenset(p) for p in
                    [('ㄅ','ㄆ'), ('ㄉ','ㄊ'), ('ㄍ','ㄎ'), ('ㄓ','ㄔ'), ('ㄗ','ㄘ'), ('ㄐ','ㄑ')]}
def final_parts(final):
    med = final[0] if final and final[0] in 'ㄧㄨㄩ' and len(final) > 1 else ''
    return med, final[len(med):]
def strip_coda(final):
    for c in ('ㄣ','ㄢ','ㄥ','ㄤ'):
        if final.endswith(c): return final[:-1], c
    return final, ''
def syllable_contrasts(idx, sa, sb):
    (ia, fa, ta), (ib, fb, tb) = sa, sb
    out = []
    if ia != ib:
        if frozenset((ia, ib)) in ASPIRATION_PAIRS:
            out.append(f'第{idx}字聲母 {ia}↔{ib}（送氣對比）')
        else:
            out.append(f'第{idx}字聲母 {ia or "∅"}↔{ib or "∅"}')
    if fa != fb:
        abody, acoda = strip_coda(fa); bbody, bcoda = strip_coda(fb)
        if abody == bbody and acoda != bcoda:
            out.append(f'第{idx}字韻母 {fa}↔{fb}（鼻韻尾）')
        else:
            amed, arest = final_parts(fa); bmed, brest = final_parts(fb)
            if arest == brest and amed != bmed:
                out.append(f'第{idx}字韻母 {fa}↔{fb}（介音）')
            else:
                out.append(f'第{idx}字韻母 {fa}↔{fb}')
    if ta != tb:
        out.append(f'第{idx}字聲調 {ta}↔{tb}（基頻線索，不作 EQ 歸因）')
    return out

# ── (3) 生成 600 筆詞對（量測值） ──────────────────────────────────────────
out = {}
for qid, r in enumerate(rows, start=1):
    for correct, heard in itertools.permutations(r, 2):
        pc, ph = profiles[correct], profiles[heard]
        diff = np.abs(pc - ph)
        top5_idx = np.argsort(-diff)[:5]
        risky = sorted(EQ16[i] for i in top5_idx)
        conf = cosine_sim(pc, ph)
        contrasts = []
        for i, (sa, sb) in enumerate(zip(zhuyin[correct], zhuyin[heard]), start=1):
            contrasts += syllable_contrasts(i, sa, sb)
        out[f'{correct},{heard}'] = {
            'question_id': qid, 'correct_word': correct, 'heard_word': heard,
            'risky_frequencies': [float(x) for x in risky],
            'confusion_score': round(conf, 4),
            'contrasts': contrasts,
        }
with open(OUT_JSON, 'w', encoding='utf-8') as f:
    json.dump(out, f, ensure_ascii=False, indent=1)
print(f'✓ 已輸出 {len(out)} 筆（量測值）→ {OUT_JSON}')
for k in ['堡壘,跑壘', '動身,眾生', '公司,燈絲']:
    v = out[k]
    print(f'  {k}: conf={v["confusion_score"]} bands={v["risky_frequencies"]}')

# ── (4) 摩擦音起始頻譜重心（ㄙㄕㄒ 驗證，比對 Lee 2011） ──────────────────
def onset_centroid(wav_path):
    """回傳 (聲母噪音段頻譜重心 Hz, 濁音起始時間 s)；若偵測失敗回傳 (None, None)。"""
    snd = parselmouth.Sound(wav_path)
    pitch = snd.to_pitch(time_step=0.005, pitch_floor=75, pitch_ceiling=500)
    times = pitch.ts()
    values = [pitch.get_value_at_time(t) for t in times]
    voiced_idx = next((i for i, v in enumerate(values) if not np.isnan(v) and v > 0), None)
    if voiced_idx is None or times[voiced_idx] < 0.03:
        return None, None
    onset_t = times[voiced_idx]
    start_t = max(0.01, onset_t - 0.16)   # 起首噪音段：濁音起始前 ~160ms 內（略過開頭靜音緩衝）
    seg = call(snd, "Extract part", start_t, onset_t, "rectangular", 1.0, False)
    spec = seg.to_spectrum()
    freqs = np.array(spec.xs())
    power = spec.values[0, :] ** 2 + spec.values[1, :] ** 2
    centroid = float((freqs * power).sum() / power.sum()) if power.sum() > 0 else None
    return centroid, onset_t

sibilant_words = []
for r in rows:
    for w in r:
        init0 = zhuyin[w][0][0]
        if init0 in ('ㄙ', 'ㄕ', 'ㄒ'):
            sibilant_words.append((w, init0))

print(f'\n量測 {len(sibilant_words)} 個 ㄙ／ㄕ／ㄒ 起首詞之擦音頻譜重心 ...')
rows_csv = []
for w, init0 in sibilant_words:
    c, onset_t = onset_centroid(word_wav[w])
    rows_csv.append((w, init0, c, onset_t))

with open(OUT_CSV, 'w', newline='', encoding='utf-8') as f:
    wr = csv.writer(f)
    wr.writerow(['word', 'initial', 'centroid_hz', 'voicing_onset_s'])
    for w, init0, c, t in rows_csv:
        wr.writerow([w, init0, round(c, 1) if c else '', round(t, 4) if t else ''])
print(f'✓ 逐詞量測結果 → {OUT_CSV}')

by_init = {}
for w, init0, c, t in rows_csv:
    if c is not None:
        by_init.setdefault(init0, []).append(c)
print('\n各聲母之擦音噪音段頻譜重心（平均 ± SD，Hz）：')
for k in ('ㄒ', 'ㄙ', 'ㄕ'):
    v = by_init.get(k, [])
    if v:
        print(f'  {k}: n={len(v)}  mean={np.mean(v):.0f}  sd={np.std(v):.0f}  range={min(v):.0f}-{max(v):.0f}')
    else:
        print(f'  {k}: 無有效量測')
