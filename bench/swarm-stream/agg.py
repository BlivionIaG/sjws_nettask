#!/usr/bin/env python3
"""Aggregate S2c PR 8 stream-gate results. Absolute §13 is the hard gate; ABBA is info-only."""
from __future__ import annotations

import json
import math
import sys
from pathlib import Path


def binom_sf(k, n, p=0.5):
    from math import comb
    return sum(comb(n, i) * (p ** i) * ((1 - p) ** (n - i)) for i in range(k, n + 1))


def load(p: Path):
    try:
        return json.loads(p.read_text())
    except Exception:
        return None


def main():
    out = Path(sys.argv[1])
    abs_runs = sorted(out.glob("abs-*/result.json"))
    windows = []
    for p in abs_runs:
        r = load(p)
        if not r:
            continue
        n = r.get("chunk_samples") or 0
        p99 = r.get("p99_us")
        pre = r.get("load1_pre", 99)
        # Architecture 2026-10-30 + inceptor 2026-10-30 18:46:
        # self_loaded if load1_post>=4 OR gen CPU > 80% of its cores. Always derive the limit
        # from 80 × gen_cores — never trust a recorded gen_sat_limit_pct override (Codex P2 #459).
        gen_cores = int(r.get("gen_cores") or 2)
        sat_lim = 80.0 * gen_cores
        cpu = r.get("client_cpu_pct_of_wall")
        try:
            cpu_f = float(cpu) if cpu is not None else float("nan")
        except (TypeError, ValueError):
            cpu_f = float("nan")
        load1_post = r.get("load1_post")
        try:
            load1_post_f = float(load1_post) if load1_post is not None else 0.0
        except (TypeError, ValueError):
            load1_post_f = 0.0
        gen_sat = (cpu_f == cpu_f) and cpu_f > sat_lim  # nan-safe
        self_loaded = bool(r.get("self_loaded")) or gen_sat or load1_post_f >= 4.0
        # Any retry or refused connection ⇒ INVALID (inceptor+Architecture; stricter wins).
        ptr = int(r.get("producer_transport_retries") or 0)
        ccr = int(r.get("consumer_connect_retries") or 0)
        refused = int(r.get("refused_per_ip") or 0)
        hz = r.get("hard_zeros") or {}
        if refused == 0:
            try:
                refused = int(float(hz.get("sjws_connections_refused_per_ip_total") or 0))
            except (TypeError, ValueError):
                refused = 0
        open_after = r.get("open_sessions_after_drain")
        if open_after is None:
            try:
                open_after = hz.get("sjws_swarm_stream_open_sessions")
            except Exception:
                open_after = None
        try:
            open_after_n = int(float(open_after)) if open_after is not None else -1
        except (TypeError, ValueError):
            open_after_n = -1
        drain_timed_out = bool(r.get("drain_timed_out"))
        transport_retry_flag = bool(r.get("transport_retry_flag")) or ptr > 0 or ccr > 0
        refused_flag = refused > 0
        open_flag = open_after_n != 0  # must be exactly 0 after drain
        # valid only when all hard conditions hold
        valid = (n >= 10000 and p99 is not None and pre < 4.0 and not self_loaded
                 and not transport_retry_flag and not refused_flag
                 and not drain_timed_out and not open_flag)
        windows.append({
            "tag": p.parent.name,
            "valid": valid,
            "mib_s": r.get("mib_s"),
            "p50_us": r.get("p50_us"),
            "p90_us": r.get("p90_us"),
            "p99_us": p99,
            "samples": n,
            "self_loaded": self_loaded,
            "gen_cores": gen_cores,
            "gen_sat_limit_pct": sat_lim,
            "client_cpu_pct_of_wall": cpu_f,
            "stall_flag": r.get("stall_flag"),
            "credit_stalls": r.get("credit_stalls"),
            "hard_zeros": r.get("hard_zeros"),
            "errors": r.get("error_count"),
            "producer_transport_retries": ptr,
            "consumer_connect_retries": ccr,
            "transport_retry_flag": transport_retry_flag,
            "refused_per_ip": refused,
            "drain_timed_out": drain_timed_out,
            "open_sessions_after_drain": open_after_n,
            "max_connections_per_ip": r.get("max_connections_per_ip"),
            "load1_pre": pre,
            "load1_post": r.get("load1_post"),
        })

    valid = [w for w in windows if w["valid"]]
    print(f"absolute windows: {len(windows)} total, {len(valid)} valid (need 5–8)")
    for w in windows:
        print(f"  {w['tag']}: valid={w['valid']} mib_s={w['mib_s']:.3f} p50={w['p50_us']} p99={w['p99_us']} "
              f"n={w['samples']} self_loaded={w['self_loaded']} stalls={w['credit_stalls']} "
              f"stall_flag={w['stall_flag']} err={w['errors']} "
              f"prod_tx_retry={w['producer_transport_retries']} cons_conn_retry={w['consumer_connect_retries']} "
              f"refused_per_ip={w['refused_per_ip']} drain_to={w['drain_timed_out']} "
              f"open_after={w['open_sessions_after_drain']} max_per_ip={w.get('max_connections_per_ip')} "
              f"load={w['load1_pre']}→{w['load1_post']}")
        if w['transport_retry_flag'] or w['refused_per_ip'] or w['drain_timed_out'] or w['open_sessions_after_drain'] != 0:
            print(f"  INVALID {w['tag']}: tx_retry={w['transport_retry_flag']} "
                  f"refused_per_ip={w['refused_per_ip']} drain_timed_out={w['drain_timed_out']} "
                  f"open_sessions_after_drain={w['open_sessions_after_drain']}")

    verdict = "FAIL"
    reason = []
    if len(valid) < 5:
        reason.append(f"only {len(valid)} valid windows (need ≥5)")
    else:
        # use first 8 valid if more
        use = valid[:8]
        mibs = sorted(w["mib_s"] for w in use)
        p99s = sorted(w["p99_us"] for w in use)
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
        # hard zeros across valid windows (retries/refused/open already exclude from valid)
        for w in use:
            if w.get("transport_retry_flag"):
                reason.append(f"{w['tag']}: transport_retry_flag")
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

    print(f"VERDICT: {verdict}" + (f" ({'; '.join(reason)})" if reason else ""))
    (out / "agg-summary.json").write_text(json.dumps({
        "verdict": verdict, "reason": reason, "windows": windows, "valid_n": len(valid),
        "sweep_info_only": sweep_rows,
    }, indent=2) + "\n")


if __name__ == "__main__":
    main()
