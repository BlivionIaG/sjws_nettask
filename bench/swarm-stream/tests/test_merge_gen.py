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

    # Mode B: any single process > 80% of its own core is self_loaded, even if the sum is < 240%.
    a4 = dict(a); a4["client_cpu_pct_of_wall"] = 90
    b4 = dict(b); b4["client_cpu_pct_of_wall"] = 10
    c4 = dict(c); c4["client_cpu_pct_of_wall"] = 10
    m4 = mod.merge([a4, b4, c4], gen_cores=3, secs=20)
    assert m4["client_cpu_pct_of_wall"] == 110.0
    assert m4["client_cpu_pct_per_proc"] == [90, 10, 10], m4["client_cpu_pct_per_proc"]
    assert m4["self_loaded"] is True, m4
    assert m4["generator_saturated_any_proc"] is True
    assert m4["generator_saturated"] is True
    # 503 fields are summed
    a5 = dict(a); a5["http_503_responses"] = 16; a5["http_503_claim_giveups"] = 1; a5["http_503_budget_exhausted"] = 16
    b5 = dict(b); b5["http_503_responses"] = 32; b5["http_503_claim_giveups"] = 2; b5["http_503_budget_exhausted"] = 32
    m5 = mod.merge([a5, b5, c], gen_cores=3, secs=20)
    assert m5["http_503_responses"] == 48, m5["http_503_responses"]
    assert m5["http_503_claim_giveups"] == 3
    assert m5["http_503_budget_exhausted"] == 48

    # smoke5 recorded rungs (inceptor 21:32): 25 and 50 must not be self_loaded;
    # 80 (sum 270.5 > 240) and 110 (one proc 99.8%) must be. load1_post>=4 does not set the flag.
    templates = [a, b, c]
    def rung(*cpus):
        parts = []
        for i, cpu in enumerate(cpus):
            d = dict(templates[i])
            d["client_cpu_pct_of_wall"] = cpu
            d["load1_post"] = 4.5
            parts.append(d)
        return mod.merge(parts, gen_cores=3, secs=20)

    m25 = rung(42.419247456034334, 48.67595620658049, 49.45217851201966)
    assert abs(m25["client_cpu_pct_of_wall"] - 140.5473821746345) < 1e-9, m25["client_cpu_pct_of_wall"]
    assert m25["self_loaded"] is False, m25
    m50 = rung(57.59790813221684, 73.39507959222537, 77.951515731217)
    assert abs(m50["client_cpu_pct_of_wall"] - 208.9445034556592) < 1e-9, m50["client_cpu_pct_of_wall"]
    assert m50["self_loaded"] is False, m50
    m80 = rung(84.79420079320167, 88.79617145807866, 96.90249720881268)
    assert abs(m80["client_cpu_pct_of_wall"] - 270.492869460093) < 1e-9, m80["client_cpu_pct_of_wall"]
    assert m80["self_loaded"] is True, m80
    m110 = rung(43.20033132842212, 99.81830645762592, 38.207023019281536)
    assert abs(m110["client_cpu_pct_of_wall"] - 181.22566080532957) < 1e-9, m110["client_cpu_pct_of_wall"]
    assert m110["self_loaded"] is True, m110
    assert m110["generator_saturated_any_proc"] is True

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


def test_merge_gen_pace_split():
    """#502 pacer: WAKE/BEHIND/CAPWAIT counts and GC sum, hists merged exactly."""
    a = part("a", pace_wake_n=90, pace_behind_n=10, pace_capwait_n=0, gc_ms_window=3, gc_count_window=1,
             pipeline_depth=8, pace_wake_hist=[[idx(2), 90]], pace_behind_hist=[[idx(500), 10]],
             ack_rtt_hist=[[idx(60), 100]])
    b = part("b", pace_wake_n=100, pace_behind_n=0, pace_capwait_n=0, gc_ms_window=2, gc_count_window=1,
             pipeline_depth=8, pace_wake_hist=[[idx(2), 100]], ack_rtt_hist=[[idx(60), 99], [idx(900), 1]])
    m = mod.merge([a, b], gen_cores=3, secs=20)
    assert m["pace_wake_n"] == 190 and m["pace_behind_n"] == 10 and m["pace_capwait_n"] == 0, m
    assert m["gc_ms_window"] == 5 and m["gc_count_window"] == 2 and m["pipeline_depth"] == 8
    assert m["pace_behind_p99_us"] == mod.upper(idx(500))
    assert m["pace_capwait_p99_us"] is None and m["pace_capwait_hist"] == []
    assert m["ack_rtt_p50_us"] == mod.upper(idx(60))
    assert sum(c for _, c in m["ack_rtt_hist"]) == 200
    c = part("c", sched_per_s_hist=[[0, [[idx(100), 10]]], [1, [[idx(300), 5]]]])
    d = part("d", sched_per_s_hist=[[1, [[idx(300), 5], [idx(900), 1]]]])
    m2 = mod.merge([c, d], gen_cores=3, secs=20)
    assert [e[0] for e in m2["sched_per_s_hist"]] == [0, 1], m2["sched_per_s_hist"]
    assert m2["sched_p99_per_s_us"][1] == [1, mod.upper(idx(900)), 11], m2["sched_p99_per_s_us"]
    print("OK test_merge_gen_pace_split")


if __name__ == "__main__":
    import tempfile
    main()
    test_merge_gen_pace_split()
    with tempfile.TemporaryDirectory() as d:
        test_merge_gen_refuses_empty(Path(d))
