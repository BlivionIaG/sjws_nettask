# Swarm research mode (hill-climb and autoresearch on agent graphs): design

Status: **Draft, for Blivion's review.** Nothing is built before approval. Base: master `3cdaa4e`. Scope: design only, no code changes.
Owner: sjws Architecture. Builds on [agent-graphs.md](agent-graphs.md) (the "graphs doc", including its decisions in §11.1 and §11.3) and [agent-swarm.md](agent-swarm.md) (the "swarm doc").

Every reference to existing code is a path and line on master `3cdaa4e`. There is **no graph code on master yet** (`rg -il graph src/sjws_nettask` finds nothing), so references to graph features point at sections of the graphs doc, not at code. This doc contains **no measurements**; §12 lists targets, which are pass thresholds, not results.

## 1. Goal and the hard rule

Research mode is a **specific graph setup plus a run mode** for two kinds of long-running search:
- **Hill climbing (§3):** propose candidates, evaluate them against a declared objective, keep or revert, repeat. The shape of [FunSearch](https://www.nature.com/articles/s41586-023-06924-6) and [AlphaEvolve](https://deepmind.google/discover/blog/alphaevolve-a-gemini-powered-coding-agent-for-designing-advanced-algorithms/): LLM proposers plus automated evaluators plus a program database.
- **Autoresearch (§4):** plan, fan out research and experiment agents, synthesize, critique, form the next hypotheses. The shape of Karpathy's [autoresearch](https://github.com/karpathy/autoresearch) (agents iterating on a single-GPU nanochat training setup) and Sakana's [The AI Scientist](https://arxiv.org/abs/2408.06292).

sjws supplies the **orchestration, state, budgets, isolation hooks and audit**. Proposers, evaluators and researchers are ordinary swarm agents (swarm §2: no agent runtime in the server).

**HARD RULE: zero cost when off.** Research mode has its own gate, `swarm.graphs.research.enabled`, below `swarm.enabled` and `swarm.graphs.enabled` (graphs §1). With it off, no `sjws_nettask.swarm.graph.research.` class is loaded, no research document is parsed, and the graph runner has no research branch on its path (§10).

## 2. Non-goals
- **Writing the agents.** sjws ships no proposer, evaluator or research prompts; the SDK has templates only (§9.2).
- **A general ML experiment tracker** (MLflow-style UI, artifact browser). The leaderboard and lineage view in the TUI (§9.3) is the scope.
- **Running untrusted code inside the sjws JVM.** Evaluation runs in a separate sandbox process or container launched by an evaluator agent (§7); the server never executes candidates.
- **Guaranteeing that a found improvement is real.** The design reduces reward hacking and noise (§7.3, §3.2); it cannot prove generalization.
- **Unbounded search.** Every loop, population and spend has a declared maximum, consistent with graphs §2.
- **New consensus, storage or scheduling.** Everything rides on graph runs, swarm tasks and the messaging ledger.

## 3. Hill-climb loop

### 3.1 Shape
```
 seed ─▶ [propose ×P] ─▶ [evaluate ×P×R] ─▶ select ─▶ stop? ──no──▶ (back to propose, loop-carried population)
                                                         └─yes──▶ final held-out eval ─▶ report
```
- **Population / beam size `P`:** number of candidates proposed per iteration (beam). `P = 1` with greedy acceptance is classic hill climbing.
- **Proposal (mutation) agents:** a swarm service, e.g. `propose.v1`. Input: parent candidate(s) from the population, recent scores, and optional feedback; output: a candidate artifact (§6) plus a short rationale. Several proposer services can be weighted, as AlphaEvolve mixes models.
- **Evaluator agents:** a swarm service, e.g. `eval.v1`, that runs the candidate in a sandbox (§7) and returns metric values.

### 3.2 Objective declaration
```yaml
objective:
  metric: "$.metrics.throughput_rps"     # JSONPath subset (graphs §3.4)
  direction: maximize                    # or minimize
  repeats: 5                             # R evaluations per candidate
  aggregate: median                      # mean | median | trimmed_mean(p)
  noise: {min_effect: relative(0.02), test: none}   # or test: welch_t(alpha)
  constraints: ["$.metrics.errors == 0", "$.metrics.p99_ms < 50"]
  target: 120000                         # optional stop condition
```
- **Direction** is required; there is no default.
- **Noise handling:** each candidate is evaluated `R` times (with distinct recorded seeds), aggregated, and a candidate only **beats** the incumbent if the difference exceeds `min_effect` and, if `test` is set, a [Welch's t-test](https://en.wikipedia.org/wiki/Welch%27s_t-test) at `alpha`. The incumbent is **re-evaluated** with fresh repeats every `reeval_every` iterations, so a lucky early score cannot lock in (the "winner's curse" of selecting the max of noisy estimates).
- **Constraints** are hard: a candidate violating any is rejected regardless of score.
- Values for `R`, `min_effect`, `alpha` are the operator's; the doc fixes none.

### 3.3 Accept rules
The `select` step (new primitive P2, §5.2) applies one rule, recorded with its inputs in the run log:
- **Greedy:** keep the best `P` of (population ∪ new candidates) that beat their parent; otherwise keep the parent (revert).
- **Simulated annealing** ([Kirkpatrick, Gelatt, Vecchi 1983](https://www.science.org/doi/10.1126/science.220.4598.671)): accept a worse candidate with probability `exp(-Δ/T_k)`, with a declared schedule: `geometric(T0, alpha)` (`T_k = T0·alpha^k`), `linear(T0, T_end)`, or `log(T0)`. Random draws come from a PRNG seeded by `(runSeed, iteration, candidateIndex)`, so a replay makes the same choices; the outcome is also recorded, so resume never re-draws (graphs §4.2).
- **Tournament** ([Goldberg and Deb 1991](https://doi.org/10.1016/B978-0-08-050684-5.50008-2)): sample `k` candidates (seeded), keep the best; repeat `P` times. Optional **islands** (FunSearch's island model) as `I` sub-populations with periodic reset of the worst islands.
- Optional **elitism:** the best-so-far is never dropped from the population.

### 3.4 Stop conditions
Any of, checked after each `select`:
- `max_iterations` (required; it is the graphs §3.5 loop bound);
- **budget** exhausted (per-run or per-graph window, graphs §5.4, §11.1 item 5);
- **plateau:** no improvement of the best-so-far by `min_effect` for `plateau_window` iterations;
- **target score** reached (after confirmation repeats);
- wall-clock deadline (graphs §6.2) or operator cancel (graphs §6.1).

The reason is recorded in `RunFinished`.

## 4. Autoresearch loop

### 4.1 Shape
```
question ─▶ plan ─▶ map[hypotheses ≤ H] ─▶ (research | experiment) ─▶ join(all|quorum k) ─▶ synthesize ─▶ critique ─┐
                ▲                                                                                                   │
                └───────────── next hypotheses (loop-carried notebook) ◀── when: "$.critique.continue == true" ──────┘
```
- **plan** (`plan.v1`): turns the question plus the notebook (P1) into ≤ `H` hypotheses, each with a planned experiment or literature query.
- **research / experiment** (map step, graphs §3.6, decided map-only before G4, graphs §11.1 item 3): one instance per hypothesis; experiments run through the evaluator sandbox (§7).
- **join:** `all` by default; `quorum(k)` to move on once enough evidence arrived, with losers cancelled by default (graphs §11.1 item 2).
- **synthesize** writes a report section citing evidence ids; **critique** (a separate service, ideally a different model) scores it, lists gaps, and decides `continue`.
- The loop is a bounded loop edge with `max_iterations` (graphs §3.5); the exit condition uses the JSONPath subset.

### 4.2 Evidence and artifact store
- All artifacts (candidates, experiment outputs, logs, retrieved documents, reports) go to the **swarm-owned blob store** (swarm §5.2 and §16 item 2), **content-addressed** by SHA-256 of the bytes (as Git addresses objects, [Git internals](https://git-scm.com/book/en/v2/Git-Internals-Git-Objects)). The run log stores only digests (graphs §4.2: outputs by reference).
- Each artifact has a **provenance record** (in the run log, not the blob): `{digest, kind, producedBy: (runId, stepInstanceId, stepAttempt), agentId (cert subject, swarm §6), service@version, model id, inputs: [digests], seed, createdAt}`. The [W3C PROV](https://www.w3.org/TR/prov-overview/) entity/activity/agent model is the reference vocabulary; we store the minimal subset above.
- Synthesis must cite evidence digests; a citation to a digest not in the run's provenance is a sanity-check failure (§7.3).
- The blob store does not exist on master; it is a swarm deliverable (owner: swarm), and research mode depends on it (§11).

## 5. Mapping onto graph primitives

### 5.1 Existing primitives used (graphs doc)
| Need | Primitive |
|---|---|
| Outer iteration | Bounded loop edge with `max_iterations` (graphs §3.5) |
| P proposals, R repeats, H hypotheses | Map step with `max_items` and `concurrency` (graphs §3.6) |
| Wait for evaluations | `all` join; `quorum(k)` for autoresearch evidence (graphs §3.3) |
| Stop / continue | Conditional edges in the JSONPath subset (graphs §3.4, §11.1 item 4) |
| Crash resume | Event log + checkpoints per shard (graphs §4.2–§4.3) |
| Exactly-once scores | Runner result fencing + `(runId, stepInstanceId, stepAttempt)` (graphs §4.4) |
| Final held-out eval, report | Subgraph steps (graphs §3.6) |
| Spend caps | Per-run, per-graph window, per-agent budgets (graphs §5.4) |

### 5.2 New primitives (minimal)
Three, each a small extension of the graph runner (in `swarm.graph`, available to any graph, but only research mode needs them in R1). All are deterministic given the recorded log.

- **P1. Loop-carried state (accumulator).** A run-scoped JSON value `$.carry` declared with a schema and a byte limit; a step may return `carry_update` applying one of a fixed set of reducers: `replace`, `append(max_len)`, `merge`, `top_k(by, k)`. The runner applies it, writes a `CarryUpdated{digest}` event, and includes it in checkpoints. Large values live in the blob store by digest. Without P1 the loop body could only see the previous step's output. (Comparable: LangGraph [state reducers](https://langchain-ai.github.io/langgraph/concepts/low_level/#reducers).)
- **P2. Select step.** A built-in step executed by the runner, not dispatched as a task: input is the carried population plus the iteration's scored candidates; output is the new population per §3.3 (greedy, anneal, tournament, islands, elitism). It is pure, seeded and recorded (`Selected{kept, dropped, draws}`), so it costs no agent call and replays exactly.
- **P3. Dynamic iteration count with a declared max.** A map step whose item count comes from an expression (`count: "$.plan.n"`) or `repeat: R`, still bounded by `max_items`; and a loop edge whose exit is a condition, still bounded by `max_iterations`. Graphs §3.6 maps over a list; P3 only adds the count form and records the resolved count before spawning (as graphs §3.6 already requires).

Not added: agent-requested spawn (deferred to G4, graphs §11.1 item 3), general-purpose code in conditions, or cross-run state.

## 6. Experiment state

### 6.1 What is recorded
- **Best-so-far:** digest, aggregate score, confidence data (all repeats), iteration found, and the last re-evaluation.
- **Lineage DAG:** every candidate is a node `{digest, parents[], proposer service@version, model, iteration, scores[], accepted?}`; edges are parent → child. It lives in the run log (events) and is materialized in P1 carry (`top_k`) only for the active population. The TUI and SDK reconstruct the full DAG from events (§9).
- **Reproducibility manifest**, written at `RunStarted` and immutable: graph `name@version` (pinned, graphs §7.4), research document version (§9.1), proposer/evaluator service versions, model ids and decoding parameters as reported by the LLM gateway (swarm §10), evaluator image digest, sandbox profile, `runSeed`, dataset digests (train/validation/held-out).
- Caveat stated plainly: LLM outputs are generally not reproducible from a seed. Reproducibility here means **every decision can be replayed from recorded outputs** and **every evaluation can be re-run** on the recorded candidate with the recorded evaluator and seed.

### 6.2 Resume
A crash resumes from the graph checkpoint (graphs §4.3): population, carry, lineage, best-so-far and RNG positions are rebuilt from the checkpoint plus the log tail. In-flight evaluations are still tasks in their pools and complete normally; scores from stale attempts are fenced (graphs §4.4).

## 7. Evaluators

### 7.1 Sandboxed execution
- The evaluator agent launches each candidate in an isolated process or container: Linux namespaces + seccomp, or a container runtime with [gVisor](https://gvisor.dev/docs/) for stronger isolation. **No network by default** (an empty network namespace); allow-lists are declared per research document and audited.
- Resource limits per evaluation (CPU, memory, wall-clock, disk, process count) via cgroups v2 ([kernel docs](https://docs.kernel.org/admin-guide/cgroup-v2.html)); exceeding any is a failed evaluation, not a crash.
- Read-only inputs, a fresh scratch directory, and output collected only from a declared path, hashed into the blob store.
- sjws itself stays out of the sandbox: it only dispatches tasks and records results (§2).

### 7.2 Timeouts and harness hooks
- Per-evaluation timeout = the step timeout, capped by the run deadline (graphs §6.2). A timeout counts as a failed repeat; the objective declares whether failures score as worst value or are excluded with a minimum repeat count.
- **Harness hook:** an evaluator declares a command (`harness: {cmd: [...], metrics: path/to/metrics.json}`). For sjws's own performance work this can wrap the existing bench harnesses in [`bench/`](../../bench/README.md), e.g. a wrk A/B as in swarm §13.

### 7.3 Anti-reward-hacking
Optimizing against an automated metric invites specification gaming ([Krakovna et al., DeepMind](https://deepmind.google/discover/blog/specification-gaming-the-flip-side-of-ai-ingenuity/); [Amodei et al. 2016, "Concrete Problems in AI Safety"](https://arxiv.org/abs/1606.06565), §4 on reward hacking; Goodhart's law). Measures:
1. **Held-out evaluation:** a held-out set (digest in the manifest) that proposers never see and the search loop never scores against; it is used only for the final report and for promotion gates. Optional periodic held-out checks to detect overfitting (a widening gap between search and held-out scores raises an alert).
2. **Evaluator isolation from proposers:** distinct services, distinct agent identities under `AgentPolicy` (swarm §6): proposers have no `TASK_CLAIM` on evaluator pools and no read access to evaluator code, held-out data or evaluator artifacts; evaluators cannot write candidates. The evaluator image is pinned by digest.
3. **Sanity checks**, run by the evaluator before scoring: the candidate does not modify the harness or test files (hash check), output format and invariants hold, the run actually executed (minimum work counters), scores outside a declared plausible range are flagged, and suspiciously large jumps trigger re-evaluation on fresh seeds.
4. **Human review gates:** a `gate` step (a task to a human-review pool served through the SDK/TUI) before promoting a best candidate, and optionally every `N` accepted improvements. The run waits, bounded by a timeout (default: stop, not approve).
5. **Critique by a different model** in autoresearch (§4.1), and synthesis citations checked against provenance (§4.2).

## 8. Budgets and scale
- **LLM spend:** per-run and per-graph window budgets (graphs §5.4; per-graph window ships in G3, graphs §11.1 item 5) on swarm `BudgetLedger` (swarm §8; an S2 deliverable, not on master). The soft-cap overshoot bound of swarm §8 applies, with `F` = the run's `max_inflight_steps`. A research document can also cap **evaluation compute** (sandbox seconds) as a separate budget dimension in the same ledger.
- **Static worst case at launch:** `max_iterations × (P × proposer estimate + P × R × evaluator estimate)` (plus `H`-based terms for autoresearch), shown before the run starts, as graphs §5.4 does for loops.
- **Parallelism:** `concurrency` on the proposal and evaluation map steps; evaluator pools are sized by the operator. Rates use `routing.GcraTable` (`src/sjws_nettask/routing/GcraTable.java:31`, `admit` at `:90`) through the graph and agent rate tables (graphs §5.4).
- **Thousands of candidates:**
  - Many independent research runs shard by `runId` (graphs §5.1, fixed shard count, graphs §11.1 item 1).
  - Within one run, a large population is evaluated by **batched evaluation**: one evaluation task carries up to `B` candidates (digests), so per-task overhead is amortized, and results return as one step result with `B` score vectors. `max_items` still bounds the map.
  - Claim contention on the evaluator pool is the known limit today (16-worker claim p99 **458 µs after #103**, measured by Messaging Core, against the S1c target < 100 µs); batching reduces claims per candidate.
  - The lineage lives in the log; only the active population and best-so-far stay in runner memory.

## 9. Run-mode config and launch surface

### 9.1 CP document
`swarm.ResearchDocument`, a CP document **signed and versioned like `GraphDocument`** (graphs §7.5, which follows `controlplane.AccessPolicyDocument`, `src/sjws_nettask/controlplane/AccessPolicyDocument.java:50`). It names: the pinned graph `name@version` (a hill-climb or autoresearch template), the objective (§3.2), accept rule and schedule, `P`, `R`, `H`, stop conditions, budgets, evaluator sandbox profile and network allow-list, dataset digests, and review gates. Launching requires a new `RESEARCH_RUN` permission in `AgentPolicy` (not core RBAC, swarm §6). Runs pin the document version, like graph versions. Standalone mode loads it from a local directory, unsigned, as graphs §7.5.

### 9.2 Python SDK
```python
doc = HillClimb(
    graph="hillclimb@1",
    objective=Objective("$.metrics.throughput_rps", direction="maximize", repeats=5, aggregate="median"),
    population=8, accept=Anneal(T0=1.0, schedule="geometric", alpha=0.95),
    stop=Stop(max_iterations=200, plateau_window=20),
    proposer="propose.v1", evaluator="eval.v1", budget={"max_tokens": ...})
await s.research.publish(doc)
run = await s.research.start(doc.name, seed=1234)
async for ev in run.events(): ...     # Proposed / Scored / Selected / BestImproved / Finished
await run.lineage(); await run.best(); await run.cancel()
```
It builds documents only; `s.serve(...)` (swarm §11.1) is unchanged for agents. Templates for proposer/evaluator agents ship as examples, not as server code.

### 9.3 Admin TUI
A research view: running experiments, iteration, best-so-far with confidence, a **leaderboard** (top candidates by aggregate score, with held-out score when available), spend vs budget, plateau counter, and pending review gates with approve/reject. Data comes from run events (graphs §6.3) via the admin API. Today's TUI has `enum View { STATUS, CONFIG, LOGS }` (`src/sjws_nettask/admin/AdminTui.java:42`); adding a view is the **sjws Admin TUI workstream's** work and is a dependency (R3).

## 10. Zero cost when off
- Package `sjws_nettask.swarm.graph.research`, loaded only when `swarm.enabled`, `swarm.graphs.enabled` and `swarm.graphs.research.enabled` are all true, through a string-named lazy hook as `OptionalModules.SWARM_CLASS` does (`src/sjws_nettask/OptionalModules.java:39`, loaded at `:73`).
- P1–P3 live in `swarm.graph` (they are graph features) but are inert unless a graph uses them; their cost when unused is checked by the graphs benchmarks (graphs §12).
- **Dependency guard:** nothing outside `swarm.graph.research` names it except the hook (extending graphs §8).
- **Class-load guard:** cloned from `tests/sjws_nettask/swarm/ClassLoadTest.java` (assertion at `:57`): no `sjws_nettask.swarm.graph.research.` class with (a) swarm off, (b) graphs off, (c) research off, in standalone and `data`/`gateway` mode (as `tests/sjws_nettask/swarm/RoleClassLoadTest.java`).
- **wrk A/B** off-mode check (§12).

## 11. Phases
| Phase | Deliverable | Depends on | Exit criteria |
|---|---|---|---|
| **R0** | This doc approved by Blivion; research gate + dependency/class-load guards (empty package) | G0 (which starts only after the S1c gate) | Guards green; off-mode wrk A/B within the A/A spread |
| **R1** | P1 carry, P2 select (greedy, anneal, tournament), P3 dynamic counts; hill-climb template graph; objective with repeats and noise rule; stop conditions; lineage events; reproducibility manifest | G2 (loops, map, conditions, joins); swarm blob store | Replay test: identical decisions from the log; resume test mid-iteration; seeded anneal determinism; plateau/target/max-iteration stop tests |
| **R2** | Evaluator sandbox reference agent (namespaces/seccomp or gVisor, no network), harness hook, sanity checks, held-out eval, `AgentPolicy` isolation between proposers and evaluators | R1; S2 (`AgentPolicy`, agent CA) | Sandbox escape-attempt tests (network, harness modification) fail closed; isolation policy tests |
| **R3** | `ResearchDocument` in the CP, `RESEARCH_RUN`, budgets incl. eval compute, static worst case, Python SDK, TUI research view and leaderboard, human review gates | G3 (budgets, `GraphDocument`, SDK); Admin TUI workstream | Budget stays within swarm §8 bound; gate timeout defaults to stop; TUI shows leaderboard from events |
| **R4** | Autoresearch template (plan → map → join → synthesize → critique loop), evidence store provenance, citation checks | R3 | End-to-end autoresearch run with provenance for every cited digest |
| **R5** | Batched evaluation, islands, multi-node research runs | G5 (multi-node) | Thousands-of-candidates target (§12) met or recorded |

## 12. Benchmark plan
Methodology as swarm §13 (machine and commit reported, ≥ 5 alternating runs, mean ± stddev, CPU-pinned). Numbers measured by others: 16-worker claim p99 458 µs after #103 (Messaging Core); request/reply p99 about 409–882 µs depending on load after #105/#108; S1c targets claim p99 < 100 µs, request/reply p99 < 500 µs.

**Targets** (proposals for review, not results):
| Workload | Measured | Target |
|---|---|---|
| Orchestration overhead per iteration | Hill climb with no-op proposer/evaluator, `P = 8`, `R = 3`; time per iteration minus agent time | Report; target ≤ the graphs per-hop target × hops per iteration (graphs §12) |
| Select step cost | P2 on populations of 10, 100, 1,000 | Report; no allocation growth beyond the population size |
| Thousands of candidates | 1 run, 5,000 candidates evaluated with batching `B ∈ {1, 16, 64}`, no-op evaluator | All scored exactly once; claims per candidate fall with `B` |
| Many runs | 1,000 concurrent small research runs on one node | No errors; per-run memory reported |
| Resume | Kill the runner mid-iteration with 1,000 runs active | Identical final decisions to an uninterrupted seeded run |
| Off-mode | Class-load tests (§10) plus `wrk -t4 -c128 -d30s --latency` and `wrk2 -R` on a 1 KiB static file: master vs branch with research off | No research class loaded; within the A/A spread |

## 13. Risks
| Risk | Mitigation |
|---|---|
| Reward hacking / overfitting to the evaluator | Held-out set, evaluator isolation, sanity checks, review gates (§7.3) |
| Noisy metrics produce false improvements | Repeats, `min_effect`, optional t-test, incumbent re-evaluation (§3.2) |
| Sandbox escape or data exfiltration | No network by default, gVisor option, cgroup limits; sjws never runs candidates (§7.1) |
| Runaway spend | Required `max_iterations`, budgets, static worst case shown at launch (§8) |
| Blob store growth from thousands of candidates | Content addressing dedups; retention for non-lineage-relevant artifacts is an open question |
| LLM non-determinism limits reproducibility | Stated caveat; replay from recorded outputs (§6.1) |
| Dependencies slip (G2/G3, blob store, TUI) | Phases gated explicitly (§11) |
| New primitives creep into general graph complexity | Only P1–P3, fixed reducer set, no user code (§5.2) |

## 14. Open questions for Blivion
1. **Default accept rule:** greedy (simplest) or annealing? Proposed: greedy with elitism as default.
2. **Noise test:** is `min_effect` plus repeats enough by default, or should Welch's t-test be on by default?
3. **Human review gate:** required before promoting any result (proposed), or optional?
4. **Evaluator sandbox:** is gVisor acceptable as the reference, or namespaces + seccomp only to avoid a runtime dependency?
5. **Network allow-lists for evaluators:** allowed at all (e.g. for autoresearch literature fetches), or should retrieval be a separate, non-sandboxed research agent?
6. **Should P1–P3 be general graph features** (proposed), or research-only?
7. **Artifact retention:** keep every candidate forever, or only lineage ancestors of the top-k plus a time window?
8. **First target use case:** sjws's own performance tuning through the `bench/` harness, or an external workload?
