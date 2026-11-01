#!/usr/bin/env python3
"""agg.py must tolerate skipped-leg placeholders with mib_s=None (Codex P2)."""
from __future__ import annotations

import json
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
AGG = HERE.parent / "agg.py"


def main():
    with tempfile.TemporaryDirectory() as td:
        out = Path(td)
        d = out / "abs-01"
        d.mkdir()
        (d / "result.json").write_text(json.dumps({
            "noisy": True,
            "noisy_marker": "NOISY",
            "load1_pre": 3.2,
            "load1_post": 3.2,
            "chunk_samples": 0,
            "p99_us": None,
            # deliberately omit mib_s
            "error_count": 1,
            "group_open_refusals": 0,
            "self_loaded": False,
            "hard_zeros": {
                "sjws_connections_refused_per_ip_total": 0,
                "sjws_swarm_stream_open_sessions": 0,
            },
        }))
        proc = subprocess.run([sys.executable, str(AGG), str(out)],
                              capture_output=True, text=True, cwd=str(HERE.parent))
        assert proc.returncode == 0, proc.stderr + proc.stdout
        assert "TypeError" not in (proc.stderr + proc.stdout)
        assert "VERDICT: FAIL" in proc.stdout
        assert "mib_s=n/a" in proc.stdout
    print("OK test_agg_placeholder_mib")


if __name__ == "__main__":
    main()
