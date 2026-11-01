#!/usr/bin/env python3
"""Mirror StreamLoad.sseDataField: strip one optional space after 'data:' (Codex P2 #459)."""
from __future__ import annotations

def sse_data_field(line: str) -> str:
    assert line.startswith("data:")
    v = line[5:]
    if v.startswith(" "):
        v = v[1:]
    return v

def main():
    # StreamHttp emits "data: " + payload
    ts = "01234567890123456789"  # 20 digits
    spaced = sse_data_field("data: " + ts + "rest")
    assert spaced[:20] == ts, spaced[:20]
    assert int(spaced[:20]) == int(ts)
    # No space still works
    bare = sse_data_field("data:" + ts)
    assert bare[:20] == ts
    # Old bug: substring(5) kept the space → first 20 chars were " " + 19 digits
    buggy = ("data: " + ts)[5:][:20]
    assert buggy != ts and buggy.strip()[:19] == ts[:19]
    print("OK test_sse_data_field")

if __name__ == "__main__":
    main()
