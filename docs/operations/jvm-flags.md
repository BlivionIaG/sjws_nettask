# Recommended JVM flags (profiles)

Operator-facing consolidation of measured GC and footprint guidance. **Default process GC stays G1** until inceptor approves otherwise. Numbers below are copied from the linked reports — nothing here is estimated.

Sources (every figure on this page comes from one of these):

- **[GC]** [docs/performance/gc.md](../performance/gc.md) results table — run 2026-10-14 05:13–06:36 Paris, JDK 25.0.4.1, master `f60f08b`, 3 reps per config, medians; raw: [gc-results-raw.txt](../performance/gc-results-raw.txt). Command: `bench/gc/run.sh 3 g1 g1tuned zgc zgcpinned serial` then `python3 bench/gc/summarize.py bench/gc/results/results.txt`.
- **[FP]** [docs/benchmarks/footprint-report.md](../benchmarks/footprint-report.md) baseline — run 2026-10-12, Temurin 25.0.4.1+1, master `b8055fe`; raw: `bench/footprint/baseline/results.json`. Command: `bench/footprint/run.sh` (defaults). Note: this baseline predates the idle-footprint changes (A/B in the same report), so current per-connection figures are lower.
- **[Docker]** repo-root `Dockerfile` `ENTRYPOINT`: `-XX:+UseContainerSupport -XX:MaxRAMPercentage=75`.

Not measured (no figures on this page): jlink image size/RSS vs full JRE — see #370; Compact Object Headers / JEP 519 — see #386; StackChunk tracking / harness CI — see #387. CDS/Leyden and the CPU-isolated GC gate are also not measured.

## Profile: default HTTP / content node

Copy-paste (matches the footprint harness and typical local benches):

```text
-Xms256m -Xmx256m -XX:+UseG1GC
```

Optional when diagnosing native growth:

```text
-XX:NativeMemoryTracking=summary
```

Measured [FP] (same flags plus NMT): median startup 168.1 ms (5 runs); idle RSS 66,268 KB at 0 conns; 21,740 B RSS per idle keep-alive at 10,000 conns. Throughput on a shared box is noisy — use the report’s medians, not a single wrk run.

## Profile: latency / messaging-fetch / swarm-sensitive node (opt-in)

Keep G1 as the product default. For nodes where GC pause tails dominate (see gc.md messaging-fetch and reqreply max-pause columns), opt into **pinned generational ZGC**:

```text
-XX:+UseZGC -Xms1g -Xmx1g -XX:SoftMaxHeapSize=768m
```

Measured [GC] (medians, n=3): ZGC pinned max GC pause 0.04 ms on messaging produce and fetch vs 4.63 ms / 7.25 ms on G1; messaging-fetch p99.9 266 µs vs 1004 µs on G1; produce 217,221 vs 225,659 ops/s (96% of G1). **Unpinned** ZGC: RSS after the HTTP run 1,188,744 KiB (vs 615,940 KiB pinned) and produce 194,259 ops/s (−14% vs G1) — pinning matters.

## Profile: small control-plane / footprint node (opt-in)

```text
-XX:+UseSerialGC -Xmx256m
```

Measured [GC] (medians, n=3): lowest RSS after the HTTP run (166,220 KiB vs 260,312 KiB on G1); idle RSS 68,592 KiB vs 70,412 KiB on G1; max GC pauses 5.11–8.78 ms across benches (worse tails). Use when RSS beats latency.

## Profile: container (Docker image)

The image entrypoint already sets [Docker]:

```text
-XX:+UseContainerSupport -XX:MaxRAMPercentage=75
```

Container RSS with this setting: not measured, see #370. Do not change `MaxRAMPercentage` without a measurement against the current image. Heap is sized from cgroup memory; pair with an explicit `-Xms`/`-Xmx` only when a profile above requires a pinned heap (for example ZGC).

## Profiles not recommended from current data

- **Tuned G1** (`-XX:+UseG1GC -Xms1g -Xmx1g -Xmn400m -XX:+ParallelRefProcEnabled`): [GC] medians: reqreply young collections 14 → 4 but max pause still 7.75 ms (s1) / 7.22 ms (s4); RSS after the HTTP run 525,012 KiB vs 260,312 KiB on G1 — not worth it on this codebase’s measured workloads.
- Changing the **process default** off G1: rejected until inceptor approves; this page documents opt-in profiles only.

## Cross-links

- Bench etiquette and harness: [bench/footprint](../../bench/footprint/README.md), [docs/development/perf-gate.md](../development/perf-gate.md)
- Docker build notes: [deployment/docker.md](../deployment/docker.md)
- Command-line / env: [user/command-line.md](../user/command-line.md)

Do not merge until inceptor approves.
