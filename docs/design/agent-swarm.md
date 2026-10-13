# Agent swarm layer (optional module): design

Status: **Draft** · Base: master `79610fd` · Scope: design only. No code changes.
Owner: sjws Architecture. **Co-author: Messaging Core** for §4–§5 (marked below), pending their review.

Every reference to existing code is a path and line on master `79610fd`. This doc contains **no new benchmark numbers**. §13 quotes the messaging baseline already measured in PR #69 and lists pass targets; the swarm's own numbers will come from the runs §13 describes.

## 1. Goal and the hard rule

The swarm layer lets many *agents* (LLM-backed workers, tools and orchestrators) cooperate through sjws:
- they exchange tasks over the messaging ledger;
- they stream tokens to callers;
- they find each other by service name;
- they authenticate with control-plane identities;
- they spend per-agent budgets;
- they call LLM backends through the proxy's resilience features;
- they can be traced end to end.

**HARD RULE: optional and zero cost when off.**
- When `swarm.enabled` is false (the default), no `sjws_nettask.swarm` class is loaded, no thread is started, and no request-path code reads a swarm flag.
- The core hot paths (`Http1Connection`, `h2`, `h3`, `core`, `routing.ProxyHandler`, `messaging.delivery.Partition`) are not modified by this workstream.
- The swarm builds on their public APIs, as the composition root already does for other optional features.

How this is enforced is in §3.3.

## 2. Non-goals
- **An agent runtime or LLM framework.** No prompt templating, no planning, no tool-calling logic in the server. Agents are external processes using the SDK (§11).
- **Hosting models.** sjws proxies to LLM backends (§10). It never runs inference.
- **Exactly-once task execution.** This follows messaging's v1 decision of at-least-once with producer dedup (`docs/messaging/design.md` §3, §10). Tasks must be idempotent or carry an idempotency key (§4.4).
- **A new consensus or storage engine.** Tasks are messages in `messaging.delivery.Partition`; identities and policy live in the CP (`sjws_nettask.cp`, `sjws_nettask.controlplane`).
- **Kafka/AMQP/MQTT compatibility.** Messaging rejected these for v1 (`docs/messaging/design.md` §10.1).
- **Changes to the content request path, or to proxy defaults** (see §10.2 on retries).

## 3. Module boundaries and dependency direction

### 3.1 Packages
| Package | Contents | May depend on |
|---|---|---|
| `sjws_nettask.swarm` (new) | `SwarmModule` (bootstrap), `TaskService`, `LeaseKeeper`, `ReplyRouter`, `TokenStream`, `AgentDirectory`, `BudgetLedger`, `TraceContext`, `LlmGateway` | `core`, `messaging.*`, `controlplane`, `cp` (read-only views), `routing` (`GcraTable`, `Resilience`, `discovery.*`, `WeightedHandler`, `ProxyHandler`), `observe` |
| `sjws_nettask.swarm.wire` (new) | Frame codec for the swarm wire protocol (§11.2) | `core` only |
| `sdk/python/sjws_swarm` (new, outside `src/`) | Thin client (§11.1) | — |

### 3.2 Direction
```
            swarm  ──▶  messaging.delivery / ledger
              │──────▶  controlplane (Rbac, Operator)   cp (signed config, node labels)
              │──────▶  routing (GcraTable, Resilience, discovery, ProxyHandler/WeightedHandler)
              └──────▶  observe, core
  messaging, cp, controlplane, routing, observe, core, http1, h2, h3  ──✗──▶  swarm
```
- `swarm` depends on the others. **Nothing outside `swarm` imports `swarm`**, except the one composition-root hook in §3.3.
- `swarm.wire` depends only on `core`, so the SDK conformance tests can use it without the server.

### 3.3 Enforcement (three mechanical checks plus one benchmark check)
1. **Dependency test**, added next to `SourceGuardTest.coreDoesNotDependOnTransports` (`tests/sjws_nettask/SourceGuardTest.java:59-66`), in the same text-scan style. It asserts:
   - no file under `src/sjws_nettask/{core,http1,h2,h3,routing,messaging,cp,controlplane,cluster,observe,config}` contains `sjws_nettask.swarm`;
   - `swarm.wire` imports only `core`.
2. **Class-load test**, cloned from `tests/sjws_nettask/cp/ClassLoadTest.java:1-45`. That test starts `sjws_nettask.ha.BenchServer --mode=single` under `-Xlog:class+load=info` and asserts that no `sjws_nettask.cp.`, `cluster.` or `service.` class was loaded. The swarm test adds `assertFalse(text.contains("sjws_nettask.swarm."))`, in standalone mode and in `data`/`gateway` mode without `swarm.enabled`.
3. **Single entry point** (shipped in #90). `sjws_nettask.OptionalModules` is the only code outside `swarm` that names the swarm, and it does so only as the string constant `SWARM_CLASS = "sjws_nettask.swarm.SwarmModule"` (`src/sjws_nettask/OptionalModules.java:33-35`).
   - When the JVM property `-Dsjws.swarm.enabled=true` is set, or the environment variable `SJWS_SWARM_ENABLED=true` (the property wins), it loads `SwarmModule` by reflection, `Class.forName(...).asSubclass(OptionalModule.class)` (`OptionalModules.java:52-65`).
   - Otherwise it returns a shared no-op `OptionalModules.NONE`, so no swarm class is loaded or verified.
   - Both startup paths call it once, after the server starts and outside any request path: `try (var modules = OptionalModules.start(err))` in `Sjws_nettask.serve` (`src/sjws_nettask/Sjws_nettask.java:95,127`).
   - The YAML key `swarm.enabled` replaces the property/env gate in S1 (§12).
   - No existing package used this pattern before #90: `service/Bootstrap.java:43` is only the `"messaging"` capability string, not a gate.
4. **Off-mode wrk gate** (§13, item 3). It is the backstop, not the proof. wrk cannot resolve a single extra volatile read; tests 1–3 can.

## 4. Task model on messaging (Co-author: Messaging Core; their notes integrated 2026-10-13, pending their review of this text)

This section is grounded in what is merged: #21 ledger, #33 delivery, #69 snapshots/retention, #37 stats, #39 config schema (doc only). Items marked **GAP** do not exist on master.

### 4.0 What exists today (single node)
- A "topic" is **one `Partition`** (`messaging/delivery/Partition.java:52`). Multi-partition topics with key hashing are a **GAP**.
- There are no message headers or keys. The payload is opaque `byte[]` (`Delivery.java:4-5`, `LedgerEntry.Enqueue` at `LedgerEntry.java:16`), so all swarm metadata rides in a payload envelope until headers exist (**GAP: headers**).
- There is no broker network protocol and no replication of partitions (**GAP**). The swarm is therefore **single-node** for messaging until those land (§12).
- Stats: `MessagingStats.snapshot()` is lock-free and pull-based, and `MessagingStats.NONE` is used when messaging is off (`messaging/stats/MessagingStats.java:8-16`).

### 4.1 Mapping onto existing primitives
| Swarm concept | Existing primitive (master) |
|---|---|
| Task queue for a service | Topic `tasks.<pool>` (the pool is the service name from §7). The worker pool is a consumer group; a worker is the `consumer` id in `poll`. Competing consumers are built in: each message is leased to one consumer of the group. |
| Submit | `Partition.produce(producerId, seq, payload[, durability])` . Dedup on `(producerId, seq)` with a window of 4096 per producer (`DeliveryState.java:40`), durable across restarts via snapshots. |
| Claim + lease | `Partition.poll(group, consumer, max, visibilityMs)` . It records `LedgerEntry.Deliver(group, offset, consumer, deadlineMs, attempt)` (`LedgerEntry.java:27`). On expiry the message is redelivered on the next poll with `attempt + 1`. |
| Complete | `Partition.ack(group, offset[, durability])`  |
| Transient failure (rate limit, tool timeout) | `Partition.nack(group, offset, retryAfterMs)` : `notBefore = now + max(delay, backoff)`. Backoff is `RetryPolicy.backoffMs`, `base << (attempt-1)` capped at `backoffMaxMs`. The default is `RetryPolicy(5, 100, 30_000)` (`RetryPolicy.java:7-20`). |
| Deterministic failure (bad input) | `Partition.reject(group, offset, reason)`  sends the message straight to the DLQ. |
| Worker crash | Lease expiry, which counts as an attempt. |
| Exhausted | At `maxAttempts` the message moves to `<topic>.dlq` (`Partition.deadLetterQueue()`, `Partition`) with origin group, offset, attempts and reason, e.g. `lease-expired:max-attempts` / `nacked:max-attempts` . |
| State | `MessageState { ENQUEUED, IN_FLIGHT, ACKED, DEAD_LETTERED }` (`MessageState.java:4`) |

The retry policy is set per topic through the `retry:` block of the #39 schema (`docs/messaging/config.md`). That schema is not parsed by code yet (**GAP**).

### 4.2 Heartbeat = lease extension (Messaging Core, #91 in review)
- `extend(group, offset, attempt, visibilityMs) → LeaseResult`.
- Batched form: `extend(group, long[] offsets, int[] attempts, visibilityMs) → List<LeaseResult>`. The attempts travel with the offsets, because a bare list of offsets cannot be fenced.
- Ledger record: `Extend(group, deadlineMs, offsets[], attempts[])`, **record type 7**. One record is written per call and covers the accepted leases only; replay sets the lease deadlines. A build older than #91 refuses a log that contains it.
- `LeaseResult` is `{OK, NOT_IN_FLIGHT, STALE_ATTEMPT}`, the same result the fenced settles return (§4.3).
- Cost: one small append per heartbeat call. Heartbeat at ≤ visibility/3. The #91 bench shows batched heartbeats cost −1.4 % (within noise) and unbatched about −9 %, so **`LeaseKeeper` must use the batched form**.
- Until #91 merges, the swarm uses fixed leases only.

### 4.3 Fencing (Messaging Core, **#87 merged**)
- Before #87, `ack`/`nack`/`reject` took `(group, offset)` only, so a stale worker whose lease had expired and been re-leased could settle the *new* attempt.
- #87 adds fenced forms that carry the `attempt` from `Delivery` as a lease token and return `LeaseResult {OK, NOT_IN_FLIGHT, STALE_ATTEMPT}`.
- The old unfenced overloads still match any attempt. They are **deprecated now** (`@Deprecated(forRemoval = true)`, pointing at the fenced forms, in a small Messaging Core follow-up) and are **removed after one release**.
- **The swarm SDK and wire protocol expose only fenced settles.** The SDK passes the attempt automatically.

### 4.4 Results, idempotency, exactly-once
- **Order:** the worker writes the reply with `Durability.ASYNC` via `produce(…, durability)` and **acks the task only when the reply's durability future completes**. A crash before that redelivers the task, and the caller deduplicates by `taskId`. Results are not lossy. Once partitions replicate, the reply uses the decided majority in-memory ack (`strict` per topic is available), and the task ack follows the reply's replicated ack.
- **Producer side** (implemented): `(producerId, seq)` dedup. Agents persist `producerId` and a monotonic `seq`, or derive `seq` deterministically (e.g. the step number in a plan), so a publish retried after a crash is dropped.
- **Consumer side** (application): delivery is at-least-once, so side effects are keyed by `taskId` (plus `attempt` for fencing). Tools that call external APIs pass `taskId` as their idempotency key (also §10.2).
- Exactly-once consume-transform-produce would need a cross-partition transaction (**GAP**). It is not proposed for v1.

### 4.5 Task envelope (in the payload until headers exist)
`{version:u8, taskId:16B (idempotency key), correlationId:16B, replyTo:topic, deadlineMs:absolute (caller clock), traceparent, tracestate?, budgetRef, schema/contentType, body}`.

When headers land (**GAP 3** in §4.8), the metadata fields move to headers and the `version` byte selects the layout.

### 4.6 Further gaps the swarm relies on
- **Deterministic jitter:** ±25 % derived from `(offset, attempt)`, so replay stays exact.
- **DLQ redrive:** `redrive(dlqOffset)` re-enqueues into the origin topic with a fresh attempt budget and keeps the origin fields for audit. The TUI and admin API expose it behind `Rbac` (§6).
- **Broker-side TTL:** an `expireAt` field on `Enqueue` makes `poll` skip and count dead work instead of spending a lease on it.
- **Per-key ordering:** a single partition delivers in offset order, but with N concurrent leases tasks run in parallel. Strict per-key order needs `max in-flight per key = 1`.

### 4.7 Stats
- `MessagingStats` already exposes, per group: lag, inFlight, acked, deadLettered, redeliveries, dlqDepth and oldestPendingAgeMs. That covers pool dashboards: queue depth, stuck work, retry storms.
- Per-task detail (attempt history, current worker, lease deadline) comes from `LedgerInspector` pages (`messaging/delivery/LedgerInspector.java:16-27`) for the TUI drill-down, not from the stats provider.
- **GAP:** per-consumer counters (in-flight per worker, extends, last heartbeat age) in `DeliveryCounts`, plus fixed-bucket latency histograms (enqueue→first delivery, delivery→ack) updated on the partition thread.

### 4.8 Gap order (Messaging Core)
1. Attempt-fenced ack/nack/reject (PR #87, **merged**); deprecate the unfenced forms now and remove them after one release.
2. `extend`, with the `Extend` record (type 7) and the fenced batched form (PR #91, in review).
3. Message headers in `Enqueue`.
4. Broker-side TTL / `expireAt`.
5. Multi-partition topics with key hashing; max in-flight per key.
6. DLQ redrive; deterministic jitter.
7. Producer backpressure signal; per-consumer stats and latency histograms.
8. Producer-id expiry (needed before long-lived agents churn producer ids).
9. Network protocol and replication (per #17 / HA). These are required before agents run on more than one node.

## 5. Request/reply and token streaming (Co-author: Messaging Core; their notes integrated, pending their review of this text)

### 5.1 Request/reply
- Reply topics are **one per requesting node or process**: `reply.<nodeId|processId>`, demultiplexed in memory by `correlationId` (`correlationId → future`). Each `Partition` owns writer, syncer and snapshotter threads, doubled with its `.dlq`, so a topic per agent would not scale.
- Reply topics are created **without a DLQ**.
- They are **closed after 10 minutes idle** (configurable; idle means lag 0 and no polls). The files are kept, the topic reopens on demand, and it is deleted after retention plus a grace period.
- A Messaging follow-up adds shared writer/syncer executors.
- **Deadlines:**
  - A worker rejects a request whose `deadlineMs` passed before it started (`reject("deadline")`, visible in the DLQ).
  - The worker sets `visibilityMs ≤ deadlineMs − now`.
  - The requester times out locally and answers the HTTP caller with a 504-class error. A late reply is acked and discarded.
  - Broker-side TTL is §4.6.
- **Durability:** replies are written with `Durability.ASYNC` (`messaging/ledger/Durability.java`), and the task is acked only after the reply is durable (§4.4). `NONE` is not used for replies: a crash after the ack would lose both the reply and the task.

### 5.2 Token streaming
There are two delivery legs. Messaging Core specifies the ledger leg; the HTTP leg is Architecture's.

- **Ledger leg (Messaging Core).**
  - One stream maps to one partition key, `streamId`, and all chunks of a stream land on one partition, so they are ordered by offset. Until multi-partition topics exist, a stream topic is sharded as one `Partition` per shard.
  - **Chunk framing** in the payload envelope: `streamId`, `chunkSeq` (u32, starting at 0), `flags` (`FIRST`, `LAST`, `ERROR`, `CANCELLED`) and `bytes`.
  - Chunks are produced with the **agent's own `producerId`/`seq`**, not a per-stream producer id. Producer dedup state (≤ 4096 seqs per producerId) is never evicted, so per-stream ids would leak forever; producer-id expiry is recorded as a messaging gap (§4.8). **Consumers drop duplicates by `(streamId, chunkSeq)`**, which they already track in order to reorder.
  - **Payload size:** at most 1 MiB inline per message by default, configurable per topic. The hard limit is 64 MiB (`RecordBatch.MAX_FRAME_BYTES`). Larger bodies go by reference to a **blob store owned by the swarm** (or a storage module), not by messaging, which stays opaque bytes plus ordering and retention.
  - **Coalescing:** a chunk closes at 1–4 KiB or 20 ms, whichever comes first. Per-token messages would cost about one append per token.
  - **Consumer:** one consumer per stream, reordering by `chunkSeq`. Gaps after a lease expiry are filled by redelivery. Max in-flight per key = 1 is the later alternative (§4.6).
  - **Durability:** chunks use `NONE`/`ASYNC`. The final `LAST` chunk may use `GROUP` when the caller needs it durable. Per-append durability already exists .
- **HTTP leg (Architecture).**
  - The gateway node that holds the caller's session consumes the stream and writes it as HTTP/1.1 chunked (`http1/Http1Writer.java:236-251`), as `text/event-stream` ([WHATWG HTML, Server-sent events](https://html.spec.whatwg.org/multipage/server-sent-events.html)), or as h2/h3 DATA frames.
  - The body is a `core.Body.Stream`, which already pulls one chunk at a time (`routing/ProxyHandler.java:55-58`).
  - A reconnecting caller resumes from its last `chunkSeq`.
- **Backpressure.**
  - The consumer is pull-based already (`poll(max)` bounds its own buffer), and the ledger writer queue is bounded.
  - **GAP:** a producer-side signal, either per-group lag from `MessagingStats` or a `produce` that fails or blocks when lag exceeds a limit. Until then, the SDK's agent-side `stream()` throttles on the `CREDIT` frames of §11.2.
- **Cancellation.**
  - The caller sends `cancel(correlationId)` on the request's reply/control topic.
  - The producer stops and emits a `LAST|CANCELLED` chunk.
  - Consumers ack the pending chunks of a cancelled stream without processing them, and retention (#69) reclaims them once acked.

**DECIDED (2026-10-13, inceptor): streaming path.**
- The **ledger is the default** for tasks and results.
- The **direct path** (agent → HTTP gateway over h2/h3: no ledger write, no resume, no replay) is allowed **only for ephemeral token streams to HTTP-gateway clients** (HTTP LLM-gateway responses). It is **opt-in per request or per route** (`stream: direct` in the request; the SDK's `stream(..., direct=True)`). Messaging Core accepts it under these conditions (sign-off on #90):
  - It is documented as **lossy and non-resumable**: a failure ends the stream with an `ERROR` chunk, and there is no replay.
  - It uses the same chunk framing (`streamId`, `chunkSeq`, `flags`, `bytes`) and the same `TOKEN`/`CANCEL`/`CREDIT` frames as §11.2, so clients have one decoder.
  - It lives outside `sjws_nettask.messaging`, so the §3.3 guards hold.
- Even on the direct path, the **final result is always written to the ledger**, as a `LAST` result message on `reply.<nodeId|processId>`, followed by the fenced task ack (§4.3/§4.4). Only intermediate tokens skip the ledger.
- A direct stream that breaks ends with an `ERROR` chunk and is not resumable. The caller falls back to the ledger result.

## 6. Agent identity and RBAC
- **Identity. DECIDED (2026-10-13, inceptor):** agent certificates come from a **separate intermediate CA** under the CP trust root (`cp/KeyRing.java`, `cp/TrustKey.java`), not from the node enrollment CA (`service/NodeEnrollment.java`, `service/JoinMaterial.java`). Agent certificates are short-lived and numerous, and they are revoked separately from node certificates. Revoking an agent never touches node trust, and a compromised agent CA can be rotated without re-enrolling nodes. Every agent connection is mTLS. The agent id is the certificate subject, which `controlplane.Rbac.ClientCert(subject)` already models (`controlplane/Rbac.java:50`).
- **Authorization.** `Rbac.authenticate(Credential)` and `Rbac.authorize(credential, permission, target, version)` (`Rbac.java:91,118`) are reused unchanged. Today's `Permission` set is operator-oriented (`READ, EDIT_ROUTES, ROLLOUT, ROLLBACK, NODE_DRAIN, …`, `controlplane/Permission.java:22-30`), and `Role` is `VIEWER/OPERATOR/ADMIN` (`Role.java:24-27`).

  **DECIDED (2026-10-13, inceptor):** agent permissions (`TASK_SUBMIT(service)`, `TASK_CLAIM(service)`, `LLM_CALL(backend)`) live in a **separate `swarm.AgentPolicy` CP document**, distributed like `controlplane.AccessPolicyDocument`.
  - **Core RBAC is untouched:** `Permission`, `Role` and `Rbac` don't change.
  - **It costs nothing when the swarm is off:** no `AgentPolicy` is parsed or held, and no `swarm` class is loaded (§3.3).
  - The swarm evaluates `AgentPolicy` itself. It may reuse `Rbac.authenticate` for the certificate subject, but never adds permissions to it.
- **Placement.** The node role is the existing `DataPlaneRole { GATEWAY, STATIC, BROKER, EDGE }` (`service/DataPlaneRole.java`). The swarm adds no role; it uses **labels** (`swarm=agent-host`, `swarm=llm-gateway`) from the node's enrollment (`service/NodeEnrollment.java:79`). Task partitions live on `BROKER` nodes, and LLM gateway routes on `GATEWAY`/`EDGE` nodes.
- **Audit.** Agent mutations (submit, claim, cancel, budget changes) go to the hash-chained `controlplane.AuditLog` that `Rbac.complete` already writes (`Rbac.java:139`).

## 7. Discovery by service name
Agents register under a **service name**, e.g. `summarize.v1`. The swarm reuses the routing discovery seam from PR #84:
- `routing.discovery.UpstreamSource` (`UpstreamSource.java:36`) and `UpstreamRegistry.lookup(service)` (`UpstreamRegistry.java:41`) return an `UpstreamSet(service, version, Endpoint[] ready, LbPolicy policy)` (`UpstreamSet.java:31`).
- `swarm.AgentDirectory` implements `UpstreamSource` for **push-style** agents (agents that expose an HTTP endpoint) and publishes endpoints from agent registration plus heartbeats. `routing.EndpointBalancer` (`EndpointBalancer.java:56`) then load-balances to them with the existing `LbPolicy` (`LbPolicy.java:26`).
- **Pull-style** agents (the default) need no endpoint: the service name maps to the queue `tasks.<service>` (§4). Discovery only answers "is anyone serving this?" (consumer count), for fast-fail on submit.

## 8. Per-agent quotas and budgets
- **Rate (requests or tasks per second)** reuses `routing.GcraTable` from PR #82 (`GcraTable.java:31`):
  - `new GcraTable(intervalNanos, burst, buckets, clock)` (`:46`);
  - `stripe(hash)` with `mix` (`:75,80`);
  - `admit(slot)`, which returns 0 when admitted or the nanoseconds until the next admit (`:90`);
  - `delay(slot)` (`:110`).

  One table per (service, dimension) is keyed by an agent-id hash. It is fixed-size and striped, so it adds no allocation per check. A refusal maps to `429` with `Retry-After`, in the same shape as `gateway.Quota.retryAfterSeconds` (`gateway/Quota.java:27-45`).
- **Budgets (tokens or cost per window)** are *not* a rate. `BudgetLedger` keeps per-agent counters that are debited from the LLM response's usage. It debits an estimate up front and settles the actual on completion. Counters are node-local `LongAdder`s, reconciled to the CP through the node report (`observe/NodeReport.java`, `observe/NodeReporter.java`).

  **DECIDED (2026-10-13, inceptor): soft global cap for S0–S2.** Hard per-node sub-allocation by the CP comes later (phase S3+, §12).

  **Maximum overshoot of the soft cap.** Each node admits a call when its last-known global remaining budget covers the call's *estimate*. It debits the estimate locally, settles to the actual on completion, and reconciles with the CP every `T_sync`. Over one budget window, with nodes `n = 1..N`:

  ```
  Overshoot ≤ Σ_n [ F_n · ε_max ]  +  Σ_n [ D_n(T_sync) ]
  ```
  - `F_n` is the maximum number of in-flight (admitted, unsettled) budgeted calls on node `n`, bounded by the node's concurrency limit.
  - `ε_max` is the maximum per-call estimate error, `max(actual − estimate, 0)`.
  - `D_n(T_sync)` is the most node `n` can debit, in estimates, between two reconciliations: at most `r_n · T_sync · e_max`, where `r_n` is the node's GCRA admit rate for budgeted calls (plus its burst) and `e_max` is the maximum per-call estimate. This is the staleness term: other nodes' debits are invisible for up to `T_sync`.

  With uniform limits this reduces to `Overshoot ≤ N · (F · ε_max + r · T_sync · e_max)`.

  Two consequences:
  - When the gateway enforces the estimate as a hard per-call cap (e.g. the request's `max_tokens`), `ε_max = 0` and only the staleness term remains.
  - The overshoot shrinks linearly with `T_sync`.

  The doc fixes no values. Operators set `F`, `r`, `T_sync` and the estimate policy, and the admin API reports the computed bound for the current configuration.
- Quotas and budgets are checked at **submit** and at **LLM call**, never on the static-content path.

## 9. Tracing across a chain of agents
- **There is no trace-context propagation on master today.** `rg -i traceparent src` finds nothing. `observe` has metrics, the access log and node reports (`observe/RequestObserver.java:39`, `observe/AccessLog.java`, `observe/ServerMetrics.java`).
- The swarm propagates [W3C Trace Context](https://www.w3.org/TR/trace-context/) `traceparent` and `tracestate`:
  - HTTP ingress reads the header;
  - the task envelope carries it (§4.5);
  - each claim creates a child span id;
  - LLM calls forward `traceparent` upstream (the proxy already forwards end-to-end headers, `ProxyHandler.java:60-65`).
- **Export** is pluggable, and off unless configured: OTLP ([OpenTelemetry protocol spec](https://opentelemetry.io/docs/specs/otlp/)), with span attributes following the [OpenTelemetry GenAI semantic conventions](https://opentelemetry.io/docs/specs/semconv/gen-ai/). The exporter lives in `swarm` (or a later `observe.trace` package) and is fed off the request thread through a bounded queue, like `observe.LogQueue`.
- The swarm adds **no tracing to the core request path**. Only swarm endpoints create spans.

## 10. LLM backend gateway
### 10.1 Reuse
An LLM backend is a route whose upstreams are model servers, for example OpenAI-compatible endpoints ([API reference](https://platform.openai.com/docs/api-reference/chat)). The swarm composes existing routing pieces rather than adding a proxy:
- **Weighted, canary and sticky routing**: `routing.WeightedHandler` (`WeightedHandler.java:36`, Vose alias table, sticky hashing, PR #80).
- **Circuit breaking and retry budget**: `routing.Resilience(Retry, Circuit)` (`Resilience.java:33,63`) with `RetryBudget`/`CircuitBreaker` as used by `ProxyHandler` (`ProxyHandler.java:141-155`).
- **Streaming**: `ProxyHandler`'s `Body.Stream` pass-through (`ProxyHandler.java:55-58`), so SSE token streams flow without buffering.
- **Rate and budget**: §8. **Endpoint sets**: §7 / `UpstreamRegistry`.

### 10.2 The retry gap, without changing proxy defaults
Today the proxy retries **only idempotent, bodyless methods** (`ProxyHandler.java:272-287`; `Resilience.java:39`; `docs/user/config-file.md:289-291`). LLM calls are `POST` with a body, so they are never retried, which is correct for a generic proxy.

**DECIDED (2026-10-13, inceptor).** The swarm's `LlmGateway` wraps `ProxyHandler` and retries a `POST` **only when the request carries an `Idempotency-Key`** ([IETF draft](https://datatracker.ietf.org/doc/draft-ietf-httpapi-idempotency-key-header/)). The SDK sets it to `taskId:attempt` (§4.4).
- The earlier "failed before send" clause is dropped. A connect failure is only provably pre-send at the transport level, and the policy is simpler without that exception.
- Retries still spend the same `RetryBudget`.
- `ProxyHandler`'s behavior and defaults stay unchanged. If a hook is needed, it is an additive constructor parameter, reviewed with the routing owner.
- A failure after the response head has started aborts the stream, as in `ProxyHandler` today (`:69-70`).

### 10.3 Usage accounting
The swarm parses the backend usage report (the final SSE event, or the JSON `usage` field), only on swarm routes, to settle `BudgetLedger` (§8).

## 11. Client SDK and wire protocol
### 11.1 Python SDK (first)
`sjws_swarm` is a thin, dependency-light package (stdlib `ssl` + `asyncio`; an optional `httpx` transport). Its API is:
```python
async with Swarm.connect("sjws://gw:7443", cert=..., key=..., ca=...) as s:
    r = await s.call("summarize.v1", payload, deadline=30)            # request/reply
    async for tok in s.stream("chat.v1", payload): ...                 # token stream
    async for task in s.serve("summarize.v1", concurrency=4):          # agent side
        async with task.lease(heartbeat=5):                            # auto-extend (§4.2)
            await task.reply(result)                                   # reply, then ack (§4.4)
```
Trace context is taken from the active OpenTelemetry context when present. The SDK holds no business logic, so other languages port by implementing §11.2.

### 11.2 Wire protocol (v1)
The transport is mTLS over **HTTP/2 or HTTP/3**, so it reuses the existing listeners, `h2` and `h3`, and their flow control. A raw TCP framing can be added later on the same frames.
- **Unary operations:** `POST /swarm/v1/tasks/{service}` (submit), `POST /swarm/v1/tasks/{service}:claim`, `POST /swarm/v1/leases/{id}:extend`, `POST /swarm/v1/leases/{id}:ack|nack|reject`.
- **Streaming:** `GET /swarm/v1/replies/{session}`, as SSE, or as binary frames when `Accept: application/vnd.sjws.swarm.v1`.
- **Binary frames:** `[version:u8][type:u8][flags:u8][len:u24][payload]`.
  - The leading version byte follows messaging's format-version decision (`docs/messaging/design.md` §10).
  - Types: `TASK, CLAIM, LEASE, EXTEND, RESULT, TOKEN, ERROR, CANCEL, CREDIT`.
  - `CREDIT` grants per-stream send credit, on top of h2/h3 flow control, for application-level backpressure.
- **Errors** use RFC 9457 problem+json ([RFC 9457](https://www.rfc-editor.org/rfc/rfc9457)), which the routing layer already emits (`routing/ProblemErrorHandler.java`).
- A conformance suite (golden frames under `tests/.../swarm/wire`) is shared by the Java codec and the Python SDK.

## 12. Phases and exit criteria
| Phase | Deliverable | Exit criteria |
|---|---|---|
| S0 | **Done (#90).** This design, plus the guard tests from §3.3 (dependency and class-load) and the single entry point, merged before any swarm code | Tests green on master ✅; Messaging Core sign-off on §4–§5 ✅, received on #90 (https://github.com/BlivionIaG/sjws_nettask/pull/90#pullrequestreview-5500560114) with three doc fixes, all applied here. **S0 exit criteria met.** |
| S1 | Uses the Messaging Core fencing PR **#87** ("fence ack/nack/reject with the delivery attempt (lease token)"), which ships separately. `swarm` package: submit, claim with fixed lease, fenced ack/nack/reject, DLQ, request/reply via `reply.<nodeId|processId>`; `swarm.wire` codec; Python SDK unary. **Decided (2026-10-13, inceptor):** (a) the YAML key `swarm.enabled` is added in S1 together with Config & ACME, which owns `ConfigLoader`; the `-Dsjws.swarm.enabled` / `SJWS_SWARM_ENABLED` gate stays in use until then. (b) S1 adds a class-load test that starts a real `data`/`gateway` role process, using a CP fixture from Integration Tests, and asserts that no `sjws_nettask.swarm.` class is loaded with the swarm off | #87 merged; `swarm.enabled` YAML key parsed (off by default); data/gateway role class-load test green; §3.3 tests green; off-mode gate (§13) within band; task-dispatch and request/reply targets (§13) met or recorded |
| S2 | Messaging gap 2 (`extend` + `Extend` record) and `LeaseKeeper`; ledger token streaming (`chunkSeq` framing + HTTP leg), the default; cancellation; W3C trace propagation; `AgentPolicy` document and the agent intermediate CA; GCRA quotas; `BudgetLedger` with the **soft global cap** (§8) | Stale-worker fencing and lease-expiry redelivery tests; heartbeat and streaming targets (§13); trace spans across 3 hops; mTLS-only agent auth with agent-CA revocation tests; 429 + `Retry-After` tests; a budget test asserting that the overshoot stays within the §8 bound |
| S3 | **Hard per-node budget sub-allocation by the CP**; `AgentDirectory` discovery for push-style agents | Sub-allocation tests (no node exceeds its grant; re-grant on node loss) |
| S4 | `LlmGateway` (weighted routing, circuit breaking, retries only with `Idempotency-Key`, usage accounting); the opt-in **direct** token path for HTTP-gateway clients, with the final result still in the ledger (§5.2); OTLP exporter | Fault-injection tests (backend down, mid-stream abort); a test that a direct stream always produces a ledger result; wrk2 latency through the gateway |
| S5 | Other SDKs | SDK conformance suite green |

## 13. Benchmark plan
All runs report the machine (CPU model, vCPU count, RAM, disk, kernel, JDK) and the commit. Every A/B comparison uses **≥ 5 alternating runs** (master, branch, master, …), reported as mean ± stddev, as Messaging Core did for #69. The load generator and the server are pinned to disjoint CPUs with `taskset`.

**Messaging baseline already measured** (PR #69 body: `DeliveryBench`, ASYNC, 256 B, 5 alternating runs):
- produce ×1: 74,342 ± 3,230 ops/s
- produce ×4: 136,611 ± 3,933 ops/s
- consume ×1 with a 1.2 M backlog: 60,244 ± 365 ops/s
- poll p50 2.6–2.8 µs
- recovery 3.1–4.4 s/GiB full replay (1.56 s/GiB with a snapshot)
- 191–192 B heap per live message, including the 64 MiB payload cache

These are the reference points for the targets below. Neither this PR nor the targets are measurements.

**Targets** (Messaging Core, single node, same box as #69; these are pass thresholds, not results):
| Workload | Target |
|---|---|
| Task dispatch (poll + ack, 1 KiB task, 16 workers) | ≥ 50k tasks/s, poll p99 < 100 µs |
| Heartbeat/extend (10k leases, every 5 s) | ≤ 2k appends/s; < 2 % throughput loss vs no heartbeats |
| Request/reply round trip (in-process) | p50 < 50 µs, p99 < 500 µs at 10k req/s |
| Token streaming (1 KiB chunks, 1k concurrent streams) | ≥ 100 MiB/s aggregate, in order, chunk p99 < 1 ms producer→consumer |
| Lease-expiry redelivery | within visibility + 10 ms |
| Recovery with 1 M in-flight tasks | < 5 s |
| Off | wrk within the noise band; no messaging threads |

**Architecture additions:**
1. **Streaming over HTTP:** time-to-first-token and inter-token latency added by sjws, agent emit → caller receive, on HTTP/1.1 chunked, SSE, h2 and h3. Ledger leg vs the optional direct path (§5.2).
2. **Throughput through the swarm wire protocol** (§11.2), not only in-process: tasks/s at saturation with 256 B and 4 KiB payloads.
3. **Off-mode gate:**
   - `wrk -t4 -c128 -d30s --latency`, plus `wrk2 -R` below saturation, against a 1 KiB static file.
   - Master vs branch with `swarm.enabled=false`, in standalone and `data`/`gateway` mode, alternating and CPU-pinned.
   - Pass when the difference lies within the measured A/A spread.
   - Request-path cost is also asserted by the §3.3 tests: no `sjws_nettask.swarm.` class loaded, and (per Messaging Core) no `sjws_nettask.messaging.` class loaded when neither swarm nor messaging is configured. The latter check is already the pattern of `tests/sjws_nettask/cp/ClassLoadTest.java`.

## 14. Risks
| Risk | Mitigation |
|---|---|
| The swarm leaks into core paths over time | §3.3 tests fail the build; reviewer checklist |
| Missing lease extension forces long leases, so failure detection is slow | S2 depends on `Partition.extend`; S1 documents the fixed-lease limit |
| Token-per-message ledger write amplification | Chunks coalesced to 1–4 KiB or 20 ms (§5.2); opt-in direct path for ephemeral HTTP token streams only (decided, §5.2) |
| Direct-path stream lost on gateway failure | Not resumable by design; the final result is always in the ledger (§5.2) |
| Stale worker acks a re-leased attempt | Attempt-fenced ack/nack/reject (#87) and extend (§4.3), used exclusively by the swarm |
| Multi-node agents blocked on messaging network protocol and replication | Single-node scope until gap 9 (§4.8); phases S1–S3 run on one broker node |
| Budget overspend across nodes (S0–S2) | Soft global cap with a stated bound, `Σ F_n·ε_max + Σ D_n(T_sync)` (§8); `ε_max = 0` when the estimate is enforced as a per-call cap; hard per-node sub-allocation in S3 |
| Retrying non-idempotent LLM POSTs duplicates cost | Retry a POST only with `Idempotency-Key` (decided, §10.2) |
| An agent cert compromise | Separate agent intermediate CA with short-lived certs, revoked independently of nodes (§6); `AuditLog`; per-agent GCRA caps the blast radius |
| Large prompts and results bloating the ledger | Inline size limit, plus storage by reference (1 MiB inline default, swarm-owned blob store, §5.2) |

## 15. Decisions (2026-10-13, inceptor)
1. **Streaming:** the ledger is the default; the direct path is opt-in per request for ephemeral HTTP-gateway token streams only; the final result is always in the ledger (§5.2).
2. **Budget:** soft global cap for S0–S2 with the overshoot bound in §8; hard per-node sub-allocation by the CP in S3.
3. **RBAC:** `AgentPolicy` is a separate CP document; core RBAC is untouched and costs nothing when the swarm is off (§6).
4. **Agent certificates:** a separate intermediate CA; short-lived, numerous, revoked separately from nodes (§6).
5. **Fencing** ships first, as Messaging Core PR #87; S1 references it (§12).
6. **LLM retries:** a POST is retried only with an `Idempotency-Key` (§10.2).

No Architecture/CP questions remain open.

## 16. Messaging Core questions: resolved

Messaging Core answered every remaining question in its sign-off (https://github.com/BlivionIaG/sjws_nettask/pull/90#pullrequestreview-5500560114). No messaging questions remain open.

1. **Default streaming path:** the ledger for everything routed through the swarm. The direct path is an opt-in for HTTP LLM-gateway responses only, lossy and with shared framing (§5.2).
2. **Inline payload:** 1 MiB maximum by default, configurable per topic, with a 64 MiB hard limit (`RecordBatch.MAX_FRAME_BYTES`). Above that, use a by-reference blob store **owned by the swarm**, not messaging (§5.2).
3. **Replicated results:** the decided majority in-memory ack, with `strict` per topic; the task ack follows the reply's replicated ack (§4.4).
4. **Network protocol:** the swarm wire protocol (§11.2) is the **first network binding of `Partition`**. Its frames are defined generically (produce, poll, ack, nack, reject, extend, with lease tokens) and referenced from `docs/messaging/design.md` §10, rather than building a parallel broker protocol.
5. **Reply topics:** one per node or process, `reply.<nodeId|processId>`, demultiplexed by `correlationId`. No DLQ; closed after 10 minutes idle, reopened on demand, deleted after retention plus a grace period (§5.1).
6. **Unfenced settles:** deprecated now (#87 is merged) and removed after one release. The swarm exposes only fenced settles (§4.3).
