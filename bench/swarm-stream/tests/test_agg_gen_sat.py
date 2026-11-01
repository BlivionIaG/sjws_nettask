#!/usr/bin/env python3
"""Fixture: agg.py self_loaded = gen CPU > 80% of gen_cores (inceptor 2026-10-30 18:46)."""
from __future__ import annotations
import json, subprocess, sys, tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
AGG = HERE.parent / "agg.py"

def write_abs(root: Path, tag: str, cpu: float, gen_cores: int = 2, load1_post: float = 1.0):
    d = root / tag
    d.mkdir(parents=True)
    # Minimal valid-shaped result; only CPU/self_loaded under test
    (d / "result.json").write_text(json.dumps({
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
        "hard_zeros": {"sjws_swarm_stream_open_sessions": 0},
        "open_sessions_after_drain": 0,
        "drain_timed_out": False,
        "producer_transport_retries": 0,
        "consumer_connect_retries": 0,
        "error_count": 0,
        "credit_stalls": 0,
        "stall_flag": False,
    }))

def run_agg(root: Path) -> str:
    return subprocess.check_output([sys.executable, str(AGG), str(root)], text=True)

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
        out = run_agg(root)
        print(out)
        assert "abs-01: valid=True" in out or ("abs-01:" in out and "self_loaded=False" in out and "valid=True" in out.split("abs-01:")[1].split("\n")[0])
        # Parse lines
        lines = {ln.split(":")[0].strip(): ln for ln in out.splitlines() if ln.strip().startswith("abs-")}
        def flags(tag):
            ln = lines[tag]
            return ("valid=True" in ln, "self_loaded=True" in ln or "self_loaded=1" in ln)
        v, s = flags("abs-01"); assert v and not s, lines["abs-01"]
        v, s = flags("abs-02"); assert (not v) and s, lines["abs-02"]
        v, s = flags("abs-03"); assert v and not s, lines["abs-03"]
        v, s = flags("abs-04"); assert (not v) and s, lines["abs-04"]
        v, s = flags("abs-05"); assert (not v) and s, lines["abs-05"]
        print("OK test_agg_gen_sat")

if __name__ == "__main__":
    main()
