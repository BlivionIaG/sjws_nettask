#!/usr/bin/env bash
# Build a pinned giltene/wrk2 into DEST (default: $HOME/.local/bin/wrk2).
# wrk2 is not packaged on Ubuntu; the official wrk binary has no fixed-rate -R mode.
# Usage: tools/perf-gate/install-wrk2.sh [DEST]
set -euo pipefail

DEST="${1:-${HOME}/.local/bin/wrk2}"
# Last commit on https://github.com/giltene/wrk2 (master) as of 2026-11-11.
WRK2_COMMIT="44a94c17d8e6a0bac8559b53da76848e430cb7a7"
# giltene/wrk2 vendors LuaJIT 2.0.x, which panics on current Ubuntu runners
# (gcc 14+ / 26.04): "PANIC: unprotected error in call to Lua API (attempt to
# index a nil value)". Seen after Install wrk2 in run 43077360085 attempts 1
# and 2. Use LuaJIT v2.1 at this pin instead.
LUAJIT_COMMIT="63c8f1a9b479ba5518cf6a170ff7729178bc3cfd"
PREFIX="$(dirname "$DEST")"
mkdir -p "$PREFIX"

wrk2_smoke() {
  local bin="$1" out
  out="$("$bin" -t1 -c1 -d1s -R1 http://127.0.0.1:1 2>&1)" || true
  if printf '%s\n' "$out" | grep -q 'PANIC:'; then
    printf '%s\n' "$out" >&2
    return 1
  fi
  return 0
}

if [ -x "$DEST" ] && "$DEST" --help 2>&1 | grep -q -- '--rate'; then
  if wrk2_smoke "$DEST"; then
    echo "wrk2 already present at $DEST"
    exit 0
  fi
  echo "existing wrk2 at $DEST is not usable, rebuilding..." >&2
  rm -f "$DEST"
fi

apt_retry() {
  local n=0
  until sudo DEBIAN_FRONTEND=noninteractive apt-get "$@"; do
    n=$((n + 1))
    if [ "$n" -ge 5 ]; then
      return 1
    fi
    sleep $((n * 8))
  done
}

missing_build=0
command -v gcc >/dev/null 2>&1 || missing_build=1
command -v make >/dev/null 2>&1 || missing_build=1
if [ ! -e /usr/include/openssl/ssl.h ]; then
  missing_build=1
fi
if [ "$missing_build" = 1 ]; then
  if command -v apt-get >/dev/null 2>&1; then
    apt_retry update -q
    apt_retry install -y -q build-essential libssl-dev zlib1g-dev
  else
    echo "gcc, make, and OpenSSL headers are required to build wrk2" >&2
    exit 1
  fi
fi

SRC="$(mktemp -d)"
trap 'rm -rf "$SRC"' EXIT
git clone --quiet https://github.com/giltene/wrk2.git "$SRC/wrk2"
git -C "$SRC/wrk2" checkout --quiet "$WRK2_COMMIT"
rm -rf "$SRC/wrk2/deps/luajit"
git clone --quiet https://github.com/LuaJIT/LuaJIT.git "$SRC/wrk2/deps/luajit"
git -C "$SRC/wrk2/deps/luajit" checkout --quiet "$LUAJIT_COMMIT"
# wrk2 vendors LuaJIT; -j1 avoids a known parallel build race in that tree.
make -C "$SRC/wrk2" -j1
install -m 0755 "$SRC/wrk2/wrk" "$DEST"
if ! wrk2_smoke "$DEST"; then
  echo "error: wrk2 at $DEST panics in the Lua runtime" >&2
  exit 1
fi
echo "installed wrk2 ${WRK2_COMMIT} (LuaJIT ${LUAJIT_COMMIT}) to $DEST"
