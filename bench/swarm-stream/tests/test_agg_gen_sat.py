#!/usr/bin/env python3
"""Fixture: agg.py self_loaded = gen CPU > 80% of gen_cores (inceptor 2026-10-30 18:46)."""
from __future__ import annotations
import json, subprocess, sys, tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
AGG = HERE.parent / "agg.py"

def write_abs(root: Path, tag: str, cpu: float, gen_cores: int = 2, load1_post: float = 1.0,
              gen_sat_limit_pct=None, hard_zeros=None):
    d = root / tag
    d.mkdir(parents=True)
    body = {
        "chunk_samples": 20000,
        "p99_us": 500.0,
        "p50_us": 100.0,
        "p90_us": 200.0,
        "mib_s": 120.0,
        "load1_pre": 0.5,
        "load1_post": load1_post,
        "self_loaded": False,  # loadgen may under-report; agg must recompute
        "client_cpu_pct_of_wall": cpu,
        "gen_cores": gen_cores,
        "hard_zeros": hard_zeros if hard_zeros is not None else {
            "sjws_swarm_stream_open_sessions": 0,
            "sjws_swarm_stream_node_refusals_total": 0,
            "sjws_swarm_stream_session_admit_refusals_total": 0,
            "sjws_swarm_stream_budget_underflows_total": 0,
            "sjws_swarm_stream_reserve_underflows_total": 0,
            "sjws_connections_refused_per_ip_total": 0,
        },
        "open_sessions_after_drain": 0,
        "drain_timed_out": False,
        "producer_transport_retries": 0,
        "consumer_connect_retries": 0,
        "error_count": 0,
        "credit_stalls": 0,
        "stall_flag": False,
    }
    if gen_sat_limit_pct is not None:
        body["gen_sat_limit_pct"] = gen_sat_limit_pct
    (d / "result.json").write_text(json.dumps(body))

def run_agg(root: Path) -> str:
    return subprocess.check_output([sys.executable, str(AGG), str(root)], text=True)

def flags(out: str, tag: str):
    lines = {ln.split(":")[0].strip(): ln for ln in out.splitlines() if ln.strip().startswith("abs-")}
    ln = lines[tag]
    return ("valid=True" in ln, "self_loaded=True" in ln or "self_loaded=1" in ln), ln

def main():
    with tempfile.TemporaryDirectory() as td:
        root = Path(td)
        # 2 cores → limit 160. 150 OK, 161 self_loaded
        write_abs(root, "abs-01", cpu=150.0, gen_cores=2)
        write_abs(root, "abs-02", cpu=161.0, gen_cores=2)
        # 3 cores → limit 240. 230 OK, 241 self_loaded
        write_abs(root, "abs-03", cpu=230.0, gen_cores=3)
        write_abs(root, "abs-04", cpu=241.0, gen_cores=3)
        # load1_post >= 4 forces self_loaded even if CPU low
        write_abs(root, "abs-05", cpu=10.0, gen_cores=2, load1_post=4.0)
        # Stale recorded override must NOT raise the limit (Codex P2): cpu=170, cores=2,
        # gen_sat_limit_pct=180 would wrongly pass if trusted; disclosed limit is 160.
        write_abs(root, "abs-06", cpu=170.0, gen_cores=2, gen_sat_limit_pct=180.0)
        out = run_agg(root)
        print(out)
        for tag, expect_valid, expect_sat in (
            ("abs-01", True, False),
            ("abs-02", False, True),
            ("abs-03", True, False),
            ("abs-04", False, True),
            ("abs-05", False, True),
            ("abs-06", False, True),
        ):
            (v, s), ln = flags(out, tag)
            assert v is expect_valid and s is expect_sat, (tag, ln, v, s, expect_valid, expect_sat)
        print("OK test_agg_gen_sat")

if __name__ == "__main__":
    main()
