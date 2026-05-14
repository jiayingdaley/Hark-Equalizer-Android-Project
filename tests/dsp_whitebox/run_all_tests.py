#!/usr/bin/env python3
"""
run_all_tests.py
================
Master test runner for all Hark DSP white-box tests.
Runs all test modules in order and generates a consolidated JSON report.

Usage:
    cd tests/dsp_whitebox
    python run_all_tests.py
"""

import sys
import os
import json
import time
import importlib

# Ensure the test directory is in the path
sys.path.insert(0, os.path.dirname(__file__))

REPORT_DIR = os.path.join(os.path.dirname(__file__), "report_figures")
os.makedirs(REPORT_DIR, exist_ok=True)

MODULES = [
    ("BiquadFilter",        "test_biquad_filter"),
    ("LR4 Crossover",       "test_lr4_crossover"),
    ("DynamicsProcessor",   "test_dynamics_processor"),
    ("NoiseSuppressor",     "test_noise_suppressor"),
    ("8-Band Filterbank",   "test_filterbank_8band"),
    ("Full Signal Chain",   "test_signal_chain"),
]

all_results = {}
total_pass = 0
total_fail = 0

print("\n" + "="*70)
print("  Hark DSP White-Box Test Suite  —  Master Runner")
print(f"  Timestamp: {time.strftime('%Y-%m-%d %H:%M:%S')}")
print("="*70 + "\n")

for module_label, module_name in MODULES:
    print(f"\n{'─'*70}")
    print(f"  Module: {module_label}")
    print(f"{'─'*70}")
    
    try:
        mod = importlib.import_module(module_name)
        
        # Reset results dict and run the module's __main__ block
        # by calling all test functions manually
        module_results = {}
        
        # Collect all test functions
        test_fns = [(name, getattr(mod, name)) 
                    for name in dir(mod) 
                    if name.startswith("test_") and callable(getattr(mod, name))]
        
        for name, fn in test_fns:
            try:
                fn()
                module_results[name] = "PASS"
                total_pass += 1
                print(f"  ✅ PASS  {name}")
            except AssertionError as e:
                module_results[name] = f"FAIL: {e}"
                total_fail += 1
                print(f"  ❌ FAIL  {name}: {e}")
            except Exception as e:
                module_results[name] = f"ERROR: {e}"
                total_fail += 1
                print(f"  💥 ERROR {name}: {e}")
        
        all_results[module_label] = module_results
        
    except Exception as e:
        print(f"  💥 Could not import {module_name}: {e}")
        all_results[module_label] = {"import_error": str(e)}
        total_fail += 1

# ─── Summary ──────────────────────────────────────────────────────────────────
print("\n" + "="*70)
print("  SUMMARY")
print("="*70)
print(f"  ✅ PASSED: {total_pass}")
print(f"  ❌ FAILED: {total_fail}")
print(f"  Total:    {total_pass + total_fail}")
print("="*70 + "\n")

# Save consolidated report
report = {
    "timestamp": time.strftime('%Y-%m-%dT%H:%M:%S'),
    "total_pass": total_pass,
    "total_fail": total_fail,
    "modules": all_results
}
report_path = os.path.join(REPORT_DIR, "full_report.json")
with open(report_path, "w", encoding="utf-8") as f:
    json.dump(report, f, indent=2, ensure_ascii=False)
print(f"  Report saved: {report_path}")

sys.exit(0 if total_fail == 0 else 1)
