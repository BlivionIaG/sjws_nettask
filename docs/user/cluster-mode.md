# Cluster mode (HA)

How to run more than one sjws process as a cluster: authenticated heartbeats, optional
messaging replication, and what happens when a node fails. Config keys live under `ha:` in
the YAML file ([config file](config-file.md#ha)); there is no `--cluster` CLI flag. Design
detail: [HA as implemented](../design/high-availability.md) and the draft
[ha/design.md](../ha/design.md).

## What “cluster mode” is

Two related features share the `ha:` block and the cluster key:

1. **HA heartbeats** (`ha.enabled: true` plus a cluster key) — UDP membership / liveness with
   HMAC-SHA256 under a HKDF-derived key (`info` `sjws-ha-heartbeat-v1`). Used by the `ha`
   package (drain, VIP hook, rolling restart) and by cluster membership. Omitted `ha.enabled`
   is false: heartbeats stay off and the authenticator is not loaded.
2. **HA messaging replication** (`ha.messaging: on` or `auto` with a valid member list) — Raft
   over TCP for listed topics (`sjws_nettask.msgrepl`). Frames are HMAC-authenticated (not
   encrypted). Requires `ha.enabled: true`, a member list that names this node, and messaging
   enabled on data nodes (`sjws.messaging.enabled=true`). Default `ha.messaging` is `off`.

A single process with neither feature is ordinary standalone. Turning on heartbeats alone does
not replicate messaging logs.

### Planned (not this guide)

Per the README Status table and [messaging design](../messaging/design.md): a separate
`Broker` API / admin endpoint, a `ReplicatedLedger` on the cluster layer, N-node gossip
(SWIM), multi-site quorum, and GSLB/anycast steering. Do not configure those; they are not
shipped. Produce/fetch today is `/messaging/v1` on the messaging HTTP module.

## Enabling HA heartbeats

```yaml
ha:
  enabled: true
  cluster_key_file: /etc/sjws/cluster.key   # preferred; mode 0600 or 0400, owned by this process
  # cluster_key: "base64:...."              # inline alternative; not together with the file
```

- Key length: at least 16 bytes; 32 random bytes is the useful size (`openssl rand -base64 32`).
- Prefer `ha.cluster_key_file` over inline `ha.cluster_key`. Never pass the key on the CLI
  (`--cluster-key` is refused). Environment override: `WEB_SERVER_HA_CLUSTER_KEY` (and
  `_PREVIOUS` during rotation). The daemon child does not inherit those variables.
- `ha.enabled: true` without a key refuses to start (exit 64).

Full file-mode and parent-directory rules: [config-file.md](config-file.md#ha).

## Enabling messaging replication

Same `ha.enabled` and cluster key, plus a member list shared by every process:

```yaml
ha:
  enabled: true
  cluster_key_file: /etc/sjws/cluster.key
  messaging: on                 # off (default) | on | auto
  node_id: a
  topics: [orders]
  election_timeout: 1s          # default 1s
  heartbeat: 100ms              # default 100ms; 2 * heartbeat * 1.01 + 10ms < election_timeout
  messaging_insecure_plaintext: true   # required while any members[].address is off loopback (#277)
  members:
    - { id: a, address: "10.0.0.1:7100", http_url: "http://10.0.0.1:8080" }
    - { id: b, address: "10.0.0.2:7100", http_url: "http://10.0.0.2:8080" }
    - { id: w, address: "10.0.0.3:7100", witness: true }
```

| Mode | Behaviour |
|---|---|
| `off` | Never replicate (default). |
| `on` | Replicate, or refuse to start (exit 64) without a valid member list / `ha.enabled`. |
| `auto` | Replicate when the member list is valid and `ha.enabled` is true; with no member list stay single-node (silently). |

Valid topologies: **2 data nodes + 1 witness**, or **3+ data nodes**. A bare pair is refused.
Each data node needs `http_url` (307 redirect target when another node leads). The witness must
have messaging **off**; data nodes need messaging **on**. Listed topics must start empty on
every data node (migration notes in
[high-availability.md](../design/high-availability.md#migrating-to-ha-messaging)). Unlisted
topics stay local on each node (no redirect, no 503).

Member links are HMAC-authenticated but not encrypted (#277). Any `members[].address` off
loopback needs `ha.messaging_insecure_plaintext: true` (startup WARN); keep the member network
private. Snapshot / install knobs (`ha.snapshot.*`) are documented in
[config-file.md](config-file.md#ha-messaging-hamessaging).

Changes to members, topics, or messaging mode need a **process restart**; a reload that
changes them reports the note and applies nothing.

## Node roles in a messaging group

| Role | Config | Role in Raft |
|---|---|---|
| Data node | `witness` omitted/false; `http_url` required; messaging on | Voter that stores topic data; may lead; serves `/messaging/v1` and 307s followers to the leader |
| Witness | `witness: true`; messaging off | Votes only; no topic data; helps a 2-data-node pair keep quorum |

`ha.node_id` must be one of `members[].id` on this process.

Process roles `control` / `data` / `standalone` (`node.mode`, `--role`, bootstrap) are separate:
they select control-plane hosting vs content serving. See
[control-plane service](../operations/control-plane-service.md). A messaging cluster can run on
`standalone` or `data` processes; the witness is typically a third process with messaging off.

## When a node fails

**Heartbeats / membership.** A peer that stops sending valid heartbeats is treated as dead after
the configured dead-after window (see [ha/design.md](../ha/design.md)). Rolling restart waits
until every other node’s `/readyz` returns 200 before restarting the next
(`ha.RollingRestart`). Drain and VIP hook behaviour: same design doc and the `ha` package.

**Messaging replication.** The group elects a new leader after `election_timeout` without a
leader heartbeat. Followers that receive produce/fetch for a replicated topic while not leading
answer with **307** to the leader’s `http_url`. If there is no leader (election in progress or
quorum lost), clients see **503** for those topics. Unlisted (local) topics on a surviving node
keep working. Durability (RELAXED vs STRICT), witness vote cursor, and snapshot catch-up:
[high-availability.md](../design/high-availability.md).

**Control plane.** Losing a CP voter minority keeps the remaining voters able to serve admin /
watch; a data node that already applied a signed snapshot keeps serving last-good when the
control plane is unreachable (`/readyz` stays ready). First boot without last-good stays
not-ready (`/readyz` 503) until a snapshot applies. Details:
[control-plane-service.md](../operations/control-plane-service.md).

## Related docs

- Config keys: [config-file.md § HA](config-file.md#ha)
- Env for the cluster key: [command-line.md](command-line.md) (`WEB_SERVER_HA_CLUSTER_KEY`)
- Design: [high-availability.md](../design/high-availability.md), [ha/design.md](../ha/design.md),
  [two-node decision](../design/two-node-ha-decision.md)
- Service units / bootstrap: [control-plane-service.md](../operations/control-plane-service.md)
