#!/bin/bash
# Report-only 16/64/256/1000 group sweep at -Xmx4g shards=4. Heap, threads, occupancy.
set -euo pipefail
export JAVA_HOME=~/jdk25 PATH=~/jdk25/bin:$PATH
OUT=/workspace/wt/ledger-pool-impl/bench
CLS=/workspace/wt/ledger-pool-impl/target/classes
# Prefer existing GroupsHeapSweep from #335 worktree if present, else a minimal census
SWEEP_SRC=/workspace/wt/ledger-heap-work/GroupsHeapSweep.java
if [ -f "$SWEEP_SRC" ]; then
  echo "Using $SWEEP_SRC"
else
  SWEEP_SRC=$OUT/GroupsHeapSweep.java
fi
wait_load() {
  for i in $(seq 1 180); do
    l=$(cut -d' ' -f1 /proc/loadavg)
    awk -v l="$l" 'BEGIN{exit !(l+0<3)}' && return 0
    sleep 15
  done
}
{
  echo "SWEEP START $(date -R) load1=$(cut -d' ' -f1 /proc/loadavg)"
  for G in 16 64 256 1000; do
    wait_load
    load1=$(cut -d' ' -f1 /proc/loadavg)
    echo "==== G=$G load1=$load1 ===="
    # Minimal: open G*12 writers ASYNC via small Java harness if GroupsHeapSweep unavailable
    nice -n 10 java -Xms2g -Xmx4g -cp "$CLS" \
      -Dsjws.messaging.ledger.sharedWriterThreads=8 \
      sjws_nettask.messaging.ledger.PoolSweep  "$G" 2>&1 | tee "$OUT/sweep-g$G.txt" || \
    nice -n 10 java -Xms2g -Xmx4g -cp "$CLS:$OUT" PoolSweepMain "$G" 2>&1 | tee "$OUT/sweep-g$G.txt" || true
  done
  echo "SWEEP DONE $(date -R)"
} 2>&1 | tee "$OUT/sweep.log"
