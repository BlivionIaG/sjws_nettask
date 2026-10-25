# Swarm peer forwarding (#236): as implemented

Status: **PR 1 of 5** (peer transport, origin-keyed dedup, forwarded produce). No swarm topic family is forwarded
yet: in production the owner side serves nothing (`PeerLink.Owner.NONE`), `SwarmModule` only runs the fail-closed
startup checks and opens no listener, and the #234 stream guard still refuses `sjws.swarm.stream.enabled=true`
when the peer map lists other nodes. The design is issue #236 (r4, option (a)); this page tracks what exists.

## 1. Configuration

| Setting (property / environment) | Meaning |
|---|---|
| `sjws.swarm.node` / `SJWS_SWARM_NODE` | This node's name; it must appear in the peer map. |
| `sjws.swarm.peers` / `SJWS_SWARM_PEERS` | Comma-separated `node=wire@host:port[#san]`. `node` matches `[A-Za-z0-9._:-]{1,64}`; `wire` is 1..65535 and is the authenticated origin id; the pinned SAN defaults to `urn:sjws:node:<node>`. Duplicate names, wires, addresses or SANs are refused, and so is a map that does not list this node. |
| `SJWS_SWARM_PEER_KEY`, `SJWS_SWARM_PEER_KEY_PREVIOUS` or `sjws.swarm.peer.keyFile`, `sjws.swarm.peer.keyFilePrevious` | The frame HMAC key (HKDF info `sjws-swarm-peer-frame-v1`) and its rotation predecessor. Setting a key both in the environment and as a file is refused. A key equal to any HA key (`WEB_SERVER_HA_CLUSTER_KEY[_PREVIOUS]`) or CP Raft key (`SJWS_CP_RAFT_KEY[_PREVIOUS]`) is refused (constant-time compare). |
| `sjws.swarm.peer.tls.cert`, `.key`, `.ca`, optional `.revocations` (env `SJWS_SWARM_PEER_TLS_CERT` / `_KEY` / `_CA` / `_REVOCATIONS`) | mTLS 1.3 material, **mandatory with two or more nodes**. Loaded through Security's `CpTls.peerContext` (key file mode checked, revocations, reloadable). This node's certificate must carry its own pinned SAN. |

Startup is fail closed: when any peer setting is present and a check fails, the swarm module refuses to start
(`OptionalModule.Refused`, message without secrets). With no peer setting no `swarm.peer` class loads, and a
single-node map opens no transport (tested in `swarm.ClassLoadTest`).

## 2. Transport and identity

`PeerLink` reuses `cluster.TcpTransport` in its own frame domain (label `sjws-swarm-peer-frame\0`, its own HKDF
info and frame cap), so a Raft frame and a peer frame never verify under each other's domain even with an equal
key. Frames carry HMAC-SHA256 tags with the existing replay checks (destination, member, epoch, sequence).

The **origin is the certificate, not the header**: on accept, `PeerSockets.peerWire` maps the verified client
certificate's single pinned SAN (URI or DNS) to a wire id, fixed for the connection. A certificate naming no pinned
node, two pinned nodes, or this node is refused at accept. A frame whose header `from` differs from the pinned wire
is a `Reason.ORIGIN` rejection: the connection is closed, `originMismatches` counts it, a WARNING alert is logged,
and the identity is held off with a doubling backoff from 1 s to 60 s (`originHeldOff` counts refused reconnects).
The dialer checks that the server presents the SAN pinned for the address it dialled (`identityRefused`).

## 3. Forwarded produce and dedup

`PeerLink.produce(owner, topic, payload, durability, budgetMs)` returns a future that never completes
exceptionally. Each (owner, topic) is one stream with a random `producerId` and sequence numbers from 1. The
owner applies the write with `Partition.produceFrom(origin = sender's pinned wire, producerId, seq, …)`; dedup is
keyed on **(authenticated origin, producerId)**, so a peer cannot collide with another peer's producers.

| Outcome | Meaning for the caller |
|---|---|
| `APPENDED` / `DUPLICATE` | Applied (exactly once); `offset` is the record's offset. |
| `TOO_OLD` | The sequence fell out of the owner's 4096-entry window: **the maximum retry distance is 4096 writes**, after which the owner can no longer say whether it applied the write. Outcome unknown. |
| `OUT_OF_ORDER` | A gap: the sender rewinds (go-back-N) and resends from the owner's expected sequence. Not surfaced unless the rewind budget runs out. |
| `BUSY` | Local only: the stream already has 256 unanswered writes (`inflightPerProducer`, at most 1024, kept well under the 4096 window so an unapplied write cannot age out while it is retried). Not sent; retryable. An owner-side `BUSY` (per-peer inbound cap 2048, or over 1024 live producers per peer; producers idle 1 h may be evicted) is not surfaced: the sender backs off (10 ms to 1 s, jittered) and resends within the write's budget. |
| `FORBIDDEN` | The owner does not serve the topic, or the record is over the cap (1 MiB default, 4 MiB maximum). Not applied. |
| `FAILED` | The owner failed while applying; a fixed code, never the exception text. Not applied. |
| `TIMEOUT` | **Outcome unknown**: no answer within the budget. The write may or may not have been applied; resending it as a new write can produce a second copy. |
| `UNAVAILABLE` | Not sent: unknown peer, self, link closed, or the owner's breaker is open (5 timeouts, open 1 s). |
| `LOST` | The owner answered with an expected sequence below what it already acknowledged (it restarted without the stream's history). Writes after the acknowledged ones are lost; the next write starts a fresh stream. |

Any failure retires the stream: all writes queued behind it fail with the same outcome and the next write starts a
new stream at sequence 1, so a caller never sees a later write applied after an earlier one failed.

## 4. Ledger format and downgrade

A forwarded write is ledger entry type 8 (`ENQUEUE_FROM`: the type-1 layout preceded by the `u16` origin); local
writes still write type 1 byte for byte. Snapshots stay version 1 unless remote producers exist, then version 2
(appends the remote windows). An older binary refuses both: an unknown entry type fails ledger replay ("unknown
ledger entry type"), and a version-2 snapshot is refused as corrupt, which falls back to an older snapshot or a
full replay that then hits the type-8 entry. **Downgrading a node that has applied a forwarded write therefore
refuses to open that partition** instead of misreading it (Security condition 4).

## 5. Remaining slices

See the PR 1 description for the slicing plan: stream chunk forwarding (PR 2, with the #247 `admitChunk` hook,
optional `credit:u32` and `NO_CREDIT`), `tasks.*` forwarding (PR 3), `reply.*` forwarding (PR 4), clock-offset
pings and the two-process suite (PR 5).
