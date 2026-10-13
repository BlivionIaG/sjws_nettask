# Agent swarm layer (optional module): design

Status: **Draft** · Base: master `79610fd` · Scope: design only. No code changes.
Owner: sjws Architecture. **Co-author: Messaging Core** for §4–§5 (marked below), pending their review.

Every reference to existing code is a path and line on master `79610fd`. This doc contains **no benchmark numbers**: §13 is a plan, and its numbers will come from the runs it describes.

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
3. **Single entry point.** The composition root (`SjwsServer` / `service.Bootstrap`) references the swarm only through `ServiceLoader.load(OptionalModule.class)`, or one reflective `Class.forName("sjws_nettask.swarm.SwarmModule")`, and only when `swarm.enabled` is true. That keeps the swarm out of the verifier's reach when off. `service.Bootstrap` already gates `messaging` the same way, by name (`src/sjws_nettask/service/Bootstrap.java:43`).
4. **Off-mode wrk gate** (§13.4). It is the backstop, not the proof. wrk cannot resolve a single extra volatile read; tests 1–3 can.

## 4. Task model on messaging (Co-author: Messaging Core, pending review)

### 4.1 Mapping onto existing primitives
| Swarm concept | Existing primitive (master) |
|---|---|
| Task queue `tasks.<service>` | A topic of `Partition`s (`messaging/delivery/Partition.java:52`) with one shared consumer group per service. Queue semantics come from `docs/messaging/design.md` §1/§10.3 |
| Submit | `Partition.produce(producerId, seq, payload[, durability])` (`Partition.java:183,190`), deduplicated by `(producerId, seq)` |
| Claim + lease | `Partition.poll(group, consumer, max, visibilityMs)` (`Partition.java:225`), which writes `DELIVER` records and sets `Delivery.deadlineMs` (`Delivery.java:4`) |
| Complete | `Partition.ack(group, offset[, durability])` (`Partition.java:262,265`) |
| Fail / retry later | `Partition.nack(group, offset, delayMs)` (`Partition.java:279`), with backoff from `RetryPolicy(maxAttempts, backoffBaseMs, backoffMaxMs)` (`RetryPolicy.java:7`) |
| Poison task | `Partition.reject(group, offset, reason)` (`Partition.java:298`), dead-lettered to `Partition.deadLetterQueue()` (`Partition.java:179`) |
| Timeout | An expired lease is redelivered with `attempt+1`, or dead-lettered with reason `lease-expired:max-attempts` (`Partition.java:238`) |
| State | `MessageState { ENQUEUED, IN_FLIGHT, ACKED, DEAD_LETTERED }` (`MessageState.java:4`) |

### 4.2 Heartbeat / lease extension: **missing primitive**
`Partition` has no call that extends an in-flight lease. The only options today are `poll` with a long `visibilityMs`, or `nack` with redelivery. LLM tasks have unpredictable duration, and long fixed leases delay failure detection.

**Proposal**, owned by Messaging Core: `Partition.extend(group, offset, consumer, visibilityMs) → boolean`. It writes a `LEASE` record (or reuses `DELIVER` with the same attempt), succeeds only if `consumer` still holds the lease, and leaves `attempt` unchanged. This is the SQS `ChangeMessageVisibility` shape ([AWS API reference](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/APIReference/API_ChangeMessageVisibility.html)).

The swarm-side `LeaseKeeper` batches heartbeats from many agents into one `extend` call per partition per tick. Until the primitive lands, the swarm offers only fixed leases (phase S1).

### 4.3 Result
A result is a message to the caller's reply address (§5). It is followed by `ack` of the task offset.
- Order: **reply durable first, then ack**.
- A crash between the two redelivers the task. The caller de-duplicates by `task_id` (§4.4).
- Results larger than a configurable inline limit are stored by reference; the limit's size is an open question (§14).

### 4.4 Task envelope
The payload stays opaque to `Partition`. Swarm defines the envelope inside the payload:
`{version:u8, task_id:16B, trace:traceparent, tracestate?, reply_to, deadline_ms, budget_ref, idempotency_key?, content_type, body}`.

`Delivery` has no headers field (`Delivery.java:4-5`), so trace context and metadata ride in the envelope. Messaging Core may prefer first-class headers (§14 Q3).

## 5. Request/reply and token streaming (Co-author: Messaging Core, pending review)

### 5.1 Request/reply
Each caller gets an ephemeral reply inbox: `reply.<caller-node>.<session>`, with `Durability.NONE` or `ASYNC` (`messaging/ledger/Durability.java`) and a short retention.
- The caller blocks on its own inbox, not on the agent.
- The deadline is `deadline_ms` from the envelope. On timeout the caller gets a `504`-class error. The task may still run; it is cancelled cooperatively via a `cancel` frame (§11.2).

### 5.2 Streaming token output
Writing every token to the ledger is the wrong default: it adds one ledger append per token. Instead:
- **Live path.** The agent opens a stream to the gateway node that holds the caller's session (located through `AgentDirectory`, §7). It pushes `token` frames, which are forwarded to the caller as:
  - HTTP/1.1 chunked responses (`http1/Http1Writer.java:236-251` writes unknown-length bodies as chunked);
  - `text/event-stream` ([WHATWG HTML §9.2, Server-sent events](https://html.spec.whatwg.org/multipage/server-sent-events.html));
  - or h2/h3 DATA frames.

  The response is a `core.Body.Stream`, which already gives pull-based backpressure (`routing/ProxyHandler.java:55-58`: "the writer pulls the next upstream chunk only after the previous one was sent").
- **Durable path (opt-in per task).** Tokens are coalesced into chunk messages (by size or time) on the reply topic, so a reconnecting caller can resume from an offset. The final `result` message is always durable.
- **Backpressure.** A slow caller stalls the agent's stream through TCP and HTTP/2/3 flow control. It never makes the server buffer without bound. The per-stream buffer is capped at one chunk, as in `ProxyHandler`.

## 6. Agent identity and RBAC
- **Identity.** Every agent connection is mTLS, with a client certificate issued under the CP trust root (`cp/KeyRing.java`, `cp/TrustKey.java`; enrollment in `service/NodeEnrollment.java`, `service/JoinMaterial.java`). The agent id is the certificate subject, which `controlplane.Rbac.ClientCert(subject)` already models (`controlplane/Rbac.java:50`).
- **Authorization.** `Rbac.authenticate(Credential)` and `Rbac.authorize(credential, permission, target, version)` (`Rbac.java:91,118`) are reused unchanged. Today's `Permission` set is operator-oriented (`READ, EDIT_ROUTES, ROLLOUT, ROLLBACK, NODE_DRAIN, …`, `controlplane/Permission.java:22-30`), and `Role` is `VIEWER/OPERATOR/ADMIN` (`Role.java:24-27`).

  The swarm needs agent permissions: `TASK_SUBMIT(service)`, `TASK_CLAIM(service)`, `LLM_CALL(backend)`. Two options:
  - (a) a separate `swarm.AgentPolicy` document, distributed through the CP like `controlplane.AccessPolicyDocument`. **Recommended**: `controlplane` does not change.
  - (b) extend `Permission`. This touches a CP enum that other code switches on.
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

  The CP enforces a global cap only as a soft limit, because a hard global budget needs consensus per debit, which is not acceptable. Hard per-node sub-budgets are allocated by the CP; whether to have them is open question §15.1.
- Quotas and budgets are checked at **submit** and at **LLM call**, never on the static-content path.

## 9. Tracing across a chain of agents
- **There is no trace-context propagation on master today.** `rg -i traceparent src` finds nothing. `observe` has metrics, the access log and node reports (`observe/RequestObserver.java:39`, `observe/AccessLog.java`, `observe/ServerMetrics.java`).
- The swarm propagates [W3C Trace Context](https://www.w3.org/TR/trace-context/) `traceparent` and `tracestate`:
  - HTTP ingress reads the header;
  - the task envelope carries it (§4.4);
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

The swarm's `LlmGateway` wraps `ProxyHandler` and adds one swarm-only rule. It retries a `POST` **only when the failure provably happened before the request was sent** (`connect`), or when the backend honours an idempotency key (`Idempotency-Key`, [IETF draft](https://datatracker.ietf.org/doc/draft-ietf-httpapi-idempotency-key-header/)). It still spends the same `RetryBudget`. `ProxyHandler`'s behavior and defaults stay unchanged; if a hook is needed, it is an additive constructor parameter, reviewed with the routing owner. A failure after the response head has started aborts the stream, as in `ProxyHandler` today (`:69-70`).

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
            await task.reply(result)                                   # reply, then ack (§4.3)
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
| S0 | This design, plus the guard tests from §3.3 (dependency and class-load), merged before any swarm code | Tests green on master; Messaging Core sign-off on §4–§5 |
| S1 | `swarm` package: submit, claim with fixed lease, ack/nack/reject, DLQ, request/reply; `swarm.wire` codec; Python SDK unary | §3.3 tests green; off-mode wrk gate (§13.4) within band; claim/lease bench (§13.1) recorded |
| S2 | `Partition.extend` (Messaging Core) + `LeaseKeeper`; token streaming (live path); W3C trace propagation | Lease-expiry redelivery tests; streaming bench (§13.2); trace spans across 3 hops in an integration test |
| S3 | Agent identity + `AgentPolicy` RBAC via CP; `AgentDirectory` discovery; GCRA quotas; `BudgetLedger` | mTLS-only agent auth tests; 429 + `Retry-After` tests; budget settle tests |
| S4 | `LlmGateway` (weighted, circuit, pre-send retry, usage accounting); OTLP exporter | Fault-injection tests (backend down, mid-stream abort); wrk2 latency through the gateway |
| S5 | Durable token chunks + resume; other SDKs | Resume-from-offset test; SDK conformance suite green |

## 13. Benchmark plan
All runs report the machine (CPU model, vCPU count, RAM, disk, kernel, JDK) and the commit. Each comparison is **≥ 3 alternating A/B runs**, reported as median and spread. The load generator and the server are pinned to disjoint CPUs with `taskset`. The harness is the one `docs/benchmarks/` and the CI perf gate (PR #46) already use.

1. **Claim/lease latency.** Closed loop with N agents: submit → claim → ack, at durability `ASYNC`/`GROUP` × fixed lease vs `extend`. Report p50/p99/p99.9 of submit→claim and claim→ack, and claims/s.
2. **Streaming token latency.** A synthetic agent emits tokens at a fixed rate. Measure inter-token latency added by sjws (agent emit → caller receive) on HTTP/1.1 chunked, SSE, h2 and h3, plus time-to-first-token, and live vs durable-chunk path.
3. **Throughput.** Tasks/s at saturation, with 256 B and 4 KiB payloads, 1 and 8 partitions.
4. **Off-mode check (gate).** `wrk -t4 -c128 -d30s --latency` and `wrk2 -R` below saturation, against a 1 KiB static file, for master vs branch with `swarm.enabled=false`, both standalone and `data`/`gateway`. Use alternating CPU-pinned runs as above. The pass condition is that the difference lies within the measured A/A spread. Request-path cost is also asserted by the §3.3 tests, because wrk cannot resolve it.

## 14. Risks
| Risk | Mitigation |
|---|---|
| The swarm leaks into core paths over time | §3.3 tests fail the build; reviewer checklist |
| Missing lease extension forces long leases, so failure detection is slow | S2 depends on `Partition.extend`; S1 documents the fixed-lease limit |
| Token-per-message ledger write amplification | Live path by default; durable chunks are opt-in and coalesced (§5.2) |
| Budget overspend across nodes | Up-front estimate debit; soft global cap; CP-allocated per-node sub-budgets (§15.1) |
| Retrying non-idempotent LLM POSTs duplicates cost | Retry only pre-send failures or with `Idempotency-Key` (§10.2) |
| An agent cert compromise | CP revocation + `AuditLog`; short-lived agent certs; per-agent GCRA caps blast radius |
| Large prompts and results bloating the ledger | Inline size limit, plus storage by reference (§4.3) |

## 15. Open questions for Architecture / CP
1. Hard per-node budget sub-allocation by the CP, or soft global only for v1?
2. `AgentPolicy` as a separate CP-distributed document (recommended), or new `Permission` values?
3. Should agent certificates be issued by the same CP enrollment flow as nodes, or by a separate intermediate CA?

## 16. Open questions for Messaging Core
1. **Lease extension:** will you add `Partition.extend(group, offset, consumer, visibilityMs)` (§4.2)? Should it write a new `LEASE` ledger record, or reuse `DELIVER` with the same attempt? What happens to `attempt` and to `deadLetter` accounting?
2. **Consumer liveness:** should a consumer whose leases are all extended by one batched call count as "alive" for `MessagingStats` lag and in-flight reporting?
3. **Headers:** `Delivery` has no headers (`Delivery.java:4-5`). Do you want first-class message headers (trace context, content type, reply-to, deadline) in the batch format, or should the swarm keep them in its payload envelope (§4.4)?
4. **Reply inboxes:** are thousands of short-lived, low-retention reply topics acceptable on `SegmentedLog`, with its segment preallocation (`docs/messaging/design.md` §2.1)? Or should replies use one shared topic keyed by session?
5. **Durability per message:** `produce(..., Durability)` exists (`Partition.java:190`). Is it fine for the swarm to use `NONE` for token chunks and `GROUP` for results on the same partition?
6. **Ordering:** should tasks for one conversation use `orderedByKey` (`docs/messaging/design.md` §3), and what does a redelivered head-of-line task do to the latency of later same-key tasks?
7. **Cancellation:** is a `cancel` best done as a `reject(reason="cancelled")` by the server on behalf of the caller, or should it be a separate record type?
8. **Large payloads:** what is the max inline payload you want in the ledger, and is a by-reference blob store in scope for messaging or for the swarm?
9. **Replication:** for replicated partitions, should the swarm's default durability for results be the decided cluster default (majority in-memory, `docs/design/architecture.md` §A Decided)?
