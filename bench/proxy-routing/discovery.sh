#!/bin/bash
# Discovery (B.4): proxied /x through master and this branch, then multi-endpoint upstreams.
#  1. off: static files, master vs branch (routing disabled)
#  2. single url upstream, master vs branch
#  3. endpoints: 1 vs 3 (weighted_random), 3 round_robin, 3 least_request
#  4. ejection mid-run: 3 endpoints, one upstream killed 3 s into a 10 s run (retry attempts 2)
#  5. set swaps under load: Swap.java replaces the set every 50 ms during the run
# Upstreams are Count.java, one process per port. Prints req/s / p99 per run (3 runs after warm-up).
# SWAP_ONLY=1 runs only the last two (static baseline and swaps).
# usage: MASTER_JAR=/path/master.jar WWW=/dir [JAVA=java] bench/proxy-routing/discovery.sh (package this branch first)
set -u
J=${JAVA:-java}; D=$(cd "$(dirname "$0")" && pwd); TG=$D/../../target
BR=$TG/sjws_nettask-0.2-SNAPSHOT.jar; MJ=${MASTER_JAR:?master jar}; W=${WWW:?document root}
T=$(mktemp -d); P=18101; U=http://127.0.0.1:$P/x; CB=18400
start(){ taskset -c 0-3 "$@" >/dev/null 2>&1 & echo $! > $T/pid; sleep 3; }
stop(){ kill $(cat $T/pid); wait $(cat $T/pid) 2>/dev/null; }
w(){ taskset -c 4-7 wrk -t4 -c64 -d3s $1 >/dev/null; for i in 1 2 3; do taskset -c 4-7 wrk -t4 -c64 -d10s --latency $1 | awk '/Requests\/sec/{r=$2} / 99%/{p=$2} END{printf "%s/%s ", r, p}'; done; }
one(){ taskset -c 4-7 wrk -t4 -c64 -d3s $1 >/dev/null; taskset -c 4-7 wrk -t4 -c64 -d10s --latency $1 | awk '/Requests\/sec/{r=$2} / 99%/{p=$2} /Non-2xx/{e=$NF} /requests in/{n=$1} END{printf "%s/%s requests=%s non2xx_or_errors=%s", r, p, n, e+0}'; }
cfg(){ printf 'server:\n  port: %s\n  root: "%s"\nhttp3:\n  enabled: false\nrouting:\n  enabled: %s\n  upstreams:\n%b  routes:\n    - { prefix: /, proxy: pool }\n' $P "$W" "${2:-true}" "$1" > $T/c.yaml; echo $T/c.yaml; }
eps(){ local s="    pool:\n      endpoints: ["; for ((i=0;i<$1;i++)); do s+="\"http://127.0.0.1:$((CB+i))\""; [ $i -lt $(($1-1)) ] && s+=", "; done; echo "$s]\n      lb: ${2:-weighted_random}\n      retry: { attempts: 2 }\n"; }
for ((i=0;i<6;i++)); do taskset -c 0-3 $J $D/Count.java $((CB+i)) 1 >/dev/null 2>&1 & echo $! > $T/up$i; done; sleep 5
if [ -z "${SWAP_ONLY:-}" ]; then
URL="    pool: { url: \"http://127.0.0.1:$CB\" }\n"
echo "== off (static), master: $(start $J -jar $MJ --config=$(cfg "$URL" false); w http://127.0.0.1:$P/; stop)"
echo "== off (static), branch: $(start $J -jar $BR --config=$(cfg "$URL" false); w http://127.0.0.1:$P/; stop)"
echo "== url, master: $(start $J -jar $MJ --config=$(cfg "$URL"); w $U; stop)"
echo "== url, branch: $(start $J -jar $BR --config=$(cfg "$URL"); w $U; stop)"
echo "== endpoints=1: $(start $J -jar $BR --config=$(cfg "$(eps 1)"); w $U; stop)"
echo "== endpoints=3 weighted_random: $(start $J -jar $BR --config=$(cfg "$(eps 3)"); w $U; stop)"
echo "== endpoints=3 round_robin: $(start $J -jar $BR --config=$(cfg "$(eps 3 round_robin)"); w $U; stop)"
echo "== endpoints=3 least_request: $(start $J -jar $BR --config=$(cfg "$(eps 3 least_request)"); w $U; stop)"
start $J -jar $BR --config=$(cfg "$(eps 3)"); taskset -c 4-7 wrk -t4 -c64 -d3s $U >/dev/null
( sleep 3; kill $(cat $T/up2) ) & K=$!; echo "== endpoints=3, one killed at 3 s: $(one $U)"; wait $K; stop
taskset -c 0-3 $J $D/Count.java $((CB+2)) 1 >/dev/null 2>&1 & echo $! > $T/up2; sleep 4
fi
echo "== endpoints=3 static (swap baseline): $(start $J -jar $BR --config=$(cfg "$(eps 3)"); one $U; stop)"
taskset -c 0-3 $J -cp "$BR:$TG/lib/*" $D/Swap.java $(cfg "$(eps 3)") 50 20 $CB $((CB+1)) $((CB+2)) $((CB+3)) $((CB+4)) > $T/swap 2>&1 & SW=$!; sleep 4
echo "== endpoints=3, set swapped every 50 ms: $(one $U) $(wait $SW; cat $T/swap)"
for ((i=0;i<6;i++)); do kill $(cat $T/up$i) 2>/dev/null; done; rm -rf $T
