# Control-plane operations runbook

Operator steps for a hosted control plane (`node.mode: control` / `--role=control`) and the
data nodes that watch it. Service units, bootstrap file format, and drain behaviour:
[control-plane-service.md](control-plane-service.md). Console against `/cp/v1`:
[cp-console.md](cp-console.md). Design: [control-plane.md](../design/control-plane.md),
[control-plane-hosting.md](../design/control-plane-hosting.md).

Document only what master supports today. Items marked **planned** are from the design docs
or README Status and are not shipped operators procedures.

## Bootstrapping a 3-node control plane

Empty `cp.peers` is the **one-voter development** control plane. Production uses a static
voter set of **3 or 5** entries in `cp.peers` (same list and order on every voter; `wire`
defaults to 1-based position).

### Prerequisites (every voter)

- Role resolves to `control` (`--role`, `WEB_SERVER_ROLE`, or `node.mode`) and `--bootstrap`
  points at a `role=control` bootstrap file ([control-plane-service.md](control-plane-service.md#bootstrap)).
- YAML `cp:` block with at least: `nodeId`, `stateDir`, `keyDir`, `rootPub`, `watch.listen`,
  `admin.listen`, and (for multi-voter) `peers` plus TLS + `SJWS_CP_RAFT_KEY`.
- Key / trust layout from [hosting §2](../design/control-plane-hosting.md): `root.pub` outside
  `stateDir`/`keyDir`; `trust.state` anti-rollback floor in both dirs; service uid, modes as
  documented there.
- With `cp.peers`: set `SJWS_CP_RAFT_KEY` (CP-only HMAC; must differ from
  `WEB_SERVER_HA_CLUSTER_KEY` / `ha.cluster_key`). Set `cp.tls.peerCert` / `peerKey` / `peerCa`
  (peer CA must not be the watch `ca`). Each peer entry needs a typed `san`
  (`uri:…` or `dns:…`) matching that voter’s `cp-peer` certificate (#175).
- Watch and admin listeners use `cp.tls` (mTLS). A control role with no `cp` settings exits 64
  before any listener.

### Example shape (illustrative)

```yaml
node:
  mode: control
server:
  bind_content: false
cp:
  nodeId: cp-1
  stateDir: /var/lib/sjws_nettask/control-1
  keyDir: /var/lib/sjws_nettask/control-1/keys
  rootPub: /etc/sjws_nettask/cp/root.pub
  watch:
    listen: 10.0.0.1:9443
  admin:
    listen: 10.0.0.1:9444
  peers:
    - { id: cp-1, address: "10.0.0.1:7101", san: "uri:urn:sjws:node:cp-1" }
    - { id: cp-2, address: "10.0.0.2:7101", san: "uri:urn:sjws:node:cp-2" }
    - { id: cp-3, address: "10.0.0.3:7101", san: "uri:urn:sjws:node:cp-3" }
  tls:
    cert: /etc/sjws_nettask/cp/watch.crt
    key: /etc/sjws_nettask/cp/watch.key
    ca: /etc/sjws_nettask/cp/clients-ca.crt
    peerCert: /etc/sjws_nettask/cp/peer-cp-1.crt
    peerKey: /etc/sjws_nettask/cp/peer-cp-1.key
    peerCa: /etc/sjws_nettask/cp/peer-ca.crt
```

Start each voter (systemd sample: `sjws_nettask-control@.service`). `CpHost.ready()` follows
Raft readiness; `systemctl is-active` only means the process is up — use `/readyz` and
`--status` for readiness. Full key table: [hosting §4](../design/control-plane-hosting.md#4-config-keys).

**Planned:** key ceremony CLI; dynamic membership changes (peers are static for v1).
Voter identity via pinned `cp.peers[].san` (#175) is shipped; see README Status for remaining
control-plane planned items.

## Joining data nodes

1. Install a bootstrap file (`role=data`) with `control-plane=host:watchPort`, `pin=` (root
   public key path), `join-token=` (or enrollment cert paths), `node-id=`, `state-dir=`, and
   optional `data-role=` / labels. Token and pin stay outside `state-dir`; modes
   `0600`/`0400` for the token ([control-plane-service.md](control-plane-service.md#bootstrap)).
2. Enable `sjws_nettask-data@.service` (or equivalent). The process presents the join on the
   watch (`cp.DataWatch`). `--status` shows `connection=pending` and `config-version=0` until
   the first answer.
3. Join tokens are idempotent per node identity for **10 minutes** after use on the control
   plane (`CpStore.presentJoin`). `NodeEnrollment.tokenConsumed()` (writes `state-dir/node.id`,
   deletes the token file) is **not called by any production path yet**, so the token file
   remains and a restart may present it again until that lands.
4. After a signed snapshot applies, the data node persists
   `<stateDir>/config/{floor,trust,last-good}.json` and serves CP-owned sections from
   last-good. Without last-good on first boot, `/readyz` is 503 and the watch retries; there
   is no automatic drain if the control plane is down later.

Issue join material from the admin API / console as documented in
[cp-console.md](cp-console.md) and the design. Capability strings and label selectors are
bootstrap / registration fields — do not invent extra enrollment APIs here.

## Backup and restore

### Data node (shipped)

Back up, with the process stopped or after a successful apply:

- `<stateDir>/config/floor.json`, `trust.json`, `last-good.json` (signed envelope + YAML)
- Bootstrap pin path and any remaining join-token path (secrets)
- Local `--config` YAML (bind address, document root, `stateDir` — not CP-owned sections)

Restore onto a replacement host with the same pin and trust expectations. Boot checks
signature, version floor, and hash before compiling last-good; a bad file is not applied
(fail closed). Details: [design §5.8](../design/control-plane.md).

### Control voters (partial)

Each voter’s `cp.stateDir` holds the Raft log for group `cp-config` and per-voter
`audit.jsonl` (audit is **not** replicated). `cp.keyDir` holds the key ring; `trust.state`
lives in **both** `stateDir` and `keyDir` (higher floor wins).

**As built, `cp-config` does not take or install application Raft snapshots**
(`GroupOptions.NO_AUTO_SNAPSHOT`; no `SnapshotTaker` / `truncateAndCompact` on the live path).
A lagging or replaced replica **replays the full log from index 1**. There is no supported
one-command “backup CP snapshot / restore CP snapshot” operator tool yet.

**Planned:** `SnapshotTaker` / `truncateAndCompact` (#341 / related); treat full log replay
and careful multi-dir restore of `stateDir`+`keyDir`+`root.pub` as the only recovery path
until that ships. Wiping `stateDir` alone without `keyDir`/`trust.state` is unsafe (floor
divergence) — see hosting docs.

## Upgrades

1. Drain / stop one process at a time (`--stop` / unit stop; drain budget default 30 s).
2. Replace the jar / package; start again. `--restart` is stop then daemon start; it does
   **not** transfer Raft leadership and does not mint a new join token.
3. SIGHUP reloads local settings only. On data nodes after a CP push, reload does not replace
   the applied document. **Hosted control-plane key ring and listener TLS are not reloaded by
   SIGHUP yet** — restart the voter to pick up new key or TLS files
   (`CpHost.reload()` exists for in-process reload seams; unit SIGHUP path is documented as
   not covering that yet in control-plane-service.md).
4. Rolling restart across HA content nodes still waits on peer `/readyz` when using
   `ha.RollingRestart`; that is independent of CP voter restarts.

**Planned:** coordinated CP leadership transfer before stop; documented binary compatibility
matrix. Until then, upgrade voters one at a time and keep quorum (majority of 3 or 5).

## Disaster recovery

| Scenario | Shipped behaviour |
|---|---|
| Control plane unreachable; data node has last-good | Keep serving last-good; watch retries; no auto-drain. Past max-staleness, readiness false but process keeps serving ([control-plane-service.md](control-plane-service.md) § decided lifecycle). |
| Control plane unreachable; no last-good | Stay not-ready (`/readyz` 503); retry watch; do not exit. |
| Minority of CP voters down | Remaining majority can still lead / serve admin and watch. |
| Majority of CP voters lost | No leader; admin returns 503 (leader not ready); data nodes keep last-good. |
| Bad or rolled-back config | Use console / admin `POST /cp/v1/rollback` to publish a prior version (new version with old content). Activate / rollout selectors: [cp-console.md](cp-console.md). |
| Root key compromise | **Out of scope** of automated recovery: new `root.pub` on every node (restart), pin via `cp.rootPubSha256` (hosting §3). |

**Planned:** automated CP state snapshot restore; membership rewrite after permanent voter loss;
key ceremony CLI for emergency rotation.

## Related docs

- Units and bootstrap: [control-plane-service.md](control-plane-service.md)
- Operator TUI: [cp-console.md](cp-console.md), [admin-console.md](admin-console.md)
- Design: [control-plane.md](../design/control-plane.md),
  [control-plane-hosting.md](../design/control-plane-hosting.md),
  [control-plane-security.md](../design/control-plane-security.md)
