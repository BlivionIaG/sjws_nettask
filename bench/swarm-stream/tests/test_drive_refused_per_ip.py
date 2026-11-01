#!/usr/bin/env python3
"""drive.hard_zeros_from_counters always emits refused_per_ip (Codex P2 #459)."""
from __future__ import annotations
import importlib.util
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
DRIVE = HERE.parent / "drive.py"

def load_drive():
    # drive.py imports sjws_swarm at module level — stub if unavailable.
    if "sjws_swarm" not in sys.modules:
        import types
        swarm = types.ModuleType("sjws_swarm")
        class Client:  # noqa: D401
            pass
        swarm.Client = Client
        sys.modules["sjws_swarm"] = swarm
        stream = types.ModuleType("sjws_swarm.stream")
        class Chunk: pass
        class ChunkWriter: pass
        stream.Chunk = Chunk
        stream.ChunkWriter = ChunkWriter
        sys.modules["sjws_swarm.stream"] = stream
    spec = importlib.util.spec_from_file_location("streamload_drive", DRIVE)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod

def main():
    d = load_drive()
    hz, refused = d.hard_zeros_from_counters({})
    assert "sjws_connections_refused_per_ip_total" in hz, hz
    assert hz["sjws_connections_refused_per_ip_total"] == 0.0
    assert refused == 0

    hz, refused = d.hard_zeros_from_counters({"sjws_connections_refused_per_ip_total": 3})
    assert hz["sjws_connections_refused_per_ip_total"] == 3.0
    assert refused == 3

    # Stream-gate keys still present
    for k in (
        "sjws_swarm_stream_node_refusals_total",
        "sjws_swarm_stream_open_sessions",
    ):
        assert k in hz
    print("OK test_drive_refused_per_ip")

if __name__ == "__main__":
    main()
