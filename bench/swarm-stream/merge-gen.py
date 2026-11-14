#!/usr/bin/env python3
"""Merge several StreamLoad result.json files from a mode-B split (GEN_PROCS>1).

Sums counters and bytes, merges LatBag sparse histograms and recomputes p50/p90/p99
from the high edge (same as StreamLoad.LatBag.upper), sums generator CPU, and takes
the last process's post-drain server counters. Sets gen_cores to the combined width
(default 3) and recomputes self_loaded: any process > 80% of its core, or sum > 80% × gen_cores.

Usage: merge-gen.py PART.json... -o OUT.json [--gen-cores 3] [--secs 20]
"""
from __future__ import annotations

import argparse
import json
import math
import sys
from pathlib import Path

SUB_BITS = 6
SUB = 1 << SUB_BITS
BUCKETS = 64 * SUB


def upper(b: int) -> float:
    exp, sub = divmod(b, SUB)
    if exp < SUB_BITS:
        return float(max(1, b + 1))
    base = 1 << exp
    width = max(1, base >> SUB_BITS)
    return base + (sub + 1) * width


def merge_hist(hists: list) -> list:
    acc = {}
    for h in hists:
        if not h:
            continue
        for pair in h:
            if not pair:
                continue
            b, c = int(pair[0]), int(pair[1])
            acc[b] = acc.get(b, 0) + c
    return [[b, acc[b]] for b in sorted(acc)]


def pct_from_hist(hist: list, p: float):
    n = sum(c for _, c in hist)
    if n == 0:
        return None
    need = max(1, int(math.ceil(p * n)))
    seen = 0
    last = 0
    for b, c in hist:
        seen += c
        last = b
        if seen >= need:
            return upper(b)
    return upper(last) if hist else None


def sum_num(parts, *keys, default=0):
    s = 0
    found = False
    for r in parts:
        for k in keys:
            if k in r and r[k] is not None:
                s += r[k]
                found = True
                break
    return s if found else default


def max_num(parts, *keys, default=None):
    vs = []
    for r in parts:
        for k in keys:
            if k in r and r[k] is not None:
                vs.append(r[k])
                break
    return max(vs) if vs else default


def or_bool(parts, *keys):
    for r in parts:
        for k in keys:
            if r.get(k):
                return True
    return False


def first_present(parts, key, default=None):
    for r in parts:
        if key in r and r[key] is not None:
            return r[key]
    return default


def nest_hist(parts, *path):
    out = []
    for r in parts:
        cur = r
        ok = True
        for k in path:
            if not isinstance(cur, dict) or k not in cur:
                ok = False
                break
            cur = cur[k]
        if ok:
            out.append(cur)
    return merge_hist(out)


def merge(parts: list[dict], gen_cores: int, secs: float | None) -> dict:
    if not parts:
        raise SystemExit("merge-gen: no input results")
    last = parts[-1]
    out = dict(last)  # start with last so counters/hard_zeros/server fields default to it

    secs = secs if secs and secs > 0 else float(first_present(parts, "elapsed_s", 20) or 20)

    out["bytes"] = sum_num(parts, "bytes")
    out["requests"] = sum_num(parts, "requests")
    out["chunk_samples"] = int(sum_num(parts, "chunk_samples"))
    out["error_count"] = int(sum_num(parts, "error_count"))
    out["teardown_error_count"] = int(sum_num(parts, "teardown_error_count"))
    out["credit_stalls"] = int(sum_num(parts, "credit_stalls"))
    out["producer_transport_retries"] = int(sum_num(parts, "producer_transport_retries"))
    out["consumer_connect_retries"] = int(sum_num(parts, "consumer_connect_retries"))
    out["group_open_refusals"] = int(sum_num(parts, "group_open_refusals"))
    for k in (
        "http_503_responses", "http_503_claim_responses", "http_503_stream_responses",
        "http_503_budget_exhausted", "http_503_claim_giveups", "http_503_stream_giveups",
    ):
        out[k] = int(sum_num(parts, k))
    out["sched_sends"] = int(sum_num(parts, "sched_sends"))
    out["sched_expected"] = int(sum_num(parts, "sched_expected"))
    out["sched_sent"] = int(sum_num(parts, "sched_sent"))
    out["sched_received"] = int(sum_num(parts, "sched_received"))
    out["sched_unreceived_at_cutoff"] = int(sum_num(parts, "sched_unreceived_at_cutoff"))
    out["sched_phantom_streams"] = int(sum_num(parts, "sched_phantom_streams"))
    out["sched_inflight_at_end"] = int(sum_num(parts, "sched_inflight_at_end"))
    out["sched_stamp_fallback"] = int(sum_num(parts, "sched_stamp_fallback"))
    out["sched_streams_short"] = int(sum_num(parts, "sched_streams_short"))
    out["sched_late_registered"] = int(sum_num(parts, "sched_late_registered"))
    out["streams"] = int(sum_num(parts, "streams"))
    # groups: unique span from stream_to_group, else sum is wrong (they are ranges)
    maps = []
    for r in parts:
        m = r.get("stream_to_group") or []
        maps.extend(m)
    out["stream_to_group"] = maps
    if maps:
        out["groups"] = len(set(maps))
    else:
        out["groups"] = int(sum_num(parts, "groups"))
    out["streams_per_group_approx"] = (
        out["streams"] / out["groups"] if out.get("groups") else 0
    )

    out["client_cpu_sec"] = sum_num(parts, "client_cpu_sec")
    # Do NOT sum server CPU: every process samples the same server.
    out["wall_s"] = max_num(parts, "wall_s", default=last.get("wall_s"))
    # CPU % of wall: sum of per-process percentages (each is cpu_sec/wall*100 of the same window).
    out["client_cpu_pct_of_wall"] = sum_num(parts, "client_cpu_pct_of_wall")
    # Mode B (inceptor 2026-11-13 20:02): record per-process CPU and the sum. self_loaded
    # if ANY process is above 80% of its own pinned core, OR the sum is above 80% × gen_cores.
    per = []
    for r in parts:
        v = r.get("client_cpu_pct_of_wall")
        per.append(None if v is None else float(v))
    out["client_cpu_pct_per_proc"] = per
    out["gen_procs"] = len(parts)
    out["gen_cores"] = gen_cores
    sat = 80.0 * gen_cores
    out["gen_sat_limit_pct"] = sat
    cpu = out["client_cpu_pct_of_wall"]
    load1_post = first_present(parts, "load1_post", last.get("load1_post"))
    out["load1_post"] = load1_post
    out["load1_pre"] = first_present(parts, "load1_pre")
    out["load1_peak"] = max_num(parts, "load1_peak")
    any_proc = any(c is not None and c > 80.0 for c in per)
    gen_sat = bool((cpu is not None and cpu > sat) or any_proc)
    out["generator_saturated"] = gen_sat
    out["generator_saturated_any_proc"] = bool(any_proc)
    out["self_loaded"] = bool(gen_sat)  # CPU rule only; load1_post>=4 is not self_loaded

    out["mib_s"] = (out["bytes"] / (1024.0 * 1024.0)) / max(1e-6, secs)
    out["offered_mib_s"] = sum_num(parts, "offered_mib_s")
    chunk = first_present(parts, "chunk_bytes")  # may be absent
    # offered_actual from combined sched_sends
    # chunk bytes is typically 1024; recover from any part's offered_actual/sched_sends
    if out["sched_sends"] and secs:
        # prefer reconstructing from a part that has both
        cb = 1024
        for r in parts:
            ss, oa = r.get("sched_sends") or 0, r.get("offered_actual_mib_s")
            el = r.get("elapsed_s") or secs
            if ss and oa:
                cb = oa * 1048576.0 * el / ss
                break
        out["offered_actual_mib_s"] = out["sched_sends"] * cb / 1048576.0 / max(1e-6, secs)
    else:
        out["offered_actual_mib_s"] = sum_num(parts, "offered_actual_mib_s")

    out["cutoff_hit"] = or_bool(parts, "cutoff_hit")
    out["drain_timed_out"] = or_bool(parts, "drain_timed_out")
    out["transport_retry_flag"] = or_bool(parts, "transport_retry_flag")
    out["stall_flag"] = or_bool(parts, "stall_flag")
    out["noisy"] = or_bool(parts, "noisy")
    out["catchup_s"] = max_num(parts, "catchup_s", default=0) or 0
    # catch-up CPU: sum cpu seconds over max wall
    catchup_cpu_sec = 0.0
    for r in parts:
        cs, cw = r.get("catchup_s") or 0, r.get("catchup_cpu_pct_of_wall") or 0
        catchup_cpu_sec += (cw / 100.0) * cs
    out["catchup_cpu_pct_of_wall"] = (
        (catchup_cpu_sec / out["catchup_s"] * 100.0) if out["catchup_s"] else 0
    )
    ss = out["sched_sends"]
    late = sum(
        (r.get("sched_late_pct") or 0) / 100.0 * (r.get("sched_sends") or 0) for r in parts
    )
    out["sched_late_pct"] = (100.0 * late / ss) if ss else 0
    out["sched_lag_max_us"] = max_num(parts, "sched_lag_max_us", default=0) or 0

    # Histograms
    send_h = nest_hist(parts, "chunk_latency_send", "hist")
    sched_h = nest_hist(parts, "chunk_latency_sched", "hist")
    lag_h = nest_hist(parts, "sched_lag", "hist")
    lag_first = nest_hist(parts, "sched_lag", "first_s_hist")
    lag_rest = nest_hist(parts, "sched_lag", "rest_hist")

    def set_pcts(dest_parent, key, hist, extras=None):
        d = dict(dest_parent.get(key) or {})
        if hist is not None:
            d["hist"] = hist
            d["p50_us"] = pct_from_hist(hist, 0.50)
            if key != "sched_lag":
                d["p90_us"] = pct_from_hist(hist, 0.90)
            d["p99_us"] = pct_from_hist(hist, 0.99)
        if extras:
            d.update(extras)
        dest_parent[key] = d

    out["chunk_latency_send"] = out.get("chunk_latency_send") or {}
    set_pcts(out, "chunk_latency_send", send_h)
    out["chunk_latency_sched"] = out.get("chunk_latency_sched") or {}
    set_pcts(out, "chunk_latency_sched", sched_h)
    lag_extras = {
        "first_s_p99_us": pct_from_hist(lag_first, 0.99) if lag_first else first_present(parts, "sched_lag_first_s_p99_us"),
        "rest_p99_us": pct_from_hist(lag_rest, 0.99) if lag_rest else first_present(parts, "sched_lag_rest_p99_us"),
        "first_s_hist": lag_first,
        "rest_hist": lag_rest,
    }
    set_pcts(out, "sched_lag", lag_h, lag_extras)

    paced = (out.get("offered_mib_s") or 0) > 0
    gate = out["chunk_latency_sched"] if paced else out["chunk_latency_send"]
    out["p50_us"] = gate.get("p50_us")
    out["p90_us"] = gate.get("p90_us")
    out["p99_us"] = gate.get("p99_us")
    out["p99_send_us"] = out["chunk_latency_send"].get("p99_us")
    out["p99_sched_us"] = out["chunk_latency_sched"].get("p99_us")
    out["p50_send_us"] = out["chunk_latency_send"].get("p50_us")
    out["p50_sched_us"] = out["chunk_latency_sched"].get("p50_us")
    out["sched_lag_p50_us"] = (out.get("sched_lag") or {}).get("p50_us")
    out["sched_lag_p99_us"] = (out.get("sched_lag") or {}).get("p99_us")
    out["sched_lag_first_s_p99_us"] = (out.get("sched_lag") or {}).get("first_s_p99_us")
    out["sched_lag_rest_p99_us"] = (out.get("sched_lag") or {}).get("rest_p99_us")
    out["latency_basis"] = "scheduled" if paced else first_present(parts, "latency_basis", "send")

    # chunk_latency wrapper used by some readers
    cl = dict(out.get("chunk_latency") or {})
    cl["n"] = out["chunk_samples"]
    cl["basis"] = out["latency_basis"]
    cl["p50_us"] = out["p50_us"]
    cl["p90_us"] = out["p90_us"]
    cl["p99_us"] = out["p99_us"]
    cl["hist_sub_bits"] = SUB_BITS
    out["chunk_latency"] = cl

    stall_n = out["chunk_samples"]
    out["credit_stall_pct_of_chunks"] = (100.0 * out["credit_stalls"] / stall_n) if stall_n else 0
    out["stall_flag"] = out["credit_stall_pct_of_chunks"] > 1.0

    # Concatenate a few errors
    errs, terrs = [], []
    for r in parts:
        errs.extend(r.get("errors") or [])
        terrs.extend(r.get("teardown_errors") or [])
    out["errors"] = errs[:50]
    out["teardown_errors"] = terrs[:50]
    out["gen_procs"] = len(parts)

    # writer_count: last (one.sh amends later)
    # Warmup-to-measure drain (existing /metrics open_sessions gauge). Take the max
    # leftover / longest wait across generator processes.
    def _maxf(key):
        vs = [r.get(key) for r in parts if r.get(key) is not None]
        return max(vs) if vs else None
    out["warmup_drain_open_sessions_before"] = _maxf("warmup_drain_open_sessions_before")
    out["warmup_drain_open_sessions_after"] = _maxf("warmup_drain_open_sessions_after")
    out["warmup_drain_charged_bytes_before"] = _maxf("warmup_drain_charged_bytes_before")
    out["warmup_drain_charged_bytes_after"] = _maxf("warmup_drain_charged_bytes_after")
    out["warmup_drain_wait_ms"] = _maxf("warmup_drain_wait_ms")
    out["warmup_drain_timed_out"] = any(bool(r.get("warmup_drain_timed_out")) for r in parts)
    # first_admit: concatenate a few from each proc (measure, then warmup)
    fa, fw = [], []
    for r in parts:
        fa.extend(r.get("first_admit") or [])
        fw.extend(r.get("first_admit_warmup") or [])
    # #502 pacer split (WAKE / BEHIND / CAPWAIT), ack RTT and generator GC over the window.
    for k in ("pace_wake_n", "pace_behind_n", "pace_capwait_n", "gc_ms_window", "gc_count_window",
              "timer_slack_threads_set"):
        out[k] = sum_num(parts, k)
    out["pipeline_depth"] = first_present(parts, "pipeline_depth")
    for name, p99key, p50key in (
        ("pace_wake_hist", "pace_wake_p99_us", None),
        ("pace_behind_hist", "pace_behind_p99_us", None),
        ("pace_capwait_hist", "pace_capwait_p99_us", None),
        ("ack_rtt_hist", "ack_rtt_p99_us", "ack_rtt_p50_us"),
    ):
        h = merge_hist([r.get(name) for r in parts])
        out[name] = h
        out[p99key] = pct_from_hist(h, 0.99) if h else None
        if p50key:
            out[p50key] = pct_from_hist(h, 0.50) if h else None
    # #502 A/A spread: per-1s-block scheduled latency (merge by second, p99 per block).
    by_s = {}
    for r in parts:
        for ent in r.get("sched_per_s_hist") or []:
            by_s.setdefault(int(ent[0]), []).append(ent[1])
    per_s = [[sec, merge_hist(hs)] for sec, hs in sorted(by_s.items())]
    out["sched_per_s_hist"] = per_s
    out["sched_p99_per_s_us"] = [
        [sec, pct_from_hist(h, 0.99), sum(c for _, c in h)] for sec, h in per_s
    ]
    out["first_admit"] = fa[:40]
    out["first_admit_warmup"] = fw[:40]
    return out


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("inputs", nargs="+", type=Path)
    ap.add_argument("-o", "--out", required=True, type=Path)
    ap.add_argument("--gen-cores", type=int, default=3)
    ap.add_argument("--secs", type=float, default=0)
    args = ap.parse_args(argv)
    parts = []
    for pth in args.inputs:
        if not pth.is_file():
            raise SystemExit(f"merge-gen: missing {pth}")
        if pth.stat().st_size == 0:
            raise SystemExit(f"merge-gen: empty {pth} (generator wrote no result; refusing to merge or reuse a leftover)")
        try:
            parts.append(json.loads(pth.read_text()))
        except json.JSONDecodeError as e:
            raise SystemExit(f"merge-gen: {pth} is not valid JSON: {e}")
    out = merge(parts, args.gen_cores, args.secs or None)
    args.out.write_text(json.dumps(out, indent=2) + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
