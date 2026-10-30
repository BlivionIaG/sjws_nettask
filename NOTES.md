# fix/syncer-async-dirty

**Branch:** `fix/syncer-async-dirty` off `origin/master` @ e0a25c1  
**Scope (inceptor):** append-mode syncer dirtiness + shard-level floor. Do **not** change stream shard open durability (NONE open rejected). #299 blocker. Reviewers: Agent Swarm + HA. Avoid admission/budget edits (Security N/A).

## Design

1. **asyncForceThrough** (volatile): high-water exclusive offset that needs a background force because an ASYNC append (or stronger path via fsyncWaiters) was committed. NONE appends advance `written` only.
2. Syncer `wanted` = `asyncForceThrough > durable || !fsyncWaiters.isEmpty()` (not bare `written > durable`).
3. Force watermark stays `written` so an ASYNC force covers earlier NONE bytes in the file (ASYNC semantics unchanged; NONE gets no stronger *guarantee*).
4. **Shard-level floor:** `sharedSyncSlice` returns floored without parking; `Shard.syncLoop` parks once for `min(remaining)` across members. Per-member force rate still ≤ 1/fsyncInterval.
5. Bound: ASYNC append forced within `fsyncInterval + ≤1 shard floor` (defaults: ≤ 2 ms) even if a neighbour is hot/floored.

## Status

- [ ] LedgerWriter dirty tracking + unpark
- [ ] SharedLedgerPool shard floor
- [ ] Tests + mutation proofs
- [ ] mvn verify
- [ ] ABBA ≥9 + force/s probe
- [ ] Push + PR

## Implementation done
- LedgerWriter: asyncForceThrough; SYNC_IDLE/BUSY/FLOORED; package ctor with pool; unpark only on ASYNC/waiters
- SharedLedgerPool: shard-level floor park
- SyncerDirtinessTest (7) + DurableOrdering/LedgerTest tweaks
- Focused ledger tests green

## Next
- full mvn verify
- ABBA 9 rounds + force/s probe under flock when load1<3
- push + PR

## Done
- PR https://github.com/BlivionIaG/sjws_nettask/pull/342 @ 99926d6
- verify PASS local + CI (verify still running on GH at last check; analyze/perf/image/CodeQL green)
- ABBA G1; force NONE 64w 12155 → 0 fsyncs/s
