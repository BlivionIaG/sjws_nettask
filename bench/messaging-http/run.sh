#!/bin/bash
# End-to-end HTTP produce/fetch with wrk (docs/design/partition-log.md R11, gap G4).
# Usage: bench/messaging-http/run.sh /path/to/sjws_nettask.jar [sizes...]   (default sizes: 100 1024 16384 262144)
# Server pinned to CPUs 0-3, wrk to 4-5; 3 s warm-up then 10 s --latency per size and direction.
# Produce: one record per POST (writer default ASYNC). Fetch: one batch at a fixed committed offset (page cache hot).
# Run it under the shared lock on a quiet box: flock /tmp/sjws-bench.lock bench/messaging-http/run.sh ...
set -eu
jar=$1; shift
sizes=${*:-100 1024 16384 262144}
root=$(mktemp -d); data=$(mktemp -d); lua=$(mktemp); port=${PORT:-18095}
printf 'hi\n' > "$root/index.html"
taskset -c 0-3 java -Xms256m -Xmx256m -XX:+UseG1GC -Dsjws.messaging.enabled=true -Dsjws.messaging.dir="$data" \
  -jar "$jar" "$port" "$root" >/tmp/sjws-messaging-bench.log 2>&1 &
pid=$!
trap 'kill $pid 2>/dev/null || true; wait $pid 2>/dev/null || true; rm -rf "$root" "$data" "$lua"' EXIT
for _ in $(seq 50); do curl -s -o /dev/null "http://127.0.0.1:$port/index.html" && break; sleep 0.2; done
echo "load=$(cut -d' ' -f1 /proc/loadavg)"
summary() { awk '/Requests\/sec/{printf "rps=%s ",$2}/Transfer\/sec/{printf "xfer=%s ",$2}/ 50%/{printf "p50=%s ",$2}/ 99%/{printf "p99=%s ",$2}/Non-2xx/{printf "non2xx=%s ",$NF}'; }
for n in $sizes; do
  printf 'wrk.method="POST"; wrk.body=string.char(%d,%d,%d,%d)..string.rep("x",%d)\n' $((n>>24&255)) $((n>>16&255)) $((n>>8&255)) $((n&255)) "$n" > "$lua"
  c=16; [ "$n" -ge 262144 ] && c=4
  url="http://127.0.0.1:$port/messaging/v1/topics/b$n/records"
  taskset -c 4-5 wrk -t2 -c$c -d3s -s "$lua" "$url" >/dev/null
  echo "produce size=$n c=$c $(taskset -c 4-5 wrk -t2 -c$c -d10s --latency -s "$lua" "$url" | summary)"
  echo "fetch   size=$n c=$c $(taskset -c 4-5 wrk -t2 -c$c -d10s --latency "$url?offset=1000&maxBytes=1" | summary)"
done
echo "load=$(cut -d' ' -f1 /proc/loadavg)"
