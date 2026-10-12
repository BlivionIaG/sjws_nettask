#!/bin/sh
# Feature-off HTTP/1.1 wrk compare. The declarative chain is not attached.
# Usage: bench/filter-chain/run.sh /path/to/sjws_nettask.jar label
# Prints one wrk summary per repetition to stdout.
set -eu
jar=$1
label=$2
root=$(mktemp -d)
printf 'hello\n' > "$root/index.html"
port=18080
java_bin=${JAVA_BIN:-java}
"$java_bin" -Xms256m -Xmx256m -XX:+UseG1GC -jar "$jar" "$port" "$root" >/tmp/sjws-bench-"$label".log 2>&1 &
pid=$!
trap 'kill "$pid" 2>/dev/null || true; wait "$pid" 2>/dev/null || true; rm -rf "$root"' EXIT
i=0
while [ "$i" -lt 50 ]; do
  if wrk -t1 -c1 -d1s "http://127.0.0.1:$port/index.html" >/dev/null 2>&1; then
    break
  fi
  i=$((i + 1))
  sleep 0.1
done
wrk -t2 -c32 -d3s "http://127.0.0.1:$port/index.html" >/dev/null
echo "=== $label ==="
n=0
while [ "$n" -lt 3 ]; do
  wrk -t2 -c32 -d10s --latency "http://127.0.0.1:$port/index.html"
  n=$((n + 1))
done
