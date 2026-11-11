#!/usr/bin/env python3
"""#414: self / inclusive CPU and allocation attribution from a JFR JSON dump.

Input: `jfr print --json --events jdk.ExecutionSample,jdk.ObjectAllocationSample rec.jfr > rec.json`
(run `jfr` only under the bench + mvn locks; this script itself is pure Python).

Output (markdown on stdout, `--json` for machine use):
- CPU: share of execution samples per method, self (top frame) and inclusive (anywhere on stack,
  counted once per sample), optionally filtered by `--prefix` (default `sjws_nettask.`).
- Allocation: share of sampled allocation weight per allocated class and per first frame
  matching the prefix (the allocation site in our code).

Used to re-check the #414 hypotheses with an unsaturated generator: `StreamHttp$Session.at`
(F1, #326), `readFrame` / `Frame` / `StreamChunk` byte[] copies, ledger force sources.
"""
from __future__ import annotations

import argparse
import json
import sys
from collections import Counter


def _name(t) -> str:
    if isinstance(t, str):
        return t.replace("/", ".")
    if isinstance(t, dict):
        return str(t.get("name", "?")).replace("/", ".")
    return "?"


def frame_name(fr: dict) -> str:
    m = fr.get("method") or {}
    return f"{_name(m.get('type'))}.{m.get('name', '?')}"


def frames(ev: dict) -> list[str]:
    st = (ev.get("values") or {}).get("stackTrace") or {}
    return [frame_name(f) for f in (st.get("frames") or [])]


def attribute(doc: dict, prefix: str) -> dict:
    events = ((doc.get("recording") or {}).get("events")) or []
    n_cpu = 0
    self_c: Counter = Counter()
    incl_c: Counter = Counter()
    alloc_total = 0.0
    alloc_cls: Counter = Counter()
    alloc_site: Counter = Counter()
    for ev in events:
        typ = ev.get("type")
        fs = frames(ev)
        if typ == "jdk.ExecutionSample":
            n_cpu += 1
            if fs:
                self_c[fs[0]] += 1
            for name in set(fs):
                if name.startswith(prefix):
                    incl_c[name] += 1
        elif typ == "jdk.ObjectAllocationSample":
            v = ev.get("values") or {}
            w = float(v.get("weight") or 0)
            alloc_total += w
            alloc_cls[_name(v.get("objectClass"))] += w
            site = next((f for f in fs if f.startswith(prefix)), fs[0] if fs else "?")
            alloc_site[site] += w

    def pct(c: Counter, denom: float, k: int) -> list[list]:
        if denom <= 0:
            return []
        return [[name, round(100.0 * v / denom, 2)] for name, v in c.most_common(k)]

    return {
        "cpu_samples": n_cpu,
        "self_pct": pct(self_c, n_cpu, 25),
        "inclusive_pct": pct(incl_c, n_cpu, 40),
        "alloc_weight_bytes": alloc_total,
        "alloc_by_class_pct": pct(alloc_cls, alloc_total, 20),
        "alloc_by_site_pct": pct(alloc_site, alloc_total, 25),
    }


def to_md(r: dict, prefix: str) -> str:
    out = [f"CPU samples: {r['cpu_samples']}", "", "| self % | method |", "|---:|---|"]
    out += [f"| {p} | `{m}` |" for m, p in r["self_pct"]]
    out += ["", f"| inclusive % | method (`{prefix}*`) |", "|---:|---|"]
    out += [f"| {p} | `{m}` |" for m, p in r["inclusive_pct"]]
    out += ["", f"Allocation weight sampled: {r['alloc_weight_bytes']:.0f} B", "",
            "| alloc % | class |", "|---:|---|"]
    out += [f"| {p} | `{m}` |" for m, p in r["alloc_by_class_pct"]]
    out += ["", "| alloc % | site |", "|---:|---|"]
    out += [f"| {p} | `{m}` |" for m, p in r["alloc_by_site_pct"]]
    return "\n".join(out) + "\n"


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("json_dump")
    ap.add_argument("--prefix", default="sjws_nettask.")
    ap.add_argument("--json", action="store_true")
    a = ap.parse_args(argv)
    with open(a.json_dump, encoding="utf-8") as fh:
        doc = json.load(fh)
    r = attribute(doc, a.prefix)
    sys.stdout.write(json.dumps(r, indent=1) + "\n" if a.json else to_md(r, a.prefix))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
