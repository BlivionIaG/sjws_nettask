# Two-node HA decision

Status: decision for Blivion · Docs only · Base: master `a9ab12d`

This settles dual-node HA before more replication code is written. It compares a witness
(option A) with async primary/standby (option B) against the classes already in
`sjws_nettask.cluster` and `sjws_nettask.ha`. Serving mode (active/active versus a VIP)
is a separate knob and is not this choice. [HA design](../ha/design.md) §4.1 is the
serving sketch; [architecture](architecture.md) T4 points here.

## 1. Two voters and no tie-break is unsafe

`QuorumPolicy.majorityCount` is `voters / 2 + 1` (`QuorumPolicy.java`). For two voters
that is 2. `QuorumPolicy.majority` returns true only when at least that many of the
voter set are in the ack set. One ack is not a quorum. The table in
`QuorumPolicyTest.majorityCountForOneThroughSeven` checks n=2: one ack is false, two
acks are true.

`RaftGroup.startElection` counts the candidate's own vote and calls `becomeLeader`
only when `isQuorum` is already true. With two voters that call does not succeed.
`quorumFor` uses the same policy for commit. A leader that cannot see its peer
cannot commit, and the peer cannot become leader either.

That is the unavailable side of the dilemma. The split-brain side is what you get if
a later change lets one node win alone (a "primary always campaigns and ignores the
missing vote"). During a partition each side would then be leader, append, and, in
active/passive, claim the VIP. `VipFence` claims only while this node leads and the
lease is still outside the fence window (`VipFence.poll`). Two leaders means two
claims. Nothing in `QuorumPolicy` or `RaftGroup` implements a one-node win, and this
decision does not add one.

`GroupOptions.preferred` does not break the tie. The constructor sets the first
election deadline to `electionTimeout / 5` when `preferred` is true
(`RaftGroup` constructor). The node campaigns sooner. It still needs `isQuorum`.
`docs/ha/design.md` §4.1 says the preferred node "wins ties by campaigning sooner".
That sentence overstates the code: sooner is not a second vote.

Fail-static serving does not fix the dilemma. A node that loses quorum keeps serving
the last snapshot (`docs/ha/design.md` §2 principle 5, §4). That is safe for static
files because a request does not append. It is not safe for `ha-control` publish or
for a message-log `append`, both of which need a commit.

## 2. Option A — two data nodes plus a witness

A witness is a third voter. It votes and acknowledges. It does not store user
entries. `Member` carries `witness` and says the storage rule is enforced by the
replication group, not by the failure detector (`Member.java`). `RaftGroup`'s class
comment says the same. On `AppendEntries` a witness acks `prevIndex + entries.size()`
and returns before `appendStored` (`RaftGroup.onAppend`). `countsAsStored` is false
for the witness, so its own disk is not an ack (`RaftGroup.countsAsStored`). It does
not start elections (`RaftGroup.tick`) and it ignores `TimeoutNow`
(`RaftGroup.onTimeoutNow`).

`QuorumPolicy.witness` is stricter than "any two of three". It is false unless the
witness is in the ack set and at least one data node is too. The test
`witnessNeedsTheWitnessAndOneDataNode` records the matrix: witness alone is false,
one data node alone is false, both data nodes without the witness is false, one data
node plus the witness is true.

### RPO and RTO

Ack mode is orthogonal to who votes.

- Default: `Durability.RELAXED`. The `GroupOptions` constructor that omits durability
  stores `RELAXED` (`GroupOptions.java`). `Durability` says the future waits for a
  quorum ack from memory, and fsync is later. `RaftGroup.append` sets `relaxedDirty`
  and does not call `force` on that path. The next `tick` calls `force` if the flag
  is set. `FileLogStorage.force` is `FileChannel.force(true)`.
- Opt-in: `Durability.STRICT`. `append` calls `force` before replicate, and a
  follower calls `force` before its ack (`RaftGroup.append`, `RaftGroup.onAppend`).
  `docs/ha/design.md` §13a: RPO 0 applies only to a completed `STRICT` future. The
  unacked tail is not covered. `RELAXED` can lose a committed entry if every node
  that held it in memory loses power before that later `force`.

RTO for a new leader is one election timeout after the lease ends
(`docs/ha/design.md` §13a). The lease length in code is `heartbeat * 2`
(`GroupOptions.leaseNanos`). There is no Java constant for the Raft heartbeat or the
election timeout. `GroupOptions` requires the caller to pass both. The design doc's
500 ms / 1 s / 1.5 s figures are the intended deployment sizes, not fields in
`GroupOptions`. Membership does have constants: `ReplicatedNode` builds
`Membership` with interval 500 ms and dead-after 5 s. `Membership.SUSPECT_AFTER_MISSES`
is 3, so a peer is suspect after about 1.5 s of silence and dead at 5 s.
`HaSettings` defaults `drainDelay` to 5 s and `drainTimeout` to 15 s. Planned
restart waits out the drain before the lease should move.

Under the current witness rule, RPO is weaker than a normal three-voter Raft group.
Commit can be the leader (one data node, and the only node that stored the entry)
plus the witness ack. The other data node may still be behind. `STRICT` fsyncs the
leader and any follower that actually stores; the witness returns before
`appendStored`, so it never fsyncs the batch. A completed commit can sit on one
data disk.

### Failure matrix (current `QuorumPolicy.witness`)

| Failure | Leadership and commit | What keeps being served |
|---|---|---|
| Lose one data node | The other data node plus the witness is a quorum (`QuorumPolicyTest`). | The survivor serves. Static files also stay up on a node that still has a snapshot. |
| Lose the witness | Both data nodes together are not a quorum. No election completes, no commit. | Both keep the last snapshot (fail static). New publishes and message appends stop. |
| Partition between the data nodes, witness reachable from both | The witness grants one `votedFor` per term (`RaftGroup.onVote`). Only one candidate gets that vote, so only one side reaches `isQuorum`. | The side without the witness serves its last snapshot and does not commit. |
| Partition that isolates the witness | Same as "lose the witness": the two data nodes cannot commit or elect. | Both serve the last snapshot. No split brain, because neither has a quorum. |

### Ack mode

Same as every other group. Default is majority in memory (`RELAXED`), except the
"majority" here is the witness rule above, not `majorityCount`. Quorum plus fsync
is `STRICT` and is opt-in. `ha-control` is specified to open with the default
(`docs/ha/design.md` §13a). `CpStore.open` is a concrete call site of that default:
`new GroupOptions(Durability.RELAXED, 1024 * 1024, 100 ms, 40 ms, 10_000)`
(`CpStore.java`). That 40 ms heartbeat makes the lease 80 ms. It is the control-plane
store, not a package-wide HA default.

### Operational cost

- A third process, on a host that is not one of the two data hosts. `SiteId` is
  already on `Member`. Placing the witness on a data host means one host loss
  removes a data node and the tie-break together.
- It is on the critical path. Every election and every commit needs its ack. A
  witness that is down is a commit outage even though both data nodes are up.
- The process is not free of disk code. `ReplicatedNode` still takes a
  `LogStorage`. The witness path simply never appends user entries. It does not
  remember the index it acked (see §4).
- Heartbeats stay the 64-byte UDP datagram (`HeartbeatCodec` comment,
  `docs/ha/design.md` §2). The witness is a normal member of the group, not a
  second protocol (`docs/ha/design.md` decision 1).

## 3. Option B — async primary/standby

Not implemented. `docs/ha/design.md` decision 1 says manual/STONITH failover and
async primary/standby "are not the default and are not implemented here".
`RaftGroup.append` writes the batch, then calls `replicate()` before it returns the
future. There is no linger, no max-lag, and no mode that completes the future on
the primary's local write alone.

### Data-loss window, if we stopped waiting for the peer

Unreplicated entries are those the primary has appended whose `matchIndex` on the
standby is still behind. They are lost if the primary dies before the standby
stores them.

Bounds that exist in code today, and bounds that do not:

- **Ship interval.** There is no batch timer on the replicate path. `replicate`
  runs inside `append`. On a live link the unsent amount is at most one
  `AppendEntries` batch, and that batch is capped at 32 entries
  (`RaftGroup.replicate`: `Math.min(32, lastIndex() - prev)`).
- **Replication lag while the standby is down.** Unbounded. The primary keeps
  the suffix in its log. Nothing reads `matchIndex` and refuses further appends.
  A time bound would be new code.
- **Fsync policy.** `RELAXED` defers `force` to the next `RaftGroup.tick`.
  `ReplicatedNode.begin(interval)` is what sleeps and then ticks; `open` leaves
  the interval null and starts no thread (`ReplicatedNode` constructor and
  `begin`). The fsync delay is that caller-supplied tick, not a built-in timer.
  `STRICT` fsyncs before the ack, which is the opposite of async: the future
  waits for the quorum disk. The architecture bench measured about 0.5 ms per
  fsync and group-commit p50 about 1.1 ms at 64 producers
  (`docs/design/architecture.md` T3). Those are bench results, not fields in
  `GroupOptions`.
- **Worked example using only code defaults.** Membership interval 500 ms,
  suspect after 3 misses, dead after 5 s (`ReplicatedNode` constructor,
  `Membership.SUSPECT_AFTER_MISSES`). Suppose the standby stops answering and
  the primary keeps taking `RELAXED` appends. For the whole 5 s until the
  detector marks it dead, every entry the primary appended is unreplicated.
  At the `CpStore` heartbeat of 40 ms that is the entire primary log growth
  over those 5 s, not "40 ms of data". After the detector fires, today's code
  still does not promote the standby: two voters still need two acks. Option B
  would add that promotion, and the lost tail would be everything appended
  since the standby's last `matchIndex`, which is the full 5 s plus whatever
  was still in flight. `STRICT` does not shrink that window unless the future
  waits for the standby's fsync, which is option A (or full quorum), not async.

### Fencing, failover, failback

Fencing that exists:

- `VipHook` is claim/release of an address. The comment says the platform
  (keepalived or a cloud API) implements it, and a content request never calls
  it (`VipHook.java`).
- `VipFence.poll` releases when this node is not leader, the lease expiry is 0,
  or `now` is inside `releaseBefore` of `leaseExpiresAt`. `onLeader` releases
  when the leader id is not self. `FailoverPolicy.ACTIVE_PASSIVE` is the only
  mode that calls the hook. `acceptPause` for passive returns a supplier of 0;
  the accept loop does not close (`FailoverPolicy.java`).
- `GroupOptions.failbackHysteresis` pushes `campaignNotBefore` out when a
  leader steps down (`RaftGroup.stepDown`). `withPreferred` is how a node asks
  to campaign sooner and to wait that hysteresis. Default hysteresis is
  `Duration.ZERO`.

Fencing that option B still needs: a standby must be allowed to become leader
without the primary's vote. That is the one-node win §1 rejects. `VipFence`
will not claim until this node is leader, and `becomeLeader` will not run
without a quorum. Lease release on the old primary still matters so the VIP
is dropped before the standby claims it. The release margin is `releaseBefore`,
which the caller passes; `VipFence` only rejects a negative duration.

Failover time if we added promotion at "dead": the membership dead timeout is
5 s, then an election. Failback: hysteresis plus the fact that a healthy leader
is not preempted (`docs/ha/design.md` §6). A returned primary stays a follower
until the standby actually loses leadership, then waits `failbackHysteresis`
before it may campaign.

## 4. What is already implemented, and what each option must change

| Already in tree | Where | What it actually does |
|---|---|---|
| Majority quorum | `QuorumPolicy.majority`, `majorityCount` | n/2+1. Two voters need both. |
| Witness vote, no user log | `QuorumPolicy.witness`, `Member.witness`, `RaftGroup.onAppend` / `tick` / `onTimeoutNow` | Ack without `appendStored`. Elections and commits need the witness and one data node. |
| Per-group Raft | `ClusterNode.openGroup`, `RaftGroup` | One group, one commit index. `ha-control` is one group id. Message partitions would be other groups on the same transport (`docs/ha/design.md` §13a). |
| Leader lease | `GroupOptions.leaseNanos`, `RaftGroup.becomeLeader`, `ReplicationGroup.leaseExpiresAt` | Lease is two heartbeats, refreshed when `quorumFor(lastIndex)` is true (`onAppendResp`). `readBarrier` completes immediately while `nanoTime() < leaseExpires`. |
| Durability | `Durability`, `GroupOptions`, `FileLogStorage.force` | `RELAXED` default, `STRICT` opt-in. Fsync is `FileChannel.force(true)`. |
| VIP fence | `FailoverPolicy`, `VipHook`, `VipFence` | Passive mode claims on leadership and releases before lease expiry. |
| Fail-static HTTP | `HaHandler`, `HealthBoard`, `docs/ha/design.md` §4 | Last snapshot stays served. `HealthBoard.readyNow` is false when `requireQuorum` is set and `quorumOk` is false. |
| Membership timing | `ReplicatedNode` constructor, `Membership`, `LocalCluster.start` | 500 ms interval and 5 s dead in `ReplicatedNode`. `LocalCluster.start` defaults dead-after to 5 s and takes the interval from the caller. Suspect after 3 misses. |

### Option A must change

1. **Persist the last acked `(index, term)` on the witness and use it in `onVote`.**
   Today the witness returns before `appendStored`, so `lastIndex()` stays at
   `storage.lastIndex` (0 unless something else wrote). `onVote` grants when the
   candidate's log is at least that short log. A witness that has acked a high
   index will still vote for a data node that never stored it. That elects a
   leader missing a committed tail. The witness does not need user bytes. It
   does need the index and term it has already acked.
2. **Decide the quorum function.** Keep `QuorumPolicy.witness` (witness is
   mandatory) or switch a three-voter group to `QuorumPolicy.majority` (any two).
   The recommendation below is majority, after change 1. Keeping the current
   function is a one-line policy choice but it makes witness loss a full commit
   outage.
3. **Wire it.** No production path opens `ha-control` with `QuorumPolicy.witness`
   and a `Member` whose `witness` flag is true. `GenerationSync` calls
   `openGroup` with the caller's options. `CpStore.open` uses
   `QuorumPolicy.majority()` and a single non-witness member. A witness process
   is a `ReplicatedNode` (or the real transport equivalent) whose `Member` has
   `witness == true`, placed on its own host.
4. **Do not count a witness ack as a disk copy in `STRICT`.** After change 1,
   `STRICT` still has to see `force` on a data node that will survive. Commit
   must not complete on leader-disk plus witness-vote alone if the other data
   node has not stored the entry. Otherwise RPO 0 is false for two data nodes.

### Option B must change

1. A new ack path that completes `append` without `quorumFor`. That is new
   `Durability` or a new policy. It is not `RELAXED` (that still waits for a
   quorum of memory acks) and not `STRICT`.
2. A lag ceiling: refuse or fence further primary appends when
   `lastIndex - matchIndex(standby)` or the age of that gap exceeds a bound.
   No such field exists on `GroupOptions`.
3. A promotion rule that elects the standby without the primary's vote, plus
   a guarantee the old primary has released the VIP (`VipFence.poll`) before
   the standby claims. `preferred` and `failbackHysteresis` only change
   campaign timing. They do not elect one node.
4. An explicit lost-tail signal to producers. `NotLeaderException` is the
   current failure for "you are not leader". It does not mean "your acked
   entries were on the dead primary only".

## 5. Recommendation

Use option A. Do not build option B. Do not add a one-node election.

Bare two-node quorum cannot fail over without either going unavailable or
splitting brain (§1). Option B makes the lost tail unbounded whenever the
standby is silent, and the code has no max-lag fence (§3). The witness types
are already in tree; the gap is safety of the vote and how the group is
opened (§4), which is smaller than a second replication protocol.

Concretely: run two data nodes and one witness on a separate host. After the
witness remembers the last `(index, term)` it acked, use `QuorumPolicy.majority`
over those three voters so any one failure still leaves a quorum. Open
`ha-control`, and any message group that must not lose a committed entry on a
single disk, with `Durability.STRICT` only when RPO 0 is required; otherwise
leave the `GroupOptions` default (`RELAXED`). Keep static HTTP fail-static
either way. Active/active stays the serving default; `VipFence` remains the
active/passive fence and is not a substitute for the third vote.

### Open questions for Blivion

1. Witness placement: a third host in the same site, or a third site? Same
   site survives a WAN cut and dies with the site. A third site makes the
   witness the inter-site vote; under today's `QuorumPolicy.witness` a cut
   to that site stops commits even when both data nodes are together.
2. Confirm majority-of-three (recommended) versus keeping "no witness, no
   promotion" (`QuorumPolicy.witness`), which treats the witness as a veto.
3. One witness vote for `ha-control` only, or the same three voters on every
   message partition? A witness that acks every partition without storing
   payloads is cheap only if change 1 in §4 stays a few integers per group,
   not a copy of the log.
4. Should the Raft heartbeat and election timeout become code defaults
   (design doc: 500 ms, lease 1 s, election 1.5 s), or stay caller-supplied
   as `GroupOptions` requires today?
5. Is fail-static static-file serving enough that any topic may still choose
   option B later? This decision says no for anything that calls `append`.
