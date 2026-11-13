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

A window is **valid only if** the generator's CPU stays at or below **80% of its cores**.
In mode B (`GEN_PROCS>1`), a leg is **self_loaded** if **any** generator process is above
80% of its own pinned core, **or** the summed generator CPU is above 80% × `GEN_CORES`
(240% for three cores), or `load1_post ≥ 4`. `result.json` records `client_cpu_pct_per_proc`
and the sum. `window_valid.py` / `agg.py` recompute this from those fields and `load1_post`
(Q4: they do not OR StreamLoad's stale pre-drain `self_loaded` flag).

HTTP 503s on claim / `:stream` (almost always `ledger writer budget exhausted`) are a
first-class metric: `http_503_responses` (every 503, including retries),
`http_503_claim_giveups` / `http_503_stream_giveups` (exhausted 15 retries),
`http_503_budget_exhausted`. They are measure-only (reset after warmup) and are not a
reason to raise the shipped writer budget or to cut the default 1000-stream floor.

| Mode | Gen CPUs | Server CPUs | `GEN_CORES` | Sat limit |
|------|----------|-------------|-------------|-----------|
| (A) default | `0-1` (`GEN_PROCS=1`) | `3-7` | 2 | 160% of wall |
| (B) fallback (pre-approved if (A) cannot unsat 1k) | `0-2` (`GEN_PROCS=3`) | `3-7` | 3 | 240% of wall |

(B) is a **disclosed rule change** for #299 / this gate when used. Thresholds (§13) unchanged.
`one.sh` starts `GEN_PROCS` StreamLoad processes, each pinned to one of `GEN_CPUS`, with
`streams/n`, `rate/n` and disjoint `--group-offset` ranges; `merge-gen.py` merges their
`result.json` (histograms combined at LatBag high edges, generator CPU summed, server CPU
taken from the last process only). The server pin is `3-7` so it never shares a core with
(B)'s 0-2 (the previous `2-7` default overlapped core 2). A leftover overlap is auto-shifted.
`StreamLoad` lean keep-alive HTTP/1.1 POST (`--lean-http`, default on) is harness-only.


## Agents

`StreamHttp.MAX_SESSIONS_PER_AGENT` is 64, so the harness mints `NAGENTS` (default 20) bearer
agents in `one.sh` and round-robins them in `drive.py` to reach 1k concurrent streams.

Metric names follow the sjws Observability review (`sjws_swarm_stream_*`, counters with `_total`,
flag-off emits nothing). Validate a scrape with `promtool check metrics`.

## Disk bounds (`one.sh`)

A 1000-stream leg writes about **3.5–4 GB** of swarm segment logs (~900 ledger topic
directories; derived from the 61 G that filled `/workspace` across 16 shared smoke2/smoke3
leg dirs before the logs were deleted). `one.sh` therefore:

- Wipes leftover `result.json` / `gen-*.json` / `$WD/swarm` **before** a leg starts, so a
  failed generator cannot be judged from a prior run's file (and so ledgers do not append
  onto a leftover dir).
- Deletes `$WD/swarm` as soon as the leg ends (the EXIT trap, after `amend-result.py` and
  `metrics.txt`). Only metrics and the result JSON are kept.
- Refuses to start a leg if `/workspace` has less than **20 GiB** free, and aborts a running
  leg that drops below **10 GiB**.
- Caps the bench server's segment size at 16 MiB via `-Dsjws.messaging.segment.bytes`
  (override with `SJWS_MESSAGING_SEGMENT_BYTES`). Swarm `Topics` honours the same property;
  the product default is still 1 GiB. Swarm partitions have no retention trimmer, so peak
  disk is bounded by the post-leg delete.
- `merge-gen.py` refuses empty or non-JSON `gen-*.json` and does not write `-o` on failure.

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
  caught up (wrk2-style). Each chunk carries its actual send stamp (data bytes 0–20) and its
  scheduled send time (bytes 20–40). **Paced windows report `p99_us` from the scheduled send time**
  (`latency_basis: "scheduled"`, inceptor Q1 2026-11-11), so a stalled producer cannot hide backlog;
  closed loop keeps the send stamp (the two are equal there). `p99_send_us` / `p99_sched_us` and
  `chunk_latency_send` / `chunk_latency_sched` are always reported side by side, with
  `offered_mib_s`, `sched_late_pct` (sends > 1 ms behind their slot) and `sched_lag_max_us`.
  Validity rules are unchanged.
- **Gate-rule change (disclosed on PR 8 / #299 and #502; Q2 per inceptor's ruling 2026-11-11 ~23:44, Architecture agrees; Architecture sign-off pending):**
  the bar moves from p99 at closed-loop saturation to p99 at a fixed 110 MiB/s offered operating point.
  - `gate.sh` runs abs and A/A windows paced at **exactly** `GATE_RATE_MIB_S=110`. `agg.py` rejects abs/A-A
    windows at any other offered rate or not on the scheduled basis.
  - It passes on median achieved ≥ 100 MiB/s and scheduled-basis p99 < 1 ms. Guards are unchanged.
- **Window accounting:**
  - Chunks are counted by slot (scheduled) time inside the window, and consumers keep receiving through the
    drain cutoff.
  - In-window slots unsent or unreceived at cutoff are recorded at their age.
  - `result.json` reports these fields: `offered_actual_mib_s`, `sched_expected`, `sched_sent`,
    `sched_received`, `sched_unreceived_at_cutoff`, `sched_phantom_streams`, `sched_inflight_at_end`,
    `cutoff_hit`, `sched_stamp_fallback`, `sched_lag_p50_us` and `sched_lag_p99_us`.
  - Slot schedules are anchored at the wave epoch, so a stream whose claim returns late still owns a full
    window of slots and catches them up. Unmeasured slots (warmup, before the window) are skipped, never
    caught up.
  - `offered_actual_mib_s` = `sched_sends` (send starts of in-window slots) × chunk bytes / secs. It must be
    within 0.5 % of 110.
  - `agg.py` fails any slot shortfall, any stream short (`sched_streams_short`), `cutoff_hit`, a stamp
    fallback, or a generator saturated during catch-up (`catchup_cpu_pct_of_wall`) on a counted window.
  - A window with any shortfall shows `p99=n/a (shortfall N)`. The aged missing slots are only a lower
    bound.
- **Leg order:**
  1. `lowload-floor-N`, the low-load end-to-end floor at `FLOOR_RATE_MIB_S=10`. Its p99 must be ≤ 300 µs.
     An invalid floor leg is rerun, up to `FLOOR_TRIES`. It understates generator delay at 110.
  2. The `abs-NN` windows.
  3. The closed-loop info legs `cl-NN`, info only (`CLOSED_LOOP_INFO=0` skips them).
  4. A/A.

  Every leg first waits for load1 < 3 (`QUIET_WAIT_MAX_S` per leg, `QUIET_WAIT_TOTAL_S` total, exit 6).
- **Preconditions:**
  - `PHASE=all|abs` refuses to start without `CALIBRATION_REF`, the posted smoke plus calibration result.
  - Every DONE line records `harness=<commit>` and `calibration=<ref>`.
  - Q4 has landed (after #465 merged into master at 23f62b51): `Q4_LANDED` is True and
    `validity_marker` is `q4`. `agg.py` prints `PASS` or `FAIL`, never `NOT_COUNTABLE`.
  - Every `result.json` is stamped with `harness_commit` and `validity_marker`. Legs without the
    `q4` marker, or with a pre-q4 marker, never count.
  - A countable `harness_commit` is a full or short (7–40) hex sha. Empty, `unknown` and `-dirty`
    commits are rejected on abs, A/A and floor legs. DONE lines may still record `-dirty` for
    information; those windows never count.
  - `sched_late_registered` is the count of streams whose claim returned at or after the window start,
    reported on each window line.
  - `--assume-q4` is for tests only and prints `TEST_ONLY_PASS` / `TEST_ONLY_FAIL`.
- `result.json` also carries the sparse latency histogram (`chunk_latency.hist`, LatBag buckets,
  `hist_sub_bits`) so windows from several generator processes ((B) split) can be merged exactly.

`profile-414.sh <jar> <out>` records JFR on the server (and the generator with `GEN_JFR=1`) for one
1k/64 window and writes `server-attribution.md` via `jfr_top.py`. It exits 5 when the window is not
valid: a profile from a self-loaded or NOISY window is not evidence. Never a gate window.


## LEAN_CONSUMER=0 admit (`Lean0AdmitTest`)

The HttpClient consumer (`--no-lean-consumer`) logs the first claim and first `:stream`
response per pool (`first_admit`, `first_admit_warmup` in `result.json`: status, headers,
body snippet, elapsed_ms). On exit it `POST`s `:cancel` for the session so leftover
warmup sessions do not sit on the server until the 75 s drain floor.

`Lean0AdmitTest` is a 2-stream in-JVM dry check (test server subprocess, `StreamLoad.run()`
in the test JVM; not a load generator). It expects both lean0 and lean1 to send chunks,
with first claim 200 and first `:stream` 201. Build and run (outside the bench lock):

```
javac -d /tmp/lean0-admit StreamLoad.java Lean0AdmitTest.java
java -Dlean0.jar=path/to/sjws_nettask-*.jar -cp /tmp/lean0-admit Lean0AdmitTest
```

The 1000-stream closed-loop 503s are the shipped ledger writer budget (see issue #414);
this test does not raise that budget.
