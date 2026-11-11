# S2c PR 8 — streaming exit gate (N4)

Producer → node → consumer with **credit on**, over SSE and binary drains. Absolute targets are
`docs/design/agent-swarm.md` §13 token-streaming row. Relative ABBA rules (inceptor / Architecture):

- report p50, p90, p99 and throughput
- ≥ 20 s measure after 5 s warmup
- A/A control block (same jar both arms) for the harness noise floor
- hard gate: ≥5 and ≤8 valid windows (inceptor amendment 2026-10-29 23:59); info ABBA 3–8 blocks; invalid if load1 ≥ 4 at any pre-leg check or base p99 ≥ 500 µs
- FAIL only if sign-flip p < 0.05 **and** geomean(head/base) > 1.10
- assert `sjws_swarm_stream_node_refusals_total == 0` (and report the other stream counters)

## Quick start (after questions are resolved)

```sh
export JAVA_HOME=~/jdk25/jdk-25.0.4.1+1
export PATH="$JAVA_HOME/bin:$PATH"
mvn -B -q -DskipTests package
# Outer flock holds the shared-box lock; SKIP_FLOCK=1 prevents gate.sh from re-acquiring it.
flock /tmp/sjws-bench.lock nice -n 10 \
  env SKIP_FLOCK=1 SECS=20 WARMUP=5 NVALID=6 \
  bench/swarm-stream/gate.sh /path/to/base.jar /path/to/head.jar /tmp/s2c-pr8-out
```

### Bench lock (`/tmp/sjws-bench.lock`)

`gate.sh` and `cross-check.sh` take an exclusive flock on `/tmp/sjws-bench.lock` unless
`SKIP_FLOCK=1`. When you wrap them in an outer `flock /tmp/sjws-bench.lock …` (the shared-box
rule), **always** set `SKIP_FLOCK=1` — otherwise the child waits forever on the lock the parent
already holds. `one.sh` never takes the lock; only those entry points do.

CPU pin: wrk / load gen on 0–1, server on 2–7. ZGC, `-Dsjws.swarm.pool.shards=4`, stream+credit on.
Counters: `/metrics` appendix only (Observability-reviewed `sjws_swarm_stream_*` names).

Off-mode class-load proof (credit / StreamStats stay unloaded when credit is off): see
`ClassLoadTest` and, when new classes are added on the credit-off path, attach a
`-Xlog:class+load` excerpt from a flag-off rr run to the PR body.


## Generator CPU / self_loaded (inceptor 2026-10-30 18:46)

A window is **valid only if** the generator's CPU stays at or below **80% of its cores**
(`self_loaded` when `client_cpu_pct_of_wall > 80 × gen_cores`, or `load1_post ≥ 4`).
`agg.py` recomputes this from `result.json` (does not trust a stale `self_loaded` flag alone).

| Mode | Gen CPUs | Server CPUs | `GEN_CORES` | Sat limit |
|------|----------|-------------|-------------|-----------|
| (A) default | `0-1` | `2-7` | 2 | 160% of wall |
| (B) fallback (pre-approved if (A) cannot unsat 1k) | `0-2` (multi-process OK) | `3-7` | 3 | 240% of wall |

(B) is a **disclosed rule change** for #299 / this gate when used. Thresholds (§13) unchanged.
`StreamLoad` lean keep-alive HTTP/1.1 POST (`--lean-http`, default on) is harness-only.


## Agents

`StreamHttp.MAX_SESSIONS_PER_AGENT` is 64, so the harness mints `NAGENTS` (default 20) bearer
agents in `one.sh` and round-robins them in `drive.py` to reach 1k concurrent streams.

Metric names follow the sjws Observability review (`sjws_swarm_stream_*`, counters with `_total`,
flag-off emits nothing). Validate a scrape with `promtool check metrics`.

## Validity / NOISY (inceptor 2026-11-01 07:05)

- Start gate: `load1 < 3` before **every** leg (`gate.sh` / `cross-check.sh`), recorded as `load1_pre`.
- During a leg: `foreign_cpu.py` samples every 5 s. A leg is **NOISY** if foreign processes
  (not the bench server/generator/harness tree) average **> 0.5 core**, or any sample shows a
  foreign `java`/`mvn`/load-gen process, or **any** sample (including end-of-leg) has `load1 >= 6`.
- Absolute-window validity is one shared rule: `window_valid.py` (used by `agg.py` and `gate.sh`).
- `agg.py` and `gate.sh` discard NOISY legs (never counted). A/A remains mandatory in `PHASE=aa|all`.
- On completion `gate.sh` writes `$OUT/DONE` (`DONE swarm-stream-gate <time> …`). Queue owners still
  append `DONE <item> <time> <PR comment URL>` to `/workspace/bench-queue.md`.


## Generator slim-down and pacing (#415) / profile (#414)

`gate.sh` now defaults `LOADGEN=java` (StreamLoad). Two generator changes, both harness-only:

- **Lean consumer** (`LEAN_CONSUMER=1`, default; `--lean-consumer`): for binary drains the `:stream`
  POST and the `replies` GET run on one raw keep-alive socket per consumer, de-chunked in place,
  frames decoded from one reused buffer, stamps parsed from bytes. `LEAN_CONSUMER=0` keeps the
  `HttpClient` consumer so the generator change can be A/B'd on its own. SSE keeps `HttpClient`.
- **Pacing** (`RATE_MIB_S=<aggregate MiB/s>`, `--rate-mib-s`; unset = closed loop as before):
  each stream sends one chunk per `streams × chunk_bytes / rate` seconds, random phase, late slots
  caught up (wrk2-style). The send stamp (data bytes 0–20) is unchanged and is what `p99_us`
  reports; a schedule stamp (bytes 20–40) gives `chunk_latency_co` (coordinated-omission view).
  `offered_mib_s`, `sched_late_pct` (sends > 1 ms behind their slot) and `sched_lag_max_us` are
  reported. Pacing is a **rule question for inceptor/Architecture** (see `PLAN-415-414.md`);
  validity rules are unchanged by it.
- `result.json` also carries the sparse latency histogram (`chunk_latency.hist`, LatBag buckets,
  `hist_sub_bits`) so windows from several generator processes ((B) split) can be merged exactly.

`profile-414.sh <jar> <out>` records JFR on the server (and the generator with `GEN_JFR=1`) for one
1k/64 window and writes `server-attribution.md` via `jfr_top.py`. It exits 5 when the window is not
valid: a profile from a self-loaded or NOISY window is not evidence. Never a gate window.
