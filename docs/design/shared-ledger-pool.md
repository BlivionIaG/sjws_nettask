# SharedLedgerPool for NONE/ASYNC ledger writers

> **Status note (2026-11-01):** The 2026-10-30 ~18:20 inceptor correction — recorded on
> [PR #339](https://github.com/BlivionIaG/sjws_nettask/pull/339) (“thread and heap win at high
> group counts only… **not** the [#299](https://github.com/BlivionIaG/sjws_nettask/pull/299)
> blocker; syncer path first”) — superseded this doc’s earlier “pool as PR-8 / #299 blocker”
> framing. Implementation [PR #339](https://github.com/BlivionIaG/sjws_nettask/pull/339) is
> **parked** (branch kept;
> [comment](https://github.com/BlivionIaG/sjws_nettask/pull/339#issuecomment-6553962246)).
> G=64 JFR ([#337 comment](https://github.com/BlivionIaG/sjws_nettask/pull/337#issuecomment-6527508238))
> **refuted H1** (writers idle; p99 bound by force/fsync), so the ledger pool is not the
> swarm-stream p99 lever for the #299 gate.


Status: **design only** (2026-10-30 Europe/Paris). Tracks [#336](https://github.com/BlivionIaG/sjws_nettask/issues/336).
Blocks S2c PR 8 / [#299](https://github.com/BlivionIaG/sjws_nettask/pull/299) after [#335](https://github.com/BlivionIaG/sjws_nettask/pull/335).
Depends on: #333 (eager heap), #335 (lazy queue + fail-closed budget), #331 / #329 (shared **syncers** already).
No Java changes in this PR.

**inceptor ruled on §17 of [#337](https://github.com/BlivionIaG/sjws_nettask/pull/337) on 2026-10-30** (see [Decisions](#decisions-inceptor-2026-10-30)); open questions are closed.

## Decisions (inceptor 2026-10-30)

| # | Ruling |
|--|--|
| D1 | `sharedWriterThreads` defaults to `max(2, min(availableProcessors(), 16))`, stays configurable, and the resolved shard count is **fixed for the process lifetime**. |
| D2 | Sticky shard = `Long.remainderUnsigned(XXH64(utf8(realPath)), N)` over the **canonical real path** of the log dir. Never `String.hashCode` of a raw path. Explicit pinning is a later option if the sweep shows skew. The **1k-group sweep must report max/mean shard occupancy**. |
| D3 | **No** aggregate shard-queue 503 in v1, on two conditions: (a) a per-shard queue-depth metric that costs nothing when metrics are off; (b) a test that one saturated partition cannot delay another on the same shard past its soft capacity bound. If the gate shows cross-partition HOL p99, the aggregate limit returns. |
| D4 | **One process-wide pool**, created lazily on the first NONE/ASYNC open. Messaging off ⇒ **0** writer-pool threads (ClassLoad / thread-count test). Pool closes at shutdown and drains its shards. |
| D5 | Rename syncer property to **`sjws.messaging.ledger.sharedSyncers`**. Keep `sharedWriters` as a **deprecated alias for one release** with a startup warning and a docs note. |
| D6 | GROUP and EACH stay dedicated. |
| D7 | Required tests / merge gates: FIFO-per-partition; refused batch never fences its shard; lifecycle lock vs close race; produce ABBA under #263; 16/64/256/1k heap+thread+stall sweep; Security + Agent Swarm review. |

## 1. Goals and non-goals

### Goals

1. Cap **writer platform threads** for `Durability.NONE` and `Durability.ASYNC` partitions at a small, configurable N (default: `max(2, min(CPUs, 16))` per D1), independent of group / partition count.
2. Preserve **per-partition append order** (FIFO) and the existing `PartitionAppender` / `awaitCapacity` → `appendNoWait` contract.
3. Keep the **fail-closed** `LedgerWriterBudget` (#335): refusals → `LedgerBudgetExhaustedException` → HTTP **503** with scrubbed detail and `Retry-After`; never OOM.
4. Isolate failures: one partition's IO error or budget refusal must **not** fence other partitions that share a writer shard (Security #335 B1; D7).
5. Leave **GROUP / EACH** on dedicated per-partition writer threads (D6; there is no `Durability.SYNC` enum value).
6. Unblock the #299 §13 gate at G=64 (1k streams): today p99 ≈ 169 ms with 774 writers and 0 `ZAllocationStall`.
7. Zero cost when messaging is off: lazy pool, 0 writer-pool threads until first NONE/ASYNC open (D4).

### Non-goals

- Changing on-disk `RecordBatch` / segment format, replication, or Raft.
- Sharing `SegmentedLog` files across partitions (one log directory per partition stays).
- Moving GROUP/EACH onto the pool in v1 (D6).
- Replacing the shared **syncer** pool (#331); writers and syncers stay separate roles.
- Aggregate shard-queue 503 in v1 (D3); may return if HOL shows up on the gate.
- Explicit shard pinning in v1 (D2); only if the 1k sweep shows occupancy skew.
- Retuning `-Xmx` / `ledger.budget_heap_fraction` for the #299 gate (heap flags stay as recorded).
- Implementation in this PR (design + issue only).

## 2. Today's ownership (master + #335 shape)

| Piece | Owner | Thread / memory |
|--|--|--|
| `SegmentedLog` | one per partition / topic | file channels; no dedicated thread |
| `LedgerWriter` | one per `Partition.open` / `LogTopics` open | **1 platform writer thread** always; queue `2×65536` + 1 MiB direct (eager on master; lazy under #335) |
| Syncer | dedicated **or** `SharedLedgerPool` shard | shared syncers by default (`sjws.messaging.ledger.sharedWriters` today; **renamed** to `sharedSyncers` per D5); `pinged` idle spin (#329); `durable` volatile (#331) |
| `LedgerWriterBudget` | process-wide (#335) | idle 64 KiB / writer; materialize `2×cap×8 + 1 MiB`; `acquireExtra` on direct growth; release on close under `materializeLock` |

Call sites that construct writers:

- `Partition.open(...)` → `new LedgerWriter(log, durability)` (task + DLQ partitions; swarm scale driver).
- `LogTopics` first produce → `new LedgerWriter(log, durability(topic))`.

`SharedLedgerPool` **today shares only syncers**, not writers. The historical property name `sharedWriters` meant syncer count; D5 renames it to `sharedSyncers` and adds `sharedWriterThreads` for the writer pool (see §15).

Close / fence today: any `IOException` / `RuntimeException` in `writeGroup` sets `failure=` and fences the writer; #335's Security B1 requires a **budget growth refusal** to fail only that batch (disk untouched). The pool design must not re-introduce cross-partition fencing (D7).

## 3. Pool model

### 3.1 Shape

```
                    producers (Partition / LogTopics)
                              │
              append / awaitCapacity / appendNoWait
                              │
              ┌───────────────┴────────────────┐
              │  per-partition LedgerWriter     │  (handle object; no dedicated
              │  handle: ordering, snapshot,    │   writer thread for NONE/ASYNC)
              │  fsync waiters, durability)     │
              └───────────────┬────────────────┘
                              │ offer(Pending) into shard inbox
              ┌───────────────┴────────────────┐
              │  SharedLedgerPool writer shard  │  × N (D1 default)
              │  one platform thread            │
              │  drains sticky partition queues │
              │  one direct ByteBuffer          │
              └───────────────┬────────────────┘
                              │ write → SegmentedLog.appendFrames
              ┌───────────────┴────────────────┐
              │  SharedLedgerPool syncer shard  │  × M (sharedSyncers)
              │  sharedSyncSlice / force        │
              └────────────────────────────────┘
```

- **One process-wide pool** (D4), created lazily on the first NONE/ASYNC `LedgerWriter` open. Messaging off ⇒ the class may load for compile references, but **no writer-pool threads start** (ClassLoad / thread-count test). At process shutdown the pool closes and **drains** every shard (flush pending Pendings / force remaining durable per handle, then stop threads).
- **N writer shards**, default `max(2, min(availableProcessors(), 16))` (D1), property `sjws.messaging.ledger.sharedWriterThreads`. Resolved once at pool construction; **fixed for the process lifetime** (changing the property afterward is ignored; document that a restart is required).
- Sticky registration (D2): see §3.4. Least-loaded placement is rejected for writers: rebalancing would reorder a partition across shards.
- GROUP/EACH constructors **skip** writer-pool registration and start a dedicated writer thread exactly as today (D6); syncer registration uses `sharedSyncers` (D5).

### 3.2 Per-partition ordering (FIFO)

Each pooled writer keeps a small **per-partition MPSC inbox** (or a single shard queue of `(writer, Pending)` with a per-writer serial bit). Rules:

1. Appends for partition P are always drained **FIFO** on the shard that owns P (D7).
2. The shard thread may interleave drain work across partitions (encode A then B as separate `write` calls in one loop). They never share a `SegmentedLog`, so there is no cross-log `appendFrames`.
3. Within one partition, grouping (`lingerNanos`, `maxGroupBytes`, `drainTo`) stays identical to `LedgerWriter.run` / `commit` / `writeGroup`.
4. `behind` / soft capacity remain **per-partition** (D3): producers of a hot partition throttle; a cold neighbour on the same shard is not falsely marked behind, and must not be delayed past its own soft capacity solely because the hot neighbour is saturated (test T10).

Invariant (test T1): two threads appending to the same partition with monotonic payloads observe strictly increasing offsets matching enqueue order; a mutant that drains two partitions' queues round-robin *inside* one partition's Pending list must fail.

### 3.3 Batching and zero-copy

- Keep `RecordBatch.encode` into a **shard-owned** direct `ByteBuffer` (one per writer shard, grown via `acquireExtra` like #335 B3).
- Do **not** copy producer `byte[]` payloads; `Pending.records` stay as today (`List.copyOf` of the caller's arrays).
- Cross-partition: one drain iteration may `write(gA)` then `write(gB)` without returning to park. No merging of frames across logs.
- Linger stays per-writer config; the shard applies each writer's `lingerNanos` / `maxGroupBytes` when draining that writer's inbox only.
- Fairness under D3: the drain loop must not run unbounded work for one saturated partition while another has Pending past its soft bound — e.g. round-robin across non-empty per-partition inboxes each slice, or a max-bytes-per-slice cap per partition before switching. The exact scheduler is an implementation choice; T10 locks the observable contract.

### 3.4 Sticky shard selection (XXH64 of real path)

Per D2:

1. Resolve `log.dir()` to its **canonical real path**: `dir.toAbsolutePath().normalize()` then `toRealPath()` when the directory exists (open path already created the dir). Store that `Path` (or its UTF-8 string) on the handle for the life of the open. Symlink aliases of the same directory must map to the same shard.
2. Hash with **XXH64** (xxHash 64-bit, official XXH64 algorithm, seed `0`) over the UTF-8 bytes of that real-path string (platform-independent separators after `Path` normalization — use the path's `toString` after real-path resolution on the local FS, documented as "real path UTF-8").
3. Shard index = `Long.remainderUnsigned(xxh64, N)`.
4. **Forbidden:** `String.hashCode`, `System.identityHashCode`, hashing a non-canonical / relative / non-real path, or any hash that depends on `System.identityHashCode` of the `Path` object.
5. Tests may inject a shard index. Explicit pinning API is **out of v1**; revisit only if the 1k-group sweep's max/mean occupancy ratio shows skew (D2).

Why XXH64: named, stable across processes and JDK versions, fast on short path strings, widely used for sharding (including Redpanda-adjacent storage stacks). Implement as a small in-tree XXH64 (or a vetted dependency already on the classpath); the design freezes the algorithm name and seed so occupancy stays reproducible across releases.

## 4. Budget charging (pool vs per-writer)

Keep `LedgerWriterBudget` as the single process-wide ledger. Change **what** is charged:

| Charge | When | Who |
|--|--|--|
| Idle handle | NONE/ASYNC writer construct | `IDLE_COST_BYTES` (64 KiB) per partition handle — object + small inbox metadata, **no** thread |
| Shard materialize | first Pending on a writer shard | one queue-equivalent + 1 MiB direct, charged **once per shard** via `acquireMaterialize` / a new `acquireShard` helper |
| Direct growth | shard buffer grows past 1 MiB | `acquireExtra`; roll back if `allocateDirect` fails (#335 N2) |
| GROUP/EACH writer | construct / materialize | **unchanged** full per-writer charge (dedicated thread + queue + direct) |
| Release | partition close / shard retire / pool shutdown drain | handle idle on unregister; shard charge when last member leaves and buffers are freed |

Consequences at swarm scale (`-Xmx2g`, default 0.25× ≈ 512 MiB budget):

- Today after #335: ~120 groups open then 503s (1926 refusals at 1k groups) because each open still charges idle and, on first append, a full queue.
- With pool: idle handles are cheap; materialize cost is **O(N shards)** not O(writers).

Fail-closed stays: any path that would allocate queue slots or direct memory without a successful `acquire*` is a bug (Security review item).

## 5. Backpressure and 503

| Condition | Behaviour |
|--|--|
| Per-partition soft capacity | `awaitCapacity` parks the producer (unchanged); shard may still be draining other partitions |
| Aggregate shard-queue bound | **Not in v1** (D3). Revisit only if the gate shows cross-partition HOL p99. |
| Per-shard queue-depth metric | Required (D3): expose depth (and optionally age) per writer shard; **zero cost when metrics are off** (no sampling, no atomics on the offer path beyond what drain already needs — or a plain `long` written by the shard thread and read without forcing metrics on). |
| Budget refuse (idle / materialize / extra) | `LedgerBudgetExhaustedException` → messaging/swarm HTTP **503**, scrubbed detail (#246 / #335 B2), `Retry-After` |
| Growth refuse mid-writeGroup | fail **that batch only**; do not set `failure=`; next small append succeeds; **must not fence the shard** (D7 / Security #335 B1) |

## 6. Close and reopen (lessons from #335 B1)

Under one lock per handle (`materializeLock` or rename to `lifecycleLock`); tested against the close race (D7 / T4):

1. `close()` sets `closed=true`, snapshots whether the handle had registered / materialized, and enqueues poison **only if** a shard is draining this handle.
2. Concurrent first-append registration / materialize under the same lock **refuses** when `closed` (no thread start, no charge).
3. Unregister from the writer shard; if the shard member set goes empty, the shard thread may retire (same start/stop discipline as syncer `#219` / `#329` races). With a process-lifetime pool (D4), empty shards may keep their thread parked rather than exit — either is fine so long as shutdown drain stops them.
4. Drain / force remaining durable bytes for this partition only, then `budget.release` exactly once.
5. Reopen = new `LedgerWriter` / new registration; no reuse of a fenced handle.
6. **Pool shutdown** (D4): mark pool closing, refuse new registrations, drain every shard (complete or exceptionally-complete remaining Pendings after force where required), join writer threads, release shard budget charges.

Mutation proof: hammer close ∥ first-append; mutant that starts shard work or takes a charge after `closed=true` must fail.

## 7. Failure isolation

| Event | Scope |
|--|--|
| `SegmentedLog` IO error while writing P | set `failure` on **P only**; complete P's group + P's fsync waiters; other partitions on the shard continue |
| Budget refuse for P's growth / P's first materialize | exceptional complete of **that** Pending / append future only; **shard stays up** (D7) |
| Shard thread crash (uncaught) | treat as process bug; log and fail members currently being drained; tests assert the handler is installed |
| Syncer force error on P | today's `sharedSyncSlice` already sets `failure` on that writer only |

Hard rule for Security: **no** shard-level `failure` flag that rejects appends for every member. A refused batch never fences its shard (D7).

## 8. Why GROUP/EACH stay dedicated (D6)

The codebase has no `Durability.SYNC`; fsync-waiting modes are **GROUP** and **EACH** (`waitsForFsync()`). Swarm / streaming defaults to NONE/ASYNC. Keeping GROUP/EACH on a dedicated writer thread means:

- Inline `forceInline()` after write stays on the same thread that called `write`, identical to today.
- No risk that a pooled shard parks in `log.force()` for GROUP partition A while NONE partition B's appends queue behind it on the same thread.
- Proof (T5): existing `LedgerCrashTest` / durability tests for GROUP/EACH run unchanged; a characterization test asserts GROUP/EACH still have a dedicated writer thread and `sharedWriterShard < 0`.

## 9. Expected thread, heap, and p99 math

Assumptions: `pool.shards=4`, ~12–16 writers/group as measured on #299, box CPUs = 8 ⇒ writer pool N = `max(2, min(8, 16))` = **8** (D1), syncer pool M from `sharedSyncers` (default still CPU-sized unless configured), #335 lazy idle = 64 KiB/handle.

### 9.1 Threads (platform)

| G | writers today | writer threads today | writer threads pooled | syncer threads (shared) | total ledger threads pooled |
|--|--|--|--|--|--|
| 16 | 262 | 262 | **8** | ≤8 | ≤16 |
| 64 | 774 | 774 | **8** | ≤8 | ≤16 |
| 256 | 2014 | 2014 (OOM path) | **8** | ≤8 | ≤16 |
| 1000 | 2674 | 2674 (OOM path) | **8** | ≤8 | ≤16 |

On a 32-CPU box, D1 caps writer threads at **16** (not 32). On a 1-CPU box, floor is **2**. GROUP/EACH dedicated writers add +1 each (expected ≈ 0 on the swarm gate). Messaging off: **0** writer-pool threads (D4 / T11).

### 9.2 Heap / budget (order of magnitude, `-Xmx2g`)

| G | #335 idle charge (64 KiB×writers) | + materialize if all hot | pooled: idle handles + N shard buffers |
|--|--|--|--|
| 16 | ~16 MiB | + hundreds of MiB if every writer materializes | ~16 MiB + ~16–32 MiB shards |
| 64 | ~48 MiB | dominates budget → early 503s when hot | ~48 MiB + shards |
| 256 / 1k | fits idle after #335; materialize / open still 503-bound | | idle scales with handles; **materialize does not** |

Prediction: 1k groups stay open under default 0.25×heap for NONE/ASYNC swarm traffic; `group_open_refusals` drop from ~1926 toward 0 unless a separate group-admission cap is added.

### 9.3 p99

#299 abs-01: 29.6 MiB/s, p99 169 ms, 774 writers, 0 ZAllocationStall. If scheduling / thread fan-out dominates, pooled N=8 should move p99 toward the **1.5 ms** guard. If disk or lock hold times dominate, p99 stays high and the JFR table (§14) tells us which.

## 10. Test plan (with mutation proofs)

| # | Test | Mutant that must fail / note |
|--|--|--|
| T1 | **FIFO-per-partition** under concurrent appenders on one pooled shard (D7) | drain ignores per-partition FIFO |
| T2 | Two partitions on one shard: IO fault on A; B's next append succeeds | shard-level `failure=` |
| T3 | Budget / growth refuse on large append; next small append succeeds; **shard not fenced** (D7) | `failure=` on refuse or shard-wide reject |
| T4 | **Lifecycle lock vs close race**: close ∥ first-append; no charge / no shard work after close (D7) | charge after `closed` |
| T5 | GROUP/EACH still dedicated; crash/durability suites green (D6) | GROUP registered on writer pool |
| T6 | Syncer registration via `sharedSyncers`; `sharedWriters` alias warns once; `durableNow` order (#331) | alias silent forever / durable before force |
| T7 | `sharedWriterThreads=0` / `false` → dedicated writers (legacy) | always pool |
| T8 | Budget release once on close; shard buffer released when last member leaves / on shutdown drain | double-free or leak (`chargedEnd≠0`) |
| T9 | Stress: 2k open/close cycles, 0 thread leaks, `writers()` and `charged()` return to baseline | unregister skip |
| T10 | **No cross-partition soft-cap delay** (D3): partition A saturated on a shard; partition B on the same shard still observes `awaitCapacity` / append latency bounded by B's own soft capacity (not A's backlog). | drain always prefers A until empty |
| T11 | **Messaging off ⇒ 0 writer-pool threads** (D4): ClassLoad / thread-count test; pool absent or unstarted | threads start at class init |
| T12 | Pool shutdown drains shards (D4): pending appends complete or fail cleanly; threads join; no budget leak | shutdown drops Pendings without drain |
| T13 | XXH64 sticky: two opens of the same real path (different raw `Path` strings / symlink) land on the same shard; `String.hashCode` path is not used | hash of raw relative path |

## 11. Bench plan

Hygiene: `flock /tmp/sjws-bench.lock`, builds `nice -n 10`, start only when load1 < 4 (prefer < 3 for produce hot path), record load1. **Merge requires** the produce ABBA and the group sweep below, plus Security and Agent Swarm review (D7).

### 11.1 Produce-path ABBA (#263 rule) — merge gate

- Harness: existing messaging produce / `RaftPublishBench` style used on #335.
- Alternating blocks base↔PR (ABBA), ≥3 valid blocks, cap 6.
- **FAIL** only if one-sided paired sign-flip on `log(p99_PR / p99_base)` has **p < 0.05** **and** geomean **> 1.10**.
- Block invalid when base pooled p99 ≥ 500 µs.
- Report G1 (`|t|<2` and delta ≤ larger sd) as info; inceptor FAIL gate is the #263 rule above.

### 11.2 Report-only group sweep (16 / 64 / 256 / 1k) — merge gate

At `-Xms2g -Xmx2g`, `shards=4`, same harness flags as #299. Record heap, **writer/thread counts**, and **stalls** (ZAllocationStall and any harness stall counters):

| Metric | 16 | 64 | 256 | 1000 |
|--|--|--|--|--|
| writers (handles) | | | | |
| writer platform threads | | | | |
| syncer threads | | | | |
| heap after GC | | | | |
| budget charged / refusals | | | | |
| MiB/s, p99 | | | | |
| ZAllocationStall / other stalls | | | | |
| **shard occupancy max / mean** (D2; required at least at **1000**) | | | | |
| per-shard queue-depth peak (metric from D3) | | | | |
| OOM? | | | | |

Compare to Agent Swarm's pre-pool table on #336. No threshold retune in v1. If max/mean occupancy skew is large, file a follow-up for explicit pinning (D2). If cross-partition HOL p99 appears, bring back the aggregate shard-queue limit (D3).

### 11.3 Optional micro-measure

If the bench lock is free and load1 < 3: W synthetic partitions on 1 vs N pooled threads, fixed append rate, report threads and p99. Skip when #335 or #299 holds the lock.

## 12. Risks

| Risk | Mitigation |
|--|--|
| Head-of-line blocking: GROUP force on a pooled thread stalls NONE neighbours | GROUP/EACH dedicated (D6) |
| NONE/NONE HOL on one shard | T10 + per-shard depth metric (D3); aggregate 503 returns if gate shows it |
| Sticky hash imbalance | XXH64 of real path (D2); report max/mean occupancy at 1k; pinning later if skewed |
| Property rename breakage | `sharedSyncers` + deprecated `sharedWriters` alias + startup warning for one release (D5) |
| Double budget accounting | single `LedgerWriterBudget`; T8 |
| Close / unregister races | lifecycle lock; T4 / T9 |
| Produce p99 regression | ABBA merge gate (D7) |
| Threads when messaging off | lazy pool; T11 |
| JFR shows disk, not threads, dominate p99 | pool still needed for thread/heap at 1k groups; p99 follow-up may be linger / fsync floor / batching |

## 13. Alternatives considered

### 13.1 Status quo + #335 only

Lazy queues stop OOM but leave **O(writers)** threads. #299 G=64: 774 threads, p99 169 ms, 0 ZAllocationStall. Rejected as the PR 8 fix; kept as prerequisite.

### 13.2 Kafka-style: shared request threads, per-partition lock

Kafka's `UnifiedLog.append` runs under a per-partition `synchronized(lock)` on broker request-handler threads ([Kafka storage engine notes](https://krivaltsevich.com/kafka-internals/03-storage-log-engine.html); [`LogManager`](https://github.com/a0x8o/kafka/blob/master/core/src/main/scala/kafka/log/LogManager.scala)). One slow partition can pin many handler threads ([KAFKA-12838](https://issues.apache.org/jira/browse/KAFKA-12838)). We already have a per-partition queue and want the opposite: **at most one thread working a partition**, with a hard cap on threads. Rejected as the primary model; the sticky shard queue is closer to the mitigation sketched in that JIRA.

### 13.3 Redpanda / Seastar: thread-per-core, partition owned by one shard

Redpanda pins one reactor per core; each partition lives on exactly one shard; appends are message-passed to the owning core ([How Redpanda Works](https://docs.redpanda.com/current/get-started/architecture); [TPC buffers](https://www.redpanda.com/blog/tpc-buffers); [InfoQ Seastar talk](https://www.infoq.com/presentations/high-performance-asynchronous3/)). **Closest prior art** to this design: sticky hash → N shards, shard-local buffers, no cross-shard locks on the write path. We do not adopt full Seastar shared-nothing memory (JVM); we only borrow the ownership rule. XXH64 matches the named-hash discipline common in that ecosystem.

### 13.4 NATS JetStream filestore

JetStream moved away from a single shared write buffer to per-message-block write-through caches ([nats-server#1675](https://github.com/nats-io/nats-server/pull/1675); [`filestore.go`](https://github.com/nats-io/nats-server/blob/main/server/filestore.go)), bounds global disk ops with `max_concurrent_io` ([JetStream config](https://docs.nats.io/reference/config/jetstream/)), and reuses a shared parallel task queue across accounts so goroutine count does not explode ([`jetstream.go`](https://github.com/nats-io/nats-server/blob/master/server/jetstream.go)). Lesson taken: **bound IO concurrency** and avoid one global write mutex; our shard count is that bound. We do not adopt per-block caches in v1.

### 13.5 One JVM virtual thread per partition

Cheap stacks, but wake/park storms and scheduling still scale with partition count. Rejected for the drain path.

### 13.6 Multiplex syncers only (already shipped)

#331. Necessary, not sufficient.

### 13.7 Aggregate shard-queue 503 in v1

Rejected by D3 unless HOL appears on the gate; replaced by the depth metric + T10.

### 13.8 Keep property name `sharedWriters` for syncers

Rejected by D5; rename to `sharedSyncers` with a one-release deprecated alias.

## 14. Hypothesis table for Agent Swarm's JFR (64-group window)

After #335 merges, Agent Swarm will attribute p99 on a 64-group / 1k-stream window. Design predictions:

| ID | Hypothesis | JFR / signals that **confirm** | Signals that **refute** | Design implication if confirmed |
|--|--|--|--|--|
| H1 | Runnable writer count / context switches dominate p99 | high `JavaThreadPark`, `ContextSwitch`, many `ledger-writer-*` runnable; CPU in scheduler | CPU in `force` / `write` / `appendFrames`; few runnables | **Pool v1 is the right fix** for the guard |
| H2 | Per-write unpark / syncer flooring dominates | stacks in `unparkSyncer`, `LockSupport.park` on syncer; fsync interval binding | writers runnable without syncer parks | tune flooring / batch unpark; pool optional for p99 |
| H3 | `Partition` lock hold / encode path dominates | samples in `Partition.append*` / `RecordBatch.encode` under lock | little time under Partition lock | messaging lock refactor; pool alone won't hit 1.5 ms |
| H4 | Disk / `FileChannel.write` / page cache dominates | `NativeMethod` in write/force; high `java.io` time; device util | CPU-bound Java stacks | pool still needed for thread/heap at 1k groups; p99 needs batching or device |
| H5 | GC / allocation (despite 0 ZAllocationStall on abs-01) | allocation pressure, minor stalls under finer events | still 0 stalls; TLAB quiet | revisit #335 buffer growth; not primary |
| H6 | Hash imbalance would hurt after pooling | (post-pool) one shard CPU ≫ others; depth / occupancy skew | even shard CPU; max/mean occupancy ≈ 1 | sticky hash rethink / explicit pinning (D2) |

The implementation PR's report must map the received JFR summary onto H1–H6 explicitly.

## 15. Config sketch (implementation PR)

| Property | Meaning | Default |
|--|--|--|
| `sjws.messaging.ledger.sharedSyncers` | **syncer** shard count (D5; new name) | same resolution as today's syncer pool: unset/`auto`/`true` → CPU count; `0`/`false`/`off` → dedicated syncers; or a positive int |
| `sjws.messaging.ledger.sharedWriters` | **deprecated alias** of `sharedSyncers` for one release (D5) | if set and `sharedSyncers` unset, apply the value and emit a **one-time startup warning**; if both set and disagree, prefer `sharedSyncers` and warn |
| `sjws.messaging.ledger.sharedWriterThreads` | **writer** shard count for NONE/ASYNC (D1) | `max(2, min(availableProcessors(), 16))`; `0`/`false`/`off` → dedicated writers (legacy); positive int overrides; resolved once at first NONE/ASYNC open and **fixed for the process lifetime** |
| `sjws.messaging.ledger.budget.*` | unchanged from #335 | 0.25× max heap |

YAML under `ledger:` mirrors the properties (same pattern as #335). Docs note (D5): `sharedWriters` is deprecated; new configs must use `sharedSyncers`.

## 16. Rollout

1. Land #335 (budget + lazy + non-fencing refuse).
2. Land this design (docs) after inceptor approval of the Decisions above.
3. Implementation PR referencing #336: pool writers for NONE/ASYNC; tests T1–T13; ABBA; 16/64/256/1k heap+thread+stall sweep with max/mean occupancy; Security + Agent Swarm review (D7).
4. Re-run #299 G=64 counted window; only then claim the PR 8 blocker cleared.

## 17. References

- Issue [#336](https://github.com/BlivionIaG/sjws_nettask/issues/336) (this work), [#333](https://github.com/BlivionIaG/sjws_nettask/issues/333), PR [#335](https://github.com/BlivionIaG/sjws_nettask/pull/335), [#337](https://github.com/BlivionIaG/sjws_nettask/pull/337) (this design), [#331](https://github.com/BlivionIaG/sjws_nettask/pull/331), [#299](https://github.com/BlivionIaG/sjws_nettask/pull/299).
- ABBA / FAIL rule from inceptor as recorded on [#263](https://github.com/BlivionIaG/sjws_nettask/pull/263).
- XXH64: [xxHash](https://xxhash.com/) / cyan4973/xxHash reference (seed 0).
- Kafka append locking: [storage engine](https://krivaltsevich.com/kafka-internals/03-storage-log-engine.html), [KAFKA-12838](https://issues.apache.org/jira/browse/KAFKA-12838).
- Redpanda TPC: [architecture](https://docs.redpanda.com/current/get-started/architecture), [TPC buffers](https://www.redpanda.com/blog/tpc-buffers).
- NATS JetStream: [filestore#1675](https://github.com/nats-io/nats-server/pull/1675), [`max_concurrent_io`](https://docs.nats.io/reference/config/jetstream/).
