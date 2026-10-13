#!/usr/bin/env bash
# Interleaved A/B wrk benchmark of two sjws_nettask builds on one host.
# Usage: tools/perf-gate/bench.sh BASE_JAR HEAD_JAR OUT_DIR
# Env: SERVER_ARGS (extra server options, e.g. "--role=standalone --config=FILE"),
#      JAVA_OPTS (extra JVM options), RUNS (5), DURATION (10s), WARMUP (5s), THREADS (2), CONNECTIONS (64), BODY_BYTES (1024)
# Each server runs in standalone mode (default; no cluster/gateway flags) with access logging off.
# Rounds alternate base/head so host drift hits both equally. Writes OUT_DIR/{base,head}.jsonl.
set -euo pipefail
BASE_JAR="$1" HEAD_JAR="$2" OUT="$3"
RUNS="${RUNS:-5}" DURATION="${DURATION:-10s}" WARMUP="${WARMUP:-5s}"
THREADS="${THREADS:-2}" CONNECTIONS="${CONNECTIONS:-64}" BODY_BYTES="${BODY_BYTES:-1024}"
HERE="$(cd "$(dirname "$0")" && pwd)"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
mkdir -p "$OUT"; : > "$OUT/base.jsonl"; : > "$OUT/head.jsonl"
ROOT="$(mktemp -d)"
PIDS=()
cleanup() { for p in "${PIDS[@]}"; do kill "$p" 2>/dev/null || true; done; rm -rf "$ROOT"; }
trap cleanup EXIT
head -c "$BODY_BYTES" /dev/zero > "$ROOT/index.html"

start() { # name jar port
  # shellcheck disable=SC2086
  "$JAVA" -Xms256m -Xmx256m ${JAVA_OPTS:-} -jar "$2" "$3" "$ROOT" ${SERVER_ARGS:-} > "$OUT/$1-server.log" 2>&1 &
  PIDS+=($!)
  for _ in $(seq 100); do
    curl -sf -o /dev/null "http://127.0.0.1:$3/index.html" && return 0
    sleep 0.2
  done
  echo "$1 server did not start; log:" >&2; cat "$OUT/$1-server.log" >&2; exit 1
}
wrk_once() { # url duration
  wrk -t"$THREADS" -c"$CONNECTIONS" -d"$2" --latency -s "$HERE/report.lua" "$1" | sed -n 's/^PERF_JSON //p'
}

start base "$BASE_JAR" 18081
start head "$HEAD_JAR" 18082
for port in 18081 18082; do wrk_once "http://127.0.0.1:$port/index.html" "$WARMUP" > /dev/null; done
for r in $(seq "$RUNS"); do
  # alternate order each round
  if (( r % 2 )); then order="base:18081 head:18082"; else order="head:18082 base:18081"; fi
  for e in $order; do
    name="${e%%:*}" port="${e##*:}"
    line="$(wrk_once "http://127.0.0.1:$port/index.html" "$DURATION")"
    echo "round $r $name $line"
    echo "$line" >> "$OUT/$name.jsonl"
  done
done
