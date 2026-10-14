#!/usr/bin/env python3
"""Median (min-max, n) per config and metric from bench/gc/run.sh output. Usage: summarize.py results.txt"""
import sys, re, statistics, collections
rows = collections.defaultdict(lambda: collections.defaultdict(list))
for line in open(sys.argv[1]):
    kv = dict(re.findall(r'(\S+?)=("[^"]*"|\S+)', line))
    if "cfg" not in kv: continue
    for k, v in kv.items():
        m = re.fullmatch(r'([\d.]+)(us|ms|s)?', v)
        if not m or k in ("rep", "rate", "n", "bytes", "threads", "load", "MB/s", "hwm_kb", "p50_us"): continue
        x = float(m.group(1)) * {"us": 1e-3, "ms": 1, "s": 1e3, None: 1}[m.group(2)]
        rows[(kv["bench"], k)][kv["cfg"]].append(x)
cfgs = []
for d in rows.values():
    for c in d:
        if c not in cfgs: cfgs.append(c)
print("| bench | metric | " + " | ".join(cfgs) + " |")
print("|---|---|" + "---|" * len(cfgs))
fmt = lambda v: f"{v:.3g}" if v < 1000 else f"{v:.0f}"
for (b, k), d in rows.items():
    cells = []
    for c in cfgs:
        v = d.get(c)
        cells.append("–" if not v else fmt(statistics.median(v)) + (f" ({fmt(min(v))}–{fmt(max(v))}, n={len(v)})" if len(v) > 1 else " (n=1)"))
    print(f"| {b} | {k} | " + " | ".join(cells) + " |")
