#!/bin/bash
# Rate limits on the static fast path (index.html through routing): master vs branch (off), branch
# with a limit that never triggers, one that mostly refuses, and many distinct clients. wrk cannot
# use many source addresses, so "many clients" keys on a header carrying a random id (same hash and
# stripe path as by: peer).
# usage: MASTER_JAR=/path/master.jar WWW=/dir/with/index.html [JAVA=java] bench/proxy-routing/ratelimit.sh
set -u
J=${JAVA:-java}; D=$(cd "$(dirname "$0")" && pwd)
BR=$D/../../target/sjws_nettask-0.2-SNAPSHOT.jar; MJ=${MASTER_JAR:?master jar}; W=${WWW:?document root}
T=$(mktemp -d); P=18101; U=http://127.0.0.1:$P/index.html
start(){ taskset -c 0-3 "$@" >/dev/null 2>&1 & echo $! > $T/pid; sleep 3; }
stop(){ kill $(cat $T/pid); wait $(cat $T/pid) 2>/dev/null; }
w(){ local s=(); [ -n "${1:-}" ] && s=(-s "$1"); taskset -c 4-7 wrk -t4 -c64 -d3s "${s[@]}" $U >/dev/null
  for i in 1 2 3; do taskset -c 4-7 wrk -t4 -c64 -d10s --latency "${s[@]}" $U | awk '/Requests\/sec/{r=$2} / 99%/{p=$2} /Non-2xx/{n=$5} /requests in/{t=$1} END{printf "%s/%s/%.1f%% ", r, p, 100*n/t}'; done; }
w2(){ taskset -c 4-7 wrk2 -t4 -c64 -d15s -R100000 --latency $U | awk '/ 50.000%/{a=$2} / 99.000%/{b=$2} / 99.900%/{c=$2} END{printf "wrk2 R=100k p50=%s p99=%s p99.9=%s", a,b,c}'; }
cfg(){ printf 'server:\n  port: %s\n  root: "%s"\nhttp3:\n  enabled: false\nrouting:\n  enabled: true\n%b  routes:\n    - prefix: /\n      static: true\n%b' $P "$W" "$1" "$2" > $T/c.yaml; echo $T/c.yaml; }
printf 'math.randomseed(os.time())\nrequest = function()\n  return wrk.format(nil, nil, {["X-Client"] = tostring(math.random(1, 1000000))})\nend\n' > $T/many.lua
UNDER='      rate_limit: { by: peer, rate: 1000000000/s, burst: 1000000 }\n'
OVER='      rate_limit: { by: peer, rate: 1000/s, burst: 100 }\n'
MANY_UNDER='      rate_limit: { by: header, header: X-Client, rate: 1000000/s, burst: 1000000, buckets: 1048576 }\n'
MANY_OVER='      rate_limit: { by: header, header: X-Client, rate: 1/h, burst: 1, buckets: 1048576 }\n'
echo "rps/p99/non-2xx per 10s run"
echo "== master: $(start $J -jar $MJ --config=$(cfg '' ''); w; w2; stop)"
echo "== branch off: $(start $J -jar $BR --config=$(cfg '' ''); w; w2; stop)"
echo "== branch on, under the limit (route): $(start $J -jar $BR --config=$(cfg '' "$UNDER"); w; w2; stop)"
echo "== branch on, under the limit (global): $(start $J -jar $BR --config=$(cfg '  rate_limit: { rate: 1000000000/s, burst: 1000000 }\n' ''); w; stop)"
echo "== branch on, over the limit (1000/s): $(start $J -jar $BR --config=$(cfg '' "$OVER"); w; stop)"
echo "== branch many clients (1M ids, 1M stripes), under: $(start $J -jar $BR --config=$(cfg '' "$MANY_UNDER"); w $T/many.lua; stop)"
echo "== branch many clients (1M ids, 1M stripes), 1/h each: $(start $J -jar $BR --config=$(cfg '' "$MANY_OVER"); w $T/many.lua; stop)"
echo "== master many clients (same lua, no limit): $(start $J -jar $MJ --config=$(cfg '' ''); w $T/many.lua; stop)"
rm -rf $T
