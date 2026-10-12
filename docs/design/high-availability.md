# High Availability Design — sjws_nettask

Status: Draft · Scope: HA only (2-node, 3-node; N-node/multi-site roadmap)
Base: master `b8055fe` (Java 25)

## 1. Context

sjws_nettask is a Java 25 static file server. `Sjws_nettask.serve(ServerConfig)` checks the
document root and calls `SjwsServer.start`, which binds a `ServerSocketChannel` (`SO_REUSEADDR`,
configurable backlog) and runs `ClientManager` on the `accept-loop` platform thread. The accept
loop is bounded by a `Semaphore` of `maxConnections` (further clients sit in the kernel backlog)
and a per-address `PerIpLimit`. Each accepted connection is a virtual thread running
`Http1Connection`: `Http1Parser` → `Http1Requests.toCore` → `core.Handler` → `Http1Writer`.
Production installs `StaticFileHandler`, which owns the small-file `FileCache` (`cacheBytes`).
Optional HTTP/3 is a `Transport` beside TCP and calls the same `Handler`. Deadlines are one
`ConnectionReaper`, not per-socket timeouts.

Content is read-only from the request path, so HA is about *availability of the serving tier*
and *consistency of content/config/certs*, not replicated request state. A request never waits
on another node.

Out of scope (owned by other workstreams, consumed here as interfaces this workstream defines):
config file format and ACME (`ConfigSource`, `CertProvider`), reverse proxy, the observability
backend (`MetricsSink`), daemon/service mode (signals, PID), and the CI workflow. `HaSettings`
is attached in code (`SjwsServer.start(config, ha)`). This workstream does not add flags to
`ServerConfig` — that parser, `--help`, and the README option tables stay with the config
workstream. The process shutdown hook in `Sjws_nettask.serve` still calls `close()`; signal
wiring that should drain instead belongs to daemon mode, which can call `Lifecycle.drain()`.

## 2. Design principles

1. **Near-zero hot-path overhead.** No per-request coordination, locks, RPCs or quorum reads.
   A request touches only node-local state. With HA off, `ClientManager` and `Http1Connection`
   keep today's path (the new hooks are null). With HA on, cluster role is one `volatile` read
   at accept time, and only when a passive gate is installed — active/active installs none.
   The request path adds one `volatile` snapshot read inside `HaHandler` (the `Handler` seam),
   not in the parser or the cache. Target: ≤1% throughput loss, ≤2% p99 delta vs single-node.
2. **Cheap heartbeats.** Fixed-size UDP datagrams (64 B, default 500 ms, jittered), one
   dedicated virtual thread, no allocation per beat beyond a reused buffer and a reused
   `DatagramPacket`. Monotonic clocks are not comparable across hosts: the packet carries
   `leaseRemainingMs`, and the receiver sets its own expiry from `System.nanoTime()`.
3. **Sync off the hot path.** Content/config/cert sync runs in background threads and is
   applied by atomic swap (new snapshot → single reference publish); readers never block.
   `HaHandler` loads the `Snapshot` once at the start of `handle`, so an in-flight call keeps
   that `Handler` (and its `FileCache`) even if a newer generation is published underneath.
4. **N-node-ready abstractions.** 2 and 3 nodes are configurations of one model
   (membership + quorum policy + failover policy), not special cases.
5. **Fail static.** On partition or lost quorum, a node keeps serving its last good snapshot
   unless policy explicitly requires fencing (VIP owner in active/passive).

## 3. Core abstractions (generalise to N nodes / sites)

All of these live in `sjws_nettask.cluster`. That package does not import HTTP, Netty, or
`sjws_nettask` server types, so Messaging Core can depend on it without the file server.

- `NodeId`, `SiteId`, `Member{id, site, addr, incarnation, state}`; states
  ALIVE → SUSPECT → DEAD (SWIM-style, incarnation numbers refute stale suspicion).
- `Membership`: static seed list now; pluggable gossip dissemination for N>3.
- `QuorumPolicy`: `majority(n)`, `witness(n)` (2 data nodes + a tie-breaker that votes but
  does not store the log), and later `perSite(local majority) + global(majority of sites)`.
  Pure function over a voter set and an ack set — not over a live RPC.
- `LeaderElection`: lease-based (term, lease expiry, monotonic clock) built on the Raft
  term/leader below. The leader only coordinates sync rollouts and VIP ownership — never
  requests.
- `FailoverPolicy`: `ACTIVE_PASSIVE` (VIP hook), `ACTIVE_ACTIVE` (external LB/DNS using
  `/healthz` and `/readyz`), later `GeoDns` / `Anycast` / `GSLB`.
- `SnapshotHolder` (in `sjws_nettask.ha`): versioned (monotonic `generation` + content hash)
  bundles. The value is the `Handler` for that generation, so the cache is generation-scoped
  without a change to `FileCache`. Nodes converge by pulling from the leader or any peer with
  a higher generation.

`Transport.stopAccepting()` already exists for HTTP/3. Drain uses it at the same moment it
closes the TCP `ServerSocketChannel`, which is the rule the HTTP/3 transport already documents.

## 4. Modes

### 4.1 Two nodes

- **Active/active (recommended):** both serve; upstream LB/DNS uses `/healthz` / `/readyz`.
  No split-brain risk for serving because content is read-only; the only contested action is
  publishing a new generation, which requires a lease from the leader and a witness ack.
- **Active/passive:** VIP owned by the lease holder. Split-brain avoidance needs a **witness**
  (lightweight 3rd process, or an external arbiter plugged in behind the same `QuorumPolicy`).
  Without a witness, the configured `preferred` node wins ties by campaigning sooner, and the
  other demotes on peer loss only if it can still reach the witness ("no witness → no
  promotion": `WitnessQuorum` is false unless the witness is in the ack set and at least one
  data node is too). A data node alone does not become leader and does not claim the VIP.
- Fencing: the losing node releases the VIP before lease expiry (lease shorter than the
  failure-detection margin). Fencing is the VIP hook, not a closed accept loop: `/healthz`
  is on the same listener as content, so a passive node still accepts on its own address.
  The accept loop's one volatile role read (passive mode only) feeds that hook and the
  `ha_role` gauge; it does not run per request.

### 4.2 Three nodes

- Majority quorum (2/3) for the leader lease and for generation publish. A minority partition
  keeps serving its last snapshot but cannot publish; readiness may optionally drop
  (`HaSettings` minority mode `serve` or `drain`, the knob the config workstream can later
  surface as `--ha-minority`).

## 5. Health checks

- `/healthz` (liveness: process up and the accept loop still inside `ClientManager.run`),
  `/readyz` (a snapshot is published, not draining, quorum OK if policy demands it).
  `HealthBoard` precomputes the `Response` objects (plain text, 200 or 503) and `HaHandler`
  returns one of them. No allocation and no file I/O on the probe path.
- The accept loop clears liveness in its `finally`, off the request path.
- Peer failure detection: UDP heartbeat plus an indirect probe (k=2 via other members when
  N≥3). Defaults: interval 500 ms, suspect after 3 misses, dead after 5 s.

## 6. Failover

- Detection → SUSPECT → DEAD → quorum recompute → new lease. Expected failover:
  active/active ≈ LB check interval; active/passive ≈ 2–6 s (3 missed beats, then lease
  expiry, then election).
- Failback is disabled by default (avoid flapping). Raft does not preempt a healthy leader,
  so a preferred node that returns stays a follower until the current leader is actually gone.
  An extra hysteresis delay applies before a demoted node may campaign again.

## 7. Graceful shutdown / drain

- `Lifecycle.drain()`: mark not-ready → wait LB deregistration (`drainDelay`, default 5 s) →
  set `Connection: close` on later keep-alive responses → expire *idle* keep-alives through
  the existing reaper (`shutdownOutput` / `shutdownInput`, the same non-blocking path as a
  deadline) → stop accepting (close the `ServerSocketChannel`, `Transport.stopAccepting()`
  for HTTP/3) → wait until in-flight `Http1Connection`s finish, bounded by `drainTimeout` →
  return. `ClientManager` only takes this path when `prepareGracefulDrain` was called;
  `close()` stays the current interrupt-immediately behaviour.
- Signal wiring belongs to the daemon-mode workstream. HA exposes `Lifecycle.drain()`.

## 8. Zero-downtime reload

- Config/cert/content: a new snapshot is built off-path, validated, and published with
  `SnapshotHolder.publish` (`AtomicReference` compare-and-set; a lower generation is
  rejected). In-flight requests keep the handler reference they already loaded. The new
  `StaticFileHandler` has a new `FileCache`, so the cache is keyed by generation without
  touching cache lookup.
- Binary upgrade: `SO_REUSEPORT` hand-over where the process can set it (`setsockopt` on the
  channel fd; the JDK has no `StandardSocketOptions` constant for it, so this needs the
  `java.base` packages opened and native access — the same native-access need HTTP/3 already
  has). Otherwise rolling restart, one node at a time, gated on `/readyz` of the others.

## 9. Config / cert / content sync

- Source of truth via `ConfigSource` / `CertProvider` (interfaces here; implementations,
  including ACME, belong to the other workstream). The leader pulls, computes a generation
  and a content hash, and announces `(generation, hash)` in heartbeats (8 B + 8 B). Peers
  that lag pull the bundle over authenticated TCP (HMAC-SHA256 with the shared secret) on a
  background thread. mTLS can replace the HMAC later behind the same `BundleTransport`.
- Private keys are never copied into heartbeats. The bundle is transferred with the HMAC,
  and the key file is written mode `0600` via a temp file and `ATOMIC_MOVE`.
- Two-phase rollout on the `ha-control` replicated log: STAGE on the quorum → pull to
  reachable nodes → ACTIVATE with quorum. Apply of ACTIVATE is what publishes the snapshot.
  A witness ack counts toward that quorum without storing the log. If the leader dies before
  a peer has the bytes, the peer keeps the last activated snapshot (fail static) and the new
  leader publishes again from `ConfigSource`. The log coordinates activation; it is not the
  only copy of the bytes.

## 10. Shared state

None required on the request path. Cluster state is the membership view, the leader lease,
and the current generation, all in memory, rebuilt on start from `LogStorage` plus the last
snapshot directory. Optional persistence of the last generation is that directory
(`HaSettings.stateDir`), so restart can mark itself ready before the first sync.

## 11. Observability hooks

`HaEvents` (role change, member state, generation applied, drain start/stop) and
`MetricsSink` (the observability workstream implements export; `MetricsSink.NONE` is the
default):

- `ha_role`
- `ha_members{state}`
- `ha_heartbeat_rtt_ms`
- `ha_generation`
- `ha_failovers_total`

Calls happen on the heartbeat, Raft, and drain threads, never inside `HaHandler.handle`
for a content request. Probes only read the precomputed board.

## 12. Test plan

- Unit: `QuorumPolicy` tables (n=1..7, witness), membership state machine (including
  incarnation refute and indirect probe), lease timing with a fake clock, snapshot
  compare-and-set, heartbeat codec (exactly 64 bytes, round-trip, checksum failure).
- Raft: election, `NotLeaderException` hint, ordered apply, group commit, read barrier /
  lease read, joint-consensus membership change, leadership transfer, witness ack without
  a stored log, minority partition cannot commit.
- Integration (in-JVM, loopback): `/healthz` and `/readyz` through a real
  `ServerSocketChannel`, 2-node failover, witness tie-break, 3-node partition (drop
  injection on the sim transport), drain with an in-flight request and `Connection: close`,
  reload that does not change a request already inside `handle`, cert file mode `0600`.
- Chaos, scripted rather than as a required CI step: `kill -9`, pause, asymmetric
  partition. The in-JVM drop injection covers the partition case the unit suite can assert.
- **Performance gate (every implementation PR):** `wrk` / `wrk2` on the same host and the
  same settings, single-node (HA not attached) vs cluster mode (HA attached, active/active,
  heartbeats running once they exist). Shape: `wrk -t4 -c128 -d30s --latency` and
  `wrk2 -t4 -c128 -d30s -R<fixed> -L`. Report req/s and p50/p90/p99/p99.9 and the overhead
  % in the PR body. The harness is `tools/ha-bench.sh` plus `sjws_nettask.ha.BenchServer`.

`mvn verify` stays the CI command (workflow file not edited). New packages get JaCoCo
`PACKAGE` rules because `DocsConsistencyTest` requires one per directory under `src/`.

## 13a. Cluster API for Messaging Core

The cluster layer is a standalone module (`sjws_nettask.cluster`, no HTTP dependency).
HTTP failover (§4–§9) is one consumer; Messaging Core log replication is another.
Leases in §3 are built on top of the Raft term/leader below, so there is one consensus core.

```java
interface ClusterNode extends AutoCloseable {
  NodeId self();
  MembershipView membership();            // members, site, state, incarnation
  void onMembershipChange(Consumer<MembershipView> l);
  ReplicatedLog openLog(String name, StateMachine sm, LogOptions o); // many logs (sharding)
}
interface ReplicatedLog {
  long term(); Optional<NodeId> leader(); boolean isLeader();
  CompletableFuture<LogPosition> append(ByteBuffer entry);         // leader only, else NotLeaderException(hint)
  CompletableFuture<List<LogPosition>> appendBatch(List<ByteBuffer> entries); // group commit
  long commitIndex(); long lastApplied();
  CompletableFuture<Void> readBarrier();   // linearizable read (ReadIndex), optional lease reads
  void onLeaderChange(BiConsumer<Long /*term*/, Optional<NodeId>> l);
  CompletableFuture<Void> changeMembership(Set<NodeId> voters, Set<NodeId> learners); // joint consensus
  void transferLeadership(NodeId target);
}
interface StateMachine {
  void apply(long index, long term, ByteBuffer entry);  // called in order, single thread
  LogSnapshot takeSnapshot(long lastIncludedIndex);     // streamed, off hot path
  void restore(LogSnapshot s);
}
record LogPosition(long term, long index) {}
record LogOptions(Durability fsync /*ALWAYS|BATCHED|NONE*/, int maxBatchBytes,
                  Duration electionTimeout, Duration heartbeat, long snapshotEveryEntries) {}
```

Pluggable SPIs: `ClusterRpc` (in-JVM for tests, TCP length-prefixed frames for real peers;
batching and pipelining on the TCP implementation), `LogStorage` (memory, or segmented
append-only files with `FileChannel.force` for `ALWAYS` / `BATCHED` / `NONE`), `Clock`,
`QuorumPolicy` (majority; witness voter-without-log for 2 data nodes; per-site hierarchical
later). Learners (non-voting) support read replicas and cross-site async followers.

Low-latency notes: batching + pipelining of AppendEntries, `ByteBuffer` payloads copied
once into the log, single-threaded apply, configurable fsync. The HTTP consumer uses one
`ha-control` log (generation publishes, VIP ownership) and never touches the log per
request. `openLog` may be called more than once on the same node (sharding); each name is
an independent log that shares membership and the RPC transport.

## 13. N-node and multi-site roadmap

1. **N nodes (single site):** gossip dissemination (SWIM) replaces all-to-all heartbeats
   (O(N) per node → O(1)); quorum stays majority; sync via peer-to-peer pull (fan-out tree).
   `Membership` is the seam: today it applies a static seed list, later a gossip pluggable.
2. **Multi-site:** `SiteId` is already on `Member`. Failure-detector timeouts become per link
   class (intra-site milliseconds, inter-site tens of milliseconds). Hierarchical quorum:
   per-site majority for local decisions, majority-of-sites (odd site count or a witness
   site) for a global generation publish. An inter-site partition leaves each site serving
   its last snapshot (fail static).
3. **Traffic steering:** GeoDNS/GSLB with `/readyz`, or anycast with BGP withdraw on drain
   (another `FailoverPolicy`). Site-local VIP inside each site, the same `VipHook`.
4. **Sync across sites:** one leader per site pulls from the global leader; content-addressed
   bundles (the hash is already in the heartbeat) dedupe WAN transfer.

## 14. Implementation plan (follow-up PRs)

Each PR is stacked on the previous one, keeps `mvn verify` green, and does not merge.
Benchmarks use `tools/ha-bench.sh` (single-node vs cluster mode) and the numbers go in
the PR body.

1. **Lifecycle.** `sjws_nettask.ha`: `Snapshot` / `SnapshotHolder`, `HealthBoard`,
   `HaHandler`, `Lifecycle.drain()`, `HaEvents`, `MetricsSink`, `BenchServer`. Nullable
   hooks on `SjwsServer`, `ClientManager`, and `Http1Connection` so HA-off is the current
   path. Idle expire goes through `ConnectionReaper`. Tests: atomic publish, probe
   responses, drain with an in-flight handler, `Connection: close`, accept continues
   during `drainDelay`.
2. **Membership.** `sjws_nettask.cluster`: `NodeId`, `SiteId`, `Member`, `Membership`,
   `QuorumPolicy` (majority and witness), 64-byte heartbeat codec, UDP heartbeat thread,
   indirect probe, `ClusterNode` membership view. Fake-clock tests. No HTTP imports.
   Cluster-mode benchmark runs a second node and the heartbeat thread.
3. **Lease and sync.** Raft `ReplicatedLog` (`openLog`, group commit, read barrier, joint
   membership, leadership transfer, `LogStorage`) and the leader lease. `ConfigSource` /
   `CertProvider`, HMAC bundle pull, two-phase generation publish, atomic cert rename.
   HTTP uses the `ha-control` log only from the sync thread.
4. **Failover tooling.** `VipHook` driven by the lease (claim on grant, release before
   expiry), preferred-node campaign delay, failback left off, `RollingRestart` gated on
   `/readyz`, and `SO_REUSEPORT` hand-over when the JVM opens the nio packages.
