#!/bin/bash
# Runs one SwarmBench reqreply/reqreply-mw with the open-loop generator pinned to a CPU set disjoint from
# the server and its workers (agent-swarm.md §13: "load generator and server pinned to disjoint CPUs").
#   SERVER_CPUS=0-6 GEN_CPUS=7 bench/pin-gen.sh [jvm flags...] -- <SwarmBench args...>
# The whole JVM starts on SERVER_CPUS; once the "sb-gen" thread exists it is moved to GEN_CPUS, and only
# then does it start sending (SwarmBench waits for -Dsjws.bench.genReady). Prints the generator's affinity.
set -euo pipefail
SERVER_CPUS=${SERVER_CPUS:-0-6}
GEN_CPUS=${GEN_CPUS:-7}
jvm=(); while [ $# -gt 0 ] && [ "$1" != "--" ]; do jvm+=("$1"); shift; done; shift
ready=$(mktemp -u /tmp/sb-genready.XXXXXX)
taskset -c "$SERVER_CPUS" java "${jvm[@]}" -Dsjws.bench.genReady="$ready" -cp target/classes bench/SwarmBench.java "$@" &
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
if [ -z "$tid" ]; then echo "pin-gen: no sb-gen thread (mode without a generator?)" >&2; touch "$ready"; wait $pid; exit $?; fi
taskset -pc "$GEN_CPUS" "$tid" >/dev/null
echo "pin-gen: server cpus=$SERVER_CPUS gen tid=$tid cpus=$(taskset -pc "$tid" | sed 's/.*: //')" >&2
touch "$ready"
wait $pid
