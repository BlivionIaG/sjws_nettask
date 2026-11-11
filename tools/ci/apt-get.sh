#!/usr/bin/env bash
# Retrying apt-get for GitHub-hosted runners.
#
# Official Ubuntu mirrors on these runners (azure.archive.ubuntu.com,
# archive.ubuntu.com, security.ubuntu.com) occasionally fail DNS after the
# test steps have already passed; see master run 43045130322.
#
# Usage: tools/ci/apt-get.sh update -q
#        tools/ci/apt-get.sh install -y -q wrk
set -euo pipefail

APT_OPTS=(
  -o Acquire::Retries=5
  -o Acquire::http::Timeout=30
  -o Acquire::https::Timeout=30
  -o Acquire::ForceIPv4=true
)

run_apt() {
  sudo DEBIAN_FRONTEND=noninteractive apt-get "${APT_OPTS[@]}" "$@"
}

# Five outer attempts with linear backoff, on top of Acquire::Retries.
retry() {
  local n=0
  local max=5
  until run_apt "$@"; do
    n=$((n + 1))
    if [ "$n" -ge "$max" ]; then
      return 1
    fi
    local wait=$((n * 8))
    echo "apt-get $* failed (attempt ${n}/${max}), retrying in ${wait}s..." >&2
    sleep "$wait"
  done
}

ubuntu_suite() {
  # shellcheck source=/dev/null
  . /etc/os-release
  printf '%s' "${VERSION_CODENAME:-}"
}

# Point apt at one fallback Ubuntu archive (not under ubuntu.com) so a
# *.ubuntu.com DNS outage does not block the rest of the job.
use_fallback_mirror() {
  local uri="$1"
  local suite
  suite="$(ubuntu_suite)"
  if [ -z "$suite" ]; then
    echo "error: could not determine Ubuntu codename for mirror fallback" >&2
    return 1
  fi
  echo "Switching apt sources to ${uri} (suite ${suite})..." >&2
  if [ -f /etc/apt/sources.list.d/ubuntu.sources ]; then
    sudo cp -a /etc/apt/sources.list.d/ubuntu.sources /etc/apt/sources.list.d/ubuntu.sources.bak-sjws
  fi
  if [ -f /etc/apt/apt-mirrors.txt ]; then
    sudo cp -a /etc/apt/apt-mirrors.txt /etc/apt/apt-mirrors.txt.bak-sjws
    printf '%s\n' "$uri" | sudo tee /etc/apt/apt-mirrors.txt >/dev/null
  fi
  sudo tee /etc/apt/sources.list.d/ubuntu.sources >/dev/null <<SOURCES
Types: deb
URIs: ${uri}
Suites: ${suite} ${suite}-updates ${suite}-backports ${suite}-security
Components: main universe restricted multiverse
Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg
SOURCES
  if [ -f /etc/apt/sources.list ]; then
    sudo cp -a /etc/apt/sources.list /etc/apt/sources.list.bak-sjws
    printf '%s\n' "# disabled by tools/ci/apt-get.sh; see ubuntu.sources" | sudo tee /etc/apt/sources.list >/dev/null
  fi
}

if retry "$@"; then
  exit 0
fi

echo "apt-get $* failed after retries; trying fallback Ubuntu mirrors..." >&2
for mirror in http://mirrors.kernel.org/ubuntu http://mirrors.wikimedia.org/ubuntu; do
  if ! use_fallback_mirror "$mirror"; then
    continue
  fi
  if [ "$1" != "update" ]; then
    retry update -q || continue
  fi
  if retry "$@"; then
    exit 0
  fi
done

echo "apt-get $* failed after retries and fallback mirrors" >&2
exit 1
