#!/bin/bash
J=${JAVA:-java}
# usage: MASTER_JAR=... JAVA=... bench/proxy-routing/run.sh  (after mvn package; javac BenchMain.java -cp target/classes:target/lib/* -d target/bench)
B=$(cd "$(dirname "$0")/../.." && pwd)/target
CP="$B/classes:$(ls -d $B/lib/* | tr '\n' ':'):$B/bench"
MJ=${MASTER_JAR:?path to the master jar}
W=${WWW:?document root with index.html, 1k.bin, 64k.bin}
start(){ taskset -c 0-3 "$@" >/dev/null 2>&1 & echo $! > /tmp/srv.pid; sleep 3; }
stop(){ kill $(cat /tmp/srv.pid); wait $(cat /tmp/srv.pid) 2>/dev/null; }
w(){ taskset -c 4-7 wrk -t4 -c64 -d3s $1 >/dev/null; for i in 1 2 3; do taskset -c 4-7 wrk -t4 -c64 -d10s --latency $1 | awk '/Requests\/sec/{r=$2} / 99%/{p=$2} END{printf "%s/%s ", r, p}'; done; echo; }
w2(){ taskset -c 4-7 wrk2 -t4 -c64 -d15s -R$2 --latency $1 | awk '/ 50.000%/{a=$2} / 99.000%/{b=$2} / 99.900%/{c=$2} /Requests\/sec/{r=$2} END{printf "R=%s p50=%s p99=%s p99.9=%s\n", r,a,b,c}'; }
P=18101; U=http://127.0.0.1:$P
echo "== master index.html"; start $J -jar $MJ $P $W; w $U/index.html; stop
echo "== branch disabled"; start $J -cp "$CP" BenchMain $P $W off; w $U/index.html; stop
for n in 1 10 50; do echo "== branch routes=$n"; start $J -cp "$CP" BenchMain $P $W routes $n; w $U/index.html; stop; done
# upstream on 18200 (master jar), not pinned with server to avoid overlap: cpus 0-3 too
taskset -c 0-3 $J -jar $MJ 18200 $W >/dev/null 2>&1 & UP=$!; sleep 3
for f in 1k.bin 64k.bin; do echo "== direct $f"; w http://127.0.0.1:18200/$f; done
echo "== wrk2 direct 1k R=10000"; w2 http://127.0.0.1:18200/1k.bin 10000
start $J -cp "$CP" BenchMain $P $W proxy http://127.0.0.1:18200
for f in 1k.bin 64k.bin; do echo "== proxied $f"; w $U/$f; done
echo "== wrk2 proxied 1k R=10000"; w2 $U/1k.bin 10000
ss -tn state established '( dport = :18200 )' | wc -l | awk '{print "upstream conns established:", $1-1}'
stop; kill $UP
