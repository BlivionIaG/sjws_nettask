#!/bin/bash
# Weighted/canary backends: proxied 1 KiB through master (proxy:), branch (proxy:), and branch
# backends: 1, 2 (90/10) and 10 (weights 1..10), plus a sticky-cookie run. Upstream is Count.java
# on 10 ports (one per backend); the per-backend hit counts give the distribution accuracy.
# usage: MASTER_JAR=/path/master.jar WWW=/dir [JAVA=java] bench/proxy-routing/weighted.sh (build this branch first)
set -u
J=${JAVA:-java}; D=$(cd "$(dirname "$0")" && pwd)
BR=$D/../../target/sjws_nettask-0.2-SNAPSHOT.jar; MJ=${MASTER_JAR:?master jar}; W=${WWW:?document root}
T=$(mktemp -d); P=18101; U=http://127.0.0.1:$P/x; CB=18300
start(){ taskset -c 0-3 "$@" >/dev/null 2>&1 & echo $! > $T/pid; sleep 3; }
stop(){ kill $(cat $T/pid); wait $(cat $T/pid) 2>/dev/null; }
w(){ taskset -c 4-7 wrk -t4 -c64 -d3s $1 >/dev/null; for i in 1 2 3; do taskset -c 4-7 wrk -t4 -c64 -d10s --latency ${2:-} $1 | awk '/Requests\/sec/{r=$2} / 99%/{p=$2} END{printf "%s/%s ", r, p}'; done; }
ups(){ printf '  upstreams:\n'; for ((i=0;i<10;i++)); do printf '    u%d: { url: "http://127.0.0.1:%d" }\n' $i $((CB+i)); done; }
cfg(){ printf 'server:\n  port: %s\n  root: "%s"\nhttp3:\n  enabled: false\nrouting:\n  enabled: true\n%s\n  routes:\n%b' $P "$W" "$(ups)" "$1" > $T/c.yaml; echo $T/c.yaml; }
counts(){ curl -s http://127.0.0.1:$CB/__counts; }
reset(){ curl -s http://127.0.0.1:$CB/__reset; }
share(){ counts | awk -v w="$1" '{n=split(w,ws,","); t=0; for(i=1;i<=n;i++){t+=$i; s+=ws[i]} e=0; out=""; for(i=1;i<=n;i++){o=$i/t; x=ws[i]/s; d=o-x; if(d<0)d=-d; if(d>e)e=d; out=out sprintf("%.4f/%.4f ", o, x)} printf "observed/expected: %s max_abs_err=%.5f (n=%d)", out, e, t}'; }
taskset -c 0-3 $J $D/Count.java $CB 10 >/dev/null 2>&1 & UP=$!; sleep 4
B1='    - prefix: /\n      backends: [ { upstream: u0 } ]\n'
B2='    - prefix: /\n      backends: [ { upstream: u0, weight: 90 }, { upstream: u1, weight: 10 } ]\n'
B10='    - prefix: /\n      backends:\n'; for ((i=0;i<10;i++)); do B10+="        - { upstream: u$i, weight: $((i+1)) }\n"; done
P1='    - { prefix: /, proxy: u0 }\n'
echo "== master proxy: $(start $J -jar $MJ --config=$(cfg "$P1"); w $U; stop)"
echo "== branch proxy (off): $(start $J -jar $BR --config=$(cfg "$P1"); w $U; stop)"
echo "== branch backends=1: $(start $J -jar $BR --config=$(cfg "$B1"); w $U; stop)"
start $J -jar $BR --config=$(cfg "$B2"); reset; echo "== branch backends=2 (90/10): $(w $U) $(share 90,10)"; stop
start $J -jar $BR --config=$(cfg "$B10"); reset; echo "== branch backends=10 (1..10): $(w $U) $(share 1,2,3,4,5,6,7,8,9,10)"; stop
# sticky: one cookie per wrk connection would need a script; a fixed cookie must hit exactly one backend
start $J -jar $BR --config=$(cfg "${B10}      sticky: { cookie: s }\n"); reset; echo "== branch backends=10 sticky (one cookie): $(w $U '-H Cookie:s=client42') $(counts)"; stop
kill $UP; rm -rf $T
