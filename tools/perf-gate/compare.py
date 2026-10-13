#!/usr/bin/env python3
"""Compare base vs head wrk results (JSON lines from bench.sh); exit 1 on a regression.

A metric regresses when the median moved the wrong way by more than
max(threshold, noise), where noise = 2 * the larger relative spread (MAD/median) of
either side. Runs with errors fail too. Prints a Markdown table (for $GITHUB_STEP_SUMMARY).
"""
import argparse, json, statistics, sys

def load(path):
    with open(path) as f:
        rows = [json.loads(l) for l in f if l.strip()]
    if not rows:
        sys.exit(f"no results in {path}")
    return rows

def stats(rows, key):
    vals = [r[key] for r in rows]
    med = statistics.median(vals)
    mad = statistics.median(abs(v - med) for v in vals)
    return med, (mad / med if med else 0.0)

def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("base"); ap.add_argument("head")
    ap.add_argument("--title", default="Perf gate")
    ap.add_argument("--threshold", type=float, default=10.0, help="allowed throughput regression in percent")
    ap.add_argument("--p99-threshold", type=float, default=None,
                    help="allowed p99 regression in percent (default: 2x --threshold; tails are noisier)")
    a = ap.parse_args(argv)
    base, head = load(a.base), load(a.head)
    limits = {"rps": a.threshold, "p99_us": a.p99_threshold if a.p99_threshold is not None else 2 * a.threshold}
    metrics = [("Requests/s", "rps", +1, lambda v: f"{v:,.0f}"),
               ("p99 latency", "p99_us", -1, lambda v: f"{v/1000:.2f} ms")]
    out = [f"### {a.title} (median of {len(base)}/{len(head)} runs, thresholds rps {limits['rps']:g}% / p99 {limits['p99_us']:g}%)", "",
           "| Metric | master | PR | Change | Allowed | Result |", "|---|---|---|---|---|---|"]
    failed = False
    for label, key, good, fmt in metrics:
        bm, bn = stats(base, key); hm, hn = stats(head, key)
        change = (hm - bm) / bm * 100 if bm else 0.0
        regression = -change * good          # positive = worse
        allowed = max(limits[key], 2 * max(bn, hn) * 100)
        bad = regression > allowed
        failed |= bad
        out.append(f"| {label} | {fmt(bm)} | {fmt(hm)} | {change:+.1f}% | {allowed:.1f}% | "
                   f"{'❌ regression' if bad else '✅'} |")
    errs = sum(r["errors"] for r in head)
    if errs:
        failed = True
        out.append(f"\n❌ PR build had {errs} socket/HTTP errors during the runs.")
    print("\n".join(out))
    return 1 if failed else 0

if __name__ == "__main__":
    sys.exit(main())
