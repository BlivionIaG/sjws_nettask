# Benchmarks

Small, dependency-free harnesses (not part of the Maven build, so they never affect `mvn verify`).

```sh
mvn -B -q -DskipTests package            # builds target/classes
# HTTP/1.1 keep-alive closed loop against an in-process SjwsServer
java -cp target/classes bench/HttpBench.java 64 10 1024    # connections seconds bodyBytes
# Standalone vs gateway-mode-off (wrk). Both leave the control plane detached.
# Official shape: warmup 5s, measure 30s, -t4 -c128, 1 KiB file. See tools/gateway-bench.sh.
tools/gateway-bench.sh
# Shorter run, and fail if gateway-off is outside ±10% of standalone:
# GATEWAY_BENCH_WARMUP=2s GATEWAY_BENCH_DURATION=10s GATEWAY_BENCH_MAX_DELTA=0.10 tools/gateway-bench.sh
# Append-only log write-path prototype
java bench/LogBench.java /tmp/logbench none  8 200000 256
java bench/LogBench.java /tmp/logbench each  8 20000  256
java bench/LogBench.java /tmp/logbench group 8 200000 256
```

Client and server share the machine (loopback); compare runs only on the same host.
Use a real local disk for `LogBench` (fsync on tmpfs/overlay can be a no-op or unrepresentative).

## Standalone vs gateway-off

`tools/gateway-bench.sh` starts `bench/GatewayBench.java` twice, role `standalone` and role
`gateway-off`. Both call `SjwsServer.start` with no control-plane object and no `HaSettings`.
That is the zero-cost path in the control-plane design
([PR #38](https://github.com/BlivionIaG/sjws_nettask/pull/38), `docs/design/control-plane.md`):
with the mode off, the process does not open `cp-config` and does not start a watch.
Each process binds port 0 and the script reads `bench port=<n>` from stdout.

Needs JDK 25, Maven, `curl`, and `wrk`. The measured command is:

```sh
mvn -B -q -DskipTests package
tools/gateway-bench.sh
```

Defaults: 5 s warm-up, 30 s measure, `wrk -t4 -c128 --latency` on a 1 KiB file, loopback.
The script prints req/s for each mode and `ratio gateway-off/standalone`. A ratio near 1
is the gate. Set `GATEWAY_BENCH_MAX_DELTA` (for example `0.10`) to fail the script when
the ratio leaves that band. CI runs a 1 s / 3 s smoke of the same script and fails on
request errors or zero throughput; it does not set the delta, because a 3 s sample on a
shared runner is too noisy for a tight band.

When `sjws_nettask.cp` grows a startup role, `gateway-off` in `GatewayBench` must select
standalone explicitly. The two wrk columns should stay in the same band.

`--mode=rate-limit` is separate. It installs the gateway factories and applies one
`rate-limit` document (`by=ip`, `buckets=4096`). `--capacity` (default `1000000`) and
`--refill` (default `1000000000`) set that document. `standalone` and `gateway-off` do not
call `chainControl`. Feature-off file serving is still `java -jar` with no chain.

## Swarm S1 (`SwarmBench`)

`bench/SwarmBench.java` measures the swarm against the targets in `docs/design/agent-swarm.md` §13. Everything runs on a single node and in-process, with the ASYNC ledger:

```sh
mvn -B -q -DskipTests package
java -cp target/classes bench/SwarmBench.java /tmp/sb dispatch 16 5          # claim(1)+fenced ack, 1 KiB, prefilled
java -cp target/classes bench/SwarmBench.java /tmp/sb heartbeat off 20       # dispatch with no keeper
java -cp target/classes bench/SwarmBench.java /tmp/sb heartbeat keeper 20    # + LeaseKeeper: 10k leases, batched tick every 5 s
java -cp target/classes bench/SwarmBench.java /tmp/sb reqreply 10000 10      # ReplyRouter.call, open loop at 10k/s
java -cp target/classes bench/SwarmBench.java /tmp/sb reqreply-mw 4x10000 10 # same, 4 workers on the pool (variant, not the gate)

`reqreply` and `reqreply-mw` print `n=` (completed calls), `ok=`, `failed=` and `missing=`; percentiles are over completed calls only, and a run with any failed or missing call prints `INVALID` and exits 3. For `reqreply-mw`, keep `W <= vCPU - 1`: the open-loop generator spins on one core, so more workers oversubscribe the box (as 16-on-8 does for dispatch).
```

- **Comparisons.** Compare `heartbeat off` and `heartbeat keeper` with 5 alternating runs pinned with `taskset`.
- **Per-second counts (opt-in).** `-Dsjws.bench.persec=true` makes `heartbeat` also print `persec=[c0,c1,...]`: completions in each wall-clock second from the start of the 2 s warm-up (`c0`-`c1` are warm-up; the last bucket is a spare and is usually 0). The default output is unchanged. The #234 heartbeat gate used it with a 20% trimmed mean over s7-31 of 30 s runs; any longer warm-up is cut from `persec` (`-Dsjws.bench.hbWarmup` is refused).
- **Request/reply latency.** It is measured from each call's scheduled send time, so a stall also counts against the calls queued behind it.
- **Results.** The S1 PR body has the numbers measured on the dev box.

## Partition log over `/messaging/v1` (`MessagingLogBench`, `messaging-http/run.sh`)

These are the produce/fetch benches for plain log topics (docs/design/partition-log.md R11). `MessagingLogBench` drives the public handler in-process, without the network:

```
mvn -q compile
java -cp target/classes bench/MessagingLogBench.java /tmp/mlb produce 1024 4 10          # writer default (async)
java -cp target/classes bench/MessagingLogBench.java /tmp/mlb produce 1024 4 10 group    # fsync opt-in
java -cp target/classes bench/MessagingLogBench.java /tmp/mlb fetch   1024 4 10
```

`messaging-http/run.sh <jar> [sizes]` runs the same operations end to end over HTTP with wrk (default sizes are 100 B, 1 KiB, 16 KiB and 256 KiB). Run every bench as `flock /tmp/sjws-bench.lock …`, only when the 1-minute load is under 4, and record that load next to the numbers.

## Replicated publish on 3 voters (`RaftPublishBench`)

Three in-process voters on loopback `TcpTransport`, memory logs, `RELAXED`, background Raft loops (1 ms tick).

```sh
mvn -q -DskipTests package
java -cp target/classes bench/RaftPublishBench.java publish 4 10            # closed loop: ops/s, p50/p99/p99.9 append→future
java -cp target/classes bench/RaftPublishBench.java overload 4 10 200       # 200 µs callbacks, open loop (512 outstanding per producer)
```

`overload` samples `deliveryLag`/`maxDeliveryLag` every 200 ms and counts `ApplyLagException`. It reads the metrics by reflection, so the same file runs on commits from before #182, where it prints `n/a`. Pin with `taskset` and run it under the bench lock like the others.

## Swarm S2c stream gate (`bench/swarm-stream`)

Credit-on producer→node→consumer over SSE and binary for the §13 token-streaming exit row (S2c PR 8).
See [`bench/swarm-stream/README.md`](swarm-stream/README.md). Counters: `/metrics` appendix (`sjws_swarm_stream_*`).

