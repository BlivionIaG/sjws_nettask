# Architecture

How the code on `master` is organised. User-visible behaviour (options, limits, caching, ranges) is
documented in the [README](README.md) and the [configuration reference](docs/configuration.md); the
tests that pin each behaviour are listed in [tests/README.md](tests/README.md).

## Layers

| Layer | Package / classes | Responsibility |
|---|---|---|
| Entry / config | `Sjws_nettask` (CLI, exit codes), `ServerConfig` | parse arguments and `WEB_SERVER_*` variables, validate, start |
| Lifecycle | `SjwsServer`, `Transport` (+ `Transport.Factory`), `AltSvc` | build the `StaticFileHandler`, bind TCP, start the optional HTTP/3 transport, Alt-Svc, shutdown |
| HTTP/1.1 connections | `ClientManager` (accept loop, per-IP limit, one virtual thread per connection), `Http1Connection`, `ConnectionReaper` (single timer thread for all deadlines), `ChannelOutput` | socket lifecycle, keep-alive, pipelining, body draining, timeouts |
| HTTP/1.1 wire format | `sjws_nettask.http1`: `Http1Parser`, `ChunkedDecoder`, `Http1Requests`, `Http1Writer`, `ParseError` (+ legacy `HTML`, `Page`, `Header`) | bytes ⇄ `Head`; `Head` → `core.Request`; `core.Response` → bytes |
| HTTP/3 | `sjws_nettask.h3`: `Http3Server`, `Http3RequestHandler`, `Http3Writer`, `Limits`, `RateGuard`, `NoRetryTokenHandler` | Netty QUIC (quiche) ⇄ core types; file slots, fair share, min send rate, handshake caps, GOAWAY |
| TLS | `sjws_nettask.tls`: `TlsMaterial`, `SelfSignedDer` | PEM certificate/key loading (encrypted keys), self-signed certificate |
| Core contract | `sjws_nettask.core`: `Handler`, `Request`, `RequestHeaders`, `Response`, `ResponseHeaders`, `Body` | protocol-neutral request/response |
| Application | `core.StaticFileHandler` + `PathResolver`, `RequestRules`, `SafeOpen`, `FileCache`, `FileMeta`, `ByteRanges`, `EntityTag`, `Validator`, `Mime`, `HttpDate`, `DateClock`, `PerIpLimit` | every status decision for static files |
| Observability | `sjws_nettask.observe`: `RequestObserver`, `ServerMetrics`, `Snapshot`, `AccessLog` | access log, in-process metrics, `/healthz`, optional `/metrics` |

`SourceGuardTest` enforces the boundaries: no Netty in core or HTTP/1, and `h3` does not import HTTP/1.

## Request flow

```
HTTP/1.1: ClientManager → Http1Connection → Http1Parser → Http1Requests.toCore ─┐
                                                                                ├→ Handler.handle(Request) → Response
HTTP/3:   Http3Server → Http3RequestHandler (virtual thread per stream) ───────┘        │
                                                                     Http1Writer / Http3Writer
```

* `core.Handler` (`Response handle(Request)`) is the seam between transports and application logic.
  `StaticFileHandler` implements it; `Http1Connection` depends on `Handler`. Optional request
  filters (`RequestFilters`, [docs/filters.md](docs/filters.md)) wrap that handler. With none
  registered, HTTP/1.1 still calls `StaticFileHandler.handle` directly.
* Both transports call the same handler, so behaviour is identical by construction; a parity test
  compares every HTTP/3 response with the HTTP/1.1 bytes for the same request. `Request` carries
  the client address, whether the connection is TLS, and the raw header map.
* Errors raised before a request reaches the handler (parse errors, 408 deadlines, 431 on HTTP/3) use
  `StaticFileHandler.error(...)`, so their pages match the handler's. Status lines and
  error-page titles use `StaticFileHandler.reasonPhrase` (all RFC 9110 codes plus 429, empty for
  unregistered ones); `Mime` maps extensions to `Content-Type`.

## Current gaps

Still open, each small and without file moves:

1. OPTIONS and 405 are decided inside `StaticFileHandler`. HTTP/1.1 already passes those methods
   through the handler (and therefore through filters, when any are registered).
2. `X-Content-Type-Options` and `Content-Security-Policy` are applied by the file handler, so
   filters see them on the way out. `Date`, `Server`, `Content-Length`, `Connection` and `Alt-Svc`
   are still added by the writers after the chain returns.

## Future direction

The project's goal is a reliable, fast, low-latency message-oriented middleware that is also a web
server. Messaging is still being designed and nothing of it exists in the code yet; the `core.Handler`
seam and the protocol-neutral `Request`/`Response` contract are where such features are expected to
plug in. The target design and migration plan follow below.

## Observability

`observe.ObserveRequestFilter` sits at order 0, outermost among equal orders, and only when observability is on. `SjwsServer` builds it with `RequestFilters.create().add(0, filter)` and copies any caller filters inside it. While the filter is absent a request does not read the clock, does not update a counter, and does not look at the path for `/healthz` or `/metrics`, so a document-root file at `/healthz` is served. `--access-log`, `--metrics` (other than `off`) and `--healthz` install it at startup. The admin TUI's status view calls `SjwsServer.metricsSnapshot()`, which arms counters on the first call (publishing the filter if startup did not) without enabling `/healthz`. HTTP/1.1 reads that published handler on the next request; HTTP/2 and HTTP/3 learn about it through `Transport.armHandler`. Duration is the time until the handler returns, before the socket write. Protocol failures that never enter the chain are not recorded. `ServerMetrics`, `Snapshot` and `AccessLog` stay in `observe`.

Counters are `LongAdder`s, and only after the observer is recording. The access log is a bounded lock-free queue drained by one thread. Neither path blocks a request on I/O.

The latency histogram is a fixed log2 scale (ratio 2, from 1 ms through 16.384 s, then `+Inf`). `ServerMetrics.LATENCY_BOUNDS_NANOS` and `LATENCY_LE_LABELS` are the stable contract: a cluster merges by summing `Snapshot.latency().counts()[i]` across nodes, or by summing Prometheus `sjws_http_request_duration_seconds_bucket` series that share an `le` label. The in-process counts are not cumulative; the exposition is.

---

# Target: message-oriented middleware that is also a web server

Goal (from Blivion): a **reliable, fast, low-latency message-oriented middleware (MOM)** that also serves HTTP. This section is the target design and the plan to get there. Every number below was measured with `bench/` (§B); figures for external systems are linked to their source.

## T0. Principles
1. **One I/O + threading core, many protocols.** HTTP/1.1, HTTP/3 and the messaging protocol(s) are adapters on the same listeners, buffer pool and deadline machinery (today `ClientManager` + `ConnectionReaper`).
2. **The log is the source of truth.** Messages are durable only once they are in an append-only segmented log. Queues, topics, consumer offsets and replication are all views over or copies of that log.
3. **Single writer per partition.** One thread owns each partition's append path, so there are no locks on the hot path. Producers hand records over through a bounded queue, which is also the backpressure signal.
4. **Durability is explicit and configurable** per stream: `none` / `group` (fsync batching) / `replicated` (majority in-memory ack, the **decided cluster default**) / `replicated+fsync` (opt-in quorum + fsync), never implicit.
5. **Behavior-preserving, incremental migration.** The web server keeps working at every phase, and every phase ships with benchmarks.

## T1. Shared I/O and threading core
| Decision | Choice | Why |
|---|---|---|
| Connection model | **Keep blocking NIO + one virtual thread per connection** ([JEP 444](https://openjdk.org/jeps/444)) for HTTP/1.1 and the messaging TCP protocol | Measured: 20k req/s at 1 connection (p50 45 µs) and 205k req/s at 256 connections on 8 vCPU (§B1), with simple sequential code. The earlier design study (`sjws_nettask-core-architecture.md`) found Netty epoll vs NIO within ±3 %, so the syscall layer is not the bottleneck at this scale. |
| Netty | **Only where the protocol needs it** (QUIC/HTTP/3 today). It could become an optional front-end later behind `Transport` | Avoids two threading models in the core. Netty's p99 was worse in the earlier study. |
| Hot partitions | Dedicated **platform thread** per partition writer (`LogBench` shape) and, later, per replication sender | Blocking file I/O on a virtual thread captures its carrier (the JDK compensates by growing the pool). Dedicated platform threads make this explicit and bounded. |
| Buffers | One **pooled direct `ByteBuffer` slab allocator** (power-of-two size classes, per-carrier free lists), shared by HTTP and messaging | Removes the per-connection `allocate` in `Http1Connection.fill`, and the log writer needs direct buffers anyway (`LogBench` uses a 4 MiB direct buffer). |
| Zero-copy | `FileChannel.transferTo` (sendfile) **for both static files and log-segment reads to consumers**, as Kafka does for consumers ([Kafka design: efficiency](https://kafka.apache.org/37/design/design/)) | Already used for large static files. Requires the on-disk record format to equal the wire format (T3). |
| Deadlines | Generalize `ConnectionReaper` timers to messaging sessions (heartbeat/idle, ack timeout) | Reuse, not a second timer wheel. |

## T2. Messaging engine
* **Protocols** (resolved by Messaging Core, PR #17). (1) Our own native binary TCP protocol, length-prefixed frames, with **batched produce and fetch** whose frames match the log record format; (2) **HTTP bindings** through the existing `core.Handler` seam: `POST /topics/{t}` publishes, `GET /topics/{t}?offset=` fetches, SSE or WebSocket streams, so the web server *is* a messaging gateway; (3) AMQP/MQTT come later, as gateways. The Kafka wire protocol will not be implemented.
* **Model.** *Topic* = named stream split into *partitions* (one log each, ordered within a partition). *Queue* = a topic with one shared consumer group, competing consumers and per-message ack/redelivery. Per-message state (deliver/ack/nack/DLQ records) is stored as records in the partition's own log, not in a separate store (PR #17 §2.1). *Subscription / consumer group* = a durable cursor (committed offset) stored in an internal compacted log. This is how Kafka models it ([Kafka design](https://kafka.apache.org/37/design/design/)). NATS JetStream offers the same split (streams + consumers, [docs](https://docs.nats.io/reference/config/jetstream/)).
* **Delivery semantics.** At-most-once (ack before processing), **at-least-once by default** (ack after durable append; redelivery after ack timeout), and effectively-once via **idempotent producers** (producer id + sequence number deduplicated at the partition leader). There is no exactly-once in v1 (PR #17); producer deduplication covers retries.
* **Backpressure.** End to end and credit-based: (a) each partition writer has a bounded queue (`LogBench` uses 65 536 entries), and a full queue makes the producer's send block or get a `retry-after`; (b) consumers grant **credits** (max in-flight messages/bytes), the server never pushes beyond them, and on HTTP the equivalent is long-poll / chunked fetch with `max_bytes`; (c) per-connection write deadlines (`ConnectionReaper`) drop slow consumers instead of buffering without bound.
* **Ordering.** Per partition. Key-hashing picks the partition.

## T3. Storage log
Prototype: `bench/LogBench.java` (bench code only, not server code).
* **Segmented append-only log** per partition: files `<baseOffset:020d>.log` (1 GiB default) plus a sparse `.index` (relative offset → file position, one entry every 4 KiB). Kafka uses the same layout ([Kafka design: log](https://kafka.apache.org/37/design/design/)).
* **Record frame** (prototype): `[len:int][crc32c:int][offset:long][payload]`. Production adds a **format version byte** (PR #17) and a batch header (base offset, count, timestamp, producer id/seq) so that **disk format = wire format** and fetch can use sendfile.
* **Fsync policy** per stream:
  * `none`: write to the page cache; fast but loses data on power failure. Measured **489k rec/s** (8 producers, 256 B), p50 11 µs.
  * `group` (**default for single node**): the writer drains everything queued, does one `write` + one `fsync`, then acks the batch. Measured: 1 producer 1.7k rec/s (≈ the fsync rate), 8 producers 6.9k rec/s, **64 producers 48.6k rec/s at p50 1.1 ms**. Throughput scales with concurrency because each fsync covers more records.
  * `each`: fsync per record. 1.6–1.7k rec/s regardless of producer count, p50 4.2 ms at 8 producers. Only for compliance-style streams.
  * `replicated` (**cluster default, decided by Blivion on 2026-10-12**): `append` is acked once a **majority of the replica set holds the entry in memory**. No follower fsync is required; the leader's and followers' fsync follows the stream's local policy (`group` or the background timer). This matches Kafka's recommended default ([flush.messages](https://kafka.apache.org/43/configuration/topic-configs/), [Hardware and OS](https://kafka.apache.org/43/operations/hardware-and-os/)) and NATS JetStream, which acks on quorum and syncs on a 2-minute timer by default ([replication and R=3](https://docs.nats.io/learn/clustering/replication-and-r3), [sync_interval](https://docs.nats.io/reference/config/jetstream/sync_interval)). Trade-off, documented on purpose: an acked message survives the loss of any minority of nodes, but it can be lost if a majority loses power before its next fsync.
  * `replicated+fsync` (**explicit opt-in, per stream/topic**): the ack waits until a majority has **fsynced** the entry (one `group` commit on each replica in the quorum). This closes the simultaneous-power-loss window, at the cost of about one fsync (≈ 0.5 ms here, §B2) plus a round-trip on every ack. Set in the stream/topic config (e.g. `durability: replicated+fsync`). It is never the implicit default.
  * A later option for `group`: linger N µs to grow batches at low concurrency. The 1-producer result shows a closed loop gets no batching benefit.
* **Recovery.** Scan the last (unsynced) segment, validate CRCs, truncate at the first bad record, rebuild its index. Kafka does the same for segments not known to be fsynced ([Hardware and OS](https://kafka.apache.org/43/operations/hardware-and-os/)).
* **Retention.** Delete whole segments by age or size (cheap: unlink), plus **compaction** (latest value per key) for internal topics (consumer offsets, cluster metadata).
* **Reads.** Tail reads come from the page cache. Lagging consumers use index lookup + sendfile. A cached small-file path like `core.FileCache` is not reused for the log.

## T4. Cluster layer
* **Replication.** Leader per partition. Followers fetch from the leader with the same fetch protocol consumers use (Kafka's model). Commit = **majority of the replica set**; consumers see only committed offsets (high watermark).
* **Metadata, membership, leader election.** A small **Raft** group ([Raft paper](https://raft.github.io/raft.pdf)) holds cluster metadata (members, partition assignment, leaders, epochs). This is the shape of Kafka's KRaft ([KIP-500](https://cwiki.apache.org/confluence/display/KAFKA/KIP-500%3A+Replace+ZooKeeper+with+a+Self-Managed+Metadata+Quorum)) and of NATS JetStream's meta group. No external ZooKeeper/etcd dependency.
* **Failover.** Heartbeats via the Raft leader. On a leader loss the controller picks a new partition leader from the in-sync replicas and bumps the **leader epoch**. Writes carrying a stale epoch are fenced. Clients follow metadata redirects.
* **Dual-node HA.** Two nodes **cannot form a majority after one fails**, so pure 2-node quorum is not split-brain safe. Options: (a) 2 data nodes + a **lightweight witness/arbiter** (votes, stores no data). This is our recommendation. (b) 2 nodes with manual or fencing-based failover (STONITH-style), giving up automatic safety. (c) Async primary/standby, which accepts loss of the unreplicated tail. This must be settled with HA (§A).
* **HTTP in a cluster.** Static content is served by every node. Messaging HTTP endpoints redirect or proxy to the partition leader.

## T5. How the existing code fits
```
                    ┌──────── shared core: listeners, virtual threads, ConnectionReaper, buffer pool ────────┐
TCP :80/:443 ──▶ Http1Connection ─┐                                                                       │
UDP (QUIC)   ──▶ h3 (Netty)      ─┼─▶ core.Request ─▶ Handler chain ─▶ StaticFileHandler                  │
                                  │                       └─▶ MessagingHttpHandler ─┐                       │
TCP :msg     ──▶ MsgConnection ───┴──────────────── produce/fetch ─────────────────▶ Broker ─▶ PartitionLog │
                                                                                     │        (segments)   │
                                                                         Replicator ◀┴▶ Raft metadata ─────┘
```
* `core.Handler` (PR #4) stays the HTTP seam. Messaging over HTTP is just another `Handler` in the chain, so it needs no transport change.
* `Transport` (today the HTTP/3 lifecycle) becomes the generic listener interface. A messaging TCP listener is a new `Transport`.
* `SjwsServer` becomes the composition root: config → log/broker → handler chain → transports.
* Messaging must **not** go through `Request`/`Response` (those are HTTP semantics). The broker API is its own (`produce(batch) → CompletionStage<offsets>`, `fetch(partition, offset, maxBytes)`).

## T6. Phased migration
| Phase | Deliverable | Exit criteria |
|---|---|---|
| 0 (this PR) | Target doc + `bench/` baselines | Numbers reproducible |
| 1 | Widen `ClientManager`/`Transport`/h3 to `Handler`. Introduce the buffer pool (HTTP only) | No regressions in `HttpBench` (±5 %); `mvn verify` green |
| 2 | `storage` package: production `PartitionLog` (segments, index, recovery, retention, fsync policies) with crash tests (kill -9 / truncated tail) | Recovery tests; `LogBench` numbers within 10 % of the prototype |
| 3 | Single-node broker: topics, partitions, consumer groups, offsets log, at-least-once, credits; HTTP bindings via `Handler` | End-to-end produce→consume latency bench; backpressure tests |
| 4 | Native binary protocol + idempotent producers | Batch-produce throughput vs HTTP baseline |
| 5 | Raft metadata + replication + failover; witness-based dual-node HA | Jepsen-style partition/kill tests; no acked-message loss under minority failures in `replicated`, nor under majority power loss in `replicated+fsync` |
| 6 | Optional: Netty or io_uring front-end behind `Transport` if benchmarks justify it | Measured win > 20 % |

## B. Benchmarks (measured)
Run on **b8055fe** (master at the time + this PR's `bench/`; later master merges, e.g. daemon mode, were not re-benchmarked). Machine: shared cloud VM, **8 vCPU Intel Xeon (model not exposed), 15 GiB RAM, Linux 6.12, virtio disk (`vda`, reported rotational), overlay root filesystem**, **OpenJDK 25.0.4.1 (Temurin)**, default JVM flags. Client and server ran on the same host over loopback, so treat the numbers as **relative baselines, not capacity claims**. Reproduce with `bench/README.md`.

### B1. HTTP/1.1 keep-alive closed loop (`HttpBench`, 10 s after a 2 s warm-up, GET of a static file)
| Connections | Body | req/s | p50 | p99 | p99.9 |
|---|---|---|---|---|---|
| 1 | 1 KiB | 20,099 | 45 µs | 114 µs | 649 µs |
| 64 | 1 KiB | 167,455 | 211 µs | 2.99 ms | 7.07 ms |
| 256 | 1 KiB | 204,667 | 816 µs | 6.75 ms | 11.4 ms |
| 64 | 64 KiB | 87,207 | 466 µs | 4.62 ms | 9.54 ms |

(A shorter 5 s run at 64 connections gave 278k req/s, p50 199 µs. Run-to-run variance on this shared VM is significant; compare only runs on the same host.)

### B2. Append-only log write path (`LogBench`, 256 B payloads, closed loop, latency = enqueue → ack)
| Mode | Producers | rec/s | MB/s | fsyncs | p50 | p99 | p99.9 |
|---|---|---|---|---|---|---|---|
| none | 8 | 489,126 | 133.0 | 0 | 11 µs | 63 µs | 373 µs |
| none | 64 | 656,117 | 178.5 | 0 | 67 µs | 486 µs | 3.35 ms |
| each | 1 | 1,618 | 0.4 | 5,000 | 541 µs | 1.90 ms | 4.22 ms |
| each | 8 | 1,737 | 0.5 | 20,000 | 4.20 ms | 10.7 ms | 18.1 ms |
| group | 1 | 1,673 | 0.5 | 20,000 | 506 µs | 2.23 ms | 5.60 ms |
| group | 8 | 6,896 | 1.9 | 99,201 | 970 µs | 3.88 ms | 20.0 ms |
| group | 64 | 48,579 | 13.2 | 12,265 | 1.12 ms | 4.95 ms | 8.31 ms |

**Takeaways.** (1) One fsync on this disk costs about 0.5 ms, which caps per-record-durable writes at about 1.7k/s no matter how many producers there are. (2) Group commit scales almost linearly with concurrency: 64 producers gave 28× the per-record throughput at roughly 2× the p50. Group commit is therefore the default single-node policy. (3) Without fsync the single writer thread sustains about 0.5–0.65 M rec/s, so the write path is not CPU-bound. (4) A durable round-trip (~0.5–1 ms) is larger than the whole HTTP request latency (45 µs). This is why the decided cluster default is the **majority in-memory ack** (T3 `replicated`): it beats local fsync whenever the network RTT is below fsync latency, which phase 5 must measure.

## A. Alignment with sibling workstreams
Updated 2026-10-12, 16:36 Paris time.

### Decided
* **Cluster ack default** (Blivion, 2026-10-12): majority **in-memory** ack (`replicated`), with no follower fsync. Quorum + fsync (`replicated+fsync`) is an explicit per-stream/topic opt-in (T3). This settles the former HA open question "does a replicated ack require a follower fsync?".

### Messaging Core: resolved by PR #17 (`docs/design/messaging.md`)
1. Protocol: **our own binary protocol plus HTTP**. AMQP/MQTT come later as gateways. No Kafka wire protocol.
2. **No exactly-once in v1**; producer deduplication instead.
3. Queues are **a shared consumer group, with per-message state records in the partition log** (no separate store).
4. **`group` fsync is the single-node default.**
5. The on-disk/wire format carries a **format version byte**.

Still to align: as of this update #17 §6 describes quorum + fsync as the default with in-memory as a "relaxed mode". That is the reverse of the decision above, so #17 needs a wording update.

**High Availability: open questions** (the HA design has since appeared in PR #14 and its follow-ups #22, #27, #29, #30. They were not re-reviewed for this update.)
1. Does "dual-node HA" allow a third **witness** process (proposed)? Without one, automatic failover is not split-brain safe.
2. Should Raft be used for metadata only, with ISR-style replication for data (Kafka/KRaft shape), or Raft for data too (NATS JetStream shape)?
3. What are the RPO/RTO targets (e.g. RPO = 0 for acked messages under minority failures in `replicated` mode, RTO < N s)? These drive heartbeat and election timeouts.
4. Should HTTP static serving be active-active on all nodes (proposed), independent of messaging leadership?
