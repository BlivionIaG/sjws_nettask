Operator profiles: [JVM flags](../operations/jvm-flags.md).

# GC choice: G1 (default / tuned) vs generational ZGC — proposal

**Status: proposal, 3 reps per config. No default is changed by this PR.** JDK 25.0.4.1, master `f60f08b`.

## Summary

- **Keep G1 as the default.** On HTTP (wrk2 1 KiB / 64 KiB) every GC is within noise at p99 (≈2.0–2.3 ms on this loopback box) and G1 has the best messaging produce throughput with a modest RSS.
- **Recommend generational ZGC with a pinned heap (`-XX:+UseZGC -Xms<N> -Xmx<N> -XX:SoftMaxHeapSize=<0.75N>`) as the documented latency profile** for swarm request/reply and messaging-fetch nodes: max pause 0.02–0.06 ms vs 7–10 ms on G1, fetch p99.9 266 µs vs 1004 µs, produce at 96% of G1 throughput. Pinning matters: unpinned ZGC grows RSS to 1.13 GiB (1,188,744 KiB) and loses 14% produce throughput.
- **Tuned G1 (fixed young `-Xmn400m`, pinned 1 GiB heap) is not worth it**: young collections drop 14 → 4 per reqreply run but each pause is still 7–8 ms, RSS doubles, fetch p99.9 is unchanged.
- **Serial (`-Xmx256m`) for small CP/footprint nodes**: lowest RSS under load (162 MiB vs 254 MiB G1; 166,220 vs 260,312 KiB), same startup and idle RSS; tail latency is worse (6–10 ms pauses).
- **The 500 µs swarm reqreply gate is not decidable on this shared box.** p99 ranged 465–4178 µs within a single config; no config's median is under 500 µs, and ZGC (no pause > 0.06 ms) still had 2 ms runs. GC removes the 7–10 ms pause component (max/p99.9) but the p99 at shards=1 is dominated by CPU contention and the partition lock (as in the S1c findings). A CPU-isolated run is needed for the gate.

## Configs (`bench/gc/run.sh`)

| name | flags |
|---|---|
| g1 | `-XX:+UseG1GC` (ergonomic: 252 MiB initial, ~3.9 GiB max on the 8 vCPU / 15 GiB box) |
| g1tuned | `-XX:+UseG1GC -Xms1g -Xmx1g -Xmn400m -XX:+ParallelRefProcEnabled` (fixed young; `MaxGCPauseMillis` is moot with `-Xmn`) |
| g1pretouch | g1tuned + `-XX:+AlwaysPreTouch` (defined in run.sh, not measured) |
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

Box: shared 8 vCPU, other agents' benchmarks run between our blocks (the lock serialises benches, not builds/other CPU load). Every block started with the 1-minute load < 4; the load sampled *during* measurements ranged 1.77–6.24 (it includes the bench itself and other agents' builds, which the lock does not serialise). Per-run load is in `gc-results-raw.txt`. 3 reps per config, configs alternating (g1, g1tuned, zgc, zgcpinned, serial), 05:13–06:36 Paris on 2026-10-14.

## Results (median (min–max, n)); HTTP latencies in ms, messaging/swarm in µs, RSS in KiB

| bench | metric | g1 | g1tuned | zgc | zgcpinned | serial |
|---|---|---|---|---|---|---|
| startup | ms | 208 (194–277, n=3) | 221 (219–243, n=3) | 261 (261–276, n=3) | 318 (290–348, n=3) | 218 (210–224, n=3) |
| idle | rss_kb | 70412 (70192–70736, n=3) | 72412 (72368–72472, n=3) | 72816 (72804–73040, n=3) | 72728 (72692–72924, n=3) | 68592 (68528–68780, n=3) |
| http_1k | p99 | 2.95 (2.14–4.13, n=3) | 2.09 (2.03–3.14, n=3) | 2.31 (2.01–2.78, n=3) | 2.1 (2.03–2.13, n=3) | 2.26 (2.03–3.24, n=3) |
| http_1k | p999 | 8.41 (6.42–9.31, n=3) | 6.56 (5.42–8.31, n=3) | 8.54 (6.14–10.6, n=3) | 6.26 (4.8–6.49, n=3) | 6.07 (6.02–10.2, n=3) |
| http_1k | rps | 19895 (19894–19895, n=3) | 19895 (19279–19895, n=3) | 19892 (19280–19896, n=3) | 19896 (19895–19897, n=3) | 19895 (19893–19896, n=3) |
| http_64k | p99 | 2.03 (1.92–2.21, n=3) | 1.94 (1.9–2.02, n=3) | 1.98 (1.97–1.98, n=3) | 1.95 (1.93–2.03, n=3) | 1.95 (1.94–3.89, n=3) |
| http_64k | p999 | 4.64 (2.35–5.11, n=3) | 3.18 (2.63–4.44, n=3) | 3.77 (3.28–5.17, n=3) | 4.57 (3.27–6.24, n=3) | 3.33 (2.46–7.78, n=3) |
| http_64k | rps | 3981 (3981–3981, n=3) | 3981 (3980–3981, n=3) | 3981 (3981–3981, n=3) | 3981 (3981–3981, n=3) | 3981 (3981–3981, n=3) |
| http_after | rss_kb | 260312 (251124–262724, n=3) | 525012 (515484–531580, n=3) | 1188744 (1185900–1190528, n=3) | 615940 (559284–782416, n=3) | 166220 (162832–171156, n=3) |
| http_after | gc_collections | 57 (56–57, n=3) | 20 (20–20, n=3) | 21 (21–21, n=3) | 41 (38–46, n=3) | 122 (121–122, n=3) |
| http_after | gc_pause_max_ms | 3.61 (3.41–4.93, n=3) | 4.38 (4.02–4.84, n=3) | 0.06 (0.06–0.11, n=3) | 0.05 (0.04–1.34, n=3) | 6.27 (5.23–9.99, n=3) |
| msg_produce | ops/s | 225659 (212326–229402, n=3) | 223926 (219571–227992, n=3) | 194259 (183153–201042, n=3) | 217221 (217095–222053, n=3) | 211733 (197078–217445, n=3) |
| msg_produce | p999_us | 104 (85.2–134, n=3) | 117 (104–124, n=3) | 183 (182–203, n=3) | 90.5 (88–112, n=3) | 132 (132–217, n=3) |
| msg_produce | gc_collections | 57 (54–58, n=3) | 29 (28–30, n=3) | 6 (6–7, n=3) | 29 (29–30, n=3) | 166 (158–171, n=3) |
| msg_produce | gc_pause_max_ms | 4.63 (4.12–4.7, n=3) | 6.71 (4.74–7.67, n=3) | 0.04 (0.04–0.05, n=3) | 0.04 (0.03–0.04, n=3) | 8.17 (7.26–8.74, n=3) |
| msg_fetch | ops/s | 66462 (64448–69993, n=3) | 67526 (62110–68033, n=3) | 52185 (51971–53445, n=3) | 57495 (54968–58258, n=3) | 81399 (80889–82635, n=3) |
| msg_fetch | p999_us | 1004 (963–1024, n=3) | 1065 (1009–1081, n=3) | 212 (199–237, n=3) | 266 (264–269, n=3) | 647 (609–1018, n=3) |
| msg_fetch | gc_collections | 253 (242–253, n=3) | 634 (588–644, n=3) | 51 (51–54, n=3) | 375 (353–399, n=3) | 3495 (3477–3551, n=3) |
| msg_fetch | gc_pause_max_ms | 7.25 (4.23–8.8, n=3) | 4.88 (4.79–7.74, n=3) | 0.04 (0.03–0.05, n=3) | 0.04 (0.04–0.04, n=3) | 8.78 (8.58–9.75, n=3) |
| reqreply_s1 | rtt_p50_us | 24.8 (24.4–25.9, n=3) | 25.4 (24.9–26.4, n=3) | 30.7 (28.3–30.8, n=3) | 28.6 (26.9–30.9, n=3) | 25.3 (24.9–27, n=3) |
| reqreply_s1 | rtt_p99_us | 1189 (1006–3022, n=3) | 1176 (1084–2058, n=3) | 1751 (465–2002, n=3) | 698 (576–3985, n=3) | 1923 (1446–4178, n=3) |
| reqreply_s1 | rtt_max_us | 9211 (8755–12680, n=3) | 9646 (8291–10349, n=3) | 10753 (8975–14623, n=3) | 11913 (8337–12012, n=3) | 7994 (6793–9754, n=3) |
| reqreply_s1 | gc_collections | 14 (13–14, n=3) | 4 (4–4, n=3) | 3 (3–3, n=3) | 7 (6–7, n=3) | 27 (26–28, n=3) |
| reqreply_s1 | gc_pause_max_ms | 9.64 (6.96–9.97, n=3) | 7.75 (7.5–8.22, n=3) | 0.05 (0.04–0.06, n=3) | 0.04 (0.02–0.04, n=3) | 5.11 (4.85–7.71, n=3) |
| reqreply_s4 | rtt_p50_us | 27.7 (27.3–27.8, n=3) | 27.7 (26.7–28, n=3) | 31.1 (29.7–33.2, n=3) | 32.6 (31.4–36.7, n=3) | 27.3 (26.8–31.4, n=3) |
| reqreply_s4 | rtt_p99_us | 1616 (599–3786, n=3) | 1806 (559–1845, n=3) | 572 (415–3087, n=3) | 2906 (737–4252, n=3) | 2267 (2023–4642, n=3) |
| reqreply_s4 | rtt_max_us | 9098 (7259–11272, n=3) | 8472 (8115–13649, n=3) | 9739 (7365–11960, n=3) | 8341 (7275–10597, n=3) | 8311 (7482–9797, n=3) |
| reqreply_s4 | gc_collections | 14 (14–14, n=3) | 4 (4–4, n=3) | 3 (3–3, n=3) | 7 (7–7, n=3) | 29 (26–29, n=3) |
| reqreply_s4 | gc_pause_max_ms | 8.16 (7.63–8.81, n=3) | 7.22 (6.88–7.25, n=3) | 0.03 (0.02–0.05, n=3) | 0.04 (0.02–0.05, n=3) | 6.8 (6.66–7.21, n=3) |

Columns: g1, g1tuned, zgc, zgcpinned, serial. `http_after` = whole HTTP server run (both wrk2 phases). ZGC pause counts are its sub-ms Mark Start/End/Relocate Start pauses.

## Allocation on the swarm request/reply path (JFR, default G1, shards=1, 10k req/s, 12 s)

`bench/gc/alloc-profile.sh 1`: 1.9 GiB sampled (`jdk.ObjectAllocationSample`) for ~140k calls including the 2 s warm-up (≈14 KiB per call, an upper bound since it includes background threads). That run's p99 was 1323 µs. `byte[]` is 36% of it. Top first-party sites:

| share | site | what | proposal (owner) |
|---|---|---|---|
| 9.5% | `LedgerEntry.encode:55` | `ByteBuffer.allocate(size(e))` per ledger entry | encode into a reused per-writer buffer / BufferPool (Messaging Core) |
| 5.1% | `RecordBatch.encodedSize:31` | for-each over `List.of(...)` allocates an iterator | index loop (Messaging Core) |
| 5.0% | `TaskEnvelope.encode:168` | `ByteBuffer.allocate(n)` per envelope | encode straight into the ledger record buffer (Agent Swarm) |
| 4.7% | `LedgerWriter.publish:274` | new `LedgerSnapshot` per publish | publish per group commit, not per record (Messaging Core) |
| 4.4% | `LedgerEntry$Enqueue.<init>` | entry object per enqueue | (Messaging Core) |
| 4.1% | `Delivery.payload:11` | `payload.clone()` on each read | owned/read-only view for internal callers (Messaging Core) |
| 3.9% | `Partition.appendAll:632` | `thenApply` stage per append | complete one future per batch (Messaging Core) |
| 3.9% | `TaskEnvelope.decode:214` | `new byte[len]` body copy on claim | slice/view of the ledger buffer (Agent Swarm) |
| 3.2% | `RecordBatch.crc:53` | `duplicate()` per CRC | `CRC32C.update(buf, off, len)` via absolute access (Messaging Core) |
| 2.2% + 1.7% | `ReplyRouter.call:125/119` | `orTimeout`+`whenComplete` per call, `body.clone()` | shared timeout wheel; take ownership of the body (Agent Swarm, already in progress) |

These are proposals only; the swarm and messaging code was not edited here. Together, the listed sites are about 48% of sampled bytes.

## Gaps

- `g1pretouch` and Parallel are defined in `run.sh` but were not measured. The `AlwaysPreTouch` tradeoff (startup vs first-touch RSS) is still open.
- CP-node idle size with each GC (`bench/footprint` with `--jvm-flag`) was not run. The `idle` row is a file-serving node 3 s after its first request.
- Shared, noisy box: the 500 µs gate needs a CPU-isolated run (taskset/isolcpus, quiet machine).

## How to reproduce

```sh
mvn -B -q -DskipTests package
bench/gc/run.sh 3 g1 g1tuned zgc zgcpinned serial      # takes the bench lock per block, waits for load < 4, honours BLACKOUT
python3 bench/gc/summarize.py bench/gc/results/results.txt
flock /tmp/sjws-bench.lock bench/gc/alloc-profile.sh 1
```
