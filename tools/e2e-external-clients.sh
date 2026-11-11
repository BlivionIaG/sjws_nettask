#!/bin/sh
# Drive a live sjws_nettask with real external clients (curl required; h2load / HTTP/3 optional).
# Usage:
#   tools/e2e-external-clients.sh              # start a temp server, then probe it
#   tools/e2e-external-clients.sh --url URL    # probe an already running HTTP/1.1 listener
# Env:
#   H2C_URL   cleartext HTTP/2 base (e.g. http://127.0.0.1:8444); optional
#   H3_URL    HTTPS/HTTP3 base (e.g. https://127.0.0.1:8443); optional
#   SJWS_JAR  jar used when this script starts the server (default: target/sjws_nettask-*.jar)
# Missing optional clients are skipped with a message; curl HTTP/1.1 failures fail the script.
set -eu
cd "$(dirname "$0")/.."
URL=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    --url) URL="${2:?}"; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

ROOT=""
LOG=""
PID=""
cleanup() {
  if [ -n "${PID:-}" ]; then
    kill "$PID" 2>/dev/null || true
    wait "$PID" 2>/dev/null || true
  fi
  if [ -n "${ROOT:-}" ]; then
    rm -rf "$ROOT"
  fi
  if [ -n "${LOG:-}" ]; then
    rm -f "$LOG"
  fi
  rm -f "${BODY:-}" "${RANGE:-}"
}
trap cleanup EXIT

start_server() {
  ROOT="$(mktemp -d)"
  LOG="$(mktemp)"
  mkdir -p "$ROOT/html"
  printf 'hello from the temp docroot\n' > "$ROOT/html/hello.txt"
  if [ -z "${SJWS_JAR:-}" ]; then
    SJWS_JAR="$(ls target/sjws_nettask-*.jar 2>/dev/null | grep -v -e sources -e tests | head -n1 || true)"
  fi
  if [ -z "$SJWS_JAR" ] || [ ! -f "$SJWS_JAR" ]; then
    echo "build the jar first (mvn -B -DskipTests package) or set SJWS_JAR" >&2
    exit 1
  fi
  JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
  # shellcheck disable=SC2086
  "$JAVA" -jar "$SJWS_JAR" 0 "$ROOT/html" --h2 --h2-port=0 --h2c-port=0 --h3 --h3-port=0 --h3-host=127.0.0.1 \
    >"$LOG" 2>&1 &
  PID=$!
  i=0
  while [ -z "$URL" ]; do
    i=$((i + 1))
    if [ "$i" -gt 100 ]; then
      echo "server did not start:" >&2
      cat "$LOG" >&2
      exit 1
    fi
    if ! kill -0 "$PID" 2>/dev/null; then
      echo "server exited:" >&2
      cat "$LOG" >&2
      exit 1
    fi
    port=$(sed -n 's/.*HTTP\/1\.1 on tcp\/\([0-9][0-9]*\).*/\1/p' "$LOG" | tail -n 1)
    if [ -n "$port" ]; then
      URL="http://127.0.0.1:$port"
    else
      sleep 0.1
    fi
  done
  if [ -z "${H2C_URL:-}" ]; then
    h2c=$(sed -n 's/.*h2c tcp\/\([0-9][0-9]*\).*/\1/p' "$LOG" | tail -n 1)
    if [ -n "$h2c" ]; then
      H2C_URL="http://127.0.0.1:$h2c"
    fi
  fi
  if [ -z "${H3_URL:-}" ]; then
    h3=$(sed -n 's/.*HTTP\/3 on udp\/\([0-9][0-9]*\).*/\1/p' "$LOG" | tail -n 1)
    if [ -n "$h3" ]; then
      H3_URL="https://127.0.0.1:$h3"
    fi
  fi
  echo "listening $URL ${H2C_URL:-} ${H3_URL:-}"
}

if [ -z "$URL" ]; then
  start_server
fi

command -v curl >/dev/null 2>&1 || { echo "curl is required" >&2; exit 1; }
BODY=$(mktemp)
RANGE=$(mktemp)

code=$(curl -sS --http1.1 -o "$BODY" -w "%{http_code}" "$URL/hello.txt")
[ "$code" = "200" ] || { echo "HTTP/1.1 GET expected 200, got $code" >&2; exit 1; }
grep -q "hello from the temp docroot" "$BODY"
code=$(curl -sS --http1.1 -o /dev/null -w "%{http_code}" "$URL/no-such-file.txt")
[ "$code" = "404" ] || { echo "HTTP/1.1 missing file expected 404, got $code" >&2; exit 1; }
range=$(curl -sS --http1.1 -H "Range: bytes=0-4" -o "$RANGE" -w "%{http_code}" "$URL/hello.txt")
[ "$range" = "206" ] || { echo "HTTP/1.1 range expected 206, got $range" >&2; exit 1; }
[ "$(cat "$RANGE")" = "hello" ] || { echo "range body mismatch" >&2; exit 1; }
echo "curl HTTP/1.1: 200, 404, 206 ok"

if curl --version 2>/dev/null | grep -q HTTP2 && [ -n "${H2C_URL:-}" ]; then
  ver=$(curl -sS --http2-prior-knowledge -o /dev/null -w "%{http_code} %{http_version}" "$H2C_URL/hello.txt")
  [ "$ver" = "200 2" ] || { echo "curl HTTP/2 expected '200 2', got $ver" >&2; exit 1; }
  echo "curl HTTP/2 prior knowledge: ok"
else
  echo "skip curl HTTP/2 (no HTTP2 feature or no H2C_URL)"
fi

if command -v h2load >/dev/null 2>&1 && [ -n "${H2C_URL:-}" ]; then
  h2load --h2c -n 10 -c 2 "$H2C_URL/hello.txt"
  echo "h2load smoke: ok"
else
  echo "skip h2load (not on PATH or no H2C_URL)"
fi

if curl --version 2>/dev/null | grep -q HTTP3 && [ -n "${H3_URL:-}" ]; then
  ver=$(curl -sS --http3-only -k -o /dev/null -w "%{http_code} %{http_version}" "$H3_URL/hello.txt")
  [ "$ver" = "200 3" ] || { echo "curl HTTP/3 expected '200 3', got $ver" >&2; exit 1; }
  echo "curl HTTP/3: ok"
else
  echo "skip curl HTTP/3 (no HTTP3 feature or no H3_URL)"
fi

echo "external-client e2e finished"
