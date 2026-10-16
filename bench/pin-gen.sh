#!/bin/bash
# Runs one SwarmBench reqreply/reqreply-mw/echo with the open-loop generator pinned to a CPU set disjoint from
# the server and its workers (agent-swarm.md §13: "load generator and server pinned to disjoint CPUs").
#   SERVER_CPUS=2-7 GEN_CPUS=0-1 bench/pin-gen.sh [jvm flags...] -- <SwarmBench args...>
# The default layout (server 2-7, generator 0-1) needs a box with 8 or more cores; set both on smaller boxes.
# Box etiquette (AGENTS.md) is enforced here: the run holds flock /tmp/sjws-bench.lock (unless the caller already
# holds it and sets SJWS_BENCH_LOCKED=1), runs under nice -n 10, and refuses to start unless the 1-minute load is
# < 4 (MAX_LOAD). Load at start and end is printed as "pin-gen: load start=.. end=..".
# CLASSES (default target/classes) and SWARMBENCH (default: SwarmBench.java next to this script) pick the build
# and the harness, so one harness can drive two worktrees.
set -euo pipefail
here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
if [ "${SJWS_BENCH_LOCKED:-0}" != 1 ]; then
  SJWS_BENCH_LOCKED=1 exec flock /tmp/sjws-bench.lock "$0" "$@"
fi
SERVER_CPUS=${SERVER_CPUS:-2-7}
GEN_CPUS=${GEN_CPUS:-0-1}
CLASSES=${CLASSES:-target/classes}
SWARMBENCH=${SWARMBENCH:-$here/SwarmBench.java}
MAX_LOAD=${MAX_LOAD:-4}
need=$(( $(echo "$SERVER_CPUS,$GEN_CPUS" | tr ',' '\n' | sed 's/.*-//' | sort -n | tail -1) + 1 ))
if [ "$(nproc)" -lt "$need" ]; then echo "pin-gen: needs $need cores for SERVER_CPUS=$SERVER_CPUS GEN_CPUS=$GEN_CPUS, have $(nproc)" >&2; exit 2; fi
l0=$(cut -d' ' -f1 /proc/loadavg)
if ! awk -v l="$l0" -v m="$MAX_LOAD" 'BEGIN{exit !(l<m)}'; then echo "pin-gen: REFUSED load=$l0 >= $MAX_LOAD" >&2; exit 75; fi
jvm=(); while [ $# -gt 0 ] && [ "$1" != "--" ]; do jvm+=("$1"); shift; done; shift
ready=$(mktemp -u /tmp/sb-genready.XXXXXX)
nice -n 10 taskset -c "$SERVER_CPUS" java "${jvm[@]}" -Dsjws.bench.genReady="$ready" -cp "$CLASSES" "$SWARMBENCH" "$@" &
pid=$!
trap 'rm -f "$ready"' EXIT
tid=""
for _ in $(seq 1 3000); do
  for t in /proc/$pid/task/*; do
    if [ "$(cat "$t/comm" 2>/dev/null)" = "sb-gen" ]; then tid=${t##*/}; break 2; fi
  done
  kill -0 $pid 2>/dev/null || break
  sleep 0.01
done
rc=0
if [ -z "$tid" ]; then echo "pin-gen: no sb-gen thread (mode without a generator?)" >&2; touch "$ready"; wait $pid || rc=$?
else
  taskset -pc "$GEN_CPUS" "$tid" >/dev/null
  echo "pin-gen: server cpus=$SERVER_CPUS gen tid=$tid cpus=$(taskset -pc "$tid" | sed 's/.*: //')" >&2
  touch "$ready"
  wait $pid || rc=$?
fi
echo "pin-gen: load start=$l0 end=$(cut -d' ' -f1 /proc/loadavg)"
exit $rc
