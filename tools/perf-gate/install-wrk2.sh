#!/usr/bin/env bash
# Build a pinned giltene/wrk2 into DEST (default: $HOME/.local/bin/wrk2).
# wrk2 is not packaged on Ubuntu; the official wrk binary has no fixed-rate -R mode.
# Usage: tools/perf-gate/install-wrk2.sh [DEST]
set -euo pipefail

DEST="${1:-${HOME}/.local/bin/wrk2}"
# Last commit on https://github.com/giltene/wrk2 (master) as of 2026-11-11.
WRK2_COMMIT="44a94c17d8e6a0bac8559b53da76848e430cb7a7"
PREFIX="$(dirname "$DEST")"
mkdir -p "$PREFIX"

if [ -x "$DEST" ] && "$DEST" --help 2>&1 | grep -q -- '--rate'; then
  # Reuse a previously built binary when the script is re-run on the same runner.
  echo "wrk2 already present at $DEST"
  exit 0
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
# wrk2 vendors LuaJIT; -j1 avoids a known parallel build race in that tree.
make -C "$SRC/wrk2" -j1
install -m 0755 "$SRC/wrk2/wrk" "$DEST"
echo "installed wrk2 ${WRK2_COMMIT} to $DEST"
