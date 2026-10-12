#!/bin/sh
# Single-node vs cluster-mode HTTP/1.1 benchmark (wrk + wrk2).
# Usage: tools/ha-bench.sh [port]
# Prints req/s and p50/p90/p99/p99.9. Cluster mode attaches HaSettings (snapshot read + probes).
set -eu
cd "$(dirname "$0")/.."
PORT="${1:-18080}"
ROOT="$(mktemp -d)"
trap 'rm -rf "$ROOT"; [ -n "${PID:-}" ] && kill "$PID" 2>/dev/null || true' EXIT
mkdir -p "$ROOT/html"
dd if=/dev/zero of="$ROOT/html/index.html" bs=1024 count=1 status=none
if [ ! -d target/classes ]; then
  mvn -B -q -DskipTests package
fi
CP="target/classes:target/sjws_nettask-0.2-SNAPSHOT.jar:target/lib/*"
JAVA="${JAVA_HOME:-}/bin/java"
if [ ! -x "$JAVA" ]; then
  JAVA=java
fi

run_mode() {
  mode="$1"
  "$JAVA" -cp "$CP" sjws_nettask.ha.BenchServer --mode="$mode" --port="$PORT" --root="$ROOT/html" &
  PID=$!
  i=0
  while ! curl -sf -o /dev/null "http://127.0.0.1:$PORT/index.html"; do
    i=$((i + 1))
    if [ "$i" -gt 50 ]; then
      echo "server did not start ($mode)" >&2
      exit 1
    fi
    sleep 0.1
  done
  echo "## $mode"
  wrk -t4 -c128 -d5s --latency "http://127.0.0.1:$PORT/index.html" >/dev/null
  wrk -t4 -c128 -d30s --latency -s tools/ha-bench.lua "http://127.0.0.1:$PORT/index.html"
  # Fixed rate: 70% of a short wrk sample is chosen by the caller via WRK2_RATE, else 50000.
  rate="${WRK2_RATE:-50000}"
  wrk2 -t4 -c128 -d30s -R"$rate" -L "http://127.0.0.1:$PORT/index.html" || true
  kill "$PID" 2>/dev/null || true
  wait "$PID" 2>/dev/null || true
  PID=
}

run_mode single
run_mode cluster
