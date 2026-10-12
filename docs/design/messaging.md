# Messaging Core Design — sjws_nettask

Status: Draft · Base: master b8055fe · Owner: Messaging Core workstream

## 0. Scope
sjws is heading toward reliable, low-latency message-oriented middleware. **Messaging Core =
the message queue/broker core**: partitions, a durable ledger, producers/consumers,
acknowledgement, redelivery, dead-lettering, inspection, and replication through the
cluster layer designed by the HA workstream (`docs/design/high-availability.md`).
Out of scope: the wire protocol for clients (it sits on the existing HTTP/1.1 and HTTP/3
transports behind `core.Handler`; it gets its own doc), the admin TUI itself, and the metrics backend.

## 1. Concepts
- **Topic → partitions**. Each partition is the unit of order, durability and replication
  (one replication group per partition).
- **Message**: `{partition, offset, key?, headers, payload, enqueuedAt, attempts}`;
  `offset` = a monotonic per-partition sequence number assigned at append.
- **Consumer group**: named, with a **per-consumer-group offset** per partition (the committed
  low watermark). Exclusive per-partition assignment within a group, so order is preserved.
- **Message states** (per group): `ENQUEUED → IN_FLIGHT → ACKED`, with
  `IN_FLIGHT → ENQUEUED` on nack/visibility timeout (attempts+1) and
  `→ DEAD_LETTERED` once `attempts > maxDeliveries` or on an explicit reject.

## 2. Durable ledger
Every non-acked message and each of its state transitions is durable before it is
acknowledged to the caller.

### 2.1 Layout (per partition directory)
```
p-0007/
  00000000000000000000.log   # append-only segment; records: len|crc32c|type|body
  00000000000000000000.idx   # sparse offset -> file position (every 4 KiB)
  state.snap                 # snapshot of the ledger state machine (+ covered index)
  MANIFEST                   # active segments + snapshot; updated by atomic rename
```
Record types: `ENQUEUE(offset,msg)`, `DELIVER(group,offset,consumer,deadline,attempt)`,
`ACK(group,offset|range)`, `NACK(group,offset)`, `DLQ(group,offset,reason)`,
`OFFSET_COMMIT(group,offset)`. Messages and their state transitions share one ordered log, so the
log defines the state exactly. Segments roll at 64 MiB (configurable) and are preallocated.

### 2.2 Group commit / fsync
- One writer per partition (or per disk, with partitions multiplexed onto it) drains pending
  appends, writes them as one batch and issues one `force(false)` (fdatasync); completion
  futures are released after the fsync.
- The batch closes on `maxBatchBytes` (256 KiB), `maxBatchDelay` (200 µs) or an idle queue, whichever comes first.
  Under load this amortizes fsync across thousands of records; when idle, latency is about one fsync.
- Durability modes: `SYNC` (default, fsync before ack, batched) and `RELAXED` (ack after
  write, fsync every N ms; documented loss window of N ms on a power failure).

### 2.3 In-memory state machine
Per partition and group: `nextOffset`, `committedOffset`, an in-flight map backed by a deadline
min-heap, a redelivery queue, and per-state counters. Payloads are not held in memory; they are
read back from segments (page cache) via the index.

### 2.4 Crash recovery (exact replay)
1. Load `MANIFEST` and `state.snap`.
2. Replay records after the snapshot index deterministically.
3. Truncate the tail at the first bad length or CRC (torn write). In replicated mode,
   reconcile with the group's committed index (§6).
4. Expired `IN_FLIGHT` messages go back to `ENQUEUED` (at-least-once).
Invariant: `state(replay(log)) == pre-crash state` for every fsynced prefix. This is
property-tested with a fault-injecting `FileChannel` that kills the process at arbitrary byte offsets.

### 2.5 Retention, truncation, compaction
- Partition low watermark = min(group committed offsets, oldest non-acked or DLQ'd entry).
  A segment entirely below it *and* covered by a snapshot is deleted.
- Background compaction rewrites mostly-acked segments, keeping live records only, and swaps them in via `MANIFEST`.
- The DLQ is a topic (`<topic>.dlq`) with the original offset, reason and attempts in its headers.

## 3. Delivery, redelivery, DLQ
- `poll(group, consumer, max, visibilityTimeout)` writes `DELIVER` records;
  `ack(group, offsets)`, `nack(group, offset, delay?)`, `reject(group, offset)`.
- A timer wheel expires in-flight messages; backoff = `min(base·2^attempt, max)` (existing `Backoff`).
- Optional `orderedByKey`: a redelivered message blocks later messages with the same key.
- At-least-once; producer idempotency `(producerId, seq)` dedups retried appends within a window.

## 4. Inspection / query API (admin TUI, metrics)
Read-only, served from in-memory state and segments, with no locks on the hot path:
- `stats(topic?, partition?, group?)`: counts per state, oldest pending age, lag,
  redelivery rate, DLQ size, fsync and batch-size histograms.
- `pending(topic, partition, group, state, fromOffset, limit)`: paginated
  `{offset, state, attempts, consumer, deadline, enqueuedAt, size, headers}`.
- `get(topic, partition, offset)`: the message and its full state history.
- Audited admin mutations, written to the ledger: requeue from the DLQ, purge, reset offset.
Exposed as Java `messaging.Inspector` and as JSON at `/_admin/messaging/...` behind the
rights decorator; metrics go to the observability `MetricsSink`.

## 5. Package layout
`sjws_nettask.messaging`: `Broker`, `Partition`, `Ledger`, `Segment`, `SegmentIndex`,
`GroupCommitWriter`, `LedgerStateMachine`, `Snapshotter`, `RedeliveryTimer`, `Inspector`,
`ReplicatedLedger` (cluster adapter; a `LocalOnly` implementation until the cluster layer lands).

## 6. Replication via the cluster layer
One replication group per partition. The leader proposes ledger record batches via `append`;
every replica applies committed entries to `LedgerStateMachine` in index order; only the
leader serves `poll`/`ack`. Offsets and in-flight state therefore fail over with the partition. The cluster
log and the ledger may share segments (log index == ledger position), so each entry is fsynced once.

### Requirements for the cluster layer
1. **Per-partition total order**: one replicated log per group; entries are applied in index order everywhere.
2. **Ack after quorum commit + fsync**: `append` completes once a majority has fsynced the entry.
   A configurable **relaxed mode** completes after the leader's fsync plus majority receipt in memory (or the leader alone).
3. **Latency**: single-digit-ms p99 replication on a LAN, with batching and pipelined appends.
4. **Multiplexing**: per-partition groups share one transport per node pair (shared heartbeats,
   batched cross-group frames), scaling to thousands of groups per node.
5. **Snapshot + log truncation** past acked/snapshotted indexes; snapshot install for
   lagging or new replicas; compaction-aware snapshots.
6. **Assumed API**:
   ```java
   CompletableFuture<Long> append(GroupId g, List<ByteBuffer> batch); // -> committed index
   void onCommitted(GroupId g, CommittedEntryCallback cb);            // in-order apply
   void onLeadership(GroupId g, Consumer<LeadershipEvent> l);         // gained/lost + term
   void snapshotHooks(GroupId g, SnapshotTaker take, SnapshotInstaller install);
   void truncateBefore(GroupId g, long index);
   ```
   Membership and quorum come from HA (`Membership`, `QuorumPolicy`). Fencing: a deposed leader
   fails pending futures and stops serving before the new term's lease starts.

## 7. Benchmark plan
JMH for components plus a load generator (`tests/bench/`), run on NVMe and on a cloud volume, with results kept as CSV.
- Raw fsync cost (`force(false)`) for 4 KiB–1 MiB writes, preallocated vs not.
- Group commit: throughput and p50/p99/p999 vs `maxBatchDelay` {0, 50, 200, 1000 µs} ×
  producers {1, 8, 64} × size {100 B, 1 KiB, 16 KiB}.
- End-to-end produce→consume→ack latency and redelivery overhead.
- Recovery time vs log size and snapshot interval.
- Replicated: 3 nodes on a LAN, commit p99 vs batch size.
Initial targets (1 node, NVMe, SYNC): ≥200k msg/s for 1 KiB messages with 64 producers; p99 append ≤2 ms;
recovery ≤1 s per GiB of un-snapshotted log.

## 8. Implementation plan (separate PRs)
1. Segments, `GroupCommitWriter`, torn-write recovery; fault-injection tests.
2. `LedgerStateMachine` (states, offsets, redelivery, DLQ) and snapshots; property tests.
3. `Broker` API, `Inspector` and the admin endpoint; benchmarks.
4. Retention, truncation and compaction.
5. `ReplicatedLedger` on the HA cluster layer.

## 9. Open questions
- Client wire protocol (HTTP/3 streams vs custom framing): a separate doc.
- Per-key log compaction in v1: assumed no.
