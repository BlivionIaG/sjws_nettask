# Control Plane / API Gateway Mode — sjws_nettask

Status: Draft · Scope: control plane and config distribution
Base: master `d592aaf` (Java 25)
Consistent with: [high availability](../ha/design.md) (the document PR #14 added as `docs/design/high-availability.md`; PR #34 moved it) and the per-group Raft API shipped in [PR #29](https://github.com/BlivionIaG/sjws_nettask/pull/29).

PR #29 is merged into the HA stack (`cursor/ha-vip-restart-5d15` and its parents). It is not on master `d592aaf`. This design calls that API. Implementation PRs merge the stack forward. They do not reimplement Raft.

## 1. Context

sjws_nettask is one binary. Today it serves static files, optional HTTP/2 and HTTP/3, a filter chain, access rules, a YAML config snapshot with ACME, and a messaging ledger. The request path holds one `AtomicReference` of an immutable `ConfigSnapshot` (`LiveConfig`). `ReloadHook.run()` rebuilds that snapshot off the request path. HA ([docs/ha/design.md](../ha/design.md)) already decided that a request never waits on another node, that `ha-control` is one Raft group on the shared cluster transport, and that the default ack is majority in memory (`Durability.RELAXED`) with quorum-plus-fsync opt-in.

This mode adds a control plane for the desired configuration: routes, filters, access rules, certificates, and messaging topics. Control nodes (3 or 5) replicate that desired state. Data-plane nodes apply it locally and keep serving the last good snapshot when the control plane is unreachable.

The content request path reads the snapshot that is already installed. It does not call the control plane, the Raft group, or the watch stream.

## 2. Principles

1. **One binary, three startup roles.** `standalone` (the default), `control`, or `data`. The choice is made once at process start. `standalone` constructs no control-plane object, opens no `cp-config` group, and starts no watch. The HTTP path stays the path on master `d592aaf`.
2. **Zero cost when the control plane is off.** With `standalone`, the accept loop and `Http1Connection` do not read a role, a lease, a generation from Raft, or a control-plane flag. Evidence for every PR, including this one, is a `wrk` / `wrk2` before/after on the same host. The gate is the same shape as HA: report req/s and p50/p90/p99/p99.9.
3. **Lock-free hot path.** A request performs one volatile read of an immutable snapshot (`LiveConfig.get` / `handler()`), then runs the prebuilt `Handler`. Publish is an atomic swap (`AtomicReference.set`). Readers take no lock. An in-flight request keeps the handler it already loaded.
4. **Raft I/O on dedicated threads.** Append, fsync, election, and `GroupListener.onCommitted` run on the cluster group's threads (PR #29: one apply thread per group, index order). Those threads are distinct from the accept loop and from request virtual threads.
5. **The control plane bootstraps without the broker.** `sjws_nettask.cp` does not import `sjws_nettask.messaging`. Messaging does not import `sjws_nettask.cp`. Topic *desired state* lives in the config document. The broker opens partitions from the applied document on its own threads.
6. **Fail static.** A data-plane node that cannot reach a leader keeps the last successfully applied snapshot, including across a restart from the persisted last-good file. A minority of control nodes cannot publish.
7. **Config & ACME own the model.** The control plane stores, versions, signs, and ships the document that workstream already validates. It does not invent a second schema.

## 3. Architecture

```
                    admin TUI
                        │  mTLS admin API
                        ▼
              ┌─────────────────────┐
              │  control role (3|5) │
              │  sjws_nettask.cp    │
              │  group: cp-config   │── Raft (cluster.ClusterNode)
              └──────────┬──────────┘
                         │  mTLS watch: push, ack, resume
           ┌─────────────┼──────────────┐
           ▼             ▼              ▼
     data: gateway   data: static   data: broker    data: edge
           │             │              │
           └─────────────┴──────────────┘
                         │  atomic swap, off the request path
                         ▼
              LiveConfig / ConfigSnapshot
                         │
                         ▼
              HTTP, filters, router, broker loop
```

### 3.1 Packages and the dependency cut

| Package | Role |
|---|---|
| `sjws_nettask.cp` | Raft state machine for desired config, signing, rollout, admin API, watch server, registration. Depends on `sjws_nettask.cluster` and on the config model. |
| `sjws_nettask.config` | Owns `ConfigDocument`, validation, `contentHash`, `ConfigSnapshot.compile`, `ConfigReloader`, `LiveConfig`. Gains the serializable snapshot, diff, and apply-from-remote entry points described in §5. |
| `sjws_nettask.cluster` | Per-group Raft from PR #29. Unchanged by this workstream. |
| Data plane (`http1`, `h2`, `h3`, `routing`, `core`, `messaging`, `acme` on a data node) | Depends on the narrow applied-config view in §3.2. Does not depend on `sjws_nettask.cp`. |

`sjws_nettask.cp` does not import `sjws_nettask.messaging`, `sjws_nettask.routing`, `sjws_nettask.http1`, `sjws_nettask.h2`, or `sjws_nettask.h3`. A control process can open `cp-config` and serve the admin API with no broker class initialized.

The composition root is `Sjws_nettask` / `SjwsServer`. It is the only place that may mention both a data-plane server and `sjws_nettask.cp`. The reference is behind the startup role. `standalone` does not execute it.

### 3.2 Narrow snapshot interface

The data plane's only view of configuration is the applied snapshot:

```java
package sjws_nettask.config;

/** What HTTP, the filter chain, the router, and the broker are allowed to see. */
public interface AppliedConfig {
    /** Cluster-wide monotonic desired-config version. 0 for a snapshot that never came from the CP. */
    long version();
    /** Raft term of cp-config that signed this snapshot. 0 when local. */
    long epoch();
    /** Lowercase SHA-256 of the canonical YAML, or null when the process has no file bytes. */
    String contentHash();
    ConfigDocument document();
    /** Prebuilt handler. One volatile read at the call site, then plain invocation. */
    Handler handler();
}
```

`ConfigSnapshot` implements `AppliedConfig`. `LiveConfig` remains the `AtomicReference` holder. HTTP keeps calling `LiveConfig.handler()` and does not grow a second read. The messaging broker, when it is running, reads `document()` on its partition-writer thread to learn the desired topic set. That read is not on the HTTP request path and is not a call to the control plane.

### 3.3 Roles in the one binary

| Startup role | Process |
|---|---|
| `standalone` | Today's server. Control-plane code is not started. |
| `control` | Voter of `cp-config`. Admin API. ACME only while leader-ready (§6). No content request path that consults Raft. |
| `data` | One data role: `gateway`, `static`, `broker`, or `edge`. Registers, watches, applies, serves from `LiveConfig`. |

A data node also carries labels (`String` map) and capabilities (`http1`, `http2`, `http3`, `proxy`, `static`, `messaging`). Labels select rollout stages. Capabilities bound the projection in §12.4.

The config workstream owns the YAML keys (`node.mode`, `node.role`, `node.labels`, `node.capabilities`) and the command-line flags. This design names them. The parser stays in `sjws_nettask.config`. Until the operator selects `control` or `data`, behavior matches `standalone`.

### 3.4 Threading

| Work | Thread |
|---|---|
| Accept, parse, `Handler.handle` | Existing accept loop and request virtual threads. They read `LiveConfig` only. |
| Raft election, append, fsync, apply | `sjws_nettask.cluster` group threads. |
| Snapshot compile, signature check, disk write of last-good | A control-plane applicator thread on the data node, started only for role `data`. |
| Admin API | Its own listener and virtual threads, control role only, distinct port from content. |
| Watch stream | One connection per data node, read on a virtual thread that is not a content request. |
| ACME | Existing ACME thread, and only on the control leader after `leaderReady()`. |

## 4. CP Raft store

Control nodes open one replication group through the PR #29 API:

```java
cluster.openGroup(new GroupId("cp-config"), options, listener);
```

`options` uses the `GroupOptions` constructor that omits durability, so the ack mode is `Durability.RELAXED` (majority in memory, fsync after the ack). A deployment that must not lose a committed config under a simultaneous majority crash passes `Durability.STRICT`. That is the same choice HA records for `ha-control`.

Voters are the control nodes. The supported counts are 3 and 5 (majority 2 and 3). Data-plane nodes are not voters. A 1-node group exists only during bootstrap (§12.3) and is not a production configuration. Two control nodes are not a supported failover mode: a single survivor cannot commit, which matches the HA decision that two data nodes without a majority do not publish.

The group shares the cluster transport. Frames carry `groupId`. Opening `cp-config` does not open a second socket beside the one `ClusterNode` already uses. `ha-control` (membership, VIP, static-generation STAGE/ACTIVATE) stays a different group. Config bytes are log entries of `cp-config` only. Static file bytes stay out of every log, as HA §13a requires. Message records stay in per-partition groups owned by messaging.

The state machine, applied in index order on the group thread:

| Entry | Effect |
|---|---|
| `Noop` | Empty entry appended by a new leader in its term (§6). |
| `Propose` | Validated canonical YAML, stored as staged at the commit index. |
| `Stage` | Binds a staged proposal to an ordered list of label selectors. |
| `Advance` | Marks the next stage eligible for push. |
| `Activate` | The staged proposal becomes the desired version. |
| `Rollback` | A new version whose content hash equals an older activated version. |
| `Register` / `Heartbeat` / `Revoke` | Data-node liveness and denylist. |
| `TrustBundle` | Signing-key rotation and the CA bundle used to verify node certs. |
| `CertReference` | ACME result: chain reference and sealed key material (§5.7, §12.5). |

`SnapshotControl.take` / `install` / `truncateAndCompact` compact the log to the latest activated snapshot plus the live registration table. Compaction runs off the request path, on the group thread, past an index that is both snapshotted and acked. The compactor keeps the latest value per key (current desired snapshot, denylist, registrations). History required for rollback is retained for a configured number of activated versions (default 8) inside the snapshot payload, then dropped.

Membership changes of the voters use `ReplicationGroup.changeMembership` (joint consensus). Learners are non-voting (§11).

## 5. Config version and snapshot model

### 5.1 Reuse the config model

`ConfigDocument` remains the validated configuration: `ServerConfig`, `AcmeSettings`, `Routing`, `List<MiddlewareSpec>`, `AccessSettings`, plus the topics section in §13.5. `ConfigDocument.contentHash()` remains the lowercase SHA-256 of the canonical YAML bytes. `ConfigLoader` remains the strict validator. `ConfigSnapshot.compile` remains the step that builds the handler, the router, and the ACME challenge wrapper. `ConfigReloader` remains the local-file reload path.

The control plane adds a serializable envelope around those bytes. It does not duplicate fields into a parallel model.

### 5.2 Identifiers

Three numbers, kept distinct on purpose:

| Name | Meaning |
|---|---|
| `version` | Cluster-wide monotonic `long`. Assigned when `Activate` or `Rollback` commits. Strictly increasing. This is the number a data node resumes from. |
| `epoch` | Raft term of `cp-config` at the moment the leader signs the envelope. Changes when leadership changes. |
| `generation` | The existing process-local `ConfigSnapshot.generation` (`int`). It increases by one on every successful local reload and every successful remote apply in this process. Standalone keeps today's counter. |

`AppliedConfig.version()` exposes `version`. A standalone snapshot reports `0`.

### 5.3 Envelope

```java
record SignedSnapshot(
    long version,
    long epoch,
    long raftIndex,
    String contentHash,
    byte[] canonicalYaml,
    byte[] diff,            // empty when this envelope carries the full document
    long parentVersion,     // 0 when diff is empty
    String projectionId,    // "full", or the node id / selector the bytes were cut for
    byte[] nonce,           // 16 bytes, unique per signature
    byte[] signature) {}
```

The signature input is the canonical encoding of `version`, `epoch`, `raftIndex`, `contentHash`, `projectionId`, and `nonce` (§12.2). The YAML and the diff are covered by `contentHash` (hash of the exact bytes in this envelope).

### 5.4 Diffs

`ConfigDiff.diff(ConfigDocument before, ConfigDocument after)` returns a deterministic, section-scoped patch (server, acme, routing, middleware, access, topics, node). The same pair of documents produces the same bytes. `ConfigDiff.apply` rebuilds the later document or fails cleanly.

The Raft log stores either a full canonical document or a diff against `parentVersion`. The state machine materializes the full document before `Activate`. A watcher that has `parentVersion` may receive the diff. A watcher that does not, including a node that resumes after compaction, receives the full envelope. There is no third representation.

### 5.5 Validate

Validation order, off the request path:

1. Signature, then anti-replay (§12.2): `version` greater than the last applied version, `epoch` greater than or equal to the highest epoch already applied, `nonce` unused for that version.
2. `ConfigLoader` strict parse of the canonical YAML (or of the document obtained by applying the diff).
3. `ConfigSnapshot.compile` against the local document root and the local challenge board.

Any failure leaves the previous `ConfigSnapshot` installed. The applicator records the error for the admin API. The content path is unchanged.

### 5.6 Apply-from-remote

`ConfigReloader.applyRemote(SignedSnapshot envelope, ConfigSnapshot current, ChallengeBoard board)` verifies, validates, compiles, and returns a `ReloadResult` in the same shape as a file reload (applied snapshot, or rejected with the current snapshot retained). On success the data node:

1. Swaps `LiveConfig` to the new snapshot.
2. Persists the envelope as last-good (§5.8).
3. Acks the version on the watch stream.

`ReloadHook` stays the local entry. The data-node applicator is registered as a hook so a later local `ReloadHook.run()` still runs, and a remote apply is a direct call to `applyRemote` from the applicator thread. A remote apply does not re-enter the hook list recursively.

Bind-time fields keep the `ConfigReloader` rule already on master: port, backlog, connection caps, and an HTTP/3 listener's timeouts are reported as needing a restart and are not silently changed under a live listener.

### 5.7 Certificates

ACME runs on the control leader only, and only after `leaderReady()` (§6). Followers do not create accounts or orders. Data nodes do not talk to the ACME directory.

The leader publishes a `CertReference`: the certificate chain, the content hash, and key material sealed to the data nodes that terminate TLS (§12.5). The plaintext private key is written on the leader with the existing ACME store rules (mode `0600`, temp file, atomic move). It is not placed in `canonicalYaml` and it is not placed in a heartbeat.

Data nodes install the chain through the same `TlsMaterial` / `Http3Server.replaceTls` path a local reload uses, after `applyRemote` succeeds.

### 5.8 Last-good persistence

After every successful apply, the data node writes the signed envelope to `stateDir/last-good.cp` (temp file in the same directory, `ATOMIC_MOVE`, mode `0600`). `standalone` does not create this file.

On startup in role `data`, the process compiles `last-good.cp` if it is present and the signature still verifies under the pinned trust bundle, then binds the content listener. The watch connects in the background. If the control plane is down, the process serves that snapshot. A corrupt or unverifiable file is not applied; the process logs the failure and, if no older snapshot is in memory, stays not-ready until an operator provides a file or the control plane returns.

## 6. Leadership interface

Config and ACME need a narrower view than `ClusterNode`. The control plane exposes this and implements it with the PR #29 group:

```java
package sjws_nettask.cp;

public interface ControlLeadership {
    /** Delegates to ReplicationGroup.isLeader() for cp-config. */
    boolean isLeader();

    /**
     * True after this term's election Noop has been committed and applied.
     * isLeader() can be true while this is still false.
     */
    boolean leaderReady();

    /** Current Raft term. 0 before the group has a term. */
    long term();

    /**
     * term, isLeader. Invoked on the group thread, as ClusterNode.onLeaderChange does.
     * Never invoked on a content request thread.
     */
    void onLeaderChange(BiConsumer<Long, Boolean> listener);

    /** Completes with the term once that term's Noop has been applied. */
    CompletableFuture<Long> whenReady();
}
```

On each transition to leader, the new leader appends a single `Noop` in the new term before it proposes config or starts an ACME order. `leaderReady()` becomes true when `onCommitted` observes that `Noop`. This is the Raft rule that a leader learns the commit index by committing an entry in its own term, expressed on the interface config and ACME already need. `whenReady()` is that completion. A leadership loss completes a waiting `whenReady()` exceptionally and clears `leaderReady()`.

`onLeaderChange` is wired to `ClusterNode.onLeaderChange(new GroupId("cp-config"), ...)`. The callback's term is the Raft term. ACME's renewal thread checks `leaderReady()` before it contacts the directory and stops work when the callback reports `isLeader == false`.

`readBarrier` / lease reads stay inside the cluster package. Config publish uses `append` on the leader. The HTTP request path calls none of these methods.

## 7. Registration and heartbeats

A data node connects to a control node over the watch (§8). The first message is `Register`: node id, data role, capabilities, labels, current applied version, and the fingerprint of its node certificate.

The leader appends `Register` to `cp-config`. Followers learn it through Raft. A follower that receives a watch redirects the node to the leader (the hint is the same idea as `NotLeaderException`: leader id and address). The data node retries against the hint.

Heartbeats are records on that same stream, default interval 1 s, carrying the applied version and a monotonic send time. The leader appends a coalesced `Heartbeat` (at most one committed entry per node per interval; intermediate beats update an in-memory freshness table on the leader and are included in the next entry). A node with no beat for 5 s is `SUSPECT`. A node with no beat for 15 s is `DEAD` for rollout accounting. Neither state changes what the data node is serving. Dead nodes are skipped by the current stage and do not block a stage whose policy allows a configured minimum of acks; a policy of "all targeted nodes" waits.

HA's 64-byte UDP heartbeat remains the failure detector for `ha-control` membership. Data-plane registration uses the mTLS watch so the identity is the node certificate. The two protocols are not merged.

## 8. Push / watch protocol

Transport: one mTLS connection (TLS 1.3) from the data node to the leader of `cp-config`. The listener is the control admin port's sibling, or the same port with a different ALPN (`sjws-cp-watch`). It is not the content port.

Frames are length-prefixed. The client speaks first:

1. `Register` (§7).
2. `Watch { fromVersion }`, where `fromVersion` is the last version this node has applied and persisted, or `0`.

The server then sends, in version order:

- `Snapshot` (full `SignedSnapshot`) when the client has no parent, or
- `Diff` when the client has `parentVersion` and the diff is still retained.

After the client is caught up, the server sends each newly activated version. The client replies `Ack { version }` after `applyRemote` has swapped `LiveConfig` and the last-good file is durable.

Backpressure: the server window is one unacked version by default (`maxInFlight`, configurable, minimum 1). It does not send version N+1 before `Ack` of N. The applicator queue on the client is bounded to that window. A slow apply stalls the stream. It does not buffer an unbounded log on the data node, and it does not stall Raft apply on the leader: the leader's push task reads the committed snapshot from memory and blocks on that one node's window only. Other nodes have their own windows.

Resume: a dropped connection reconnects with `Watch { fromVersion = last acked }`. The server sends only higher versions. Duplicate `Ack`s are idempotent. A version already applied is not compiled again (`version` equal to the installed version is a successful no-op).

The content request path does not read this socket.

## 9. Staged rollout, canary, and rollback

An operator proposes canonical YAML through the admin API. The leader validates with `ConfigLoader`, appends `Propose`, and the proposal sits in `STAGED`.

`Stage` names an ordered list of selectors. Each selector is a conjunction of label matchers (`role=gateway`, `track=canary`) plus an optional percent of the matching registered nodes (stable hash of the node id, so membership jitter does not reshuffle the set while the stage is open). The first stage is the canary. A typical policy is `track=canary`, then `role=gateway`, then the remainder.

The leader pushes the staged version only to nodes in the open stage. Other nodes keep their installed version. A node that does not match any selector is untouched.

`Advance` opens the next stage after the open stage has enough `Ack`s. The default requirement is every targeted node that is `ALIVE`. The operator can set a minimum count. A node that is `DEAD` is reported and, under the default policy, blocks advance; the operator may skip it explicitly. That skip is itself a log entry.

`Activate` commits when the last stage has met its ack rule, or when an admin with role `operate` forces it. Activation publishes the version as desired. Nodes that have not acked still receive it (subject to their window) and still serve last-good until they apply.

`Rollback { toVersion }` appends a new version whose `contentHash` and canonical YAML equal the retained activated snapshot `toVersion`. The new version is higher than any current version, so anti-replay still moves forward. The rollout of a rollback uses the same stage list unless the operator selects a single stage of all nodes. Nodes that never installed the bad version treat the rollback as a no-op if their installed hash already matches, and ack it.

A proposal that fails validation is not appended. A failed `applyRemote` on one node does not roll the others back automatically. The admin API shows the node error. The operator issues `Rollback`.

## 10. Partitions and stale config

A partition that leaves a minority of control nodes unable to reach a majority cannot commit. Those nodes do not sign a new version. Data nodes that can still see a leader keep watching. Data nodes that cannot see a leader keep the installed snapshot and keep answering requests.

Stale means `installed version < desired version` while the watch is down or the node has not acked. The watch thread publishes a gauge. The content path does not read it. `/readyz`, when HA's `HealthBoard` is in use, may report `config_stale` as text and still return 200. An optional max-staleness policy (off by default) flips readiness to 503 after the configured age. It does not close the accept loop by itself. Drain remains `Lifecycle.drain()` from the HA design, invoked by an operator or by that policy's explicit drain action.

On reconnect the node resumes from the persisted version. It does not drop the snapshot it is serving while the catch-up compile runs. The swap happens only after the new snapshot compiles.

Split brain of desired config is prevented by the single `cp-config` majority. Two sites that cannot see each other do not each elect a publisher (§11).

## 11. Multi-site

The default is one `cp-config` group for the whole deployment, with one version line. Voters sit where the operator places the 3 or 5 control nodes (one site, or spread across sites when the inter-site latency still meets the election timeout).

Other sites run **learners**: `changeMembership` adds them with an empty extra voter set and a learner set, which is the PR #29 joint-consensus API. A learner replicates the log, serves watches to data nodes in its site, and does not vote and does not sign. A site whose learners have the latest snapshot keeps pushing that snapshot locally during a WAN interruption. It does not advance the version.

An inter-site partition leaves each site serving the last snapshot its learners (or its data nodes) already applied. That is the HA fail-static rule. This design does not add a per-site quorum that can `Activate` on its own. HA's hierarchical quorum (per-site majority for local membership decisions, majority of sites for a global generation) remains the rule for `ha-control`. For desired config, local decisions are "keep serving" and "keep pushing an already-replicated version". Publishing stays one Raft group so a partition cannot fork the version line.

A later deployment that wants an independent per-site control plane is a separate group id and a separate version line, named explicitly by the operator. It is outside the default and outside this design's failure tests.

## 12. Security and threat model

### 12.1 Assets and boundaries

Assets: the desired config, TLS private keys for content certificates, node identity keys, the snapshot signing key, admin credentials, join tokens, and topic definitions. Message payloads stay in the messaging ledger. The control plane does not store them.

Boundaries:

| From | To | Channel |
|---|---|---|
| Admin (TUI or API client) | Control node | mTLS admin API, RBAC |
| Data node | Control leader | mTLS watch |
| Control voter | Control voter | `ClusterNode` transport, mutual auth (§12.2) |
| Control leader | ACME directory | HTTPS, leader-ready only |
| Content client | Data node | Existing content listener. This path has no control-plane call. |

The content listener is outside the control-plane trust boundary. Compromise of a request handler is contained by §12.4.

### 12.2 mTLS, identity, and cert rotation

Every control node and data node has an identity certificate. The SAN is `urn:sjws:node:<nodeId>` plus a DNS name where an operator needs one. The watch, the admin API, and the voter-to-voter transport require TLS 1.3 mutual authentication against the pinned CA bundle.

Rotation is a `TrustBundle` log entry followed by a config version that carries the new bundle. Verifiers accept the previous CA and the new CA for one overlap window (the next activated version, or a configured time bound stored in the bundle). The following version drops the previous CA. Node certificates are renewed by a CSR the node generates locally: the private key is created on the node, the leader signs the CSR only while `leaderReady()`, and the node installs the new certificate on the applicator thread. The control plane never receives the node private key.

A peer that fails verification closes the watch or the admin connection. The data node keeps last-good. Content connections are unaffected.

Until the cluster transport grows mTLS, voter-to-voter frames use the HMAC-SHA256 shared secret HA §9 already specifies for bundles, with the same rule that private keys are absent from heartbeats. The watch and the admin API are mTLS from the first implementation PR that exposes them. Replacing the HMAC with the node identity on the cluster transport is a follow-through on the same `BundleTransport` seam HA names, not a second trust model.

### 12.3 Signed snapshots and anti-replay

The signing key is an Ed25519 key held by the control plane. The private half lives in `stateDir` mode `0600` on voters and is replicated only as a sealed blob inside a `TrustBundle` entry addressed to the other voters' identity keys. It is not in `canonicalYaml`.

The signature covers `version`, `epoch`, `raftIndex`, `contentHash`, `projectionId`, and `nonce`.

A receiver rejects an envelope when any of the following holds:

- the signature does not verify under the current or overlap signing key;
- `version` is less than or equal to the last applied version (monotonic version);
- `epoch` is less than the highest epoch the node has already applied (a signed envelope from an older term cannot be reused after a newer term has been seen);
- the pair `(version, nonce)` has already been accepted (the nonce cuts a duplicate of the same version);
- `contentHash` does not match the bytes.

`Rollback` allocates a fresh version and a fresh nonce. Replaying the old envelope fails the version check. The retained historical document is re-signed in the current epoch.

### 12.4 Join tokens and bootstrap

The first control node starts with an admin token read from a file mode `0600`, opens a one-voter `cp-config` group, and prints the CA fingerprint. Additional voters join by `changeMembership` after they present a bootstrap token. The operator retires the one-voter configuration before serving data nodes. Production failure tests use 3 voters.

A data node joins with a one-time token. The token is bound to a role and to a label constraint, stored on the control plane as a hash, and accepted once. The first watch presents the token and a CSR. The leader, if `leaderReady()`, signs the node certificate, appends `Register`, and consumes the token. Later connections present the node certificate only. A reused token is an authentication failure and an audit event.

The operator pins the CA fingerprint out of band (the admin token file's sibling, or the TUI's first-run prompt). A data node refuses a watch peer whose CA does not match the pin.

### 12.5 Compromised data-plane node

A data node is not a voter and cannot append to `cp-config`. It cannot become leader and cannot sign snapshots.

The leader pushes a **projection**, not necessarily the full document. The projection is the subset of the canonical document that the node's role and capabilities require:

| Role | Projection |
|---|---|
| `gateway` | routing, middleware, access, TLS references for the names it terminates |
| `static` | server file settings, access, TLS references |
| `broker` | topics section |
| `edge` | the gateway projection plus the topics it is labeled to ingress |

`projectionId` is the node id. The signature covers that id and the hash of the projected bytes, so one node's envelope does not verify on another node. Sealed secrets in the projection are encrypted to that node's identity key.

`Revoke` appends the node id and the certificate fingerprint to the denylist. The leader closes that node's watch and refuses a new one. The node continues to serve whatever it already applied. It receives no later version and no later sealed secret. Revocation does not remotely wipe the last-good file; an operator who needs that pulls the host.

A compromised node can still answer its own clients with the config it already has, or with local tampering. Containment is: it cannot move the desired version, it cannot read another node's projection, and the operator has a denylist entry that stops further distribution.

### 12.6 Secrets

`canonicalYaml` contains no private keys, passwords, join tokens, or admin tokens. Those values are either:

- a reference (a path the receiving node already owns, mode `0600`), or
- a ciphertext sealed to the recipient node's identity key (X25519 sealed box). The Raft log stores the ciphertext. Apply decrypts on the recipient and writes the plaintext with the ACME store's atomic `0600` write.

Admin tokens and join tokens are stored as hashes. Heartbeats, registration entries, and audit logs carry node ids and versions, not secret bytes. The existing config rule stands: the YAML loader does not interpolate `${env:}` or `${file:}` into the document.

### 12.7 Admin RBAC

The admin API authenticates the client certificate and maps the SAN to a role stored in the signed config:

| Role | Allowed |
|---|---|
| `view` | Read desired config, diffs, nodes, leadership, per-node applied version. |
| `operate` | `view`, plus propose, stage, advance, activate, rollback. |
| `admin` | `operate`, plus voter membership, revoke, signing-key rotation, join-token issue. |

An unknown SAN is rejected. The bootstrap token maps to `admin` and works only from a connection that also presents the bootstrap file's holder, until the first admin identity is enrolled. The TUI uses this API. It does not get a side channel into the content server.

## 13. Integration points

Each consumer keeps its own runtime objects. The control plane's job ends when `LiveConfig` swaps.

### 13.1 Proxy and routing

`Routing` inside `ConfigDocument` is the section from [proxy routing](proxy-routing.md) (`RouteSpec`, `Upstream`). `ConfigSnapshot.compile` already calls `RoutingCompiler` when `routing.enabled`. A pushed document takes that same path. The control plane does not construct a `Router` and does not open upstream connections. With `routing.enabled: false` the compiled handler remains the static file handler, as on master.

### 13.2 Filter chain

`List<MiddlewareSpec>` is stored as the document already stores it (`name`, `order`, `route`, `options`). Compile behavior is unchanged: an empty list builds no chain; names are not resolved inside the control plane. A data node that has factories bound applies them when it compiles, on the applicator thread. A document that names an unknown factory fails `applyRemote` and leaves the previous snapshot.

### 13.3 Access rules

`AccessSettings` travels in the document and is applied by `StaticFileHandler` at compile time, which is the rule `ConfigSnapshot.compile` already implements for the command-line access settings. The YAML `access:` section stays owned by config. The control plane does not evaluate a request against the rules.

### 13.4 Config and ACME

Local reload remains `ReloadHook` → `ConfigReloader.reload` (re-read the file, clamp bind-time fields, compile, swap). Remote apply is `ConfigReloader.applyRemote` (§5.6). ACME issuance is leader-only and waits on `ControlLeadership` (§5.7, §6). Challenge responses on a data node still use the in-memory `ChallengeBoard` that `ConfigSnapshot.compile` already wraps in front of the handler when HTTP-01 is on the main listener. Tokens are placed by the leader's publish of the challenge reference inside the snapshot the data node applies, before the leader tells the ACME directory to validate. That ordering is the same as today's local ACME flow (token on the board before `trigger`), moved to the leader and the watch.

### 13.5 Messaging topics

The document gains a `topics:` list: name, partition count, and the durability mode messaging already defines (`async` / `group` / `each` / `none`, and the cluster ack `replicated` / `strict`). The control plane stores that list inside the canonical YAML and projects it to `broker` and labeled `edge` nodes.

The broker reads the list from `AppliedConfig.document()` after apply and opens or closes `Partition`s on its writer threads. `sjws_nettask.cp` does not call `Partition.open`, does not touch the ledger, and does not append to a partition group. `sjws_nettask.messaging` does not call `ControlLeadership` or the watch. A control-role process starts with no partition directory.

Desired topic state and the message log are different Raft groups. Losing the control plane does not truncate a partition log. The broker keeps the partitions it already opened from last-good.

## 14. Admin API and the TUI

The control role binds a dedicated admin address. The content port does not serve these routes.

| Method | Path | Role | Body |
|---|---|---|---|
| `GET` | `/cp/v1/health` | `view` | leader, term, `leaderReady`, voter count |
| `GET` | `/cp/v1/config` | `view` | activated `SignedSnapshot` metadata and canonical YAML |
| `GET` | `/cp/v1/config/diff?from=&to=` | `view` | `ConfigDiff` between two retained versions |
| `POST` | `/cp/v1/config` | `operate` | canonical YAML to `Propose` |
| `POST` | `/cp/v1/rollout` | `operate` | ordered selectors |
| `POST` | `/cp/v1/rollout/advance` | `operate` | open the next stage, or force |
| `POST` | `/cp/v1/rollback` | `operate` | `{ "toVersion": <long> }` |
| `GET` | `/cp/v1/nodes` | `view` | id, role, labels, version, freshness |
| `POST` | `/cp/v1/nodes/{id}/revoke` | `admin` | denylist |

Responses are JSON. Mutations return the committed `version` and `raftIndex`, or a validation error and no committed entry. A follower responds with the leader address and does not append.

`sjws_nettask.admin.ServerControl` stays the in-process control for `standalone` (start, stop, reload). The TUI gains a control-plane client that implements the calls above when the operator points it at an admin URL. The standalone screens are unchanged. The TUI never links the admin client into `Http1Connection`.

## 15. Failure test plan

In-JVM unless a row says otherwise. The cluster transport is the PR #29 sim transport when the assertion is about commit. `mvn verify` remains the CI command. New package `sjws_nettask.cp` gets its own JaCoCo `PACKAGE` rule, because `DocsConsistencyTest` requires one per directory under `src/`. The root package branch floor stays **0.96**. This workstream does not lower it.

| Case | Assertion |
|---|---|
| Signature | A flipped byte, an unknown key, or a bad `contentHash` is rejected. The installed snapshot is unchanged. |
| Anti-replay | An envelope with an old `version`, an old `epoch` after a newer epoch was applied, or a repeated nonce is rejected. |
| `Noop` | `leaderReady()` is false after the election callback and true only after the `Noop` is applied. ACME's directory client is not invoked before that. |
| Minority | With 3 voters and one link cut, the minority cannot `Activate`. |
| Leader loss mid-rollout | The new leader's `Noop` commits, then push resumes. A version already acked is not applied twice. |
| Watch drop | The data node keeps serving. Reconnect with `fromVersion` receives only newer versions. |
| Backpressure | A client that does not ack does not grow the server send queue past `maxInFlight`. Raft commit of a later version still completes. |
| Canary | A node whose labels miss stage 1 does not observe the new version until its stage opens. |
| Rollback | `toVersion` produces a higher version with the old hash. Nodes apply it or no-op when they already hold that hash. |
| Apply failure | `compile` throws. `LiveConfig` still returns the previous handler. |
| CP down at boot | Role `data` serves `last-good.cp`. Role `standalone` does not read that file. |
| CP down at runtime | Requests succeed against the installed snapshot. No request thread blocks on the watch. |
| Projection | A `broker` envelope fails verification on a `gateway` node id and contains no sealed blob for the gateway key. |
| Join token | The second presentation of the same token fails. |
| Revoke | The node receives no version committed after `Revoke`. |
| Standalone hot path | No `cp-` thread and no `cp-config` group. `SingleNodeHotPathTest` style: the handler is the static file handler when no filters are configured. |
| Dependency cut | A compilation unit of `sjws_nettask.cp` that imports `sjws_nettask.messaging` fails review; a test lists `cp` class files and rejects a constant-pool reference to `sjws_nettask/messaging`. |
| Last-good mode | The file is `0600` on a POSIX filesystem. A crash between compile and rename leaves the previous file in place. |

Chaos that stays scripted, not a required CI step: `kill -9` of a leader, pause, asymmetric partition. The sim-transport rows cover the partition assertion the unit suite can make deterministic.

## 16. Benchmark gate

Every PR in this workstream, including a docs-only PR, reports `wrk` and `wrk2` on the same host, before and after, with the control plane off (`standalone` / the current server).

```
wrk  -t4 -c128 -d30s --latency http://127.0.0.1:<port>/index.html
wrk2 -t4 -c128 -d30s -R50000 -L http://127.0.0.1:<port>/index.html
```

1 KiB file, loopback, four threads, 128 connections. The table in the PR body has req/s and p50/p90/p99/p99.9 for before and after, and the overhead. `standalone` must show no request-path work added by this mode: the after binary, when the role is off, does not open `cp-config` and does not start a watch. Shared-VM noise of the kind HA already recorded (throughput inside a few percent, fixed-rate p99 in the same band) is the comparison band. A regression that comes from a new read on the request path is a failed gate.

Implementation PRs also record a "control plane running, data node serving last-good, watch connected" column, to show the watch thread is off the request path. That column is not a license to add a per-request call.

## 17. Implementation plan

Each PR is based on current master (this document's base or newer), merges the HA stack when it needs `ClusterNode`, keeps `mvn verify` green, does not lower the 0.96 root branch floor, and does not merge itself.

1. **Design.** This document.
2. **Store and admin API.** `sjws_nettask.cp`: open `cp-config`, the state machine, `SignedSnapshot`, `ControlLeadership` including the election `Noop`, `ConfigDiff`, `applyRemote` on the config side, and the admin routes in §14 with RBAC. Tests from §15 that do not need a data-node watch. Benchmark as §16.
3. **Registration and distribution.** Watch client and server, projection, ack window, last-good file, staged rollout and rollback, label selection. Data role applies through `applyRemote` and `LiveConfig`. Tests for resume, backpressure, canary, partition, and revoke. Benchmark as §16.

The request path in both implementation PRs stays a volatile read of the immutable snapshot.
