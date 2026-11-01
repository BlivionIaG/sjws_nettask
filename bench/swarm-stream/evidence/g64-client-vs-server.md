# G=64 client vs server p99 (report-only)

Scratch instrumentation only (`scratch/g64-client-vs-server` @ `d5c194c` + StreamLoad latency split). **Not** pushed as product code. Server jar = #299 tip. Pinning unchanged (loadgen 0–1, server 2–7). Europe/Paris 2026-10-30.

**Corrects / qualifies** the Session.at CPU note (`g64-cpu-attribution.md`): every prior 1k-stream G=64 window was **`self_loaded`**, so those p99s are not Architecture-valid passes.

---

## 1. `self_loaded` on every prior 64-group window

| Window | Tree / note | client CPU % wall | `generator_saturated` | `self_loaded` | agg/gate valid? |
|--|--|--|--|--|--|
| Counted abs-01 | includes `da329fd`; `result.json` now | **197%** | **true** | **true** | **INVALID** (`self_loaded`; also stopped `guard_p99_gt_1_5ms` in `run-one-abs`) |
| Master JFR attrib | `1603d96` / evidence `a9623d5` | **195%** | **true** | **true** | INVALID (report-only; also drain/open) |
| #342 JFR | `22c9bda` | **186%** | **true** | **true** | INVALID (report-only) |
| sweep-g64 | report-only | **197%** | **true** | **true** | INVALID under agg rules |
| This CPU-attr window | same as attrib | **195%** | **true** | **true** | same |

Threshold (Architecture 2026-10-30): `client_cpu_pct_of_wall >= 180` ⇒ `generator_saturated` ⇒ `self_loaded` (`StreamLoad.java` ~153–156).

### Did agg.py flag it?

**Current `agg.py` / `gate.sh`: yes** — `not self_loaded` is required for `valid` (`agg.py:34–61`, `gate.sh` one-liner).

**Harness bug (historical):** before commit `0cce0c0` (*bench(swarm): Architecture 2026-10-30 StreamLoad conditions*, 08:17), `gate.sh` validity was only `n≥10000 && p99 && load1_pre<4`. `gate.log` still shows early abs windows printed **`VALID` with `"self_loaded": true`** (e.g. old abs-01 mib≈10.9). That was a real gate bug; **fixed in `0cce0c0`**. Current counted abs-01 path uses the fixed check (and additionally STOP'd on p99 guard).

**#299 numbers that need a `self_loaded` caveat:** all 1k-stream / G=64 e2e p99 citations (29–35 ms JFR, 169 ms abs-01, Session.at attribution window). Treat as **diagnostic under a saturated loadgen**, not §13 passes.

---

## 2. Latency split (scratch StreamLoad)

No prior server-stage timestamps in harness. Scratch records:

| Metric | Definition |
|--|--|
| **e2e** | producer `emit` nanoTime in payload → consumer recv (existing) |
| **post_rtt** | `emit` → successful `:chunk` POST returns (≈ client HTTP + **server admit/produce/deliverLocal** + response) |
| **consumer_lag** | POST done → recv, only when POST completed before recv (consumer behind). If recv wins the race, sample omitted |

### 1k streams / 1024 B / G=64 (saturated, matches prior shape)

| | p50 | p99 |
|--|--|--|
| e2e | 11.4 ms | **49.6 ms** |
| **post_rtt** | 11.1 ms | **48.3 ms** |
| consumer_lag | 0.65 ms | **10.0 ms** |

**≈97% of e2e p99 is post_rtt**, not consumer read/parse lag. Session.at runs on the **GET/`Reader.next` path**; it is **not** the stopwatch interval of e2e. It can still inflate post_rtt via **CPU steal** and **per-session lock** (`drain`/`at` share `Session.lock`).

---

## 3. Lower client load (G=64 requested; groups cap to `min(64, streams)`)

| streams | chunk B | client % | sat / self_loaded | MiB/s | e2e p99 | post_rtt p99 | consumer_lag p99 | server % |
|--|--|--|--|--|--|--|--|--|
| 1000 | 1024 | 193 | **yes / yes** | 35.3 | 49.6 ms | 48.3 ms | 10.0 ms | 259 |
| 500 | 1024 | 190 | yes / yes | 31.9 | 24.2 ms | 25.1 ms | 5.9 ms | 262 |
| 250 | 1024 | 193 | yes / yes | 34.2 | 9.5 ms | 11.4 ms | 2.8 ms | 282 |
| 100 | 1024 | 190 | yes / yes | 30.6 | 5.1 ms | 6.4 ms | 1.9 ms | 277 |
| 1000 | 256 | 196 | yes / yes | 8.8 | 44.8 ms | 45.7 ms | 8.3 ms | 254 |
| **50** | 1024 | **179.3** | **no / no** | 28.2 | 5.6 ms | 5.9 ms | 4.6 ms | 271 |
| 25 | 1024 | 182.5 | yes / yes (edge) | 30.9 | 3.7 ms | 4.0 ms | 2.3 ms | 293 |
| **10** | 1024 | **171.7** | **no / no** | 25.9 | 3.6 ms | 2.3 ms | 3.0 ms | 292 |

**Where loadgen stops saturating:** around **≤50 streams** on this 2-core pin (180% threshold). Smaller chunks at 1k streams **do not** unsaturate (still ~196%).

p99 falls hard as stream concurrency drops; MiB/s stays the same order (~26–35) until chunk size shrinks. Server pin stays ~**2.5–2.9 core-eq** even at 10 streams — not idle.

---

## 4. Verdict

**Mixed, with e2e stopwatch dominated by post_rtt (chunk POST path), under a saturated loadgen at 1k streams.**

1. **All prior 1k G=64 p99s on #299 are `self_loaded`** — do not treat as valid §13; caveat them.  
2. **e2e p99 ≈ post_rtt p99** at 1k → waiting on **chunk POST completion** (server admit/produce/deliverLocal + HTTP), **not** primarily consumer parse.  
3. **`Session.at` remains the top server *CPU self-time* leaf (~10%)** and shares `Session.lock` with `drain` on the produce path — plausible **interferer**, but saying “p99 *is* Session.at” over-claims the stopwatch split.  
4. **Consumer_lag** is smaller at 1k (p99 ~10 ms vs post ~48 ms); at very low stream counts it can meet or beat post_rtt.  
5. **Unsaturation** needs **far fewer streams** (~50), not smaller chunks; even then p99 is ~5 ms (still above 1.5 ms guard) with post_rtt still leading p50.

### What to change in the Session.at story on #299

Qualify: under saturated StreamLoad, measured e2e p99 is **mostly post_rtt**; Session.at is the leading **CPU** hotspot and a **design fix candidate (#326)** for server CPU / lock hold, not a proven sole e2e interval owner until a **non-`self_loaded`** G=64 window repeats the split.

