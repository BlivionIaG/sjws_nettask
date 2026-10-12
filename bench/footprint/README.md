# Footprint benchmark

One command (needs JDK 25 as `JAVA_HOME`, Maven, python3, `wrk`):

    bench/footprint/run.sh                       # defaults: 0/1000/10000 idle conns, wrk c100 x3
    bench/footprint/run.sh --conns 0,1000 --wrk-reps 1 --jvm-flag=-Xss256k   # options: --help

It builds the jar, then:

1. **Startup** (`--startup-runs`, default 5 fresh JVMs): wall time from process spawn to the first `200` on `/1k.bin`.
2. **Idle footprint** (one JVM): for each count in `--conns` (ascending), opens that many keep-alive
   connections, each serves one 1 KB GET and then idles. At each step: `GC.run`, heap used
   (`GC.heap_info`), VmRSS, thread count and `VM.native_memory summary` (committed, per category).
   Per-connection cost = delta vs the 0-connection step / connections.
3. **Throughput**: fresh JVM, `wrk -t4 -c100` on a 1 KB file, 5 s warm-up then `--wrk-reps` x 10 s.

Fixed JVM flags: `-Xms256m -Xmx256m -XX:+UseG1GC -XX:NativeMemoryTracking=summary` (+ `--jvm-flag`).
Server flags: `--max-connections=20000 --max-connections-per-ip=20000 --idle-timeout=3600 --header-timeout=3600 --backlog=4096`.

Output in `bench/footprint/results/` (git-ignored): `results.json` (everything, incl. raw NMT
categories) and `results.csv` (flat metrics). `baseline/` holds the run in `REPORT.md`.

Notes on interpretation:
* `-Xms=-Xmx` means NMT "Java Heap" committed is constant; RSS still grows as heap pages are first
  touched, so **RSS/conn includes heap page faulting** and is the number closest to what an operator sees.
* NMT non-heap deltas can be negative: compiler "Arena Chunk" memory is released between steps and
  hides the small Thread/Metaspace growth. Look at the per-category values in `results.json`.
* Kernel socket buffers are not in RSS (see `sockstat` in the JSON).
