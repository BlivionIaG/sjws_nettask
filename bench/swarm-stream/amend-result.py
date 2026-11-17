#!/usr/bin/env python3
"""Post-process one.sh result.json: writer_count, heap_flags, groups."""
import json, os, sys
out, swarm, heap, groups = sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4])
r = json.load(open(out))
writers = 0
if os.path.isdir(swarm):
    writers = sum(1 for n in os.listdir(swarm) if not n.startswith("."))
r["writer_count"] = writers
r["heap_flags"] = heap
r["groups"] = r.get("groups") or groups
# Pre-leg gate value measured by one.sh before the server/generator started (01:06 rule).
pre = os.environ.get("LOAD1_PRE_LEG")
if pre:
    try:
        if "load1_at_gen_start" not in r:
            r["load1_at_gen_start"] = r.get("load1_pre")
        r["load1_pre"] = float(pre)
        r["load1_pre_source"] = "one.sh before server start"
    except ValueError:
        pass
with open(out, "w") as f:
    json.dump(r, f, indent=2)
    f.write("\n")
print(f"amended writer_count={writers} groups={r.get('groups')} heap={heap}")
