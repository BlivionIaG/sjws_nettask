# G=64 chunk p99 CPU attribution (report-only)

**Inceptor approved** this investigation **2026-10-30 18:18 Europe/Paris** — PR 8 blocker investigation.  
**Deliver/Ack durability stays unchanged** (inceptor). No code changes in this note; candidates need design approval. A later fix PR needs ABBA under #263 and a 64-group report-only window before/after.

| | Baseline window | #342 scratch |
|--|--|--|
| Tree | post-#335 @ `1603d96` (evidence commit `a9623d5`) | `22c9bda` (`a9623d5`+`99926d6`) |
| JFR | `gate-out/jfr-g64-attrib/attrib.jfr` (18 s) | `gate-out/jfr-g64-pr342/attrib.jfr` (37 s) |
| MiB/s / p50 / p99 | **37.3** / 11.4 ms / **29.6 ms** | 35.5 / 12.3 ms / **35.0 ms** |
| `ExecutionSample` | **1144** (enabled) | **1691** |
| `ObjectAllocationSample` | **2118** (enabled — **no re-run**) | **4108** |
| `VirtualThreadPinned` | **0 events** (not enabled in `attrib.jfc` — inconclusive) | 0 |

Primary numbers below from the **baseline** window unless noted; #342 shows the same shape.

---

## Verdict (dominant cause)

**Dominant cause: consumer-side `StreamHttp$Session.at` linear scan of the retained window under the per-session lock (#326 C1), on the GET/`Reader.next` path — not force waits, not CPU pin saturation.**

| Evidence | Number |
|--|--|
| **Self-time #1** | `Session.at:512` **9.7%** of ExecutionSamples (111/1144); same **9.7%** on #342 (164/1691) |
| Callers | **110/115** from `StreamHttp$Reader.next:876` (response write loop) |
| Inclusive HTTP | `serveRequest` **43%**, `SwarmHttp.handle` **32%**, `StreamHttp*` **35%**, `creditChunk` **17%** |
| Force wait on delivery | **0** `awaitDurable` parks (prior causality note) |
| Server CPU | **3.04 core-eq** avg (`server_cpu_pct_of_wall=303%`) on **6** pinned CPUs (2–7) → **~51% of pin** — **not saturated** |
| Loadgen | **1.95 core-eq** on **2** CPUs (0–1) → **~97% of pin**; `generator_saturated=true` |
| Credit stalls | **0** (`credit_wait.n=0`) |
| Monitor contention | Baseline 15 enters (noise); #342 257 mostly **classload** during warmup, not a hot session-lock storm |
| ~16 streams/group | Harness `streams_per_group_approx=15.625` is **task-group mapping**, not a StreamHttp group lock. Chunk path locks are **per-Session** (+ messaging `Partition` on produce). **No evidence that ~16 streams serialize on one C1 group lock.** |

p50≈11 ms / p99≈30 ms is end-to-end (producer `nanoTime` in payload → consumer read). With the loadgen saturated and the server only half-busy on its pin, the shape fits **queueing behind per-session reader work** (`at` + encode + write) and scheduling, not a fully pegged server.

---

## 1. CPU by thread group / saturation

Harness (reliable wall accounting):

| Side | pin | cpu% of wall | core-eq | vs pin |
|--|--|--|--|--|
| Server (ZGC jar) | CPUs **2–7** (6) | **303%** | **3.04** | **~51%** — **not saturated** |
| Loadgen (`StreamLoad`) | CPUs **0–1** (2) | **195%** | **1.95** | **~97%** — **saturated** (`generator_saturated=true`) |
| load1 | pre **0.72** → post **3.55** | | | |

`jdk.ThreadCPULoad` (sum over samples; same story): **FJP carriers** dominate user+sys; ledger-writer/syncer and ZGC are small. Mid jstack: **14 RUNNABLE / 275** threads; FJP workers mostly WAITING (VT parked). Ledger writers idle on queue (prior H1 refute).

---

## 2. Ranked self-time top 10 + allocation top 10

### Self-time top 10 (ExecutionSample leaf) — baseline

| Rank | Samples | % | Frame |
|--|--|--|--|
| 1 | **111** | **9.7%** | **`StreamHttp$Session.at:512`** (#326; was cited ~:473) |
| 2 | 58 | 5.1% | `ConcurrentHashMap.get:958` |
| 3 | 52 | 4.5% | `VirtualThread.submitRunContinuation:386` |
| 4 | 44 | 3.8% | `SwarmHttp.readFrame:464` |
| 5 | 25 | 2.2% | `VirtualThread.park:745` |
| 6 | 23 | 2.0% | `RecordBatch.encode:41` |
| 7 | 19 | 1.7% | `StreamHttp$Reader.next:876` |
| 8 | 18 | 1.6% | `ByteBuffer.array:1514` |
| 9 | 18 | 1.6% | `SwarmHttp.lease:328` |
| 10 | 17 | 1.5% | `MessageDigest.isEqual:514` |

**Inclusive (context):** `Http1Connection.serveRequest` 43%, `SwarmHttp.handle` 32%, `ObserveRequestFilter.filter` 27%, `SwarmHttp.lease` 25%, `Http1Writer.write` 19%, `StreamHttp.creditChunk` / `chunk` ~17%, `TokenStreams.admitAndWrite` ~16%, `Session.at` inclusive **10%** (115).

**Credit path:** present but not the self-time tip — credit-stack leaves are CHM/`drain`/`pushOffset`/`admitAndWrite`, not a single grant hotspot. `credit_wait` unused (n=0) in this window.

**`Session.at` body** (`StreamHttp.java:490–524`): `drain()` then lock; optional `trimRead`; **linear `for (var c : this.retained)`** until `chunkSeq == from`. `retainMax = sinkChunks` (default **256**, +3 slack with credit) — O(window) under lock on every `Reader.next` poll.

### Allocation top 10 by JFR sample weight — baseline

(`ObjectAllocationSample.weight`; rate-oriented, not exact bytes.)

| Rank | Weight (MB-w) | n | Site |
|--|--|--|--|
| 1 | 3541 | 316 | `SwarmHttp.readFrame:461` |
| 2 | 2024 | 129 | `Segment.recover:110` (startup/recover; less measure-hot) |
| 3 | 1372 | 116 | `Frame.<init>:66` |
| 4 | 1203 | 90 | `Frame.payload:71` |
| 5 | 1079 | 42 | `Http1Parser.headerLine:351` |
| 6 | 1073 | 98 | `StreamChunk.decode:180` |
| 7 | 933 | 83 | `Http1Connection.fill:288` |
| 8 | 830 | 77 | `Frame.decode:102` |
| 9 | 747 | 78 | `StreamChunk.encode:153` |
| 10 | 704 | 44 | `LedgerEntry$Enqueue.<init>:25` |

Types: **`byte[]` dominates** (~16 GB-w). Credit grant/alloc is not a top allocator; wire **Frame/StreamChunk encode–decode** and **readFrame** are.

#342: same self #1 (`Session.at` 9.7%); alloc similar with more `Segment.recover`/`materialize` weight (longer recording / opens).

---

## 3. Scheduling (VT pin / carriers / monitors)

| Signal | Result |
|--|--|
| `jdk.VirtualThreadPinned` | **Not in `attrib.jfc`** → 0 events **inconclusive** (no dedicated re-run; ObjectAllocationSample already present) |
| Carriers | FJP workers own most ThreadCPULoad; `VirtualThread.submitRunContinuation` is self #3 |
| Runnable VTs | Mid jstack shows few RUNNABLE platform threads; work is VT on FJP |
| `JavaMonitorEnter` | No sustained Session-lock contention profile; #342 spikes are classload |
| Group lock / ~16:1 | **Not supported** as the C1 serialization story — see verdict |

---

## 4. Queueing / e2e time split

Harness latency = **producer stamp → consumer recv** only (`StreamLoad` embeds `nanoTime` in chunk; reader subtracts). **No** server-side enqueue/dequeue timestamps in `result.json`.

Estimated split (code + JFR, not measured stages):

| Stage | Role in p99 |
|--|--|
| Producer POST | Sync one-in-flight per stream; loadgen pin saturated → producer queueing **outside** server |
| Server admit (`creditChunk` / `admitAndWrite` / `deliverLocal`) | On CPU samples; completes without durable wait for NONE |
| Consumer long-poll / wakeup | `Reader.next` → `Session.at` → `Wait` → `await(250)` — **at() is the measured self hotspot** |
| Response write | `Http1Writer.write` / `writeStream` inclusive ~19% |

**credit_wait = 0** → not credit-backoff dominated. p99 likely **producer pin queue + per-session reader `at`/write**, with force/IO as background load (prior note) rather than a wait edge on the chunk path.

---

## 5. Fix proposal (design approval — **do not implement here**)

Eventual separate PR: ABBA (#263) + 64-group report-only before/after. **Do not change Deliver/Ack durability.**

### F1 — C1: O(1)/O(log n) `Session.at` retained lookup (#326) — **primary**

| | |
|--|--|
| **What** | Replace linear scan of `retained` with a cursor / seq index (deque index or map), preserving Gap/Reset/LAST and `trimRead` under pressure |
| **Where** | `StreamHttp.java` `Session.at` **:490–524** (hot leaf today **:512**); `drain` / trim paths that mutate `retained` (~:359–420); `Reader.next` **:876** caller unchanged |
| **Expected effect** | Remove ~**10% self** and shorten lock hold on every reader poll; **best shot at moving p99** toward the 1.5 ms guard among swarm-only changes. Rough: if `at` + lock hold is a large fraction of the 11–30 ms e2e, expect **multi-ms** p50/p99 drop; not guaranteed alone if producer pin remains saturated |
| **Risk** | Stream-core concurrency + credit invariants (Architecture must approve — already filed as #326). Wrong index breaks gap/reset/trim |

### F2 — Cap / structure retained window for credit-on readers

| | |
|--|--|
| **What** | Keep `retainMax` tight to the live credit window; ensure trim keeps `retained.size()` small so even a scan is cheap; optional ring buffer keyed by seq |
| **Where** | `StreamHttp.java` session create **:661–663** (`sinkChunks` / `+ CREDIT_RETAIN_SLACK`); trim in `drain` **:407**; credit `trimRead` under pressure |
| **Expected effect** | Reduces `at` work **without** a full index if windows stay ≪ 256; smaller win than F1 if readers often sit mid-window |
| **Risk** | Too-aggressive trim → more `Gap`/`overflow` under slow consumers; credit budget interactions (#267) |

### F3 — Cheapen hot-path observe + wire alloc on stream chunk routes

| | |
|--|--|
| **What** | Skip or lighten `ObserveRequestFilter` on high-rate stream chunk POST/GET; reduce `Frame`/`StreamChunk` encode–decode churn (reuse buffers / slice) on `readFrame` + reader encode |
| **Where** | `ObserveRequestFilter.java:57` (inclusive **27%**); `SwarmHttp.readFrame:461` (alloc #1); `StreamChunk.encode/decode`, `Frame.*` |
| **Expected effect** | CPU headroom on FJP carriers; secondary p99 help. Unlikely to beat F1 alone |
| **Risk** | Metrics/cardinality loss if observe is skipped incorrectly; buffer reuse use-after-publish bugs |

**Out of scope here (per inceptor):** Deliver/Ack → NONE, syncer floor, SharedLedgerPool writer pooling (Messaging Core).

---

## Takeaway for #299

1. Investigation **inceptor-approved 2026-10-30 18:18**; blocker is **CPU/`Session.at`**, not force-wait.  
2. JFRs already had **ExecutionSample + ObjectAllocationSample** — no re-run.  
3. Ship path: design-approve **F1 (#326)** (and optionally F2/F3) → separate PR with ABBA + G=64 report-only.  
4. Deliver/Ack durability **unchanged**.
