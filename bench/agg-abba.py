#!/usr/bin/env python3
import csv, math, statistics as stats
from pathlib import Path
raw = Path('/workspace/wt/ledger-pool-impl/bench/abba-raw.tsv')
rows = list(csv.DictReader(raw.open(), delimiter='\t'))
for bench in ('raft', 'msg'):
    b = [r for r in rows if r['bench']==bench and r['ops_s']]
    before = [float(r['ops_s']) for r in b if r['side']=='before']
    after = [float(r['ops_s']) for r in b if r['side']=='after']
    if len(before)<2 or len(after)<2:
        print(bench, 'insufficient', len(before), len(after)); continue
    # Welch t on ops/s (info); geomean after/before
    mb, ma = stats.mean(before), stats.mean(after)
    sb, sa = stats.stdev(before), stats.stdev(after)
    # paired ratios in round order
    ratios=[]
    byr={}
    for r in b:
        byr.setdefault(r['round'], {})[r['side']]=float(r['ops_s'])
    for rd,d in sorted(byr.items(), key=lambda x:int(x[0])):
        if 'before' in d and 'after' in d and d['before']>0:
            ratios.append(d['after']/d['before'])
    logs=[math.log(x) for x in ratios]
    # one-sided sign-flip: count after slower (ratio<1 for throughput → bad if we used latency;
    # for ops/s, regression is ratio<1. #263 used p99 so higher=worse. Here higher ops=better.
    # Report geomean after/before; FAIL gate for throughput regression: geomean < 1/1.10 and p...
    # Design §11.1 says log(p99_PR/p99_base); for produce we report ops/s like #335.
    geo = math.exp(sum(logs)/len(logs))
    # Welch
    nb, na = len(before), len(after)
    sev = sb*sb/nb + sa*sa/na
    t = (ma-mb)/math.sqrt(sev) if sev>0 else 0
    print(f'{bench}: before={mb:.1f}±{sb:.1f} after={ma:.1f}±{sa:.1f} t={t:.2f} geomean(after/before)={geo:.3f} n={len(ratios)}')
    for r in b:
        print(f"  r{r['round']} {r['side']} load1={r['load1_start']} ops={r['ops_s']}")
