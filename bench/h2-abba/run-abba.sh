#!/bin/bash
# ABBA h1 wrk + h2/h2c static GET through SjwsServer main.
# Takes flock itself (Architecture N2 / AGENTS.md); waits for load1 < 4 and records it.
set -uo pipefail
exec 9>/tmp/sjws-bench.lock
flock 9
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
B=${BENCH_DIR:-/tmp/sjws-h2-abba}
J=$(command -v java)
H1=${H1:-32280}; H2C=${H2C:-32281}; H2=${H2:-32544}
BEFORE_JAR=${1:?before jar dir (contains sjws_nettask-*.jar and lib/)}
AFTER_JAR=${2:?after jar dir}
WWW=${3:?docroot with 1k.bin}
gate(){ for _ in $(seq 1 90); do
  l=$(cut -d' ' -f1 /proc/loadavg)
  python3 -c "import sys; sys.exit(0 if float('$l') < 4 else 1)" && { echo "  [load1=$l]"; return 0; }
  sleep 5
done
  echo "  [load1=$(cut -d' ' -f1 /proc/loadavg) GATE TIMEOUT]" >&2
  return 1
}
killports(){ fuser -k ${H1}/tcp ${H2C}/tcp ${H2}/tcp 2>/dev/null; sleep 1; }
srv(){ killports
  local dir=$1
  local jar
  jar=$(ls "$dir"/sjws_nettask-*.jar | head -1)
  $J -jar "$jar" $H1 "$WWW" --h2 --h2-port=$H2 --h2c-port=$H2C \
    --cert="$B/bench-cert.pem" --key="$B/bench-key.pem" \
    >"$B/srv-$(basename "$dir").log" 2>&1 & P=$!
  sleep 3; kill -0 $P || { echo "SERVER $dir FAILED"; cat "$B/srv-$(basename "$dir").log"; return 1; }
}
stop(){ kill $P 2>/dev/null; wait $P 2>/dev/null; sleep 1; }
# One measured load: gate, run, record ending load, mark NOISY if load1>=4 (AGENTS.md / Codex P1).
measure_h2(){
  local label=$1 slot=$2 kind=$3 url=$4
  gate || exit 2
  local out rc=0
  out=$(SJWS_BENCH_CA="$B/bench-cert.pem" nice -n 10 $J "$SCRIPT_DIR/JdkH2Load.java" "$url" 16 8 2 2>/dev/null | grep rps=) || rc=$?
  local end_load
  end_load=$(cut -d' ' -f1 /proc/loadavg)
  local tag=""
  python3 -c "import sys; sys.exit(0 if float('$end_load') < 4 else 1)" || tag=" NOISY"
  if [[ $rc -ne 0 || -z "$out" ]]; then
    echo "${label} slot${slot} ${kind} FAIL end_load1=${end_load}${tag}"
    return 1
  fi
  echo "${label} slot${slot} ${kind} ${out} end_load1=${end_load}${tag}"
}
mkdir -p "$B"
# Bench-only self-signed cert (trusted by JdkH2Load via SJWS_BENCH_CA) — avoids trust-all.
if [[ ! -f "$B/bench-cert.pem" || ! -f "$B/bench-key.pem" ]]; then
  openssl req -x509 -newkey rsa:2048 -keyout "$B/bench-key.pem" -out "$B/bench-cert.pem" \
    -days 1 -nodes -subj "/CN=localhost" -addext "subjectAltName=DNS:localhost,IP:127.0.0.1" 2>/dev/null
fi
echo "### ABBA h2 before=$(basename "$BEFORE_JAR") after=$(basename "$AFTER_JAR") (flock in script)"
uptime
ORDER=("$BEFORE_JAR" "$AFTER_JAR" "$AFTER_JAR" "$BEFORE_JAR")
LABELS=(before after after before)
echo "### h1 wrk -t4 -c64 -d10s --latency (ABBA)"
for i in 0 1 2 3; do
  gate || exit 2
  srv "${ORDER[$i]}" || continue
  wrk -t2 -c16 -d2s http://127.0.0.1:$H1/1k.bin >/dev/null
  echo "${LABELS[$i]} slot$i wrk: $(wrk -t4 -c64 -d10s --latency http://127.0.0.1:$H1/1k.bin | grep -E 'Requests/sec| 50%| 99%' | tr -s ' ' | tr '\n' ' ')"
  end_load=$(cut -d' ' -f1 /proc/loadavg)
  python3 -c "import sys; sys.exit(0 if float('$end_load') < 4 else 1)" || echo "  [end_load1=$end_load NOISY]"
  stop
done
echo "### h2/h2c GET static via SjwsServer main (ABBA)"
for i in 0 1 2 3; do
  gate || exit 2
  srv "${ORDER[$i]}" || continue
  # Gate each measurement independently (Codex P1): h2c then TLS, each with ending load.
  measure_h2 "${LABELS[$i]}" "$i" "h2c-GET" "http://127.0.0.1:$H2C/1k.bin" || true
  measure_h2 "${LABELS[$i]}" "$i" "h2-GET" "https://127.0.0.1:$H2/1k.bin" || true
  stop
done
echo DONE; uptime
