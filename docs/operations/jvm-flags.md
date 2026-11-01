# Recommended JVM flags (profiles)

Operator-facing consolidation of measured GC and footprint guidance. **Default process GC stays G1** until inceptor approves otherwise. Numbers below are copied from the linked reports — nothing here is estimated.

Sources:

- [GC proposal + results](../performance/gc.md) (JDK 25.0.4.1, master `f60f08b`, 3 reps; raw: [gc-results-raw.txt](../performance/gc-results-raw.txt))
- [Footprint baseline](../benchmarks/footprint-report.md) (JDK Temurin 25.0.4.1+1, master `b8055fe` and idle-footprint A/B)
- Docker entrypoint in the repo-root `Dockerfile`: `-XX:+UseContainerSupport -XX:MaxRAMPercentage=75`

Pending sister measurements (placeholders only — do not treat as recommendations until numbers land): jlink image size/RSS vs full JRE (#370), Compact Object Headers / JEP 519 (#386), StackChunk tracking / harness CI (#387), CDS/Leyden, CPU-isolated GC gate.

## Profile: default HTTP / content node

Copy-paste (matches the footprint harness and typical local benches):

```text
-Xms256m -Xmx256m -XX:+UseG1GC
```

Optional when diagnosing native growth:

```text
-XX:NativeMemoryTracking=summary
```

From the footprint report (same flags): median startup ≈ 168 ms; idle RSS ≈ 66 MiB at 0 conns; ≈ 21.7 KiB RSS/conn at 10k idle keep-alives (baseline SHA in that report). Throughput on a shared box is noisy — use the report’s medians, not a single wrk run.

## Profile: latency / messaging-fetch / swarm-sensitive node (opt-in)

Keep G1 as the product default. For nodes where GC pause tails dominate (see gc.md messaging-fetch and reqreply max-pause columns), opt into **pinned generational ZGC**:

```text
-XX:+UseZGC -Xms1g -Xmx1g -XX:SoftMaxHeapSize=768m
```

Measured (gc.md medians, n=3): ZGC pinned max GC pause ≈ 0.04–0.05 ms on messaging produce/fetch vs ≈ 4–8 ms on G1; messaging-fetch p99.9 ≈ 266 µs vs ≈ 1004 µs on G1; produce throughput ≈ 96% of G1. **Unpinned** ZGC grew RSS to ≈ 1.19 GiB and lost ≈ 14% produce throughput — pinning matters.

## Profile: small control-plane / footprint node (opt-in)

```text
-XX:+UseSerialGC -Xmx256m
```

Measured (gc.md): lowest RSS under the HTTP load block (≈ 166 MiB vs ≈ 260 MiB G1); idle RSS similar to G1; tail latency worse (max pauses ≈ 6–10 ms). Use when RSS beats latency.

## Profile: container (Docker image)

The image entrypoint already sets:

```text
-XX:+UseContainerSupport -XX:MaxRAMPercentage=75
```

Do not change `MaxRAMPercentage` here without a re-measurement against the current image. Heap is sized from cgroup memory; pair with an explicit `-Xms`/`-Xmx` only when a profile above requires a pinned heap (for example ZGC).

## Profiles not recommended from current data

- **Tuned G1** (`-XX:+UseG1GC -Xms1g -Xmx1g -Xmn400m -XX:+ParallelRefProcEnabled`): fewer young collections but pauses still ≈ 7–8 ms and RSS roughly doubles (gc.md) — not worth it on this codebase’s measured workloads.
- Changing the **process default** off G1: rejected until inceptor approves; this page documents opt-in profiles only.

## Cross-links

- Bench etiquette and harness: [bench/footprint](../../bench/footprint/README.md), [docs/development/perf-gate.md](../development/perf-gate.md)
- Docker build notes: [deployment/docker.md](../deployment/docker.md)
- Command-line / env: [user/command-line.md](../user/command-line.md)

Do not merge until inceptor approves.
