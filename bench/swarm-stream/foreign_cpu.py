#!/usr/bin/env python3
"""Foreign-CPU sampler for the 2026-11-01 07:05 bench-queue addendum.

A block is NOISY if:
  - processes OTHER than the bench's own server/generator/harness use > 0.5 core
    averaged over the block, OR
  - any sample shows a foreign java / mvn / load process, OR
  - end-of-block load1 >= 6 (absolute guard).

Own processes = allowlisted PIDs plus their descendants (one.sh tree).
"""
from __future__ import annotations

import argparse
import json
import os
import re
import signal
import sys
import time
from pathlib import Path

USER_HZ = 100.0  # Linux default
FOREIGN_CMD_RE = re.compile(
    r"(^|/)(java|mvn|mvnw|wrk|hey|ab|fortio|oha|nighthawk|RaftPublishBench|"
    r"StreamLoad|SwarmBench|HttpBench)(\s|$)",
    re.I,
)
OWN_CMD_HINTS = re.compile(
    r"(gate\.sh|one\.sh|cross-check\.sh|foreign_cpu\.py|agg\.py|amend-result|"
    r"drive\.py|StreamLoad\.java)",
    re.I,
)


def load1() -> float:
    return float(Path("/proc/loadavg").read_text().split()[0])


def read_stat(pid: int) -> tuple[int, int, str] | None:
    """Return (ppid, utime+stime jiffies, comm) or None if gone."""
    try:
        raw = Path(f"/proc/{pid}/stat").read_text()
    except (FileNotFoundError, ProcessLookupError, PermissionError):
        return None
    rp = raw.rfind(")")
    if rp < 0:
        return None
    comm = raw[raw.find("(") + 1 : rp]
    rest = raw[rp + 2 :].split()
    if len(rest) < 13:
        return None
    ppid = int(rest[1])
    utime = int(rest[11])
    stime = int(rest[12])
    return ppid, utime + stime, comm


def read_cmdline(pid: int) -> str:
    try:
        raw = Path(f"/proc/{pid}/cmdline").read_bytes()
    except (FileNotFoundError, ProcessLookupError, PermissionError):
        return ""
    return raw.replace(b"\x00", b" ").decode("utf-8", "replace").strip()


def list_pids() -> list[int]:
    out = []
    for name in os.listdir("/proc"):
        if name.isdigit():
            out.append(int(name))
    return out


def expand_own(allow: set[int]) -> set[int]:
    """Closure: allowlisted PIDs + every process whose ancestor is allowlisted."""
    ppid_of: dict[int, int] = {}
    for pid in list_pids():
        st = read_stat(pid)
        if st is None:
            continue
        ppid_of[pid] = st[0]
    own = set(allow)
    changed = True
    while changed:
        changed = False
        for pid, pp in ppid_of.items():
            if pid not in own and pp in own:
                own.add(pid)
                changed = True
    return own


def snapshot(allow: set[int]) -> dict:
    own = expand_own(allow)
    # Also treat obvious harness scripts as own even if not in the tree yet
    procs = {}
    foreign_jiffies = 0
    foreign_java = False
    foreign_details = []
    for pid in list_pids():
        st = read_stat(pid)
        if st is None:
            continue
        ppid, jif, comm = st
        cmd = read_cmdline(pid) or comm
        if pid in own or pid == os.getpid():
            continue
        if OWN_CMD_HINTS.search(cmd):
            continue
        procs[pid] = jif
        foreign_jiffies += jif
        if FOREIGN_CMD_RE.search(cmd) or FOREIGN_CMD_RE.search(comm):
            foreign_java = True
            foreign_details.append({"pid": pid, "cmd": cmd[:200]})
    return {
        "ts": time.time(),
        "own_pids": sorted(own),
        "foreign_jiffies_sum": foreign_jiffies,
        "per_pid_jiffies": procs,
        "foreign_load_proc": foreign_java,
        "foreign_details": foreign_details[:20],
        "load1": load1(),
    }


def judge(samples: list[dict], load1_end: float | None = None,
          avg_threshold: float = 0.5, load1_end_limit: float = 6.0) -> dict:
    """Decide NOISY from ≥1 samples. Pure function for unit tests."""
    if not samples:
        return {
            "noisy": False,
            "foreign_cpu_avg_cores": 0.0,
            "foreign_load_proc_seen": False,
            "load1_end": load1_end,
            "reasons": [],
            "samples_n": 0,
        }
    # Average foreign cores from successive jiffy deltas
    cores = []
    foreign_seen = any(bool(s.get("foreign_load_proc")) for s in samples)
    for i in range(1, len(samples)):
        a, b = samples[i - 1], samples[i]
        dt = max(1e-6, float(b["ts"]) - float(a["ts"]))
        # Prefer per-pid deltas when both snapshots have the pid
        delta = 0
        pa, pb = a.get("per_pid_jiffies") or {}, b.get("per_pid_jiffies") or {}
        for pid, j1 in pb.items():
            j0 = pa.get(pid)
            if j0 is None:
                continue
            d = j1 - j0
            if d > 0:
                delta += d
        if not pa and not pb:
            delta = max(0, int(b.get("foreign_jiffies_sum", 0)) - int(a.get("foreign_jiffies_sum", 0)))
        cores.append((delta / USER_HZ) / dt)
    avg = sum(cores) / len(cores) if cores else 0.0
    # Single-sample fallback: cannot average deltas; rely on foreign_load_proc + end load1
    reasons = []
    if avg > avg_threshold:
        reasons.append(f"foreign_cpu_avg_cores={avg:.3f}>{avg_threshold}")
    if foreign_seen:
        reasons.append("foreign_java_or_load_process_seen")
    end = load1_end if load1_end is not None else samples[-1].get("load1")
    try:
        end_f = float(end) if end is not None else 0.0
    except (TypeError, ValueError):
        end_f = 0.0
    if end_f >= load1_end_limit:
        reasons.append(f"load1_end={end_f}>={load1_end_limit}")
    return {
        "noisy": bool(reasons),
        "foreign_cpu_avg_cores": avg,
        "foreign_load_proc_seen": foreign_seen,
        "load1_end": end_f,
        "reasons": reasons,
        "samples_n": len(samples),
    }


def merge_into_result(result_path: Path, judgment: dict, load1_pre: float | None = None) -> None:
    if not result_path.is_file():
        return
    r = json.loads(result_path.read_text())
    r["noisy"] = bool(judgment.get("noisy"))
    r["foreign_cpu_avg_cores"] = judgment.get("foreign_cpu_avg_cores")
    r["foreign_load_proc_seen"] = judgment.get("foreign_load_proc_seen")
    r["noisy_reasons"] = judgment.get("reasons") or []
    if load1_pre is not None:
        r["load1_pre"] = float(load1_pre)
    if judgment.get("load1_end") is not None:
        r["load1_post"] = float(judgment["load1_end"])
    # Marker string for aggregators that scan text
    if r["noisy"]:
        r["noisy_marker"] = "NOISY"
    result_path.write_text(json.dumps(r, indent=2) + "\n")


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--out", required=True, help="JSON path for samples + judgment")
    ap.add_argument("--allow-pid", type=int, action="append", default=[],
                    help="Harness PID to treat as own (repeatable)")
    ap.add_argument("--allow-pid-file", default=None,
                    help="File with extra allow PIDs (one per line); re-read each sample")
    ap.add_argument("--interval", type=float, default=5.0)
    ap.add_argument("--duration", type=float, default=0.0,
                    help="If >0, sample this many seconds then exit; else until SIGTERM")
    args = ap.parse_args(argv)

    allow = set(args.allow_pid) | {os.getpid()}
    samples: list[dict] = []
    stop = {"flag": False}

    def _stop(signum, frame):  # noqa: ARG001
        stop["flag"] = True

    signal.signal(signal.SIGTERM, _stop)
    signal.signal(signal.SIGINT, _stop)

    t0 = time.time()
    while not stop["flag"]:
        extra = set(allow)
        if args.allow_pid_file:
            try:
                for line in Path(args.allow_pid_file).read_text().splitlines():
                    line = line.strip()
                    if line.isdigit():
                        extra.add(int(line))
            except FileNotFoundError:
                pass
        samples.append(snapshot(extra))
        if args.duration > 0 and (time.time() - t0) >= args.duration:
            break
        # sleep in slices so SIGTERM is prompt
        end = time.time() + args.interval
        while not stop["flag"] and time.time() < end:
            time.sleep(0.2)
        if args.duration > 0 and (time.time() - t0) >= args.duration:
            break

    end_load = load1()
    judgment = judge(samples, load1_end=end_load)
    out = {"samples": samples, "judgment": judgment}
    Path(args.out).write_text(json.dumps(out, indent=2) + "\n")
    print(json.dumps({"noisy": judgment["noisy"], **{k: judgment[k] for k in
          ("foreign_cpu_avg_cores", "foreign_load_proc_seen", "load1_end", "reasons", "samples_n")}}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
