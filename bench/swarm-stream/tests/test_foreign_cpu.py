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

    # inceptor 01:06: load1 is information only. End load1 >= 6 alone is NOT noisy.
    j = fc.judge([
        samp(0, {}),
        samp(5, {}, load1=6.2),
    ], load1_end=6.2)
    assert not j["noisy"] and j["reasons"] == [], j
    assert j["load1_peak"] >= 6.2, j

    # Mid-block spike is recorded as load1_peak but is not a NOISY reason
    j = fc.judge([
        samp(0, {}, load1=2.0),
        samp(5, {}, load1=127.0),
        samp(10, {}, load1=2.0),
    ], load1_end=2.0)
    assert not j["noisy"] and j["load1_peak"] >= 127.0, j
    assert not any("load1" in r for r in j["reasons"]), j

    # Below 6 throughout → not load-noisy (foreign quiet)
    j = fc.judge([
        samp(0, {7: 1000}, load1=5.9),
        samp(5, {7: 1000 + 40}, load1=5.5),
    ], load1_end=5.5)
    assert not j["noisy"], j

    # HTTP v5: sand-host (uid 999 / host-main.cjs) is excluded from NOISY foreign CPU
    # but platform_cpu is still recorded. 0.8 platform cores + 0.08 foreign → not NOISY.
    def samp_plat(ts, foreign, platform, load1=1.0):
        return {
            "ts": ts,
            "foreign_jiffies_sum": sum(foreign.values()),
            "platform_jiffies_sum": sum(platform.values()),
            "per_pid_jiffies": dict(foreign),
            "platform_per_pid_jiffies": dict(platform),
            "pid_meta": {str(k): {"pid": k, "cmd": "/exec-daemon/node /opt/sand/sand-host/host-main.cjs",
                                  "comm": "node", "uid": 999, "platform": True} for k in platform}
                       | {str(k): {"pid": k, "cmd": "some-other", "comm": "other", "uid": 1000, "platform": False}
                          for k in foreign},
            "foreign_load_proc": False,
            "load1": load1,
        }
    # 0.8 platform cores over 5s: delta = 0.8*100*5 = 400; 0.08 foreign: 40
    j = fc.judge([
        samp_plat(0, {8: 1000}, {3617: 2000}),
        samp_plat(5, {8: 1000 + 40}, {3617: 2000 + 400}),
    ], load1_end=1.0)
    assert not j["noisy"], j
    assert j["foreign_cpu_avg_cores"] < 0.2, j
    assert j["platform_cpu_avg_cores"] > 0.7, j
    assert abs(j["platform_cpu_avg_cores"] - 0.8) < 0.05, j

    # Same 0.8 cores if they were foreign (no platform split) still NOISY
    j = fc.judge([
        samp(0, {3617: 2000}),
        samp(5, {3617: 2000 + 400}),
    ], load1_end=1.0)
    assert j["noisy"] and j["foreign_cpu_avg_cores"] > 0.5, j
    # trigger list names the process
    assert j["foreign_trigger_procs"], j
    assert j["foreign_trigger_procs"][0]["pid"] == 3617, j
    assert j["foreign_trigger_procs"][0]["cpu"] > 0.5, j

    # is_platform unit checks (v5 set)
    assert fc.is_platform(999, "node foo", "node")
    assert fc.is_platform(1000, "/exec-daemon/node /opt/sand/sand-host/host-main.cjs", "node")
    assert fc.is_platform(0, "/pod-daemon --x", "pod-daemon")
    assert fc.is_platform(0, "python3 /usr/local/bin/sand-memory-watch", "sand-memory-watch")
    assert not fc.is_platform(1000, "/exec-daemon/node /usr/local/bin/sand-web-bot-auth.mjs", "node")

    print("OK test_foreign_cpu")

if __name__ == "__main__":
    main()
