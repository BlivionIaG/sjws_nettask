#!/usr/bin/env python3
"""Credit-on producer→consumer stream drive for S2c PR 8. Records p50/p90/p99/rps and counters."""
from __future__ import annotations
import argparse, json, os, sys, threading, time, urllib.request

def main():
    p = argparse.ArgumentParser()
    p.add_argument("--base", required=True)
    p.add_argument("--token", required=True)
    p.add_argument("--mode", choices=("sse", "binary", "aa"), required=True)
    p.add_argument("--warmup", type=float, default=5)
    p.add_argument("--secs", type=float, default=20)
    p.add_argument("--out", required=True)
    p.add_argument("--stats-url", required=True)
    p.add_argument("--streams", type=int, default=0,
                   help="concurrent streams; 0 = leave unset until questions.md Q6 is answered")
    args = p.parse_args()
    load1 = float(open("/proc/loadavg").read().split()[0])
    # Gate run is blocked on /workspace/s2c-pr8/questions.md — emit a structured stub the agg can read.
    stats = fetch_stats(args.stats_url, args.token)
    out = {
        "mode": args.mode,
        "status": "BLOCKED_ON_QUESTIONS",
        "questions": "/workspace/s2c-pr8/questions.md",
        "load1_pre": load1,
        "load1_post": float(open("/proc/loadavg").read().split()[0]),
        "p50_us": None,
        "p90_us": None,
        "p99_us": None,
        "rps": None,
        "mib_s": None,
        "streams": args.streams,
        "counters": stats,
        "note": "Implement full producer/consumer once Q1–Q6 are answered; counters scrape works now.",
    }
    open(args.out, "w").write(json.dumps(out, indent=2) + "\n")
    print(json.dumps(out))
    if out["counters"].get("sjws_swarm_stream_node_refusals", "0") not in ("0", 0, "0.0"):
        sys.exit(4)

def fetch_stats(url, token):
    req = urllib.request.Request(url, headers={"Authorization": f"Bearer {token}"})
    try:
        with urllib.request.urlopen(req, timeout=5) as r:
            text = r.read().decode()
    except Exception as e:
        return {"error": str(e)}
    d = {}
    for line in text.splitlines():
        if line.startswith("#") or not line.strip():
            continue
        parts = line.split()
        if len(parts) >= 2:
            d[parts[0]] = parts[1]
    return d

if __name__ == "__main__":
    main()
