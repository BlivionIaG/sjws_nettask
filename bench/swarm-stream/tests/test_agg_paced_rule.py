#!/usr/bin/env python3
"""Gate rule (inceptor Q1 + Architecture review of 5f4805d6): abs/A-A windows pass only when paced at
exactly 110 MiB/s with scheduled-basis p99, full slot accounting, a valid low-load floor <= 300 µs,
and (sequencing) only once the Q4 validity change has landed."""
from __future__ import annotations

import json
import subprocess
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from _gatefix import ACCOUNTING, HARD_ZEROS, write_floor  # noqa: E402

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
        "open_sessions_after_drain": 0, "hard_zeros": dict(HARD_ZEROS), **ACCOUNTING,
    }
    r.update(over)
    (d / "result.json").write_text(json.dumps(r))


def agg(root: Path, q4: bool = True) -> str:
    cmd = [sys.executable, str(HERE / "agg.py"), str(root)] + (["--assume-q4"] if q4 else [])
    return subprocess.run(cmd, capture_output=True, text=True, cwd=HERE).stdout


def case(abs_over=None, bad_tag="abs-03", floor=True, floor_p99=180.0, aa_over=None, q4=True, extra=None):
    with tempfile.TemporaryDirectory() as t:
        root = Path(t)
        for i in range(1, 6):
            tag = f"abs-{i:02d}"
            write(root, tag, **((abs_over or {}) if tag == bad_tag or bad_tag == "all" else {}))
            write(root, f"cl-{i:02d}", mib_s=60.0, p99_us=9000.0, offered_mib_s=0.0, latency_basis="send")
        if aa_over is not None:
            write(root, "aa-a", **aa_over)
            write(root, "aa-b")
        if floor:
            write_floor(root, p99_us=floor_p99)
        if extra:
            extra(root)
        return agg(root, q4)


def main():
    out = case()
    assert "VERDICT: TEST_ONLY_PASS" in out, out
    assert "closed-loop(info) mib_s=60.0 p99=9000.0" in out, out   # beside each window, info only
    assert "lowload-floor@10.0MiB/s p99=180.0" in out, out          # Q5: reported
    assert "lag_p50=" in out and "offered_actual=110.0" in out, out  # item 2/5 reporting

    # Sequencing: pre-Q4 nothing counts, even a clean run.
    out = case(q4=False)
    assert "VERDICT: NOT_COUNTABLE" in out and "would be PASS" in out, out

    # Item 2: exactly 110, not "any >= 100", not < 100, not closed loop.
    for off in (100.0, 105.0, 120.0, 95.0):
        out = case({"offered_mib_s": off})
        assert "VERDICT: TEST_ONLY_FAIL" in out and "not a paced gate window at 110" in out, (off, out)
    out = case({"latency_basis": "send", "offered_mib_s": 0.0}, bad_tag="all")
    assert "VERDICT: TEST_ONLY_FAIL" in out, out
    out = case(aa_over={"offered_mib_s": 100.0})
    assert "VERDICT: TEST_ONLY_FAIL" in out and "A/A leg 0: not paced at 110" in out, out

    # Item 1: stall at window end. Slots of the last ~1.8 s never arrived; they used to vanish.
    out = case({"sched_received": 18200, "sched_unreceived_at_cutoff": 1800})
    assert "VERDICT: TEST_ONLY_FAIL" in out and "slot shortfall 1800 of 20000" in out, out
    # Item 1: ~9 % hung streams (never claimed -> phantom slots; drain cutoff hit).
    out = case({"sched_received": 18200, "sched_unreceived_at_cutoff": 1800,
                "sched_phantom_streams": 90, "cutoff_hit": True})
    assert "VERDICT: TEST_ONLY_FAIL" in out and "drain cutoff hit" in out, out
    out = case({"sched_expected": None})
    assert "VERDICT: TEST_ONLY_FAIL" in out and "no slot accounting" in out, out

    # Optional: scheduled-stamp parse fallback is flagged on paced windows.
    out = case({"sched_stamp_fallback": 3})
    assert "VERDICT: TEST_ONLY_FAIL" in out and "scheduled stamps unparsable" in out, out

    # Item 5: floor acceptance fixed in advance (<= 300 µs), missing floor, and rerun of an invalid one.
    out = case(floor_p99=450.0)
    assert "VERDICT: TEST_ONLY_FAIL" in out and "cannot resolve the 1 ms gate" in out, out
    out = case(floor=False)
    assert "VERDICT: TEST_ONLY_FAIL" in out and "no valid low-load" in out, out
    out = case(floor=False, extra=lambda r: (write_floor(r, tag="lowload-floor-1", noisy=True),
                                              write_floor(r, tag="lowload-floor-2")))
    assert "VERDICT: TEST_ONLY_PASS" in out, out
    # --assume-q4 never prints a countable verdict.
    assert "VERDICT: PASS" not in case() and "VERDICT: FAIL" not in case({"offered_mib_s": 100.0})

    # Re-review item 1: offered_actual_mib_s is checked against 110 (0.5 % allowance).
    out = case({"offered_actual_mib_s": 100.1})
    assert "VERDICT: TEST_ONLY_FAIL" in out and "offered_actual_mib_s=100.1 not within" in out, out
    assert "VERDICT: TEST_ONLY_PASS" in case({"offered_actual_mib_s": 109.6})
    out = case({"offered_actual_mib_s": None})
    assert "VERDICT: TEST_ONLY_FAIL" in out, out
    # Optional: any stream short of its slots fails, not just the global count.
    out = case({"sched_streams_short": 3})
    assert "VERDICT: TEST_ONLY_FAIL" in out and "3 streams short" in out, out
    # Optional: generator saturated during the catch-up period.
    out = case({"catchup_s": 2.0, "catchup_cpu_pct_of_wall": 190.0, "gen_sat_limit_pct": 160.0})
    assert "VERDICT: TEST_ONLY_FAIL" in out and "saturated during catch-up" in out, out
    # Re-review fix: no p99 is reported for a window with a shortfall.
    out = case({"sched_received": 18200, "sched_unreceived_at_cutoff": 1800})
    assert "abs-03: valid=True mib_s=104.000 p50=100.0 p99=n/a (shortfall 1800)" in out, out
    # Re-review item 2: every result.json must carry the harness commit.
    out = case({"harness_commit": ""})
    assert "VERDICT: TEST_ONLY_FAIL" in out and "no harness_commit" in out, out

    # Re-review item 2: once Q4 lands, pre-Q4 windows never count; q4-stamped ones do.
    import contextlib
    import io
    sys.path.insert(0, str(HERE))
    import agg as aggmod  # noqa: E402

    def landed(marker):
        with tempfile.TemporaryDirectory() as t:
            root = Path(t)
            for i in range(1, 6):
                write(root, f"abs-{i:02d}", validity_marker=marker)
            write_floor(root, validity_marker=marker)
            buf = io.StringIO()
            old, aggmod.Q4_LANDED = aggmod.Q4_LANDED, True
            try:
                with contextlib.redirect_stdout(buf):
                    aggmod.main([str(root)])
            finally:
                aggmod.Q4_LANDED = old
            return buf.getvalue()
    out = landed("pre-q4")
    assert "VERDICT: FAIL" in out and "only 0 valid windows" in out, out
    out = landed("q4")
    assert "VERDICT: PASS" in out, out
    print("ok")


if __name__ == "__main__":
    main()
