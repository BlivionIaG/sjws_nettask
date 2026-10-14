# Agent graphs (addendum to the agent swarm design)

Status: **Draft** · Base: master `a9ebb4c` · Scope: design only. No code changes.
Owner: sjws Architecture. Messaging-facing sections (§4, §6) need **Messaging Core** review.
Parent design: [agent-swarm.md](agent-swarm.md) (the "swarm doc"). Section numbers like "swarm §4.4" refer to it.

Every reference to existing code is a path and line on master `a9ebb4c`. This doc contains **no new measurements**. §12 quotes numbers measured by others (with the source) and lists targets, which are pass thresholds, not results.

## 1. Goal and the hard rule

Blivion's requirement: the swarm must run **agent graphs**, from a handful of agents to thousands, and from one run to thousands of concurrent runs.

A *graph* is a versioned definition of steps (agent calls) and edges. A *run* is one execution of a graph version with an input. The graph layer is a **thin orchestrator on top of the swarm task model**: every step is an ordinary swarm task (swarm §4), except the built-in steps listed in §2, and every run's state is an event log in the messaging ledger.

**HARD RULE (inherited from swarm §1): optional and zero cost when off.**
- The graph feature lives in a new package `sjws_nettask.swarm.graph`, loaded only when **both** `swarm.enabled` and `swarm.graphs.enabled` are true.
- With graphs off (the default), no `sjws_nettask.swarm.graph.` class is loaded, no runner thread is started, no graph topic is opened, and no graph document is parsed or held. With the swarm off, nothing in `swarm.*` loads at all (swarm §3.3).
- Core hot paths (`http1`, `h2`, `h3`, `core`, `routing.ProxyHandler`, `messaging.delivery.Partition`) are not modified. The graph layer uses public APIs of `swarm` and `messaging` only.

**Built on graphs:** [swarm research mode](swarm-research-mode.md) (hill-climb and autoresearch loops).

## 2. Non-goals
- **An agent framework or planner.** sjws does not decide *what* agents do. It executes a graph someone defined. Prompting, tool selection and LLM-driven planning stay in agents (swarm §2).
- **Turing-complete workflows in the server.** Conditions are a small, side-effect-free expression language over step outputs and run-scoped carried state (§3.4). No user code runs in the server. The runner itself executes only a fixed set of **built-in steps** (today: `select`, swarm research §5.2 P2). They are pure, seeded and versioned with the server, and their outcomes (including random draws) are recorded in the run log, where at replay the recorded outcome wins. Run-scoped **carried state** (`$.carry`, P1) changes only through a fixed set of reducers (`replace`, `append(max_len)`, `merge`, `top_k(by, k)`) under a declared schema and byte limit, so every run remains a bounded, finite-state computation.
- **Unbounded loops or unbounded spawning.** Every cycle and every dynamic spawn has a hard limit (§3.5, §3.6): a loop edge always declares `max_iterations` even when its exit is a condition, and a map step always declares `max_items` even when its count comes from an expression or `repeat` (P3). The resolved count is recorded before spawning.
- **Exactly-once side effects.** We give exactly-once *recorded step results* per run (§4.4). External effects remain at-least-once, as in swarm §4.4.
- **Deterministic replay of agent code** (as Temporal does for workflow code). Agents are external and non-deterministic; we replay *recorded results*, never re-execute to rebuild state.
- **Cross-run transactions** and **multi-node graph execution before messaging has a network protocol and replication** (swarm §4.8 gap 9).

## 3. Graph model

### 3.1 Elements
| Element | Meaning |
|---|---|
| **Graph** `name@version` | Immutable once published. Nodes, edges, limits, budgets, default timeouts. |
| **Step** (node) | `id`, `service` (a swarm pool, swarm §7: the task goes to `tasks.<service>`), `input` mapping, `timeout`, `retry` (overrides the topic `RetryPolicy`), `join`, optional `budget`. |
| **Edge** | `from → to`, optional `when` condition, optional `loop` marker with `max_iterations`. |
| **Subgraph step** | A step whose target is another graph `name@version`; runs as a child run (§3.5). |
| **Map step** | Dynamic fan-out over a list produced at run time (§3.6). |
| **Run** | `runId` (16 B random, like `TaskEnvelope.taskId`), pinned `graph@version`, input, deadline, budget, trace context. |

### 3.2 DAG semantics
- Without `loop` edges the graph must be acyclic; this is validated at publish time (topological sort), not at run time.
- A step becomes **ready** when its join condition (§3.3) is satisfied and its inputs can be resolved. Ready steps are dispatched immediately; independent branches run in parallel.
- The run completes when every terminal step has completed or been skipped, and fails when a step fails after exhausting retries and no `on_error` edge handles it.

### 3.3 Fan-out / fan-in and join semantics
A step with several incoming edges declares `join`:
- `all` (default): wait for every non-skipped predecessor. A skipped predecessor (its `when` was false) counts as satisfied, so conditional branches do not deadlock the join.
- `any`: fire on the first completed predecessor; the remaining in-flight predecessors are **cancelled by default** (§6.1, decided §11.1) unless the step sets `cancel_losers: false`.
- `quorum(k)`: fire after `k` completions; the rest are cancelled the same way. Publish-time validation rejects `k` larger than the in-degree.
- Failed predecessors: `all` fails the step; `any`/`quorum(k)` fail only when success has become impossible (`completed + still_possible < k`).

A join records **which** predecessor results it consumed in the `StepReady` event (§4.2), so resume never re-evaluates a race differently.

### 3.4 Conditional edges
- `when:` is an expression over the JSON output of the source step and the run input, written in a **JSONPath subset** (decided §11.1; not CEL). The subset, based on [RFC 9535](https://www.rfc-editor.org/rfc/rfc9535):
  - **Paths:** root `$` (the run context: `$.input`, `$.<stepId>` for completed step outputs, `$.carry` for run-scoped carried state, P1 in swarm research §5.2), member access `.name` or `['name']`, array index `[n]` (non-negative). No wildcards, slices, recursive descent (`..`) or filter selectors, so every path selects at most one value.
  - **Literals:** strings, numbers, `true`, `false`, `null`.
  - **Operators:** `== != < <= > >=`, `&&`, `||`, `!`, parentheses, `in` (value in a literal list), and the one function `len(path)` (string, array or object size).
  - **Missing values:** a path that does not resolve yields `null`; comparisons with `null` other than `==`/`!=` are false.
  - No user functions, no loops, no side effects. Expression size is limited at publish time, so evaluation cost is bounded.
- Evaluated once, in the runner, when the source step completes; the outcome (taken / skipped) is written to the run log, so replay uses the recorded outcome, not a re-evaluation.
- Precedent: conditional edges in [LangGraph](https://langchain-ai.github.io/langgraph/concepts/low_level/#conditional-edges) and branching in [Airflow](https://airflow.apache.org/docs/apache-airflow/stable/core-concepts/dags.html#branching). Unlike LangGraph, the condition is declarative data, not a Python function, so the server can evaluate it and the CP can distribute it.

### 3.5 Bounded cycles (loops)
- A cycle is allowed only if at least one edge on it is marked `loop: {max_iterations: N}` (N required, with a server-wide ceiling). Typical use: critic/reviser loops.
- Each loop iteration gets a fresh **step instance id** `stepId#i` (§4.1). Iteration counters live in run state and are part of the idempotency key, so iteration 3 never dedups against iteration 2.
- Reaching `max_iterations` takes the edge's `on_exhausted` target if declared, otherwise fails the run with `loop-exhausted`.
- Precedent: LangGraph's `recursion_limit` ([docs](https://langchain-ai.github.io/langgraph/concepts/low_level/#recursion-limit)) bounds super-steps globally; we bound each loop explicitly, which is easier to reason about for budgets (§5.4).

### 3.6 Subgraphs and dynamic spawning
- **Subgraph step:** starts a child run of a pinned `name@version` with `parentRunId`, `parentStepId`. The child inherits deadline, budget slice and trace context (§6). Its result is the parent step's result. Max nesting depth is a server limit; recursion (graph calling itself) is allowed only within that depth.
- **Map step (dynamic fan-out):** `map: {over: <expr yielding a list>, step: <template>, max_items: N, concurrency: C, join: all|any|quorum(k)}`. The runner records the resolved list length in the log before spawning, refuses lists longer than `max_items` (fails the step, no truncation), and keeps at most `C` instances in flight (backpressure, §5.3).
- **Agent-requested spawn: deferred to G4** (decided §11.1). Through G3, map steps are the only dynamic fan-out. The G4 design, for reference: an agent may return a `spawn` directive (list of step templates already declared in the graph as `spawnable`). It is bounded per step (`max_spawn`) and per run (`max_steps_per_run`), and charged to the run budget. Agents cannot introduce services or graphs that the version does not name, so `AgentPolicy` (swarm §6) still governs every task.
- Precedent: Airflow [dynamic task mapping](https://airflow.apache.org/docs/apache-airflow/stable/authoring-and-scheduling/dynamic-task-mapping.html) (`max_map_length`), LangGraph [`Send`](https://langchain-ai.github.io/langgraph/concepts/low_level/#send) for map-reduce.

## 4. Execution

### 4.1 Mapping onto tasks and topics
| Graph concept | Swarm / messaging primitive |
|---|---|
| Step instance dispatch | A normal swarm task on `tasks.<service>` via `TaskService.submit(pool, producerId, seq, envelope)` (`src/sjws_nettask/swarm/TaskService.java:77`). Workers do not know they are in a graph. |
| Step result | The worker's normal fenced reply-then-ack, `TaskService.reply(...)` (`TaskService.java:206`), whose `replyTo` is the run's **shard topic** instead of a reply topic. Workers write results **directly** into the shard topic, with no runner copy (decided §11.3). The envelope already carries `replyTo` (`TaskEnvelope.java:93`). |
| Run event log | Topic `graph.runs.<shard>`, `shard = hash(runId) mod S`. One shard = one `Partition` (`messaging/delivery/Partition.java:52`) opened through `swarm.Topics.partition` (`swarm/Topics.java:52`). Multi-partition topics are a messaging GAP (swarm §4.8 gap 5), so sharding is by topic name until they exist. |
| Step identity | `stepInstanceId = stepId[#iteration][/mapIndex]`, deterministic from the graph and the recorded run state. |
| Idempotency key | `(runId, stepInstanceId, stepAttempt)` in the envelope `taskId` field (16 B: a hash of the triple) and, for LLM calls, in the `Idempotency-Key` header (swarm §10.2). |
| Producer dedup | The runner produces with a **stable per-shard `producerId`** and `seq = runner-local monotonic counter persisted in the checkpoint` (swarm §4.4; `DeliveryState` window of 4096 per producer). Workers writing results are also producers on the shard partition with their own ids (§11.3). |

`stepAttempt` is the **graph-level** retry counter (re-dispatch of a step after a failed result or timeout). The messaging delivery `attempt` (lease token, `Task.attempt`, `swarm/Task.java:23`) is separate and handles worker crashes inside one step attempt.

### 4.2 Durable per-run state: event-sourced in the ledger
Each shard topic is the **source of truth**. Events, all keyed by `runId`:

`RunStarted{graph@version, input-ref, deadline, budget, traceparent}` · `StepReady{stepInstanceId, consumedResults[]}` · `StepDispatched{stepInstanceId, stepAttempt, taskId, pool, offset}` · `StepResult{stepInstanceId, stepAttempt, status, output-ref, usage}` · `EdgeDecided{from, to, taken}` · `Spawned{stepInstanceId, n}` · `StepCancelled` · `CarryUpdated{digest}` · `Selected{kept, dropped, draws}` · `RunCheckpoint{...}` · `RunFinished{status, output-ref}`.

- Payloads above the 1 MiB inline limit go by reference to the swarm-owned blob store (swarm §5.2, §16 item 2).
- **One topic per shard, one total order** (decided §11.3): decisions and results share the shard partition, so replay picks the same accepted result (§4.4 point 2), with one snapshot, one retention low watermark and one `poll` loop. If result volume starves the runner (visible as runner group lag in `MessagingStats`), the fix is more shards, not more topics per shard. Workers that are not trusted to write run history are excluded through `AgentPolicy` rights, not by adding a reply topic.
- **Single writer per shard:** one `GraphRunner` owns a shard and is the only producer of decisions (`StepReady`, `StepDispatched`, `EdgeDecided`, …). `StepResult` events are appended by workers (via `replyTo`), and the runner consumes the shard topic as one consumer group, so it sees results and its own decisions in one total order. No lock is shared between shards.
- Prior art: event-sourced workflow history in [Temporal](https://docs.temporal.io/workflow-execution/event); the difference (§2) is that we record decisions too, so no workflow code is replayed.

### 4.3 Checkpoints and resume
- The runner keeps an in-memory table `runId → RunState` for runs in its shard. Every `K` events or `T` ms (operator-tuned; no values fixed here) it writes a `RunCheckpoint` containing the compact state of all active runs in the shard plus the shard offset it covers, and the runner's producer `seq`. Carried state (`$.carry`, P1) is part of `RunState`, so it is included in `RunCheckpoint`.
- This is a per-shard snapshot of an event-sourced log, similar in spirit to [Flink's checkpoints](https://nightlies.apache.org/flink/flink-docs-stable/docs/concepts/stateful-stream-processing/#checkpointing) (state + source offset), but trivially consistent because one thread owns the shard. Messaging's own snapshots (#69) keep the partition recovery bounded underneath.
- **Resume after failure** (process crash, node restart): open shard, read the last `RunCheckpoint`, replay events after its offset, rebuild `RunState`. Then reconcile in-flight steps: a `StepDispatched` without `StepResult` is still a live task in `tasks.<pool>` (it survives in its own partition), so the runner just waits, with the step timeout (§6.2) as the backstop. Nothing is re-dispatched merely because the runner restarted.
- **Retention** (decided §11.3). History below a checkpoint can be freed while runs are active, under these rules:
  - Shard floor = `min(latest durable RunCheckpoint offset, RunStarted offset of the oldest active run not fully contained in that checkpoint)`. Because checkpoints hold all active runs' state, this is in practice the checkpoint offset.
  - The runner exposes the floor to messaging as an **explicit "retain from" pin**, not via consumer-group commit timing. The runner group's committed offset already pins the delivery low watermark (`RetentionPolicy`), so unconsumed results are never lost.
  - Order: partition snapshot (#69) at or above the checkpoint offset, then the checkpoint, then retention. Retention never deletes at or above the newest snapshot, so recovery cannot replay into a gap.
  - Finished-run history for the TUI and tracing (§6.3) is a time floor (`keepResolvedMs`), applied only below the checkpoint floor.
  - The retain-from pin is a messaging API addition (Messaging Core); it does not exist on master.

### 4.4 Exactly-once step results (and what it does not mean)
Guarantee: **for each `(runId, stepInstanceId)`, the run state records exactly one accepted result, and downstream steps see only that result.**

How:
1. **Fencing at the task level:** workers settle with the fenced `ack(group, offset, attempt, ...)` (`Partition.java:418-431`) after the reply is durable (`TaskService.reply`, `TaskService.java:206`; swarm §4.4). A stale worker gets `STALE_ATTEMPT` and its ack is rejected.
2. **But a stale worker's reply may already be in the shard log** (it is written before the ack). So the runner also fences: it accepts a `StepResult` only if `stepAttempt` equals the current attempt for that step instance and no result has been accepted yet; all others are recorded as `duplicate`/`stale` and ignored. The decision is made by the single writer, in log order, so replay yields the same choice.
3. **Idempotency key `(runId, stepInstanceId, stepAttempt)`** travels to agents and to the LLM gateway (`Idempotency-Key`, swarm §10.2), so a redelivered task (new messaging `attempt`, same `stepAttempt`) can be deduplicated by the agent's tools.
4. Runner output (`StepDispatched`, task submit) uses producer dedup with a checkpointed `seq`, so a runner that crashes between writing `StepDispatched` and submitting the task resubmits with the same `(producerId, seq)` and the broker drops the duplicate.

**Honest caveats.**
- External effects (an email sent, a ticket created, LLM tokens spent) can happen more than once, because delivery is at-least-once (swarm §2, §4.4). Only tools that honour the idempotency key avoid duplicates. LLM spend on a duplicated call is real and is charged to the budget.
- Task submit to `tasks.<pool>` and the `StepDispatched` event live in different partitions; there is no cross-partition transaction (swarm §4.4, GAP). Point 4 makes the duplicate harmless, it does not make it impossible.
- Item 4 relies on producer-dedup state surviving restarts (it does, via snapshots) and on producer ids not expiring mid-run. Producer-id expiry (swarm §4.8 gap 8) is **on idle time, not age**: a producer expires only after `max_run_duration + max step timeout + clock-skew margin` without an append, evaluated from ledger timestamps so replay expires the same producers (decided §11.3). Runner producer ids are stable per shard and never idle while the shard lives; worker producer ids are the ones that expire.

## 5. Scale

### 5.1 Partitioning and no global locks
- Runs are partitioned by `runId` into `S` shards; each shard has one runner thread (a virtual thread blocking on `Partition.poll(..., waitNanos)`, `Partition.java:262`, from #99). Runs never move between shards, so a run's state is touched by one thread only: **no locks on run state**.
- The only shared structures are per-shard (the shard `Partition`, which has its own `ReentrantLock`, `Partition.java:65`) and per-pool (`tasks.<pool>`). There is no global lock and no global counter on the dispatch path. Budget counters are striped (§5.4).
- Worker side is unchanged: thousands of agents are thousands of consumers on pools, each claiming via `TaskService.claim` (`TaskService.java:89-94`) and heartbeating through the batched `LeaseKeeper` (`swarm/LeaseKeeper.java:41`, `:117`).
- Pool contention is the current limit, not the graph layer: 16-worker claim p99 is **458 µs after #103** (measured by Messaging Core) against the S1c target of < 100 µs. Graphs that fan out to one pool inherit that until the S1c gate is met; multi-partition pools (gap 5) are the scale-out path.
- Shard count `S` is **fixed per deployment** (decided §11.1); changing it requires draining. Online resharding is deferred and not scheduled in G0–G5.
- Multi-node: shards are assigned to broker nodes by the CP once messaging replicates (gap 9). Until then, graphs are single-node, like the rest of the swarm.

### 5.2 Thousands of concurrent runs: memory model
- Active `RunState` is compact: per step instance a status byte, attempt, task offset and output reference (outputs live in the log or blob store, not in RunState). No numbers are promised here; §12 measures bytes per active run.
- Finished runs are evicted from memory after `RunFinished`.

### 5.3 Backpressure
Bounded at every level, and admission refuses rather than queues unboundedly:
1. **Run admission:** `POST /swarm/v1/graphs/{name}/runs` is refused with `429` + `Retry-After` (RFC 9457 problem, as swarm §8) when the shard's active runs exceed `max_active_runs_per_shard`, or the graph's GCRA rate is exceeded (§5.4).
2. **Per-run concurrency:** `max_inflight_steps` per run and `concurrency` per map step (§3.6).
3. **Per-pool lag:** before dispatching, the runner reads the pool group's `lagMessages` from the **published `MessagingStats` snapshot** (lock-free; about 2.6 µs per read as measured by Messaging Core), never `counts()`, which can take the partition lock to rebuild. The read is rate-limited to about once per ms or once per poll batch, so a hot runner loop cannot hammer it (decided §11.3). Above a threshold, dispatch is deferred. The producer-side backpressure signal is messaging gap 7.
4. The runner's own input is pull-based (`poll(max)`), so a flood of results cannot overrun it.

### 5.4 Budgets: per graph, per run, per agent
- **Rates** reuse `routing.GcraTable` (`routing/GcraTable.java:31`; `admit(slot)` at `:90`): one table for run starts keyed by graph-name hash, and the existing per-agent tables (swarm §8) for step tasks. No allocation per check.
- **Spend** reuses swarm `BudgetLedger` (swarm §8; it is an S2 deliverable and does not exist on master yet). The graph layer adds two budget scopes, debited alongside the agent's: **per run** (`budget.max_tokens`/`max_cost` in the run request, capped by the graph's default) and **per graph** (window budget across all runs of a graph name; ships in G3, decided §11.1).
- Every step debits its estimate before dispatch and settles on `StepResult.usage`. A run that exceeds its budget stops dispatching, cancels in-flight steps (§6.1), and finishes with `budget-exhausted`.
- The soft global cap and its overshoot bound from swarm §8 apply unchanged per scope; for a run, in-flight steps are bounded by `max_inflight_steps`, which plays the role of `F` in that formula.
- Loops and spawns are charged as they happen, so `max_iterations` × step estimate gives the operator a static worst case per run at publish time; the admin API shows it.

### 5.5 Cost at small scale
- One node, one shard by default (`S = 1`): one extra topic and one runner virtual thread, parked in `poll` when idle.
- Per step: one `StepDispatched` append, one task submit, one result append (written by the worker in place of the normal reply), and one runner decision. The marginal cost over a plain swarm request/reply is therefore one extra append per hop on the shard topic; §12 measures it.
- Graphs off: nothing loads (§1, §8).

## 6. Control and observability

### 6.1 Cancellation
- `POST /swarm/v1/runs/{runId}:cancel` (or deadline expiry, budget exhaustion, a losing `any`/`quorum` branch) makes the runner append `StepCancelled` for in-flight steps and stop dispatching.
- In-flight tasks are cancelled with the swarm's `cancel(correlationId)` (swarm §5.2) carrying the step's correlation id; agents check it cooperatively. Queued, unclaimed tasks are skipped by the worker on claim because their deadline/cancel marker is visible (until broker-side TTL, swarm §4.6, lands).
- Child runs (subgraphs) receive the cancel recursively; a cancelled child never reports success to the parent.
- A result arriving after cancellation is recorded as `late` and ignored; its spend is still settled.

### 6.2 Timeouts and deadline inheritance
- Every run has an absolute deadline. Each step's effective deadline is `min(run deadline, now + step timeout)`, and a child run's deadline is `min(parent step deadline, child default)`. The value lands in `TaskEnvelope.deadlineMs` (`TaskEnvelope.java:97`), which workers already honour (expired tasks are rejected on claim, `swarm/TaskService.java:116`, using `TaskEnvelope.expiredAt`, `TaskEnvelope.java:136`; swarm §5.1).
- The worker's lease visibility is already bounded by the deadline (swarm §5.1).
- The runner enforces step timeouts itself with a per-shard timer wheel (one thread, no per-step thread), so a lost task cannot stall a run: on timeout it bumps `stepAttempt` and redispatches (if retries remain) or fails the step. This mirrors Temporal's [start-to-close and schedule-to-close timeouts](https://docs.temporal.io/encyclopedia/detecting-activity-failures).

### 6.3 Tracing per run and per edge
- [W3C Trace Context](https://www.w3.org/TR/trace-context/): the run's root span is the `traceparent` of the start request (or new). Each step attempt is a child span; its `traceparent` goes into `TaskEnvelope.traceparent` (`TaskEnvelope.java:101`), so agents and LLM calls nest under it (swarm §9).
- **Edges:** each taken edge is a short span (or a [span link](https://opentelemetry.io/docs/concepts/signals/traces/#span-links) for joins with several parents), with attributes `graph.name`, `graph.version`, `run.id`, `edge.from`, `edge.to`, `edge.condition_result`. Joins link all consumed predecessors.
- Attributes follow OpenTelemetry [GenAI semantic conventions](https://opentelemetry.io/docs/specs/semconv/gen-ai/) where they apply (agent and LLM spans).
- Export uses the swarm's pluggable OTLP exporter (swarm §9, S4), fed off-thread. Without it, the run log itself is the trace: the TUI renders a run as a graph with per-step timing from event timestamps (via `LedgerInspector`, swarm §4.7).
- Metrics per graph: active runs, runs/s started/finished/failed, step latency per edge, retries, cancellations, budget remaining.

## 7. API and SDK

### 7.1 Declarative definition (YAML / JSON)
```yaml
graph: research-report
version: 3
limits: {max_steps_per_run: 500, max_inflight_steps: 32, max_depth: 3}
budget: {max_tokens: 200000}
timeout: 10m
steps:
  plan:      {service: planner.v1}
  search:    {service: search.v1, map: {over: "$.plan.queries", max_items: 100, concurrency: 16}}
  summarize: {service: summarize.v1, join: all}
  critic:    {service: critic.v1}
  revise:    {service: writer.v1}
  publish:   {graph: publish@2}            # subgraph
edges:
  - {from: plan, to: search}
  - {from: search, to: summarize}
  - {from: summarize, to: critic}
  - {from: critic, to: revise,  when: "$.critic.score < 0.8", loop: {max_iterations: 3}}
  - {from: revise, to: critic}
  - {from: critic, to: publish, when: "$.critic.score >= 0.8"}
```
A JSON Schema for this document ships with G0 and is the single source of validation for both the server and the SDK. YAML and JSON are equivalent encodings.

### 7.2 Python SDK
Extends `sjws_swarm` (swarm §11.1); the builder only produces the declarative document, it never runs graph logic client-side:
```python
g = Graph("research-report", version=3, timeout=600)
plan = g.step("plan", service="planner.v1")
search = g.map("search", service="search.v1", over="$.plan.queries", max_items=100, concurrency=16)
summ = g.step("summarize", service="summarize.v1", join="all")
plan >> search >> summ
await s.graphs.publish(g)                       # needs GRAPH_PUBLISH in AgentPolicy
run = await s.graphs.start("research-report", input, version=3, deadline=600)
async for ev in run.events(): ...               # StepResult / RunFinished stream
await run.cancel()
```
Agents serving steps use the existing `s.serve(...)` unchanged; step metadata (`runId`, `stepInstanceId`, `stepAttempt`, idempotency key) is exposed on the task object.

### 7.3 HTTP surface (on the `/swarm/v1` mount)
Added to the swarm handler mounted through `HandlerContributor` (PR #101; `SwarmModule`, `swarm/SwarmModule.java`), under `/swarm/v1/graphs/...` and `/swarm/v1/runs/...`: start, get, events (SSE / binary frames per swarm §11.2), cancel. When graphs are off these paths return `404` from the swarm handler without loading graph classes (`SwarmHttp`, `swarm/SwarmHttp.java:77`, dispatches by path; a graph prefix is delegated to a lazily loaded handler only when enabled).

### 7.4 Versioning
- `name@version` is immutable. Publishing a changed definition requires a new version.
- **In-flight runs are pinned** to the version in `RunStarted`; they always finish on it. New runs use the version named in the request, or the graph's `default` pointer.
- A version can be **retired** (no new runs) but is kept while any run pinned to it is active.
- No in-place migration of running runs in v1 (non-goal); Temporal's [versioning](https://docs.temporal.io/workflow-definition#workflow-versioning) problem does not arise because no workflow code is replayed (§2).

### 7.5 Distribution through the control plane
- Graph definitions are a **separate CP document type, `swarm.GraphDocument`**, distributed exactly like `AgentPolicy` (swarm §6), which itself follows `controlplane.AccessPolicyDocument` (`controlplane/AccessPolicyDocument.java:50`: `record AccessPolicyDocument(long version, Scope scope, …)`, `MAX_BYTES = 1 << 20` at `:54`, `encode`/`decode` at `:86`, `:110`).
- Signed and versioned through the CP's config envelope, signature and anti-rollback rules (`control-plane.md` §5.3, §5.8, §12.3). Nodes keep the last good set when the CP is unreachable (`control-plane.md` §5.8).
- Publish requires a new `GRAPH_PUBLISH` permission in `AgentPolicy` (not in core `Permission`, consistent with swarm §6). Publish is audited in `controlplane.AuditLog`.
- Standalone (no CP): graphs are loaded from a local directory on startup, same schema, no signature (documented as such).
- At publish time the CP validates: schema, acyclicity except declared loops, every `service` and subgraph reference exists and is allowed by `AgentPolicy`, limits within server ceilings.

## 8. Module boundaries and the zero-cost check
- `sjws_nettask.swarm.graph` may depend on `swarm`, `swarm.wire`, `messaging.*`, `routing.GcraTable`, `observe`, `core`. Nothing outside `swarm.graph` imports it except one lazy, string-named hook in `SwarmModule` (the same pattern as `OptionalModules.SWARM_CLASS`, `src/sjws_nettask/OptionalModules.java:39`).
- **Dependency test:** extend the swarm guard (swarm §3.3 item 1) so no file in `swarm` (outside `swarm.graph`) or any core package contains `sjws_nettask.swarm.graph`, except that hook's string constant.
- **Class-load test:** clone `tests/sjws_nettask/swarm/ClassLoadTest.java` (it asserts `assertFalse(text.contains(SWARM))` at `:57`) to assert that `sjws_nettask.swarm.graph.` is absent (a) with the swarm off, (b) with the swarm on and graphs off, in standalone and in `data`/`gateway` mode (as `tests/sjws_nettask/swarm/RoleClassLoadTest.java`). With graphs on and research mode off, the P1–P3 classes (G2b) load, but no `swarm.graph.research.*` class may load.
- **Off-mode wrk A/B** (§12, item 4).

## 9. Phases (fit with S2+)
**G0 starts only after the S1c gate is met** (reaffirmed 2026-10-13); every graph phase therefore starts after the **S1c gate** (claim p99 < 100 µs with workers = vCPU count, heartbeat < 2 %, request/reply p99 < 500 µs; inceptor; see swarm §13) and depend on swarm phases as listed.

| Phase | Deliverable | Depends on | Exit criteria |
|---|---|---|---|
| **G0** | This doc approved; JSON Schema for graph docs; dependency + class-load guards for `swarm.graph` (empty package, lazy hook) | S1c gate; Blivion approval | Guards green; off-mode wrk A/B within A/A band |
| **G1** | Single-node runner, `S = 1..n` shards: static DAG, `all` join, event log + checkpoints + resume, step retries/timeouts, runner-side result fencing, idempotency key, run start/get/cancel API, local-directory graph loading | S1 (`LeaseKeeper` + `extend`); S2 (cancellation, trace propagation) | Crash-resume tests (kill runner at every event type, results identical); stale-result fencing test; duplicate-submit test; per-hop latency and 1k fan-out targets (§12) met or recorded |
| **G2** | Conditional edges (JSONPath subset, §3.4), `any`/`quorum`, bounded loops, map steps, subgraphs, deadline inheritance, recursive cancel, per-edge spans | G1 | Loop-bound and spawn-bound tests; join race replay test; cancellation reaches all descendants within one runner tick; trace for a 3-level subgraph |
| **G2b** | P1–P3 as general graph features ([swarm research mode](swarm-research-mode.md) §5.2): P1 loop-carried state (`$.carry`, reducers), P2 built-in `select` step, P3 dynamic counts | G2 | 1. **Reducer bounds and byte limit.** Each reducer (`replace`, `append(max_len)`, `merge`, `top_k(by, k)`) has a unit test at its bound: `append` at `max_len`, and `top_k` with ties broken deterministically (by `by`, then digest). A `carry_update` that would exceed the declared byte limit or fail the declared schema is **rejected**: the step fails with `carry-limit` / `carry-schema`, carry is unchanged, and no `CarryUpdated` is written. A graph whose declared carry limit is above the server ceiling is refused **at publish time**.<br>2. **`select` replays from recorded draws.** Golden-log test: a fixed event log maps to the expected population at every iteration. Replay test with a **deliberately different seed/PRNG** (inject a PRNG that throws if called during replay): the rebuilt population equals the one recorded in `Selected{kept, dropped, draws}`, and no draw is re-generated. A `Selected` event that conflicts with the recorded inputs fails resume loudly rather than silently re-selecting.<br>3. **Expression counts stay bounded.** `count: "$.plan.n"` resolving above `max_items` fails the step (no truncation), as do resolving to a negative number, a non-integer or `null`; the resolved count is recorded in `Spawned{n}` **before** any child is dispatched (assert the event order). `repeat: R` above `max_items` is refused at publish. A condition-exit loop whose condition never becomes false stops at exactly `max_iterations`, taking `on_exhausted` or failing with `loop-exhausted`. Publish refuses a loop edge without `max_iterations` and a map without `max_items`, including when the count is an expression.<br>4. **Class-load check** (§8). A forked JVM with `swarm.enabled=true`, `swarm.graphs.enabled=true` and research off runs one graph that uses P1–P3: the P1–P3 classes are loaded (positive control) and no `sjws_nettask.swarm.graph.research.` class is loaded. Research on with graphs off is refused at config load (exit 64).<br>5. **Carry checkpoint and restore.** Kill the runner (a) after `CarryUpdated` and before the next `RunCheckpoint`, and (b) right after a `RunCheckpoint`: on resume, carry is byte-identical (same digest) to an uninterrupted run, and no reducer is applied twice. A blob-referenced carry above the inline limit restores via its digest. The checkpoint/retention floor never frees the blob of the current carry digest. |
| **G3** | Budgets per run and **per-graph window** on `BudgetLedger`, GCRA run admission, backpressure (lag-based), Python SDK builder + run API, `GraphDocument` in the CP (signed, versioned, pinned runs), `GRAPH_PUBLISH` | S2 (`BudgetLedger`, `AgentPolicy`) ; CP document plumbing | Budget overshoot within swarm §8 bound per scope; 10k concurrent-run target (§12) met or recorded; version pinning test across a publish |
| **G4** | Agent-requested spawn (deferred here from the model; map steps only before G4); TUI run view; OTLP export of run/edge spans | G3; S4 (OTLP exporter) | Spawn limits tests; TUI drill-down |
| **G5** | Multi-node: shard assignment by the CP, shard failover | Messaging network protocol + replication (swarm §4.8 gap 9); HA | Shard failover test without lost or duplicated accepted results |

## 10. Risks
| Risk | Mitigation |
|---|---|
| Pool claim contention dominates large fan-outs (458 µs p99 today, §5.1) | S1c gate before G0; `concurrency` per map step; multi-partition pools (gap 5) |
| Runner shard becomes a hotspot (one hot graph) | Shard by `runId`, not graph; `max_active_runs_per_shard`; per-graph GCRA |
| Shard log grows with long runs | Checkpoints + retention (§4.3); outputs by reference |
| Duplicate external effects | Idempotency key per step attempt; stated caveat (§4.4) |
| Runaway cost from loops/spawns | Mandatory `max_iterations`, `max_items`, `max_spawn`, `max_steps_per_run`; static worst case shown at publish (§5.4) |
| Producer-id expiry breaks runner dedup | Idle-time TTL > max run duration + max step timeout + skew margin; stable runner ids (§4.4, §11.3); messaging gap 8 |
| Workers and runner contend on the shard partition lock | Small at today's rates per Messaging Core; watch runner group lag; add shards |
| Graph layer leaks into swarm or core | §8 guards fail the build |
| Fixed shard count limits growth | Decided for now (§11.1); size `S` for expected peak; resharding deferred |

## 11. Open questions
### 11.1 Decided (2026-10-13, Blivion via inceptor)
1. **Shard count:** fixed per deployment for now; online resharding is deferred (§5.1).
2. **Join losers:** losing `any`/`quorum` branches are cancelled by default; `cancel_losers: false` opts out (§3.3).
3. **Dynamic fan-out:** map steps only; agent-requested spawn is deferred to G4 (§3.6, §9).
4. **Conditions:** a JSONPath subset (RFC 9535 based, defined in §3.4), not CEL.
5. **Budgets:** a per-graph window budget ships in G3, alongside per-run and per-agent budgets (§5.4, §9).

No inceptor/Blivion questions remain open.

### 11.2 Open

None. Messaging Core answered every question (Messaging Core, [#111 review](https://github.com/BlivionIaG/sjws_nettask/pull/111#pullrequestreview-5503546631)); see §11.3.

### 11.3 Decided (2026-10-13, Messaging Core)
1. **Results:** workers write results directly into the run's shard topic; the fenced reply-then-ack path (`TaskService.reply`) works unchanged with `replyTo` set to the shard topic. This saves one append and one runner copy per step. Untrusted workers are excluded through rights, not topology (§4.1, §4.2).
2. **Topics:** one topic per shard for decisions and results, giving a single total order; split only if runner group lag shows starvation, and then add shards (§4.2).
3. **Producer-id expiry:** on idle time, `> max_run_duration + max step timeout + clock-skew margin`, evaluated from ledger timestamps; runner producer ids are stable per shard (§4.1, §4.4).
4. **Lag polling:** cheap (about 2.6 µs, measured by Messaging Core); read `lagMessages` from the published snapshot, not `counts()`, rate-limited to about once per ms or per poll batch (§5.3).
5. **Retention:** history below a checkpoint can be freed while runs are active if the runner exposes its floor as an explicit retain-from pin, with snapshot → checkpoint → retention ordering (§4.3).

## 12. Benchmark plan
All runs follow swarm §13 methodology: machine and commit reported, ≥ 5 alternating runs, mean ± stddev, load generator and server pinned to disjoint CPUs.

**Known numbers (measured by others, not by this doc):**
- 16-worker claim p99 **458 µs** after #103 (Messaging Core).
- Request/reply p99 **about 409–882 µs** depending on load, after #105/#108 (Messaging Core / Architecture S1c runs).
- S1c gate **targets**: claim p99 < 100 µs, request/reply p99 < 500 µs (swarm §13; inceptor).

**Targets** (pass thresholds, single node, to be measured in G1–G3):
| Workload | What is measured | Target |
|---|---|---|
| 1. Per-hop latency | Linear chain of 10 no-op steps, in-process agents; added latency per hop vs a plain swarm request/reply | p99 per hop ≤ request/reply p99 + one shard append; absolute p99 per hop < 1 ms |
| 2. 1k fan-out | One map step over 1,000 items, no-op agents, `all` join; time from dispatch to join, and runner CPU | All 1,000 results joined, zero duplicates accepted; join completes within 2× the time to drain 1,000 tasks through the pool alone |
| 3. 10k concurrent runs | 10,000 active runs of a 5-step DAG with agents sleeping a fixed delay; steady-state throughput, RSS, bytes per active run, step dispatch p99 | Sustained without errors or unbounded growth; dispatch p99 < 10 ms; bytes per active run reported |
| 4. Off-mode zero cost | (a) class-load tests (§8); (b) `wrk -t4 -c128 -d30s --latency` and `wrk2 -R` on a 1 KiB static file, master vs branch with swarm off, and with swarm on/graphs off | (a) no `swarm.graph` class loaded; (b) within the measured A/A spread |
| 5. Resume | Kill the runner with 10k active runs; time to rebuild from checkpoint + tail | Report; target < 5 s (same as swarm §13 recovery target) |
| 6. Fault | Kill workers mid-step; stale result injection | Exactly one accepted result per step instance in every run |

The 1 ms, 2×, 10 ms and 5 s values are proposals for review, not measurements, and may be revised once S1c numbers settle.
