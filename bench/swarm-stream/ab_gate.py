#!/usr/bin/env python3
"""Old-vs-new A/B merge gate: FAIL if p<0.05 AND p99_new/p99_old > 1.10.

p is a two-sided Mann-Whitney U on reconstructed scheduled-latency samples
from each result's chunk_latency_sched hist (capped). p99 is the reported
p99_us (scheduled basis when present, else p99_send_us).
"""
from __future__ import annotations

import json
import math
import sys
from pathlib import Path


def _samples_from_hist(hist, cap=20000):
    """hist is [[bucket_us_or_idx, count], ...] or LatBag sparse [[idx, n], ...]."""
    if not hist:
        return []
    pairs = []
    total = 0
    for item in hist:
        if not item or len(item) < 2:
            continue
        x, n = item[0], int(item[1])
        if n <= 0:
            continue
        pairs.append((float(x), n))
        total += n
    if total == 0:
        return []
    # downsample proportionally if huge
    out = []
    if total > cap:
        scale = cap / total
        acc = 0.0
        for x, n in pairs:
            acc += n * scale
            k = int(acc)
            acc -= k
            out.extend([x] * k)
        return out
    for x, n in pairs:
        out.extend([x] * n)
    return out


def mann_whitney_u(a, b):
    """Two-sided MWU p-value (normal approx with tie correction). Returns (U, p)."""
    n1, n2 = len(a), len(b)
    if n1 < 2 or n2 < 2:
        return None, None
    # ranks
    combined = [(v, 0) for v in a] + [(v, 1) for v in b]
    combined.sort(key=lambda t: t[0])
    ranks = [0.0] * len(combined)
    i = 0
    tie_term = 0.0  # sum (t^3-t)
    while i < len(combined):
        j = i
        while j < len(combined) and combined[j][0] == combined[i][0]:
            j += 1
        t = j - i
        avg = (i + 1 + j) / 2.0  # 1-based ranks
        for k in range(i, j):
            ranks[k] = avg
        if t > 1:
            tie_term += t * t * t - t
        i = j
    r1 = sum(r for r, (_, g) in zip(ranks, combined) if g == 0)
    U1 = r1 - n1 * (n1 + 1) / 2.0
    U = min(U1, n1 * n2 - U1)
    mu = n1 * n2 / 2.0
    n = n1 + n2
    # variance with ties
    var = n1 * n2 * (n + 1) / 12.0
    if n > 1 and tie_term:
        var -= n1 * n2 * tie_term / (12.0 * n * (n - 1))
    if var <= 0:
        return U, 1.0
    # continuity correction
    z = (U - mu + 0.5) / math.sqrt(var)
    # two-sided from normal
    p = 2.0 * (1.0 - 0.5 * (1.0 + math.erf(abs(z) / math.sqrt(2.0))))
    p = min(1.0, max(0.0, p))
    return U, p


def p99_of(r: dict):
    v = r.get("p99_us")
    if v is None:
        v = r.get("p99_sched_us")
    if v is None:
        v = r.get("p99_send_us")
    try:
        return float(v) if v is not None else None
    except (TypeError, ValueError):
        return None


def hist_of(r: dict):
    cl = r.get("chunk_latency_sched") or r.get("chunk_latency") or {}
    return cl.get("hist") or r.get("lat_hist_sched") or []


def evaluate(new_r: dict, old_r: dict) -> dict:
    pn, po = p99_of(new_r), p99_of(old_r)
    ratio = (pn / po) if (pn and po and po > 0) else None
    sa = _samples_from_hist(hist_of(new_r))
    sb = _samples_from_hist(hist_of(old_r))
    U, p = mann_whitney_u(sa, sb)
    # FAIL only if significant AND new is more than 10% slower
    fail = bool(p is not None and p < 0.05 and ratio is not None and ratio > 1.10)
    if ratio is None or pn is None or po is None:
        verdict = "INCOMPLETE"
    elif fail:
        verdict = "FAIL"
    else:
        verdict = "PASS"
    return {
        "verdict": verdict,
        "p": p,
        "ratio": ratio,
        "p99_new": pn,
        "p99_old": po,
        "n_new": len(sa),
        "n_old": len(sb),
        "U": U,
        "fail_rule": "p<0.05 AND ratio>1.10",
        "http_503_new": (new_r or {}).get("http_503_responses"),
        "http_503_old": (old_r or {}).get("http_503_responses"),
    }


def main(argv):
    if len(argv) < 3:
        print("usage: ab_gate.py NEW.json OLD.json", file=sys.stderr)
        return 2
    new_r = json.loads(Path(argv[1]).read_text())
    old_r = json.loads(Path(argv[2]).read_text())
    ev = evaluate(new_r, old_r)
    print(json.dumps(ev))
    return 0 if ev["verdict"] == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
