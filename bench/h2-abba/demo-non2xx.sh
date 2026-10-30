#!/bin/bash
# Demonstrate JdkH2Load treats HTTP non-2xx as errors and exits 2 (Codex P2 on #303).
# Starts SjwsServer briefly against an empty docroot so GET /1k.bin → 404.
set -euo pipefail
SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$SCRIPT_DIR/../.." && pwd)
J=$(command -v java)
B=${BENCH_DIR:-/tmp/sjws-h2-abba-demo-non2xx}
H1=32380; H2C=32381; H2=32382
mkdir -p "$B/www-empty" "$B"
# Prefer a packaged jar next to the repo build, else fail clearly.
JAR=$(ls "$ROOT"/target/sjws_nettask-*.jar 2>/dev/null | head -1 || true)
if [[ -z "$JAR" ]]; then
  echo "demo-non2xx: need $ROOT/target/sjws_nettask-*.jar (mvn -DskipTests package)" >&2
  exit 1
fi
if [[ ! -f "$B/bench-cert.pem" ]]; then
  openssl req -x509 -newkey rsa:2048 -keyout "$B/bench-key.pem" -out "$B/bench-cert.pem" \
    -days 1 -nodes -subj "/CN=localhost" -addext "subjectAltName=DNS:localhost,IP:127.0.0.1" 2>/dev/null
fi
fuser -k ${H1}/tcp ${H2C}/tcp ${H2}/tcp 2>/dev/null || true
$J -jar "$JAR" $H1 "$B/www-empty" --h2 --h2-port=$H2 --h2c-port=$H2C \
  --cert="$B/bench-cert.pem" --key="$B/bench-key.pem" >"$B/srv.log" 2>&1 & P=$!
cleanup(){ kill $P 2>/dev/null; wait $P 2>/dev/null || true; fuser -k ${H1}/tcp ${H2C}/tcp ${H2}/tcp 2>/dev/null || true; }
trap cleanup EXIT
sleep 3
kill -0 $P || { echo "demo-non2xx: server failed"; cat "$B/srv.log"; exit 1; }
out=$(SJWS_BENCH_CA="$B/bench-cert.pem" nice -n 10 "$J" "$SCRIPT_DIR/JdkH2Load.java" \
  "http://127.0.0.1:$H2C/1k.bin" 4 2 0 2>&1) || rc=$?
rc=${rc:-0}
echo "$out"
if [[ $rc -eq 2 ]] && echo "$out" | grep -q 'FAIL' && echo "$out" | grep -q 'errors='; then
  echo "demo-non2xx: OK (exit=$rc, 404 counted as errors, FAIL line present)"
  exit 0
fi
echo "demo-non2xx: UNEXPECTED exit=$rc" >&2
exit 1
