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
        # Architecture 2026-10-30: self_loaded (incl. generator-CPU sat) does not count as a pass
        self_loaded = bool(r.get("self_loaded"))
        valid = n >= 10000 and p99 is not None and pre < 4.0 and not self_loaded
        windows.append({
            "tag": p.parent.name,
            "valid": valid,
            "mib_s": r.get("mib_s"),
            "p50_us": r.get("p50_us"),
            "p90_us": r.get("p90_us"),
            "p99_us": p99,
            "samples": n,
            "self_loaded": r.get("self_loaded"),
            "stall_flag": r.get("stall_flag"),
            "credit_stalls": r.get("credit_stalls"),
            "hard_zeros": r.get("hard_zeros"),
            "errors": r.get("error_count"),
            "load1_pre": pre,
            "load1_post": r.get("load1_post"),
        })

    valid = [w for w in windows if w["valid"]]
    print(f"absolute windows: {len(windows)} total, {len(valid)} valid (need 5–8)")
    for w in windows:
        print(f"  {w['tag']}: valid={w['valid']} mib_s={w['mib_s']:.3f} p50={w['p50_us']} p99={w['p99_us']} "
              f"n={w['samples']} self_loaded={w['self_loaded']} stalls={w['credit_stalls']} "
              f"stall_flag={w['stall_flag']} err={w['errors']} load={w['load1_pre']}→{w['load1_post']}")

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
        # hard zeros across valid windows
        for w in use:
            hz = w.get("hard_zeros") or {}
            for k, expect_zero in (
                ("sjws_swarm_stream_node_refusals_total", True),
                ("sjws_swarm_stream_session_admit_refusals_total", True),
                ("sjws_swarm_stream_budget_underflows_total", True),
                ("sjws_swarm_stream_reserve_underflows_total", True),
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

    print(f"VERDICT: {verdict}" + (f" ({'; '.join(reason)})" if reason else ""))
    (out / "agg-summary.json").write_text(json.dumps({
        "verdict": verdict, "reason": reason, "windows": windows, "valid_n": len(valid),
    }, indent=2) + "\n")


if __name__ == "__main__":
    main()
