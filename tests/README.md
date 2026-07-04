# Hark 離線測試（Python）

不需 Android 裝置即可驗證 DSP 演算法行為。測試報告與圖表對應
[docs/whitebox_test/DSP_WHITEBOX_TEST_REPORT.md](../docs/whitebox_test/DSP_WHITEBOX_TEST_REPORT.md)。

## 環境

```bash
pip install numpy scipy matplotlib soundfile
```

（本專案開發時使用 Anaconda「Datamining」環境。）

## 執行

```bash
python run_all_tests.py        # 全部測試
```

## 目錄

| 目錄 | 內容 |
|------|------|
| `whitebox/code` → `whitebox/results` | DSP 白箱測試：LR4 分頻器、WDRC 壓縮特性、Biquad、降噪 SNR、端對端信號鏈 |
| `unit/code` → `unit/results` | 單元測試 |
| `field_performance/`（code / raw / results） | 實地效能量測腳本與資料 |
