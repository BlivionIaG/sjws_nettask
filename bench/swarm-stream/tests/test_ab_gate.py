#!/usr/bin/env python3
import importlib.util
from pathlib import Path

def load():
    p = Path(__file__).resolve().parents[1] / "ab_gate.py"
    spec = importlib.util.spec_from_file_location("ab_gate", p)
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m

def r(p99, hist):
    return {"p99_us": p99, "chunk_latency_sched": {"hist": hist, "p99_us": p99}}

def main():
    ab = load()
    # identical -> PASS, ratio 1, p high
    ev = ab.evaluate(r(200, [[100, 500], [200, 500]]), r(200, [[100, 500], [200, 500]]))
    assert ev["verdict"] == "PASS", ev
    assert ev["ratio"] == 1.0
    # 12% higher p99 but identical distributions (same hist) — ratio>1.10 from p99
    # Use clearly worse new samples
    ev = ab.evaluate(r(400, [[400, 2000]]), r(200, [[200, 2000]]))
    assert ev["ratio"] == 2.0
    assert ev["p"] is not None and ev["p"] < 0.05, ev
    assert ev["verdict"] == "FAIL", ev
    # 5% higher: ratio 1.05, even if significant, PASS
    ev = ab.evaluate(r(210, [[210, 2000]]), r(200, [[200, 2000]]))
    assert ev["ratio"] == 1.05
    assert ev["verdict"] == "PASS", ev
    # incomplete
    ev = ab.evaluate({}, r(200, [[200, 100]]))
    assert ev["verdict"] == "INCOMPLETE", ev
    print("OK test_ab_gate")

if __name__ == "__main__":
    main()
