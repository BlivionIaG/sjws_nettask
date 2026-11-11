#!/usr/bin/env bash
# Install wrk for CI benches. Prefers a preinstalled or cached binary so an
# apt/DNS flake does not fail the job after tests have already passed.
#
# Usage: tools/ci/install-wrk.sh
# Optional: WRK_CACHE=/path/to/dir  (stores or restores a wrk binary)
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"

already() {
  if ! command -v wrk >/dev/null 2>&1; then
    return 1
  fi
  echo "wrk already on PATH: $(command -v wrk)"
  wrk --version 2>&1 || true
  return 0
}

if already; then
  exit 0
fi

cache="${WRK_CACHE:-}"
if [ -n "$cache" ] && [ -x "$cache/wrk" ]; then
  sudo install -m 0755 "$cache/wrk" /usr/local/bin/wrk
  if already; then
    echo "wrk restored from ${cache}/wrk"
    exit 0
  fi
  echo "cached wrk was not usable, installing via apt..." >&2
  sudo rm -f /usr/local/bin/wrk
fi

"$here/apt-get.sh" update -q
"$here/apt-get.sh" install -y -q wrk

if ! already; then
  echo "error: apt-get reported success but wrk is not on PATH" >&2
  exit 1
fi

if [ -n "$cache" ]; then
  mkdir -p "$cache"
  cp -a "$(command -v wrk)" "$cache/wrk"
  echo "cached wrk at ${cache}/wrk"
fi
