# LedgerWriter.durableNow():310 — JFR note (S2c PR 8, 2026-10-30 Europe/Paris)

## Observation

Offline 1k-stream server JFR (`gate-out/cpu-proof/java1k-server-jfr/server.jfr`, client not generator-saturated):

| Rank | Frame | Samples (of 1705 exec) |
|--|--|--|
| 1 | `sjws_nettask.messaging.ledger.LedgerWriter.durableNow() line: 310` | **465 (~27%)** |
| 2 | `sjws_nettask.swarm.StreamHttp$Session.at(long,long) line: 473` | 94 |
| … | … | … |

## What line 310 is

```java
// LedgerWriter.java:310
private long durableNow() { lock.lock(); try { return durable; } finally { lock.unlock(); } }
```

Messaging Core code. **Do not edit** for this PR.

## Stack (from JFR)

Almost all `durableNow` samples are the shared-ledger syncer, not the stream chunk publish path:

1. `LedgerWriter.durableNow():310`
2. `LedgerWriter.needsSharedSync():188` **or** `LedgerWriter.sharedSyncSlice():197`
3. `SharedLedgerPool$Shard.syncLoop():168` / `:161`

So this is lock traffic on the durable-offset read inside the sync loop under ASYNC/GROUP waiters.

## Does our stream path call durableNow per chunk?

**No.** `TokenStreams.admitAndWrite` (`TokenStreams.java:464`) produces with:

- non-LAST chunks → `Durability.NONE`
- LAST → `Durability.ASYNC`

`Durability.NONE` does not `waitsForFsync()` (`Durability.java:21`). In `LedgerWriter.writeGroup` (`:291`) the short-circuit

```java
if (!p.mode.waitsForFsync() || durableNow() >= end) p.done.complete(o);
```

skips `durableNow()` for NONE. Chunk publish completes without waiting on durability.

What *does* feed the syncer under a 1k gate: task placement / lease / LAST ASYNC produces and any other ASYNC writers on the same shared ledger shards. That is Messaging Core behavior; our swarm code does not insert an extra per-chunk `durableNow` call.

## Implication for the gate

Treat the 27% sample as evidence the box is busy in Messaging Core sync under 1k-stream admit/LAST churn — useful context, not a StreamLoad or PR-8 stream-path bug to “fix” by editing `LedgerWriter`.
