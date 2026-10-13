# Control-plane process hosting

Status: draft for Security review. Docs only. Anything not already in master is marked **proposed**.
Builds on `docs/design/control-plane.md`, `docs/design/control-plane-security.md` and the #38 review.

## 1. Starting a control-role process

**Today (master):**
- `NodeRole` is `CONTROL` or `DATA`. `CpStore.resolveRole(cli, env, yaml)` delegates to `NodeLifecycle.role`.
- `ServiceLaunch.onListening` opens `NodeEnrollment` and, only for `NodeRole.DATA`, a `DataWatch`. No production
  path opens `CpStore`, `WatchServer` or `AdminServer`; tests construct them directly.
- `CpStore.open(stateDir, KeyRing, Clock[, adminSan])` runs one Raft group `cp-config` with
  `GroupOptions(RELAXED, 1 MiB, electionTimeout 100 ms, heartbeat 40 ms, ...)` (CpStore.java:114).
  `ready()` = `/readyz` ok AND `leaderReady()`. `bindsContentPort()` is false for the control role.
- `WatchServer` refuses a non-loopback plain listener; sockets come from `openServerSocket(address, AdminTls)`.
  `AdminServer` refuses to bind without cert, key and admin-CA trust.

**Proposed start order** (in `ServiceLaunch`, new `NodeRole.CONTROL` branch, reached by reflection the same way as
the swarm so data-only builds load no CP class):
1. Resolve role and CP config (section 4); validate file permissions (section 3). Any error -> exit non-zero.
2. Load `KeyRing` from disk (section 2).
3. `CpStore.open(stateDir, keys, clock)` with the peer list (multi-voter, below).
4. Bind `WatchServer` (mTLS), then `AdminServer` (mTLS).
5. Report ready only when `CpStore.ready(...)` is true.

**Proposed shutdown order** (reverse): stop accepting admin writes (close `AdminServer`) -> close `WatchServer`
(watchers fail over to another voter) -> `CpStore.close()` (Raft node, log) -> zero private key bytes held in memory.
Shutdown stays within the existing drain budget (`Sjws_nettask.drainBudgetMillis`).

**Multi-voter fit (proposed, my next PR):** `CpStore.open` gains a static peer list (3 or 5 full voters, or
2 data + 1 witness, section 6). Every voter hosts `WatchServer`; non-leaders answer watches from their applied
state, admin writes go to the leader (existing `FailoverCpClient` already retries across addresses).
Peers come from config; membership changes are out of scope for v1.

## 2. KeyRing on disk

**Today:** `KeyRing` is in-memory only: `KeyRing.generate(keyId, notAfter, versionMin, versionMax[, floor])` makes an
Ed25519 root and online key; the root signs `SnapshotCrypto.trustInput(versionFloor, keys)`. There is no load path.
That is what blocks Security.

**Proposed layout** under `cp.keyDir` (dir 0700, owned by the service user):

| File | Content | Mode |
|---|---|---|
| `online.key` | PKCS#8 DER Ed25519 private key of the current online key | 0600 |
| `trust.bundle` | canonical bundle: `versionFloor`, list of `TrustKey(keyId, spki, notAfter, versionMin, versionMax)`, root signature, encoded per `SnapshotCrypto` (big-endian ints, length-prefixed strings) | 0644 or tighter |
| `root.pub` | pinned root SPKI; kept **outside** `stateDir` as `KeyRing`'s javadoc requires | 0644 or tighter |

The root private key is **never** on a CP host.

**Loading (proposed), fail closed:** a new `KeyRing.load(keyDir, rootPub)` that
1. refuses symlinks and loose modes (reuse the `PosixMode` checks used for token files);
2. parses `root.pub` and `trust.bundle`, verifies the root signature over `trustInput`;
3. parses `online.key` and checks its public half equals a `TrustKey` in the bundle that is not past `notAfter`;
4. any parse, signature, expiry or mismatch error throws and the process exits. No fallback, no auto-generate in
   production (`generate` stays for tests and the ceremony tool).

## 3. Online key vs offline root (proposed)

- **Online key**: on every voter, signs snapshots (`KeyRing.sign`). Short-lived (`notAfter`), bounded
  `versionMin..versionMax`.
- **Offline root**: on an air-gapped machine; signs only trust bundles.
- **Rotation ceremony:** (1) on a voter or the offline box, generate a new online pair; (2) carry the public SPKI to
  the offline box; (3) the root signs a new `trust.bundle` containing old + new key, a raised `versionFloor` if a key is
  being retired; (4) copy the bundle and the new `online.key` to every voter; (5) SIGHUP or restart; (6) after all data
  nodes acknowledge the new bundle, issue a bundle without the old key. A CLI (`sjws-cp keys ...`) produces the
  artifacts; format as in section 2.
- **Permissions:** key dir 0700, `online.key` 0600 (0400 accepted), owner must equal the process uid; group/other
  bits on secret files or a writable bundle -> refuse to start. Same rule as `PosixMode.requireOwnerOnly`.
  On non-POSIX filesystems: refuse unless an explicit override is set (open question).

## 4. Config keys (proposed)

Follow `OptionalModules`: JVM property `sjws.cp.*`, env `SJWS_CP_*`, YAML `cp:` block; property > env > YAML.

| Property | Env | YAML | Meaning |
|---|---|---|---|
| `sjws.role` (existing resolution via `NodeLifecycle.role`) | `SJWS_ROLE` | `role` | `control` / `data` |
| `sjws.cp.stateDir` | `SJWS_CP_STATE_DIR` | `cp.stateDir` | Raft log + snapshots |
| `sjws.cp.keyDir` | `SJWS_CP_KEY_DIR` | `cp.keyDir` | section 2 |
| `sjws.cp.rootPub` | `SJWS_CP_ROOT_PUB` | `cp.rootPub` | pinned root key |
| `sjws.cp.nodeId` | `SJWS_CP_NODE_ID` | `cp.nodeId` | this voter |
| — | — | `cp.peers[]` (`id`, `address`, `witness: bool`) | voter set |
| `sjws.cp.watch.listen` | `SJWS_CP_WATCH_LISTEN` | `cp.watch.listen` | WatchServer |
| `sjws.cp.admin.listen` | `SJWS_CP_ADMIN_LISTEN` | `cp.admin.listen` | AdminServer |
| — | — | `cp.tls.{cert,key,ca,adminCa}` | owned by Security |

Exact names of the role key must match the existing `NodeLifecycle.role` inputs; check before implementing.

## 5. Zero cost when the CP role is off

Existing guards: `tests/.../cp/ClassLoadTest`, `CpCoverageTest`, `SourceGuardTest`; `ServiceLaunch` already names
no cp type except behind the role branch (`DataWatch` is typed loosely). Proposed: the control branch is reached by
reflection; extend `cp/ClassLoadTest` to assert a data-role and a no-role start load no `CpStore`, `WatchServer`,
`AdminServer` or `KeyRing`, and `SourceGuardTest` to forbid those names outside `cp/` and the reflective entry.
Benchmark: wrk A/B, CP off, 5 alternating pinned runs.

## 6. Voters and witnesses (HA input)

Source: HA, #118, `docs/design/high-availability.md` (HA's doc), #133, #142, #146.
1. **Witness = vote-only voter**, not a `CpStore` replica. No log; durably records last acked `(index, term)` and
   votes. Joins the CP Raft group as a witness voter (same groupId multiplexing), never serves reads, never leader.
2. **Co-hosting allowed:** a data node may host a CP voter in-process as a separate Raft group over the shared
   cluster transport, as long as the control role does not bind the content port (`CpStore.bindsContentPort()` is
   already false). Failure domains are then shared, so the witness MUST be on a third host or site.
3. **Voter counts:** 2 data + 1 witness (option A) or an odd number of full voters (3/5). A bare 2-voter config is
   rejected at startup (`QuorumPolicy`, #142). Majority is always over all voters. STRICT needs both data nodes
   durable (a witness ack is not a disk copy); RELAXED needs any 2 of 3.
4. **Timing:** lease < election timeout − max clock drift. Pre-vote mandatory. Heartbeats are HMAC-authenticated
   with a boot epoch (#134/#144/#145); the dead interval drives both failover and lower-epoch acceptance.
   Defaults in master: CP group `electionTimeout = Duration.ofMillis(100)`, `heartbeat = Duration.ofMillis(40)`
   (CpStore.java:114); `GroupOptions.leaseNanos()` = `heartbeat * 2` = 80 ms; `LocalCluster.start` default
   `deadAfter = Duration.ofSeconds(5)`; `HeartbeatAuthenticator.WINDOW_MILLIS = 5_000L`. Max clock drift has no
   config value in master (open question).

## 7. Split of work

- **Security:** listener config for `WatchServer`/`AdminServer` (`cp.tls.*`) and SIGHUP `reloadTls()`; node-to-CP
  mTLS (item b) replaces the interim node credential from #141. Reviews this doc.
- **Architecture (me):** control-role hosting in `ServiceLaunch`, `KeyRing.load`, the key ceremony CLI, multi-voter
  `CpStore` with witness support, class-load/SourceGuard tests.
- **HA:** witness voter semantics and timing review.

## 8. Open questions

1. Does SIGHUP also reload `trust.bundle`/`online.key`, or only TLS?
2. Bundle encoding: reuse the `SnapshotCrypto` binary input as-is, or a canonical YAML/JSON wrapper?
3. Non-POSIX filesystems: refuse, or allow with an explicit override?
4. Max clock drift: new config key, or a fixed bound from HA?
5. Do all voters host `AdminServer`, or only redirect writes to the leader?
6. Is static peer config enough for v1, or is joint-consensus membership change needed?
7. Witness: inside `CpStore` or a separate small class to keep witness hosts lean?
