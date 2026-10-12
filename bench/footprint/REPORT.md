# Footprint baseline — master `b8055fe`

All numbers below were measured by `bench/footprint/run.sh` (defaults) on 2026-10-12; raw data in
`baseline/results.json` / `baseline/results.csv`. Nothing is estimated.

**Box:** shared cloud VM, 8 vCPU, 15 GB RAM, no swap, Linux; client and server on the same host (loopback).
**JDK:** Temurin 25.0.4.1+1 LTS. **Load tool:** wrk 4.x. JVM flags: `-Xms256m -Xmx256m -XX:+UseG1GC -XX:NativeMemoryTracking=summary`.

## Startup (spawn → first 200 on /1k.bin), 5 runs

| median | min | runs (ms) |
|---|---|---|
| 168.1 ms | 147.3 ms | 177.7, 168.1, 154.8, 204.6, 147.3 |

## Idle footprint

| idle conns | RSS | heap used after GC | NMT committed total | threads |
|---|---|---|---|---|
| 0 | 66,268 KB | 2,778 KB | 352,773 KB | 28 |
| 1,000 | 92,272 KB | 9,990 KB | 348,546 KB | 36 |
| 10,000 | 278,568 KB | 67,819 KB | 348,805 KB | 38 |

NMT committed at 0 conns: Java Heap 262,144 KB (fully committed by `-Xms`), GC 54,722 KB, Code 7,886 KB,
Arena Chunk 7,118 KB, Metaspace 1,883 KB, Thread 1,487 KB, Internal 1,272 KB, Symbol 1,227 KB.

### Per idle keep-alive connection (delta vs 0 conns)

| conns | RSS / conn | heap-after-GC / conn | NMT non-heap / conn |
|---|---|---|---|
| 1,000 | 26,628 B | 7,385 B | −4,328 B (Arena Chunk released) |
| 10,000 | **21,740 B** | **6,660 B** | −406 B |

The heap figure (6.6 KB) matches the prior baseline. Native per-connection memory is ~0 (virtual
threads: carrier count stays at 36–38 threads). The RSS/heap gap is heap page first-touch plus G1
region slack (RSS grows ~212 MB for ~65 MB of live heap at 10k).

Heap histogram at 10,000 idle connections (`GC.class_histogram`, ad-hoc, same flags):

| class | instances | bytes | ≈ per conn |
|---|---|---|---|
| `byte[]` | 91,675 | 25,697,800 | 2,570 B (2,048 B `INPUT_BUFFER` + header strings/line buffers) |
| `jdk.internal.vm.StackChunk` | 10,017 | 23,259,192 | 2,326 B (parked virtual-thread stack) |
| `String` | 71,419 | 1,714,056 | 171 B (retained request head) |
| `VirtualThread` | 10,009 | 1,681,512 | 168 B |
| `SocketChannelImpl` | 10,000 | 1,120,000 | 112 B |
| `ChunkedDecoder` | 10,000 | 720,000 | 72 B (allocated eagerly) |
| `LinkedHashMap`+entries+`HashMap$Node[]` | ~30,000 | ~1.9 MB | ~190 B (retained headers) |
| `HeadParser` / `Head` / `ConnectionReaper$Timer` | 10,000 each | 640k / 400k / 560k | 64 / 40 / 56 B |

## Throughput, 1 KB file, wrk -t4 -c100, 3 × 10 s after 5 s warm-up

| median | reps (req/s) | p99 per rep |
|---|---|---|
| 189,606 req/s | 189,606; 174,476; 258,369 | 5.61 ms; 5.60 ms; 4.61 ms |

RSS after the throughput run: 269,956 KB. Variance between reps is large on this shared VM; the
median is in line with the earlier ~200–220k req/s.
