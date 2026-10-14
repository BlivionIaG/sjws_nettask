#!/usr/bin/env bash
# Top allocation sites on the swarm request/reply path (JFR ObjectAllocationSample, default G1).
# Usage: flock /tmp/sjws-bench.lock bench/gc/alloc-profile.sh [shards]   -> prints top frames by sampled weight
set -euo pipefail
cd "$(dirname "$0")/../.."
JAVA_HOME=${JAVA_HOME:-$HOME/jdk25}
sh=${1:-1}; d=$(mktemp -d); jfr=${JFR:-/tmp/rr-alloc.jfr}
taskset -c 0-5 "$JAVA_HOME/bin/java" -XX:+UseG1GC -Dsjws.swarm.pool.shards=$sh \
  -XX:StartFlightRecording=filename=$jfr,settings=profile \
  -cp target/classes bench/SwarmBench.java "$d" reqreply 10000 12 | grep '^reqreply'
rm -rf "$d"
"$JAVA_HOME/bin/jfr" print --json --events jdk.ObjectAllocationSample --stack-depth 8 "$jfr" > "$jfr.json"
python3 - "$jfr.json" <<'PY'
import json, sys, collections
ev = json.load(open(sys.argv[1]))["recording"]["events"]
by_site, by_type, tot = collections.Counter(), collections.Counter(), 0
skip = ("java.", "jdk.", "sun.", "java/", "jdk/", "sun/")
for e in ev:
    v = e["values"]; w = v["weight"]; tot += w
    by_type[v["objectClass"]["name"]] += w
    frames = (v.get("stackTrace") or {}).get("frames") or []
    site = next((f for f in frames if not f["method"]["type"]["name"].startswith(skip)), frames[0] if frames else None)
    if site: by_site[f'{site["method"]["type"]["name"]}.{site["method"]["name"]}:{site["lineNumber"]}'] += w
print(f"total sampled weight {tot/2**20:.0f} MiB")
for name, c in (("site", by_site), ("type", by_type)):
    print(f"-- top by {name}")
    for k, w in c.most_common(15): print(f"{100*w/tot:5.1f}%  {w/2**20:8.1f} MiB  {k}")
PY
