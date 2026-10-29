#!/bin/bash
# one.sh <jar> <workdir> <mode:sse|binary|aa> <out.json> [secs=20]
# Starts one ZGC server (CPUs 2-7), runs a credit-on stream drain, writes result.json.
set -euo pipefail
JAR=$1; WD=$2; MODE=$3; OUT=$4; SECS=${5:-20}; WARMUP=${WARMUP:-5}
export JAVA_HOME=${JAVA_HOME:-~/jdk25/jdk-25.0.4.1+1}
export PATH=$JAVA_HOME/bin:${PATH:-}
mkdir -p "$WD/site" "$WD/swarm"
echo hi > "$WD/site/hello.txt"
TOK=s2c8-$(openssl rand -hex 24)
echo "bench $TOK *" > "$WD/tokens"; chmod 600 "$WD/tokens"
PORT=$(python3 -c 'import socket;s=socket.socket();s.bind(("127.0.0.1",0));print(s.getsockname()[1]);s.close()')
LOG=$WD/server.log
taskset -c 2-7 nice -n 10 java -XX:+UseZGC -Xms1g -Xmx1g \
  -Dsjws.swarm.enabled=true -Dsjws.swarm.stream.enabled=true \
  -Dsjws.swarm.stream.credit.enabled=true \
  -Dsjws.swarm.pool.shards=4 -Dsjws.swarm.dir="$WD/swarm" -Dsjws.swarm.node=bench \
  -Dsjws.swarm.tokens.file="$WD/tokens" \
  -jar "$JAR" "$PORT" "$WD/site" >"$LOG" 2>&1 &
SPID=$!
cleanup(){ kill $SPID 2>/dev/null || true; wait $SPID 2>/dev/null || true; }
trap cleanup EXIT
ok=0
for i in $(seq 1 100); do
  curl -sf -o /dev/null "http://127.0.0.1:$PORT/hello.txt" && ok=1 && break
  sleep 0.1
done
[ $ok = 1 ] || { echo "server did not start"; tail -40 "$LOG"; exit 3; }
# Placeholder until questions.md is answered: setup + measure live in drive.py
python3 "$(dirname "$0")/drive.py" --base "http://127.0.0.1:$PORT" --token "$TOK" \
  --mode "$MODE" --warmup "$WARMUP" --secs "$SECS" --out "$OUT" --stats-url \
  "http://127.0.0.1:$PORT/swarm/v1/stream-stats"
echo "one.sh wrote $OUT"
