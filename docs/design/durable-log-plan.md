# Durable log: remaining plan

Status: plan · Docs only · Follows [partition-log.md](partition-log.md) (G1–G4 are merged; G2's blocked-retention stat was not built). Base: master.

This plan covers what is left for the plain partition log (`/messaging/v1`, `LogTopics`, `SegmentedLog`) after
G1–G4: segment compaction, consumer offsets, and replication to the HA peer. The delivery `Partition` already has
snapshot-covered compaction and group cursors, so each step below reuses that code where it fits instead of adding a
second mechanism. Each step is one PR. The benches are `MessagingLogBench` (produce, `fetch`, `fetch-fixed`) and
`bench/messaging-http/run.sh`, before/after, ≥5 alternating runs under flock, with the load recorded.

## 1. Segment compaction (keyed topics)

- **Scope:** opt-in per topic (`compaction: key`). Only sealed segments are rewritten, keeping the last record per key.
  The topic's records carry a key: the first `[len:int][key]` field of the record body, the same framing the produce
  API already uses, so there is no format change.
- **Mechanism:** this is the same copy, fsync, marker, swap protocol as `Segment.writeCompacted` and
  `SegmentedLog.commitCompaction`. That protocol is already crash-tested for `Partition` (marker, then atomic rename,
  with recovery honouring the marker). The key index is built off-lock from the sealed segments, newest first.
  Fetch does not tolerate gaps yet: a compacted `Segment.read` returns null for a missing offset and
  `MessagingHttp.fetch` stops there, so this step must make fetch skip to the next retained batch and report it as
  `Next-Offset`.
- **Readers:** the swap takes the topic's `reads` write lock (#155), so in-flight fetches finish on the old file.
- **Retention interplay:** compaction runs only below `min(retention low watermark, oldest consumer offset)` once
  §2 lands, so a slow consumer never sees a key's history disappear under it.
- **Tests:** a crash at each step of the protocol (as in G1b); a fetch spanning a compacted gap; the last value per
  key survives a restart.
- **Bench:** retention/compaction run off the hot path. The bench checks that produce and fetch are unchanged during a
  compaction pass.
- **As built:** topics are listed in `sjws.messaging.compaction.topics` / `SJWS_MESSAGING_COMPACTION_TOPICS`
  (comma-separated); passes run on the retention timer (`sjws.messaging.retention.check.ms`), which exists only when
  retention or compaction is configured. The key map is built from sealed segments only (they are fsynced at seal), and
  the unit dropped is a whole batch: a batch stays if any of its records is a key's last value or has no key, because
  a frame's offsets are contiguous and its CRC covers every record. Fetch on a compacted topic uses
  `SegmentedLog.readCeil` to skip gaps; unkeyed topics keep the plain `read`. Bench: `bench/LogCompactionBench.java`.

## 2. Consumer offsets

- **API:** `PUT /messaging/v1/topics/{t}/offsets/{group}` `{"offset":n}` and `GET` of the same path. The offset is
  the next offset to fetch (Kafka semantics). A commit below the log start returns 416, and one above the
  log-end offset returns 400.
- **Storage:** one internal compacted topic per node, `__offsets`, keyed `topic\0group`, using §1. It is written with
  `Durability.GROUP` by default (the per-request header may strengthen it, as in G3). There is no new file format: an
  offset is a record and recovery is a replay.
- **Retention:** the oldest committed offset of every group becomes a retention floor, exactly like the retain-from
  pin (#149) does for `Partition`. A group with no commit for `offsets.expiryMs` (default 7 days) stops pinning.
  That bounds disk when a consumer disappears.
- **Tests:** commit, then crash, then restart returns the committed offset; the retention floor holds history that a
  committed-but-unfetched group still needs; expiry releases it.
- **Metrics:** `sjws_messaging_lag_offsets` (log-end minus committed; the lag series that
  [messaging-metrics.md](messaging-metrics.md) already defines), fed through a new `MessagingHooks` method (it has only
  `produced` and `fetched`).

- **As built:** `LogOffsets`. Record = `[klen][topic\0group][offset:long][commitMs:long]`. `__offsets` is always
  key-compacted, is excluded from size/age retention, and is opened/replayed lazily (first commit or read, or
  maintenance finding the directory), so nothing changes while offsets are unused. The first commit starts the
  maintenance timer. Expiry: `sjws.messaging.offsets.expiry.ms` (default 7 days, measured from the group's last
  commit). The floor also caps §1 compaction (`LogCompaction.prepare(log, floor)`). User produce to `__*` topics is
  400. Lag: `MessagingHooks.offsetCommitted(topic, group, lag)` (default no-op; no exporter yet).
  Bench: `bench/OffsetCommitBench.java`.

## 3. Replication to HA peers

This follows the [two-node HA decision](two-node-ha-decision.md): two data nodes plus a witness (option A, majority of three voters). The log
replicates under the same leadership (`RaftGroup` term and lease), not under a second consensus.

- **Unit:** the raw RecordBatch frames, which are byte-identical on disk and on the wire (G1a). The standby fetches
  from the primary with the same `GET .../records?offset=` call, so the replicated bytes are the bytes the primary
  wrote, and the per-batch CRC (§2.1 of partition-log.md) verifies them end to end.
- **Durability levels:** `Durability-Effective` gains `replicated`: the produce ack waits until the standby has
  written (fsync per its own policy) through that offset. The existing modes are unchanged and local. A topic policy
  can require `replicated`, and the header can only strengthen it (G3 clamp).
- **Fencing:** every replicated batch carries the leader term in the segment's sidecar. On failover the new primary
  truncates any suffix it has above the last offset acknowledged under the old term, reusing the
  `VipFence`/lease rules. A deposed primary's writer is fenced and its pending futures fail, as in the
  writer-failure path of #169.
- **Catch-up:** a standby that has fallen behind past the primary's retention restarts from the oldest segment and
  then streams. Compacted topics replicate the compacted segments as files.
- **Tests:** SimTransport scenarios (partition, failover, a deposed leader with an un-replicated suffix) asserting
  that no acknowledged `replicated` record is lost and that no suffix written under a deposed term is visible after
  failover.
- **Bench:** produce p50/p99 at `replicated` vs `group` on loopback, and standby lag under sustained produce.

## Order and dependencies

1. §2 offsets, storage only, without the topic: the `__offsets` writes use a plain topic first and switch to a
   compacted one after §1.
2. §1 compaction.
3. §3 replication. This depends on the HA leadership work, not on §1/§2, so it can run in parallel once the HA
   owner signs off on the term sidecar.
