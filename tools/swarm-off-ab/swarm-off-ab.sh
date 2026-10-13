#!/usr/bin/env bash
# Swarm-off wrk A/B (docs/design/agent-swarm.md §13 item 3). Not run in CI.
# Usage: swarm-off-ab.sh <base-jar> <head-jar> [runs=5] [mode=standalone|yaml-off]
# Alternates base/head runs, pins server and wrk to disjoint CPUs, reports mean ± stddev of req/s and p99.
set -euo pipefail
BASE=${1:?base jar}; HEAD=${2:?head jar}; RUNS=${3:-5}; MODE=${4:-standalone}
PORT=${PORT:-18080}; SERVER_CPUS=${SERVER_CPUS:-0-3}; WRK_CPUS=${WRK_CPUS:-4-7}
WRK_ARGS=${WRK_ARGS:--t4 -c128 -d30s --latency}
WORK=$(mktemp -d); trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/www"; head -c 1024 /dev/urandom > "$WORK/www/1k.bin"
printf 'swarm:\n  enabled: false\n' > "$WORK/off.yaml"
echo "host: $(uname -srm); cpu: $(grep -m1 'model name' /proc/cpuinfo | cut -d: -f2); nproc: $(nproc); java: $(java -version 2>&1 | head -1)"
run() { # jar label
  local args=("$PORT" "$WORK/www")
  [ "$MODE" = yaml-off ] && args+=(--config "$WORK/off.yaml")
  env -u SJWS_SWARM_ENABLED taskset -c "$SERVER_CPUS" java -jar "$1" "${args[@]}" >/dev/null 2>&1 & local pid=$!
  for _ in $(seq 100); do curl -fs "http://127.0.0.1:$PORT/1k.bin" -o /dev/null && break; sleep 0.1; done
  taskset -c "$WRK_CPUS" wrk $WRK_ARGS "http://127.0.0.1:$PORT/1k.bin" > "$WORK/$2.out"
  kill "$pid"; wait "$pid" 2>/dev/null || true
  awk -v l="$2" '/Requests\/sec/{r=$2} /^ +99%/{p=$2} END{print l, r, p}' "$WORK/$2.out" >> "$WORK/results"
}
for i in $(seq "$RUNS"); do run "$BASE" "base.$i"; run "$HEAD" "head.$i"; done
awk '{split($1,a,"."); n[a[1]]++; s[a[1]]+=$2; q[a[1]]+=$2*$2; print}
     END{for(k in n){m=s[k]/n[k]; printf "%s req/s mean %.0f ± %.0f (n=%d)\n",k,m,sqrt(q[k]/n[k]-m*m),n[k]}}' "$WORK/results"
echo "Pass when |head-base| lies within the A/A spread (run base vs base to measure it)."
