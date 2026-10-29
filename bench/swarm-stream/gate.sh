#!/bin/bash
# gate.sh <base.jar> <head.jar> <outdir>
# Holds flock, runs A/A + ABBA up to NVALID valid blocks. Exits 2 if questions.md still blocks.
set -euo pipefail
QUESTIONS=${QUESTIONS:-/workspace/s2c-pr8/questions.md}
if [ -f "$QUESTIONS" ] && grep -q 'Stopping before any gate run' "$QUESTIONS" 2>/dev/null; then
  echo "gate.sh: blocked on $QUESTIONS — answer Q1–Q6 before running the §13 exit gate."
  exit 2
fi
BASE_JAR=$1; HEAD_JAR=$2; OUT=$3
NVALID=${NVALID:-6}; CAP=${CAP:-12}; SECS=${SECS:-20}
mkdir -p "$OUT"
exec 9>>/tmp/sjws-bench.lock
flock 9
echo "gate start $(date -Is) load=$(cat /proc/loadavg)" | tee "$OUT/gate.log"
# … ABBA loop (filled in after questions) …
echo "gate: implement ABBA loop after questions are answered" | tee -a "$OUT/gate.log"
exit 2
