#!/usr/bin/env python3
"""Aggregate S2c PR 8 stream-gate results. Absolute §13 is the hard gate; ABBA is info-only."""
from __future__ import annotations

import json
import math
import re
import sys
from pathlib import Path

from window_valid import evaluate as evaluate_window

# inceptor Q1 (2026-11-11): the disclosed gate operating point. abs and A/A windows must be offered
# exactly this rate (no other rate counts, Architecture item 2).
GATE_RATE_MIB_S = 110.0
# Architecture item 5, fixed before any run: the low-load end-to-end floor (10 MiB/s offered, same
# config) must show scheduled-basis p99 <= 300 µs (30 % of the 1 ms budget), or the harness cannot
# resolve the gate and the verdict is not PASS. It understates generator delay at 110 MiB/s.
FLOOR_P99_MAX_US = 300.0
# Architecture item 7 / inceptor Q4: no gate window counts until the Q4 validity change lands
# (after #465 merges: master merged into #502, stricter self_loaded + --rate-mib-s folded in).
# Flip to True in that commit, together with StreamLoad.VALIDITY_MARKER = "q4".
# Re-review item 2: once landed, only windows whose result.json carries a harness commit AND the
# "q4" validity marker count; older windows can never turn into a PASS. --assume-q4 exists for the
# tests only and never prints PASS (TEST_ONLY_PASS / TEST_ONLY_FAIL).
Q4_LANDED = False
REQUIRED_VALIDITY_MARKER = "q4"
# Re-review item 1: offered_actual_mib_s (sched_sends x chunk_bytes / secs) must be within this
# rounding allowance of 110. Per-stream edge rounding is <= 1 slot of ~2 250 per stream in a 20 s
# window (<= 0.05 %); integer-ns interval truncation is negligible; 0.5 % leaves 10x headroom and
# still catches ~1 % of slots never sent.
OFFERED_ACTUAL_TOL_PCT = 0.5
# Architecture approval of fa125a48, required change: a countable harness_commit is a full (40) or
# short (7–40) lowercase hex sha, never empty, never "unknown", never a "-dirty" suffix. DONE lines
# may still record "-dirty" for information; those windows never count.
HARNESS_COMMIT_RE = re.compile(r"^[0-9a-f]{7,40}$")


def binom_sf(k, n, p=0.5):
    from math import comb
    return sum(comb(n, i) * (p ** i) * ((1 - p) ** (n - i)) for i in range(k, n + 1))


def load(p: Path):
    try:
        return json.loads(p.read_text())
    except Exception:
        return None


def closed_loop_info(out: Path, abs_tag: str) -> dict:
    """Closed-loop info leg cl-NN run right after abs-NN (inceptor Q1: info only)."""
    r = load(out / abs_tag.replace("abs-", "cl-", 1) / "result.json")
    if not r:
        return {}
    return {"mib_s": r.get("mib_s"), "p99_us": r.get("p99_us"),
            "client_cpu_pct_of_wall": r.get("client_cpu_pct_of_wall")}


def lowload_floor(out: Path):
    """First valid lowload-floor-N leg (gate.sh reruns invalid ones), else the last one, else None."""
    legs = [load(p) for p in sorted(out.glob("lowload-floor-*/result.json"))]
    legs = [r for r in legs if r]
    for r in legs:
        if evaluate_window(r)["valid"]:
            return r, True
    return (legs[-1], False) if legs else (None, False)


def is_gate_rate(off) -> bool:
    return isinstance(off, (int, float)) and abs(float(off) - GATE_RATE_MIB_S) < 1e-6


def offered_actual_ok(v) -> bool:
    return (isinstance(v, (int, float))
            and abs(float(v) - GATE_RATE_MIB_S) <= GATE_RATE_MIB_S * OFFERED_ACTUAL_TOL_PCT / 100.0)


def harness_commit_ok(c) -> bool:
    """True iff c is a full or short hex sha with no -dirty suffix (and not 'unknown' or empty)."""
    return isinstance(c, str) and bool(HARNESS_COMMIT_RE.fullmatch(c))


def harness_commit_reason(tag: str, c) -> str | None:
    if not isinstance(c, str) or not c:
        return f"{tag}: no harness_commit stamped in result.json"
    if c.endswith("-dirty"):
        return (f"{tag}: harness_commit={c} is a dirty tree "
                "(DONE may record -dirty for information; such windows never count)")
    if not harness_commit_ok(c):
        return f"{tag}: harness_commit={c!r} is not a full or short hex sha"
    return None


def shortfall(r: dict):
    """In-window slots not received (int), or None when the window has no slot accounting."""
    exp, rec = r.get("sched_expected"), r.get("sched_received")
    if not isinstance(exp, int) or not isinstance(rec, int) or exp <= 0:
        return None
    return max(0, exp - rec)


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    assume_q4 = "--assume-q4" in argv
    enforce_marker = Q4_LANDED and not assume_q4
    out = Path([a for a in argv if not a.startswith("--")][0])
    abs_runs = sorted(out.glob("abs-*/result.json"))
    windows = []
    for p in abs_runs:
        r = load(p)
        if not r:
            continue
        # Shared absolute-window validity (window_valid.py) — same rule as gate.sh
        ev = evaluate_window(r)
        short = shortfall(r)
        # Re-review fix: no p99 is ever reported for a window with a slot shortfall (or no accounting
        # on a paced window): the missing slots' true ages are only lower-bounded.
        no_p99 = (short is None and r.get("latency_basis") == "scheduled") or (short or 0) > 0
        marker_ok = (harness_commit_ok(r.get("harness_commit"))
                     and r.get("validity_marker") == REQUIRED_VALIDITY_MARKER)
        windows.append({
            "tag": p.parent.name,
            "valid": ev["valid"],
            "mib_s": r.get("mib_s"),
            "p50_us": r.get("p50_us"),
            "p90_us": r.get("p90_us"),
            "p99_us": None if no_p99 else ev["p99_us"],
            "shortfall": short,
            "harness_commit": r.get("harness_commit"),
            "validity_marker": r.get("validity_marker"),
            "marker_ok": marker_ok,
            "samples": ev["samples"],
            "self_loaded": ev["self_loaded"],
            "gen_cores": ev["gen_cores"],
            "gen_sat_limit_pct": ev["gen_sat_limit_pct"],
            "client_cpu_pct_of_wall": ev["client_cpu_pct_of_wall"],
            "stall_flag": r.get("stall_flag"),
            "credit_stalls": r.get("credit_stalls"),
            "hard_zeros": r.get("hard_zeros"),
            "errors": ev["errors"],
            "group_open_refusals": ev["group_open_refusals"],
            "setup_fail": ev["setup_fail"],
            "noisy": ev["noisy"],
            "foreign_cpu_avg_cores": ev["foreign_cpu_avg_cores"],
            "producer_transport_retries": ev["producer_transport_retries"],
            "consumer_connect_retries": ev["consumer_connect_retries"],
            "transport_retry_flag": ev["transport_retry_flag"],
            "refused_per_ip": ev["refused_per_ip"],
            "drain_timed_out": ev["drain_timed_out"],
            "open_sessions_after_drain": ev["open_sessions_after_drain"],
            "max_connections_per_ip": r.get("max_connections_per_ip"),
            "load1_pre": ev["load1_pre"],
            "load1_post": ev["load1_post"],
            # inceptor Q1/Q2/Q5 (2026-11-11)
            "offered_mib_s": r.get("offered_mib_s"),
            "latency_basis": r.get("latency_basis"),
            "p99_send_us": None if no_p99 else r.get("p99_send_us"),
            "p99_sched_us": None if no_p99 else r.get("p99_sched_us"),
            "sched_late_pct": r.get("sched_late_pct"),
            "offered_actual_mib_s": r.get("offered_actual_mib_s"),
            "sched_lag_p50_us": r.get("sched_lag_p50_us"),
            "sched_lag_p99_us": r.get("sched_lag_p99_us"),
            "sched_expected": r.get("sched_expected"),
            "sched_sent": r.get("sched_sent"),
            "sched_received": r.get("sched_received"),
            "sched_unreceived_at_cutoff": r.get("sched_unreceived_at_cutoff"),
            "sched_phantom_streams": r.get("sched_phantom_streams"),
            "sched_stamp_fallback": r.get("sched_stamp_fallback"),
            "cutoff_hit": r.get("cutoff_hit"),
            "sched_streams_short": r.get("sched_streams_short"),
            "sched_late_registered": r.get("sched_late_registered"),
            "catchup_s": r.get("catchup_s"),
            "catchup_cpu_pct_of_wall": r.get("catchup_cpu_pct_of_wall"),
            "closed_loop": closed_loop_info(out, p.parent.name),
        })

    for w in windows:
        if not harness_commit_ok(w.get("harness_commit")):
            w["valid"] = False  # unknown / empty / dirty: never counts
        elif enforce_marker and not w["marker_ok"]:
            w["valid"] = False  # pre-Q4 or unstamped window: never counts
    valid = [w for w in windows if w["valid"]]
    floor, floor_valid = lowload_floor(out)
    if floor:
        floor_commit_ok = harness_commit_ok(floor.get("harness_commit"))
        floor_marker_ok = floor.get("validity_marker") == REQUIRED_VALIDITY_MARKER
        if not floor_commit_ok or (enforce_marker and not floor_marker_ok):
            floor_valid = False  # dirty / unknown / empty, or a pre-Q4 floor: never counts
    floor_txt = (f"lowload-floor@{floor.get('offered_mib_s')}MiB/s p99={floor.get('p99_us')}µs "
                 f"(send {floor.get('p99_send_us')}, valid={floor_valid})") if floor else "lowload-floor=n/a"
    print(f"absolute windows: {len(windows)} total, {len(valid)} valid (need 5–8)")
    print(f"low-load end-to-end {floor_txt} (Q5: reported, never subtracted; accept p99 <= "
          f"{FLOOR_P99_MAX_US:.0f} µs; understates generator delay at {GATE_RATE_MIB_S:.0f} MiB/s)")
    for w in windows:
        mib_s = w['mib_s']
        mib_txt = f"{mib_s:.3f}" if isinstance(mib_s, (int, float)) else "n/a"
        p99_txt = (f"n/a (shortfall {w['shortfall']})" if (w['shortfall'] or 0) > 0
                   else ("n/a (no slot accounting)" if w['p99_us'] is None else w['p99_us']))
        print(f"  {w['tag']}: valid={w['valid']} mib_s={mib_txt} p50={w['p50_us']} p99={p99_txt} "
              f"n={w['samples']} self_loaded={w['self_loaded']} stalls={w['credit_stalls']} "
              f"stall_flag={w['stall_flag']} err={w['errors']} group_open={w.get('group_open_refusals')} "
              f"prod_tx_retry={w['producer_transport_retries']} cons_conn_retry={w['consumer_connect_retries']} "
              f"refused_per_ip={w['refused_per_ip']} drain_to={w['drain_timed_out']} "
              f"open_after={w['open_sessions_after_drain']} max_per_ip={w.get('max_connections_per_ip')} "
              f"load={w['load1_pre']}→{w['load1_post']}")
        cl = w.get("closed_loop") or {}
        print(f"    offered={w.get('offered_mib_s')} offered_actual={w.get('offered_actual_mib_s')} "
              f"achieved={mib_txt} basis={w.get('latency_basis')} "
              f"p99_sched={w.get('p99_sched_us')} p99_send={w.get('p99_send_us')} late%={w.get('sched_late_pct')} "
              f"lag_p50={w.get('sched_lag_p50_us')} lag_p99={w.get('sched_lag_p99_us')} "
              f"slots exp/sent/recv={w.get('sched_expected')}/{w.get('sched_sent')}/{w.get('sched_received')} "
              f"unrecv={w.get('sched_unreceived_at_cutoff')} streams_short={w.get('sched_streams_short')} "
              f"cutoff_hit={w.get('cutoff_hit')} catchup={w.get('catchup_s')}s@{w.get('catchup_cpu_pct_of_wall')}% "
              f"late_registered={w.get('sched_late_registered')} "
              f"harness={w.get('harness_commit')}/{w.get('validity_marker')} | "
              f"closed-loop(info) mib_s={cl.get('mib_s')} p99={cl.get('p99_us')} cpu={cl.get('client_cpu_pct_of_wall')} | "
              f"{floor_txt}")
        if not harness_commit_ok(w.get("harness_commit")):
            print(f"  INVALID {w['tag']}: {harness_commit_reason(w['tag'], w.get('harness_commit'))}")
        elif enforce_marker and not w["marker_ok"]:
            print(f"  INVALID {w['tag']}: harness_commit={w.get('harness_commit')} "
                  f"validity_marker={w.get('validity_marker')} (need {REQUIRED_VALIDITY_MARKER})")
        if (w['transport_retry_flag'] or w['refused_per_ip'] or w['drain_timed_out']
                or w['open_sessions_after_drain'] != 0 or w.get('setup_fail') or w.get('noisy')):
            print(f"  INVALID {w['tag']}: tx_retry={w['transport_retry_flag']} "
                  f"refused_per_ip={w['refused_per_ip']} drain_timed_out={w['drain_timed_out']} "
                  f"open_sessions_after_drain={w['open_sessions_after_drain']} "
                  f"setup_fail={w.get('setup_fail')} err={w.get('errors')} "
                  f"group_open={w.get('group_open_refusals')} noisy={w.get('noisy')} "
                  f"foreign_cpu={w.get('foreign_cpu_avg_cores')}")

    verdict = "FAIL"
    reason = []
    for w in windows:
        cr = harness_commit_reason(w["tag"], w.get("harness_commit"))
        if cr:
            reason.append(cr)
    if len(valid) < 5:
        reason.append(f"only {len(valid)} valid windows (need ≥5)")
    else:
        # use first 8 valid if more
        use = valid[:8]
        mibs = sorted(float(w["mib_s"]) for w in use if w["mib_s"] is not None)
        p99s = sorted(float(w["p99_us"]) for w in use if w["p99_us"] is not None)
        if len(mibs) < len(use) or len(p99s) < len(use):
            reason.append("valid window missing mib_s/p99_us")
        if not mibs or not p99s:
            reason.append("no numeric mib_s/p99 among valid windows")
            med_mib = worst_mib = 0.0
            med_p99 = worst_p99 = float("inf")
        else:
            med_mib = mibs[len(mibs) // 2]
            med_p99 = p99s[len(p99s) // 2]
            worst_mib = min(mibs)
            worst_p99 = max(p99s)
        print(f"median mib_s={med_mib:.3f} (need ≥100)  median p99_us={med_p99:.1f} (need <1000)")
        print(f"worst mib_s={worst_mib:.3f} (floor 80)  worst p99_us={worst_p99:.1f} (ceil 1500)")
        ok_med = med_mib >= 100.0 and med_p99 < 1000.0
        ok_floor = worst_mib >= 80.0 and worst_p99 <= 1500.0
        if not ok_med:
            reason.append("median missed §13 (≥100 MiB/s and p99<1ms)")
        if not ok_floor:
            reason.append("a window below 80 MiB/s or p99>1.5ms")
        # inceptor Q1 + Architecture items 1/2: paced at exactly the disclosed rate, scheduled-basis
        # p99, every in-window slot received (nothing lost or hung at the drain cutoff).
        for w in use:
            off = w.get("offered_mib_s")
            if w.get("latency_basis") != "scheduled" or not is_gate_rate(off):
                reason.append(f"{w['tag']}: not a paced gate window at {GATE_RATE_MIB_S:.0f} MiB/s "
                              f"(offered={off}, basis={w.get('latency_basis')})")
            oa = w.get("offered_actual_mib_s")
            if not offered_actual_ok(oa):
                reason.append(f"{w['tag']}: offered_actual_mib_s={oa} not within "
                              f"{OFFERED_ACTUAL_TOL_PCT}% of {GATE_RATE_MIB_S:.0f}")
            exp, short = w.get("sched_expected"), w.get("shortfall")
            if short is None:
                reason.append(f"{w['tag']}: no slot accounting (sched_expected={exp})")
            elif short > 0:
                reason.append(f"{w['tag']}: slot shortfall {short} of {exp} "
                              f"(unreceived at cutoff {w.get('sched_unreceived_at_cutoff')}, "
                              f"phantom streams {w.get('sched_phantom_streams')})")
            ss = w.get("sched_streams_short")
            if not isinstance(ss, int):
                reason.append(f"{w['tag']}: no per-stream accounting (sched_streams_short={ss})")
            elif ss > 0:
                reason.append(f"{w['tag']}: {ss} streams short of their in-window slots")
            cs, cp, lim = w.get("catchup_s"), w.get("catchup_cpu_pct_of_wall"), w.get("gen_sat_limit_pct")
            if (isinstance(cs, (int, float)) and cs >= 1.0 and isinstance(cp, (int, float))
                    and isinstance(lim, (int, float)) and cp > lim):
                reason.append(f"{w['tag']}: generator saturated during catch-up ({cp:.0f}% > {lim:.0f}% for {cs:.1f}s)")
            if w.get("cutoff_hit"):
                reason.append(f"{w['tag']}: drain cutoff hit (hung streams)")
            if (w.get("sched_stamp_fallback") or 0) > 0:
                reason.append(f"{w['tag']}: {w.get('sched_stamp_fallback')} scheduled stamps unparsable")
        # Architecture item 5: the low-load floor acceptance, fixed in advance.
        if not floor or not floor_valid:
            reason.append("no valid low-load end-to-end floor leg")
        elif not isinstance(floor.get("p99_us"), (int, float)) or floor["p99_us"] > FLOOR_P99_MAX_US:
            reason.append(f"low-load floor p99={floor.get('p99_us')} µs > {FLOOR_P99_MAX_US:.0f} µs: "
                          "harness cannot resolve the 1 ms gate")
        # hard zeros across valid windows (retries/refused/open already exclude from valid)
        for w in use:
            if w.get("transport_retry_flag"):
                reason.append(f"{w['tag']}: transport_retry_flag")
            if w.get("setup_fail"):
                reason.append(f"{w['tag']}: setup_fail err={w.get('errors')} group_open={w.get('group_open_refusals')}")
            if w.get("noisy"):
                reason.append(f"{w['tag']}: NOISY foreign_cpu={w.get('foreign_cpu_avg_cores')}")
            if w.get("refused_per_ip"):
                reason.append(f"{w['tag']}: refused_per_ip={w['refused_per_ip']}")
            if w.get("drain_timed_out"):
                reason.append(f"{w['tag']}: drain_timed_out")
            if w.get("open_sessions_after_drain") not in (0, None):
                reason.append(f"{w['tag']}: open_sessions_after_drain={w.get('open_sessions_after_drain')}")
            hz = w.get("hard_zeros") or {}
            for k, expect_zero in (
                ("sjws_swarm_stream_node_refusals_total", True),
                ("sjws_swarm_stream_session_admit_refusals_total", True),
                ("sjws_swarm_stream_budget_underflows_total", True),
                ("sjws_swarm_stream_reserve_underflows_total", True),
                ("sjws_connections_refused_per_ip_total", True),
                ("sjws_swarm_stream_open_sessions", True),
            ):
                v = hz.get(k)
                if v is None:
                    reason.append(f"{w['tag']}: missing {k}")
                elif expect_zero and v != 0:
                    reason.append(f"{w['tag']}: {k}={v} (need 0)")
            charged = hz.get("sjws_swarm_stream_charged_bytes")
            # after drain charged should be ~0; allow small residual during scrape race
            if charged is not None and charged > 0:
                print(f"  note {w['tag']}: charged_bytes={charged} at scrape (report)")
        if ok_med and ok_floor and not reason:
            verdict = "PASS"

    # Info ABBA (credit on vs off) — never fails the PR
    info_on, info_off = [], []
    for p in sorted(out.glob("info-b*/result.json")):
        r = load(p)
        if not r or r.get("mib_s") is None:
            continue
        name = p.parent.name
        if name.endswith(("A1", "A2")):
            info_on.append(r["mib_s"])
        elif name.endswith(("B1", "B2")):
            info_off.append(r["mib_s"])
    if info_on and info_off:
        # paired by block order roughly: geomean(on/off) — here compare means of ratios per available pairs
        n = min(len(info_on), len(info_off))
        ratios = [info_on[i] / info_off[i] if info_off[i] > 0 else float("inf") for i in range(n)]
        # sign-flip: count ratios >1 vs <1 for "on slower than off" framing as head/base style
        # Treat credit-on as "head" cost: ratio = off/on so >1 means on is slower (worse throughput)
        cost = [info_off[i] / info_on[i] if info_on[i] > 0 else float("inf") for i in range(n)]
        flips = sum(1 for c in cost if c > 1)
        p = binom_sf(flips, n) if n else 1.0
        # geomean of on/off throughput (higher on is better); FAIL-line uses head/base >1.10 where head=on cost
        # Report geomean(off/on) as the "cost factor"
        if all(c > 0 and math.isfinite(c) for c in cost):
            geo = math.exp(sum(math.log(c) for c in cost) / n)
        else:
            geo = float("nan")
        print(f"info ABBA credit on vs off: n={n} geomean(off/on)={geo:.4f} signflip_p={p:.4f} "
              f"(flag if p<0.05 AND geo>1.10; info-only, cannot FAIL PR)")
        if p < 0.05 and geo > 1.10:
            print("INFO_FLAG: credit-on looks >10% slower than credit-off under ABBA rule — escalate to inceptor")

    # A/A
    aa = [load(p) for p in sorted(out.glob("aa-*/result.json"))]
    aa = [r for r in aa if r and r.get("mib_s") is not None]
    for i, r in enumerate(aa):
        bad = []
        if not is_gate_rate(r.get("offered_mib_s")) or r.get("latency_basis") != "scheduled":
            bad.append(f"not paced at {GATE_RATE_MIB_S:.0f} MiB/s "
                       f"(offered={r.get('offered_mib_s')}, basis={r.get('latency_basis')})")
        elif not offered_actual_ok(r.get("offered_actual_mib_s")):
            bad.append(f"offered_actual_mib_s={r.get('offered_actual_mib_s')} off {GATE_RATE_MIB_S:.0f}")
        if (shortfall(r) or 0) > 0:
            bad.append(f"slot shortfall {shortfall(r)}")
            r["p99_us"] = None
        cr = harness_commit_reason(f"A/A leg {i}", r.get("harness_commit"))
        if cr:
            bad.append(cr)
        elif enforce_marker and r.get("validity_marker") != REQUIRED_VALIDITY_MARKER:
            bad.append("pre-Q4 or unstamped A/A leg")
        for b_ in bad:
            reason.append(f"A/A leg {i}: {b_}")
        if bad and verdict == "PASS":
            verdict = "FAIL"
    if len(aa) >= 2:
        print(f"A/A noise floor: {aa[0]['mib_s']:.3f} vs {aa[1]['mib_s']:.3f} MiB/s; "
              f"p99 {aa[0].get('p99_us')} vs {aa[1].get('p99_us')} µs")

    # Addition 1 binary
    b = load(out / "add1-binary" / "result.json")
    if b:
        print(f"Addition1 binary (report-only): mib_s={b.get('mib_s')} p99_us={b.get('p99_us')} "
              f"ttft={b.get('ttft')} inter={b.get('inter_token')}")


    # Architecture 2026-10-30 15:13: group-count sweep is report-only (never fails the verdict).
    sweep_rows = []
    for p in sorted(out.glob("sweep-*/result.json")):
        r = load(p)
        if not r:
            continue
        sweep_rows.append({
            "tag": p.parent.name,
            "groups": r.get("groups"),
            "streams": r.get("streams"),
            "mib_s": r.get("mib_s"),
            "p99_us": r.get("p99_us"),
            "writer_count": r.get("writer_count"),
            "heap_after_gc": r.get("heap_after_gc"),
            "zallocation_stalls": r.get("zallocation_stalls"),
            "oom": r.get("oom") or r.get("server_oom"),
            "heap_flags": r.get("heap_flags"),
        })
    if sweep_rows:
        print("Group sweep (info-only, Architecture 2026-10-30 15:13):")
        for row in sweep_rows:
            print(f"  {row['tag']}: groups={row['groups']} mib_s={row['mib_s']} p99_us={row['p99_us']} "
                  f"writers={row['writer_count']} zstall={row['zallocation_stalls']} oom={row['oom']}")

    if assume_q4:
        # Tests only: exercises the rule on pre-Q4 fixtures, never a countable verdict.
        verdict = f"TEST_ONLY_{verdict}"
    elif not Q4_LANDED:
        # Sequencing (Architecture item 7): report what it would be, but nothing counts yet.
        reason.insert(0, f"pre-Q4 harness, would be {verdict}")
        verdict = "NOT_COUNTABLE"
    print(f"VERDICT: {verdict}" + (f" ({'; '.join(reason)})" if reason else ""))
    (out / "agg-summary.json").write_text(json.dumps({
        "verdict": verdict, "reason": reason, "windows": windows, "valid_n": len(valid),
        "sweep_info_only": sweep_rows,
    }, indent=2) + "\n")


if __name__ == "__main__":
    main()
