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
