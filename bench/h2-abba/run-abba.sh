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
  python3 -c "import sys; sys.exit(0 if float('$l') < 4 else 1)" && { echo "  [load1=$l]"; return; }
  sleep 5
done; echo "  [load1=$(cut -d' ' -f1 /proc/loadavg) GATE TIMEOUT]"; }
killports(){ fuser -k ${H1}/tcp ${H2C}/tcp ${H2}/tcp 2>/dev/null; sleep 1; }
srv(){ killports
  local dir=$1
  local jar
  jar=$(ls "$dir"/sjws_nettask-*.jar | head -1)
  $J -jar "$jar" $H1 "$WWW" --h2 --h2-port=$H2 --h2c-port=$H2C >"$B/srv-$(basename "$dir").log" 2>&1 & P=$!
  sleep 3; kill -0 $P || { echo "SERVER $dir FAILED"; cat "$B/srv-$(basename "$dir").log"; return 1; }
}
stop(){ kill $P 2>/dev/null; wait $P 2>/dev/null; sleep 1; }
L(){ nice -n 10 $J "$SCRIPT_DIR/JdkH2Load.java" "$@" 2>/dev/null | grep rps=; }
mkdir -p "$B"
echo "### ABBA h2 before=$(basename "$BEFORE_JAR") after=$(basename "$AFTER_JAR") (flock in script)"
uptime
ORDER=("$BEFORE_JAR" "$AFTER_JAR" "$AFTER_JAR" "$BEFORE_JAR")
LABELS=(before after after before)
echo "### h1 wrk -t4 -c64 -d10s --latency (ABBA)"
for i in 0 1 2 3; do
  gate; srv "${ORDER[$i]}" || continue
  wrk -t2 -c16 -d2s http://127.0.0.1:$H1/1k.bin >/dev/null
  echo "${LABELS[$i]} slot$i wrk: $(wrk -t4 -c64 -d10s --latency http://127.0.0.1:$H1/1k.bin | grep -E 'Requests/sec| 50%| 99%' | tr -s ' ' | tr '\n' ' ')"
  stop
done
echo "### h2/h2c GET static via SjwsServer main (ABBA)"
for i in 0 1 2 3; do
  gate; srv "${ORDER[$i]}" || continue
  echo "${LABELS[$i]} slot$i h2c-GET $(L http://127.0.0.1:$H2C/1k.bin 16 8 2)"
  echo "${LABELS[$i]} slot$i h2-GET  $(L https://127.0.0.1:$H2/1k.bin 16 8 2)"
  stop
done
echo DONE; uptime
