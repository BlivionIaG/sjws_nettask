# Raft delivery executor (per group)

Status: implemented (`cluster.DeliveryExecutor`, wired into `RaftGroup`).

## Problem

Before this change a `RaftGroup` delivered committed entries by draining a queue under a `deliveryLock`,
on whichever thread got there first: the Raft tick/frame thread (via `tryLock`, skipping when busy), an
`append()` caller, or a callback that appended re-entrantly. That gave ordering and no overlap, but:

- the thread running a delivery was whoever happened to commit, so a slow callback ran on an appender
  or (when the lock was free) on the Raft thread itself;
- there was no bound: if apply fell behind, the queue grew without limit and nobody was told;
- append futures completed on that same arbitrary thread;
- shutting a group down could not tell whether a drainer was still running, so a `heal()` reopening the
  group could overlap an old drainer;
- listeners such as `GenerationSync` did IO (bundle pull, key write) inside the callback.

## Design

One single-thread executor per group, `raft-deliver-<group>` (a virtual thread, started lazily on the
first delivery).

- **What runs on it.** Everything the group used to queue for delivery, in log order: user
  `GroupListener` callbacks, internal no-op listeners, and completion of pending `append()` futures
  (including failing them on step-down). The Raft thread and appenders only `submit`; they never run a
  callback.
- **Ordering / no overlap.** One FIFO queue, one thread: entry *n*'s callback finishes before *n+1*'s
  starts, and an append future completes after the callbacks of every entry up to its own.
- **Re-entrancy.** The old "already draining on this thread" guard becomes `onExecutorThread()`. A
  callback may `append()`; the resulting deliveries are queued behind it (never run inline). Waiting
  helpers (`awaitIdle`, `ReplicatedNode.drive()`) return at once on the executor thread instead of
  waiting for themselves.
- **Bounded queue + backpressure.** `lag` = deliveries queued or running. When `lag` reaches the limit
  (`DEFAULT_LAG_LIMIT` = 4096), `append()` on the leader **fails fast**: its future fails at once with `ApplyLagException`
  (nothing appended; the caller retries later). We chose fail over block: blocking an appender that is
  itself a callback would deadlock, and blocking while holding the group lock would stall Raft.
  Submissions from the Raft thread (follower commits, step-down) are never refused, so the Raft thread
  never blocks; the limit therefore bounds client-driven growth, not replication.
- **Lag metric.** `ReplicatedNode.deliveryLag(group)` (current) and `maxDeliveryLag(group)` (high-water
  mark), plus a delivered counter in the executor.
- **Deterministic close.** `close(drainBudget)`: wait up to the budget for the queue to go idle, then
  mark closed and *abandon* whatever is still queued (each future fails with
  `IllegalStateException("group closed before delivery")`), interrupt a callback that overran, and join
  the thread. After it returns no new delivery starts, so `heal()`/reopen cannot overlap an old drainer.
  `ReplicatedNode.close()` stops the tick thread first, then closes each group's deliveries (1 s budget).
  Submits after close are abandoned the same way.
- **Failures.** A callback that throws is logged (WARNING) and skipped; the stream continues. The
  interrupt flag is cleared after each item so one callback's interrupt does not leak into the next.
- **Witness.** A witness never applies entries and has no listener; its executor thread is never
  started (lazy start), so it costs nothing and its behaviour is unchanged.

## Manual driving

`ReplicatedNode.drive()` (tests, `LocalCluster`, `CpStore.drive()`, `CpWitness.drive()`) is a step plus
a bounded wait (5 s) for the deliveries it caused, so manual callers still observe callbacks right after
`drive()` returns. The background loop calls `step()` and never waits. Tests that model "the Raft
thread while a callback is parked" use `step()`.

## Listener rules (GroupListener javadoc)

Callbacks must be fast, do no IO, and never block on their own (or a later) append future: that future
completes on the same thread, after the callback returns. `GenerationSync` now copies each marker and
hands it to its own single-thread worker (`ha-generation-sync`), which does the pull/key write
(STAGE) and activation (ACTIVATE) in order.

## Latency impact

Each commit batch adds one handoff to the delivery thread (a queue offer plus a virtual-thread unpark)
before callbacks run and futures complete. This is expected to be small next to a replication round
trip, but **it has not been measured**; no benchmark was run for this change (it does not touch the
HTTP request path). In exchange, heartbeats and votes are fully decoupled from slow callbacks, and
appenders no longer pay for other entries' callbacks.

## Tests

`RaftClusterTest`: nested appends with no overlap/reordering under concurrency; concurrent appends with
a Raft-thread stepper delivering each entry once in order; callbacks and futures on the delivery thread,
callback before future; backpressure (`ApplyLagException` at a lowered limit, everything accepted still
delivered); `close()` during a slow callback (bounded, interrupts, abandons the rest); latched 3-voter
tests on the follower (heartbeat and vote handled while parked) and on the leader (commits continue
while parked, futures complete in order after release). `GenerationSyncTest` checks the work runs on
the sync worker.
