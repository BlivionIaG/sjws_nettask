#!/bin/bash
# Routing/proxy benchmarks through the real entry point (--config).
# usage: MASTER_JAR=/path/master.jar WWW=/dir/with/index.html,1k.bin,64k.bin,4m.bin [JAVA=java] [MODE=quick] bench/proxy-routing/run.sh
# Build this branch first (mvn package). Server on CPUs 0-3, wrk/wrk2 on 4-7.
set -u
J=${JAVA:-java}
BR=$(cd "$(dirname "$0")/../.." && pwd)/target/sjws_nettask-0.2-SNAPSHOT.jar
MJ=${MASTER_JAR:?path to the master jar}
W=${WWW:?document root with index.html, 1k.bin, 64k.bin, 4m.bin}
T=$(mktemp -d)
P=18101; U=http://127.0.0.1:$P
start(){ taskset -c 0-3 "$@" >/dev/null 2>&1 & echo $! > $T/pid; sleep 3; }
stop(){ kill $(cat $T/pid); wait $(cat $T/pid) 2>/dev/null; }
w(){ taskset -c 4-7 wrk -t4 -c64 -d3s $1 >/dev/null; for i in 1 2 3; do taskset -c 4-7 wrk -t4 -c64 -d10s --latency $1 | awk '/Requests\/sec/{r=$2} / 99%/{p=$2} END{printf "%s/%s ", r, p}'; done; echo; }
w2(){ taskset -c 4-7 wrk2 -t4 -c64 -d15s -R$2 --latency $1 | awk '/ 50.000%/{a=$2} / 99.000%/{b=$2} / 99.900%/{c=$2} /Requests\/sec/{r=$2} END{printf "R=%s p50=%s p99=%s p99.9=%s\n", r,a,b,c}'; }
cfg(){ printf 'server:\n  port: %s\n  root: "%s"\nhttp3:\n  enabled: false\n%b' "$P" "$W" "$1" > $T/c.yaml; echo $T/c.yaml; }
routes(){ local n=$1 s="routing:\n  enabled: true\n  routes:\n"; for ((i=0;i<n-1;i++)); do s+="    - { host: h$i.test, prefix: /r$i, static: true }\n"; done; s+="    - { prefix: /, static: true }\n"; printf "$s"; }

echo "== master index.html"; start $J -jar $MJ $P $W; w $U/index.html; stop
echo "== branch routing disabled"; start $J -jar $BR --config=$(cfg "routing:\n  enabled: false\n"); w $U/index.html; stop
[ "${MODE:-full}" = quick ] && exit 0
for n in 1 10 50; do echo "== branch routes=$n"; start $J -jar $BR --config=$(cfg "$(routes $n)"); w $U/index.html; stop; done
# upstream: the master static server on CPUs 0-3 beside the proxy (the client keeps 4-7)
taskset -c 0-3 $J -jar $MJ 18200 $W >/dev/null 2>&1 & UP=$!; sleep 3
for f in 1k.bin 64k.bin 4m.bin; do echo "== direct $f"; w http://127.0.0.1:18200/$f; done
for r in "1k.bin 10000" "64k.bin 5000" "4m.bin 200"; do set -- $r; echo "== wrk2 direct $1 R=$2"; w2 http://127.0.0.1:18200/$1 $2; done
PROXY='routing:\n  enabled: true\n  upstreams:\n    up: { url: "http://127.0.0.1:18200" }\n  routes:\n    - { prefix: /, proxy: up }\n'
for side in master branch; do
  [ $side = master ] && JAR=$MJ || JAR=$BR
  start $J -jar $JAR --config=$(cfg "$PROXY")
  for f in 1k.bin 64k.bin 4m.bin; do echo "== $side proxied $f"; w $U/$f; done
  for r in "1k.bin 10000" "64k.bin 5000" "4m.bin 200"; do set -- $r; echo "== wrk2 $side proxied $1 R=$2"; w2 $U/$1 $2; done
  ss -tn state established '( dport = :18200 )' | wc -l | awk -v s=$side '{print s, "upstream conns established:", $1-1}'
  stop
done
kill $UP; rm -rf $T
