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
trap 'rm -rf "$ROOT"; rm -f "$LOG" "$LOG.wrk"; [ -n "${PID:-}" ] && kill "$PID" 2>/dev/null || true' EXIT
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
