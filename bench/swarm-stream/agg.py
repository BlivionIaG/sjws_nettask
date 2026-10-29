#!/usr/bin/env python3
"""Aggregate S2c PR 8 stream-gate ABBA / A/A results (same FAIL rule as #282)."""
import json, math, sys
from pathlib import Path

def binom_sf(k, n, p=0.5):
    from math import comb
    return sum(comb(n, i) * (p ** i) * ((1 - p) ** (n - i)) for i in range(k, n + 1))

out = Path(sys.argv[1])
print("agg: waiting for finished blocks under", out)
# Placeholder — filled once gate.sh writes b*-{base,head}-{sse,binary}/result.json
