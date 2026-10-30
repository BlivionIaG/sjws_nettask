# StreamLoad client hotspot (2026-10-30)

## Root cause
`producer()` enqueued into an **unbounded** `ConcurrentLinkedQueue` with no backpressure (`while (!stop) q.add(frame)`), while `window` sender VTs drained via HttpClient. At `streams=1` this:
- spun allocating frames → ~200% client CPU, huge G1/ZGC parallel phases
- JFR/jstack: `StreamLoad.producer` enqueue + `HttpClient` SelectorManager + GC

## Fix
Synchronous one-POST-at-a-time on the producer virtual thread (parallelism = N streams). Reused payload buffer; ASCII ns without `String.format`.

## Offline check (private port, no flock)
| | before | after |
|--|--|--|
| client CPU % wall | ~195 | ~22 mid-measure / ~92 averaged |
| p99 µs | ~58k | **~393** |
| samples / 20s | 25k | **180k** |

## Abs-01 follow-up (2026-10-30 ~12:00 Europe/Paris) — 429 busy-loop at 1k

Official abs-01 (`LOADGEN=java`, 1000 streams) after f9215f7:
- client_cpu_pct_of_wall **182.8** → generator_saturated / self_loaded (Architecture #2)
- mib_s 26.5, p99 ~12.9 ms, measure errors 1951 ("header parser received no bytes")
- credit_stalls / credit_refusals_total **4782**

Root cause on top of the old enqueue spin: producer treated HTTP 429 as a counted no-op and **immediately re-POSTed** the next attempt without SDK #261 Q9 backoff (5 ms × 2^tries ±20%, cap Retry-After) and without applying CREDIT snapshots. At window=8 × 1k streams that busy-loops the two client CPUs.

Fix (this commit): same-seq 429 retry with credit_backoff; parse CREDIT from 200 to gate sends; ThreadLocal latency bags (cut ConcurrentLinkedQueue CAS at high sample rates).
