#!/bin/bash
# gate.sh <base.jar> <head.jar> <outdir>
# Absolute §13 stream gate (credit on, 1k streams) + A/A + info credit on/off ABBA.
# NVALID 5–8 valid windows. Resumes from on-disk abs-*/result.json. PHASE=all|floor|abs|cl|aa|info|add1|agg.
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
# The disclosed rate is 110 exactly; agg.py rejects abs/A-A windows at any other offered rate.
# Closed-loop info legs cl-NN run AFTER the abs phase (Architecture item 4: they must not raise
# load1 between abs windows); CLOSED_LOOP_INFO=0 skips them. Q5: a low-load end-to-end floor leg
# (FLOOR_RATE_MIB_S, default 10; rerun up to FLOOR_TRIES until valid) is reported beside every
# window, never subtracted. It understates generator delay at 110 MiB/s.
GATE_RATE_MIB_S=${GATE_RATE_MIB_S:-110}
FLOOR_RATE_MIB_S=${FLOOR_RATE_MIB_S:-10}
FLOOR_TRIES=${FLOOR_TRIES:-3}
CLOSED_LOOP_INFO=${CLOSED_LOOP_INFO:-1}
# Quiet wait before every leg (Architecture item 4, like bench-416's wait_box_quiet): poll until
# load1 < 3, at most QUIET_WAIT_MAX_S per leg, QUIET_WAIT_TOTAL_S for the whole gate (then abort).
QUIET_WAIT_MAX_S=${QUIET_WAIT_MAX_S:-900}
QUIET_WAIT_TOTAL_S=${QUIET_WAIT_TOTAL_S:-5400}
QUIET_WAITED_S=0
HERE=$(cd "$(dirname "$0")" && pwd)
# Architecture item 7: every DONE line records the harness commit (and whether the tree was dirty).
HARNESS_COMMIT=$(git -C "$HERE" rev-parse --short=12 HEAD 2>/dev/null || echo unknown)
if [ -n "$(git -C "$HERE" status --porcelain -- . 2>/dev/null)" ]; then HARNESS_COMMIT="$HARNESS_COMMIT-dirty"; fi
# Architecture item 6: no gate window counts before generator calibration plus compile/smoke are
# done and posted. abs/all phases refuse to start without CALIBRATION_REF (the result comment URL).
case "$PHASE" in all|abs)
  if [ -z "${CALIBRATION_REF:-}" ]; then
    echo "gate.sh: CALIBRATION_REF unset; run smoke + generator calibration first (PLAN-415-414.md)" >&2
    exit 2
  fi;;
esac
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
echo "gate phase=$PHASE start $(date -Is) load=$(cat /proc/loadavg) streams=$STREAMS groups=$GATE_GROUPS loadgen=$LOADGEN gate_rate=$GATE_RATE_MIB_S lean_consumer=${LEAN_CONSUMER:-1} harness=$HARNESS_COMMIT calibration=${CALIBRATION_REF:-none}" | tee -a "$OUT/gate.log"
# 07:05 addendum: start gate load1 < 3 (also rechecked before every leg).
LOAD_PRE=$(awk '{print $1}' /proc/loadavg)
python3 -c "import sys; sys.exit(0 if float('$LOAD_PRE') < 3.0 else 1)" || {
  echo "gate: pre-start load1=$LOAD_PRE >= 3 — refuse" | tee -a "$OUT/gate.log"
  exit 3
}

run_one() {
  # $6 = offered rate MiB/s for this leg ("" = closed loop); defaults to the caller's RATE_MIB_S.
  local jar=$1 arm=$2 accept=$3 credit_off=$4 tag=$5 rate=${6-${RATE_MIB_S:-}}
  local wd="$OUT/$tag" load_pre rc=0 mon waited=0
  mkdir -p "$wd"
  load_pre=$(awk '{print $1}' /proc/loadavg)
  while ! python3 -c "import sys; sys.exit(0 if float('$load_pre') < 3.0 else 1)" \
      && [ "$waited" -lt "$QUIET_WAIT_MAX_S" ]; do
    if [ "$QUIET_WAITED_S" -ge "$QUIET_WAIT_TOTAL_S" ]; then
      echo "gate: FATAL quiet-wait total ${QUIET_WAITED_S}s >= ${QUIET_WAIT_TOTAL_S}s before $tag" | tee -a "$OUT/gate.log"
      exit 6
    fi
    sleep 15; waited=$((waited + 15)); QUIET_WAITED_S=$((QUIET_WAITED_S + 15))
    load_pre=$(awk '{print $1}' /proc/loadavg)
  done
  [ "$waited" -gt 0 ] && echo "gate: quiet-wait ${waited}s before $tag (total ${QUIET_WAITED_S}s)" | tee -a "$OUT/gate.log"
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
  # Q5: low-load end-to-end floor (same jar/config at FLOOR_RATE_MIB_S), reported, never
  # subtracted; an invalid floor leg is rerun (Architecture item 5). Acceptance is in agg.py.
  for ft in $(seq 1 "$FLOOR_TRIES"); do
    ftag="lowload-floor-$ft"
    if [ -f "$OUT/$ftag/result.json" ]; then
      [ "$(python3 "$HERE/window_valid.py" "$OUT/$ftag/result.json")" = 1 ] && break
      continue
    fi
    run_one "$HEAD_JAR" floor "$ACCEPT" 0 "$ftag" "$FLOOR_RATE_MIB_S" || true
    [ -f "$OUT/$ftag/result.json" ] && [ "$(python3 "$HERE/window_valid.py" "$OUT/$ftag/result.json")" = 1 ] && break
    echo "gate: $ftag invalid, rerun" | tee -a "$OUT/gate.log"
  done
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

if [ "$PHASE" = all ] || [ "$PHASE" = abs ] || [ "$PHASE" = cl ]; then
  # Q1: closed-loop numbers beside every abs window, run after the abs phase (info only).
  if [ "$CLOSED_LOOP_INFO" = 1 ]; then
    for f in "$OUT"/abs-*/result.json; do
      [ -f "$f" ] || continue
      atag=$(basename "$(dirname "$f")"); cltag="cl-${atag#abs-}"
      [ -f "$OUT/$cltag/result.json" ] || run_one "$HEAD_JAR" info "$ACCEPT" 0 "$cltag" "" || true
    done
  fi
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

write_done() {
  # 07:05 addendum: DONE marker for waiters, written for every outcome (owner still appends
  # `DONE <item> <time> <PR comment URL>` to /workspace/bench-queue.md). Architecture item 7:
  # it records the harness commit the windows were measured with.
  local v=$1
  DONE_LINE="DONE swarm-stream-gate $(date -Is) out=$OUT phase=$PHASE verdict=$v harness=$HARNESS_COMMIT calibration=${CALIBRATION_REF:-none}"
  echo "$DONE_LINE" | tee -a "$OUT/gate.log" | tee "$OUT/DONE"
}

if [ "$PHASE" = all ] || [ "$PHASE" = agg ]; then
  python3 "$HERE/agg.py" "$OUT" | tee "$OUT/verdict.txt" | tee -a "$OUT/gate.log"
  VERDICT=$(sed -n 's/^VERDICT: \([A-Z_]*\).*/\1/p' "$OUT/verdict.txt" | tail -1)
  if [ "${VERDICT:-NONE}" != PASS ]; then
    if [ ! -f "$OUT/diag-250/result.json" ]; then
      echo "not PASS — 250-stream diagnostic" | tee -a "$OUT/gate.log"
      STREAMS=250 run_one "$HEAD_JAR" head "$ACCEPT" 0 "diag-250" || true
    fi
    write_done "${VERDICT:-NONE}"
    exit 1
  fi
  write_done PASS
  exit 0
fi
write_done "phase-$PHASE"
