# Control-plane benchmarks

`bench/controlplane/run.sh [options]` builds the jar and runs `cp.py`. You need JDK 25, Maven, python3, `wrk` and `wrk2`. The run reuses the helpers in `bench/footprint/` and writes `results/results.json`.

| Part | What it measures | How |
|---|---|---|
| (a) | config swap | `CpBench swap`: `ConfigSnapshot.compile` time for an N-route document, `LiveConfig.set` cost, and the time until 4 spinning reader threads see the new snapshot |
| (b) | compiled routing lookup | `CpBench route`: ns/op of `Router.handle` (a 301 redirect, response included) for the first, middle and last of N prefix routes |
| (c) | p99/p99.9 under pushes | `CpBench serve` with an N-route config. `wrk2 -R<rate> -c100 -t4` on `/1k.bin` (unmatched, so it scans every route and then falls back to static), first without pushes, then with a push every `--push-ms`. A push is `SjwsServer.applyRemote(doc)` when it exists (#42), otherwise `reload()` (re-reads the file) |
| (d) | idle data-node footprint | for each `--modes` value (`omit` = no `node:` key; `standalone`/`data` need #42), measures startup, RSS, threads, heap after GC and NMT total, and whether a `.last-good` file was written |

Options: `--routes 1000 --rate 50000 --duration 30 --push-ms 100 --modes omit[,standalone,data] --skip a,b,c,d`.
On master, run with `--modes omit`. To measure #42, copy this directory into a checkout of its branch.

## Results, 2026-10-13 (8-vCPU shared box, JDK 25, G1, 256m heap; single runs, wrk2 shares the box)

| | master `d592aaf` | #42 `cursor/config-model-d95d` |
|---|---|---|
| (a) compile, 1000 routes, p50 / p99 | 27.0 / 304 µs | 26.2 / 242 µs |
| (a) `LiveConfig.set` p50 / p99 | 194 / 459 ns | 149 / 488 ns |
| (a) visible to 4 readers p50 / p99 | 0.98 / 5.8 µs | 0.90 µs / 4.0 ms (scheduler/GC noise) |
| (b) route[0] / [500] / [999] | 618 / 3972 / 7702 ns | 730 / 3923 / 8872 ns |
| (c) no push: p50 / p99 / p99.9 | 1.79 / 23.2 / 43.6 ms | 2.47 / 50.5 / 100.7 ms |
| (c) push every 100 ms: p50 / p99 / p99.9 | 2.09 / 41.5 / 70.8 ms | 3.17 / 71.7 / 108.7 ms |
| (c) push apply p50 / p99 | 42.5 / 162.6 ms (`reload`, n=233) | 2.47 / 31.2 ms (`applyRemote`, n=327) |
| (d) heap after GC / RSS / threads, no `node:` | 4797 KB / 71804 KB / 22 | 4818 KB / 71876 KB / 22 |
| (d) `node.mode: data` | n/a | 4818 KB / 71692 KB / 23 |
| (d) `.last-good` written when standalone | no | **yes** |

Notes: (b) is a linear scan, about 6 to 8 ns per route skipped, so a 1k-route table has O(n) lookup. Every snapshot (including each push) gets a new `FileCache`. The two (c) columns are separate runs on a noisy box, so compare push against no push within a column.
