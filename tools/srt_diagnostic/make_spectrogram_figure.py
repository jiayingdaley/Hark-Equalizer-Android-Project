# -*- coding: utf-8 -*-
"""
產生論文 3.3.3 節插圖：ㄙ／ㄕ／ㄒ 三組擦音之實際錄音頻譜圖比較。
取三個量測結果落於各自聲母群集中心附近的代表詞（避免離群樣本），
標示濁音起始時間（灰色虛線）與擦音噪音段（灰色網底），供與 Lee (2011)
之頻譜重心階層比對。

執行：python3 make_spectrogram_figure.py
輸出：spectrogram_sibilants.png
"""
import os
import numpy as np
import parselmouth
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib import font_manager

HERE = os.path.dirname(os.path.abspath(__file__))
RAW_DIR = os.path.normpath(os.path.join(HERE, '..', '..', 'app', 'src', 'main', 'res', 'raw'))

for fp in ['/System/Library/Fonts/PingFang.ttc', '/System/Library/Fonts/STHeiti Medium.ttc']:
    if os.path.exists(fp):
        font_manager.fontManager.addfont(fp)
        plt.rcParams['font.family'] = font_manager.FontProperties(fname=fp).get_name()
        break
plt.rcParams['axes.unicode_minus'] = False

# (word, row, col, initial, 濁音起始時間 s)——取自 fricative_centroid_measurements.csv
# 分別為 ㄙ／ㄕ／ㄒ 群集中心附近之代表詞
EXAMPLES = [
    ('搜刮', 'ㄙ', 5585.6, 0.1623),
    ('行搶', 'ㄒ', 5632.2, 0.1569),
    ('沙發', 'ㄕ', 4085.5, 0.1416),
]
# 對應之 wav（依 wordlist.csv 實際列/欄位置手動查得）
WAV = {
    '搜刮': 'hselist4_r24_c4.wav',
    '行搶': 'hselist4_r7_c3.wav',
    '沙發': 'hselist4_r24_c1.wav',
}

fig, axes = plt.subplots(1, 3, figsize=(13, 4.2), sharey=True)
for ax, (word, init, centroid, onset_t) in zip(axes, EXAMPLES):
    wav_path = os.path.join(RAW_DIR, WAV[word])
    snd = parselmouth.Sound(wav_path)
    spec = snd.to_spectrogram(window_length=0.015, maximum_frequency=8000)
    X, Y = spec.x_grid(), spec.y_grid()
    sg_db = 10 * np.log10(spec.values + 1e-12)
    ax.pcolormesh(X, Y, sg_db, cmap='Greys', vmin=sg_db.max() - 60, vmax=sg_db.max())
    ax.axvline(onset_t, color='#0072B2', linestyle='--', linewidth=1.3)
    ax.axvspan(0.01, onset_t, color='#E69F00', alpha=0.15)
    ax.set_title(f'{word}（{init}）\n擦音段頻譜重心 ≈ {centroid:.0f} Hz')
    ax.set_xlabel('時間 (s)')
    ax.set_ylim(0, 8000)
axes[0].set_ylabel('頻率 (Hz)')
fig.suptitle('ㄙ／ㄒ／ㄕ 起首詞之實際錄音頻譜圖（虛線＝濁音起始時間；橙色網底＝擦音噪音段）', y=1.03)
plt.tight_layout()
out = os.path.join(HERE, 'spectrogram_sibilants.png')
plt.savefig(out, dpi=200, bbox_inches='tight')
print('saved:', out)
