#!/bin/bash
# one.sh <jar> <workdir> <accept:sse|binary> <out.json> [secs=20] [streams=1000]
set -euo pipefail
JAR=$1; WD=$2; ACCEPT=$3; OUT=$4; SECS=${5:-20}; STREAMS=${6:-1000}
GATE_GROUPS=${GATE_GROUPS:-$STREAMS}  # Architecture 2026-10-30 15:13; gate default 64
GEN_CPUS=${GEN_CPUS:-0-1}             # inceptor 2026-10-30 18:46 (A)=0-1; (B)=0-2
GEN_CORES=${GEN_CORES:-2}             # width of GEN_CPUS; sat limit = 80% × GEN_CORES
# Default 3-7: mode B (gen 0-2) must not share a core with the server. Mode A (0-1) is
# also disjoint. The previous default 2-7 overlapped core 2 with (B).
SRV_CPUS=${SRV_CPUS:-3-7}
GEN_PROCS=${GEN_PROCS:-1}             # (B): n StreamLoad processes on GEN_CPUS, then merge-gen.py
HEAP_FLAGS=${HEAP:--Xms2g -Xmx2g}
HEAP=$HEAP_FLAGS
export HEAP
WARMUP=${WARMUP:-5}
NAGENTS=${NAGENTS:-20}
export JAVA_HOME=${JAVA_HOME:-$HOME/jdk25/jdk-25.0.4.1+1}
export PATH=$JAVA_HOME/bin:${PATH:-}
# Expand a taskset list (0-2,4) to one cpu per line.
_expand_cpus() {
  local part a b i
  local IFS=','
  for part in $1; do
    if [[ "$part" == *-* ]]; then
      a=${part%-*}; b=${part#*-}
      for ((i=a; i<=b; i++)); do printf '%s\n' "$i"; done
    else
      printf '%s\n' "$part"
    fi
  done
}
_cpu_overlap() {
  local x y
  for x in $(_expand_cpus "$1"); do
    for y in $(_expand_cpus "$2"); do
      [ "$x" = "$y" ] && return 0
    done
  done
  return 1
}
# Live Cpus_allowed_list for a pid (taskset -cp output after the colon).
_live_cpus() {
  taskset -cp "$1" 2>/dev/null | awk -F: '{gsub(/^ +/, "", $2); print $2}'
  # empty if the pid is gone
}
# If the live server affinity overlaps any live generator, re-pin the server to SRV_CPUS (3-7).
_repin_if_overlap() {
  local gcpu scpu p overlapped=0
  scpu=$(_live_cpus "$SPID")
  [ -n "$scpu" ] || return 0
  for p in "${GENPIDS[@]:-}"; do
    gcpu=$(_live_cpus "$p")
    [ -n "$gcpu" ] || continue
    if _cpu_overlap "$gcpu" "$scpu"; then
      echo "one.sh: LIVE OVERLAP server pid=$SPID ($scpu) gen pid=$p ($gcpu); re-pinning server to $SRV_CPUS" | tee -a "$WD/affinity.txt"
      overlapped=1
    fi
  done
  if [ "$overlapped" = 1 ]; then
    taskset -cp "$SRV_CPUS" "$SPID" >/dev/null
    echo "one.sh: server re-pinned requested=$SRV_CPUS live=$(_live_cpus "$SPID")" | tee -a "$WD/affinity.txt"
    scpu=$(_live_cpus "$SPID")
    for p in "${GENPIDS[@]:-}"; do
      gcpu=$(_live_cpus "$p")
      [ -n "$gcpu" ] || continue
      if _cpu_overlap "$gcpu" "$scpu"; then
        echo "one.sh: WARNING still overlapping after re-pin server=$scpu gen=$gcpu pid=$p" | tee -a "$WD/affinity.txt"
      fi
    done
  else
    echo "one.sh: affinity ok server=$scpu gens=${GENPIDS[*]:-} (no overlap with 0-2)" | tee -a "$WD/affinity.txt"
  fi
}
if _cpu_overlap "$GEN_CPUS" "$SRV_CPUS"; then
  echo "one.sh: GEN_CPUS=$GEN_CPUS overlaps SRV_CPUS=$SRV_CPUS; moving the server off the generator cores" >&2
  _new=()
  for i in 0 1 2 3 4 5 6 7; do
    _skip=0
    for x in $(_expand_cpus "$GEN_CPUS"); do
      [ "$i" = "$x" ] && _skip=1 && break
    done
    [ "$_skip" = 0 ] && _new+=("$i")
  done
  if [ ${#_new[@]} -eq 0 ]; then
    echo "one.sh: no remaining cores for the server after GEN_CPUS=$GEN_CPUS" >&2
    exit 2
  fi
  IFS=,
  SRV_CPUS="${_new[*]}"
  unset IFS
  echo "one.sh: SRV_CPUS now $SRV_CPUS" >&2
fi
if [ "${GEN_PROCS:-1}" -gt 1 ]; then
  mapfile -t _gc < <(_expand_cpus "$GEN_CPUS")
  if [ ${#_gc[@]} -lt 1 ]; then
    echo "one.sh: GEN_CPUS=$GEN_CPUS is empty" >&2
    exit 2
  fi
  GEN_CORES=${#_gc[@]}
fi

# Thin jar: maven-jar-plugin writes Class-Path: lib/... relative to the jar,
# and maven-dependency-plugin copies runtime deps to target/lib at package.
# Launching a copied jar without that sibling lib/ fails in ConfigLoader
# (NoClassDefFoundError: org/yaml/snakeyaml/... after master c9d8ae16).
if [ ! -f "$JAR" ]; then
  echo "one.sh: jar not found: $JAR" >&2
  exit 3
fi
JAR="$(cd "$(dirname "$JAR")" && pwd)/$(basename "$JAR")"
LIB_DIR="${SJWS_LIB_DIR:-$(dirname "$JAR")/lib}"
if [ ! -d "$LIB_DIR" ] || ! compgen -G "$LIB_DIR/*.jar" >/dev/null; then
  echo "one.sh: runtime classpath missing: expected dependency jars in $LIB_DIR" >&2
  echo "one.sh: mvn package writes them via maven-dependency-plugin (copy-dependencies). Copy target/lib next to the jar, or set SJWS_LIB_DIR." >&2
  exit 3
fi
if [ "$LIB_DIR" != "$(dirname "$JAR")/lib" ]; then
  # Manifest Class-Path is relative to the jar. Symlink so `java -jar` keeps
  # manifest Add-Opens / Enable-Native-Access.
  ln -sfn "$LIB_DIR" "$(dirname "$JAR")/lib"
fi
# Fresh work dir: never reuse leftover result.json / gen-*.json / prior swarm segments
# (smoke3 judged leftover smoke2 results after ENOSPC left empty gen files).
mkdir -p "$WD"
rm -rf "$WD/swarm"
rm -f "$OUT" "$WD"/gen-*.json "$WD/disk-abort"
mkdir -p "$WD/site" "$WD/swarm"
echo hi > "$WD/site/hello.txt"

# Free-space guard (mandatory #502-smoke4): refuse to start a leg below 20G free on /workspace;
# a watcher aborts the leg if free space drops below 10G.
_workspace_avail() { df -B1 --output=avail /workspace | awk 'NR==2 { print $1 }'; }
_write_disk_result() {
  local reason=$1
  HARNESS_COMMIT=${HARNESS_COMMIT:-} python3 -c '
import json, os, sys
from pathlib import Path
Path(sys.argv[1]).write_text(json.dumps({
    "error": sys.argv[2],
    "harness_commit": os.environ.get("HARNESS_COMMIT", ""),
    "mib_s": 0,
    "validity_marker": "DISK",
}, indent=2) + "\n")
' "$OUT" "$reason"
}
NEED_START=$((20 * 1024 * 1024 * 1024))
NEED_ABORT=$((10 * 1024 * 1024 * 1024))
_av=$(_workspace_avail)
if [ -z "$_av" ] || [ "$_av" -lt "$NEED_START" ]; then
  echo "one.sh: REFUSE: /workspace has ${_av:-unknown} bytes free (need 20G to start a leg)" >&2
  _write_disk_result "REFUSE_DISK_LT_20G"
  exit 5
fi
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
# #414: optional extra server JVM flags (profile-414.sh passes JFR here). Empty for gate windows.
read -r -a SERVER_JAVA_ARGS <<< "${SERVER_JAVA_OPTS:-}"
# Pre-leg gate (inceptor 2026-11-14 01:06): load1 < 3 measured BEFORE the server or generator
# starts. Wait up to PRE_LEG_WAIT_S (default 300 s) for it; the value is written to result.json
# as load1_pre (window_valid rejects load1_pre >= 3). StreamLoad's own start-time sample is kept
# as load1_at_gen_start (information only).
PRE_LEG_WAIT_S=${PRE_LEG_WAIT_S:-300}
_pre_t0=$(date +%s)
LOAD1_PRE_LEG=$(awk '{print $1}' /proc/loadavg)
while ! awk -v l="$LOAD1_PRE_LEG" 'BEGIN{exit !(l+0<3)}' && [ $(( $(date +%s) - _pre_t0 )) -lt "$PRE_LEG_WAIT_S" ]; do
  sleep 5
  LOAD1_PRE_LEG=$(awk '{print $1}' /proc/loadavg)
done
export LOAD1_PRE_LEG
echo "one.sh: pre-leg load1=$LOAD1_PRE_LEG (gate <3, waited $(( $(date +%s) - _pre_t0 ))s)"
# shellcheck disable=SC2086
taskset -c "$SRV_CPUS" nice -n 10 java -XX:+UseZGC $HEAP "${SERVER_JAVA_ARGS[@]}" \
  -Dsjws.swarm.enabled=true -Dsjws.swarm.stream.enabled=true \
  "${CREDIT_FLAGS[@]}" \
  -Dsjws.swarm.pool.shards=4 -Dsjws.swarm.stream.shards=4 \
  -Dsjws.swarm.dir="$WD/swarm" -Dsjws.swarm.node=bench \
  -Dsjws.swarm.tokens.file="$WD/tokens" \
  -Dsjws.messaging.segment.bytes="${SJWS_MESSAGING_SEGMENT_BYTES:-16777216}" \
  -jar "$JAR" --config="$WD/gate-server.yaml" --metrics=localhost "$PORT" "$WD/site" >"$LOG" 2>&1 &
SPID=$!
export SERVER_PID=$SPID
# Own PIDs for foreign-CPU sampler (07:05 addendum): server + this one.sh shell.
{ echo "$$"; echo "$SPID"; } > "$WD/bench.pids"
{
  echo "one.sh: server pid=$SPID requested_pin=$SRV_CPUS live=$(_live_cpus "$SPID")"
} | tee -a "$WD/affinity.txt"
GENPIDS=()
_SPACE_WATCH_PID=
cleanup(){
  if [ -n "${_SPACE_WATCH_PID:-}" ]; then
    kill "$_SPACE_WATCH_PID" 2>/dev/null || true
    wait "$_SPACE_WATCH_PID" 2>/dev/null || true
  fi
  local p
  for p in "${GENPIDS[@]:-}"; do kill "$p" 2>/dev/null || true; done
  kill ${SPID:-} 2>/dev/null || true
  wait ${SPID:-} 2>/dev/null || true
  # Mandatory: delete this leg's swarm dir as soon as the leg ends. Keep metrics.txt and result JSON.
  rm -rf "$WD/swarm"
}
trap cleanup EXIT
# Abort the leg if free space on /workspace drops below 10G while it is running.
_space_watch() {
  while true; do
    sleep 2
    local av
    av=$(_workspace_avail)
    if [ -n "$av" ] && [ "$av" -lt "$NEED_ABORT" ]; then
      echo "one.sh: ABORT: /workspace has ${av} bytes free (min 10G during a leg)" >&2
      date -Iseconds > "$WD/disk-abort"
      local p
      for p in "${GENPIDS[@]:-}"; do kill "$p" 2>/dev/null || true; done
      if [ -f "$WD/bench.pids" ]; then
        while read -r p; do
          [ "$p" = "$$" ] && continue
          kill "$p" 2>/dev/null || true
        done < "$WD/bench.pids"
      fi
      return 0
    fi
  done
}
_space_watch &
_SPACE_WATCH_PID=$!
ok=0
for i in $(seq 1 200); do
  curl -sf -o /dev/null "http://127.0.0.1:$PORT/hello.txt" && ok=1 && break
  sleep 0.1
done
[ $ok = 1 ] || { echo "server did not start"; tail -80 "$LOG"; exit 3; }
LOADGEN=${LOADGEN:-python}
HERE_DIR="$(cd "$(dirname "$0")" && pwd)"
if [ "$LOADGEN" = java ]; then
  # #415 re-review item 2: StreamLoad stamps the harness commit into result.json (gate.sh exports it).
  if [ -z "${HARNESS_COMMIT:-}" ]; then
    HARNESS_COMMIT=$(git -C "$HERE_DIR" rev-parse --short=12 HEAD 2>/dev/null || echo unknown)
    if [ -n "$(git -C "$HERE_DIR" status --porcelain -- . 2>/dev/null)" ]; then HARNESS_COMMIT="$HARNESS_COMMIT-dirty"; fi
  fi
  export HARNESS_COMMIT
  if [ "${LEAN_HTTP:-1}" = 0 ]; then LEAN_HTTP_FLAG=--no-lean-http; else LEAN_HTTP_FLAG=--lean-http; fi
  # B1: drain timeout ≥ GRACE_MS + 15s (StreamHttp.GRACE_MS=60000 → ≥75s). Do not use 40.
  DRAIN_TIMEOUT=${DRAIN_TIMEOUT:-75}
  # #415: RATE_MIB_S>0 paces producers to an aggregate offered rate (0/unset = closed loop).
  # LEAN_CONSUMER=0 keeps the HttpClient consumer (A/B the generator change itself).
  GEN_EXTRA=()
  if [ -n "${PACE_MS:-}" ]; then
    echo "one.sh: PACE_MS is removed; use RATE_MIB_S (aggregate MiB/s, --rate-mib-s) instead" >&2
    exit 2
  fi
  if [ -n "${RATE_MIB_S:-}" ]; then GEN_EXTRA+=(--rate-mib-s "$RATE_MIB_S"); fi
  if [ "${LEAN_CONSUMER:-1}" = 0 ]; then GEN_EXTRA+=(--no-lean-consumer); fi
  if [ -n "${CLAIM_DELAY_MS:-}" ]; then GEN_EXTRA+=(--claim-delay-ms "$CLAIM_DELAY_MS"); fi
  if [ -n "${HANG_EVERY:-}" ]; then GEN_EXTRA+=(--hang-every "$HANG_EVERY"); fi
  if [ -n "${PIPELINE_DEPTH:-}" ]; then GEN_EXTRA+=(--pipeline-depth "$PIPELINE_DEPTH"); fi
  # #502: PACER_LOOP=1 = one pacing loop per generator process (opt-in).
  if [ "${PACER_LOOP:-0}" = 1 ]; then GEN_EXTRA+=(--pacer-loop); fi
  if [ -n "${PACER_SPIN_US:-}" ]; then GEN_EXTRA+=(--pacer-spin-us "$PACER_SPIN_US"); fi
  if [ -n "${PACER_DEPTH:-}" ]; then GEN_EXTRA+=(--pacer-depth "$PACER_DEPTH"); fi
  # #415/#414: optional generator JVM flags (e.g. JFR on the generator to find its own hot spots).
  read -r -a GEN_JAVA_ARGS <<< "${GEN_JAVA_OPTS:-}"
  # Launch one StreamLoad: _launch_streamload <pin> <streams> <groups> <group_offset> <rate_or_empty> <out>
  _launch_streamload() {
    local pin=$1 sp=$2 gp=$3 goff=$4 rate=$5 outp=$6
    local extra=("${GEN_EXTRA[@]}")
    # Replace a pre-set --rate-mib-s with this process's share, if any.
    local i
    for i in "${!extra[@]}"; do
      if [ "${extra[$i]}" = "--rate-mib-s" ]; then
        if [ -n "$rate" ]; then extra[$((i+1))]="$rate"; else unset "extra[$i]" "extra[$((i+1))]"; fi
      fi
    done
    if [ -n "$rate" ] && [[ ! " ${extra[*]} " =~ " --rate-mib-s " ]]; then
      extra+=(--rate-mib-s "$rate")
    fi
    # Per-process --gen-cores 1 (its pin is one core); merge-gen.py sets the combined width.
    local gcores=1
    [ "${GEN_PROCS:-1}" -le 1 ] && gcores="$GEN_CORES"
    taskset -c "$pin" nice -n 10 java "${GEN_JAVA_ARGS[@]}" "$HERE_DIR/StreamLoad.java" \
      --base "http://127.0.0.1:$PORT" --tokens-file "$WD/tokens" \
      --accept "$ACCEPT" --warmup "$WARMUP" --secs "$SECS" --streams "$sp" \
      --groups "$gp" --group-offset "$goff" \
      --gen-cores "$gcores" \
      ${LEAN_HTTP_FLAG:-} \
      --out "$outp" --drain-timeout "$DRAIN_TIMEOUT" --window "${WINDOW:-8}" \
      --server-pid "$SPID" \
      --max-connections-per-ip "${GATE_MAX_CONN_PER_IP:-4096}" \
      "${extra[@]}" &
    _LAUNCHED_PID=$!
  }
  GENPIDS=()
  if [ "${GEN_PROCS:-1}" -le 1 ]; then
    _launch_streamload "$GEN_CPUS" "$STREAMS" "$GATE_GROUPS" 0 "${RATE_MIB_S:-}" "$OUT"
    GENPID=$_LAUNCHED_PID
    GENPIDS=("$GENPID")
    echo "$GENPID" >> "$WD/bench.pids"
    {
      echo "one.sh: gen-0 pid=$GENPID requested_pin=$GEN_CPUS live=$(_live_cpus "$GENPID")"
    } | tee -a "$WD/affinity.txt"
    _repin_if_overlap
    wait "$GENPID"
  else
    mapfile -t _gc < <(_expand_cpus "$GEN_CPUS")
    leftover_s=$STREAMS leftover_g=$GATE_GROUPS goff=0
    rc=0
    PARTS=()
    for ((p=0; p<GEN_PROCS; p++)); do
      np=$((GEN_PROCS - p))
      sp=$(( (leftover_s + np - 1) / np ))
      leftover_s=$((leftover_s - sp))
      gp=$(( (leftover_g + np - 1) / np ))
      leftover_g=$((leftover_g - gp))
      pin=${_gc[$((p % ${#_gc[@]}))]}
      outp="$WD/gen-$p.json"
      rate=""
      if [ -n "${RATE_MIB_S:-}" ]; then
        rate=$(python3 -c "print($RATE_MIB_S * $sp / $STREAMS)")
      fi
      _launch_streamload "$pin" "$sp" "$gp" "$goff" "$rate" "$outp"
      GENPID=$_LAUNCHED_PID
      GENPIDS+=("$GENPID")
      echo "$GENPID" >> "$WD/bench.pids"
      PARTS+=("$outp")
      goff=$((goff + gp))
      {
        echo "one.sh: gen-$p pid=$GENPID requested_pin=$pin live=$(_live_cpus "$GENPID")"
      } | tee -a "$WD/affinity.txt"
    done
    _repin_if_overlap
    for GENPID in "${GENPIDS[@]}"; do
      wait "$GENPID" || rc=$?
    done
    python3 "$HERE_DIR/merge-gen.py" "${PARTS[@]}" -o "$OUT" --gen-cores "$GEN_CORES" --secs "$SECS"
    [ "$rc" -eq 0 ] || echo "one.sh: a generator process exited $rc (merged $OUT anyway)" >&2
  fi
else
  export PYTHONPATH="$(cd "$(dirname "$0")/../.." && pwd)/sdk/python${PYTHONPATH:+:$PYTHONPATH}"
  DRAIN_TIMEOUT=${DRAIN_TIMEOUT:-75}
  taskset -c "$GEN_CPUS" nice -n 10 python3 "$HERE_DIR/drive.py" \
    --base "http://127.0.0.1:$PORT" --tokens-file "$WD/tokens" \
    --accept "$ACCEPT" --warmup "$WARMUP" --secs "$SECS" --streams "$STREAMS" \
    --groups "$GATE_GROUPS" \
    --gen-cores "$GEN_CORES" \
    --out "$OUT" --metrics-url "http://127.0.0.1:$PORT/metrics" --drain-timeout "$DRAIN_TIMEOUT" --window "${WINDOW:-8}" \
    ${SPID:+--server-pid "$SPID"} &
  GENPID=$!
  echo "$GENPID" >> "$WD/bench.pids"
  wait "$GENPID"
fi

# Architecture 2026-10-30 15:13: writer_count + heap_flags into result.json
if [ -f "$WD/disk-abort" ]; then
  echo "one.sh: leg aborted on free-space guard (<10G)" >&2
  _write_disk_result "ABORT_DISK_LT_10G"
  exit 6
fi
# Refuse to publish a leftover or a failed merge: empty gen-*.json must not leave $OUT in place.
for _g in "$WD"/gen-*.json; do
  [ -e "$_g" ] || continue
  if [ ! -s "$_g" ]; then
    echo "one.sh: empty $_g — refusing to keep a result" >&2
    rm -f "$OUT"
    exit 7
  fi
done
if [ -f "$OUT" ]; then
  python3 "$HERE_DIR/amend-result.py" "$OUT" "$WD/swarm" "$HEAP_FLAGS" "$GATE_GROUPS" || true
fi
curl -sf "http://127.0.0.1:$PORT/metrics" > "$WD/metrics.txt" || true
# flag-off check: when CREDIT_OFF=1, stream series must be absent
if [ "${CREDIT_OFF:-0}" = 1 ]; then
  if grep -q 'sjws_swarm_stream_' "$WD/metrics.txt" 2>/dev/null; then
    echo "FAIL: credit-off /metrics still has sjws_swarm_stream_*" >&2
    exit 4
  fi
fi
if [ ! -s "$OUT" ]; then
  echo "one.sh: no result written at $OUT" >&2
  exit 7
fi
echo "one.sh wrote $OUT"
