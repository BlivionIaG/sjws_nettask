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
with open(out, "w") as f:
    json.dump(r, f, indent=2)
    f.write("\n")
print(f"amended writer_count={writers} groups={r.get('groups')} heap={heap}")
