# Perf-regression gate

`.github/workflows/perf-gate.yml` runs on pull requests that touch `src/**`, `pom.xml` or the gate
itself. It builds the PR base and head, starts both jars on the same runner in standalone mode
(default flags; no cluster/gateway mode exists on the CLI yet) serving a 1 KiB file, and benchmarks
them with wrk (`-t2 -c64`, keep-alive):

* 5 s warmup per server, then 5 rounds of 10 s, alternating base/head order each round,
* median requests/s and p99 latency per side,
* a metric fails when it regresses by more than `max(threshold, 2 x relative MAD)` of either side,
  so a noisy runner widens the band instead of flaking; any wrk errors on the PR side fail too.

Defaults: throughput 10 %, p99 20 % (tails are noisier on shared 2-vCPU runners). Override with the
repository variables `PERF_THRESHOLD` / `PERF_P99_THRESHOLD`. The table goes to the job summary and
raw results to the `perf-gate` artifact. Add the `skip-perf` label to skip a PR.

The whole run, with the second pass described below:

```mermaid
flowchart TB
    pr["A pull request that<br/>touches src/**, pom.xml,<br/>the gate or the sample<br/>config. The label<br/>skip-perf skips the job"] --> b["Build the base and the<br/>head jar on one runner"]
    b --> p1["Pass 1: the default<br/>command line"]
    b --> p2["Pass 2:<br/>--role=standalone with<br/>the sample config and<br/>the swarm forced off"]
    p1 --> r["Each pass: start both<br/>servers on a 1 KiB file<br/>with 256 MiB of heap,<br/>warm each up for 5 s,<br/>then 5 rounds of 10 s<br/>with wrk (2 threads, 64<br/>connections). Base and<br/>head alternate their<br/>order every round"]
    p2 --> r
    r --> c["compare.py: the median<br/>requests per second and<br/>the median p99. A metric<br/>fails when it is worse<br/>than max(threshold, 2 x<br/>its relative MAD): 10<br/>percent and 20 percent<br/>by default. Any wrk<br/>error on the PR side<br/>fails too"]
    c --> o["A table in the job<br/>summary and the raw<br/>results in the perf-gate<br/>artifact"]
```

Run locally:

    mvn -B -q -DskipTests package   # in two checkouts
    tools/perf-gate/bench.sh base.jar head.jar /tmp/perf
    tools/perf-gate/compare.py /tmp/perf/base.jsonl /tmp/perf/head.jsonl --threshold 10

The gateway-off mode is only reachable through the bench harness (`bench/GatewayBench.java`), not
the server CLI, so the A/B gate compares standalone builds. Standalone vs gateway-off within one
build is `tools/gateway-bench.sh`, run briefly in CI and at full length by
`.github/workflows/cluster-tests.yml` (nightly, `workflow_dispatch`, or the `cluster-tests` PR label),
together with the cluster harness (`mvn -B -Dgroups=cluster -Dsurefire.failIfNoSpecifiedTests=true test`).

## Second pass: explicit role

The gate runs the A/B twice. The second pass starts both builds with
`--role=standalone --config=contrib/sjws_nettask-standalone.yaml` and `-Dsjws.swarm.enabled=false`
(positional port and root still win over the file), so the role/config path is gated as well.
`--role=data` and `--role=control` are not benchmarked: both require `--bootstrap` with a control-plane
contact, pin and join token, which a single cheap runner pass cannot provide.
