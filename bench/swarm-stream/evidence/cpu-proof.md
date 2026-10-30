# CPU proof + deciding 1-stream Java (S2c PR 8)

**Date:** 2026-10-30 Europe/Paris  
**Head:** `0cce0c0` (Architecture conditions 2026-10-30)  
**Harness sha256:**
```
dc1180299e42558e57b22632bf4f71ff8b5385df8dd56c2b8f65dfcb09c322af  bench/swarm-stream/drive.py
dc7300a0c1e59a97761bd41a6cb1dd7a66dfa52713eb2c13423dc77b9d602541  bench/swarm-stream/StreamLoad.java
a40548cfb19683f9a6cfddc0860677f3701994e3c0f126ba5376da9ff933df4c  bench/swarm-stream/one.sh
```

## Deciding measurement: 1-stream `LOADGEN=java` (fixed StreamLoad @ 0cce0c0)

**Run:** 2026-10-30T10:07:59+01:00 → ~10:10 Europe/Paris  
**Artifacts:** `gate-out/cpu-proof/java1-fixed-result.json`

| Metric | Value |
|--------|-------|
| `chunk_samples` | **25455** (≥10k ✓) |
| `mib_s` | **1.243** |
| `p50_us` | **942** |
| `p90_us` | **4813** |
| `p99_us` | **58484** (~**58.5 ms**) |
| `error_count` | 0 |
| `client_cpu_pct_of_wall` | 195.3 (generator_saturated / self_loaded) |
| `server_cpu_pct_of_wall` | 56.9 |
| refusals / underflows | 0 |
| post drain | `open_sessions=2`, `charged_bytes=1712186` (drain incomplete) |

### Verdict

**SERVER_ISSUE — STOP.** Fast-client (StreamLoad) 1-stream e2e p99 is **58.5 ms ≫ 1 ms**, with 25k samples and 0 measure errors. Per plan: do not proceed to abs / cross-check / ready.

Server still has CPU headroom (~57% of wall on CPUs 2–7) while p99 is tens of milliseconds → not explained by a pinned client alone.

JFR re-run: lock contended after the deciding run; profile capture retried separately (`java1-jfr.sh`). If `gate-out/cpu-proof/server-java1.jfr` is absent, capture on next quiet flock.

## Earlier saved proof (pre-fix java1 / py250)

- Affinity correct (client 0–1, server 2–7).
- py250 CPU series inconclusive / contaminated.
- Pre-`490abcf` java1 invalid (0 samples, stream-id parse bug).
- diag-1 Python 1-stream: p99≈7164 µs (context only).

## Architecture conditions 2026-10-30

Cited in #299. StreamLoad swap requirements (latency semantics, dual CPU + gen-sat self_loaded, cross-check, unchanged gate) remain in tree @ `0cce0c0`; **cross-check and abs not run** after SERVER_ISSUE stop.
