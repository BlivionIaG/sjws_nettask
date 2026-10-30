#!/bin/bash
# Demonstrate JdkH2Load exits non-zero with errors=N when the listener is down
# (Codex P2 on #303). No server is started; port 9 is discard/closed locally.
set -euo pipefail
SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
J=$(command -v java)
# Point at a closed local port; leave SJWS_BENCH_CA unset for http so TLS is unused.
out=$(nice -n 10 "$J" "$SCRIPT_DIR/JdkH2Load.java" "http://127.0.0.1:9/nope" 4 2 0 2>&1) || rc=$?
rc=${rc:-0}
echo "$out"
if [[ $rc -eq 2 ]] && echo "$out" | grep -q 'FAIL' && echo "$out" | grep -q 'errors='; then
  echo "demo-fail-loud: OK (exit=$rc, FAIL line with errors= present)"
  exit 0
fi
echo "demo-fail-loud: UNEXPECTED exit=$rc" >&2
exit 1
