#!/bin/bash
# Produce-path ABBA under #263 rule: RaftPublish + MessagingLog produce, >=9 rounds alternating.
# FAIL only if one-sided paired sign-flip on log(ratio) has p<0.05 AND geomean>1.10.
# Frame: heap/thread PR — report ops/s honestly; no p99 claim.
set -euo pipefail
export JAVA_HOME=~/jdk25 PATH=~/jdk25/bin:$PATH
OUT=/workspace/wt/ledger-pool-impl/bench
BEFORE=${BEFORE:-/workspace/wt/ledger-pool-before/target/classes}
AFTER=${AFTER:-/workspace/wt/ledger-pool-impl/target/classes}
BBENCH=/workspace/wt/ledger-pool-before/bench
ABENCH=/workspace/wt/ledger-pool-impl/bench
# Prefer project bench sources
BBENCH=$(ls -d /workspace/wt/ledger-pool-before/bench 2>/dev/null || ls -d /workspace/sjws_nettask/bench)
ABENCH=/workspace/wt/ledger-pool-impl/bench
# Use repo bench java sources from each tree
BSRC=/workspace/wt/ledger-pool-before
ASRC=/workspace/wt/ledger-pool-impl
ROUNDS=${ROUNDS:-9}
SECS=${SECS:-4}
RAW=$OUT/abba-raw.tsv
: > "$RAW"
printf 'round\tside\tbench\tload1_start\tops_s\tload1_end\traw\n' >> "$RAW"

wait_load() {
  for i in $(seq 1 360); do
    l=$(cut -d' ' -f1 /proc/loadavg)
    awk -v l="$l" 'BEGIN{exit !(l+0<3)}' && { echo "load1_ok $l"; return 0; }
    echo "wait load1=$l"; sleep 20
  done
  echo "WARN: proceeding load1=$(cut -d' ' -f1 /proc/loadavg)"
}

parse_ops() { echo "$1" | sed -n 's/.*ops\/s=\([0-9][0-9.]*\).*/\1/p'; }

run_one() {
  local tag=$1 cls=$2 root=$3 side=$4 round=$5
  local load1 load1e line ops
  load1=$(cut -d' ' -f1 /proc/loadavg)
  echo "### $tag load1=$load1 $(date -R)"
  mkdir -p "/tmp/msg336-$tag"
  line=$(nice -n 10 java -cp "$cls" "$root/bench/RaftPublishBench.java" publish 4 "$SECS" | tee "$OUT/${tag}-raft.txt" | grep '^publish ' | tail -1)
  load1e=$(cut -d' ' -f1 /proc/loadavg)
  ops=$(parse_ops "$line")
  printf '%s\t%s\traft\t%s\t%s\t%s\t%s\n' "$round" "$side" "$load1" "$ops" "$load1e" "$line" >> "$RAW"
  load1=$(cut -d' ' -f1 /proc/loadavg)
  line=$(nice -n 10 java -cp "$cls" "$root/bench/MessagingLogBench.java" "/tmp/msg336-$tag" produce 256 4 "$SECS" | tee "$OUT/${tag}-msg.txt" | grep '^messaging produce' | tail -1)
  load1e=$(cut -d' ' -f1 /proc/loadavg)
  ops=$(parse_ops "$line")
  printf '%s\t%s\tmsg\t%s\t%s\t%s\t%s\n' "$round" "$side" "$load1" "$ops" "$load1e" "$line" >> "$RAW"
}

{
  echo "START $(date -R) load1=$(cut -d' ' -f1 /proc/loadavg)"
  echo "before=e0a25c1 after=HEAD rounds=$ROUNDS rule=#263"
  wait_load
  for r in $(seq 1 "$ROUNDS"); do
    echo "==== ROUND $r BEFORE ===="; wait_load
    run_one "r${r}-before" "$BEFORE" "$BSRC" before "$r"
    echo "==== ROUND $r AFTER ===="; wait_load
    run_one "r${r}-after" "$AFTER" "$ASRC" after "$r"
  done
  echo "DONE $(date -R)"
  python3 "$OUT/agg-abba.py" || true
} 2>&1 | tee "$OUT/abba.log"
