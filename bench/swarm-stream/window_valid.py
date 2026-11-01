#!/usr/bin/env python3
"""Shared absolute-window validity for agg.py and gate.sh (inceptor / #459).

A window is valid only when ALL of:
  - chunk_samples >= 10000, p99 present, load1_pre < 3
  - not self_loaded (recomputed: load1_post>=4 OR cpu > 80*gen_cores)
  - no transport retries / refused_per_ip / drain timeout / open sessions after drain
  - no setup failures (error_count / group_open_refusals)
  - not NOISY (marker, foreign_cpu_avg>0.5, foreign load proc, OR any/end load1>=6)
"""
from __future__ import annotations

import json
import sys
from pathlib import Path


def _f(v, default=0.0) -> float:
    try:
        return float(v) if v is not None else float(default)
    except (TypeError, ValueError):
        return float(default)


def _i(v, default=0) -> int:
    try:
        return int(float(v)) if v is not None else int(default)
    except (TypeError, ValueError):
        return int(default)


def evaluate(r: dict) -> dict:
    n = _i(r.get("chunk_samples"))
    p99 = r.get("p99_us")
    pre = _f(r.get("load1_pre"), 99.0)
    load1_post = _f(r.get("load1_post"), 0.0)
    gen_cores = max(1, _i(r.get("gen_cores"), 2))
    sat_lim = 80.0 * gen_cores
    cpu = r.get("client_cpu_pct_of_wall")
    try:
        cpu_f = float(cpu) if cpu is not None else float("nan")
    except (TypeError, ValueError):
        cpu_f = float("nan")
    gen_sat = (cpu_f == cpu_f) and cpu_f > sat_lim
    self_loaded = bool(r.get("self_loaded")) or gen_sat or load1_post >= 4.0

    ptr = _i(r.get("producer_transport_retries"))
    ccr = _i(r.get("consumer_connect_retries"))
    transport_retry_flag = bool(r.get("transport_retry_flag")) or ptr > 0 or ccr > 0

    refused = _i(r.get("refused_per_ip"))
    hz = r.get("hard_zeros") or {}
    if refused == 0:
        refused = _i(hz.get("sjws_connections_refused_per_ip_total"), 0)

    open_after = r.get("open_sessions_after_drain")
    if open_after is None:
        open_after = hz.get("sjws_swarm_stream_open_sessions")
    open_after_n = _i(open_after, -1) if open_after is not None else -1
    drain_timed_out = bool(r.get("drain_timed_out"))
    err_count = _i(r.get("error_count"))
    group_open = _i(r.get("group_open_refusals"))
    setup_fail = err_count > 0 or group_open > 0

    noisy = bool(r.get("noisy")) or str(r.get("noisy_marker") or "").upper() == "NOISY"
    fcpu = _f(r.get("foreign_cpu_avg_cores"), 0.0)
    if fcpu > 0.5:
        noisy = True
    if r.get("foreign_load_proc_seen"):
        noisy = True
    if load1_post >= 6.0:
        noisy = True
    # any mid-block sample (07:05 / Codex P1): foreign_cpu judgment may stash load1_peak
    if _f(r.get("load1_peak"), 0.0) >= 6.0:
        noisy = True
    if any(_f(x, 0.0) >= 6.0 for x in (r.get("load1_samples") or [])):
        noisy = True

    valid = (
        n >= 10000
        and p99 is not None
        and pre < 3.0
        and not self_loaded
        and not transport_retry_flag
        and refused == 0
        and not drain_timed_out
        and open_after_n == 0
        and not setup_fail
        and not noisy
    )
    return {
        "valid": valid,
        "self_loaded": self_loaded,
        "gen_cores": gen_cores,
        "gen_sat_limit_pct": sat_lim,
        "client_cpu_pct_of_wall": cpu_f,
        "transport_retry_flag": transport_retry_flag,
        "producer_transport_retries": ptr,
        "consumer_connect_retries": ccr,
        "refused_per_ip": refused,
        "drain_timed_out": drain_timed_out,
        "open_sessions_after_drain": open_after_n,
        "errors": err_count,
        "group_open_refusals": group_open,
        "setup_fail": setup_fail,
        "noisy": noisy,
        "foreign_cpu_avg_cores": fcpu,
        "samples": n,
        "p99_us": p99,
        "load1_pre": pre,
        "load1_post": load1_post,
    }


def main(argv: list[str]) -> int:
    """CLI for gate.sh: print 1 or 0."""
    if len(argv) < 2:
        print("usage: window_valid.py <result.json>", file=sys.stderr)
        return 2
    r = json.loads(Path(argv[1]).read_text())
    print("1" if evaluate(r)["valid"] else "0")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
