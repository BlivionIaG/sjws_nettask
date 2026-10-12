# Control Plane / API Gateway Mode — sjws_nettask

Status: Draft · Scope: control plane and config distribution
Base: master `459d98e` (Java 25), which includes the config model from [PR #42](https://github.com/BlivionIaG/sjws_nettask/pull/42)
Consistent with: [high availability](../ha/design.md) (the document PR #14 added as `docs/design/high-availability.md`; PR #34 moved it), the per-group Raft API shipped in [PR #29](https://github.com/BlivionIaG/sjws_nettask/pull/29), and the cluster harness in `tests/sjws_nettask/clusterharness`.

PR #29 is merged into the HA stack (`cursor/ha-vip-restart-5d15` and its parents). It is not on master `459d98e`. This design calls that API. Implementation PRs merge the stack forward. They do not reimplement Raft.

Binding decisions, which override a review where the two disagree:

1. One hash. `canonicalHash` is the SHA-256 of the canonical serialization (`ConfigCodec.text`). The signature covers `canonicalHash`, `version`, and `schema`, and also `parentVersion`, `kind`, and `keyId`.
2. `ConfigDiff` is a per-section report. Distribution always sends a full signed snapshot. It never sends a patch.
3. Signature checks and rollback checks run inside `SjwsServer.applyRemote` and fail closed.
4. The control plane's last-good config is `<stateDir>/config/last-good.json`.

## 1. Context

sjws_nettask is one binary. Today it serves static files, optional HTTP/2 and HTTP/3, a filter chain, access rules, a YAML config snapshot with ACME, and a messaging ledger. The request path holds one `AtomicReference` of an immutable `ConfigSnapshot` (`LiveConfig`). `ReloadHook.run()` rebuilds that snapshot off the request path. HA ([docs/ha/design.md](../ha/design.md)) already decided that a request never waits on another node, that `ha-control` is one Raft group on the shared cluster transport, and that the default ack is majority in memory (`Durability.RELAXED`) with quorum-plus-fsync opt-in.

This mode adds a control plane for the desired configuration: routes, filters, access rules, certificates, and messaging topics. Control nodes (3 or 5) replicate that desired state. Data-plane nodes apply it locally and keep serving the last good snapshot when the control plane is unreachable.

The content request path reads the snapshot that is already installed. It does not call the control plane, the Raft group, or the watch stream.

## 2. Principles

1. **One binary, three startup roles.** `standalone` (the default), `control`, or `data`. The choice is made once at process start. `standalone` constructs no control-plane object, opens no `cp-config` group, and starts no watch. The HTTP path stays the path on master `459d98e`.
2. **Zero cost when the control plane is off.** With `standalone`, the accept loop and `Http1Connection` do not read a role, a lease, a generation from Raft, or a control-plane flag. Evidence for every PR, including this one, is a `wrk` / `wrk2` before/after on the same host. The gate is the same shape as HA: report req/s and p50/p90/p99/p99.9.
3. **Lock-free hot path.** A request performs one volatile read of an immutable snapshot (`LiveConfig.get` / `handler()`), then runs that snapshot's `Handler`. Publish is an atomic swap (`AtomicReference.set`) of the whole snapshot. The handler chain, the HA generation leaf, and the HTTP/3 handler supplier all read that snapshot. They do not keep a leaf captured at startup (§5.6). Readers take no lock. An in-flight request keeps the handler it already loaded.
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
| `sjws_nettask.cp` | Raft state machine, signing, rollout, admin API, watch, registration. Depends on `sjws_nettask.cluster` and on the config model. Owns `SignedSnapshot`. Does not apply patches. |
| `sjws_nettask.config` | Owns `ConfigDocument`, `ConfigCodec`, `canonicalHash`, `contentHash`, `ConfigValidator`, `ConfigDiff.between`, `ConfigSnapshot.compile`, `ConfigReloader.apply`, `LiveConfig`. It does not mention a `cp` type. Signature checks are not here; they are inside `SjwsServer.applyRemote` (§5.5). |
| `sjws_nettask.cluster` | Per-group Raft from PR #29. Unchanged by this workstream. It imports neither `sjws_nettask.cp` nor `sjws_nettask.messaging`. |
| Data plane (`http1`, `h2`, `h3`, `routing`, `core`, `messaging`, `acme` on a data node) | Sees a `Handler` or, for the broker and the composition root only, `AppliedConfig` (§3.2). Does not depend on `sjws_nettask.cp`. |

`sjws_nettask.cp` does not import `sjws_nettask.messaging`, `sjws_nettask.routing`, `sjws_nettask.http1`, `sjws_nettask.h2`, or `sjws_nettask.h3`. A control process can open `cp-config` and serve the admin API with no broker class initialized. `cp-config` has its own log directory and its own segments. It does not share the messaging ledger's segments, so compaction or truncation of one cannot affect the other.

The composition root is `Sjws_nettask` / `SjwsServer`. It is the only place that may mention both a data-plane server and `sjws_nettask.cp`, and only through `sjws_nettask.cp.CpBootstrap`, reached from the `control` or `data` branch. `standalone` does not name that class, so the verifier does not load `sjws_nettask.cp` or `sjws_nettask.cluster` (§16).

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
    /** Lowercase SHA-256 of the canonical serialization. Empty when the snapshot never came from the CP. */
    String canonicalHash();
    ConfigDocument document();
    /** Prebuilt handler. One volatile read at the call site, then plain invocation. */
    Handler handler();
}
```

`ConfigSnapshot` implements `AppliedConfig`. `version()` is `ConfigDocument.bundleVersion()` (0 when unset). `canonicalHash()` is `ConfigDocument.canonicalHash()`. `epoch` is one extra `long` on the snapshot (0 when local). That field is the only new hot-path cost. `LiveConfig` remains the `AtomicReference` holder. The harness method `DataNode.contentHash()` returns this `canonicalHash`.

Transports do not import `AppliedConfig`. They already depend on `Supplier<Handler>` and `HandlerRef`, which is the narrower seam, and that stays. HTTP/1, HTTP/2, and HTTP/3 read the supplier the publish step installs (§5.6). `AppliedConfig` is for the broker and the composition root. The messaging broker, when it is running, reads `document()` on its partition-writer thread to learn the desired topic set. That read is not on the HTTP request path and is not a call to the control plane.

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
| Accept, parse, `Handler.handle` | Existing accept loop and request virtual threads. They read the live snapshot supplier only. |
| Raft election, append, fsync, apply | `sjws_nettask.cluster` group threads. `onCommitted` does not compile or sign. |
| Compile and sign on a control leader | A dedicated compiler thread. A large document cannot stall Raft apply. |
| Verify, compile, `publish` on a data node | One applicator thread per process, started only for role `data`. It is the only caller of remote `publish`, so it serializes with itself. |
| Admin API | Its own listener and virtual threads, control role only, distinct port from content. |
| Watch stream | One virtual thread per data node, not a content request. A slow ack blocks that thread only. |
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
| `Register` / `Heartbeat` / `Revoke` | Data-node liveness and denylist. `Register` commits in the same entry as `ConsumeToken` (§12.4). |
| `TrustBundle` | Root-signed delegation of per-voter online signing keys, CA intermediates, and the version floor (§12.3). `admin` only. |
| `Rbac` | SAN-to-role map and the secrets-directory allowlist (§12.7). `admin` only. A `Propose` that carries either is rejected. |
| `Audit` | One control-plane audit record (§12.10). Replicated so one voter cannot drop it. |
| `CertReference` | ACME result: chain reference and envelope-encrypted key material (§5.7, §12.6). |

`SnapshotControl.take` / `install` / `truncateAndCompact` compact the log to the latest activated snapshot plus the live registration table. Compaction runs off the request path, on the group thread, past an index that is both snapshotted and acked. The compactor keeps the latest value per key (current desired snapshot, denylist, registrations). History required for rollback is retained for a configured number of activated versions (default 8) inside the snapshot payload, then dropped.

Membership changes of the voters use `ReplicationGroup.changeMembership` (joint consensus). Learners are non-voting (§11).

## 5. Config version and snapshot model

### 5.1 Reuse the config model

The model is the one PR #42 merged: `ConfigDocument` (`ServerConfig`, `AcmeSettings`, `Routing`, `List<MiddlewareSpec>`, `AccessSettings`, `NodeSettings`, `schemaVersion`, `bundleVersion`), `ConfigCodec`, `ConfigValidator`, `ConfigDiff.between`, `ConfigReloader.apply`, and `SjwsServer.applyRemote`. The control plane does not grow a second document. Topics (§13.5) are added to that document, not beside it.

Two digests already exist, and they stay different:

| Accessor | Bytes |
|---|---|
| `contentHash()` | SHA-256 of the operator file that was read. Null when there is no file. Key order in that file changes it. |
| `canonicalHash()` | SHA-256 of `ConfigCodec.text`. Fixed key order, maps sorted, TLS key password omitted. This is the only hash the control plane signs. |

`ConfigLoader` remains the strict parser. `ConfigSnapshot.compile` remains the step that builds the handler. `ConfigReloader.reload` remains the local-file path and is what `ReloadHook.run()` calls. A remote apply does not call `ReloadHook.run()`.

### 5.2 Identifiers

Three numbers, kept distinct on purpose:

| Name | Meaning |
|---|---|
| `version` | `ConfigDocument.bundleVersion()`. Cluster-wide monotonic `long`. Assigned when `Activate` or `Rollback` commits. Strictly increasing. `0` means unset, so a local edit with no version still applies. This is the number a data node resumes from. |
| `schema` | `ConfigDocument.schemaVersion()`. Schema 1 is the only accepted value and is inside the canonical YAML. An unknown schema fails `ConfigValidator.validate` before compile. |
| `epoch` | Raft term of `cp-config` at the moment the leader signs the envelope. Changes when leadership changes. |
| `generation` | The existing process-local `ConfigSnapshot.generation` (`int`). It increases by one on every successful local reload and every successful remote apply in this process. Standalone keeps today's counter. |

`AppliedConfig.version()` exposes `version`. A standalone snapshot reports `0`.

### 5.3 Envelope

```java
record SignedSnapshot(
    byte[] keyId,           // which online signing key produced the signature
    long version,           // bundleVersion
    int schema,             // schemaVersion; 1
    long epoch,
    long raftIndex,
    long parentVersion,     // always 0; distribution does not send patches
    byte kind,              // always 1 (full). A different value fails closed
    String projectionId,    // node id the bytes were cut for
    byte[] canonicalHash,   // SHA-256 of ConfigCodec.text, 32 raw bytes
    long issuedAtMillis,
    long expiresAtMillis,
    byte[] nonce,           // 16 bytes, covered by the signature; not stored as a set
    byte[] canonicalYaml,   // the full canonical document, never a patch
    byte[] signature) {}
```

The signature input is the domain-separated encoding in §12.3. It covers `canonicalHash`, `version`, and `schema`, and also `parentVersion`, `kind`, and `keyId`. There is no second hash of "transmitted bytes": the bytes on the wire are the canonical YAML, and `canonicalHash` is their digest.

Canonical YAML is `ConfigCodec.text` of the document:

- UTF-8, LF line endings, no BOM. The codec already emits a fixed key order with maps sorted.
- The TLS key password is omitted.
- `schema` and `version` are the first lines.
- SHA-256 over those exact bytes, lowercase hex on `ConfigDocument.canonicalHash()`, raw 32 bytes in the envelope.

`kind` is signed so a rewritten kind fails closed, and `applyRemote` rejects any value other than 1. `parentVersion` is signed and must be 0. A non-zero parent is a reject, not a request for a patch.

### 5.4 Diffs

`ConfigDiff.between(before, after, http3Running)` is a per-section report. Each `Change` names a section (`schema`, `version`, `node`, `server`, `http3`, `tls`, `acme`, `routing`, `middleware`, `access`, `startup`), an effect (`LIVE` or `RESTART`), and a comma-separated list of field names. It does not return bytes. There is no `ConfigDiff.apply`. A `Change` list is not turned back into a `ConfigDocument`.

The report is what `GET /cp/v1/config/diff` returns. Field names never include a password, a PEM body, or file bytes.

Distribution does not use this report. The watch sends a full `SignedSnapshot` for every version (§8). The Raft log stores the full canonical YAML. `Activate` and `Rollback` both ship that full document.

### 5.5 Validate, inside `applyRemote`

`SjwsServer.applyRemote` is the data-plane entry. Signature verification and rollback verification run inside that method, under `reloadLock`, before `ConfigReloader.apply`. A failure returns `ReloadResult.rejected` with the snapshot that is already installed. The method does not publish, does not compile, and does not throw the previous snapshot away. That is fail closed.

Order inside the lock:

1. Frame size, then the signature under the `keyId` in the envelope (§12.3). The online key must be inside its `notAfter` and its allowed version range, and the root delegation that names it must verify. `kind` must be 1 and `parentVersion` must be 0. `canonicalHash` must equal SHA-256 of `canonicalYaml`. `schema` in the envelope must equal the `schema` in that YAML.
2. Anti-replay and freshness (§12.3). `version` must be greater than the anti-rollback floor, `version - lastApplied` at most 2^20, and `version` at most `Long.MAX_VALUE - 2^20`. `epoch` greater than or equal to the highest epoch already applied is defense in depth. `expiresAtMillis` must still be in the future. `issuedAtMillis` must not be ahead of the local clock by more than 30 s.
3. Rollback check, when the activation is a rollback: `version` is strictly greater than the installed version, and `canonicalHash` equals the retained hash of `toVersion`. A missing retention, or a hash that does not match, is a reject. The historical document is what was re-signed; the old envelope is not replayed.
4. A document whose YAML contains `rbac` or `trust` is rejected. Those sections are their own log entries (§12.7).
5. Only then does `config` see the bytes. `ConfigValidator.validate` checks schema, the document root, cert and key files, and that `tls.cert` and `acme.enabled` are not both set. `ConfigReloader.apply(current, incoming, http3Running, board, keepLocalFile)` clamps bind-time fields. `keepLocalFile` is true, so a push does not retarget the local config path. A non-zero `bundleVersion` lower than the one already running is rejected. An equal version with the same `canonicalHash` is a successful no-op: ack, no second swap.
6. Secret references pass the allowlist in §12.6 before any file open. `ConfigSnapshot.compile` runs against the local document root and the local challenge board. The snapshot is fully built before it is published.

`node.mode` is clamped like `server.port`: a new mode is reported in `needsRestart` and the running mode stays until process restart. Labels apply on the successful swap. The config package does not import `sjws_nettask.cp`. `SjwsServer.applyRemote` is the composition-root method that names `SignedSnapshot`, because the checks are inside it. `standalone` does not call that method.

### 5.6 Publish, one path for local reload and remote apply

File reload and `applyRemote` share `reloadLock`, the lock `SjwsServer.reload()` already holds around `reloadLocked()`. `applyRemote` runs the checks in §5.5, then `ConfigReloader.apply`, then the same publish `reload()` uses: ACME listener, reaper timeouts, `live.set`, `replaceHandler`, `replaceTls`. It does not call `ReloadHook.run()`. Generation is assigned only inside the lock, from the snapshot currently installed. Two callers cannot both compute `generation + 1` from the same base. There is one generation authority.

Role `data` does not let `ReloadHook` / `SIGHUP` publish CP-owned sections from the local file. Those sections are routing, middleware, access, topics, and ACME material that arrived in an envelope. A SIGHUP may still apply local-only fields the control plane does not own (this host's document-root path, the listen address). A SIGHUP that would change a CP-owned section is rejected and audited. Role `standalone` keeps today's full file reload.

The publish step, for a file reload and for a remote apply:

1. The new snapshot is already compiled, including resources it owns (a router and its upstream pools, once that snapshot carries them). Compile finishes before the swap.
2. Under `reloadLock`: open or close the dedicated ACME HTTP-01 listener, update `ConnectionReaper` timeouts, `live.set`, and `Http3Server.replaceHandler` / `replaceTls`. The same side effects today's `reloadLocked()` performs.
3. The previous snapshot is closed after the swap, on the publisher thread, not on a request thread. Requests that already loaded the old handler keep it.
4. The accept loop stays open. Bind-time fields (port, backlog, connection caps, and an HTTP/3 listener's timeouts) are still reported as needing a restart and are not silently changed.

Handler sources all read the snapshot that `publish` installed:

- With no filters and no HA, HTTP/1 keeps the direct file-handler call when the leaf is a `StaticFileHandler`, and reads `live.handler()` when the leaf is anything else. That is the path on master today.
- With caller filters, a custom terminal, or an observe chain, the chain's leaf is a supplier of `live.handler()`, not a handler captured at startup. When the filter list itself changes, `publish` builds the new chain against that supplier and swaps the chain reference under the same lock.
- With HA, `generationLeaf` is `live::handler` (or the supplier-backed chain), not a captured `built`. `HaHandler` therefore serves the snapshot `publish` just set.
- HTTP/3 is given that same supplier. `replaceHandler` runs on every successful publish, including when HA or filters installed an `override` at startup. `override` points at a handler that reads the live snapshot.

A node acks a version only after `applyRemote` returns applied. An ack means HTTP/1, HTTP/2, and HTTP/3 will observe the new leaf. After that, the applicator persists the floor, the trust bundle, and `<stateDir>/config/last-good.json` (§5.8), then acks on the watch. The accept loop is not closed by apply. A test that samples the content port during `Activate` sees no non-2xx and no connection failure (§15).

### 5.7 Certificates

ACME runs on the control leader only, and only after `leaderReady()` (§6). Followers do not create accounts or orders. Data nodes do not talk to the ACME directory.

The leader publishes a `CertReference`: the certificate chain, the content hash, and key material sealed to the data nodes that terminate TLS (§12.5). The plaintext private key is written on the leader with the existing ACME store rules (mode `0600`, temp file, atomic move). It is not placed in `canonicalYaml` and it is not placed in a heartbeat.

Data nodes install the chain through the same `TlsMaterial` / `Http3Server.replaceTls` path a local reload uses, inside `publish` (§5.6).

### 5.8 Last-good persistence and the anti-rollback floor

The control plane's last-good config is `<stateDir>/config/last-good.json`. After every successful `applyRemote` the data node writes three files, each via a temp file, `ATOMIC_MOVE`, mode `0600`, and an `fsync` of the file and its directory. The floor write finishes first:

1. `<stateDir>/config/floor.json` — `(maxVersion, maxEpoch, keyId)`.
2. `<stateDir>/config/trust.json` — the trust bundle that verified the envelope, so a restart after key rotation can check the signature while the control plane is down.
3. `<stateDir>/config/last-good.json` — the signed snapshot: `schema`, `version`, `canonicalHash`, `keyId`, `epoch`, `parentVersion`, `kind`, `projectionId`, `issuedAtMillis`, `expiresAtMillis`, `nonce`, `signature`, and the canonical YAML.

`standalone` creates none of these and does not create `stateDir` for the control plane.

The config package still writes canonical YAML (no password) to the sibling `<config-file>.last-good` after a successful start or apply, and boots from that sibling only when the operator file is missing and `node.mode` is `data`. That sibling is not the signed envelope. A path that already ends in `.last-good` is not given a second suffix. Role `data` that has a `stateDir` serves CP-owned sections from `last-good.json`, not from the sibling.

Precedence for role `data`, at boot and at runtime:

| Source | What it wins |
|---|---|
| Pin file and join material, outside `stateDir` | Root public key and the root version floor. Required. A missing pin refuses to start. |
| `<stateDir>/config/floor.json` and `trust.json` | Highest applied `(version, epoch, keyId)` and the trust bundle. Read before `last-good.json`. |
| `<stateDir>/config/last-good.json` | CP-owned sections, after the same signature and rollback checks `applyRemote` uses. Fail closed: a bad file is not compiled. |
| Local `--config` file | Bind-time and local-only fields: port, listen address, this host's document root, `stateDir`. It does not override CP-owned sections. |
| `<config-file>.last-good` | Only the config package's missing-file boot. It does not override `last-good.json`. |

On startup the process reads the pin, then `floor.json` and `trust.json`, and only then considers `last-good.json`. The file is compiled when its signature verifies under the persisted bundle (which must chain to the pinned root), its `version` equals `maxVersion`, and its `canonicalHash` matches the canonical YAML in the file. Those checks are the boot path of `applyRemote`: a failure leaves the file uncompiled.

An envelope at or below the floor, or a last-good file older than the floor, is not compiled. The process still binds the content listener and answers 503 (not ready) until a snapshot passes the check. It does not serve CP-owned sections from the local YAML. `/healthz` can be 200 while the process is up. `/readyz` is 503.

A wipe or restore of `stateDir` deletes `config/floor.json`, `config/trust.json`, and `config/last-good.json` together, so those files cannot stop a replay by themselves. The floor that survives the wipe is the root version floor in the pin and in the join material (§12.3, §12.4). A node with an empty `stateDir` still refuses any envelope whose `version` is below that floor, and it binds 503 until a newer envelope arrives.

The watch connects in the background. If the control plane is down, the process serves the snapshot that passed the floor check. A corrupt or unverifiable file is not applied. The listener stays bound and answers 503 until a good snapshot is published. There is no older snapshot in memory at boot.

## 6. Leadership interface

Config and ACME need a narrower view than `ClusterNode`. The control plane exposes this and implements it with the PR #29 group:

```java
package sjws_nettask.cp;

public interface ControlLeadership {
    /** Delegates to ReplicationGroup.isLeader() for cp-config. */
    boolean isLeader();

    /**
     * True after this term's election Noop has been committed and applied.
     * isLeader() can be true while this is still false. The harness reads this
     * on ControlNode. isLeader() alone is too early.
     */
    boolean leaderReady();

    /** Current Raft term. 0 before the group has a term. */
    long term();

    /**
     * term, leader id. Empty when the leader is unknown. Group thread.
     * Wired to ClusterNode.onLeaderChange(new GroupId("cp-config"), ...).
     * Never invoked on a content request thread.
     */
    void onLeaderChange(BiConsumer<Long, Optional<String>> listener);

    /**
     * Fired on the group thread only after this leader's election Noop is applied.
     * appliedIndex is that entry. Not fired merely because isLeader() is true.
     */
    void leaderReady(BiConsumer<Long, Long> listener);
}
```

On each transition to leader, the new leader appends a single `Noop` in the new term before it proposes config or starts an ACME order. `leaderReady()` becomes true, and `leaderReady(term, appliedIndex)` fires, when `onCommitted` observes that `Noop`. A leadership loss, or a leader id that is not this node, clears `leaderReady()` and cancels an in-flight ACME order. A later `leaderReady` for the new term may start again. ACME subscribes to `leaderReady(term, appliedIndex)` and does not contact the directory on `isLeader()` alone.

`standalone` uses a local stub that is leader and fires `leaderReady` once at startup, so today's ACME path is unchanged. `control` uses the real group. `data` does not construct an `AcmeService`.

`readBarrier` / lease reads stay inside the cluster package. Config publish uses `append` on the leader. The HTTP request path calls none of these methods.

## 7. Registration and heartbeats

A data node connects to a control node over the watch (§8). The first message after the handshake is `Register`: node id, the fingerprint of its node certificate, and the applied version. Role, labels, and capabilities are not taken from this message. They are the values the control plane wrote into the certificate when it consumed the join token (§12.4). A `Register` whose role, labels, or capabilities differ from that certificate is an authentication failure and an audit event. The connection closes. The projection is derived from the certificate.

The leader appends one Raft entry that is both `ConsumeToken` and `Register`, and only then returns the certificate (§12.4). Followers learn the registration through Raft. A follower that receives a watch redirects the node to the leader (the hint is the same idea as `NotLeaderException`: leader id and address). The data node retries against the hint.

Liveness heartbeats are records on that same stream, default interval 1 s, carrying the applied version and a monotonic send time. The leader appends a coalesced `Heartbeat` (at most one committed entry per node per interval; intermediate beats update an in-memory freshness table on the leader and are included in the next entry). A node with no beat for 5 s is `SUSPECT`. A node with no beat for 15 s is `DEAD` for rollout accounting. Neither state changes what the data node is serving. Dead nodes are skipped by the current stage and do not block a stage whose policy allows a configured minimum of acks; a policy of "all targeted nodes" waits.

Separately, the leader pushes a signed **freshness** frame at least every 30 s, including when the desired version has not changed (§12.3). That frame is how a data node distinguishes "nothing new" from "a newer version is being withheld". It is not the liveness heartbeat above.

HA's 64-byte UDP heartbeat remains the failure detector for `ha-control` membership. Data-plane registration uses the mTLS watch so the identity is the node certificate. The two protocols are not merged.

## 8. Push / watch protocol

Transport: one mTLS connection (TLS 1.3) from the data node to the leader of `cp-config`. The listener is the control admin port's sibling, or the same port with a different ALPN (`sjws-cp-watch`). It is not the content port.

Frames are length-prefixed. The maximum frame size is 16 MiB by default and is configurable downward. A larger frame closes the connection before the body is buffered (§12.8). The signature of a snapshot or freshness frame is verified inside `applyRemote` before the payload is parsed (§5.5). There is no diff frame.

The client speaks first:

1. `Register` (§7).
2. `Watch { fromVersion }`, where `fromVersion` is the last version this node has applied and persisted, or `0`.

The server then sends, in version order, a full `SignedSnapshot` for every version above `fromVersion`. A client that already holds the parent still receives the full document. Patches are not a frame type.

After the client is caught up, the server sends each newly activated version the same way. The client replies `Ack { version }` after `applyRemote` has swapped the live snapshot, the side effects in §5.6 have run, and `floor.json`, `trust.json`, and `last-good.json` are durable.

Backpressure: the server window is one unacked version by default (`maxInFlight`, configurable, minimum 1). It does not send version N+1 before `Ack` of N. The applicator queue on the client is bounded to that window. A slow apply stalls the stream. It does not buffer an unbounded log on the data node, and it does not stall Raft apply on the leader: the leader's push task reads the committed snapshot from memory and blocks on that one node's window only. Other nodes have their own windows.

Resume: a dropped connection reconnects with `Watch { fromVersion = last acked }`. The server sends only higher versions. Duplicate `Ack`s are idempotent. A version already applied is not compiled again (`version` equal to the installed version is a successful no-op).

The content request path does not read this socket.

Watcher cap: the leader runs at most one push task per node id, and at most a configured number of watch connections in total (default 1024). A connection past the cap is refused. Join and CSR work is rate-limited per source address (§12.8) and does not run on the Raft apply thread.

## 9. Staged rollout, canary, and rollback

An operator proposes canonical YAML through the admin API. The leader validates with `ConfigLoader`, appends `Propose`, and the proposal sits in `STAGED`.

`Stage` names an ordered list of selectors. Each selector is a conjunction of label matchers (`role=gateway`, `track=canary`) plus an optional percent of the matching registered nodes (stable hash of the node id, so membership jitter does not reshuffle the set while the stage is open). The first stage is the canary. A typical policy is `track=canary`, then `role=gateway`, then the remainder.

The leader pushes the staged version only to nodes in the open stage. Other nodes keep their installed version. A node that does not match any selector is untouched.

`Advance` opens the next stage after the open stage has enough `Ack`s. The default requirement is every targeted node that is `ALIVE`. The operator can set a minimum count. A node that is `DEAD` is reported and, under the default policy, blocks advance; the operator may skip it explicitly. That skip is itself a log entry.

`Activate` commits when the last stage has met its ack rule, or when an admin with role `operate` forces it. Activation publishes the version as desired. Nodes that have not acked still receive it (subject to their window) and still serve last-good until they apply.

`Rollback { toVersion }` appends a new version whose `canonicalHash` and canonical YAML equal the retained activated snapshot `toVersion`. The new version is higher than any current version, so anti-replay still moves forward. The rollout of a rollback uses the same stage list unless the operator selects a single stage of all nodes. Nodes that never installed the bad version treat the rollback as a no-op if their installed hash already matches, and ack it.

A proposal that fails validation is not appended. A failed `publish` on one node does not roll the others back automatically. The admin API shows the node error. The operator issues `Rollback`.

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

Assets: the desired config, TLS private keys for content certificates, node identity keys, the offline root key, the per-voter online signing keys, admin credentials, join tokens, and topic definitions. Message payloads stay in the messaging ledger. The control plane does not store them.

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

TLS is 1.3 only. There is no fallback to 1.2, no 0-RTT early data, and no session-ticket resumption across an identity change (ticket keys rotate with the identity). Leaf keys are Ed25519 or ECDSA P-256. The URI SAN is exactly `urn:sjws:node:<nodeId>`. A verifier matches that SAN to the peer it expected: a voter checks a voter id, and a data node checks that the peer is a voter or learner id named by the trust bundle, not merely that the CA signed something. Extended key usage splits `serverAuth` and `clientAuth`.

Three intermediates, each signed by the offline root (§12.3): one for voters, one for data nodes, one for admin clients. A data-node certificate cannot authenticate as a voter or as an admin. The online intermediate private keys live on the control leader only while `leaderReady()`, mode `0600`. The root private key does not.

Node certificates last 24 hours. The node renews by CSR at two thirds of that lifetime. The private key is generated on the node. The leader signs the CSR only while `leaderReady()` and only after the join token or the existing certificate has been checked. The control plane never receives the node private key. A sealing key (§12.6) is a different key from the identity key. There is no conversion of an Ed25519 identity key into an X25519 sealing key.

A peer that fails verification closes the watch or the admin connection. The data node keeps last-good. Content connections are unaffected.

Voter-to-voter mTLS is a ship blocker for every deployment except a single-node lab. A group with more than one voter refuses to start until voter mTLS is on. The HMAC shared secret from HA §9 is allowed only in that lab, behind an explicit flag, and it is not a production identity. The watch and the admin API are mTLS in every role, including the lab.

### 12.3 Keys, signatures, freshness, and anti-replay

Two tiers of keys.

- The **root** is offline and operator-held. It signs `TrustBundle` entries. Data nodes pin the root public key in the pin file outside `stateDir`. They do not pin an online key as the trust anchor.
- Each voter has its own **online signing key** (Ed25519), named by `keyId`. The root delegates it in a `TrustBundle` with `notAfter` and an allowed version range. A compromised voter is revoked by a new bundle that drops that `keyId`. The other voters keep signing. There is no single signing key sealed to every voter.

The signature input is this canonical byte string, and nothing else is trusted outside it. Integers are unsigned big-endian, fixed width. Strings are a `uint16` length plus UTF-8 bytes. Hashes are 32 raw bytes.

```
"sjws-cp-snapshot-v1" || keyId || version:u64 || schema:u32 || epoch:u64 || raftIndex:u64
  || parentVersion:u64 || kind:u8 || projectionId
  || canonicalHash:32
  || issuedAtMillis:u64 || expiresAtMillis:u64 || nonce:16
```

`canonicalHash` is the SHA-256 of `ConfigCodec.text` (§5.3). `version` is `bundleVersion`. `schema` is `schemaVersion`. `keyId`, `parentVersion`, and `kind` are in the same encoding, so changing any of them without re-signing fails closed. `kind` is 1 and `parentVersion` is 0 on every snapshot this design sends.

A receiver rejects an envelope when any of the following holds:

- the signature fails under `keyId`, or the root delegation for that key is missing, expired, or outside the key's version range;
- `version` is less than or equal to the anti-rollback floor (§5.8);
- `version - lastApplied` is greater than 2^20, or `version` is greater than `Long.MAX_VALUE - 2^20` (a jump to the top of the `long` range is a reject, not a publish);
- `expiresAtMillis` is in the past, or `issuedAtMillis` is more than 30 s ahead of the local clock;
- `canonicalHash` does not equal SHA-256 of the canonical YAML, or `schema` disagrees with the YAML;

`version` is strictly monotonic, so a replay of an old envelope fails the floor check. The nonce is inside the signature so two signatures of the same body differ. It is not stored as a set. `epoch` is recorded and must be greater than or equal to the highest epoch already applied. That check is defense in depth against a deposed leader's key. `version` is what orders envelopes.

Every envelope carries `issuedAtMillis` and `expiresAtMillis` (default lifetime 15 minutes). While a version stays current, the leader also pushes a signed freshness frame at least every 30 s: `version`, `issuedAtMillis`, `expiresAtMillis`, `keyId`, and a signature over those fields with domain `"sjws-cp-freshness-v1"`. Max-staleness compares the local clock to that signed expiry. A withheld update and a quiet version are different: quiet still refreshes the freshness frame. A node past expiry without a new frame treats the snapshot as stale for readiness. It keeps serving last-good unless the operator turned max-staleness drain on (§10).

Key compromise: the operator signs a new `TrustBundle` with the root. The bundle names the replacement online keys and a **version floor**. Every envelope signed by a retired `keyId`, and every envelope below that floor, fails verification even on a node whose `stateDir` was wiped. The floor is also written into the pin file and into join material so a node that has not yet seen the bundle still enforces it.

`Rollback` allocates a fresh version and is signed by a current online key. The historical document is what gets re-signed. The old envelope is not replayed.

### 12.4 Join tokens and bootstrap

A join token is `{tokenId, secret}`. The control plane stores the hash of `secret`, a TTL (default 1 hour, maximum 24 hours), the role, the labels, the capabilities, and an optional expected node id or SAN. The token is single-use.

The data node checks the pinned CA fingerprint **before** it sends the token. Role `data` has no trust-on-first-use path. A lab process may pass `--insecure-tofu`, and that flag is refused when the role is `data` or when the group has more than one voter. The bootstrap API returns the fingerprint and the token to its caller. A test does not scrape stdout for either value.

Role, labels, and capabilities come from the token record. The leader writes them into the node certificate (SAN and a role extension). The projection is derived from that certificate. `Register` cannot raise them. A `Register` that claims a different role is rejected.

`ConsumeToken` and `Register` are one Raft entry. The leader appends that entry and waits until it commits, and only then returns the certificate. A leadership change cannot accept the same token again: the consume is already in the log, or it is not and the certificate was not sent. Failed presentations are rate-limited and audited per source address. The token is checked before any CSR is signed, so an unauthenticated joiner does not cost a signature.

Voter bootstrap tokens follow the same TTL, hash, and single-use rules, and an enrolled `admin` must approve them. The one-voter lab configuration is not a production deployment. Production failure tests use 3 voters. The operator retires the one-voter group before data nodes join.

### 12.5 Compromised data-plane node

A data node is not a voter and cannot append to `cp-config`. It cannot become leader and cannot sign snapshots.

The leader pushes a **projection**, not the full document. The projection is the subset the certificate's role and capabilities allow:

| Role | Projection |
|---|---|
| `gateway` | routing, middleware, access, TLS references for the names it terminates |
| `static` | server file settings, access, TLS references |
| `broker` | topics section |
| `edge` | the gateway projection plus the topics it is labeled to ingress |

`projectionId` is the node id from the certificate. The signature covers that id, so one node's envelope does not verify on another node. Secrets in the projection use the envelope encryption in §12.6, wrapped to that node's sealing key.

`Revoke` is a signed log entry: node id, certificate fingerprint, and the join-token ids issued for that identity. Every watch server, voter or learner, and every admin listener checks the denylist on handshake and again when the entry is applied, and drops a live connection that matches. A 24-hour certificate plus "no renewal" is what makes revocation stick on a peer that has not yet seen the denylist. On revoke, any secret previously wrapped to that node's sealing key is rotated, and the audit log records the rotation. The node keeps serving what it already applied. It receives no later version. Revocation does not remotely wipe last-good. An operator who needs that pulls the host.

A compromised node can still answer its own clients with the config it already has, or with local tampering. Containment is: it cannot move the desired version, it cannot read another node's projection, and further distribution stops.

### 12.6 Secrets

`canonicalYaml` contains no private keys, passwords, join tokens, or admin tokens. A secret is either a reference or ciphertext.

References are paths inside a secrets directory named by the `Rbac` entry (§12.7). The path is canonicalized. `realPath` must stay inside that directory. Symlinks that leave it are rejected. An error is a code. It does not include file bytes. A proposal that names `/etc/shadow` or a `..` path fails this check and is not opened.

Ciphertext is envelope encryption. Each version generates a data key. The secret is AES-256-GCM under that key, with AAD `version || nodeId || secretName`. The data key is wrapped to the recipient's current X25519 sealing key, which is separate from the identity key and rotates on its own `TrustBundle` entry. The Raft log stores the ciphertext and the wrapped data key. Only the recipient unwraps it, and only into a `byte[]`. After the `0600` atomic write, the buffer is wiped with `Arrays.fill`. Secrets are not Java `String`s. They are not placed in exception messages. `PrivateKey.destroy()` is not relied on.

A `Secret` value's `toString` is `***`. Logs, metrics, admin JSON, and validation errors are scanned in tests for token and sealed-blob patterns. `GET /cp/v1/config` and `GET /cp/v1/config/diff` return ids and hashes of sealed blobs, not the blobs.

Heap dumps and core dumps are in scope. The service unit sets `-XX:-HeapDumpOnOutOfMemoryError` and `ulimit -c 0`.

Admin tokens and join tokens are stored as hashes. Heartbeats and audit records carry node ids and versions, not secret bytes. The YAML loader does not interpolate `${env:}` or `${file:}`.

### 12.7 Admin RBAC

The admin API authenticates the client certificate. The SAN maps to a role stored in the `Rbac` log entry, which only `admin` can append. `Propose` is rejected when the YAML contains `rbac` or `trust`. An `operate` identity cannot grant itself `admin`, and it cannot rotate keys or issue join tokens.

| Role | Allowed |
|---|---|
| `view` | Read desired config, diffs, nodes, leadership, per-node applied version. Responses omit sealed blobs. |
| `operate` | `view`, plus propose, stage, advance, activate, rollback. |
| `admin` | `operate`, plus voter membership, revoke, `TrustBundle`, `Rbac`, join-token issue. |

An unknown SAN is rejected. The bootstrap token maps to `admin` until the first admin identity is enrolled, and only from a connection that also presents that token's holder. The TUI uses this API. It does not get a side channel into the content server.

### 12.8 Limits

| Limit | Default |
|---|---|
| Frame size | 16 MiB, configurable downward |
| Materialized document | 4 MiB |
| YAML aliases | `LoaderOptions.setMaxAliasesForCollections` at 50 |
| YAML nesting depth | 32 |
| YAML code points | the materialized-document cap |
| TLS handshake and idle | 10 s handshake, 60 s idle |
| Watch connections | 1024 total, one push task per node id |
| Join, CSR, and admin mutations | per-identity and per-source-address token buckets |

The signature is verified before a payload is parsed or decompressed. Compile is bounded in time and output size. An unauthenticated join is rejected on the token check before any signing work.

### 12.9 Audit

An append-only audit stream, separate from the access log, records actor SAN, role, action, target, `version`, `raftIndex`, result, source address, and timestamp. It covers every admin mutation, join success and failure, revoke, key and CA rotation, signature and anti-replay rejection, and admin authentication failure. Control-plane events are `Audit` log entries, so one voter cannot drop them. The stream never contains token or secret bytes (§12.6).

### 12.10 What `standalone` does not do

Role `standalone` does not load `sjws_nettask.cp` or `sjws_nettask.cluster`, does not initialize a crypto provider for the control plane, does not create `stateDir` for it, and does not bind an admin or watch port. The proof is the class-load test in §15, not a throughput delta. `<stateDir>/config/last-good.json` is ignored outside role `data`.

## 13. Integration points

Each consumer keeps its own runtime objects. The control plane's job ends when `LiveConfig` swaps.

### 13.1 Proxy and routing

`Routing` inside `ConfigDocument` is the section from [proxy routing](proxy-routing.md) (`RouteSpec`, `Upstream`). `ConfigSnapshot.compile` already calls `RoutingCompiler` when `routing.enabled`. A pushed document takes that same path. The control plane does not construct a `Router` and does not open upstream connections. With `routing.enabled: false` the compiled handler remains the static file handler, as on master.

### 13.2 Filter chain

`List<MiddlewareSpec>` is stored as the document already stores it (`name`, `order`, `route`, `options`). Compile behavior is unchanged: an empty list builds no chain; names are not resolved inside the control plane. A data node that has factories bound applies them when it compiles, on the applicator thread, before `publish`. A document that names an unknown factory fails that compile and leaves the previous snapshot.

### 13.3 Access rules

`AccessSettings` travels in the document and is applied by `StaticFileHandler` at compile time, which is the rule `ConfigSnapshot.compile` already implements for the command-line access settings. The YAML `access:` section stays owned by config. The control plane does not evaluate a request against the rules.

### 13.4 Config and ACME

Local reload remains `ReloadHook` → `ConfigReloader.reload`. A remote apply is `SjwsServer.applyRemote`: signature and rollback checks, then `ConfigReloader.apply`, then the same swap, all under `reloadLock` (§5.5, §5.6). It does not call `ReloadHook.run()`. On role `data`, SIGHUP does not replace CP-owned sections. ACME issuance is leader-only and waits on `leaderReady(term, appliedIndex)` (§5.7, §6). Certificate bytes stay out of canonical YAML. Heartbeats carry `canonicalHash` and `version`, not PEM, passwords, or the process-local generation. Rotation accepts the previous CA and the new CA for one activated version, then drops the previous CA. Challenge responses on a data node still use the in-memory `ChallengeBoard` that `ConfigSnapshot.compile` already wraps in front of the handler when HTTP-01 is on the main listener. Tokens are placed by the leader's publish of the challenge reference inside the snapshot the data node applies, before the leader tells the ACME directory to validate. That ordering is the same as today's local ACME flow (token on the board before `trigger`), moved to the leader and the watch.

### 13.5 Messaging topics

The document gains a `topics:` list: name, partition count, a local ledger mode, and a cluster ack. Local modes are the names `LedgerWriter` already uses: `async` (the default), `group`, `each`, and `none`. The cluster ack uses the HA vocabulary from [high availability](../ha/design.md): `relaxed` is the default and means majority in memory (`Durability.RELAXED`); `strict` is opt-in and means quorum commit plus fsync (`Durability.STRICT`). The control plane stores that list inside the canonical YAML and projects it to `broker` and labeled `edge` nodes. `cp-config` itself is opened with the same default: majority in memory, quorum-plus-fsync only when the group options say so.

The broker reads the list from `AppliedConfig.document()` after apply and opens or closes `Partition`s on its writer threads. `sjws_nettask.cp` does not call `Partition.open`, does not touch the ledger, and does not append to a partition group. `sjws_nettask.messaging` does not call `ControlLeadership` or the watch. A control-role process starts with no partition directory.

Desired topic state and the message log are different Raft groups. Losing the control plane does not truncate a partition log. The broker keeps the partitions it already opened from last-good.

## 14. Admin API and the TUI

The control role binds a dedicated admin address. The content port does not serve these routes.

| Method | Path | Role | Body |
|---|---|---|---|
| `GET` | `/cp/v1/health` | `view` | leader, term, `leaderReady`, voter count |
| `GET` | `/cp/v1/config` | `view` | activated metadata and canonical YAML. Sealed blobs appear as ids and hashes. |
| `GET` | `/cp/v1/config/diff?from=&to=` | `view` | `ConfigDiff.between` for the two retained versions: section, `LIVE` or `RESTART`, field names. Not a patch. Sealed blobs are omitted. |
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

### 15.1 Harness seams

The suite that will run the four outage scenarios is already in the tree: `tests/sjws_nettask/clusterharness`. `ClusterHarness.open(controlNodes, dataNodes, root, role, clock)` starts data nodes the way `FileServingEndToEndTest` does (`SjwsServer` on port 0). Control nodes go through `ControlPlane`. `PendingControlPlane` refuses every call until `sjws_nettask.cp` implements that interface. The implementation reads `ClusterClock` for election, heartbeat, suspect, dead, watch retry, max-staleness, and `delay`. `GroupOptions` durations are arguments of the test cluster, not only the production defaults. The four scenarios stay `@Disabled` until `ControlPlane.available()` is true. Enabling one is removing `@Disabled` and pointing `ClusterHarness.newControlPlane()` at the real factory.

The in-JVM observation does not mint a `view` certificate. `ControlPlane` is the direct read: `leader()`, `ControlNode.leaderReady()`, `ControlNode.adminPort()`, `ControlNode.term()`, `push`, `rollback`, `contentHash(version)` (that value is `canonicalHash`), `partitionDataFromControl`, `heal`, `killLeader`, `delay`, and `awaitConverged`. A follower's leader address is data on that interface, not a log line. Admin and watch listeners bind `127.0.0.1:0` and return the bound port from `start`. A subprocess prints one line, `content=<port> admin=<port> watch=<port>`. Bootstrap returns the CA fingerprint and the join token to the caller. A test does not scrape stdout for either value.

While the watch is down, the oracle is the data node, not the admin API. `DataNode.version()` and `DataNode.contentHash()` read `AppliedConfig` (`bundleVersion` and `canonicalHash`). Role `data` also exposes a loopback status with version, epoch, `canonicalHash`, and whether the watch is connected. Role `standalone` does not register that route. A standalone snapshot reports version `0`.

`awaitConverged(version, timeout)` is the barrier: every data node in the open stage reports that `AppliedConfig.version()`. The deadline is time spent in the method. Election and delay move only when the clock advances. A node that missed an earlier canary stage stays on the previous version until its stage opens. History depth defaults to 8 and is settable on the test cluster so a rollback target is still present.

| Scenario | Hook |
|---|---|
| Network partition | `partitionDataFromControl` isolates every data node from every voter. `heal` restores the watch. This is separate from the minority-voter cut. |
| Leader kill | `killLeader` stops one in-process control node. One `ClusterClock.advance` of an election timeout makes a survivor `leaderReady` on its own admin port. `kill -9` stays the chaos row. |
| Slow node | `delay(dataNodeId, duration)` is one-way on the watch. The node still acks, only later. Other nodes' windows stay independent. Content, including the slow node's, keeps answering. |
| Config rollback | `rollback(toVersion)` appends `Rollback` and returns the new, higher version. Its `canonicalHash` equals `contentHash(toVersion)`. |

`applyRemote` does not close the content listener. A row samples that port concurrently with `Activate` and expects no non-2xx and no connection failure.

| Case | Assertion |
|---|---|
| Signature | A flipped byte, an unknown `keyId`, or a bad `canonicalHash` is rejected inside `applyRemote`. The installed snapshot is unchanged. |
| Unsigned field | Changing `parentVersion`, `kind`, or `schema` without re-signing is rejected. `kind` other than 1, or a non-zero `parentVersion`, is rejected. There is no patch to apply. |
| Version jump | `version - lastApplied` above 2^20, or a version within 2^20 of `Long.MAX_VALUE`, is rejected. |
| Expiry | An envelope past `expiresAtMillis`, or a node with no freshness frame past that expiry, is stale. Serving stays on last-good. |
| Anti-replay | An envelope at or below the floor is rejected. `epoch` below the highest applied epoch is rejected. No nonce set is required. |
| Wiped `stateDir` | With the pin floor set, a pre-floor envelope is rejected and the listener answers 503. Last-good is not compiled. |
| `operate` RBAC | A `Propose` that contains `rbac` or `trust` is rejected. The `Rbac` entry is unchanged. |
| Secret path | A reference outside the secrets directory, including `..` and a symlink escape, is rejected. The error has no file bytes. |
| Learner revoke | A learner watch server drops a revoked node on the next handshake and on apply of `Revoke`. |
| Token replay | After a leader change, the same join token is rejected. The certificate was returned only after `ConsumeToken` committed. |
| Role claim | `Register` claiming a role other than the token's role is rejected. The projection follows the certificate. |
| `Noop` | `leaderReady()` is false after the election callback and true only after the `Noop` is applied. ACME's directory client is not invoked before that. |
| Minority | With 3 voters and one link cut, the minority cannot `Activate`. |
| Leader loss mid-rollout | The new leader's `Noop` commits, then push resumes. A version already acked is not applied twice. |
| Watch drop | The data node keeps serving. Reconnect with `fromVersion` receives only newer versions. |
| Backpressure | A client that does not ack does not grow the server send queue past `maxInFlight`. Raft commit of a later version still completes. |
| Canary | A node whose labels miss stage 1 does not observe the new version until its stage opens. |
| Rollback | `toVersion` produces a higher version with the old `canonicalHash`. `applyRemote` checks that hash against the retained version and fails closed on a mismatch. Nodes that already hold the hash ack without a second swap. |
| Apply failure | `compile` throws. `LiveConfig` still returns the previous handler. |
| CP down at boot | Role `data` serves `<stateDir>/config/last-good.json` after the signature check. Role `standalone` does not read that file. |
| CP down at runtime | Requests succeed against the installed snapshot. No request thread blocks on the watch. |
| Projection | A `broker` envelope fails verification on a `gateway` node id and contains no sealed blob for the gateway key. |
| Join token | The second presentation of the same token fails. A presentation past TTL fails. The CSR is not signed before the token check. |
| Revoke | The node receives no version committed after `Revoke`. Secrets wrapped to it are rotated. |
| Chain swap | With HA and caller filters enabled, a pushed routing change is observed on HTTP/1, HTTP/2, and HTTP/3. The accept loop stays open. No non-2xx and no connection failure on the content port during `publish`. |
| One publisher | A remote apply and a SIGHUP overlap. One generation is assigned. Both run the ACME listener, reaper timeout, and `replaceTls` side effects. Role `data` rejects a SIGHUP that changes a CP-owned section. |
| Class load | `standalone` under `-Xlog:class+load` (or a `jdk.ClassLoad` JFR event) loads no `sjws_nettask.cp.*` and no `sjws_nettask.cluster.*` class. No admin port, no `stateDir`, no crypto provider init for the control plane. |
| Dependency cut | `cp` class files have no constant-pool reference to `sjws_nettask/messaging`. `config` sources do not reference `cp`. `SjwsServer.applyRemote` is the one composition-root method that names `SignedSnapshot`, because the checks run inside it; `standalone` does not call it. `cluster` sources reference neither `cp` nor `messaging`. |
| Limits | A frame over the cap, and a YAML alias bomb, are rejected before compile. |
| Redaction | A scan of logs, metrics, and admin JSON finds no token or sealed-blob bytes. `toString` of a `Secret` is `***`. |
| Standalone hot path | No `cp-` thread. With no filters, the handler is the static file handler. |
| Last-good mode | The file is `0600` on a POSIX filesystem. A crash between compile and rename leaves the previous file in place. |

Chaos that stays scripted, not a required CI step: `kill -9` of a leader, pause, asymmetric partition. The sim-transport rows cover the partition assertion the unit suite can make deterministic.

## 16. Benchmark gate

Every PR in this workstream reports `wrk` and `wrk2` on the same host, before and after, with the control plane off (`standalone`).

```
wrk  -t4 -c128 -d30s --latency http://127.0.0.1:<port>/index.html
wrk2 -t4 -c128 -d30s -R50000 -L http://127.0.0.1:<port>/index.html
```

1 KiB file, loopback, four threads, 128 connections. `wrk2 -R` stays well below saturation. Implementation PRs run at least five alternating before/after pairs. The PR body reports the median and the spread (min and max) of req/s and of p50/p90/p99/p99.9, plus the JDK, the commit, and the machine. `wrk` and the server are pinned with `taskset` to disjoint CPUs.

A new read on the request path is caught by the class-load test and by a `SingleNodeHotPathTest`-style assertion (§15), not by wrk. wrk cannot see one extra volatile read. The numbers still go in the PR body so a real throughput shift is visible. Shared-VM noise of the kind HA already recorded is the comparison band when the runs are not pinned. Pinned runs should sit tighter than that.

Implementation PRs also record a column for "control plane running, data node serving last-good, watch connected" and, when the change touches handler wiring, a column with HA and caller filters enabled. Those columns show the watch thread and the chain supplier are off the accept path. They are not a license to add a per-request call.

This design PR changes no `.java` file. Its before/after pair is noise on identical classes. The repeated pinned method above is the gate for the implementation PRs. The numbers already measured for this document are in the PR body.

## 17. Implementation plan

Each PR is based on current master (this document's base or newer), merges the HA stack when it needs `ClusterNode`, keeps `mvn verify` green, does not lower the 0.96 root branch floor, and does not merge itself.

1. **Design.** This document.
2. **Store and admin API.** `sjws_nettask.cp`: open `cp-config`, the state machine, `SignedSnapshot`, `ControlLeadership` including the election `Noop`, and `ConfigDiff.between` as a report. `SjwsServer.applyRemote` verifies the signature and the rollback inside the lock and fails closed, then uses `ConfigReloader.apply`. The handler chain reads the live snapshot. Last-good is `<stateDir>/config/last-good.json`. Admin routes in §14 with RBAC kept out of `Propose`. Tests from §15 that do not need a data-node watch, including the class-load test. Benchmark as §16.
3. **Registration and distribution.** Watch client and server, projection, ack window, full snapshots only, `<stateDir>/config/last-good.json`, staged rollout and rollback, label selection. Data role calls `applyRemote`. Implement `ControlPlane` so the four harness scenarios can drop `@Disabled`. Tests for resume, backpressure, canary, partition, and revoke. Benchmark as §16.

The request path in both implementation PRs stays a volatile read of the immutable snapshot.
