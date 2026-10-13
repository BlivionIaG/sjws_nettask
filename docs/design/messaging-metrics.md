# Messaging metrics

Status: proposal, revised 2026-10-13 with input from sjws Messaging Core. Names in the [proposed](#proposed-names) section are **not implemented** and are **not stable**. They are marked `PROPOSED` so Messaging Core can change them before anything is scraped. The [shipped](#shipped-partition-lag) lag gauges are already in the tree; this doc does not rename them.

No code in this change. Nothing here adds a `ServerConfig` flag, a request-path branch, or a series on `/metrics`.

## What this lines up with

`PartitionLog` is the production storage log in [docs/design/architecture.md](architecture.md) (T3 storage, T5 diagram, T6 phase 2): segmented files, a sparse index, crash recovery, retention, and the fsync policies. The broker API next to it is `produce(batch)` and `fetch(partition, offset, maxBytes)`. There is no `PartitionLog` Java type. A search of open pull requests on 2026-10-13 found none that add one. The merged work that stands in for it is:

| Piece | Where | Role |
|---|---|---|
| Segmented log | `sjws_nettask.messaging.ledger.SegmentedLog` | append-only segments, the T3 layout |
| Group-commit writer | `sjws_nettask.messaging.ledger.LedgerWriter` | queue, group write/fsync, the ack future (`async`, `group`, `each`, `none`) |
| Committed offsets | `sjws_nettask.messaging.ledger.LedgerSnapshot` | log end, fsynced offset, unsynced count; published lock-free after each committed batch |
| Partition | `sjws_nettask.messaging.delivery.Partition` | `produce`, `poll` (fetch), lease, ack, nack, extend, DLQ, replay |
| Per-group stats | `sjws_nettask.messaging.stats.MessagingStats` | committed offset, in-flight, DLQ depth, oldest-pending age (PR #37) |

[docs/messaging/design.md](../messaging/design.md) §2 is that ledger. §5 lists `ReplicatedLedger` as a later package, with a `LocalOnly` implementation until the cluster adapter exists. §6 is one replication group per partition: the leader appends, every replica applies in index order, only the leader serves `poll` and `ack`, and the cluster log may share segments with the ledger so the log index and the ledger position are the same offset.

The cluster code that exists today is `sjws_nettask.cluster.ReplicationGroup` / `RaftGroup`: `term`, `isLeader`, `commitIndex`, `lastApplied`, and a private `matchIndex`. `RaftGroup.Role` is `FOLLOWER`, `CANDIDATE`, or `LEADER`. There is no in-sync replica set yet. Messaging Core will take replica state from the HA groups (PR #29 names `commitIndex` and `durableIndex`; this tree exposes `commitIndex()` and does not yet have `durableIndex`). A partition log and the control-plane Raft group are different groups ([docs/design/control-plane.md](control-plane.md)): losing the control plane does not truncate a partition log. Architecture T4 is the data-plane shape those gauges describe: one leader per partition, followers fetch, commit is a majority, consumers see only the high watermark.

HTTP produce and fetch through `core.Handler` is the next Messaging Core step. That handler, and `docs/design/partition-log.md`, are not in this tree yet. The latency wrappers below sit on that handler once it exists.

## Offsets and per-group stats

Log offsets live on `ledger.LedgerSnapshot`, published by `LedgerWriter.publish` through an atomic reference after every committed group (and after every background fsync). A reader takes one volatile load and does not lock the writer.

| Field | Meaning |
|---|---|
| `nextOffset` | log end: the first offset not yet committed. Readers must not read at or past it |
| `durableOffset` | fsynced point: every offset below this is fsynced. It is ≤ `nextOffset`, and equal to it in `GROUP` and `EACH` |
| `unsyncedRecords()` | `nextOffset - durableOffset`, the records acked and not yet fsynced |

Per-group consumer state lives on `MessagingStats` (PR #37): committed offset, in-flight, DLQ depth, and oldest-pending age, on `GroupStats`. `MessagingStats.NONE` is the off switch. When messaging is off, no registry is created, `snapshot()` returns one shared empty object, and nothing is walked or allocated per call.

## Shipped partition lag

`PartitionStatsRegistry.prometheus` already renders these gauges from that `MessagingStats` snapshot. Labels are `topic`, `partition` (quoted index), and `group`. This is the partition-lag family. Do not add a second one.

| Name | Meaning |
|---|---|
| `sjws_messaging_lag_offsets` | log end minus the group's committed offset; 0 when `lagMessages` is 0, because other groups' state records keep moving the log end |
| `sjws_messaging_lag_messages` | messages not yet acked or dead-lettered (enqueued + in flight) |
| `sjws_messaging_inflight` | messages leased to consumers |
| `sjws_messaging_dlq_depth` | messages waiting in the topic's DLQ; 0 on a DLQ partition |
| `sjws_messaging_oldest_pending_age_ms` | age of the oldest unresolved message; 0 when there is none |

The committed offset is on `GroupStats` and is the input to `sjws_messaging_lag_offsets`. It is not its own scraped series. In-flight, DLQ depth, and oldest-pending age are the three gauges above.

They are not appended to the HTTP `/metrics` page. `Partition.counts()` is the read the registry uses when messaging is on: one volatile load, no partition lock, not on the produce path beyond the republish the ledger already does after each entry.

The existing exposition has no cap on group names. The cap in [cardinality](#cardinality) is proposed for the new series. Applying that same cap to these five gauges is a follow-up for Messaging Core the next time that method changes. This doc does not change it.

## Latency buckets

Reuse `ServerMetrics.LATENCY_BOUNDS_NANOS` and `ServerMetrics.LATENCY_LE_LABELS`. Do not re-bin.

The scale is fixed log2, ratio exactly 2, from 1 ms through 16.384 s, then one `+Inf` bucket (`Long.MAX_VALUE`). Index `i` counts samples in `(bound[i-1], bound[i]]`. A snapshot stores those counts, not a cumulative histogram. The Prometheus text is cumulative, with `le` labels `0.001`, `0.002`, `0.004`, `0.008`, `0.016`, `0.032`, `0.064`, `0.128`, `0.256`, `0.512`, `1.024`, `2.048`, `4.096`, `8.192`, `16.384`, `+Inf`. Two nodes merge by adding the series that share an `le`. A histogram family is `_bucket`, `_sum` (seconds), and `_count`, and `_count` equals the `+Inf` bucket. Changing a bound or a label breaks that merge, the same way it would for `sjws_http_request_duration_seconds`.

`_sum` is the real sum of samples. It is not estimated from bucket midpoints.

## Proposed names

Every name in this section is `PROPOSED`.

The histogram names use the `*_duration_seconds` suffix so they match `sjws_http_request_duration_seconds`. sjws Messaging Core suggested `sjws_messaging_produce_seconds`. That shorter name is an open question; this proposal keeps `*_duration_seconds` until they pick one. Fetch uses the same suffix so the two histograms stay a pair.

### Produce latency

`sjws_messaging_produce_duration_seconds` — histogram.

Labels: `topic`, `partition` (the partition index, rendered as a decimal).

Produce path: `Partition.produce` → `LedgerWriter` queue → group write/fsync → apply → ack future. The sample starts when the batch is enqueued on `LedgerWriter` (`append` → `queue.put`) and ends when that ack future completes. That is the ack the producer waits for (`async`, `group`, `each`, or the replicated majority ack once `ReplicatedLedger` exists).

A duplicate `(producerId, seq)` that appends nothing is still one sample, in the same histogram. There is no `duplicate` label. Retries stay visible without doubling the series. A duplicate has no enqueue; its sample is the time until the already-completed future returns.

When the HTTP handler in the forthcoming `docs/design/partition-log.md` becomes the public ack point, the histogram keeps this name and still times enqueue through the ack future.

### Fetch latency

`sjws_messaging_fetch_duration_seconds` — histogram.

Labels: `topic`, `partition`, `group`. `group` is only on consumer series. There is no `consumer` label.

Fetch path: `Partition.poll(group, max, waitNanos)` → lease → ack / nack / extend. The Java method also takes `consumer` and `visibilityMs` (`poll(group, consumer, max, visibilityMs, waitNanos)`); Messaging Core's shorthand is the same call. The sample is request to response: from the poll invocation until the delivery list is returned, which includes the long-poll wait. Ack, nack, and extend are the rest of the lease and are outside this histogram.

When fetch moves onto `core.Handler`, the histogram still times that request to its response.

### Log offsets and high watermark

Gauges, read at scrape time from the published `LedgerSnapshot` (one volatile load). Labels: `topic`, `partition`. Omitted entirely when messaging is off (`MessagingStats.NONE`, or no ledger). A single-node process does not emit the replication family in their place.

| Name | Value |
|---|---|
| `sjws_messaging_log_end_offset` | `LedgerSnapshot.nextOffset` |
| `sjws_messaging_durable_offset` | `LedgerSnapshot.durableOffset`, the fsynced point |
| `sjws_messaging_unsynced_records` | `LedgerSnapshot.unsyncedRecords()` |
| `sjws_messaging_high_watermark_offset` | the offset consumers may read. On one node that is the durable offset, which in `async` mode is `nextOffset` (the ack point; `durableOffset` still trails by the unsynced count). In `group` and `each`, `nextOffset` and `durableOffset` are equal. In a cluster it is the HA layer's `commitIndex`, and `durableIndex` once PR #29 exposes that field |

`sjws_messaging_lag_offsets` stays log end minus the group's committed offset. The high watermark is a partition gauge, so an operator can see the consumer-visible end separately from the fsynced point.

### Replication status

Omitted entirely unless that partition has a replication group attached. A single-node `Partition` does not emit zeros for these, so a deployment without replication does not carry the families.

Gauges. Labels: `topic`, `partition`. No `peer` label: each replica is scraped on its own process, and Prometheus already has `instance`.

| Name | Value |
|---|---|
| `sjws_messaging_replication_leader` | 1 when `ReplicationGroup.isLeader()` is true, otherwise 0. `CANDIDATE` and `FOLLOWER` are both 0. A role label would publish three series for a value that is almost always leader or not. |
| `sjws_messaging_replication_term` | `ReplicationGroup.term()` |
| `sjws_messaging_replication_commit_offset` | `ReplicationGroup.commitIndex()`. While design §6 holds (log index and ledger position are the same), this is the committed ledger offset. On a cluster this is also the high watermark until `durableIndex` exists. |
| `sjws_messaging_replication_applied_offset` | `ReplicationGroup.lastApplied()` |
| `sjws_messaging_replication_lag_offsets` | `max(0, commitIndex - lastApplied)` on this node. This is apply lag. Consumer lag stays `sjws_messaging_lag_offsets`. |

There is no ISR series. Messaging Core has no in-sync set yet; replica state will come from the HA groups. `RaftGroup` keeps `matchIndex` private, and `ReplicationGroup` does not expose it. Do not read the private field, and do not report an in-sync gauge as 1 while that signal is missing.

Overflow partitions are not folded into these gauges. Summing two leaders into `other` would be a lie. Partitions past the topic cap are left out, and one unlabeled gauge counts them: `sjws_messaging_replication_partitions_omitted`.

## Cardinality

Labels that are not allowed, on any series in this doc: producer id, producer sequence, consumer id, message key, client address, header value. Those are unbounded.

`partition` is the configured index, an int rendered as a decimal string, except the overflow value `other`. The shipped lag gauges use a decimal only. `other` is new and only for the proposed series. `group` appears only on consumer series (the shipped lag gauges, and fetch latency).

Safety caps, applied when the text is rendered, not on the produce path. sjws Messaging Core capped topics at about 1000, with an `other` bucket:

| Dimension | Cap | Overflow |
|---|---|---|
| distinct `topic` values on the latency histograms and the offset gauges | 1000 | fold into `topic="other"`, `partition="other"`. Histograms add bucket `i` to bucket `i` and add `_sum`. Gauges add the offset or the unsynced count |
| `(topic, partition, group)` pairs for fetch latency | 4096, and at most 64 groups on one pair | `group="other"` |
| replication gauges | the same 1000 topics | omit the topic's partitions and add them to `sjws_messaging_replication_partitions_omitted` |

1000 topics is a rail, not a sizing target. The config default is 3 partitions and replication 3 (`MessagingSettings`). A process that stays near that default is far under the cap. A histogram at the cap is one series set per admitted `(topic, partition)`, 18 lines each (16 buckets + `_sum` + `_count`). Folding keeps a buggy topic list from making the scrape unbounded.

Group overflow on the shipped lag gauges is deliberately not part of this proposal. See [shipped partition lag](#shipped-partition-lag).

## Hook points

Off means the listener is absent. The cost is the same shape as HTTP observability: the filter is not installed, so the hot path does not call `nanoTime`, does not update a bucket, and does not look at a registry.

1. **Produce.** `Partition.produce` and `LedgerWriter` stay as they are. The caller that times the ack is the forthcoming `core.Handler` produce path (see `docs/design/partition-log.md`), and only when that caller was given a listener. The listener is null when metrics are off, and the call is the plain produce. When the listener is present, the wrapper reads the clock at enqueue onto the `LedgerWriter` queue and records the sample when the ack future completes, on the completion thread. `SegmentedLog` and `LedgerWriter` do not gain metric calls. A log opened by a test does not pay for them.

2. **Fetch.** The same wrapper around `Partition.poll`, including the waiting overload `poll(group, consumer, max, visibilityMs, waitNanos)`. The sample is request to response. The waiting poll already reads the clock for its own deadline. This proposal does not add another read inside `Partition`. The wrapper's read sits outside, and only when the listener is installed. The same wrapper moves onto `core.Handler` when HTTP fetch lands.

3. **Partition lag.** Already behind `MessagingStats.NONE` (PR #37). No new hook.

4. **Offsets and high watermark.** Scrape-time only. Copy `LedgerSnapshot` (already published lock-free per committed batch) and, when a replication group is attached, `commitIndex` / `durableIndex`. The produce path does not publish these gauges. With messaging off, the families are absent from the text.

5. **Replication.** The Raft thread writes a few volatiles when leadership or the commit index changes (`onLeadership`, commit advance). A scrape copies them. The produce path does not read them. With no group attached, the families are absent from the text, not a block of zeros. Replica membership waits on the HA groups.

6. **`PartitionLog` and `core.Handler`.** When HTTP produce and fetch replace the direct `Partition` calls as the public ack and fetch points, the wrappers move with those handler methods. The series names do not change. Internal segment reads stay untimed.

7. **Scrape.** A later change can append `PartitionStatsRegistry.prometheus` and these families to HTTP `/metrics` when a server holds a `MessagingStats` other than `NONE`, using the same rule as cluster gauges: a null source adds no lines and does not install a filter. That wiring waits until Messaging Core accepts the names. Until then the messaging text stays on `PartitionStatsRegistry.prometheus`, which callers already have.

## Open points for sjws Messaging Core

- Confirm or replace every `PROPOSED` name before it is scraped.
- Naming: this proposal is `sjws_messaging_produce_duration_seconds` and `sjws_messaging_fetch_duration_seconds`, matching `sjws_http_request_duration_seconds`. Messaging Core suggested `sjws_messaging_produce_seconds`. Say which form to keep. Fetch should use the same suffix as produce.
- Produce samples include duplicate hits, with no extra label. Say if duplicates should be dropped instead.
- `sjws_messaging_high_watermark_offset` on one node follows their rule (the durable offset, and `nextOffset` in `async` mode). In a cluster it follows HA `commitIndex` until `durableIndex` from PR #29 is a public field. Say if `durableIndex` should replace `commitIndex` as soon as it exists, or be a second gauge.
- `partition="other"` is a non-integer label value used only for the topic overflow bucket on the proposed series.
- Whether these families join `/metrics` or stay on the messaging exposition is a follow-up, after the names are agreed.
