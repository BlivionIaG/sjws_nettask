#!/usr/bin/env bash
# Retry apt-get. GitHub-hosted runners occasionally fail "Temporary failure resolving"
# archive.ubuntu.com / security.ubuntu.com mid-update.
# Usage: tools/perf-gate/apt-retry.sh update -q
#        tools/perf-gate/apt-retry.sh install -y -q wrk
set -euo pipefail
n=0
until sudo DEBIAN_FRONTEND=noninteractive apt-get "$@"; do
  n=$((n + 1))
  if [ "$n" -ge 5 ]; then
    exit 1
  fi
  echo "apt-get $* failed (attempt $n), retrying..." >&2
  sleep $((n * 8))
done
