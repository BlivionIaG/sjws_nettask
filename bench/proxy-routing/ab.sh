#!/bin/bash
# usage: MASTER_JAR=/path/master.jar WWW=/dir/with/1k.bin,64k.bin,4m.bin [JAVA=java] [D=5] bench/proxy-routing/ab.sh (build this branch first)
# interleaved A/B: direct, master proxied, branch proxied; same window
J=${JAVA:-java}; W=${WWW:?document root}; T=$(mktemp -d); printf 'server:\n  port: %s\n  root: "%s"\nhttp3:\n  enabled: false\nrouting:\n  enabled: true\n  upstreams:\n    up: { url: "http://127.0.0.1:18200" }\n  routes:\n    - { prefix: /, proxy: up }\n' 18101 $W > $T/a.yaml; sed 's/18101/18102/' $T/a.yaml > $T/b.yaml
MJ=${MASTER_JAR:?master jar}; BJ=$(cd "$(dirname "$0")/../.." && pwd)/target/sjws_nettask-0.2-SNAPSHOT.jar
taskset -c 0-3 $J -jar $MJ 18200 $W >/dev/null 2>&1 & UP=$!
taskset -c 0-3 $J -jar $MJ --config=$T/a.yaml >/dev/null 2>&1 & A=$!
taskset -c 0-3 $J -jar $BJ --config=$T/b.yaml >/dev/null 2>&1 & B=$!; sleep 4
r(){ taskset -c 4-7 wrk -t4 -c64 -d${D:-5}s $1 | awk '/Requests\/sec/{printf "%8.0f", $2}'; }
for f in 1k.bin 64k.bin 4m.bin; do r http://127.0.0.1:18101/$f >/dev/null; r http://127.0.0.1:18102/$f > /dev/null; for k in 1 2; do echo "$f direct $(r http://127.0.0.1:18200/$f) master $(r http://127.0.0.1:18101/$f) branch $(r http://127.0.0.1:18102/$f)"; done; done
kill $A $B $UP
