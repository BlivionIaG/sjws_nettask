# S2c PR 8 — server findings & design proposals (2026-10-30 Europe/Paris)

Harness head at time of writing includes transport-retry counters (`producer_transport_retries` /
`consumer_connect_retries`, hard-flag in `agg.py`). **No server code changes in this note — proposals only.**

---

## Finding A — EOF / `HTTP/1.1 header parser received no bytes` at high stream counts

### Symptom

Measure-phase client errors: `IOException: HTTP/1.1 header parser received no bytes` (JDK HttpClient:
connection closed before response headers). Seen at 1k streams (abs-01/02) and reproduced offline.

### Root cause (file:line)

`ClientManager.java:313–316` — accept loop:

```java
if (!this.perIp.tryAcquire(address)) { // one address can't take every connection slot
    this.refusedPerIp.increment();
    this.permits.release();
    closeQuietly(socket);   // ← no HTTP response
    continue;
}
```

Default `ServerConfig.DEFAULT_MAX_CONNECTIONS_PER_IP = 256` (`ServerConfig.java:95`).
Gate loadgen uses **one loopback address** (`127.0.0.1`) with ~1k consumer long-GETs + producer POSTs
⇒ far more than 256 concurrent connections. Excess accepts are **silently closed** → client EOF /
“header parser received no bytes”.

Not an fd limit: `ulimit -n` = 524288 on this box. Not primarily idle/keep-alive (30s idle default;
failures are at connect/setup under stampede). Not StreamHttp closing on 429 (429 returns a proper
problem response; EOFs have empty response).

### Offline proof (private port, no flock)

| Case | `max_connections_per_ip` | streams | `error_count` | `producer_transport_retries` | `consumer_connect_retries` |
|--|--|--|--|--|--|
| `eof-perip16` | **16** | 80 | 174 | 323 | 210 |
| `eof-perip4096` | **4096** | 80 | **0** | **0** | **0** |

Artifacts: `gate-out/cpu-proof/eof-perip16/`, `gate-out/cpu-proof/eof-perip4096/`.

### Proposal A1 — raise per-IP for the stream gate (config only)

For N4 / §13 1k-stream runs from a single client IP, set in the bench server config (or `one.sh`):

```yaml
server:
  max_connections: 4096          # already default 2048; 1k×2 needs headroom
  max_connections_per_ip: 4096   # was 256 — blocks loopback 1k
  backlog: 2048                  # default 1024; optional stampede cushion
```

Wire via `--config=` in `one.sh`. **Design approval needed** before treating this as the official gate
environment (changes the server’s multi-tenant isolation assumption for the bench only).

### Proposal A2 — export `refusedPerIp` on `/metrics` (Observability)

`ClientManager.refusedPerIp()` exists (`ClientManager.java:193–194`) but is not scraped. Exporting
`sjws_http_connections_refused_per_ip_total` would make this failure mode visible without JFR.
Observability review required.

---

## Finding B — `open_sessions` residual after drain (abs-02: 245)

### Symptom

After abs-02 measure + drain (`DRAIN_TIMEOUT=40`), `sjws_swarm_stream_open_sessions=245` and
`charged_bytes` nonzero — hard-zero miss.

### How sessions leave

- Gauge: `StreamStats` → `StreamBudget.openSessions()` (`StreamBudget.java:179`, sessions holding a LAST ticket).
- Ticket booked at `admitSession()` (`StreamBudget.java:354`); released in `budget.close` / `forget`
  (`StreamHttp.java:694–698`, `StreamBudget.java:close`).
- Single exit: `StreamHttp.exit` (`StreamHttp.java:681–690`).
- Lifetime: sessions live until **LAST + `GRACE_MS` (60_000)** or deadline+grace, then `sweep()`
  (`StreamHttp.java:94`, `:722–733`, class comment `:78`).

### Interaction with harness

1. **Per-IP EOFs (Finding A)** leave many sessions without a clean producer LAST (consumer never
   attached / producer never claimed). Those sessions wait for grace expiry before `sweep` → `forget`.
2. **`DRAIN_TIMEOUT=40` < `GRACE_MS=60s`**, so `waitDrained` cannot observe `open_sessions==0` for
   abandoned sessions even if the server is correct.
3. Producer abandoning a seq after transport retries without sending LAST does **not** call cancel;
   the session stays until grace. That is by design (readable until swept), not a silent leak of the
   exit function — but it does mean incomplete streams pin LAST tickets for up to 60s.

### Proposal B1 — harness drain ≥ grace (no server change)

Restore / require `DRAIN_TIMEOUT >= 75` (GRACE 60 + margin) for official abs windows so abandoned
sessions can be swept before the hard-zero scrape. (Earlier speed-up to 40s is insufficient.)

### Proposal B2 — optional: cancel-on-abandon (server, design approval)

If a lease holder disappears without LAST, an explicit cancel path that `forget`s sooner would shrink
grace residency under fault. Today cancel is client-driven (`StreamHttp.cancel`). Needs product/
Architecture approval (semantics of “producer gone”).

---

## Finding C — `StreamHttp$Session.at():473` (ours) — propose, do not implement

### Observation

Second JFR hotspot: retained-window lookup under the session lock (`StreamHttp.java:451–473`):

```java
for (var c : this.retained) {
    if (c.chunkSeq() == from) return new Chunk(c);
    if (c.chunkSeq() > from) return new Gap(...);
}
```

Linear scan of `retained` deque on every reader wake / `at` call, while holding `s.lock`. Under 1k
readers + credit/pressure this shows up (~94 samples).

### Proposal C1 — index or cursor for `at` (design approval)

- Keep a `chunkSeq → node` index, or a reader cursor into `retained`, so `at(from)` is O(1)/O(log n)
  instead of O(n) under the session lock.
- Must preserve gap/reset/LAST semantics and pressure trim (`trimRead`).
- **Do not implement without Architecture approval** (stream-core concurrency + credit invariants).

---

## Finding D — `LedgerWriter.durableNow():310` (Messaging Core)

See `/workspace/s2c-pr8/ledger-durableNow.md`. Not ours to edit. Stream chunks use `Durability.NONE`;
hot path is `SharedLedgerPool$Shard.syncLoop`, not per-chunk stream publish.

---

## Suggested gate order after approval

1. Land harness transport-retry counters (done in companion commit) — retries no longer hide EOFs.
2. Approve **A1** (per-IP config for bench) + **B1** (drain ≥ 75s).
3. Re-run abs windows under flock; expect EOF/retry flags to clear if A1 is in effect.
4. Only then consider C1 / A2 as follow-ups.
