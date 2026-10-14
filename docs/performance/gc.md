# GC choice: G1 (default / tuned) vs generational ZGC — proposal

**Status: DRAFT, partial data.** Only rep 1 of 3 is in for `g1`, `g1tuned` and `zgc`; `zgcpinned` and `serial` are pending.
No default is changed by this PR. The recommendation below is provisional until all reps are in.

## Configs (`bench/gc/run.sh`)

| name | flags |
|---|---|
| g1 | `-XX:+UseG1GC` (ergonomic: 252 MiB initial, ~3.9 GiB max on the 8 vCPU / 15 GiB box) |
| g1tuned | `-XX:+UseG1GC -Xms1g -Xmx1g -Xmn400m -XX:+ParallelRefProcEnabled` (fixed young; `MaxGCPauseMillis` is moot with `-Xmn`) |
| g1pretouch | g1tuned + `-XX:+AlwaysPreTouch` (defined, not run yet) |
| zgc | `-XX:+UseZGC` (generational is the only ZGC mode in JDK 25) |
| zgcpinned | `-XX:+UseZGC -Xms1g -Xmx1g -XX:SoftMaxHeapSize=768m` |
| serial | `-XX:+UseSerialGC -Xmx256m` (small-footprint nodes) |

Note: `G1NewSizePercent`/`G1MaxNewSizePercent` are experimental in JDK 25 (need `-XX:+UnlockExperimentalVMOptions`), so the tuned config uses `-Xmn`.

## Method

Per config and rep, one block under `flock /tmp/sjws-bench.lock`, started only when the 1-minute load is < 4 (load is recorded per measurement in `results.txt`); configs alternate within each rep:

- **startup**: spawn → first 200 on `/1k.bin` (`java -jar`, server on CPUs 0-3).
- **idle RSS**: VmRSS 3 s after the first request.
- **HTTP**: wrk2 `-t2 -c64` on CPUs 4-5, 5 s warm-up + 15 s `--latency`, 1 KiB at 20k req/s and 64 KiB at 4k req/s (p50/p99/p99.9 in **ms**). RSS/HWM and GC pauses for the whole server run afterwards.
- **messaging**: `MessagingLogBench produce|fetch 1024 4 10` in-process (µs).
- **swarm**: `SwarmBench reqreply 10000 12` with `sjws.swarm.pool.shards=1` and `4` (µs).
- GC pauses from `-Xlog:gc,gc+phases`: for ZGC these are the sub-ms Mark Start/End/Relocate Start pauses, `gc_collections` counts cycles.

Box: shared 8 vCPU, other agents' benchmarks run between our blocks (the lock serialises benches, not builds/other CPU load). Load at measurement was 1.9–3.7.

## Results (median, range, n)

| bench | metric | g1 | g1tuned | zgc |
|---|---|---|---|---|
| startup | ms | 277 (n=1) | 243 (n=1) | 261 (n=1) |
| idle | rss_kb | 70192 (n=1) | 72368 (n=1) | 73040 (n=1) |
| http_1k | p50 | 0.94 (n=1) | 0.94 (n=1) | 0.94 (n=1) |
| http_1k | p99 | 2.14 (n=1) | 2.03 (n=1) | 2.01 (n=1) |
| http_1k | p999 | 8.41 (n=1) | 6.56 (n=1) | 10.6 (n=1) |
| http_1k | rps | 19895 (n=1) | 19895 (n=1) | 19280 (n=1) |
| http_64k | p50 | 0.9 (n=1) | 0.88 (n=1) | 0.88 (n=1) |
| http_64k | p99 | 1.92 (n=1) | 1.94 (n=1) | 1.98 (n=1) |
| http_64k | p999 | 2.35 (n=1) | 3.18 (n=1) | 3.28 (n=1) |
| http_64k | rps | 3981 (n=1) | 3981 (n=1) | 3981 (n=1) |
| http_after | rss_kb | 262724 (n=1) | 515484 (n=1) | 1185900 (n=1) |
| http_after | gc_collections | 57 (n=1) | 20 (n=1) | 21 (n=1) |
| http_after | gc_pauses | 57 (n=1) | 20 (n=1) | 105 (n=1) |
| http_after | gc_pause_total_ms | 70.2 (n=1) | 44.3 (n=1) | 1.6 (n=1) |
| http_after | gc_pause_max_ms | 4.93 (n=1) | 4.84 (n=1) | 0.06 (n=1) |
| msg_produce | ops/s | 229402 (n=1) | 227992 (n=1) | 183153 (n=1) |
| msg_produce | p99_us | 33.6 (n=1) | 33.7 (n=1) | 44.1 (n=1) |
| msg_produce | p999_us | 85.2 (n=1) | 104 (n=1) | 203 (n=1) |
| msg_produce | gc_collections | 58 (n=1) | 30 (n=1) | 6 (n=1) |
| msg_produce | gc_pauses | 58 (n=1) | 30 (n=1) | 28 (n=1) |
| msg_produce | gc_pause_total_ms | 77.7 (n=1) | 52.1 (n=1) | 0.4 (n=1) |
| msg_produce | gc_pause_max_ms | 4.63 (n=1) | 4.74 (n=1) | 0.05 (n=1) |
| msg_fetch | ops/s | 66462 (n=1) | 68033 (n=1) | 52185 (n=1) |
| msg_fetch | p99_us | 119 (n=1) | 106 (n=1) | 111 (n=1) |
| msg_fetch | p999_us | 1024 (n=1) | 1009 (n=1) | 212 (n=1) |
| msg_fetch | gc_collections | 253 (n=1) | 644 (n=1) | 51 (n=1) |
| msg_fetch | gc_pauses | 253 (n=1) | 644 (n=1) | 169 (n=1) |
| msg_fetch | gc_pause_total_ms | 295 (n=1) | 569 (n=1) | 2.3 (n=1) |
| msg_fetch | gc_pause_max_ms | 7.25 (n=1) | 7.74 (n=1) | 0.05 (n=1) |
| reqreply_s1 | rtt_p50_us | 25.9 (n=1) | 26.4 (n=1) | 30.7 (n=1) |
| reqreply_s1 | rtt_p99_us | 1189 (n=1) | 2058 (n=1) | 2002 (n=1) |
| reqreply_s1 | rtt_max_us | 12680 (n=1) | 8291 (n=1) | 14623 (n=1) |
| reqreply_s1 | gc_collections | 14 (n=1) | 4 (n=1) | 3 (n=1) |
| reqreply_s1 | gc_pauses | 14 (n=1) | 4 (n=1) | 15 (n=1) |
| reqreply_s1 | gc_pause_total_ms | 65.2 (n=1) | 21 (n=1) | 0.3 (n=1) |
| reqreply_s1 | gc_pause_max_ms | 9.64 (n=1) | 7.5 (n=1) | 0.05 (n=1) |
| reqreply_s4 | rtt_p50_us | 27.8 (n=1) | 26.7 (n=1) | 29.7 (n=1) |
| reqreply_s4 | rtt_p99_us | 599 (n=1) | 559 (n=1) | 415 (n=1) |
| reqreply_s4 | rtt_max_us | 7259 (n=1) | 8115 (n=1) | 7365 (n=1) |
| reqreply_s4 | gc_collections | 14 (n=1) | 4 (n=1) | 3 (n=1) |
| reqreply_s4 | gc_pauses | 14 (n=1) | 4 (n=1) | 15 (n=1) |
| reqreply_s4 | gc_pause_total_ms | 55.3 (n=1) | 22.7 (n=1) | 0.2 (n=1) |
| reqreply_s4 | gc_pause_max_ms | 8.16 (n=1) | 6.88 (n=1) | 0.03 (n=1) |

## Reading so far (rep 1 only)

- **GC pauses**: ZGC's longest pause is ≤ 0.06 ms everywhere vs 4.6–9.6 ms young pauses on G1 (default and tuned). g1tuned cuts young collections on reqreply from 14 to 4 per run but each pause stays 7–8 ms (copying cost, not count).
- **reqreply p99 is not GC-dominated on this box**: rep 1 gave shards=1 p99 1189 µs (g1), 2058 µs (g1tuned), 2002 µs (zgc), all far worse than Agent Swarm's quiet-window 896/263 µs. With ≤ 4 pauses (g1tuned) or no pause > 0.06 ms (zgc), p99 still reached ~2 ms, so the shards=1 tail on a loaded box comes from CPU contention / the partition lock (see /workspace/s1c-gc-findings.md §2), not GC. Needs reps 2-3 before concluding.
- **HTTP**: p99 indistinguishable (≈2.0 ms 1 KiB, ≈1.9 ms 64 KiB). ZGC fell ~3% short of the 20k offered rate on 1 KiB in rep 1.
- **Messaging**: ZGC costs throughput (produce −20%, fetch −22%) and produce p99.9; it wins fetch p99.9 (212 vs ~1010 µs), which is where G1 pauses show up.
- **Footprint**: idle RSS is ~70-73 MiB for all. Under load RSS grows to the heap: g1 263 MiB, g1tuned 515 MiB (1 GiB pinned heap, touched lazily), zgc 1.19 GiB (ergonomic max heap is used aggressively). Startup ~240-280 ms for all.

## Provisional recommendation

Keep **G1 as the default** (no change). Document **generational ZGC with a pinned heap** (`-XX:+UseZGC -Xms<N> -Xmx<N>`, optionally `SoftMaxHeapSize`) as the recommended profile for latency-gated swarm/messaging-fetch nodes, where it removes the 5-10 ms pauses that set p99.9/max, at the cost of ~20% messaging throughput and much higher RSS unless the heap is capped. Fixed-young G1 reduces pause count but not pause length and doubled fetch collections, so it is not worth recommending. To be confirmed by reps 2-3, zgcpinned and serial.

## Allocation on the request/reply path

Pending: `bench/gc/alloc-profile.sh` (JFR `ObjectAllocationSample`, top sites/types). Candidates already named in s1c findings (owned by Agent Swarm; proposals only): per-call `CompletableFuture`, envelope copies (`TaskEnvelope` decode in `TaskService.claim`, `payloadOf` 1 KiB copy), `Delivery`/`Task` objects, reply routing.

## Gaps

- Reps 2-3 for all configs; zgcpinned and serial; g1pretouch and parallel not run.
- JFR allocation profile; CP-node idle size (`bench/footprint` with these flags) not run.
- Box is shared and noisy; a CPU-isolated run is needed for the 500 µs reqreply gate.
