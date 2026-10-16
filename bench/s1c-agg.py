#!/usr/bin/env python3
"""Aggregates bench/s1c-ab.sh output: per side, the per-run reqreply p99s, their mean±sd, the pooled p99 over every
call of every run (from the -Dsjws.bench.dump files, column 2 = latency ns, -1 = failed/missing), echo p99s and
load start/end. Usage: s1c-agg.py <out-dir>. No medians (gate: pooled p99 < 500 µs, §13 S1c)."""
import glob, re, statistics as st, sys

out = sys.argv[1]
lines = open(f"{out}/runs.log").read().splitlines()
for side in ("base", "pr"):
    rr = [l for l in lines if f"side={side} bench=reqreply " in l]
    p99 = [float(m.group(1)) for l in rr for m in [re.search(r"rtt_p99_us=([\d.]+)", l)] if m]
    echo = [float(m.group(1)) for l in lines if f"side={side} bench=echo " in l for m in [re.search(r"rtt_p99_us=([\d.]+)", l)] if m]
    loads = [(a, b) for l in rr for a, b in re.findall(r"load start=([\d.]+) end=([\d.]+)", l)]
    lat = []
    for f in sorted(glob.glob(f"{out}/dumps/{side}-*.txt")):
        with open(f) as fh:
            lat += [int(x.split()[1]) for x in fh if int(x.split()[1]) >= 0]
    lat.sort()
    pooled = lat[max(0, -(-99 * len(lat) // 100) - 1)] / 1e3 if lat else float("nan")
    msd = f"{st.mean(p99):.1f} ± {st.stdev(p99):.1f}" if len(p99) > 1 else (f"{p99[0]:.1f}" if p99 else "n/a")
    print(f"{side}: runs={len(p99)} per-run p99 us={p99} mean±sd={msd} us pooled_p99={pooled:.1f} us (n={len(lat)}) "
          f"echo_p99 us={echo} load start/end={loads} gate(pooled<500)={'PASS' if pooled < 500 else 'FAIL'}")
