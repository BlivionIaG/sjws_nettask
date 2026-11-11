#!/usr/bin/env bash
# Probes for tools/ci/install-wrk.sh runnable()/already().
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=install-wrk.sh
source "$here/install-wrk.sh"

fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
pass() { printf 'ok: %s\n' "$*"; }

tmpdir=""
cleanup() { /bin/rm -rf "$tmpdir"; }
trap cleanup EXIT
tmpdir="$(mktemp -d)"
mkdir -p "$tmpdir/bin"
# Isolate from a wrk already on the runner.
export PATH="$tmpdir/bin:$PATH"

write_wrk() {
  cat > "$tmpdir/bin/wrk"
  chmod +x "$tmpdir/bin/wrk"
}

# wrk 4.1 usage exit 1 after a successful load — accept
write_wrk << 'SCRIPT'
#!/bin/sh
echo "Usage: wrk <options> <url>" >&2
exit 1
SCRIPT
if ! runnable; then
  fail "usage exit 1 should be accepted"
fi
if ! already >/dev/null; then
  fail "already() should accept usage exit 1"
fi
pass "usage exit 1 accepted"

# exit 127 (dynamic loader) — reject, fall through to apt
write_wrk << 'SCRIPT'
#!/bin/sh
echo "wrk: error while loading shared libraries: libluajit-5.1.so.2: cannot open shared object file: No such file or directory" >&2
exit 127
SCRIPT
if runnable; then
  fail "exit 127 should be rejected"
fi
if already >/dev/null 2>&1; then
  fail "already() should reject exit 127"
fi
pass "exit 127 rejected"

# exit 126 — reject
write_wrk << 'SCRIPT'
#!/bin/sh
echo "wrk: Exec format error" >&2
exit 126
SCRIPT
if runnable; then
  fail "exit 126 should be rejected"
fi
pass "exit 126 rejected"

# Loader message with exit 1 must not be treated as usage
write_wrk << 'SCRIPT'
#!/bin/sh
echo "wrk: error while loading shared libraries: libluajit-5.1.so.2: cannot open shared object file: No such file or directory" >&2
exit 1
SCRIPT
if runnable; then
  fail "loader error with exit 1 should be rejected (not treated as usage)"
fi
if already >/dev/null 2>&1; then
  fail "already() should reject loader error with exit 1"
fi
pass "loader error with exit 1 rejected"

# ldd reports 'not found', even if wrk exits 0
mkdir -p "$tmpdir/lddpath"
cat > "$tmpdir/lddpath/ldd" << 'SCRIPT'
#!/bin/sh
echo "	libluajit-5.1.so.2 => not found"
exit 0
SCRIPT
chmod +x "$tmpdir/lddpath/ldd"
write_wrk << 'SCRIPT'
#!/bin/sh
exit 0
SCRIPT
PATH="$tmpdir/lddpath:$tmpdir/bin:$PATH"
if runnable; then
  fail "ldd 'not found' should be rejected"
fi
if already >/dev/null 2>&1; then
  fail "already() should reject ldd not found"
fi
pass "ldd not found rejected"

# missing wrk
mkdir -p "$tmpdir/empty"
PATH="$tmpdir/empty"
if runnable; then
  fail "missing wrk should be rejected"
fi
pass "missing wrk rejected"

echo "all install-wrk probes passed"
