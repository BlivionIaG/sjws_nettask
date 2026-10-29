# S2c PR 8 — streaming exit gate (N4)

Producer → node → consumer with **credit on**, over SSE and binary drains. Absolute targets are
`docs/design/agent-swarm.md` §13 token-streaming row. Relative ABBA rules (inceptor / Architecture):

- report p50, p90, p99 and throughput
- ≥ 20 s measure after 5 s warmup
- A/A control block (same jar both arms) for the harness noise floor
- ABBA with 3–6 valid blocks; invalid if load1 ≥ 4 at any pre-leg check or base p99 ≥ 500 µs
- FAIL only if sign-flip p < 0.05 **and** geomean(head/base) > 1.10
- assert `sjws_swarm_stream_node_refusals == 0` (and report the other stream counters)

**Open questions** that change pass/fail (ABBA arms, TTFT thresholds, “credit stall”, aggregation,
1k-stream vs load1) live in `/workspace/s2c-pr8/questions.md`. Do **not** run `gate.sh` until those
are answered.

## Quick start (after questions are resolved)

```sh
export JAVA_HOME=~/jdk25/jdk-25.0.4.1+1
export PATH="$JAVA_HOME/bin:$PATH"
mvn -B -q -DskipTests package
flock /tmp/sjws-bench.lock nice -n 10 \
  env SECS=20 WARMUP=5 NVALID=6 \
  bench/swarm-stream/gate.sh /path/to/base.jar /path/to/head.jar /tmp/s2c-pr8-out
```

CPU pin: wrk / load gen on 0–1, server on 2–7. ZGC, `-Dsjws.swarm.pool.shards=4`, stream+credit on.
Counters: `GET /swarm/v1/stream-stats` (Bearer token) or `/metrics` appendix when metrics are armed.

Off-mode class-load proof (credit / StreamStats stay unloaded when credit is off): see
`ClassLoadTest` and, when new classes are added on the credit-off path, attach a
`-Xlog:class+load` excerpt from a flag-off rr run to the PR body.
