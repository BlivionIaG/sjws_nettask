#!/bin/bash
# profile-414.sh <jar> <outdir>   (#414: server CPU + allocation profile, NOT a gate window)
#
# One 1k-stream / 64-group credit-on binary window with JFR on the server (and optionally on the
# generator), then jfr_top.py attribution. The profile only counts if the window itself is valid
# under the shared rule (window_valid.py: generator CPU <= 80% x GEN_CORES, not NOISY, hard zeros),
# otherwise it is the same self-loaded profile #414 already has.
#
# Bench rules: run under `flock /tmp/sjws-bench.lock` (SKIP_FLOCK=1 when the caller holds it),
# start only at load1 < 3, foreign-CPU sampler as gate.sh. JFR adds overhead, so numbers from
# this window are never gate numbers.
#
# Env: SECS (default 40: the recording skips the ramp), WARMUP (5), STREAMS (1000),
#      GATE_GROUPS (64), RATE_MIB_S (offered rate, unset = closed loop), GEN_JFR=1 (also
#      record the generator), JFR_DELAY_S (default WARMUP+15), JFR_DURATION_S (default SECS-10).
set -euo pipefail
JAR=$1; OUT=$2
HERE=$(cd "$(dirname "$0")" && pwd)
SECS=${SECS:-40}; WARMUP=${WARMUP:-5}; STREAMS=${STREAMS:-1000}; GATE_GROUPS=${GATE_GROUPS:-64}
JFR_DELAY_S=${JFR_DELAY_S:-$((WARMUP + 15))}
JFR_DURATION_S=${JFR_DURATION_S:-$((SECS - 10))}
export JAVA_HOME=${JAVA_HOME:-$HOME/jdk25/jdk-25.0.4.1+1}
export PATH=$JAVA_HOME/bin:${PATH:-}
mkdir -p "$OUT"
if [ "${SKIP_FLOCK:-0}" != 1 ]; then
  exec 9>>/tmp/sjws-bench.lock
  flock 9
fi
LOAD_PRE=$(awk '{print $1}' /proc/loadavg)
python3 -c "import sys; sys.exit(0 if float('$LOAD_PRE') < 3.0 else 1)" || {
  echo "profile-414: load1=$LOAD_PRE >= 3, refuse" | tee -a "$OUT/profile.log"; exit 3; }
wd="$OUT/prof"
mkdir -p "$wd"
echo "$$" > "$wd/bench.pids"
JFR_COMMON="delay=${JFR_DELAY_S}s,duration=${JFR_DURATION_S}s,settings=profile"
export SERVER_JAVA_OPTS="-XX:StartFlightRecording=${JFR_COMMON},filename=$wd/server.jfr"
if [ "${GEN_JFR:-0}" = 1 ]; then
  export GEN_JAVA_OPTS="-XX:StartFlightRecording=${JFR_COMMON},filename=$wd/gen.jfr"
fi
echo "profile-414 start $(date -Is) load1=$LOAD_PRE jfr=$JFR_COMMON rate=${RATE_MIB_S:-closed}" | tee -a "$OUT/profile.log"
python3 "$HERE/foreign_cpu.py" --out "$wd/foreign_cpu.json" --allow-pid "$$" \
  --allow-pid-file "$wd/bench.pids" --interval 5 &
mon=$!
rc=0
LOADGEN=java WARMUP=$WARMUP GATE_GROUPS=$GATE_GROUPS \
  "$HERE/one.sh" "$JAR" "$wd" binary "$wd/result.json" "$SECS" "$STREAMS" \
  2>&1 | tee -a "$OUT/profile.log" || rc=$?
kill -TERM "$mon" 2>/dev/null || true
wait "$mon" 2>/dev/null || true
[ -f "$wd/result.json" ] || { echo "profile-414: no result.json (rc=$rc)" | tee -a "$OUT/profile.log"; exit 4; }
python3 "$HERE/merge_noisy.py" "$wd/result.json" "$wd/foreign_cpu.json" "$LOAD_PRE" | tee -a "$OUT/profile.log" || true
valid=$(python3 "$HERE/window_valid.py" "$wd/result.json")
echo "profile-414 window_valid=$valid" | tee -a "$OUT/profile.log"
for rec in server gen; do
  [ -f "$wd/$rec.jfr" ] || continue
  jfr print --json --events jdk.ExecutionSample,jdk.ObjectAllocationSample "$wd/$rec.jfr" > "$wd/$rec.json"
  python3 "$HERE/jfr_top.py" "$wd/$rec.json" > "$OUT/$rec-attribution.md"
  rm -f "$wd/$rec.json"  # large; the .jfr stays (gitignored)
  for v in hot-methods allocation-by-site contention-by-site gc-pauses; do
    jfr view --width 200 "$v" "$wd/$rec.jfr" > "$OUT/$rec-view-$v.txt" 2>&1 || true
  done
done
[ "$valid" = 1 ] || { echo "profile-414: window INVALID, profile is not evidence" | tee -a "$OUT/profile.log"; exit 5; }
echo "DONE profile-414 $(date -Is) out=$OUT" | tee -a "$OUT/profile.log"
