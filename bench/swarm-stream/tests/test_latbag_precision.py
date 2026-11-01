#!/usr/bin/env python3
"""Mirror LatBag index/upper: gate thresholds must not false-pass (Codex P2 #459)."""
from __future__ import annotations

SUB_BITS = 6
SUB = 1 << SUB_BITS

def index(us: int) -> int:
    us = max(1, us)
    exp = us.bit_length() - 1
    if exp < SUB_BITS:
        return us
    base = 1 << exp
    sub = ((us - base) << SUB_BITS) >> exp
    sub = min(SUB - 1, max(0, sub))
    return exp * SUB + sub

def upper(b: int) -> float:
    exp = b // SUB
    sub = b % SUB
    if exp < SUB_BITS:
        return float(max(1, b + 1))
    base = 1 << exp
    width = max(1, base >> SUB_BITS)
    return base + (sub + 1) * width

def main():
    # Old coarse midpoint reported 768 for anything in [512,1024) — false-pass on p99<1000.
    u1001 = upper(index(1001))
    assert u1001 >= 1000.0, u1001
    # Ceil 1500: sample above 1500 must report >1500 (not a mid-bucket under-estimate).
    u1501 = upper(index(1501))
    assert u1501 > 1500.0, u1501
    # Sanity: much smaller samples stay well below 1000.
    u500 = upper(index(500))
    assert u500 < 1000.0, u500
    print(f"OK test_latbag_precision 500→{u500:.1f} 1001→{u1001:.1f} 1501→{u1501:.1f}")

if __name__ == "__main__":
    main()
