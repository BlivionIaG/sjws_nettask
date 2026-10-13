# Messaging metrics

Status: proposal, 2026-10-13. Names in the [proposed](#proposed-names) section are **not implemented** and are **not stable**. They are marked `PROPOSED` so Messaging Core can change them before anything is scraped. The [shipped](#shipped-partition-lag) lag gauges are already in the tree; this doc does not rename them.

No code in this change. Nothing here adds a `ServerConfig` flag, a request-path branch, or a series on `/metrics`.

## What this lines up with

`PartitionLog` is the production storage log in [docs/design/architecture.md](architecture.md) (T3 storage, T5 diagram, T6 phase 2): segmented files, a sparse index, crash recovery, retention, and the fsync policies. The broker API next to it is `produce(batch)` and `fetch(partition, offset, maxBytes)`. There is no `PartitionLog` Java type. A search of open pull requests on 2026-10-13 found none that add one. The merged work that stands in for it is:

| Piece | Where | Role |
|---|---|---|
| Segmented log | `sjws_nettask.messaging.ledger.SegmentedLog` | append-only segments, the T3 layout |
| Group-commit writer | `sjws_nettask.messaging.ledger.LedgerWriter` | the ack future (`async`, `group`, `each`, `none`) |
| Partition | `sjws_nettask.messaging.delivery.Partition` | `produce`, `poll` (fetch), ack, DLQ, replay |
| Stats | `sjws_nettask.messaging.stats.PartitionStatsRegistry` | lock-free lag snapshot and its own text exposition |

[docs/messaging/design.md](../messaging/design.md) §2 is that ledger. §5 lists `ReplicatedLedger` as a later package, with a `LocalOnly` implementation until the cluster adapter exists. §6 is one replication group per partition: the leader appends, every replica applies in index order, only the leader serves `poll` and `ack`, and the cluster log may share segments with the ledger so the log index and the ledger position are the same offset.

The cluster code that exists today is `sjws_nettask.cluster.ReplicationGroup` / `RaftGroup`: `term`, `isLeader`, `commitIndex`, `lastApplied`, and a private `matchIndex`. `RaftGroup.Role` is `FOLLOWER`, `CANDIDATE`, or `LEADER`. A partition log and the control-plane Raft group are different groups ([docs/design/control-plane.md](control-plane.md)): losing the control plane does not truncate a partition log. Architecture T4 is the data-plane shape those gauges describe: one leader per partition, followers fetch, commit is a majority, consumers see only the high watermark.

## Shipped partition lag

`PartitionStatsRegistry.prometheus` already renders these gauges. Labels are `topic`, `partition` (quoted index), and `group`. This is the partition-lag family. Do not add a second one.

| Name | Meaning |
|---|---|
| `sjws_messaging_lag_offsets` | log end minus the group's committed offset; 0 when `lagMessages` is 0, because other groups' state records keep moving the log end |
| `sjws_messaging_lag_messages` | messages not yet acked or dead-lettered (enqueued + in flight) |
| `sjws_messaging_inflight` | messages leased to consumers |
| `sjws_messaging_dlq_depth` | messages waiting in the topic's DLQ; 0 on a DLQ partition |
| `sjws_messaging_oldest_pending_age_ms` | age of the oldest unresolved message; 0 when there is none |

They are not appended to the HTTP `/metrics` page. `MessagingStats.NONE` is the off switch: no registry is created, `snapshot()` returns one shared empty object, and nothing is walked or allocated per call. `Partition.counts()` is the read the registry uses when messaging is on: one volatile load, no partition lock, not on the produce path beyond the republish the ledger already does after each entry.

The existing exposition has no cap on group names. The cap in [cardinality](#cardinality) is proposed for the new series. Applying that same cap to these five gauges is a follow-up for Messaging Core the next time that method changes. This doc does not change it.

## Latency buckets

Reuse `ServerMetrics.LATENCY_BOUNDS_NANOS` and `ServerMetrics.LATENCY_LE_LABELS`. Do not re-bin.

The scale is fixed log2, ratio exactly 2, from 1 ms through 16.384 s, then one `+Inf` bucket (`Long.MAX_VALUE`). Index `i` counts samples in `(bound[i-1], bound[i]]`. A snapshot stores those counts, not a cumulative histogram. The Prometheus text is cumulative, with `le` labels `0.001`, `0.002`, `0.004`, `0.008`, `0.016`, `0.032`, `0.064`, `0.128`, `0.256`, `0.512`, `1.024`, `2.048`, `4.096`, `8.192`, `16.384`, `+Inf`. Two nodes merge by adding the series that share an `le`. A histogram family is `_bucket`, `_sum` (seconds), and `_count`, and `_count` equals the `+Inf` bucket. Changing a bound or a label breaks that merge, the same way it would for `sjws_http_request_duration_seconds`.

`_sum` is the real sum of samples. It is not estimated from bucket midpoints.

## Proposed names

Every name in this section is `PROPOSED`.

### Produce latency

`sjws_messaging_produce_duration_seconds` — histogram.

Labels: `topic`, `partition`.

The sample is the time from the call that invokes `Partition.produce` until that call's durability future completes. That is the ack the producer waits for (`async`, `group`, `each`, or the replicated majority ack once `ReplicatedLedger` exists). It is not the time spent holding the partition lock, and it is not a later socket write.

A duplicate `(producerId, seq)` that appends nothing is still one sample, in the same histogram. There is no `duplicate` label. Retries stay visible without doubling the series.

When `PartitionLog.append` replaces `Partition.produce` as the public ack point, the histogram keeps this name and still times the caller-visible ack.

### Fetch latency

`sjws_messaging_fetch_duration_seconds` — histogram.

Labels: `topic`, `partition`, `group`.

The sample is the time from the call that invokes `Partition.poll` (including the waiting poll) until the delivery list is returned. That includes the long-poll wait. It is the time the caller saw, which is the number an operator compares to a timeout. A second histogram that excludes the wait is out of scope; shipping both would freeze two meanings. If Messaging Core wants service time instead, change this definition before the name is implemented. Do not add a second series later and leave this one meaning something else.

`group` is on the series because `poll` is per consumer group. There is no `consumer` label.

When fetch moves to `PartitionLog` (index lookup plus a segment read, or sendfile for a lagging consumer), the histogram still times the caller-visible fetch, not an internal read inside `SegmentedLog`.

### Replication status

Omitted entirely unless that partition has a replication group attached. A single-node `Partition` does not emit zeros for these, so a deployment without replication does not carry the families.

Gauges. Labels: `topic`, `partition`. No `peer` label: each replica is scraped on its own process, and Prometheus already has `instance`.

| Name | Value |
|---|---|
| `sjws_messaging_replication_leader` | 1 when `ReplicationGroup.isLeader()` is true, otherwise 0. `CANDIDATE` and `FOLLOWER` are both 0. A role label would publish three series for a value that is almost always leader or not. |
| `sjws_messaging_replication_term` | `ReplicationGroup.term()` |
| `sjws_messaging_replication_commit_offset` | `ReplicationGroup.commitIndex()`. While design §6 holds (log index and ledger position are the same), this is the committed ledger offset. If `PartitionLog` later uses a different index, the hook maps it once. The series stays an offset in the partition log. |
| `sjws_messaging_replication_applied_offset` | `ReplicationGroup.lastApplied()` |
| `sjws_messaging_replication_lag_offsets` | `max(0, commitIndex - lastApplied)` on this node. This is apply lag. Consumer lag stays `sjws_messaging_lag_offsets`. |
| `sjws_messaging_replication_insync` | 1 when this node is in the ISR, otherwise 0. |

`sjws_messaging_replication_insync` waits on a public signal. `RaftGroup` keeps `matchIndex` private, and `ReplicationGroup` does not expose it or an in-sync bit. Do not read the private field. The series is omitted, not reported as 1, until `ReplicationGroup` grows something like `inSync()` (leader, or a follower whose match index has reached the commit index the leader advertised). Architecture T4's high watermark is that commit index.

Overflow partitions are not folded into these gauges. Summing two leaders into `other` would be a lie. Partitions past the cap are left out, and one unlabeled gauge counts them: `sjws_messaging_replication_partitions_omitted`.

## Cardinality

Labels that are not allowed, on any series in this doc: producer id, producer sequence, consumer id, message key, client address, header value. Those are unbounded.

`partition` is the configured index, rendered as a decimal string, except the overflow value `other`. The shipped lag gauges use a decimal only. `other` is new and only for the proposed series.

Safety caps, applied when the text is rendered, not on the produce path:

| Dimension | Cap | Overflow |
|---|---|---|
| `(topic, partition)` pairs for the two latency histograms | 1024 | fold into `topic="other"`, `partition="other"`, adding bucket `i` to bucket `i` and adding `_sum` |
| `(topic, partition, group)` pairs for fetch latency | 4096, and at most 64 groups on one pair | `group="other"` |
| replication gauges | the same 1024 pairs | omit the pair and increment `sjws_messaging_replication_partitions_omitted` |

1024 pairs is a rail, not a sizing target. The config default is 3 partitions and replication 3 (`MessagingSettings`). A process that stays near that default is far under the cap. A histogram at the cap is 1024 × 18 series (16 buckets + `_sum` + `_count`) per histogram. Folding keeps a buggy topic list from making the scrape unbounded.

Group overflow on the shipped lag gauges is deliberately not part of this proposal. See [shipped partition lag](#shipped-partition-lag).

## Hook points

Off means the listener is absent. The cost is the same shape as HTTP observability: the filter is not installed, so the hot path does not call `nanoTime`, does not update a bucket, and does not look at a registry.

1. **Produce.** `Partition.produce` stays as it is. The caller that should time it is the future broker or the messaging HTTP handler, and only when that caller was given a listener. The listener is null when metrics are off, and the call is the plain `produce`. When the listener is present, the wrapper reads the clock before `produce` and records the sample when the durability future completes, on the completion thread, not under the partition lock. `SegmentedLog` and `LedgerWriter` do not gain metric calls. A log opened by a test does not pay for them.

2. **Fetch.** The same wrapper around `Partition.poll`, including the waiting overload. The waiting poll already reads the clock for its own deadline. This proposal does not add another read inside `Partition`. The wrapper's read sits outside, and only when the listener is installed.

3. **Partition lag.** Already behind `MessagingStats.NONE`. No new hook.

4. **Replication.** The Raft thread writes a few volatiles when leadership or the commit index changes (`onLeadership`, commit advance). A scrape copies them. The produce path does not read them. With no group attached, the families are absent from the text, not a block of zeros.

5. **`PartitionLog`.** When that type replaces the current log as the public append and fetch point, the wrapper moves with the public methods. The series names do not change. Internal segment reads stay untimed.

6. **Scrape.** A later change can append `PartitionStatsRegistry.prometheus` and these families to HTTP `/metrics` when a server holds a `MessagingStats` other than `NONE`, using the same rule as cluster gauges: a null source adds no lines and does not install a filter. That wiring waits until Messaging Core accepts the names. Until then the messaging text stays on `PartitionStatsRegistry.prometheus`, which callers already have.

## Open points for Messaging Core

- Confirm or replace every `PROPOSED` name before it is scraped.
- Produce samples include duplicate hits, with no extra label. Say if duplicates should be dropped instead.
- Fetch samples include the long-poll wait. Say if the histogram should be service time instead. Do not ask for both under this name.
- `sjws_messaging_replication_insync` needs a public ISR or match-index signal on `ReplicationGroup`. Until that exists the series is omitted.
- `partition="other"` is a non-integer label value used only for overflow on the proposed series.
- Whether these families join `/metrics` or stay on the messaging exposition is a follow-up, after the names are agreed.
