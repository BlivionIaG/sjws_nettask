#!/usr/bin/env python3
"""agg.py must invalidate windows with error_count / group_open_refusals (Codex P2 #459)."""
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
    }
    body.update(over)
    (d / "result.json").write_text(json.dumps(body))

def run_agg(root: Path) -> str:
    return subprocess.check_output([sys.executable, str(AGG), str(root)], text=True)

def line_for(out: str, tag: str) -> str:
    for ln in out.splitlines():
        if ln.strip().startswith(tag + ":"):
            return ln
    raise AssertionError(f"missing {tag} in\n{out}")

def main():
    with tempfile.TemporaryDirectory() as td:
        root = Path(td)
        # Five otherwise-passing windows — Codex reproduction used error_count=300.
        for i in range(1, 6):
            write_abs(root, f"abs-0{i}", error_count=300, group_open_refusals=300)
        out = run_agg(root)
        print(out)
        for i in range(1, 6):
            ln = line_for(out, f"abs-0{i}")
            assert "valid=False" in ln, ln
            assert "setup_fail=True" in out or "group_open=300" in ln
        assert "VERDICT: FAIL" in out
        assert "valid windows" in out.lower() or "only 0 valid" in out or "only 0 valid windows" in out

        # Clean control: five good windows → can PASS medians
        root2 = Path(td) / "clean"
        root2.mkdir()
        for i in range(1, 6):
            write_abs(root2, f"abs-0{i}")
        out2 = run_agg(root2)
        print(out2)
        for i in range(1, 6):
            assert "valid=True" in line_for(out2, f"abs-0{i}")
        assert "VERDICT: PASS" in out2

        # error_count alone invalidates
        root3 = Path(td) / "err_only"
        root3.mkdir()
        write_abs(root3, "abs-01", error_count=1, group_open_refusals=0)
        out3 = run_agg(root3)
        assert "valid=False" in line_for(out3, "abs-01")

        # group_open alone invalidates
        root4 = Path(td) / "go_only"
        root4.mkdir()
        write_abs(root4, "abs-01", error_count=0, group_open_refusals=1)
        out4 = run_agg(root4)
        assert "valid=False" in line_for(out4, "abs-01")

        print("OK test_agg_setup_failures")

if __name__ == "__main__":
    main()
