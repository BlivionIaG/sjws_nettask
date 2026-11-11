#!/usr/bin/env python3
"""inceptor Q1 (2026-11-11): abs windows only pass when paced (offered >= 100, scheduled-time p99)."""
from __future__ import annotations

import json
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parents[1]


def write(root: Path, tag: str, **over):
    d = root / tag
    d.mkdir(parents=True)
    r = {
        "chunk_samples": 20000, "p50_us": 100.0, "p90_us": 300.0, "p99_us": 500.0,
        "p99_send_us": 400.0, "p99_sched_us": 500.0, "load1_pre": 0.5, "load1_post": 1.0,
        "mib_s": 104.0, "offered_mib_s": 110.0, "latency_basis": "scheduled",
        "self_loaded": False, "client_cpu_pct_of_wall": 100.0, "gen_cores": 2, "error_count": 0,
        "group_open_refusals": 0, "producer_transport_retries": 0, "consumer_connect_retries": 0,
        "transport_retry_flag": False, "refused_per_ip": 0, "drain_timed_out": False,
        "open_sessions_after_drain": 0,
        "hard_zeros": {k: 0 for k in (
            "sjws_swarm_stream_node_refusals_total", "sjws_swarm_stream_session_admit_refusals_total",
            "sjws_swarm_stream_budget_underflows_total", "sjws_swarm_stream_reserve_underflows_total",
            "sjws_connections_refused_per_ip_total", "sjws_swarm_stream_open_sessions")},
    }
    r.update(over)
    (d / "result.json").write_text(json.dumps(r))


def agg(root: Path) -> str:
    return subprocess.run([sys.executable, str(HERE / "agg.py"), str(root)],
                          capture_output=True, text=True, cwd=HERE).stdout


def main():
    with tempfile.TemporaryDirectory() as t:
        root = Path(t)
        for i in range(1, 6):
            write(root, f"abs-{i:02d}")
            write(root, f"cl-{i:02d}", mib_s=60.0, p99_us=9000.0, offered_mib_s=0.0, latency_basis="send")
        write(root, "floor", mib_s=10.0, offered_mib_s=10.0, p99_us=180.0, p99_send_us=150.0)
        out = agg(root)
        assert "VERDICT: PASS" in out, out
        assert "closed-loop(info) mib_s=60.0 p99=9000.0" in out, out  # beside each window, info only
        assert "floor@10.0MiB/s p99=180.0" in out, out               # Q5: reported
    with tempfile.TemporaryDirectory() as t:
        root = Path(t)
        for i in range(1, 6):
            write(root, f"abs-{i:02d}", latency_basis="send", offered_mib_s=0.0)  # closed loop
        out = agg(root)
        assert "VERDICT: FAIL" in out and "not a paced gate window" in out, out
    print("ok")


if __name__ == "__main__":
    main()
