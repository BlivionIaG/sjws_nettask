#!/usr/bin/env python3
"""jfr_top.attribute: self vs inclusive CPU and allocation-site shares (#414)."""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import jfr_top  # noqa: E402


def fr(cls, m):
    return {"method": {"type": {"name": cls}, "name": m}}


def cpu(*stack):
    return {"type": "jdk.ExecutionSample", "values": {"stackTrace": {"frames": [fr(c, m) for c, m in stack]}}}


def alloc(cls, w, *stack):
    return {"type": "jdk.ObjectAllocationSample",
            "values": {"objectClass": {"name": cls}, "weight": w,
                       "stackTrace": {"frames": [fr(c, m) for c, m in stack]}}}


def main():
    at = ("sjws_nettask/swarm/StreamHttp$Session", "at")
    chunk = ("sjws_nettask.swarm.StreamHttp", "chunk")
    copy = ("java.lang.System", "arraycopy")
    rf = ("sjws_nettask.swarm.SwarmHttp", "readFrame")
    doc = {"recording": {"events": [
        cpu(at, chunk),
        cpu(at, at, chunk),          # recursion counts once per sample (inclusive)
        cpu(copy, rf, chunk),
        cpu(("java.lang.Thread", "run")),
        alloc("byte[]", 3000, copy, rf, chunk),
        alloc("byte[]", 1000, ("sjws_nettask.swarm.wire.StreamChunk", "decode"), chunk),
    ]}}
    r = jfr_top.attribute(doc, "sjws_nettask.")
    assert r["cpu_samples"] == 4, r
    self_ = dict((m, p) for m, p in r["self_pct"])
    incl = dict((m, p) for m, p in r["inclusive_pct"])
    assert self_["sjws_nettask.swarm.StreamHttp$Session.at"] == 50.0, self_
    assert incl["sjws_nettask.swarm.StreamHttp$Session.at"] == 50.0, incl
    assert incl["sjws_nettask.swarm.StreamHttp.chunk"] == 75.0, incl
    assert "java.lang.System.arraycopy" not in incl  # prefix filter on inclusive
    site = dict((m, p) for m, p in r["alloc_by_site_pct"])
    assert site["sjws_nettask.swarm.SwarmHttp.readFrame"] == 75.0, site  # first frame in our code
    cls = dict((m, p) for m, p in r["alloc_by_class_pct"])
    assert cls["byte[]"] == 100.0, cls
    md = jfr_top.to_md(r, "sjws_nettask.")
    assert "Session.at" in md
    assert jfr_top.attribute({}, "x")["cpu_samples"] == 0
    print("ok")


if __name__ == "__main__":
    main()
