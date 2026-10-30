# CPU proof + StreamLoad status (S2c PR 8)

**Head:** `f9215f7`  
**Europe/Paris 2026-10-30**

## SERVER_ISSUE retracted

The `d164b35` / `java1-fixed-result.json` run (p99≈58.5 ms, client≈195% CPU) is **not** a server verdict. Generator was saturated (`self_loaded` / Architecture condition 2). Cause: StreamLoad unbounded enqueue spin — fixed in **`f9215f7`**.

Offline after fix: p99≈393 µs, ~180k samples/20s (`evidence/client-hotspot.md`).

## Official quiet-window runs

Pending flock + load1&lt;4:
1. 1-stream `LOADGEN=java` with both CPUs
2. Cross-check 1 & 16 vs `drive.py`
3. Abs 5–8 windows, etc.
