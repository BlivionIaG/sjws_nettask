#!/usr/bin/env python3
"""Mirror of StreamLoad's #415 window accounting (slotsInWindow / onChunk / accountWave).

Architecture review of 5f4805d6, item 1: counting only chunks *received* while the window is open
let a stall in the last ~1.8 s, or ~9 % hung streams, pass. The fixed rule counts by slot time in
[start, end), keeps receiving through the drain cutoff, and ages every in-window slot still unsent
or unreceived at cutoff. These scenarios show the old rule passing and the new one failing.
(Mirror test: the Java itself is checked when it can be compiled; keep the two in step.)
"""
from __future__ import annotations

import math
import random

SUB_BITS = 6
SUB = 1 << SUB_BITS
BUCKETS = 64 * SUB


def index(us: int) -> int:  # LatBag.index
    us = max(1, us)
    exp = us.bit_length() - 1
    if exp < SUB_BITS:
        return min(us, BUCKETS - 1)
    base = 1 << exp
    sub = min(SUB - 1, max(0, ((us - base) << SUB_BITS) >> exp))
    return min(exp * SUB + sub, BUCKETS - 1)


def upper(b: int) -> float:  # LatBag.upper
    exp, sub = b // SUB, b % SUB
    if exp < SUB_BITS:
        return float(max(1, b + 1))
    base = 1 << exp
    return base + (sub + 1) * max(1, base >> SUB_BITS)


def p99(samples_us) -> float:
    h = [0] * BUCKETS
    for s in samples_us:
        h[index(int(max(1, s)))] += 1
    n = sum(h)
    need = max(1, math.ceil(0.99 * n))
    seen = 0
    for b, c in enumerate(h):
        seen += c
        if seen >= need:
            return upper(b)
    return upper(BUCKETS - 1)


def ceil_div(a: int, b: int) -> int:  # Math.ceilDiv for b > 0
    return -((-a) // b)


def slots_in_window(phase: int, iv: int, start: int, end: int) -> int:  # StreamLoad.slotsInWindow
    k_lo = max(0, ceil_div(start - phase, iv))
    k_hi = max(0, ceil_div(end - phase, iv))
    return max(0, k_hi - k_lo)


MS = 1_000_000
START, END = 10_000 * MS, 30_000 * MS      # 20 s window
CUTOFF = END + 75_000 * MS                 # drain timeout
IV = 9 * MS                                # ~110 MiB/s over 1k streams
BASE_LAT_US = 300


def simulate(n_streams: int, deliver, registered=lambda i: True, seed=1):
    """deliver(slot_ns) -> receive time ns or None (never). Returns (old_p99, new_p99, shortfall)."""
    rnd = random.Random(seed)
    old, new = [], []
    expected = received = 0
    for i in range(n_streams):
        if not registered(i):  # producer never claimed: phantom slots spread over the window
            per = (END - START) // IV
            expected += per
            new += [(CUTOFF - (START + k * IV)) / 1e3 for k in range(per)]
            continue
        phase = rnd.randrange(IV)
        exp = slots_in_window(phase, IV, START, END)
        expected += exp
        recv = 0
        k = 0
        while True:
            slot = phase + k * IV
            k += 1
            if slot >= END:
                break
            if slot < START:
                continue
            rt = deliver(slot)
            if rt is not None and START <= rt < END:      # old rule: received while measureActive
                old.append((rt - slot) / 1e3)
            if rt is not None and rt <= CUTOFF:           # new rule: by slot time, through cutoff
                new.append((rt - slot) / 1e3)
                recv += 1
        missing = exp - recv
        k_hi = max(0, ceil_div(END - phase, IV))
        for j in range(1, missing + 1):                   # accountWave: age the youngest slots
            new.append((CUTOFF - (phase + (k_hi - j) * IV)) / 1e3)
        received += recv
    return p99(old), p99(new), expected - received


def main():
    # slotsInWindow edge cases
    assert slots_in_window(0, 10, 0, 100) == 10
    assert slots_in_window(5, 10, 0, 100) == 10           # 5,15,...,95
    assert slots_in_window(5, 10, 6, 96) == 9             # 15..95
    assert slots_in_window(200, 10, 0, 100) == 0          # phase after the window
    assert slots_in_window(0, 10, 100, 100) == 0

    healthy = lambda s: s + BASE_LAT_US * 1000
    o, n, short = simulate(200, healthy)
    assert o < 1000 and n < 1000 and short == 0, (o, n, short)

    # Stall in the last 1.8 s, then nothing until the cutoff (server hangs).
    stall_hang = lambda s: None if s >= END - 1_800 * MS else healthy(s)
    o, n, short = simulate(200, stall_hang)
    assert o < 1000, o                                    # old rule: passes (samples vanished)
    assert n > 1000 and short > 0, (n, short)             # new rule: fails, slots aged

    # Stall in the last 1.8 s, delivered after the window end (late, not lost).
    stall_late = lambda s: END + 200 * MS if s >= END - 1_800 * MS else healthy(s)
    o, n, short = simulate(200, stall_late)
    assert o < 1000, o
    assert n > 1000 and short == 0, (n, short)            # counted at their real latency

    # ~9 % hung streams that claimed but never got a chunk through.
    hung = set(range(0, 200, 11))
    rnd = random.Random(7)
    old, new, missing_total = [], [], 0
    for i in range(200):
        phase = rnd.randrange(IV)
        exp = slots_in_window(phase, IV, START, END)
        if i in hung:
            k_hi = max(0, ceil_div(END - phase, IV))
            new += [(CUTOFF - (phase + (k_hi - j) * IV)) / 1e3 for j in range(1, exp + 1)]
            missing_total += exp
            continue
        lat = [BASE_LAT_US] * exp
        old += lat
        new += lat
    assert p99(old) < 1000 and p99(new) > 1000 and missing_total > 0

    # ~9 % hung streams that never claimed (phantom streams).
    o, n, short = simulate(200, healthy, registered=lambda i: i % 11 != 0)
    assert o < 1000 and n > 1000 and short > 0, (o, n, short)
    print("ok")


if __name__ == "__main__":
    main()
