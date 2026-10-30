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
# A1 fallback (Architecture 2026-10-30 13:12): HttpClient has no local-bind API, so raise
# max_connections_per_ip only in this bench config — never change ServerConfig defaults.
# Preferred multi-loopback bind remains awkward with java.net.http.HttpClient.
GATE_MAX_CONN_PER_IP=${GATE_MAX_CONN_PER_IP:-4096}
GATE_MAX_CONN=${GATE_MAX_CONN:-4096}
GATE_BACKLOG=${GATE_BACKLOG:-2048}
cat > "$WD/gate-server.yaml" <<YML
server:
  max_connections: ${GATE_MAX_CONN}
  max_connections_per_ip: ${GATE_MAX_CONN_PER_IP}
  backlog: ${GATE_BACKLOG}
YML
export GATE_MAX_CONN_PER_IP GATE_MAX_CONN
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
  -Dsjws.swarm.pool.shards=4 -Dsjws.swarm.stream.shards=4 \
  -Dsjws.swarm.dir="$WD/swarm" -Dsjws.swarm.node=bench \
  -Dsjws.swarm.tokens.file="$WD/tokens" \
  -jar "$JAR" --config="$WD/gate-server.yaml" --metrics=localhost "$PORT" "$WD/site" >"$LOG" 2>&1 &
SPID=$!
export SERVER_PID=$SPID
cleanup(){ kill $SPID 2>/dev/null || true; wait $SPID 2>/dev/null || true; }
trap cleanup EXIT
ok=0
for i in $(seq 1 200); do
  curl -sf -o /dev/null "http://127.0.0.1:$PORT/hello.txt" && ok=1 && break
  sleep 0.1
done
[ $ok = 1 ] || { echo "server did not start"; tail -80 "$LOG"; exit 3; }
LOADGEN=${LOADGEN:-python}
HERE_DIR="$(cd "$(dirname "$0")" && pwd)"
if [ "$LOADGEN" = java ]; then
  # B1: drain timeout ≥ GRACE_MS + 15s (StreamHttp.GRACE_MS=60000 → ≥75s). Do not use 40.
  DRAIN_TIMEOUT=${DRAIN_TIMEOUT:-75}
  taskset -c 0-1 nice -n 10 java "$HERE_DIR/StreamLoad.java" \
    --base "http://127.0.0.1:$PORT" --tokens-file "$WD/tokens" \
    --accept "$ACCEPT" --warmup "$WARMUP" --secs "$SECS" --streams "$STREAMS" \
    --out "$OUT" --drain-timeout "$DRAIN_TIMEOUT" --window "${WINDOW:-8}" \
    --server-pid "$SPID" \
    --max-connections-per-ip "${GATE_MAX_CONN_PER_IP:-4096}"
else
  export PYTHONPATH="$(cd "$(dirname "$0")/../.." && pwd)/sdk/python${PYTHONPATH:+:$PYTHONPATH}"
  DRAIN_TIMEOUT=${DRAIN_TIMEOUT:-75}
  taskset -c 0-1 nice -n 10 python3 "$HERE_DIR/drive.py" \
    --base "http://127.0.0.1:$PORT" --tokens-file "$WD/tokens" \
    --accept "$ACCEPT" --warmup "$WARMUP" --secs "$SECS" --streams "$STREAMS" \
    --out "$OUT" --metrics-url "http://127.0.0.1:$PORT/metrics" --drain-timeout "$DRAIN_TIMEOUT" --window "${WINDOW:-8}"
fi
curl -sf "http://127.0.0.1:$PORT/metrics" > "$WD/metrics.txt" || true
# flag-off check: when CREDIT_OFF=1, stream series must be absent
if [ "${CREDIT_OFF:-0}" = 1 ]; then
  if grep -q 'sjws_swarm_stream_' "$WD/metrics.txt" 2>/dev/null; then
    echo "FAIL: credit-off /metrics still has sjws_swarm_stream_*" >&2
    exit 4
  fi
fi
echo "one.sh wrote $OUT"
