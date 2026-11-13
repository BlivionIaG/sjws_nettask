#!/usr/bin/env python3
"""merge-gen.py: exact hist merge, CPU sum, no double-counted server CPU."""
from __future__ import annotations

import sys
from pathlib import Path

HERE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(HERE))

# import as file
import importlib.util
spec = importlib.util.spec_from_file_location("merge_gen_mod", HERE / "merge-gen.py")
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)


def idx(us: int) -> int:
    us = max(1, us)
    exp = us.bit_length() - 1
    if exp < 6:
        return us
    base = 1 << exp
    sub = ((us - base) << 6) >> exp
    sub = min(63, max(0, sub))
    return exp * 64 + sub


def part(tag, **kw):
    # a 100 µs sample and a 200 µs sample in sched hist, plus one 50 µs send
    sched = kw.pop("sched", [[idx(100), 50], [idx(200), 50]])
    send = kw.pop("send", [[idx(50), 100]])
    lag = kw.pop("lag", [[idx(20), 100]])
    r = {
        "bytes": 1024 * 1024 * 10,
        "requests": 100,
        "chunk_samples": 100,
        "elapsed_s": 20,
        "wall_s": 22,
        "streams": 100,
        "groups": 10,
        "stream_to_group": list(range(kw.pop("goff", 0), kw.pop("goff_end", 10))),
        "client_cpu_sec": 10.0,
        "client_cpu_pct_of_wall": 80.0,
        "server_cpu_sec": 15.0,
        "server_cpu_pct_of_wall": 100.0,
        "gen_cores": 1,
        "offered_mib_s": 110 / 3,
        "offered_actual_mib_s": 36.0,
        "sched_sends": 1000,
        "sched_expected": 1000,
        "sched_sent": 1000,
        "sched_received": 1000,
        "sched_unreceived_at_cutoff": 0,
        "sched_phantom_streams": 0,
        "sched_streams_short": 0,
        "sched_late_pct": 1.0,
        "sched_lag_max_us": 40,
        "error_count": 0,
        "load1_post": 1.0,
        "cutoff_hit": False,
        "drain_timed_out": False,
        "catchup_s": 0.5,
        "catchup_cpu_pct_of_wall": 90.0,
        "latency_basis": "scheduled",
        "harness_commit": "aaaaaaaaaaaa",
        "validity_marker": "q4",
        "chunk_latency_send": {"hist": send, "p99_us": 50},
        "chunk_latency_sched": {"hist": sched, "p99_us": 200},
        "sched_lag": {"hist": lag, "p99_us": 20, "first_s_hist": lag, "rest_hist": lag,
                      "first_s_p99_us": 20, "rest_p99_us": 20},
        "counters": {"sjws_swarm_stream_open_sessions": 0.0, "tag": float(ord(tag[0]) if tag else 0)},
        "hard_zeros": {"sjws_swarm_stream_open_sessions": 0.0},
        "errors": [],
        "p99_us": 200,
        "p99_sched_us": 200,
        "p99_send_us": 50,
    }
    r.update(kw)
    return r


def main():
    a = part("a", goff=0, goff_end=10)
    b = part("b", goff=10, goff_end=20)
    c = part("c", goff=20, goff_end=31)
    m = mod.merge([a, b, c], gen_cores=3, secs=20)
    assert m["bytes"] == 3 * 1024 * 1024 * 10, m["bytes"]
    assert m["sched_received"] == 3000
    assert m["streams"] == 300
    assert m["groups"] == 31, m["groups"]
    assert m["client_cpu_pct_of_wall"] == 240.0, m["client_cpu_pct_of_wall"]
    assert m["gen_cores"] == 3
    assert m["gen_sat_limit_pct"] == 240.0
    # 240% is not > 240, so not self_loaded
    assert m["self_loaded"] is False, m["self_loaded"]
    assert m["generator_saturated"] is False
    # server CPU must NOT be summed (would be 45 / 300%)
    assert m["server_cpu_sec"] == 15.0, m["server_cpu_sec"]
    assert m["server_cpu_pct_of_wall"] == 100.0
    # p99 from merged hist: 150 samples at 100, 150 at 200 → p99 is 200's upper
    p99 = m["p99_sched_us"]
    assert p99 is not None and p99 >= 200, p99
    assert m["p99_us"] == p99
    assert m["latency_basis"] == "scheduled"
    assert m["offered_mib_s"] == 110.0
    assert m["gen_procs"] == 3
    assert m["cutoff_hit"] is False
    # last process counters kept
    assert m["counters"]["tag"] == float(ord("c"))

    # self_loaded when summed CPU exceeds 240
    a2 = dict(a); a2["client_cpu_pct_of_wall"] = 90
    b2 = dict(b); b2["client_cpu_pct_of_wall"] = 90
    c2 = dict(c); c2["client_cpu_pct_of_wall"] = 90
    m2 = mod.merge([a2, b2, c2], gen_cores=3, secs=20)
    assert m2["client_cpu_pct_of_wall"] == 270.0
    assert m2["self_loaded"] is True
    assert m2["generator_saturated"] is True

    # shortfall is summed
    a3 = dict(a); a3["sched_unreceived_at_cutoff"] = 5; a3["sched_streams_short"] = 1
    m3 = mod.merge([a3, b, c], gen_cores=3, secs=20)
    assert m3["sched_unreceived_at_cutoff"] == 5
    assert m3["sched_streams_short"] == 1

    print("OK test_merge_gen")


def test_merge_gen_refuses_empty(tmp_path):
    import json as _json
    import subprocess, sys
    script = Path(__file__).resolve().parents[1] / "merge-gen.py"
    empty = tmp_path / "gen-0.json"
    empty.write_text("")
    good = tmp_path / "gen-1.json"
    good.write_text(_json.dumps({"bytes": 1, "requests": 1, "mib_s": 1, "elapsed_s": 20,
                                 "client_cpu_sec": 1, "client_cpu_pct_of_wall": 10,
                                 "server_cpu_sec": 1, "server_cpu_pct_of_wall": 10,
                                 "p99_us": 100, "lat_hist_sched": {}, "harness_commit": "abc"}))
    out = tmp_path / "result.json"
    r = subprocess.run([sys.executable, str(script), str(empty), str(good), "-o", str(out)],
                       capture_output=True, text=True)
    assert r.returncode != 0, r.stdout + r.stderr
    assert "empty" in r.stderr.lower() or "empty" in r.stdout.lower()
    assert not out.exists() or out.stat().st_size == 0
    print("OK test_merge_gen_refuses_empty")


if __name__ == "__main__":
    import tempfile
    main()
    with tempfile.TemporaryDirectory() as d:
        test_merge_gen_refuses_empty(Path(d))
