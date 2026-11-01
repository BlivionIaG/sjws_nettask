#!/usr/bin/env python3
"""foreign_cpu.judge — 07:05 addendum NOISY rules."""
from __future__ import annotations
import importlib.util
from pathlib import Path

HERE = Path(__file__).resolve().parent
FC = HERE.parent / "foreign_cpu.py"

def load():
    spec = importlib.util.spec_from_file_location("foreign_cpu", FC)
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m

def samp(ts, per_pid, foreign_proc=False, load1=1.0):
    return {
        "ts": ts,
        "foreign_jiffies_sum": sum(per_pid.values()),
        "per_pid_jiffies": dict(per_pid),
        "foreign_load_proc": foreign_proc,
        "load1": load1,
    }

def main():
    fc = load()
    # 0.6 cores over 5s at USER_HZ=100 → delta jiffies = 0.6*100*5 = 300
    j = fc.judge([
        samp(0, {7: 1000}),
        samp(5, {7: 1000 + 300}),
    ], load1_end=1.0)
    assert j["noisy"] and j["foreign_cpu_avg_cores"] > 0.5, j

    # Quiet foreign CPU
    j = fc.judge([
        samp(0, {7: 1000}),
        samp(5, {7: 1000 + 40}),  # 0.08 cores
    ], load1_end=1.0)
    assert not j["noisy"], j

    # Foreign java/mvn/load process seen
    j = fc.judge([
        samp(0, {}, foreign_proc=True),
        samp(5, {}, foreign_proc=False),
    ], load1_end=1.0)
    assert j["noisy"] and any("foreign_java" in r for r in j["reasons"]), j

    # End load1 >= 6
    j = fc.judge([
        samp(0, {}),
        samp(5, {}, load1=6.2),
    ], load1_end=6.2)
    assert j["noisy"] and any("load1_end" in r for r in j["reasons"]), j

    print("OK test_foreign_cpu")

if __name__ == "__main__":
    main()
