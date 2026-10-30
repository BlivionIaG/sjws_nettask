# SharedLedgerPool for NONE/ASYNC ledger writers

Status: **design only** (2026-10-30 Europe/Paris). Tracks [#336](https://github.com/BlivionIaG/sjws_nettask/issues/336).
Blocks S2c PR 8 / [#299](https://github.com/BlivionIaG/sjws_nettask/pull/299) after [#335](https://github.com/BlivionIaG/sjws_nettask/pull/335).
Depends on: #333 (eager heap), #335 (lazy queue + fail-closed budget), #331 / #329 (shared **syncers** already).
No Java changes in this PR.

## 1. Goals and non-goals

### Goals

1. Cap **writer platform threads** for `Durability.NONE` and `Durability.ASYNC` partitions at a small, configurable N (default: CPU count), independent of group / partition count.
2. Preserve **per-partition append order** and the existing `PartitionAppender` / `awaitCapacity` → `appendNoWait` contract.
3. Keep the **fail-closed** `LedgerWriterBudget` (#335): refusals → `LedgerBudgetExhaustedException` → HTTP **503** with scrubbed detail and `Retry-After`; never OOM.
4. Isolate failures: one partition's IO error or budget refusal must **not** fence other partitions that share a writer shard (Security #335 B1 lesson generalized).
5. Leave **GROUP / EACH** (fsync-waiting; there is no `Durability.SYNC` enum value) on dedicated per-partition writer threads unless a later PR proves identical semantics.
6. Unblock the #299 §13 gate at G=64 (1k streams): today p99 ≈ 169 ms with 774 writers and 0 `ZAllocationStall`.

### Non-goals

- Changing on-disk `RecordBatch` / segment format, replication, or Raft.
- Sharing `SegmentedLog` files across partitions (one log directory per partition stays).
- Moving GROUP/EACH onto the pool in v1.
- Replacing the shared **syncer** pool (#331); writers and syncers stay separate roles.
- Retuning `-Xmx` / `ledger.budget_heap_fraction` for the #299 gate (heap flags stay as recorded).
- Implementation in this PR (design + issue only).

## 2. Today's ownership (master `89a901b` + #335 shape)

| Piece | Owner | Thread / memory |
|--|--|--|
| `SegmentedLog` | one per partition / topic | file channels; no dedicated thread |
| `LedgerWriter` | one per `Partition.open` / `LogTopics` open | **1 platform writer thread** always; queue `2×65536` + 1 MiB direct (eager on master; lazy under #335) |
| Syncer | dedicated **or** `SharedLedgerPool` shard | CPU-sized shared syncers by default (`sjws.messaging.ledger.sharedWriters`); `pinged` idle spin (#329); `durable` volatile (#331) |
| `LedgerWriterBudget` | process-wide (#335) | idle 64 KiB / writer; materialize `2×cap×8 + 1 MiB`; `acquireExtra` on direct growth; release on close under `materializeLock` |

Call sites that construct writers:

- `Partition.open(...)` → `new LedgerWriter(log, durability)` (task + DLQ partitions; swarm scale driver).
- `LogTopics` first produce → `new LedgerWriter(log, durability(topic))`.

`SharedLedgerPool` **today shares only syncers**, not writers. Naming is historical (`PROPERTY = sjws.messaging.ledger.sharedWriters`); v1 of this design keeps the property for syncer size and adds a **separate** writer-pool property (see §11) so existing `0` / `false` = dedicated syncers stays valid.

Close / fence today: any `IOException` / `RuntimeException` in `writeGroup` sets `failure=` and fences the writer; #335's latest Security B1 requires a **budget growth refusal** to fail only that batch (disk untouched), matching materialize refusal. The pool design must not re-introduce cross-partition fencing.

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
              │  SharedLedgerPool writer shard  │  × N (default = CPUs)
              │  one platform thread            │
              │  drains sticky partition queues │
              │  one direct ByteBuffer          │
              └───────────────┬────────────────┘
                              │ write → SegmentedLog.appendFrames
              ┌───────────────┴────────────────┐
              │  SharedLedgerPool syncer shard  │  × M (existing; often = CPUs)
              │  sharedSyncSlice / force        │
              └────────────────────────────────┘
```

- **N writer shards**, default `max(1, availableProcessors())`, property `sjws.messaging.ledger.sharedWriterThreads` (see §11).
- A NONE/ASYNC `LedgerWriter` **registers** on `hash(partitionKey) % N` (sticky). Least-loaded placement is rejected for writers: rebalancing would reorder a partition across shards.
- Partition key = `log.dir()` path string (stable for the life of the open); tests may inject a shard index.
- GROUP/EACH constructors **skip** writer-pool registration and start a dedicated writer thread exactly as today (syncer registration unchanged).

### 3.2 Per-partition ordering

Each pooled writer keeps a small **per-partition MPSC inbox** (or a single shard queue of `(writer, Pending)` with a per-writer serial bit). Rules:

1. Appends for partition P are always drained FIFO on the shard that owns P.
2. The shard thread may **batch across partitions** (encode A then B into the shard's direct buffer only if they target different logs — they never share a `SegmentedLog`, so cross-partition batching is separate `write` calls in one drain loop, not one `appendFrames`).
3. Within one partition, grouping (`lingerNanos`, `maxGroupBytes`, `drainTo`) stays identical to `LedgerWriter.run` / `commit` / `writeGroup`.
4. `behind` / soft capacity remain per-partition (producers of a hot partition throttle; a cold neighbour on the same shard is not falsely marked behind).

Invariant (test): two threads appending to the same partition with monotonic payloads observe strictly increasing offsets matching enqueue order; a mutant that drains two partitions' queues round-robin *inside* one partition's Pending list must fail that test.

### 3.3 Batching and zero-copy

- Keep `RecordBatch.encode` into a **shard-owned** direct `ByteBuffer` (one per writer shard, grown via `acquireExtra` like #335 B3).
- Do **not** copy producer `byte[]` payloads; `Pending.records` stay as today (`List.copyOf` of the caller's arrays).
- Cross-partition: one drain iteration may `write(gA)` then `write(gB)` without returning to park, so a busy shard amortizes wakeups. No merging of frames across logs.
- Linger stays per-writer config; the shard applies each writer's `lingerNanos` / `maxGroupBytes` when draining that writer's inbox only.

## 4. Budget charging (pool vs per-writer)

Keep `LedgerWriterBudget` as the single process-wide ledger. Change **what** is charged:

| Charge | When | Who |
|--|--|--|
| Idle handle | NONE/ASYNC writer construct | `IDLE_COST_BYTES` (64 KiB) per partition handle — object + small inbox metadata, **no** thread |
| Shard materialize | first Pending on a writer shard | one `ArrayBlockingQueue`-equivalent (or striped inboxes' backing) + 1 MiB direct, charged **once per shard** via `acquireMaterialize` / a new `acquireShard` helper |
| Direct growth | shard buffer grows past 1 MiB | `acquireExtra`; roll back if `allocateDirect` fails (#335 N2) |
| GROUP/EACH writer | construct / materialize | **unchanged** full per-writer charge (dedicated thread + queue + direct) |
| Release | partition close / shard retire | handle idle on unregister; shard charge when last member leaves and shard buffers are freed |

Consequences at swarm scale (`-Xmx2g`, default 0.25× ≈ 512 MiB budget):

- Today after #335: ~120 groups open then 503s (1926 refusals at 1k groups) because each open still charges idle and, on first append, a full queue.
- With pool: idle handles are cheap; materialize cost is **O(N shards)** not O(writers). Admission can prefer refusing **new group opens** via a separate group-admission hook later; v1 keeps writer-budget refusals as 503 on first append / open path that charges.

Fail-closed stays: any path that would allocate queue slots or direct memory without a successful `acquire*` is a bug (Security review item).

## 5. Backpressure and 503

| Condition | Behaviour |
|--|--|
| Per-partition soft capacity | `awaitCapacity` parks the producer (unchanged); shard may still be draining other partitions |
| Shard overloaded (optional second bound) | if the shard's aggregate queued Pendings exceed `N × queueCapacity`, `appendNoWait` offers fail into wait / 503 — **opt-in**; default off in v1 so one hot partition does not 503 cold ones beyond their own soft bound |
| Budget refuse (idle / materialize / extra) | `LedgerBudgetExhaustedException` → messaging/swarm HTTP **503**, scrubbed detail (#246 / #335 B2), `Retry-After` |
| Growth refuse mid-writeGroup | fail **that batch only**; do not set `failure=`; next small append succeeds (Security #335 B1) |

## 6. Close and reopen (lessons from #335 B1)

Under one lock per handle (`materializeLock` or rename to `lifecycleLock`):

1. `close()` sets `closed=true`, snapshots whether the handle had registered / materialized, and enqueues poison **only if** a shard is draining this handle.
2. Concurrent first-append registration / materialize under the same lock **refuses** when `closed` (no thread start, no charge).
3. Unregister from the writer shard; if the shard member set goes empty, the shard thread retires (same start/stop discipline as syncer `#219` / `#329` races: clear `writerThread` only under the shard lock after observing empty membership).
4. Drain / force remaining durable bytes for this partition only (reuse today's shared-syncer close flush), then `budget.release` exactly once.
5. Reopen = new `LedgerWriter` / new registration; no reuse of a fenced handle.

Mutation proof: hammer close ∥ first-append; mutant that starts the shard work or takes a charge after `closed=true` must fail.

## 7. Failure isolation

| Event | Scope |
|--|--|
| `SegmentedLog` IO error while writing P | set `failure` on **P only**; complete P's group + P's fsync waiters; other partitions on the shard continue |
| Budget refuse for P's growth / P's first materialize | exceptional complete of **that** Pending / append future only |
| Shard thread crash (uncaught) | treat as process bug; log and fail members currently being drained; prefer not to silently continue — tests assert the handler is installed |
| Syncer force error on P | today's `sharedSyncSlice` already sets `failure` on that writer only |

Hard rule for Security: **no** shard-level `failure` flag that rejects appends for every member.

## 8. Why GROUP/EACH stay dedicated (SYNC semantics)

The codebase has no `Durability.SYNC`; fsync-waiting modes are **GROUP** and **EACH** (`waitsForFsync()`). Swarm / streaming defaults to NONE/ASYNC. Keeping GROUP/EACH on a dedicated writer thread means:

- Inline `forceInline()` after write stays on the same thread that called `write`, identical to today.
- No risk that a pooled shard parks in `log.force()` for GROUP partition A while NONE partition B's appends queue behind it on the same thread (priority inversion on the produce path).
- Proof in the implementation PR: existing `LedgerCrashTest` / durability tests for GROUP/EACH run unchanged against pooled-NONE builds; a characterization test asserts GROUP/EACH still have `thread != null && sharedWriterShard < 0`.

If a later JFR shows GROUP is rare and safe to pool with a "force before dequeue other partitions" rule, that is a follow-up — not v1.

## 9. Expected thread, heap, and p99 math

Assumptions: `pool.shards=4`, ~12–16 writers/group as measured on #299 (task+DLQ+stream/reply partitions), box CPUs = 8, writer pool N = 8, syncer pool M = 8, #335 lazy idle = 64 KiB/handle, shard materialize ≈ 1 MiB queue-equivalent + 1 MiB direct (order-of-magnitude; exact queue sizing is an implementation knob).

### 9.1 Threads (platform)

| G | writers today | writer threads today | writer threads pooled | syncer threads (shared) | total ledger threads pooled |
|--|--|--|--|--|--|
| 16 | 262 | 262 | **8** | ≤8 | ≤16 |
| 64 | 774 | 774 | **8** | ≤8 | ≤16 |
| 256 | 2014 | 2014 (OOM path) | **8** | ≤8 | ≤16 |
| 1000 | 2674 | 2674 (OOM path) | **8** | ≤8 | ≤16 |

GROUP/EACH dedicated writers add +1 each (expected ≈ 0 on the swarm gate).

### 9.2 Heap / budget (order of magnitude, `-Xmx2g`)

| G | #335 idle charge (64 KiB×writers) | + materialize if all hot | pooled: idle handles + 8 shard buffers |
|--|--|--|--|
| 16 | ~16 MiB | + hundreds of MiB if every writer materializes | ~16 MiB + ~16–32 MiB shards |
| 64 | ~48 MiB | dominates budget → early 503s when hot | ~48 MiB + shards |
| 256 / 1k | fits idle after #335; materialize / open still 503-bound | | idle scales with handles; **materialize does not** |

Prediction: 1k groups stay open under default 0.25×heap for NONE/ASYNC swarm traffic; `group_open_refusals` drop from ~1926 toward 0 unless a separate group-admission cap is added.

### 9.3 p99

#299 abs-01: 29.6 MiB/s, p99 169 ms, 774 writers, 0 ZAllocationStall. If scheduling / thread fan-out dominates, pooled N=8 should move p99 toward the **1.5 ms** guard at the same MiB/s. If disk or lock hold times dominate, p99 stays high and the JFR table (§13) tells us which.

## 10. Test plan (with mutation proofs)

| # | Test | Mutant that must fail |
|--|--|--|
| T1 | Per-partition order under concurrent appenders on one pooled shard | drain ignores per-partition FIFO |
| T2 | Two partitions on one shard: IO fault injected on A; B's next append succeeds | shard-level `failure=` |
| T3 | Budget growth refuse on large append; next small append succeeds (port Security #335 B1) | `failure=` on refuse |
| T4 | close ∥ first-append: no charge / no shard work after close | charge after `closed` |
| T5 | GROUP/EACH still dedicated; crash/durability suites green | GROUP registered on writer pool |
| T6 | Syncer registration unchanged; `durableNow` publish order (port #331 DurableOrderingTest) | durable store before force returns |
| T7 | Pool size 0 / property off → dedicated writers (legacy) | always pool |
| T8 | Budget release once on close; shard buffer released when last member leaves | double-free or leak (`chargedEnd≠0`) |
| T9 | Stress: 2k open/close cycles, 0 thread leaks, `writers()` and `charged()` return to baseline | unregister skip |

## 11. Bench plan

Hygiene (standing Messaging Core rules): `flock /tmp/sjws-bench.lock`, builds `nice -n 10`, start only when load1 < 4 (prefer < 3 for produce hot path), record load1.

### 11.1 Produce-path ABBA (#263 rule)

- Harness: existing messaging produce / `RaftPublishBench` style used on #335.
- Alternating blocks base↔PR (ABBA), ≥3 valid blocks, cap 6.
- **FAIL** only if one-sided paired sign-flip on `log(p99_PR / p99_base)` has **p < 0.05** **and** geomean **> 1.10**.
- Block invalid when base pooled p99 ≥ 500 µs.
- Report G1 (`|t|<2` and delta ≤ larger sd) as info; inceptor FAIL gate is the #263 rule above.

### 11.2 Report-only group sweep

At `-Xms2g -Xmx2g`, `shards=4`, same harness flags as #299:

| Metric | 16 | 64 | 256 | 1000 |
|--|--|--|--|--|
| writers (handles) | | | | |
| writer platform threads | | | | |
| syncer threads | | | | |
| heap after GC | | | | |
| budget charged / refusals | | | | |
| MiB/s, p99 | | | | |
| OOM? | | | | |

Compare to Agent Swarm's pre-pool table (§1 of #336). No threshold retune in v1; numbers are evidence for inceptor.

### 11.3 Optional micro-measure

If the bench lock is free and load1 < 3: a unit-scale harness with W synthetic partitions on 1 vs N pooled threads, fixed append rate, report threads and p99. Skip when #335 or #299 holds the lock.

## 12. Risks

| Risk | Mitigation |
|--|--|
| Head-of-line blocking: GROUP force on a pooled thread stalls NONE neighbours | GROUP/EACH dedicated in v1 |
| Sticky hash imbalance | document; optional power-of-two choices later; measure max/mean queue depth per shard in sweep |
| Property name confusion (`sharedWriters` = syncers today) | new `sharedWriterThreads`; leave old property meaning syncers; docs callout |
| Double budget accounting (handle + shard) | single `LedgerWriterBudget`; tests for `charged()` monotonicity |
| Close / unregister races | port #335 `materializeLock` discipline; stress T9 |
| Produce p99 regression from extra hop | ABBA gate; keep `ArrayBlockingQueue`-class hand-off; no virtual thread on the drain path |
| JFR shows disk, not threads, dominate p99 | pool still needed for 1k-group memory; p99 follow-up may be linger / fsync floor / batching |

## 13. Alternatives considered

### 13.1 Status quo + #335 only

Lazy queues stop OOM but leave **O(writers)** threads. #299 G=64: 774 threads, p99 169 ms, 0 ZAllocationStall. Rejected as the PR 8 fix; kept as prerequisite.

### 13.2 Kafka-style: shared request threads, per-partition lock

Kafka's `UnifiedLog.append` runs under a per-partition `synchronized(lock)` on broker request-handler threads ([Kafka storage engine notes](https://krivaltsevich.com/kafka-internals/03-storage-log-engine.html); [`LogManager`](https://github.com/a0x8o/kafka/blob/master/core/src/main/scala/kafka/log/LogManager.scala)). One slow partition can pin many handler threads ([KAFKA-12838](https://issues.apache.org/jira/browse/KAFKA-12838)). We already have a per-partition queue and want the opposite: **at most one thread working a partition**, with a hard cap on threads. Rejected as the primary model; the sticky shard queue is closer to the mitigation sketched in that JIRA.

### 13.3 Redpanda / Seastar: thread-per-core, partition owned by one shard

Redpanda pins one reactor per core; each partition lives on exactly one shard; appends are message-passed to the owning core ([How Redpanda Works](https://docs.redpanda.com/current/get-started/architecture); [TPC buffers](https://www.redpanda.com/blog/tpc-buffers); [InfoQ Seastar talk](https://www.infoq.com/presentations/high-performance-asynchronous3/)). **Closest prior art** to this design: sticky hash → N shards, shard-local buffers, no cross-shard locks on the write path. We do not adopt full Seastar shared-nothing memory (JVM); we only borrow the ownership rule.

### 13.4 NATS JetStream filestore

JetStream moved away from a single shared write buffer to per-message-block write-through caches ([nats-server#1675](https://github.com/nats-io/nats-server/pull/1675); [`filestore.go`](https://github.com/nats-io/nats-server/blob/main/server/filestore.go)), bounds global disk ops with `max_concurrent_io` ([JetStream config](https://docs.nats.io/reference/config/jetstream/)), and reuses a shared parallel task queue across accounts so goroutine count does not explode ([`jetstream.go`](https://github.com/nats-io/nats-server/blob/master/server/jetstream.go)). Lesson taken: **bound IO concurrency** and avoid one global write mutex; our shard count is that bound. We do not adopt per-block caches in v1.

### 13.5 One JVM virtual thread per partition

Cheap stacks, but wake/park storms and scheduling still scale with partition count; #299's symptom is consistent with scheduler pressure under hundreds of runnable writers. Rejected for the drain path; producers may stay on whatever thread `Partition` already uses.

### 13.6 Multiplex syncers only (already shipped)

#331. Necessary, not sufficient.

## 14. Hypothesis table for Agent Swarm's JFR (64-group window)

After #335 merges, Agent Swarm will attribute p99 on a 64-group / 1k-stream window. Design predictions:

| ID | Hypothesis | JFR / signals that **confirm** | Signals that **refute** | Design implication if confirmed |
|--|--|--|--|--|
| H1 | Runnable writer count / context switches dominate p99 | high `JavaThreadPark`, `ContextSwitch`, many `ledger-writer-*` runnable; CPU in scheduler | CPU in `force` / `write` / `appendFrames`; few runnables | **Pool v1 is the right fix** for the guard |
| H2 | Per-write unpark / syncer flooring dominates | stacks in `unparkSyncer`, `LockSupport.park` on syncer; fsync interval binding | writers runnable without syncer parks | tune flooring / batch unpark; pool optional for p99 |
| H3 | `Partition` lock hold / encode path dominates | samples in `Partition.append*` / `RecordBatch.encode` under lock | little time under Partition lock | messaging lock refactor; pool alone won't hit 1.5 ms |
| H4 | Disk / `FileChannel.write` / page cache dominates | `NativeMethod` in write/force; high `java.io` time; device util | CPU-bound Java stacks | pool still needed for thread/heap at 1k groups; p99 needs batching or device |
| H5 | GC / allocation (despite 0 ZAllocationStall on abs-01) | allocation pressure, minor stalls under finer events | still 0 stalls; TLAB quiet | revisit #335 buffer growth; not primary |
| H6 | Hash imbalance would hurt after pooling | (post-pool) one shard CPU ≫ others; depth skew | even shard CPU | sticky hash rethink / work-stealing **with** order token |

The implementation PR's report must map the received JFR summary onto H1–H6 explicitly.

## 15. Config sketch (implementation PR)

| Property | Meaning | Default |
|--|--|--|
| `sjws.messaging.ledger.sharedWriters` | **syncer** shard count (existing) | CPU count; `0`/`false` = dedicated syncers |
| `sjws.messaging.ledger.sharedWriterThreads` | **writer** shard count for NONE/ASYNC | CPU count; `0`/`false` = dedicated writers (legacy) |
| `sjws.messaging.ledger.budget.*` | unchanged from #335 | 0.25× max heap |

YAML under `ledger:` mirrors the properties (same pattern as #335).

## 16. Rollout

1. Land #335 (budget + lazy + non-fencing refuse).
2. Land this design (docs) after inceptor approval.
3. Implementation PR referencing #336: pool writers for NONE/ASYNC; tests T1–T9; ABBA; report-only sweep; Security + Agent Swarm review.
4. Re-run #299 G=64 counted window; only then claim the PR 8 blocker cleared.

## 17. Open questions for inceptor

1. Default `sharedWriterThreads`: CPU count, or `max(2, CPUs/2)`, or match syncer size exactly?
2. Sticky `hash(dir) % N` vs explicit shard pinned at `Partition.open` (swarm could pass shard id)?
3. Is an aggregate shard-queue 503 in v1 desired, or only per-partition soft capacity + budget?
4. Should `LogTopics` NONE/ASYNC join the same pool as delivery `Partition` writers (recommendation: **yes**, one process pool)?
5. Naming: rename today's `SharedLedgerPool` roles in docs to "shared syncer pool" to end the `sharedWriters` confusion, or accept dual-duty class with two properties?

## 18. References

- Issue [#336](https://github.com/BlivionIaG/sjws_nettask/issues/336) (this work), [#333](https://github.com/BlivionIaG/sjws_nettask/issues/333), PR [#335](https://github.com/BlivionIaG/sjws_nettask/pull/335), [#331](https://github.com/BlivionIaG/sjws_nettask/pull/331), [#299](https://github.com/BlivionIaG/sjws_nettask/pull/299).
- ABBA / FAIL rule from inceptor as recorded on [#263](https://github.com/BlivionIaG/sjws_nettask/pull/263).
- Kafka append locking: [storage engine](https://krivaltsevich.com/kafka-internals/03-storage-log-engine.html), [KAFKA-12838](https://issues.apache.org/jira/browse/KAFKA-12838).
- Redpanda TPC: [architecture](https://docs.redpanda.com/current/get-started/architecture), [TPC buffers](https://www.redpanda.com/blog/tpc-buffers).
- NATS JetStream: [filestore#1675](https://github.com/nats-io/nats-server/pull/1675), [`max_concurrent_io`](https://docs.nats.io/reference/config/jetstream/).
