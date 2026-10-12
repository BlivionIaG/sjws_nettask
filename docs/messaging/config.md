# Messaging configuration and broker-side apply

Status: Draft (proposal pending Blivion and the HA/control-plane workstream) · Base: master d592aaf

## 0. Context and assumptions
The control-plane (CP) direction (via inceptor, 2026-10-12): Raft control nodes push **versioned config** to
**labeled data-plane nodes**, and the message broker is one node role. HA owns `docs/design/control-plane.md`.
**As of d592aaf, that document has no PR, branch or file, and no CP interfaces exist in the code. The
`cluster` package from #29 is also not on master.** This doc therefore defines (a) the declarable messaging schema and
(b) the broker-side apply contract against an **assumed** CP interface (§5). Implementation waits until the CP interfaces land.

## 1. Principles
1. **Declarative and idempotent.** The CP pushes the full desired messaging state at version `v`. A broker applies
   the diff between its current state and `v`. Applying `v` twice is a no-op, and versions older than the applied one are ignored.
2. **Zero cost when off.** Without a `messaging:` section, or with `enabled: false`, no ledger, thread or stats registry exists
   (`MessagingStats.NONE`, #37).
3. **Safe by default.** Destructive changes (deleting a topic or group, lowering `partitions`) need an explicit
   `allow_destructive` on that object, and partitions are never deleted implicitly.
4. **The data path never waits on the CP.** Config changes the set of partitions, groups and policies a broker hosts.
   Produce, poll and ack never consult the CP.

## 2. Schema (YAML, same conventions as `docs/sjws.yaml`: bare durations are seconds, sizes like `16m`)
```yaml
messaging:
  enabled: true
  version: 42                      # set by the CP; monotonically increasing; brokers report the applied version
  defaults:
    partitions: 3
    replication: 3                 # replicas per partition (1 = no replication)
    durability: replicated         # ack default: replicated (majority in-memory) | async (single node)
    durability_opt_in: [strict, group]   # what producers or topics may request per append
    retry: { max_attempts: 5, backoff_base: 100ms, backoff_max: 30s }
    visibility_timeout: 30s
    retention: { max_age: 7d, max_bytes: 10g }   # applies only below the low watermark (§2.5 of design.md)
    segment_bytes: 1g
  placement:
    broker_selector: { role: broker }            # node labels set by the CP
    spread_by: zone                              # spread replicas across this label (optional)
  topics:
    orders:
      partitions: 6
      replication: 3
      durability: replicated
      durability_opt_in: [strict]                # producers may request strict (quorum + fsync)
      retry: { max_attempts: 8 }                 # merged over defaults
      dlq: { enabled: true, retention: { max_age: 30d } }   # <topic>.dlq; enabled by default
      placement: { broker_selector: { role: broker, tier: ssd } }
      groups:
        billing:  { start: earliest }            # earliest | latest | offset:<n>, used at creation only
        shipping: { start: latest, visibility_timeout: 2m, retry: { max_attempts: 3 } }
    audit:
      partitions: 1
      replication: 3
      durability: strict                         # topic default: every ack is quorum + fsync
      dlq: { enabled: false }
      allow_destructive: false
```
Validation, at the CP before push and again at the broker:
- **Names:** topic names match `[a-z0-9][a-z0-9._-]{0,127}` and must not end in `.dlq`. Group names follow the same rule.
- **Numbers:** `1 ≤ replication ≤ |eligible brokers|` and `partitions ≥ 1`.
- **Retry and durability:** `max_attempts ≥ 1`, and `backoff_max ≥ backoff_base`. `durability` ∈ {async, group, replicated, strict}; `async`/`group` mean local-only and require `replication: 1`.
- **Destructive changes:** lowering `partitions` or removing a topic or group without `allow_destructive: true` is a validation error, not a silent drop.

The parser reuses `config.YamlValues` and `Durations`/`Sizes`, so the messaging section joins `ConfigDocument`/`ConfigSnapshot`.

## 3. Mapping to the code that exists
| Config | Code (#21/#33/#37) |
|---|---|
| `retry.*` | `RetryPolicy(maxAttempts, backoffBaseMs, backoffMaxMs)` |
| `durability` (single node) | `ledger.Durability` ASYNC/GROUP; `replicated`/`strict` map to the cluster `RELAXED`/`STRICT` modes (design.md §6) |
| `durability_opt_in` | allowed values of the per-append override (`LedgerWriter.append(recs, Durability)`) |
| `dlq.enabled` | `Partition` opens `<topic>.dlq` (today always; it becomes conditional) |
| `groups.<g>.start` | `Partition.subscribe(group, fromStart)` (plus `offset:<n>`, to add) |
| `visibility_timeout` | default for `poll(..., visibilityMs)` |
| `segment_bytes`, `retention` | `SegmentedLog` segment size; retention is step 4 (task A) |
| stats | `PartitionStatsRegistry` registers each hosted partition (#37) |

## 4. Placement and rebalance
- **Assignment** is computed by the CP leader and pushed as part of `v`: `assignment: { "orders/0": [n1, n3, n5], ... }`,
  with the first replica as the preferred leader. Candidates are nodes whose labels match `broker_selector`.
  Replicas spread by `spread_by` (zone) first, then by fewest hosted partitions, ties broken by the stable hash of
  `topic/partition`. The CP owns this computation, so every broker sees the same assignment for `v`.
- **Leaders** come from each partition's replication group (one Raft group per partition, design.md §6), not from the CP.
  `preferred leader` is a hint; the CP may call `transferLeadership` to rebalance leaders.
- **Moving a replica (rebalance), make-before-break:**
  1. The CP adds the new replica as a **learner** (`changeMembership(voters, learners + new)`).
  2. The learner catches up: snapshot install plus log tail (step 3: on-disk state snapshots).
  3. Once the learner's lag is ≤ `catch_up_entries`, it is promoted to voter by joint consensus.
  4. The old replica is removed.
  5. Only after removal is committed does the old node delete its local data (after `grace`, default 10 min).

  Consumer leases and offsets live in the ledger, so they move with the partition and nothing is reset.
- **Triggers:** a node joining or leaving the broker label set, a `partitions` increase, an operator `rebalance` call.
  The CP never rebalances on a transient node failure; that is failover, handled by Raft. It rebalances only after `node_dead_after` (default 10 min).
- **Partition increase:** new partitions start empty, and keyed producers see a new key→partition mapping from the next version.
  This is documented as reordering across the change; no data moves.
- **Partition decrease:** not supported in v1. It errors even with `allow_destructive`; a v2 migration plan is needed.

## 5. Broker-side apply contract (assumed CP interface)
```java
interface ConfigSubscriber<T> {                 // provided by the CP client on each data-plane node
  void onConfig(long version, T desired);        // in version order, at least once
}
interface ApplyReport {                          // reported back to the CP (and shown by the TUI)
  long appliedVersion();
  Map<String, String> errors();                  // object path → reason; empty when fully applied
}
```
`MessagingConfigApplier implements ConfigSubscriber<MessagingConfig>`:
1. If `version ≤ applied`, ignore it (idempotent, at-least-once delivery safe).
2. Validate (§2). On error, keep the old state and report `errors`. The broker never applies half a version.
3. Diff against the current state:
   - **Open:** partitions assigned to this node and not open (open the ledger, replay, register stats, join the replication group).
   - **Update in place:** retry and visibility settings (an atomic swap of the partition policy; in-flight leases keep their deadlines),
     `durability` defaults (new appends only) and retention.
   - **Create groups:** new groups get a `SUBSCRIBE` record on the leader. Group creation is replicated via the log, so it happens once.
   - **Close:** partitions no longer assigned to this node (close and unregister; delete after `grace` only once removal from the
     replica set is committed).
4. Persist `appliedVersion` atomically (temp file plus rename) **after** the side effects, so a crash re-applies the same version idempotently.
5. Report `ApplyReport`.

All steps run on one applier thread, off the data path. Opening a large partition is bounded by step 3's on-disk snapshots.

## 6. Review of the control-plane design: what Messaging needs from it
There is no control-plane document or PR to review yet, so these are the requirements for it:
1. **Versioned full-state push** with a monotonic version, delivered in order and at least once, plus an `ApplyReport` back-channel
   (applied version, errors) per node.
2. **Node labels** in membership, including `role: broker`, so placement can select brokers, plus a topology label (zone) for spreading.
3. **An assignment computed by the CP leader**, part of the versioned config and deterministic, so brokers never compute placement themselves.
4. **Access to replication-group membership changes** (learner add, joint-consensus promote, remove) and `transferLeadership`.
   The #29 cluster API had these, but its code is not on master d592aaf; please confirm where it now lives.
5. **Separation of planes:** the CP Raft group holds config and assignment only. Message data goes through per-partition groups
   (design.md §6) and never through the CP log.
6. **Fencing:** the CP must not move a partition away from a node until the node's removal is committed in that partition's group,
   and the leader epoch must be exposed (cf. the #29 review: conditional append on term).
7. **Zero cost when off:** a node without the broker role gets no messaging section, and nothing messaging-related starts.

## 7. Implementation plan
1. A `messaging:` section in `ConfigDocument` (parse and validate). Single node, no CP: apply on start and on reload, which is useful now.
2. `MessagingConfigApplier` against `ConfigSubscriber`, once the CP interfaces exist.
3. Rebalance hooks (learner → voter) on the replication groups.
