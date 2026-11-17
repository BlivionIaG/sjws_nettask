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


def first_slot_at_or_after(phase: int, iv: int, t: int) -> int:  # StreamLoad.firstSlotAtOrAfter
    return phase + max(0, ceil_div(t - phase, iv)) * iv


MS = 1_000_000
EPOCH = 0                                  # Win.epochNs: wave creation, before the admit ramp
START, END = 10_000 * MS, 30_000 * MS      # 20 s window
CUTOFF = END + 75_000 * MS                 # drain timeout
IV = 9 * MS                                # ~110 MiB/s over 1k streams
BASE_LAT_US = 300
SEND_NS = 300_000                          # one sync POST round trip


def first_send_slot(phase: int, reg: int, measured: bool = True) -> int:
    """StreamLoad.producer: start at the first slot >= min(claim return, window start)."""
    return first_slot_at_or_after(phase, IV, min(reg, START) if measured else reg)


def simulate(n_streams: int, deliver=None, registered=lambda i: True, reg_at=lambda i: 0,
             legacy_anchor=False, seed=1):
    """Paced producers with catch-up, consumers through the cutoff.

    deliver(slot_ns, send_ns) -> receive time ns or None. legacy_anchor=True models b47b7b26, where a
    stream's phase (and so its expected slots) started at its own claim return.
    Returns dict(old_p99, new_p99, shortfall, offered_frac) where offered_frac = in-window slot
    sends / (streams x window/IV), i.e. offered_actual / 110.
    """
    deliver = deliver or (lambda slot, send: send + BASE_LAT_US * 1000)
    rnd = random.Random(seed)
    old, new = [], []
    expected = received = sends = 0
    for i in range(n_streams):
        if not registered(i):  # producer never claimed: phantom slots spread over the window
            per = (END - START) // IV
            expected += per
            new += [(CUTOFF - (START + k * IV)) / 1e3 for k in range(per)]
            continue
        reg = reg_at(i)
        off = rnd.randrange(IV)
        phase = (reg + off) if legacy_anchor else (EPOCH + off)
        exp = slots_in_window(phase, IV, START, END)
        expected += exp
        slot = phase if legacy_anchor else first_send_slot(phase, reg)
        busy = reg
        recv = 0
        while slot < END:
            send = max(slot, busy)
            if send > CUTOFF:
                break
            busy = send + SEND_NS
            if slot >= START:
                sends += 1
                rt = deliver(slot, send)
                if rt is not None and START <= rt < END:      # receive-time rule (pre-5f4805d6)
                    old.append((rt - slot) / 1e3)
                if rt is not None and rt <= CUTOFF:           # slot-time rule, through the cutoff
                    new.append((rt - slot) / 1e3)
                    recv += 1
            slot += IV
        missing = exp - recv
        k_hi = max(0, ceil_div(END - phase, IV))
        for j in range(1, missing + 1):                   # accountWave: youngest slots, lower bound
            new.append((CUTOFF - (phase + (k_hi - j) * IV)) / 1e3)
        received += recv
    full = n_streams * (END - START) / IV
    return {"old_p99": p99(old), "new_p99": p99(new) if new else 0.0,
            "shortfall": expected - received, "offered_frac": sends / full, "expected": expected}


def main():
    # slotsInWindow / firstSlotAtOrAfter edge cases
    assert slots_in_window(0, 10, 0, 100) == 10
    assert slots_in_window(5, 10, 0, 100) == 10           # 5,15,...,95
    assert slots_in_window(5, 10, 6, 96) == 9             # 15..95
    assert slots_in_window(200, 10, 0, 100) == 0          # a phase after the window owns no slots...
    assert slots_in_window(0, 10, 100, 100) == 0
    assert first_slot_at_or_after(5, 10, 0) == 5 and first_slot_at_or_after(5, 10, 6) == 15
    assert first_slot_at_or_after(5, 10, 15) == 15
    # ...which is why phases are anchored at the wave epoch: every phase is <= start, so every
    # registered stream owns a full window of slots (re-review item 1).
    for off in (0, IV // 3, IV - 1):
        n = slots_in_window(EPOCH + off, IV, START, END)
        assert n in ((END - START) // IV, (END - START) // IV + 1), n

    r = simulate(200)
    assert r["old_p99"] < 1000 and r["new_p99"] < 1000 and r["shortfall"] == 0, r
    assert abs(r["offered_frac"] - 1) < 0.005, r          # within agg's 0.5 % allowance

    # Stall in the last 1.8 s, then nothing until the cutoff (server hangs).
    stall_hang = lambda slot, send: None if slot >= END - 1_800 * MS else send + BASE_LAT_US * 1000
    r = simulate(200, stall_hang)
    assert r["old_p99"] < 1000, r                         # receive-time rule: passes
    assert r["new_p99"] > 1000 and r["shortfall"] > 0, r  # slot rule: fails, slots aged

    # Stall in the last 1.8 s, delivered after the window end (late, not lost).
    stall_late = lambda slot, send: END + 200 * MS if slot >= END - 1_800 * MS else send + BASE_LAT_US * 1000
    r = simulate(200, stall_late)
    assert r["old_p99"] < 1000, r
    assert r["new_p99"] > 1000 and r["shortfall"] == 0, r

    # ~9 % of streams that claimed but never got a chunk through.
    hung = set(range(0, 200, 11))
    r = simulate(200, lambda slot, send: None)            # nothing delivered at all
    assert r["shortfall"] == r["expected"]

    def hung_sim():
        rnd = random.Random(7)
        new, short = [], 0
        for i in range(200):
            phase = EPOCH + rnd.randrange(IV)
            exp = slots_in_window(phase, IV, START, END)
            if i in hung:
                k_hi = max(0, ceil_div(END - phase, IV))
                new += [(CUTOFF - (phase + (k_hi - j) * IV)) / 1e3 for j in range(1, exp + 1)]
                short += exp
            else:
                new += [BASE_LAT_US] * exp
        return p99(new), short
    hp, hs = hung_sim()
    assert hp > 1000 and hs > 0

    # ~9 % hung streams that never claimed (phantom streams).
    r = simulate(200, registered=lambda i: i % 11 != 0)
    assert r["old_p99"] < 1000 and r["new_p99"] > 1000 and r["shortfall"] > 0, r

    # Re-review item 1: ~9 % of claims return AFTER the window (b47b7b26 gave them 0 expected
    # slots, so the window passed at ~100.1 MiB/s offered). Now they own a full window of slots and
    # catch them up late: p99 fails.
    late = lambda i: END + 1_000 * MS if i % 11 == 0 else 0
    legacy = simulate(200, reg_at=late, legacy_anchor=True)
    assert legacy["new_p99"] < 1000 and legacy["shortfall"] == 0, legacy   # the hole
    assert 0.90 < legacy["offered_frac"] < 0.92, legacy                    # ~100.1 MiB/s
    r = simulate(200, reg_at=late)
    assert r["new_p99"] > 1000, r
    assert r["expected"] > legacy["expected"], (r, legacy)
    assert abs(r["offered_frac"] - 1) < 0.005, r          # sent late, but sent: offered stays 110

    # A claim returning MID-window catches up from the window start.
    mid = lambda i: START + 10_000 * MS if i % 11 == 0 else 0
    legacy = simulate(200, reg_at=mid, legacy_anchor=True)
    assert legacy["new_p99"] < 1000 and legacy["shortfall"] == 0, legacy
    assert legacy["offered_frac"] < 0.96, legacy          # agg's offered_actual check catches this
    r = simulate(200, reg_at=mid)
    assert r["new_p99"] > 1000 and r["shortfall"] == 0, r

    # A claim so late it can't catch up before the cutoff: unsent slots are aged.
    r = simulate(200, reg_at=lambda i: CUTOFF + MS if i % 11 == 0 else 0)
    assert r["new_p99"] > 1000 and r["shortfall"] > 0, r

    # Re-review fix: unmeasured slots (warmup / before the window) are never caught up: a producer
    # 2 s behind before the window skips to the next slot instead of bursting at window start.
    phase = EPOCH + 123
    behind_now = START - 50 * MS
    nxt = first_slot_at_or_after(phase, IV, behind_now)
    assert nxt >= behind_now and nxt - behind_now < IV
    # ...but once the window is open, a late producer resumes at the window start (catch-up).
    assert first_send_slot(phase, START + 5_000 * MS) == first_slot_at_or_after(phase, IV, START)
    print("ok")


if __name__ == "__main__":
    main()
