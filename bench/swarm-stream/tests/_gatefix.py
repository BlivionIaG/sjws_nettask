"""Shared fixture bits for agg tests (#415 Architecture review): slot accounting + low-load floor."""
from __future__ import annotations

import json
from pathlib import Path

# A clean paced window: every in-window slot received, no cutoff, no stamp fallback.
ACCOUNTING = {
    "sched_expected": 20000, "sched_sent": 20000, "sched_received": 20000,
    "sched_unreceived_at_cutoff": 0, "sched_phantom_streams": 0, "sched_stamp_fallback": 0,
    "cutoff_hit": False, "offered_actual_mib_s": 110.0, "sched_sends": 20000,
    "sched_streams_short": 0, "sched_late_registered": 0, "catchup_s": 0.2, "catchup_cpu_pct_of_wall": 40.0,
    # Stamped by StreamLoad (Q4 landed). A countable window carries a hex sha and the "q4" marker.
    "harness_commit": "fa125a483a11", "validity_marker": "q4",
}

HARD_ZEROS = {k: 0 for k in (
    "sjws_swarm_stream_node_refusals_total", "sjws_swarm_stream_session_admit_refusals_total",
    "sjws_swarm_stream_budget_underflows_total", "sjws_swarm_stream_reserve_underflows_total",
    "sjws_connections_refused_per_ip_total", "sjws_swarm_stream_open_sessions")}


def write_floor(root: Path, p99_us: float = 180.0, tag: str = "lowload-floor-1", **over) -> None:
    """Valid low-load end-to-end floor leg (10 MiB/s offered)."""
    d = root / tag
    if (d / "result.json").exists():
        return
    d.mkdir(parents=True, exist_ok=True)
    r = {"chunk_samples": 200000, "p50_us": 60.0, "p90_us": 120.0, "p99_us": p99_us,
         "p99_send_us": p99_us - 30, "mib_s": 10.0, "offered_mib_s": 10.0,
         "latency_basis": "scheduled", "load1_pre": 0.5, "load1_post": 0.8, "self_loaded": False,
         "client_cpu_pct_of_wall": 20.0, "gen_cores": 2, "error_count": 0, "group_open_refusals": 0,
         "producer_transport_retries": 0, "consumer_connect_retries": 0,
         "transport_retry_flag": False, "refused_per_ip": 0, "drain_timed_out": False,
         "open_sessions_after_drain": 0, "hard_zeros": dict(HARD_ZEROS), **ACCOUNTING}
    r.update(over)
    (d / "result.json").write_text(json.dumps(r))
