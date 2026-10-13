# Messaging configuration and broker-side apply

Status: Draft (proposal pending Blivion and the HA/control-plane workstream) · Base: master d592aaf

## 0. Context and assumptions
The control-plane (CP) direction (via inceptor, 2026-10-12): Raft control nodes push **versioned config** to
**labeled data-plane nodes**, and the message broker is one node role. HA owns `docs/design/control-plane.md`.
**Update:** the control-plane design is now [PR #38](https://github.com/BlivionIaG/sjws_nettask/pull/38) (`docs/design/control-plane.md`). Its §13.5 `topics:` list is the anchor this schema extends. No CP interfaces exist in code yet, so the apply contract below stays an assumed interface until #38's implementation PRs land.
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
Requirements on the control plane (posted as review feedback on #38):
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
