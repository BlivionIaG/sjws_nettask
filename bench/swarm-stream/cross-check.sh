#!/bin/bash
# Cross-check StreamLoad vs drive.py at N=1 and N=16 (Architecture conditions 2026-10-30).
# Usage: cross-check.sh <jar> <outdir>
set -euo pipefail
JAR=$1; OUT=$2
HERE=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$OUT"
export DRAIN_TIMEOUT=${DRAIN_TIMEOUT:-40}
export WARMUP=${WARMUP:-5}
SECS=${SECS:-20}

run() {
  local tag=$1 loadgen=$2 streams=$3
  local wd="$OUT/$tag"
  rm -rf "$wd"; mkdir -p "$wd"
  echo "=== cross $tag loadgen=$loadgen streams=$streams load=$(cat /proc/loadavg) ==="
  LOADGEN=$loadgen bash "$HERE/one.sh" "$JAR" "$wd" binary "$wd/result.json" "$SECS" "$streams"
  sync "$wd/result.json" 2>/dev/null || true
}

run cc-py-1 python 1
run cc-java-1 java 1
run cc-py-16 python 16
run cc-java-16 java 16

python3 - "$OUT" <<'PY'
import json, sys
from pathlib import Path
out = Path(sys.argv[1])

def load(tag):
  p = out / tag / "result.json"
  return json.loads(p.read_text()) if p.exists() else None

def hz(r):
  h = (r or {}).get("hard_zeros") or {}
  return {k: h.get(k) for k in (
    "sjws_swarm_stream_node_refusals_total",
    "sjws_swarm_stream_session_admit_refusals_total",
    "sjws_swarm_stream_budget_underflows_total",
    "sjws_swarm_stream_reserve_underflows_total",
    "sjws_swarm_stream_charged_bytes",
    "sjws_swarm_stream_open_sessions",
  )}

rows = []
ok = True
for n in (1, 16):
  py = load(f"cc-py-{n}")
  ja = load(f"cc-java-{n}")
  if not py or not ja:
    print(f"MISSING results for n={n}")
    ok = False
    continue
  # error / refusal agreement
  py_err = py.get("error_count", -1)
  ja_err = ja.get("error_count", -1)
  py_hz, ja_hz = hz(py), hz(ja)
  # refusals must match (both zero ideally)
  refuse_keys = [k for k in py_hz if "refusal" in k or "underflow" in k]
  refuse_ok = all((py_hz.get(k) or 0) == (ja_hz.get(k) or 0) for k in refuse_keys)
  # StreamLoad at least as fast (mib_s) and p99 no worse by >20% when both have samples
  py_mib, ja_mib = py.get("mib_s") or 0, ja.get("mib_s") or 0
  py_p99, ja_p99 = py.get("p99_us"), ja.get("p99_us")
  faster = ja_mib >= py_mib * 0.95  # allow 5% noise
  lat_ok = True
  if py_p99 is not None and ja_p99 is not None and py_p99 > 0:
    lat_ok = ja_p99 <= py_p99 * 1.20
  row_ok = refuse_ok and faster and lat_ok and py_err == ja_err
  if not row_ok:
    ok = False
  rows.append(dict(n=n, py_mib=py_mib, ja_mib=ja_mib, py_p99=py_p99, ja_p99=ja_p99,
                   py_err=py_err, ja_err=ja_err, py_hz=py_hz, ja_hz=ja_hz,
                   refuse_ok=refuse_ok, faster=faster, lat_ok=lat_ok, row_ok=row_ok,
                   py_client=py.get("client_cpu_pct_of_wall"), ja_client=ja.get("client_cpu_pct_of_wall"),
                   py_server=py.get("server_cpu_pct_of_wall"), ja_server=ja.get("server_cpu_pct_of_wall")))

print("| N | drive.py MiB/s | StreamLoad MiB/s | drive p99µs | Java p99µs | err py/java | refusals match | Java ≥ fast | ok |")
print("|---|---|---|---|---|---|---|---|---|")
for r in rows:
  print(f"| {r['n']} | {r['py_mib']:.3f} | {r['ja_mib']:.3f} | {r['py_p99']} | {r['ja_p99']} | {r['py_err']}/{r['ja_err']} | {r['refuse_ok']} | {r['faster'] and r['lat_ok']} | {r['row_ok']} |")
  print(f"  hard_zeros py={r['py_hz']}")
  print(f"  hard_zeros ja={r['ja_hz']}")
  print(f"  cpu py client/server={r['py_client']}/{r['py_server']}  ja={r['ja_client']}/{r['ja_server']}")

summary = {"ok": ok, "rows": rows}
(out / "cross-check.json").write_text(json.dumps(summary, indent=2) + "\n")
print("CROSS_CHECK:", "PASS" if ok else "FAIL")
sys.exit(0 if ok else 1)
PY
