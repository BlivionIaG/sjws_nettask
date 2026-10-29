#!/usr/bin/env python3
"""Credit-on producer→consumer stream drive for S2c PR 8.

Each chunk payload is: 8-byte big-endian emit monotonic_ns + padding to --chunk-bytes.
Chunk latency is emit→consumer receive (same host, one monotonic clock).
Uses N agent tokens (StreamHttp.MAX_SESSIONS_PER_AGENT=64) so 1k streams fit.
"""
from __future__ import annotations

import argparse
import json
import struct
import sys
import threading
import time
import urllib.request
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "sdk" / "python"))

from sjws_swarm import Client  # noqa: E402
from sjws_swarm.stream import Chunk, ChunkWriter  # noqa: E402


def pct(sorted_vals, q):
    if not sorted_vals:
        return None
    i = min(len(sorted_vals) - 1, int(q * len(sorted_vals)))
    return sorted_vals[i]


def fetch_metrics(url: str) -> dict[str, float]:
    req = urllib.request.Request(url)
    with urllib.request.urlopen(req, timeout=10) as r:
        text = r.read().decode()
    out = {}
    for line in text.splitlines():
        if not line or line.startswith("#"):
            continue
        parts = line.split()
        if len(parts) >= 2:
            try:
                out[parts[0]] = float(parts[1])
            except ValueError:
                pass
    return out


def instrument_credit_waits(w: ChunkWriter, waits: list[float]):
    """Accumulate seconds spent waiting on credit (Architecture Q3)."""
    orig = w._credit_allows
    state = {"blocked_since": None}

    def wrapped(j, now):
        ok = orig(j, now)
        if ok:
            if state["blocked_since"] is not None:
                waits.append(now - state["blocked_since"])
                state["blocked_since"] = None
        else:
            if state["blocked_since"] is None and not w._out:
                state["blocked_since"] = now
        return ok

    w._credit_allows = wrapped  # type: ignore[method-assign]


def load_tokens(path: str | None, single: str | None) -> list[str]:
    if path:
        toks = []
        for line in Path(path).read_text().splitlines():
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            parts = line.split()
            if len(parts) >= 2:
                toks.append(parts[1])
        if not toks:
            raise SystemExit(f"no tokens in {path}")
        return toks
    if not single:
        raise SystemExit("need --token or --tokens-file")
    return [single]


def run_window(args) -> dict:
    load1_pre = float(open("/proc/loadavg").read().split()[0])
    tokens = load_tokens(args.tokens_file, args.token)
    clients = [Client(args.base, token=t) for t in tokens]
    lat_us: list[float] = []
    bytes_rx = 0
    credit_waits: list[float] = []
    writer_stats = Counter()
    stop = threading.Event()
    errors: list[str] = []
    ttft_us: list[float] = []
    inter_us: list[float] = []
    lock = threading.Lock()
    pad = args.chunk_bytes - 8
    if pad < 0:
        raise SystemExit("chunk-bytes must be >= 8")
    filler = b"\x00" * pad

    def producer(client: Client, pool: str):
        try:
            (lease,) = client.claim(pool, wait=60, visibility_ms=60_000)
            with lease.stream(window=args.window, coalesce=False, heartbeat=0) as w:
                instrument_credit_waits(w, credit_waits)
                while not stop.is_set():
                    emit = time.monotonic_ns()
                    w.send(struct.pack(">Q", emit) + filler)
                w.finish(b"")
                with lock:
                    writer_stats.update(w.stats)
        except BaseException as e:  # noqa: BLE001
            with lock:
                errors.append(f"producer {pool}: {type(e).__name__}: {e}")

    def consumer(client: Client, pool: str, accept: str):
        nonlocal bytes_rx
        try:
            with client.stream(pool, b"gate", deadline=180, sse=(accept == "sse")) as s:
                first = True
                prev_recv = None
                start_ns = time.monotonic_ns()
                for ev in s:
                    if stop.is_set() and not first:
                        break
                    if not isinstance(ev, Chunk) or len(ev.data) < 8:
                        continue
                    recv = time.monotonic_ns()
                    (emit,) = struct.unpack_from(">Q", ev.data, 0)
                    d_us = (recv - emit) / 1e3
                    with lock:
                        lat_us.append(d_us)
                        bytes_rx += len(ev.data)
                        if first:
                            ttft_us.append((recv - start_ns) / 1e3)
                            first = False
                        elif prev_recv is not None:
                            inter_us.append((recv - prev_recv) / 1e3)
                    prev_recv = recv
        except BaseException as e:  # noqa: BLE001
            with lock:
                errors.append(f"consumer {pool}: {type(e).__name__}: {e}")

    def run_wave(n_streams: int, duration: float, label: str):
        nonlocal bytes_rx
        threads = []
        stop.clear()
        for i in range(n_streams):
            pool = f"{label}{i}"
            c = clients[i % len(clients)]
            t_c = threading.Thread(target=consumer, args=(c, pool, args.accept), daemon=True)
            t_p = threading.Thread(target=producer, args=(c, pool), daemon=True)
            threads.extend((t_c, t_p))
            t_c.start()
            if i % 32 == 0:
                time.sleep(0.01)
            t_p.start()
        time.sleep(duration)
        stop.set()
        # drain
        time.sleep(3)
        for t in threads:
            t.join(20)

    # Warmup
    with lock:
        lat_us.clear(); bytes_rx = 0; ttft_us.clear(); inter_us.clear()
        credit_waits.clear(); writer_stats.clear(); errors.clear()
    run_wave(min(32, args.streams), args.warmup, "w")
    with lock:
        lat_us.clear(); bytes_rx = 0; ttft_us.clear(); inter_us.clear()
        credit_waits.clear(); writer_stats.clear(); errors.clear()

    # Measure
    t0 = time.monotonic()
    run_wave(args.streams, args.secs, "g")
    elapsed = max(1e-6, time.monotonic() - t0)
    load1_post = float(open("/proc/loadavg").read().split()[0])

    lat_sorted = sorted(lat_us)
    wait_sorted = sorted(credit_waits)
    inter_sorted = sorted(inter_us)
    ttft_sorted = sorted(ttft_us)
    mib_s = (bytes_rx / (1024 * 1024)) / elapsed
    counters = {}
    try:
        counters = fetch_metrics(args.metrics_url)
    except Exception as e:  # noqa: BLE001
        counters = {"_error": str(e)}

    def pack(vals):
        s = sorted(vals)
        return {
            "n": len(s),
            "p50_us": pct(s, 0.50),
            "p90_us": pct(s, 0.90),
            "p99_us": pct(s, 0.99),
            "p999_us": pct(s, 0.999),
        }

    credit_429s = int(writer_stats.get("credit_429s", 0))
    n_chunks = len(lat_us)
    stall_pct = (100.0 * credit_429s / n_chunks) if n_chunks else 0.0

    hard_zeros = {
        "sjws_swarm_stream_node_refusals_total": counters.get("sjws_swarm_stream_node_refusals_total"),
        "sjws_swarm_stream_session_admit_refusals_total": counters.get("sjws_swarm_stream_session_admit_refusals_total"),
        "sjws_swarm_stream_budget_underflows_total": counters.get("sjws_swarm_stream_budget_underflows_total"),
        "sjws_swarm_stream_reserve_underflows_total": counters.get("sjws_swarm_stream_reserve_underflows_total"),
        "sjws_swarm_stream_charged_bytes": counters.get("sjws_swarm_stream_charged_bytes"),
    }

    out = {
        "mode": args.accept,
        "streams": args.streams,
        "agents": len(clients),
        "elapsed_s": elapsed,
        "load1_pre": load1_pre,
        "load1_post": load1_post,
        "self_loaded": load1_post >= 4.0,
        "chunk_samples": n_chunks,
        "bytes": bytes_rx,
        "mib_s": mib_s,
        "chunk_latency": pack(lat_sorted),
        "ttft": pack(ttft_sorted),
        "inter_token": pack(inter_sorted),
        "credit_wait": {
            "n": len(wait_sorted),
            "total_s": sum(wait_sorted) if wait_sorted else 0.0,
            "p50_s": pct(wait_sorted, 0.50),
            "p99_s": pct(wait_sorted, 0.99),
        },
        "writer_stats": dict(writer_stats),
        "credit_stalls": credit_429s,
        "credit_stall_pct_of_chunks": stall_pct,
        "stall_flag": stall_pct > 1.0,
        "counters": counters,
        "hard_zeros": hard_zeros,
        "errors": errors[:30],
        "error_count": len(errors),
        "p50_us": pct(lat_sorted, 0.50),
        "p90_us": pct(lat_sorted, 0.90),
        "p99_us": pct(lat_sorted, 0.99),
        "rps": n_chunks / elapsed,
    }
    Path(args.out).write_text(json.dumps(out, indent=2) + "\n")
    print(json.dumps({k: out[k] for k in (
        "mib_s", "chunk_samples", "p50_us", "p90_us", "p99_us", "load1_pre", "load1_post",
        "self_loaded", "stall_flag", "credit_stalls", "error_count", "agents")}, indent=2))
    return out


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--base", required=True)
    p.add_argument("--token", default=None)
    p.add_argument("--tokens-file", default=None)
    p.add_argument("--accept", choices=("sse", "binary", "chunked"), default="sse")
    p.add_argument("--warmup", type=float, default=5)
    p.add_argument("--secs", type=float, default=20)
    p.add_argument("--streams", type=int, default=1000)
    p.add_argument("--chunk-bytes", type=int, default=1024)
    p.add_argument("--window", type=int, default=8)
    p.add_argument("--out", required=True)
    p.add_argument("--metrics-url", required=True)
    args = p.parse_args()
    if args.accept == "chunked":
        args.accept = "binary"
    run_window(args)


if __name__ == "__main__":
    main()
