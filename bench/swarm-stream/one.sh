#!/bin/bash
# one.sh <jar> <workdir> <accept:sse|binary> <out.json> [secs=20] [streams=1000]
set -euo pipefail
JAR=$1; WD=$2; ACCEPT=$3; OUT=$4; SECS=${5:-20}; STREAMS=${6:-1000}
WARMUP=${WARMUP:-5}
NAGENTS=${NAGENTS:-20}
export JAVA_HOME=${JAVA_HOME:-$HOME/jdk25/jdk-25.0.4.1+1}
export PATH=$JAVA_HOME/bin:${PATH:-}
mkdir -p "$WD/site" "$WD/swarm"
echo hi > "$WD/site/hello.txt"
: > "$WD/tokens"
for i in $(seq 0 $((NAGENTS - 1))); do
  tok=$(openssl rand -hex 24)
  echo "a$i $tok *" >> "$WD/tokens"
done
chmod 600 "$WD/tokens"
PORT=$(python3 -c 'import socket;s=socket.socket();s.bind(("127.0.0.1",0));print(s.getsockname()[1]);s.close()')
LOG=$WD/server.log
CREDIT_FLAGS=(-Dsjws.swarm.stream.credit.enabled=true)
if [ "${CREDIT_OFF:-0}" = 1 ]; then
  CREDIT_FLAGS=(-Dsjws.swarm.stream.credit.enabled=false)
fi
HEAP=${HEAP:--Xms2g -Xmx2g}
# shellcheck disable=SC2086
taskset -c 2-7 nice -n 10 java -XX:+UseZGC $HEAP \
  -Dsjws.swarm.enabled=true -Dsjws.swarm.stream.enabled=true \
  "${CREDIT_FLAGS[@]}" \
  -Dsjws.swarm.pool.shards=4 -Dsjws.swarm.dir="$WD/swarm" -Dsjws.swarm.node=bench \
  -Dsjws.swarm.tokens.file="$WD/tokens" \
  -jar "$JAR" --metrics=localhost "$PORT" "$WD/site" >"$LOG" 2>&1 &
SPID=$!
cleanup(){ kill $SPID 2>/dev/null || true; wait $SPID 2>/dev/null || true; }
trap cleanup EXIT
ok=0
for i in $(seq 1 200); do
  curl -sf -o /dev/null "http://127.0.0.1:$PORT/hello.txt" && ok=1 && break
  sleep 0.1
done
[ $ok = 1 ] || { echo "server did not start"; tail -80 "$LOG"; exit 3; }
export PYTHONPATH="$(cd "$(dirname "$0")/../.." && pwd)/sdk/python${PYTHONPATH:+:$PYTHONPATH}"
taskset -c 0-1 nice -n 10 python3 "$(dirname "$0")/drive.py" \
  --base "http://127.0.0.1:$PORT" --tokens-file "$WD/tokens" \
  --accept "$ACCEPT" --warmup "$WARMUP" --secs "$SECS" --streams "$STREAMS" \
  --out "$OUT" --metrics-url "http://127.0.0.1:$PORT/metrics" --drain-timeout "${DRAIN_TIMEOUT:-75}"
curl -sf "http://127.0.0.1:$PORT/metrics" > "$WD/metrics.txt" || true
# flag-off check: when CREDIT_OFF=1, stream series must be absent
if [ "${CREDIT_OFF:-0}" = 1 ]; then
  if grep -q 'sjws_swarm_stream_' "$WD/metrics.txt" 2>/dev/null; then
    echo "FAIL: credit-off /metrics still has sjws_swarm_stream_*" >&2
    exit 4
  fi
fi
echo "one.sh wrote $OUT"
