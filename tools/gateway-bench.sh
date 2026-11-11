#!/bin/sh
# Standalone vs gateway-mode-off HTTP/1.1 benchmark (wrk).
# Both modes leave the control plane detached (docs/design/control-plane.md, PR #38).
# They must land in the same band: the mode-off path adds no request-path work.
#
# Usage:
#   tools/gateway-bench.sh
# Env:
#   GATEWAY_BENCH_WARMUP     wrk warmup duration (default 5s)
#   GATEWAY_BENCH_DURATION   measured wrk duration (default 30s)
#   GATEWAY_BENCH_THREADS    wrk -t (default 4)
#   GATEWAY_BENCH_CONNECTIONS wrk -c (default 128)
#   GATEWAY_BENCH_MAX_DELTA  fail when abs(gateway-off/standalone - 1) exceeds this
#                            (unset: print the ratio, do not fail on it)
#   GATEWAY_BENCH_WRK2       auto (default; run wrk2 when it is on PATH), 1 (required),
#                            or 0 (skip). Fixed-rate p99 is printed; it does not fail
#                            the script on magnitude (no absolute floor has been measured).
#   GATEWAY_BENCH_WRK2_FRACTION  fraction of that mode's wrk req/s used as wrk2 -R (default 0.7)
#   GATEWAY_BENCH_WRK2_RATE  optional absolute wrk2 -R for both modes
#
# After the wrk (and optional wrk2) pass the script prints the server RSS in kB.
# A missing RSS reading fails the script; the value itself is not gated.
#
# The official comparison is warmup 5s, duration 30s, -t4 -c128, 1 KiB file, loopback.
# Each mode binds an ephemeral port (GatewayBench --port=0) and prints it.
set -eu
cd "$(dirname "$0")/.."
WARMUP="${GATEWAY_BENCH_WARMUP:-5s}"
DURATION="${GATEWAY_BENCH_DURATION:-30s}"
THREADS="${GATEWAY_BENCH_THREADS:-4}"
CONNS="${GATEWAY_BENCH_CONNECTIONS:-128}"
ROOT="$(mktemp -d)"
LOG="$(mktemp)"
trap 'rm -rf "$ROOT"; rm -f "$LOG" "$LOG.wrk" "$LOG.wrk2"; [ -n "${PID:-}" ] && kill "$PID" 2>/dev/null || true' EXIT
mkdir -p "$ROOT/html"
dd if=/dev/zero of="$ROOT/html/index.html" bs=1024 count=1 status=none
if [ ! -d target/classes ] || [ ! -d target/lib ]; then
  mvn -B -q -DskipTests package
fi
CP="target/classes:target/sjws_nettask-0.2-SNAPSHOT.jar:target/lib/*"
JAVA="${JAVA_HOME:-}/bin/java"
if [ ! -x "$JAVA" ]; then
  JAVA=java
fi

run_mode() {
  mode="$1"
  : >"$LOG"
  "$JAVA" -cp "$CP" bench/GatewayBench.java --mode="$mode" --port=0 --root="$ROOT/html" >"$LOG" &
  PID=$!
  port=""
  i=0
  while [ -z "$port" ]; do
    i=$((i + 1))
    if [ "$i" -gt 50 ]; then
      echo "server did not start ($mode)" >&2
      cat "$LOG" >&2 || true
      exit 1
    fi
    if ! kill -0 "$PID" 2>/dev/null; then
      echo "server exited ($mode)" >&2
      cat "$LOG" >&2 || true
      exit 1
    fi
    port=$(sed -n 's/^bench port=\([0-9][0-9]*\) .*/\1/p' "$LOG" | head -1)
    if [ -z "$port" ]; then
      sleep 0.1
    fi
  done
  i=0
  while ! curl -sf -o /dev/null "http://127.0.0.1:$port/index.html"; do
    i=$((i + 1))
    if [ "$i" -gt 50 ]; then
      echo "listener not ready ($mode port $port)" >&2
      exit 1
    fi
    sleep 0.1
  done
  echo "## $mode port=$port"
  wrk -t"$THREADS" -c"$CONNS" -d"$WARMUP" --latency "http://127.0.0.1:$port/index.html" >/dev/null
  wrk -t"$THREADS" -c"$CONNS" -d"$DURATION" --latency -s tools/gateway-bench.lua \
    "http://127.0.0.1:$port/index.html" | tee "$LOG.wrk"
  line=$(grep '^wrk req/s ' "$LOG.wrk" | tail -1)
  if [ -z "$line" ]; then
    echo "wrk produced no summary ($mode)" >&2
    exit 1
  fi
  req=$(printf '%s\n' "$line" | awk '{print $3}')
  err=$(printf '%s\n' "$line" | awk '{print $5}')
  case "$req" in
    ''|0|0.00) echo "zero throughput ($mode)" >&2; exit 1 ;;
  esac
  if [ "$err" != "0" ]; then
    echo "request errors in $mode: $err" >&2
    exit 1
  fi
  printf '%s\n' "$req" > "$ROOT/${mode}.req"

  rss=$(ps -o rss= -p "$PID" 2>/dev/null | tr -d ' \t')
  if [ -z "$rss" ] || [ "$rss" = "0" ]; then
    echo "could not read RSS ($mode pid $PID)" >&2
    exit 1
  fi
  echo "rss_kb=$rss ($mode)"
  printf '%s\n' "$rss" > "$ROOT/${mode}.rss"

  run_wrk2=0
  case "${GATEWAY_BENCH_WRK2:-auto}" in
    1|yes|true) run_wrk2=1 ;;
    0|no|false) run_wrk2=0 ;;
    auto)
      if command -v wrk2 >/dev/null 2>&1; then
        run_wrk2=1
      fi
      ;;
    *)
      echo "GATEWAY_BENCH_WRK2 must be auto, 1, or 0" >&2
      exit 1
      ;;
  esac
  if [ "$run_wrk2" = 1 ]; then
    if ! command -v wrk2 >/dev/null 2>&1; then
      echo "wrk2 is required (GATEWAY_BENCH_WRK2=${GATEWAY_BENCH_WRK2}) but is not on PATH" >&2
      exit 1
    fi
    fraction=${GATEWAY_BENCH_WRK2_FRACTION:-0.7}
    if [ -n "${GATEWAY_BENCH_WRK2_RATE:-}" ]; then
      rate=$GATEWAY_BENCH_WRK2_RATE
    else
      rate=$(awk -v r="$req" -v f="$fraction" 'BEGIN { v=int(r * f); if (v < 1) v=1; print v }')
    fi
    echo "wrk2 -R$rate ($mode, fraction $fraction of wrk req/s unless GATEWAY_BENCH_WRK2_RATE is set)"
    wrk2 -t"$THREADS" -c"$CONNS" -d"$DURATION" -R"$rate" -L \
      "http://127.0.0.1:$port/index.html" | tee "$LOG.wrk2"
    if ! grep -q 'Requests/sec:' "$LOG.wrk2"; then
      echo "wrk2 produced no summary ($mode)" >&2
      exit 1
    fi
    rss=$(ps -o rss= -p "$PID" 2>/dev/null | tr -d ' \t')
    if [ -n "$rss" ] && [ "$rss" != "0" ]; then
      echo "rss_kb=$rss ($mode after wrk2)"
      printf '%s\n' "$rss" > "$ROOT/${mode}.rss"
    fi
  fi

  kill "$PID" 2>/dev/null || true
  wait "$PID" 2>/dev/null || true
  PID=
}

run_mode standalone
run_mode gateway-off

standalone_req=$(cat "$ROOT/standalone.req")
gateway_off_req=$(cat "$ROOT/gateway-off.req")
echo "standalone req/s=${standalone_req}"
echo "gateway-off req/s=${gateway_off_req}"
if [ -f "$ROOT/standalone.rss" ] && [ -f "$ROOT/gateway-off.rss" ]; then
  echo "standalone rss_kb=$(cat "$ROOT/standalone.rss")"
  echo "gateway-off rss_kb=$(cat "$ROOT/gateway-off.rss")"
fi
ratio=$(awk -v a="$standalone_req" -v b="$gateway_off_req" 'BEGIN { printf "%.4f", b / a }')
echo "ratio gateway-off/standalone=${ratio}"
if [ -n "${GATEWAY_BENCH_MAX_DELTA:-}" ]; then
  awk -v r="$ratio" -v d="$GATEWAY_BENCH_MAX_DELTA" 'BEGIN {
    diff = r - 1
    if (diff < 0) diff = -diff
    if (diff > d) {
      printf "ratio %s is outside ±%s of 1\n", r, d > "/dev/stderr"
      exit 1
    }
  }'
fi
