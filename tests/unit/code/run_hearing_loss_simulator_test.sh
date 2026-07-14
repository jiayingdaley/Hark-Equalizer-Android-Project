#!/usr/bin/env bash
# 聽損模擬器單元測試（在主機端直接編譯原生 DSP 原始碼，不需 Android 裝置）
#
#   ./tests/unit/code/run_hearing_loss_simulator_test.sh
#
# 驗證項目：
#   1. 純音閉式增益 toneGainDb —— SL=HL 時落回聽閾、SL=UCL 時增益為 0、擴展比 = UCL/(UCL−HL)
#   2. 8 頻帶擴展器 —— 斜降型聽力圖之高頻衰減 >> 低頻；小聲衰減多、大聲衰減少（響度重振）
#   3. Baer–Moore 頻譜模糊 —— 寬頻訊號響度不變；能量確實擴散到鄰近頻率
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
DSP="$ROOT/app/src/main/cpp/dsp"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

clang++ -std=c++14 -O2 -I"$DSP" \
    "$ROOT/tests/unit/code/test_hearing_loss_simulator.cpp" \
    "$DSP/HearingLossSimulator.cpp" \
    "$DSP/FrequencySmearing.cpp" \
    "$DSP/CrossoverBank8.cpp" \
    "$DSP/LinkwitzRileyCrossover.cpp" \
    "$DSP/BiquadFilter.cpp" \
    -o "$OUT/test_hlsim"

"$OUT/test_hlsim"
