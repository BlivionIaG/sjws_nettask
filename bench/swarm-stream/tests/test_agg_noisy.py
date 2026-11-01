#!/usr/bin/env python3
"""agg.py rejects NOISY legs (07:05 addendum)."""
from __future__ import annotations
import json, subprocess, sys, tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
AGG = HERE.parent / "agg.py"

def write_abs(root: Path, tag: str, **over):
    d = root / tag
    d.mkdir(parents=True)
    body = {
        "chunk_samples": 20000,
        "p99_us": 500.0,
        "p50_us": 100.0,
        "p90_us": 200.0,
        "mib_s": 120.0,
        "load1_pre": 0.5,
        "load1_post": 1.0,
        "self_loaded": False,
        "client_cpu_pct_of_wall": 10.0,
        "gen_cores": 2,
        "hard_zeros": {
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
        "group_open_refusals": 0,
        "credit_stalls": 0,
        "stall_flag": False,
        "noisy": False,
    }
    body.update(over)
    (d / "result.json").write_text(json.dumps(body))

def run_agg(root: Path) -> str:
    return subprocess.check_output([sys.executable, str(AGG), str(root)], text=True)

def line(out: str, tag: str) -> str:
    for ln in out.splitlines():
        if ln.strip().startswith(tag + ":"):
            return ln
    raise AssertionError(tag)

def main():
    with tempfile.TemporaryDirectory() as td:
        root = Path(td)
        # Five clean → PASS
        for i in range(1, 6):
            write_abs(root, f"abs-0{i}")
        out = run_agg(root)
        assert "VERDICT: PASS" in out, out

        # Marker NOISY
        root2 = Path(td) / "m"
        root2.mkdir()
        for i in range(1, 6):
            write_abs(root2, f"abs-0{i}", noisy=True, noisy_marker="NOISY")
        out2 = run_agg(root2)
        assert "valid=False" in line(out2, "abs-01")
        assert "VERDICT: FAIL" in out2

        # foreign_cpu_avg_cores > 0.5
        root3 = Path(td) / "f"
        root3.mkdir()
        write_abs(root3, "abs-01", foreign_cpu_avg_cores=0.6)
        out3 = run_agg(root3)
        assert "valid=False" in line(out3, "abs-01")
        assert "noisy=True" in out3

        # load1_post >= 6
        root4 = Path(td) / "l"
        root4.mkdir()
        write_abs(root4, "abs-01", load1_post=6.0)
        out4 = run_agg(root4)
        assert "valid=False" in line(out4, "abs-01")

        print("OK test_agg_noisy")

if __name__ == "__main__":
    main()
