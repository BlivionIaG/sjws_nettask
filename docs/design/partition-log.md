# Durable single-node partition log: gap analysis and design

Status: design note (2026-10-13). Scope: one node, one partition = one log. Replication, multi-partition topics and the
cluster CP are out of scope. Most of the log already exists in `messaging/ledger` (#21: segmented CRC32C ledger, group
commit, kill -9 test; #69: snapshots and retention). This note maps each requirement to existing code or to a gap, makes
the open decisions, and lists the small PRs that close the gaps.

## 1. Requirement → code / gap

| # | Requirement | Status | Where / what is missing |
|---|---|---|---|
| R1 | Segmented append-only `PartitionLog` | **Exists** (named `SegmentedLog`) | `ledger/SegmentedLog.java` (append, read, scan, roll), `ledger/Segment.java` (`<base:020d>.log`). No rename: `PartitionLog` is this class. |
| R2 | On-disk format = wire format | **Exists for batches**; wire side is a gap | `RecordBatch` frame `[len][crc32c][baseOffset][count][timestampMs] count×([recLen][bytes])` (`RecordBatch.java:9`). Nothing serves it on the wire yet (see R9). Fetch must return these frames unchanged (zero re-encode). |
| R3 | Sparse offset index | **Exists** | `.index`, one entry per 4 KiB (`Segment.INDEX_INTERVAL`), rebuilt by scan on open when stale or corrupt (`LedgerTest.staleOrCorruptIndexFallsBackToScan`). |
| R4 | Per-record CRC | **Per-batch exists; decision: per-batch suffices** | See §2.1. No per-record CRC is added. |
| R5 | Recovery truncates a bad tail | **Exists** | `Segment.recover` truncates at the first torn or corrupt frame; later segments after a torn sealed one are dropped (`SegmentedLog.open`). Tests: `tornTailAtEveryByte`, `randomTailCorruptionRecoversPrefix`, `gapAfterTornSealedSegmentDropsLaterSegments`, `PartitionTest.tornTailRecoversAPrefixState`. |
| R6 | Size and time retention | **Partial: gap** | `RetentionPolicy(keepResolvedMs, compactLiveRatio)` deletes sealed segments below the low watermark after a time. There is **no size cap** and **no time limit for unconsumed data** (by design the delivery layer never drops what a group still needs). Gap G2, §2.3. |
| R7 | Single writer per partition, platform thread | **Exists** | `LedgerWriter`: `Thread.ofPlatform()` writer and syncer per log (`LedgerWriter.java:90-91`); producers hand off through a bounded queue. The shared writer/syncer pool (#86 §5.1, "PR 5") is planned; it keeps one writer *per partition at a time*. |
| R8 | Per-topic durability none / group, fsync opt-in | **Exists** | `Durability {NONE, ASYNC, GROUP, EACH}` as writer default (per partition, so per topic) plus per-append override (`Durability.java`). `ASYNC` is the default (background fsync); `GROUP` is the fsync opt-in. A topic-level config key is a small gap (G3). |
| R9 | Produce / fetch through the `core.Handler` seam, off by default | **Gap (main one)** | No messaging HTTP endpoints exist; only `swarm/SwarmHttp` (a `Handler`) uses messaging internally. Gap G1, §2.2. |
| R10 | kill -9 and truncated-tail crash tests | **Exist** for the ledger; gap for the HTTP path | `LedgerCrashTest.noAckedRecordLostAfterSigkill` (child JVM, `destroyForcibly`) plus the torn-tail tests in R5. G1 adds a kill -9 test through produce over HTTP. |
| R11 | LogBench-style benches: append throughput, p99 produce/fetch | **Partial** | `bench/LogBench.java` (prototype write path) and `bench/LedgerBench.java` (append throughput by durability mode). No fetch bench and no end-to-end HTTP produce/fetch p99. Gap G4. |
| R12 | Zero cost on the static path when off | **To be shown** | The module will load only when configured (same pattern as `OptionalModules`/`SwarmModule`). Proof: wrk before/after on static files with the module off (G1 acceptance). |
| R13 | Designed around JVM Footprint's shared buffer pool | **Agreed design, not merged** | `core.buf.BufferPool` (proposal: `sjws-slab-allocator-design.md`, JVM Footprint). Decisions are in §2.4. |

## 2. Decisions

### 2.1 Per-batch CRC is enough
The CRC32C covers the whole batch frame after the crc field, so every record byte is covered. A record is only ever written,
acknowledged, recovered and fetched as part of its batch: there is no partial-batch ack, and recovery truncates at batch
boundaries. A per-record CRC would add 4 B per record and a second pass over the bytes on append, and it would detect
nothing the batch CRC misses. Consumers that need to verify a single record after a fetch verify the batch it arrived in
(fetch returns whole frames, R2). This is the same choice as Kafka's v2 record batch. Revisit only if a fetch API ever
returns sub-batch slices.

### 2.2 Produce / fetch HTTP surface (G1)
- `MessagingHttp implements core.Handler`, mounted at `/messaging/v1` through `HandlerContributor`. It is **off by default**; with the module off, no class loads.
- `POST /messaging/v1/topics/{t}/records`: the body is one or more records (length-prefixed). Optional headers `Producer-Id` and `Producer-Seq` (dedup, as `Partition.produce`) and `Durability: none|group`. The response is `{baseOffset}` once the requested durability holds.
- `GET /messaging/v1/topics/{t}/records?offset=&maxBytes=`: the body is raw `RecordBatch` frames (`application/vnd.sjws.batch`), starting at the batch that contains `offset` and stopping before `maxBytes`. The handler copies file bytes straight to the response (`transferTo` on plain TCP, otherwise a pooled buffer via `tryAcquire`, 503 under pressure); it never decodes.
- Errors are RFC 9457 problems, like `SwarmHttp`.
- Access goes through the existing rights or filters chain; no new auth.

### 2.3 Retention (G2)
Add `maxBytes` and `maxAgeMs` to the log-level policy for **plain log topics** (topics with no delivery groups, i.e. the
fetch API). These delete the oldest sealed segments past either limit. Topics with delivery groups keep today's rule:
nothing at or above the low watermark is deleted. A size cap may still not override it; it only reports `retention.blocked`
in `MessagingStats`. This keeps work-queue semantics exact.

### 2.4 Buffers (agreed with JVM Footprint, `core.buf.BufferPool`)
- **Public API copies:** public `records()` keeps returning `byte[]` copies. An opt-in zero-copy view is used only by fetch/sendfile and by replication.
- **Writer:** it encodes into its own per-partition group buffer. Writer and syncer threads may use per-thread pool caches.
- **Recovery:** scan buffers come from the pool's large cache, and only during recovery.
- **Fetch:** the fetch frame is byte-identical to the on-disk batch, so plain TCP uses sendfile (`FileChannel.transferTo`). There is no TLS in v1. Fetch takes buffers with `tryAcquire` and answers `503` + `Retry-After` under pool pressure instead of blocking.
- **Budget:** one pool cap, with a messaging sub-budget.
- **Observability:** it will define `sjws_messaging_*` metrics with `topic`/`partition`/`group` labels. The log leaves hook points (append bytes and latency, fetch bytes and 503s, pool acquire failures, retention deletes or blocks) and does not define metric names itself.
- **When off:** the log allocates nothing.

## 3. Gap PRs (each one change)
1. **G1a**: `MessagingHttp` produce and fetch handler, off by default, with tests and a wrk static-path check (R9, R12).
2. **G1b**: kill -9 through HTTP produce, plus a truncated-tail fetch test (R10).
3. **G2**: size and age retention for group-less topics, with a blocked-retention stat (R6).
4. **G3**: per-topic durability config key (R8).
5. **G4**: fetch bench and an HTTP produce/fetch p99 bench in `LogBench` style (R11).
