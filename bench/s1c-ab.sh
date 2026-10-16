#!/bin/bash
# S1c request/reply A/B: alternates a base build and a PR build, each run an echo baseline then reqreply,
# through pin-gen.sh (flock, nice -n 10, load < 4 refused and retried, load start/end recorded), then aggregates
# with s1c-agg.py (mean±sd of per-run p99 and pooled p99 from the per-call dumps).
#   bench/s1c-ab.sh <base-worktree> <pr-worktree> <out-dir> [iterations=5] [rate=10000] [secs=10]
# Each worktree must be built (target/classes). Extra JVM flags: JVM_FLAGS (default "-XX:+UseZGC
# -Dsjws.swarm.pool.shards=4"). CPU layout: SERVER_CPUS / GEN_CPUS as in pin-gen.sh (default needs 8+ cores).
set -euo pipefail
here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
[ $# -ge 3 ] || { sed -n 2,7p "$0"; exit 64; }
base=$(cd "$1" && pwd); pr=$(cd "$2" && pwd); out=$(mkdir -p "$3" && realpath "$3"); iters=${4:-5}; rate=${5:-10000}; secs=${6:-10}
JVM_FLAGS=${JVM_FLAGS:--XX:+UseZGC -Dsjws.swarm.pool.shards=4}
# <out-dir> is resolved to an absolute path: each run cds into its worktree, so a relative one lost the dumps.
mkdir -p "$out/dumps"; log="$out/runs.log"
one() { # side dir name args...
  local side=$1 dir=$2 name=$3; shift 3
  for try in $(seq 1 30); do
    set +e
    res=$(cd "$dir" && CLASSES="$dir/target/classes" SWARMBENCH="$here/SwarmBench.java" "$here/pin-gen.sh" $JVM_FLAGS "$@" 2>&1)
    rc=$?
    set -e
    if [ $rc = 75 ]; then sleep 10; continue; fi
    echo "$(date +%T) iter=$ITER side=$side bench=$name rc=$rc :: $(echo "$res" | grep -v '^WARN' | tr '\n' ' ')" >> "$log"
    return 0
  done
  echo "$(date +%T) iter=$ITER side=$side bench=$name SKIPPED: load never < 4" >> "$log"
}
for ITER in $(seq "$iters"); do
  for side in base pr; do
    dir=$base; [ $side = pr ] && dir=$pr
    one $side "$dir" echo -- /tmp echo "$rate" "$secs"
    one $side "$dir" reqreply "-Dsjws.bench.dump=$out/dumps/$side-$ITER.txt" -- /tmp reqreply "$rate" "$secs"
  done
done
python3 "$here/s1c-agg.py" "$out" | tee "$out/summary.txt"
