# Abs-01 errors + 1k client CPU after 4d6bc0d (Europe/Paris 2026-10-30)

## 1. err≈1951 — measure vs teardown (drive.py split)

StreamLoad mirrors drive.py: `inTeardown` flips only after the measure sleep; `note()` routes to `measureErr` or `teardownErr`.

| Field | abs-01 (pre-429-fix harness) | meaning |
|--|--|--|
| `error_count` | **1951** | measure-phase |
| `teardown_error_count` | 6 | after `inTeardown` |
| stored measure samples | 30 (cap) | all `consumer g*: IOException: HTTP/1.1 header parser received no bytes` |
| teardown samples | claim 204 + consumer closed | tear-down / missing task |

**Verdict:** not the old Python BrokenPipe teardown race. These are **measure-phase** consumer connect/read failures (EOF before response headers). Offline re-runs at 1k still show ~1700–1800 measure errors of the same shape; teardown stays small (2–6).

## 2. 4d6bc0d + stream.shards=4 — client off saturation?

| Run | client % | gen_sat | credit_stalls | stall_flag | mib/s | notes |
|--|--|--|--|--|--|
| abs-01 (f9215f7, shards mis-set=1) | **182.8** | true | 4782 | false (0.88%) | 26.5 | 429 busy-loop |
| offline 4d6bc0d shards=1 | 191 | true | 8 | false | 22.4 | backoff works; still sat |
| offline 4d6bc0d + `-Dsjws.swarm.stream.shards=4` | **165** | **false** | 0 | false | 20.3 | `one.sh` previously set only `pool.shards` |
| offline server-JFR shards=4 | **178.5** | false | 99 | false (0.02%) | 29.6 | just under 180 |

`one.sh` bug: logged `shards=1` because TokenStreams reads **`sjws.swarm.stream.shards`**, not `sjws.swarm.pool.shards`. Fixed in harness.

Client CPU on 0–1 is now **under 180%** with shards=4 + 429/CREDIT fix. Throughput/latency still fail the absolute row; residual measure errors remain.

## 3. Credit refusals / 429 counting

- Each HTTP 429 on `:chunk` increments `credit_stalls` **before** sleep/backoff (not suppressed).
- `credit_stall_pct_of_chunks = credit_stalls / chunk_samples`; `stall_flag` iff > 1% (Architecture Q3/Q4).
- After backoff+CREDIT gate, stalls dropped 4782 → ~0–99 and stay **flagged correctly** when >1% (none of these runs crossed 1%).

## 4. Server JFR (1k, client not saturated) — cause hint

File: `gate-out/cpu-proof/java1k-server-jfr/server.jfr`

Top `sjws_nettask` execution samples (1705):

1. **`LedgerWriter.durableNow() line: 310`** — 465 (~27%) — lock-protected durable offset read; syncer/ASYNC wait path
2. **`StreamHttp$Session.at(long,long) line: 473`** — 94 — retained-window lookup under session lock
3. `Http1Connection.read() line: 296` — 69
4. `SwarmHttp.readFrame(Request) line: 455` — 45

Chunks use `Durability.NONE`; `LAST`/task paths use `ASYNC`. Hot `durableNow` is lock traffic on the ledger syncer under 1k-stream admit/lease churn — leading server-side cost candidate when the client is not generator-saturated.


## Abs-02 official (flock, load1_pre=3.26, head 6c49b7b, Europe/Paris 12:31)

| | |
|--|--|
| mib_s | 27.21 |
| p50 / p99 µs | 337.799 / 4010.147 |
| samples | 557228 |
| error_count / teardown | 14144 / 105 |
| client / server CPU % | 169.8 / 365.6 |
| gen_sat / self_loaded | False / True (load1_post=5.21) |
| credit_stalls / stall_flag | 3 / False |
| open_sessions after drain | 245.0 |

Client **not** generator-saturated (169.8% < 180). Invalid: self_loaded (load1_post 5.21) + low mib/s + p99>1ms + measure errors.
error_count inflated: producer noted every transport retry (fixed next commit — note only after tries exhausted).
Server still hot: prior JFR `LedgerWriter.durableNow():310`, `Session.at():473`.
