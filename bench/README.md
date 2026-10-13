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
```

- **Comparisons.** Compare `heartbeat off` and `heartbeat keeper` with 5 alternating runs pinned with `taskset`.
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
