#!/usr/bin/env bash
# Install wrk for CI benches. Prefers a preinstalled or cached prefix so an
# apt/DNS flake does not fail the job after tests have already passed.
#
# The cache stores the wrk binary plus non-base shared libraries (luajit).
# A bare wrk binary is not runnable on a fresh runner; see PR CI run
# 43066143761, which restored a 38 KiB wrk-ubuntu-26.04-X64 cache without
# libluajit-5.1.so.2.
#
# Usage: tools/ci/install-wrk.sh
# Optional: WRK_CACHE=/path/to/dir  (stores or restores wrk + its libs)
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"

runnable() {
  command -v wrk >/dev/null 2>&1 && wrk --version >/dev/null 2>&1
}

already() {
  if ! runnable; then
    return 1
  fi
  echo "wrk already on PATH: $(command -v wrk)"
  wrk --version 2>&1 || true
  return 0
}

# Copy wrk and its non-base shared-library dependencies into $1/.
cache_prefix() {
  local dest="$1"
  local bin
  bin="$(command -v wrk)"
  mkdir -p "$dest/lib"
  cp -a "$bin" "$dest/wrk"
  if ! command -v ldd >/dev/null 2>&1; then
    return 0
  fi
  local line so real base
  while IFS= read -r line; do
    so="$(printf '%s\n' "$line" | awk '/=> \// {print $3}')"
    [ -n "${so:-}" ] && [ -e "$so" ] || continue
    base="$(basename "$so")"
    case "$base" in
      libc.so.*|libm.so.*|libpthread.so.*|libdl.so.*|librt.so.*|libgcc_s.so.*|ld-linux*.so.*|linux-vdso.so.*)
        continue
        ;;
    esac
    real="$(readlink -f "$so")"
    cp -a "$real" "$dest/lib/$(basename "$real")"
    if [ "$(basename "$real")" != "$base" ]; then
      ln -sfn "$(basename "$real")" "$dest/lib/$base"
    fi
  done < <(ldd "$bin")
}

install_prefix() {
  local src="$1"
  [ -x "$src/wrk" ] || return 1
  sudo install -m 0755 "$src/wrk" /usr/local/bin/wrk
  if [ -d "$src/lib" ] && [ -n "$(ls -A "$src/lib" 2>/dev/null || true)" ]; then
    sudo mkdir -p /usr/local/lib
    sudo cp -a "$src/lib/." /usr/local/lib/
    printf '%s\n' /usr/local/lib | sudo tee /etc/ld.so.conf.d/sjws-wrk.conf >/dev/null
    sudo ldconfig
  fi
}

if already; then
  exit 0
fi

cache="${WRK_CACHE:-}"
if [ -n "$cache" ] && [ -x "$cache/wrk" ]; then
  if install_prefix "$cache" && already; then
    echo "wrk restored from ${cache}"
    exit 0
  fi
  echo "cached wrk was not usable, installing via apt..." >&2
  sudo rm -f /usr/local/bin/wrk
fi

"$here/apt-get.sh" update -q
"$here/apt-get.sh" install -y -q wrk

if ! already; then
  echo "error: apt-get reported success but wrk is not runnable" >&2
  exit 1
fi

if [ -n "$cache" ]; then
  mkdir -p "$cache"
  cache_prefix "$cache"
  echo "cached wrk at ${cache}/wrk"
fi
