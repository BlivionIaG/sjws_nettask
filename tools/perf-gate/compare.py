#!/usr/bin/env python3
"""Compare base vs head wrk / wrk2 / RSS results (JSON lines from bench.sh); exit 1 on a regression.

A metric regresses when the median moved the wrong way by more than
max(threshold, noise), where noise = 2 * the larger relative spread (MAD/median) of
either side. Runs with errors fail too. Prints a Markdown table (for $GITHUB_STEP_SUMMARY).

wrk2 p99 uses the same relative-to-base rule as wrk p99 (default 2x the throughput
threshold). It is not an absolute latency floor.

RSS is always recorded when bench.sh can read the server pid. An absolute or
relative RSS budget is measurement-pending until PERF_RSS_THRESHOLD /
--rss-threshold is set from a real run. When the flag is omitted, RSS is printed
and never fails the gate on magnitude; a missing RSS row still fails (harness error).
"""
import argparse, json, statistics, sys


def load_all(path):
    with open(path) as f:
        rows = [json.loads(l) for l in f if l.strip()]
    if not rows:
        sys.exit(f"no results in {path}")
    return rows


def kind_of(row):
    return row.get("kind", "wrk")


def select(rows, kind):
    return [r for r in rows if kind_of(r) == kind]


def stats(rows, key):
    vals = [r[key] for r in rows]
    med = statistics.median(vals)
    mad = statistics.median(abs(v - med) for v in vals)
    return med, (mad / med if med else 0.0)


def rss_value(rows):
    """Prefer post-wrk2 RSS, then post-wrk, then warmup, else any rss row."""
    by_phase = {}
    for row in rows:
        by_phase.setdefault(row.get("phase", "rss"), []).append(row["rss_kb"])
    for phase in ("after-wrk2", "after-wrk", "warmup", "rss"):
        if phase in by_phase:
            return statistics.median(by_phase[phase]), phase
    if not rows:
        return None, None
    return statistics.median(r["rss_kb"] for r in rows), "rss"


def append_metric(out, label, base_rows, head_rows, key, good, fmt, threshold):
    bm, bn = stats(base_rows, key)
    hm, hn = stats(head_rows, key)
    change = (hm - bm) / bm * 100 if bm else 0.0
    regression = -change * good
    allowed = max(threshold, 2 * max(bn, hn) * 100)
    bad = regression > allowed
    out.append(
        f"| {label} | {fmt(bm)} | {fmt(hm)} | {change:+.1f}% | {allowed:.1f}% | "
        f"{'❌ regression' if bad else '✅'} |"
    )
    return bad


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("base")
    ap.add_argument("head")
    ap.add_argument("--title", default="Perf gate")
    ap.add_argument("--threshold", type=float, default=10.0, help="allowed throughput regression in percent")
    ap.add_argument("--p99-threshold", type=float, default=None,
                    help="allowed p99 regression in percent (default: 2x --threshold; tails are noisier)")
    ap.add_argument("--rss-threshold", type=float, default=None,
                    help="allowed RSS growth in percent; omitted = record only (measurement-pending)")
    ap.add_argument("--require-wrk2", action="store_true",
                    help="fail when either side is missing wrk2 rows")
    a = ap.parse_args(argv)

    base_all, head_all = load_all(a.base), load_all(a.head)
    base, head = select(base_all, "wrk"), select(head_all, "wrk")
    if not base or not head:
        sys.exit("no wrk results in the jsonl files")

    p99_limit = a.p99_threshold if a.p99_threshold is not None else 2 * a.threshold
    out = [f"### {a.title} (median of {len(base)}/{len(head)} wrk runs, thresholds rps {a.threshold:g}% / p99 {p99_limit:g}%)", "",
           "| Metric | master | PR | Change | Allowed | Result |", "|---|---|---|---|---|---|"]
    failed = False
    failed |= append_metric(out, "Requests/s", base, head, "rps", +1, lambda v: f"{v:,.0f}", a.threshold)
    failed |= append_metric(out, "p99 latency", base, head, "p99_us", -1, lambda v: f"{v/1000:.2f} ms", p99_limit)

    errs = sum(r.get("errors", 0) for r in head)
    if errs:
        failed = True
        out.append(f"\n❌ PR build had {errs} socket/HTTP errors during the wrk runs.")

    base_w2, head_w2 = select(base_all, "wrk2"), select(head_all, "wrk2")
    if a.require_wrk2 and (not base_w2 or not head_w2):
        failed = True
        out.append("\n❌ wrk2 results are required but missing from base or head.")
    elif (base_w2 and not head_w2) or (head_w2 and not base_w2):
        failed = True
        out.append("\n❌ wrk2 results are present on only one side.")
    elif base_w2 and head_w2:
        out.append("")
        out.append(
            f"wrk2 fixed-rate p99 (median of {len(base_w2)}/{len(head_w2)} runs; "
            f"rate is {WRK2_NOTE}; relative threshold {p99_limit:g}%)"
        )
        failed |= append_metric(
            out, "wrk2 p99 latency", base_w2, head_w2, "p99_us", -1,
            lambda v: f"{v/1000:.2f} ms", p99_limit,
        )
        w2_errs = sum(r.get("errors", 0) for r in head_w2)
        if w2_errs:
            failed = True
            out.append(f"\n❌ PR build had {w2_errs} socket/HTTP errors during the wrk2 runs.")

    base_rss, head_rss = select(base_all, "rss"), select(head_all, "rss")
    if not base_rss or not head_rss:
        failed = True
        out.append("\n❌ RSS was not recorded on both sides (harness error).")
    else:
        bm, bp = rss_value(base_rss)
        hm, hp = rss_value(head_rss)
        change = (hm - bm) / bm * 100 if bm else 0.0
        phase = hp if hp == bp else f"{bp}→{hp}"
        if a.rss_threshold is None:
            out.append(
                f"| RSS ({phase}) | {bm:,.0f} kB | {hm:,.0f} kB | {change:+.1f}% | "
                f"pending | ✅ recorded |"
            )
            out.append(
                "\nRSS budgets are measurement-pending: the gate records kB and the percent "
                "change, and does not fail on magnitude until `--rss-threshold` / "
                "`PERF_RSS_THRESHOLD` is set from a measured run."
            )
        else:
            def phase_vals(rows, preferred):
                vals = [r["rss_kb"] for r in rows if r.get("phase") == preferred]
                return vals or [r["rss_kb"] for r in rows]
            _, brel = stats([{"rss_kb": v} for v in phase_vals(base_rss, bp)], "rss_kb")
            _, hrel = stats([{"rss_kb": v} for v in phase_vals(head_rss, hp)], "rss_kb")
            allowed = max(a.rss_threshold, 2 * max(brel, hrel) * 100)
            bad = change > allowed
            failed |= bad
            out.append(
                f"| RSS ({phase}) | {bm:,.0f} kB | {hm:,.0f} kB | {change:+.1f}% | "
                f"{allowed:.1f}% | {'❌ regression' if bad else '✅'} |"
            )

    print("\n".join(out))
    return 1 if failed else 0


WRK2_NOTE = "70% of that side's median wrk req/s unless WRK2_RATE is set"


if __name__ == "__main__":
    sys.exit(main())
