#!/usr/bin/env bash
# GC A/B matrix: HTTP (wrk2, 1 KiB / 64 KiB), messaging produce/fetch (in-process),
# SwarmBench reqreply (shards=1,4), startup and idle RSS, per JVM config.
# Configs alternate within each rep. Each rep takes the shared bench lock and refuses to start
# unless the 1-minute load is below MAX_LOAD (default 4). Output: one key=value line per measurement.
# Usage: bench/gc/run.sh [reps] [configs...]   (after `mvn -B -q -DskipTests package`)
set -euo pipefail
cd "$(dirname "$0")/../.."
JAVA=${JAVA_HOME:-$HOME/jdk25}/bin/java
if [ "${1:-}" != __block ]; then REPS=${1:-3}; shift || true; CONFIGS=${*:-g1 g1tuned zgc zgcpinned serial}; fi
MAX_LOAD=${MAX_LOAD:-4}
OUT=${OUT:-bench/gc/results}; mkdir -p "$OUT"
JAR=$(ls target/sjws_nettask-*.jar | grep -v sources | head -1)
flags() { case $1 in
  g1)        echo "-XX:+UseG1GC" ;;
  g1tuned)   echo "-XX:+UseG1GC -Xms1g -Xmx1g -Xmn400m -XX:+ParallelRefProcEnabled" ;;
  g1pretouch) echo "-XX:+UseG1GC -Xms1g -Xmx1g -Xmn400m -XX:+AlwaysPreTouch" ;;
  zgc)       echo "-XX:+UseZGC" ;;
  zgcpinned) echo "-XX:+UseZGC -Xms1g -Xmx1g -XX:SoftMaxHeapSize=768m" ;;
  serial)    echo "-XX:+UseSerialGC -Xmx256m" ;;
  parallel)  echo "-XX:+UseParallelGC -Xmx256m" ;;
  *) echo "unknown config $1" >&2; exit 2 ;; esac; }
load() { cut -d' ' -f1 /proc/loadavg; }
waitload() { while awk -v l="$(load)" -v m="$MAX_LOAD" 'BEGIN{exit !(l>=m)}'; do echo "# load $(load) >= $MAX_LOAD, waiting" >&2; sleep 30; done; }
pauses() { # STW pauses from -Xlog:gc,gc+phases (G1/Serial/Parallel "Pause Young/Full", ZGC "Pause Mark Start/End/Relocate Start") + collection count
  awk '/Pause/ && /ms$/ {n++; v=$NF; sub("ms","",v); s+=v; if (v>m) m=v} /\[gc *\]/ && /->/ {c++} END{printf "gc_collections=%d gc_pauses=%d gc_pause_total_ms=%.1f gc_pause_max_ms=%.2f", c, n, s, m}' "$1"; }
wrkline() { awk '/Requests\/sec/{printf "rps=%s ",$2} / 50.000%/{printf "p50=%s ",$2} / 99.000%/{printf "p99=%s ",$2} / 99.900%/{printf "p999=%s ",$2} /Non-2xx|Socket errors/{printf "errors=\"%s\" ",$0}'; }
run_http() { # $1 cfg $2 rep
  local root; root=$(mktemp -d); head -c 1024 /dev/urandom > "$root/1k.bin"; head -c 65536 /dev/urandom > "$root/64k.bin"
  local port=18190 gl="$OUT/http-$1-$2.gc.log"
  local t0; t0=$(date +%s%N)
  taskset -c 0-3 "$JAVA" $(flags "$1") -Xlog:gc,gc+phases=info:file="$gl" -jar "$JAR" $port "$root" >/dev/null 2>&1 &
  local pid=$!
  local i=0; until curl -sf -o /dev/null "http://127.0.0.1:$port/1k.bin"; do sleep 0.01; i=$((i+1)); [ $i -lt 2000 ] || { echo "cfg=$1 rep=$2 bench=startup error=no-start"; kill $pid; return; }; done
  echo "cfg=$1 rep=$2 bench=startup ms=$(( ($(date +%s%N)-t0)/1000000 ))"
  sleep 3; echo "cfg=$1 rep=$2 bench=idle rss_kb=$(awk '/VmRSS/{print $2}' /proc/$pid/status)"
  for f in 1k:20000 64k:4000; do
    local file=${f%%:*} rate=${f##*:}
    taskset -c 4-5 wrk2 -t2 -c64 -d5s -R$rate "http://127.0.0.1:$port/$file.bin" >/dev/null
    echo "cfg=$1 rep=$2 bench=http_$file rate=$rate load=$(load) $(taskset -c 4-5 wrk2 -t2 -c64 -d15s -R$rate --latency "http://127.0.0.1:$port/$file.bin" | wrkline)"
  done
  echo "cfg=$1 rep=$2 bench=http_after rss_kb=$(awk '/VmRSS/{print $2}' /proc/$pid/status) hwm_kb=$(awk '/VmHWM/{print $2}' /proc/$pid/status) $(pauses "$gl")"
  kill $pid; wait $pid 2>/dev/null || true; rm -rf "$root"
}
run_msg() {
  for op in produce fetch; do
    local d; d=$(mktemp -d) gl="$OUT/msg-$op-$1-$2.gc.log"
    echo "cfg=$1 rep=$2 bench=msg_$op load=$(load) $(timeout 180 taskset -c 0-5 "$JAVA" $(flags "$1") -Xlog:gc,gc+phases=info:file="$gl" -cp target/classes bench/MessagingLogBench.java "$d" $op 1024 4 10 | tail -1) $(pauses "$gl")"
    rm -rf "$d"
  done
}
run_swarm() {
  for sh in 1 4; do
    local d; d=$(mktemp -d) gl="$OUT/rr-s$sh-$1-$2.gc.log"
    echo "cfg=$1 rep=$2 bench=reqreply_s$sh load=$(load) $(timeout 180 taskset -c 0-5 "$JAVA" $(flags "$1") -Dsjws.swarm.pool.shards=$sh -Xlog:gc,gc+phases=info:file="$gl" -cp target/classes bench/SwarmBench.java "$d" reqreply 10000 12 | grep '^reqreply') $(pauses "$gl")"
    rm -rf "$d"
  done
}
if [ "${1:-}" = __block ]; then run_http "$2" "$3"; run_msg "$2" "$3"; run_swarm "$2" "$3"; exit; fi
for rep in $(seq "$REPS"); do
  for cfg in $CONFIGS; do
    [ -n "${SKIP:-}" ] && grep -qx "$cfg:$rep" <<<"$(tr ' ' '\n' <<<"$SKIP")" && continue
    waitload
    flock /tmp/sjws-bench.lock "$0" __block "$cfg" "$rep" | tee -a "$OUT/results.txt"
  done
done