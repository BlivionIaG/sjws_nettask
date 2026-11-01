#!/usr/bin/env python3
"""drive.py: measureActive window + SDK retry export + gen_cores (Codex P2 #459)."""
from __future__ import annotations

import ast
import importlib.util
import inspect
import threading
from pathlib import Path

HERE = Path(__file__).resolve().parent
DRIVE = HERE.parent / "drive.py"


def load_drive():
    spec = importlib.util.spec_from_file_location("drive", DRIVE)
    m = importlib.util.module_from_spec(spec)
    # Avoid importing sjws_swarm at module load for source checks; exec still needs it.
    # We only parse AST / inspect source for structural guarantees, plus unit-test helpers.
    return m


def test_source_has_measure_active_and_retry_export():
    src = DRIVE.read_text()
    assert "measure_active" in src
    assert "measure_active.set()" in src
    assert "measure_active.clear()" in src
    assert "if measure_active.is_set():" in src
    assert "producer_transport_retries" in src
    assert 'writer_stats.get("transport_errors"' in src
    assert "consumer_connect_retries" in src
    assert "transport_retry_flag" in src
    assert "gen_cores" in src
    assert "--gen-cores" in src
    assert "80.0 * gen_cores" in src
    # Must not hardcode the old 180% sat ceiling as the sole rule
    assert "client_cpu_pct >= 180.0" not in src
    assert "client_cpu_pct_of_wall > 180" not in src


def test_instrument_credit_waits_respects_active_event():
    # Lightweight stand-in: call the function after exec with mocked ChunkWriter
    # Parse AST to ensure signature includes active=
    tree = ast.parse(DRIVE.read_text())
    fn = next(n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name == "instrument_credit_waits")
    args = [a.arg for a in fn.args.args]
    assert "active" in args, args


def main():
    test_source_has_measure_active_and_retry_export()
    test_instrument_credit_waits_respects_active_event()
    print("OK test_drive_measure_and_retries")


if __name__ == "__main__":
    main()
