# Stalled STRICT override: operator escape

When a per-append `Durability.STRICT` override was accepted in a RELAXED group and the second
data node then fails, that entry (and every entry behind it) waits without limit for
`dataCopiesRequired()` data copies. Gauges and a WARN make the stall visible; stepping down
does not help (#203, #208).

## Preferred recovery

Restore the missing data node (or heal the partition). The STRICT entry commits once two data
copies hold it.

## Operator escape (fail-safe)

If the data node cannot return in time, an **explicit** escape on the **current leader** appends
a new `STRICT_RELEASE` log marker that names the highest stalled uncommitted `USER_STRICT` index.
Existing entries are never rewritten or truncated. Once the marker is in the leader's log, the
STRICT barrier for covered indexes is dropped so the group can commit with ordinary quorum; every
replica that applies the marker (and every `becomeLeader` rescan that sees it, committed or not)
honours the same upto:

```text
ClusterNode.releaseStalledStrictOverrides(groupId, operatorId)
```

| Guard | Behaviour |
| --- | --- |
| Caller | Must pass a non-blank `operatorId` (authenticated admin identity, e.g. mTLS SAN / CP operator id). Blank is refused. |
| Authorization | Gate the call with `Permission.RELEASE_STALLED_STRICT` (role OPERATOR or ADMIN) the same way other admin mutations are gated. |
| Stall age | Oldest pending STRICT override must be at least **2 × lease** old (same as the stall WARN). Younger entries return `0` and are unchanged. |
| Group mode | `GroupOptions` STRICT groups are refused — this escape is only for per-append overrides in a RELAXED group. |
| Leadership | Not the leader → `NotLeaderException`. |
| Audit | One WARNING: `audit strict.release: operator=… group=… uptoIndex=… released=…` (control characters stripped from `operatorId`). |
| Metric | `ReplicatedNode.strictOverridesReleased(group)` increases by the number of overrides the marker covers. |
| Idempotent | A second call with nothing pending returns `0`. |

This **weakens durability** for the released entries (they no longer wait for two data disks). It
does not run on a timer and does not change the group's configured durability. The original
`USER_STRICT` bytes stay on disk; the release is the new marker alone, so Raft log matching is
preserved if a follower that stored the STRICT entry later becomes leader.

## Procedure

1. Confirm the stall: `strictPending(group) > 0`, `oldestStrictPendingAgeNanos` ≥ 2 × lease, and the
   WARN naming `1 of 2 data copies` (or similar).
2. Attempt to restore the data node.
3. If escape is required: authenticate as OPERATOR/ADMIN, call
   `releaseStalledStrictOverrides(group, <operatorId>)` on the leader, confirm the audit line and
   that `strictPending` returns to 0 and the waiting futures complete (or fail per Raft rules).
4. Keep the audit trail; treat the released entries as RELAXED for RPO purposes.
