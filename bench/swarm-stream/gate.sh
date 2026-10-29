#!/bin/bash
# gate.sh <base.jar> <head.jar> <outdir>
# Absolute §13 stream gate (credit on, 1k streams) + A/A noise floor + info credit on/off ABBA.
# NVALID 5–8 valid windows (inceptor amendment 2026-10-29 23:59). Exits 0 PASS / 1 FAIL / 2 blocked.
set -euo pipefail
QUESTIONS=${QUESTIONS:-/workspace/s2c-pr8/questions.md}
if [ -f "$QUESTIONS" ] && grep -q 'Stopping before any gate run' "$QUESTIONS" 2>/dev/null; then
  echo "gate.sh: blocked on $QUESTIONS — answer Q1–Q6 before running the §13 exit gate."
  exit 2
fi
BASE_JAR=$1; HEAD_JAR=$2; OUT=$3
NVALID=${NVALID:-6}; CAP=${CAP:-8}; SECS=${SECS:-20}; WARMUP=${WARMUP:-5}
STREAMS=${STREAMS:-1000}
ACCEPT=${ACCEPT:-binary}
HERE=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$OUT"
exec 9>>/tmp/sjws-bench.lock
flock 9
echo "gate start $(date -Is) load=$(cat /proc/loadavg)" | tee "$OUT/gate.log"
LOAD_PRE=$(awk '{print $1}' /proc/loadavg)
python3 -c "import sys; sys.exit(0 if float('$LOAD_PRE') < 4.0 else 1)" || {
  echo "gate: pre-start load1=$LOAD_PRE >= 4 — refuse to start" | tee -a "$OUT/gate.log"
  exit 3
}

run_one() {
  local jar=$1 arm=$2 accept=$3 credit_off=$4 tag=$5
  local wd="$OUT/$tag"
  mkdir -p "$wd"
  echo "=== $tag jar=$(basename "$jar") accept=$accept credit_off=$credit_off load=$(cat /proc/loadavg) ===" | tee -a "$OUT/gate.log"
  CREDIT_OFF=$credit_off WARMUP=$WARMUP \
    "$HERE/one.sh" "$jar" "$wd" "$accept" "$wd/result.json" "$SECS" "$STREAMS" \
    2>&1 | tee -a "$OUT/gate.log" || return $?
}

# --- Absolute §13 windows on HEAD, credit on ---
valid=0
attempt=0
while [ "$valid" -lt "$NVALID" ] && [ "$attempt" -lt "$CAP" ]; do
  attempt=$((attempt + 1))
  tag=$(printf 'abs-%02d' "$attempt")
  if ! run_one "$HEAD_JAR" head "$ACCEPT" 0 "$tag"; then
    echo "window $tag: one.sh failed" | tee -a "$OUT/gate.log"
    continue
  fi
  # validity: ≥10k samples, load1_pre < 4 (recorded in result), p99 present
  ok=$(python3 - "$OUT/$tag/result.json" <<'PY'
import json,sys
r=json.load(open(sys.argv[1]))
n=r.get("chunk_samples") or 0
p99=r.get("p99_us")
pre=r.get("load1_pre", 99)
print("1" if n>=10000 and p99 is not None and pre<4.0 else "0")
PY
)
  if [ "$ok" = 1 ]; then
    valid=$((valid + 1))
    echo "window $tag: VALID ($valid/$NVALID)" | tee -a "$OUT/gate.log"
  else
    echo "window $tag: invalid (samples/load)" | tee -a "$OUT/gate.log"
  fi
done

# --- A/A noise floor (same jar both conceptual arms — one head window pair reported) ---
run_one "$HEAD_JAR" aa "$ACCEPT" 0 "aa-a" || true
run_one "$HEAD_JAR" aa "$ACCEPT" 0 "aa-b" || true

# --- Info credit on/off ABBA (3–8 blocks; does not fail the PR) ---
# Block pattern: A=credit-on, B=credit-off on head jar; ABBA means A B B A per block.
INFO_BLOCKS=${INFO_BLOCKS:-3}
for b in $(seq 1 "$INFO_BLOCKS"); do
  bb=$(printf '%02d' "$b")
  run_one "$HEAD_JAR" info "$ACCEPT" 0 "info-b${bb}-A1" || true
  run_one "$HEAD_JAR" info "$ACCEPT" 1 "info-b${bb}-B1" || true
  run_one "$HEAD_JAR" info "$ACCEPT" 1 "info-b${bb}-B2" || true
  run_one "$HEAD_JAR" info "$ACCEPT" 0 "info-b${bb}-A2" || true
done

# --- Binary accept report-only window (Addition 1) ---
run_one "$HEAD_JAR" head binary 0 "add1-binary" || true
run_one "$HEAD_JAR" head sse 0 "add1-sse" || true

# Aggregate + verdict
python3 "$HERE/agg.py" "$OUT" | tee "$OUT/verdict.txt" | tee -a "$OUT/gate.log"
# exit code from verdict
grep -q '^VERDICT: PASS' "$OUT/verdict.txt"
