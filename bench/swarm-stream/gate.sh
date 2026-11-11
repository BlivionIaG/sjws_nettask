#!/bin/bash
# gate.sh <base.jar> <head.jar> <outdir>
# Absolute §13 stream gate (credit on, 1k streams) + A/A + info credit on/off ABBA.
# NVALID 5–8 valid windows. Resumes from on-disk abs-*/result.json. PHASE=all|abs|aa|info|add1|agg.
set -euo pipefail
QUESTIONS=${QUESTIONS:-/workspace/s2c-pr8/questions.md}
if [ -f "$QUESTIONS" ] && grep -q 'Stopping before any gate run' "$QUESTIONS" 2>/dev/null; then
  echo "gate.sh: blocked on $QUESTIONS"
  exit 2
fi
BASE_JAR=$1; HEAD_JAR=$2; OUT=$3
NVALID=${NVALID:-6}; CAP=${CAP:-8}; SECS=${SECS:-20}; WARMUP=${WARMUP:-5}
STREAMS=${STREAMS:-1000}
GATE_GROUPS=${GATE_GROUPS:-64}  # Architecture 2026-10-30 15:13 / inceptor 15:14
ACCEPT=${ACCEPT:-binary}
PHASE=${PHASE:-all}
# #415: the gate drives the Java generator (drive.py saturates CPython at 1k streams; one.sh's
# own default stays python for the small SDK checks). RATE_MIB_S / LEAN_CONSUMER pass through.
export LOADGEN=${LOADGEN:-java}
# inceptor Q1 (2026-11-11, gate-rule change disclosed on PR 8, Architecture sign-off pending):
# absolute (abs-*) and A/A windows run PACED at GATE_RATE_MIB_S offered (default 110) and gate on
# achieved >= 100 MiB/s and p99 < 1 ms, p99 measured from each chunk's SCHEDULED send time.
# Each abs window is followed by a closed-loop info leg cl-NN (info only, never counted;
# CLOSED_LOOP_INFO=0 skips it). Q5: one low-rate floor leg (FLOOR_RATE_MIB_S, default 10) reports
# the generator's own latency floor next to each window; it is never subtracted.
GATE_RATE_MIB_S=${GATE_RATE_MIB_S:-110}
FLOOR_RATE_MIB_S=${FLOOR_RATE_MIB_S:-10}
CLOSED_LOOP_INFO=${CLOSED_LOOP_INFO:-1}
HERE=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$OUT"
# Bench lock convention (AGENTS.md / shared box):
# - Entry points acquire /tmp/sjws-bench.lock exactly once.
# - If the caller already holds it (outer `flock /tmp/sjws-bench.lock …`), set SKIP_FLOCK=1
#   so this script does not re-acquire and deadlock on the same fd/file.
# - Bare `./gate.sh` (no outer flock) takes the lock itself.
if [ "${SKIP_FLOCK:-0}" != 1 ]; then
  exec 9>>/tmp/sjws-bench.lock
  flock 9
fi
echo "gate phase=$PHASE start $(date -Is) load=$(cat /proc/loadavg) streams=$STREAMS groups=$GATE_GROUPS loadgen=$LOADGEN rate=${RATE_MIB_S:-closed} lean_consumer=${LEAN_CONSUMER:-1}" | tee -a "$OUT/gate.log"
# 07:05 addendum: start gate load1 < 3 (also rechecked before every leg).
LOAD_PRE=$(awk '{print $1}' /proc/loadavg)
python3 -c "import sys; sys.exit(0 if float('$LOAD_PRE') < 3.0 else 1)" || {
  echo "gate: pre-start load1=$LOAD_PRE >= 3 — refuse" | tee -a "$OUT/gate.log"
  exit 3
}

run_one() {
  # $6 = offered rate MiB/s for this leg ("" = closed loop); defaults to the caller's RATE_MIB_S.
  local jar=$1 arm=$2 accept=$3 credit_off=$4 tag=$5 rate=${6-${RATE_MIB_S:-}}
  local wd="$OUT/$tag" load_pre rc=0 mon
  mkdir -p "$wd"
  load_pre=$(awk '{print $1}' /proc/loadavg)
  echo "=== $tag accept=$accept credit_off=$credit_off rate=${rate:-closed} load1_pre=$load_pre ===" | tee -a "$OUT/gate.log"
  # 07:05: recheck start gate (load1 < 3) before EVERY leg
  if ! python3 -c "import sys; sys.exit(0 if float('$load_pre') < 3.0 else 1)"; then
    echo "gate: skip $tag — load1_pre=$load_pre >= 3 (NOISY)" | tee -a "$OUT/gate.log"
    printf '%s\n' "{\"noisy\":true,\"noisy_marker\":\"NOISY\",\"load1_pre\":$load_pre,\"load1_post\":$load_pre,\"noisy_reasons\":[\"load1_pre>=3\"],\"chunk_samples\":0,\"error_count\":1,\"group_open_refusals\":0,\"self_loaded\":false,\"hard_zeros\":{\"sjws_connections_refused_per_ip_total\":0,\"sjws_swarm_stream_open_sessions\":0}}" > "$wd/result.json"
    return 1
  fi
  : > "$wd/bench.pids"
  echo "$$" > "$wd/bench.pids"
  python3 "$HERE/foreign_cpu.py" --out "$wd/foreign_cpu.json" --allow-pid "$$" \
    --allow-pid-file "$wd/bench.pids" --interval 5 &
  mon=$!
  RATE_MIB_S=$rate CREDIT_OFF=$credit_off WARMUP=$WARMUP GATE_GROUPS=$GATE_GROUPS \
    "$HERE/one.sh" "$jar" "$wd" "$accept" "$wd/result.json" "$SECS" "$STREAMS" \
    2>&1 | tee -a "$OUT/gate.log" || rc=$?
  kill -TERM "$mon" 2>/dev/null || true
  wait "$mon" 2>/dev/null || true
  sync "$wd/result.json" 2>/dev/null || true
  if [ -f "$wd/result.json" ]; then
    python3 "$HERE/merge_noisy.py" "$wd/result.json" "$wd/foreign_cpu.json" "$load_pre" \
      | tee -a "$OUT/gate.log" || true
  fi
  return $rc
}

abs_valid_count() {
  # Shared rule with agg.py via window_valid.py (Codex P2 #459)
  local valid=0 f ok
  for f in "$OUT"/abs-*/result.json; do
    [ -f "$f" ] || continue
    ok=$(python3 "$HERE/window_valid.py" "$f")
    [ "$ok" = 1 ] && valid=$((valid + 1))
  done
  echo "$valid"
}

if [ "$PHASE" = all ] || [ "$PHASE" = abs ] || [ "$PHASE" = floor ]; then
  # Q5: generator latency floor (low offered rate, same jar/config), reported, never subtracted.
  [ -f "$OUT/floor/result.json" ] || run_one "$HEAD_JAR" floor "$ACCEPT" 0 "floor" "$FLOOR_RATE_MIB_S" || true
fi

if [ "$PHASE" = all ] || [ "$PHASE" = abs ]; then
  valid=$(abs_valid_count)
  echo "abs resume: $valid already valid" | tee -a "$OUT/gate.log"
  attempt=0
  created=0
  MAX_NEW=${MAX_NEW:-999}
  while [ "$valid" -lt "$NVALID" ] && [ "$attempt" -lt "$CAP" ] && [ "$created" -lt "$MAX_NEW" ]; do
    attempt=$((attempt + 1))
    tag=$(printf 'abs-%02d' "$attempt")
    if [ -f "$OUT/$tag/result.json" ]; then
      echo "skip existing $tag" | tee -a "$OUT/gate.log"
      continue
    fi
    if ! run_one "$HEAD_JAR" head "$ACCEPT" 0 "$tag" "$GATE_RATE_MIB_S"; then
      echo "window $tag: one.sh failed" | tee -a "$OUT/gate.log"
      continue
    fi
    # Q1: closed-loop numbers beside every window (info only; cl-* never counts as abs).
    if [ "$CLOSED_LOOP_INFO" = 1 ]; then
      cltag=$(printf 'cl-%02d' "$attempt")
      [ -f "$OUT/$cltag/result.json" ] || run_one "$HEAD_JAR" info "$ACCEPT" 0 "$cltag" "" || true
    fi
    created=$((created + 1))
    ok=$(python3 "$HERE/window_valid.py" "$OUT/$tag/result.json")
    if [ "$ok" = 1 ]; then
      valid=$((valid + 1))
      echo "window $tag: VALID ($valid/$NVALID)" | tee -a "$OUT/gate.log"
    else
      echo "window $tag: invalid" | tee -a "$OUT/gate.log"
    fi
  done
fi

if [ "$PHASE" = all ] || [ "$PHASE" = aa ]; then
  [ -f "$OUT/aa-a/result.json" ] || run_one "$HEAD_JAR" aa "$ACCEPT" 0 "aa-a" "$GATE_RATE_MIB_S" || true
  [ -f "$OUT/aa-b/result.json" ] || run_one "$HEAD_JAR" aa "$ACCEPT" 0 "aa-b" "$GATE_RATE_MIB_S" || true
fi

if [ "$PHASE" = all ] || [ "$PHASE" = info ]; then
  INFO_BLOCKS=${INFO_BLOCKS:-3}
  for b in $(seq 1 "$INFO_BLOCKS"); do
    bb=$(printf '%02d' "$b")
    [ -f "$OUT/info-b${bb}-A1/result.json" ] || run_one "$HEAD_JAR" info "$ACCEPT" 0 "info-b${bb}-A1" || true
    [ -f "$OUT/info-b${bb}-B1/result.json" ] || run_one "$HEAD_JAR" info "$ACCEPT" 1 "info-b${bb}-B1" || true
    [ -f "$OUT/info-b${bb}-B2/result.json" ] || run_one "$HEAD_JAR" info "$ACCEPT" 1 "info-b${bb}-B2" || true
    [ -f "$OUT/info-b${bb}-A2/result.json" ] || run_one "$HEAD_JAR" info "$ACCEPT" 0 "info-b${bb}-A2" || true
  done
fi

if [ "$PHASE" = all ] || [ "$PHASE" = add1 ]; then
  [ -f "$OUT/add1-binary/result.json" ] || run_one "$HEAD_JAR" head binary 0 "add1-binary" || true
  [ -f "$OUT/add1-sse/result.json" ] || run_one "$HEAD_JAR" head sse 0 "add1-sse" || true
fi

if [ "$PHASE" = all ] || [ "$PHASE" = agg ]; then
  python3 "$HERE/agg.py" "$OUT" | tee "$OUT/verdict.txt" | tee -a "$OUT/gate.log"
  if ! grep -q '^VERDICT: PASS' "$OUT/verdict.txt"; then
    if [ ! -f "$OUT/diag-250/result.json" ]; then
      echo "FAIL — 250-stream diagnostic" | tee -a "$OUT/gate.log"
      STREAMS=250 run_one "$HEAD_JAR" head "$ACCEPT" 0 "diag-250" || true
    fi
    exit 1
  fi
fi

# 07:05 addendum: DONE marker for waiters (owner still appends to /workspace/bench-queue.md
# with the PR comment URL when the queue item finishes).
DONE_LINE="DONE swarm-stream-gate $(date -Is) out=$OUT phase=$PHASE"
echo "$DONE_LINE" | tee -a "$OUT/gate.log" | tee "$OUT/DONE"
