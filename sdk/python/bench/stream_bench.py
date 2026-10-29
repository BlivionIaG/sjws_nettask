"""SDK stream bench (S2c PR 7, #261 Q17): informational only, no gate (the §13 token-streaming targets are PR 8's gate).

Starts the jar with streaming and credit on, then runs S streams × N chunks of SIZE bytes through the Python SDK
(``Client.stream`` reader + ``ChunkWriter`` worker, one thread each, all in this process). Modes: ``open`` (send as fast
as the writer allows) and ``paced`` (RATE chunks/s per stream). Reports chunks/s, MiB/s, ``:chunk`` request p50/p99,
end-to-end chunk latency (worker ``send`` → reader receive, same clock) p50/p99, 429s, probes and gaps.

Run it under the bench rules (AGENTS.md): load1 < 4 at the start and the end, one bench at a time, for example
    flock /tmp/sjws-bench.lock nice -n 10 taskset -c 0-1 python3 sdk/python/bench/stream_bench.py \
        --jar target/sjws_nettask-*.jar --server-cpus 2-7
"""
import argparse
import os
import pathlib
import statistics
import struct
import subprocess
import sys
import threading
import time

ROOT = pathlib.Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "sdk" / "python"))
sys.path.insert(0, str(ROOT / "sdk" / "python" / "tests"))

import test_lease_stream_e2e as e2e  # noqa: E402  (the jar launcher)
from sjws_swarm import Client  # noqa: E402
from sjws_swarm import stream as st  # noqa: E402
from sjws_swarm.client import StreamGap  # noqa: E402

LAT = []
_lock = threading.Lock()
_orig = st._Http.request


def _timed(self, method, path, body=None, headers=None, stream=False):
    t0 = time.perf_counter()
    try:
        return _orig(self, method, path, body, headers, stream)
    finally:
        if path.endswith(":chunk"):
            with _lock:
                LAT.append(time.perf_counter() - t0)


def pct(xs, p):
    xs = sorted(xs)
    return xs[min(len(xs) - 1, int(len(xs) * p))] if xs else float("nan")


def run(client, streams, n, size, rate, window):
    e2e_lat, gaps, stats = [], [0], []
    lock = threading.Lock()

    def worker(i):
        (lease,) = client.claim(f"bench{i}", wait=10)
        with lease.stream(window=window, coalesce=False) as w:
            for k in range(n):
                w.send(struct.pack(">d", time.perf_counter()) + b"x" * (size - 8))
                if rate:
                    time.sleep(1.0 / rate)
            w.finish()
            with lock:
                stats.append(w.stats)

    def reader(i, s):
        local = []
        try:
            for ev in s:
                if isinstance(ev, st.Chunk) and len(ev.data) >= 8:
                    local.append(time.perf_counter() - struct.unpack(">d", ev.data[:8])[0])
        except StreamGap:
            with lock:
                gaps[0] += 1
        with lock:
            e2e_lat.extend(local)

    sessions = [client.stream(f"bench{i}", b"p", deadline=600) for i in range(streams)]
    t0 = time.perf_counter()
    ts = [threading.Thread(target=reader, args=(i, s)) for i, s in enumerate(sessions)]
    ts += [threading.Thread(target=worker, args=(i,)) for i in range(streams)]
    [t.start() for t in ts]
    [t.join() for t in ts]
    dt = time.perf_counter() - t0
    total = streams * n
    agg = {k: sum(s[k] for s in stats) for k in ("credit_429s", "node_429s", "credit_probes", "requests")}
    return {"chunks/s": round(total / dt), "MiB/s": round(total * size / dt / (1 << 20), 2),
            "chunk p50 ms": round(pct(LAT, 0.5) * 1e3, 3), "chunk p99 ms": round(pct(LAT, 0.99) * 1e3, 3),
            "e2e p50 ms": round(pct(e2e_lat, 0.5) * 1e3, 3), "e2e p99 ms": round(pct(e2e_lat, 0.99) * 1e3, 3),
            "gaps": gaps[0], **agg, "seconds": round(dt, 2)}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--jar", default=os.environ.get("SJWS_JAR"))
    ap.add_argument("--streams", type=int, default=8)
    ap.add_argument("--chunks", type=int, default=2000)
    ap.add_argument("--size", type=int, default=1024)
    ap.add_argument("--rate", type=float, default=200.0, help="paced mode: chunks/s per stream")
    ap.add_argument("--window", type=int, default=8)
    ap.add_argument("--server-cpus", default=None, help="taskset CPU list for the server, e.g. 2-7")
    a = ap.parse_args()
    if not a.jar:
        sys.exit("--jar or SJWS_JAR is required")
    e2e.JAR = os.path.abspath(a.jar)
    st._Http.request = _timed
    print(f"load1 start {os.getloadavg()[0]:.2f}", flush=True)
    flags = ["-Dsjws.swarm.stream.credit.enabled=true", "-XX:+UseZGC", "-Dsjws.swarm.pool.shards=4"]
    srv = e2e._Server(flags)
    if a.server_cpus:
        subprocess.run(["taskset", "-a", "-p", "-c", a.server_cpus, str(srv.proc.pid)], check=True,
                       stdout=subprocess.DEVNULL)
    try:
        client = Client(f"http://127.0.0.1:{srv.port}", token=srv.token)
        for mode, rate in (("open", 0), ("paced", a.rate)):
            LAT.clear()
            r = run(client, a.streams, a.chunks, a.size, rate, a.window)
            print(mode, f"streams={a.streams} chunks={a.chunks} size={a.size} window={a.window}", r, flush=True)
    finally:
        srv.close()
    print(f"load1 end {os.getloadavg()[0]:.2f}", flush=True)


if __name__ == "__main__":
    main()
