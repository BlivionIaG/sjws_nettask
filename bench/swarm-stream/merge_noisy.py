#!/usr/bin/env python3
"""Merge foreign_cpu.py output into a leg's result.json (07:05 addendum)."""
from __future__ import annotations
import importlib.util
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent


def load_fc():
    spec = importlib.util.spec_from_file_location("foreign_cpu", HERE / "foreign_cpu.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def main(argv: list[str]) -> int:
    if len(argv) < 3:
        print("usage: merge_noisy.py <result.json> <foreign_cpu.json> [load1_pre]", file=sys.stderr)
        return 2
    rp, fp = Path(argv[1]), Path(argv[2])
    load1_pre = float(argv[3]) if len(argv) > 3 else None
    fc = load_fc()
    data = json.loads(fp.read_text()) if fp.is_file() else {"samples": []}
    samples = data.get("samples") or []
    judgment = fc.judge(samples, load1_end=fc.load1())
    fc.merge_into_result(rp, judgment, load1_pre=load1_pre)
    print(json.dumps({
        "noisy": judgment["noisy"],
        "foreign_cpu_avg_cores": judgment["foreign_cpu_avg_cores"],
        "reasons": judgment["reasons"],
        "load1_end": judgment["load1_end"],
        "samples_n": judgment["samples_n"],
    }))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
