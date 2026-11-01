#!/usr/bin/env python3
"""Shared window_valid rule — gate.sh and agg.py must agree (Codex P2 #459)."""
from __future__ import annotations

import importlib.util
import json
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
WV = HERE.parent / "window_valid.py"
AGG = HERE.parent / "agg.py"


def load_wv():
    spec = importlib.util.spec_from_file_location("window_valid", WV)
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


def base_ok(**over):
    r = {
        "chunk_samples": 20000,
        "p99_us": 500.0,
        "mib_s": 120.0,
        "load1_pre": 1.0,
        "load1_post": 2.0,
        "self_loaded": False,
        "gen_cores": 2,
        "client_cpu_pct_of_wall": 100.0,
        "producer_transport_retries": 0,
        "consumer_connect_retries": 0,
        "transport_retry_flag": False,
        "refused_per_ip": 0,
        "hard_zeros": {
            "sjws_connections_refused_per_ip_total": 0,
            "sjws_swarm_stream_open_sessions": 0,
            "sjws_swarm_stream_node_refusals_total": 0,
            "sjws_swarm_stream_session_admit_refusals_total": 0,
            "sjws_swarm_stream_budget_underflows_total": 0,
            "sjws_swarm_stream_reserve_underflows_total": 0,
            "sjws_swarm_stream_charged_bytes": 0,
        },
        "open_sessions_after_drain": 0,
        "drain_timed_out": False,
        "error_count": 0,
        "group_open_refusals": 0,
        "noisy": False,
        "credit_stalls": 0,
        "stall_flag": False,
    }
    r.update(over)
    return r


def main():
    wv = load_wv()
    assert wv.evaluate(base_ok())["valid"]

    # CPU 170% with gen_cores=2 → sat 160 → invalid (the gate/agg mismatch case)
    ev = wv.evaluate(base_ok(client_cpu_pct_of_wall=170.0, self_loaded=False, gen_cores=2))
    assert not ev["valid"] and ev["self_loaded"], ev

    # Same CPU with gen_cores=3 → sat 240 → valid
    ev = wv.evaluate(base_ok(client_cpu_pct_of_wall=170.0, self_loaded=False, gen_cores=3))
    assert ev["valid"] and not ev["self_loaded"], ev

    # recovered retries
    ev = wv.evaluate(base_ok(producer_transport_retries=3))
    assert not ev["valid"] and ev["transport_retry_flag"], ev

    # mid-block load1 peak via load1_peak / samples
    ev = wv.evaluate(base_ok(load1_peak=6.5, load1_post=2.0))
    assert not ev["valid"] and ev["noisy"], ev

    # CLI prints 0/1
    with tempfile.TemporaryDirectory() as td:
        f = Path(td) / "result.json"
        f.write_text(json.dumps(base_ok(client_cpu_pct_of_wall=170.0, gen_cores=2)))
        out = subprocess.check_output([sys.executable, str(WV), str(f)], text=True).strip()
        assert out == "0", out
        f.write_text(json.dumps(base_ok()))
        out = subprocess.check_output([sys.executable, str(WV), str(f)], text=True).strip()
        assert out == "1", out

        # Placeholder without mib_s must not crash agg
        out_dir = Path(td) / "out"
        abs1 = out_dir / "abs-01"
        abs1.mkdir(parents=True)
        (abs1 / "result.json").write_text(json.dumps({
            "noisy": True, "noisy_marker": "NOISY", "load1_pre": 3.5,
            "load1_post": 3.5, "noisy_reasons": ["load1_pre>=3"],
            "chunk_samples": 0, "error_count": 1, "group_open_refusals": 0,
            "self_loaded": False,
            "hard_zeros": {"sjws_connections_refused_per_ip_total": 0,
                           "sjws_swarm_stream_open_sessions": 0},
        }))
        # five good windows so agg would try to format placeholders among peers
        for i in range(2, 7):
            d = out_dir / f"abs-{i:02d}"
            d.mkdir()
            (d / "result.json").write_text(json.dumps(base_ok()))
        proc = subprocess.run([sys.executable, str(AGG), str(out_dir)],
                              capture_output=True, text=True, cwd=str(HERE.parent))
        assert proc.returncode == 0, proc.stderr + proc.stdout
        assert "TypeError" not in proc.stderr
        assert "mib_s=n/a" in proc.stdout or "valid=False" in proc.stdout
        assert "VERDICT:" in proc.stdout

    print("OK test_window_valid")


if __name__ == "__main__":
    main()
