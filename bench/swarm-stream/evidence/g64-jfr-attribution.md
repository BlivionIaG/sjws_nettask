# G=64 JFR attribution vs #337 §14 H1–H6 (report-only)

**Not a counted §13 window.** Stay draft on #299. Tree `1603d96` (master+#335). Artifacts: `gate-out/jfr-g64-attrib/` on the bench host; compact copies under `bench/swarm-stream/evidence/g64-jfr-attrib-artifacts/`. Recording ~18 s (`attrib.jfr` 5.4 MiB). Wall clock: 2026-10-30 ~17:06 Europe/Paris (CET).

---

## H1 REFUTED — runnable writer fan-out does **not** dominate p99

| Signal | Value |
|--|--|
| `ledger-writer-*` mid jstack | **241 WAITING / 0 RUNNABLE** |
| `ledger-writer-*` late jstack | **241 WAITING / 0 RUNNABLE** |
| Writer wait site (mid+late) | **241/241** on `ArrayBlockingQueue.take` → `LedgerWriter.run:380` (idle for work) |
| Writer `ThreadCPULoad` | avg **0.33% user / 0.55% sys** across 737 samples |
| Exec samples in `force`/`FileChannel`/`Segment.force` | **3 / 1144** (`Segment.force`); force/write native category **37** frames |
| Native `force0` | **120 / 881**, **all** on `ledger-syncer-*` |
| ContextSwitchRate | avg **83 289**/s (high) — but **not** from runnable writers |

§14 H1 confirm needed “many `ledger-writer-*` runnable; CPU in scheduler”. We have the opposite: writers are parked empty-queue, CPU is elsewhere (stream HTTP + syncer `FileForce`). **Implication for Messaging Core (already on `feat/shared-ledger-pool`):** do **not** expect pool-v1 alone to clear the 1.5 ms guard by cutting runnable-writer scheduling. Pool remains justified for **thread/heap at high group counts** (see H4); p99 looks **I/O / syncer-force** bound (H2/H4).

---

## Window numbers (vs abs-01 baseline)

| | abs-01 (pre-#335 merge window) | this JFR window (post-#335 @ 1603d96) |
|--|--|--|
| groups / streams | 64 / 1000 | 64 / 1000 |
| MiB/s | 29.64 | **37.29** |
| p50 / p99 | — / **169.0 ms** | 11.4 ms / **29.6 ms** |
| writer_count | 774 | **625** (1108 `group_open_refusals` / claim 503) |
| heap | -Xmx2g | -Xmx2g; ZHeap used **1048M**/2048M after GC |
| ZAllocationStall | 0 | **0** |
| load1 pre→post | — | 0.72 → 3.55 |
| §13 guard | STOP (p99≫1.5 ms) | report-only; still **p99 ≫ 1.5 ms** |

Throughput up, p99 down ~5.7× vs abs-01, still ~20× over the 1.5 ms guard.

---

## H2–H6 (each with numbers)

### H2 — Per-write unpark / syncer flooring — **CONFIRM** (force/flooring; unpark itself cheap)

| Signal | Value |
|--|--|
| `jdk.FileForce` | **23 918** events in ~18 s (~**1 329**/s); Σ duration **12 629 ms**; p50 **0.274 ms**, p99 **3.78 ms**, max **28.8 ms** |
| FileForce threads | **23 882** on `ledger-syncer-*`; caller `Segment.force` |
| Syncer parks | n=**26 560**, Σ **120 405 ms**; parkedClass includes `SharedLedgerPool$Shard` |
| Exec `unparkSyncer` | **1 / 1144** (unpark not a CPU hotspot) |
| Syncer census | **6** threads (5 TIMED_WAITING / 1 counted RUNNABLE at mid — stacks show `syncLoop` parkNanos) |

Refute column (“writers runnable without syncer parks”) fails: writers idle, syncers park+force heavily. Flooring / fsync rate can alone exceed the 1.5 ms guard (FileForce p99 3.78 ms).

### H3 — `Partition` lock / encode dominates — **INCONCLUSIVE** (present, not dominant)

| Signal | Value |
|--|--|
| Exec stacks with `Partition.append*` | **130 / 1144 (~11%)** |
| `RecordBatch` / `encode` | 36 / 64 |
| Dominant exec stacks | `Http1Connection.*` **588**, `StreamHttp` **400**, `TokenStreams` **305** |

Some messaging-lock samples, but stream/HTTP path owns the CPU sample pie. Pool alone would not remove that 11%; neither is it the main story.

### H4 — Disk / `FileChannel.write` / force dominates — **CONFIRM**

| Signal | Value |
|--|--|
| NativeMethodSample force | **120** (100% syncer); write **96**; other **665** (accept/poll heavy) |
| FileForce wall | Σ **12.6 s** across ~18 s recording (multi-thread) |
| FileForce p99 | **3.78 ms** (already > 1.5 ms guard); max **28.8 ms** ≈ window p99 scale |
| Device util | not collected |

§14 implication if confirmed: pool still needed for thread/heap at 1k groups; **p99 needs batching and/or device**, not just fewer writer threads.

### H5 — GC / allocation primary — **REFUTE**

| Signal | Value |
|--|--|
| `jdk.ZAllocationStall` | **0** |
| ZGC `longestPause` max | **0.038 ms** (27 cycles); `sumOfPauses` max **0.11 ms** |
| GC cycle wall (concurrent) | up to ~584 ms ZGC Major — not STW |
| Alloc samples | top `SwarmHttp.lease` / `readFrame` / `handle` (stream), not ledger buffer blow-up |

### H6 — Hash imbalance after pooling — **INCONCLUSIVE**

Pre-pool / syncer-only `SharedLedgerPool` window. No XXH64 writer-shard occupancy (D2) to score. Defer to post-`feat/shared-ledger-pool` 1k sweep max/mean occupancy.

---

## Census snapshot (mid ≈ +10 s)

| Thread class | Count | States |
|--|--|--|
| `ledger-writer-*` | 241 | WAITING 241 |
| `ledger-syncer-*` | 6 | TIMED_WAITING 5, RUNNABLE 1 |
| JVM total (jstack) | 275 | WAITING 247, RUNNABLE 14, TIMED_WAITING 14 |

`writer_count` from harness amend = **625** (includes writers that existed over the run; mid snapshot is live set after 503 refusals / close).

---

## JFR event counts (attrib.jfr)

| Event | Count |
|--|--|
| ThreadPark | 125 711 |
| FileForce | 23 918 |
| ThreadCPULoad | 2 575 |
| ObjectAllocationSample | 2 118 |
| ExecutionSample | 1 144 |
| NativeMethodSample | 881 |
| GCPhasePause | 85 |
| GarbageCollection | 27 |
| ThreadContextSwitchRate | 18 |
| ZAllocationStall | **0** |
| FileWrite | 12 |

---

## Takeaway for #337 / Messaging Core

1. **H1 contradicted** — stop treating runnable-writer fan-out as the p99 root cause on this shape.
2. **H2+H4 confirmed** — syncer `Segment.force` / FileForce rate and latency explain guard failure; batching / flooring / device matter for 1.5 ms.
3. **Pool v1 still warranted** for collapsing hundreds of idle `ledger-writer` platform threads and heap at high G (H4 implication; 625 writers here, thousands at G=1k historically) — but ship expectations should separate **thread/heap win** from **p99 win**.
4. No further counted §13 window from this report; #299 stays draft.

