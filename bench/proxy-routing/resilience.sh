#!/bin/bash
# Retries/circuit breaking: proxied 1k.bin with retry+circuit off vs on, against a healthy upstream
# (master static server), a flaky one (Flaky.java, FAIL% 503s) and a dead one (closed port).
# usage: MASTER_JAR=/path/master.jar WWW=/dir/with/1k.bin [JAVA=java] [FAIL=20] bench/proxy-routing/resilience.sh
# Build this branch first. Server and upstream on CPUs 0-3, wrk/wrk2 on 4-7. Reports rps/p99 and non-2xx.
set -u
J=${JAVA:-java}; D=$(cd "$(dirname "$0")" && pwd)
BR=$D/../../target/sjws_nettask-0.2-SNAPSHOT.jar; MJ=${MASTER_JAR:?master jar}; W=${WWW:?document root}
T=$(mktemp -d); P=18101; U=http://127.0.0.1:$P/1k.bin
start(){ taskset -c 0-3 "$@" >/dev/null 2>&1 & echo $! > $T/pid; sleep 3; }
stop(){ kill $(cat $T/pid); wait $(cat $T/pid) 2>/dev/null; }
w(){ taskset -c 4-7 wrk -t4 -c64 -d3s $1 >/dev/null; taskset -c 4-7 wrk -t4 -c64 -d10s --latency $1 | awk '/Requests\/sec/{r=$2} / 99%/{p=$2} /Non-2xx/{n=$5} /requests in/{t=$1} END{printf "wrk rps=%s p99=%s non2xx=%s/%s\n", r, p, n+0, t}'; }
w2(){ taskset -c 4-7 wrk2 -t4 -c64 -d15s -R$2 --latency $1 | awk '/ 50.000%/{a=$2} / 99.000%/{b=$2} / 99.900%/{c=$2} /Non-2xx/{n=$5} /requests in/{t=$1} END{printf "wrk2 R=%s p50=%s p99=%s p99.9=%s non2xx=%s/%s\n", "'$2'",a,b,c,n+0,t}'; }
cfg(){ printf 'server:\n  port: %s\n  root: "%s"\nhttp3:\n  enabled: false\nrouting:\n  enabled: true\n  upstreams:\n    up:\n      url: "http://127.0.0.1:%s"\n%b  routes:\n    - { prefix: /, proxy: up }\n' $P "$W" $1 "$2" > $T/c.yaml; echo $T/c.yaml; }
ON='      retry: { attempts: 2, on: [connect, reset, 503], budget: 20% }\n      circuit: { failures: 5, eject_for: 1s }\n'
taskset -c 0-3 $J -jar $MJ 18200 $W >/dev/null 2>&1 & UP=$!
taskset -c 0-3 $J $D/Flaky.java 18201 ${FAIL:-20} >/dev/null 2>&1 & FL=$!; sleep 4
DEAD=$(python3 -c 'import socket;s=socket.socket();s.bind(("127.0.0.1",0));print(s.getsockname()[1])')
for up in "healthy 18200 10000" "flaky 18201 5000" "dead $DEAD 10000"; do
  set -- $up
  for mode in master off on; do
    case $mode in master) JAR=$MJ; X='';; off) JAR=$BR; X='';; on) JAR=$BR; X=$ON;; esac
    start $J -jar $JAR --config=$(cfg $2 "$X")
    echo "== $1 $mode: $(w $U) | $(w2 $U $3)"
    stop
  done
done
kill $UP $FL; rm -rf $T
