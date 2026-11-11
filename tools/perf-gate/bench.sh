#!/usr/bin/env bash
# Interleaved A/B wrk (and optional wrk2) benchmark of two sjws_nettask builds on one host.
# Usage: tools/perf-gate/bench.sh BASE_JAR HEAD_JAR OUT_DIR
# Env: SERVER_ARGS (extra server options, e.g. "--role=standalone --config=FILE"),
#      JAVA_OPTS (extra JVM options), RUNS (5), DURATION (10s), WARMUP (5s), THREADS (2),
#      CONNECTIONS (64), BODY_BYTES (1024),
#      WRK2 (auto|1|0; default auto: run wrk2 when it is on PATH),
#      REQUIRE_WRK2 (1: fail if wrk2 is missing),
#      WRK2_RUNS (3), WRK2_DURATION (20s; independent of DURATION so wrk2 runs past its 10s calibration),
#      WRK2_FRACTION (0.7 of that side's median wrk req/s; WRK2_RATE overrides),
#      WRK2_RATE (optional absolute rate for both sides; do not invent a default).
# Each server runs in standalone mode (default; no cluster/gateway flags) with access logging off.
# Rounds alternate base/head so host drift hits both equally.
# Writes OUT_DIR/{base,head}.jsonl: wrk lines (no kind, or kind=wrk), kind=wrk2, and kind=rss.
set -euo pipefail
BASE_JAR="$1" HEAD_JAR="$2" OUT="$3"
RUNS="${RUNS:-5}" DURATION="${DURATION:-10s}" WARMUP="${WARMUP:-5s}"
THREADS="${THREADS:-2}" CONNECTIONS="${CONNECTIONS:-64}" BODY_BYTES="${BODY_BYTES:-1024}"
WRK2_MODE="${WRK2:-auto}"
WRK2_RUNS="${WRK2_RUNS:-3}"
WRK2_DURATION="${WRK2_DURATION:-20s}"
WRK2_FRACTION="${WRK2_FRACTION:-0.7}"
HERE="$(cd "$(dirname "$0")" && pwd)"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
mkdir -p "$OUT"; : > "$OUT/base.jsonl"; : > "$OUT/head.jsonl"
ROOT="$(mktemp -d)"
PIDS=()
declare -A SERVER_PID
cleanup() { for p in "${PIDS[@]}"; do kill "$p" 2>/dev/null || true; done; rm -rf "$ROOT"; }
trap cleanup EXIT
head -c "$BODY_BYTES" /dev/zero > "$ROOT/index.html"

start() { # name jar port
  # shellcheck disable=SC2086
  "$JAVA" -Xms256m -Xmx256m ${JAVA_OPTS:-} -jar "$2" "$3" "$ROOT" ${SERVER_ARGS:-} > "$OUT/$1-server.log" 2>&1 &
  SERVER_PID[$1]=$!
  PIDS+=("${SERVER_PID[$1]}")
  for _ in $(seq 100); do
    curl -sf -o /dev/null "http://127.0.0.1:$3/index.html" && return 0
    sleep 0.2
  done
  echo "$1 server did not start; log:" >&2; cat "$OUT/$1-server.log" >&2; exit 1
}

wrk_once() { # url duration
  wrk -t"$THREADS" -c"$CONNECTIONS" -d"$2" --latency -s "$HERE/report.lua" "$1" | sed -n 's/^PERF_JSON //p'
}

rss_kb() { # pid
  local kb
  kb=$(ps -o rss= -p "$1" 2>/dev/null | tr -d ' [:space:]')
  if [ -z "$kb" ] || [ "$kb" = "0" ]; then
    echo "could not read RSS for pid $1" >&2
    return 1
  fi
  printf '%s' "$kb"
}

record_rss() { # name phase
  local kb
  kb=$(rss_kb "${SERVER_PID[$1]}")
  echo "{\"kind\":\"rss\",\"phase\":\"$2\",\"rss_kb\":$kb}" >> "$OUT/$1.jsonl"
  echo "rss $1 $2 ${kb}kB"
}

median_rps() { # jsonl
  python3 -c '
import json, statistics, sys
rows = []
for line in open(sys.argv[1]):
    line = line.strip()
    if not line:
        continue
    row = json.loads(line)
    if row.get("kind", "wrk") == "wrk" and "rps" in row:
        rows.append(row["rps"])
if not rows:
    sys.exit("no wrk rps in " + sys.argv[1])
print(int(statistics.median(rows)))
' "$1"
}

have_wrk2() {
  command -v wrk2 >/dev/null 2>&1
}

want_wrk2() {
  case "$WRK2_MODE" in
    1|yes|true) return 0 ;;
    0|no|false) return 1 ;;
    auto)
      if have_wrk2; then
        return 0
      fi
      return 1
      ;;
    *)
      echo "WRK2 must be auto, 1, or 0 (got $WRK2_MODE)" >&2
      exit 1
      ;;
  esac
}

parse_wrk2_fallback() { # stdout, rate
  python3 -c '
import json, re, sys
text, rate = sys.argv[1], int(sys.argv[2])

def to_us(token):
    token = token.strip().lower().replace(" ", "")
    m = re.fullmatch(r"([0-9]+(?:\.[0-9]+)?)(us|ms|s)", token)
    if not m:
        return None
    value = float(m.group(1))
    unit = m.group(2)
    if unit == "us":
        return int(round(value))
    if unit == "ms":
        return int(round(value * 1000))
    return int(round(value * 1_000_000))

p50 = p99 = None
for pct, key in ((r"50(?:\.0+)?%", "p50"), (r"99(?:\.0+)?%", "p99")):
    m = re.search(rf"{pct}\s+([0-9.]+\s*[um]?s)", text, re.I)
    if not m:
        continue
    us = to_us(m.group(1))
    if key == "p50":
        p50 = us
    else:
        p99 = us
rps = None
m = re.search(r"Requests/sec:\s*([0-9.]+)", text)
if m:
    rps = float(m.group(1))
errs = 0
# wrk2 prints Socket errors / Non-2xx / timeout lines in several layouts.
for pat in (r"Socket errors:\s*[^\n]*", r"Non-2xx or 3xx responses:\s*(\d+)", r"timeout[s]?:\s*(\d+)"):
    m = re.search(pat, text, re.I)
    if not m:
        continue
    nums = [int(x) for x in re.findall(r"(\d+)", m.group(0))]
    errs += sum(nums)
requests = 0
m = re.search(r"(\d+)\s+requests in", text, re.I)
if m:
    requests = int(m.group(1))
if p99 is None or rps is None:
    sys.exit("could not parse wrk2 output")
if p50 is None:
    p50 = p99
print(json.dumps({
    "kind": "wrk2",
    "rps": rps,
    "p50_us": p50,
    "p99_us": p99,
    "errors": errs,
    "requests": requests,
    "rate": rate,
}, separators=(",", ":")))
' "$1" "$2"
}

decorate_wrk2() { # json-line rate
  python3 -c '
import json, sys
row = json.loads(sys.argv[1])
row["kind"] = "wrk2"
row["rate"] = int(sys.argv[2])
print(json.dumps(row, separators=(",", ":")))
' "$1" "$2"
}

wrk2_once() { # url duration rate
  local url="$1" duration="$2" rate="$3" raw line

  try_wrk2() { # remaining args are wrk2 flags after wrk2 itself
    set +e
    raw=$(wrk2 "$@" 2>&1)
    local rc=$?
    set -e
    line=$(printf '%s\n' "$raw" | sed -n 's/^PERF_JSON //p' | tail -n 1)
    if [ -n "$line" ]; then
      decorate_wrk2 "$line" "$rate"
      return 0
    fi
    if printf '%s\n' "$raw" | grep -q 'Requests/sec:'; then
      parse_wrk2_fallback "$raw" "$rate"
      return 0
    fi
    return "$rc"
  }

  # Prefer the shared report.lua JSON line; wrk2's Lua API is not identical to wrk
  # on every build, so a missing PERF_JSON line falls back to parsing -L text, and
  # a Lua failure is retried without a script. Both measured paths keep -L (HdrHistogram).
  # A run without -L must not be treated as a p99 measurement: it would let the gate
  # pass on uncorrected summary text.
  if try_wrk2 -t"$THREADS" -c"$CONNECTIONS" -d"$duration" -R"$rate" -L -s "$HERE/report.lua" "$url"; then
    return 0
  fi
  if try_wrk2 -t"$THREADS" -c"$CONNECTIONS" -d"$duration" -R"$rate" -L "$url"; then
    return 0
  fi
  echo "wrk2 -L produced no usable output (rate $rate); not falling back without -L because that cannot measure corrected p99:" >&2
  printf '%s\n' "$raw" >&2
  return 1
}

start base "$BASE_JAR" 18081
start head "$HEAD_JAR" 18082
for port in 18081 18082; do wrk_once "http://127.0.0.1:$port/index.html" "$WARMUP" > /dev/null; done
record_rss base warmup
record_rss head warmup
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
record_rss base after-wrk
record_rss head after-wrk

if want_wrk2; then
  if ! have_wrk2; then
    echo "wrk2 is required but not on PATH" >&2
    exit 1
  fi
  wrk2_rate_for() { # name
    if [ -n "${WRK2_RATE:-}" ]; then
      printf '%s' "$WRK2_RATE"
      return
    fi
    local median rate
    median=$(median_rps "$OUT/$1.jsonl")
    rate=$(python3 -c 'import math,sys; print(max(1, int(float(sys.argv[1]) * float(sys.argv[2]))))' "$median" "$WRK2_FRACTION")
    printf '%s' "$rate"
  }
  rate_base=$(wrk2_rate_for base)
  rate_head=$(wrk2_rate_for head)
  echo "wrk2 rates (fraction ${WRK2_FRACTION} of median wrk rps, or WRK2_RATE): base=$rate_base head=$rate_head"
  for r in $(seq "$WRK2_RUNS"); do
    if (( r % 2 )); then order="base:18081:$rate_base head:18082:$rate_head"
    else order="head:18082:$rate_head base:18081:$rate_base"; fi
    for e in $order; do
      name="${e%%:*}"; rest="${e#*:}"; port="${rest%%:*}"; rate="${rest##*:}"
      line="$(wrk2_once "http://127.0.0.1:$port/index.html" "$WRK2_DURATION" "$rate")"
      echo "wrk2 round $r $name $line"
      echo "$line" >> "$OUT/$name.jsonl"
    done
  done
  record_rss base after-wrk2
  record_rss head after-wrk2
elif [ "${REQUIRE_WRK2:-}" = "1" ]; then
  echo "wrk2 is required (REQUIRE_WRK2=1) but WRK2=$WRK2_MODE and wrk2 is not available" >&2
  exit 1
else
  echo "wrk2 skipped (WRK2=$WRK2_MODE, wrk2 $(have_wrk2 && echo present || echo missing))"
fi
