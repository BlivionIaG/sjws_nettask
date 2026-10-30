package sjws_nettask.messaging.ledger;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Append-mode syncer dirtiness + shard-level floor (Messaging Core / #299 blocker).
 * Does not change stream-shard open durability (inceptor: NONE open rejected).
 */
class SyncerDirtinessTest {

    @TempDir Path dir;

    private static byte[] b(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    /**
     * GROUP/EACH still wait on fsync (SYNC semantics unchanged).
     * Mutation: make waitsForFsync always false, or skip forceInline on GROUP writer → ack returns before durable advances.
     */
    @Test
    void groupAndEachStillWaitOnFsync() throws Exception {
        try (var log = SegmentedLog.open(dir.resolve("group"))) {
            var w = new LedgerWriter(log, Durability.GROUP, 0, 64, 4 << 20, 0, null); // dedicated
            try {
                w.pauseSync();
                var f = w.append(List.of(b("g")), Durability.GROUP);
                assertEquals(0L, f.get(5, TimeUnit.SECONDS));
                assertEquals(1, w.snapshot().durableOffset());
                assertEquals(1, w.snapshot().fsyncs());
                w.resumeSync();
            } finally {
                w.close();
            }
        }
        try (var log = SegmentedLog.open(dir.resolve("each"))) {
            var w = new LedgerWriter(log, Durability.EACH, 0, 64, 4 << 20, 0, null);
            try {
                w.pauseSync();
                assertEquals(0L, w.append(List.of(b("e")), Durability.EACH).get(5, TimeUnit.SECONDS));
                assertEquals(1, w.snapshot().durableOffset());
                assertTrue(w.snapshot().fsyncs() >= 1);
                w.resumeSync();
            } finally {
                w.close();
            }
        }
    }

    /**
     * NONE-only appends on an ASYNC writer must not schedule background fsyncs.
     * Mutation: unpark/mark dirty on writer durability==ASYNC (ignoring append mode) → fsyncs &gt; 0.
     */
    @Test
    void noneOnlyAppendsIssueNoBackgroundFsyncs() throws Exception {
        var pool = new SharedLedgerPool(1);
        try (var log = SegmentedLog.open(dir.resolve("none-only"))) {
            var w = new LedgerWriter(log, Durability.ASYNC, 0, 256, 1 << 20,
                    LedgerWriter.DEFAULT_FSYNC_INTERVAL_NANOS, pool);
            try {
                long f0 = w.snapshot().fsyncs();
                for (int i = 0; i < 200; i++) {
                    w.append(List.of(b("n")), Durability.NONE).get(5, TimeUnit.SECONDS);
                }
                Thread.sleep(50); // generous vs 1 ms floor; syncer would have forced if scheduled
                assertEquals(f0, w.snapshot().fsyncs(), "NONE-only must not schedule background fsyncs");
                assertEquals(0, w.asyncForceThroughForTests() - w.snapshot().durableOffset(),
                        "asyncForceThrough must stay at durable under NONE-only");
                assertTrue(w.snapshot().nextOffset() > w.snapshot().durableOffset(),
                        "bytes are written but not marked for async force");
            } finally {
                w.close();
            }
        }
    }

    /**
     * An ASYNC append is forced within the interval bound (dedicated syncer, short interval).
     * Mutation: never set asyncForceThrough on ASYNC → durable stays behind.
     */
    @Test
    void asyncAppendForcedWithinInterval() throws Exception {
        long interval = 2_000_000L; // 2 ms
        try (var log = SegmentedLog.open(dir.resolve("async-bound"))) {
            var w = new LedgerWriter(log, Durability.ASYNC, 0, 64, 4 << 20, interval, null);
            try {
                long t0 = System.nanoTime();
                w.append(List.of(b("a")), Durability.ASYNC).get(5, TimeUnit.SECONDS);
                long deadline = t0 + interval + interval + 200_000_000L; // interval + one floor + slack
                while (w.snapshot().durableOffset() < 1) {
                    assertTrue(System.nanoTime() < deadline,
                            "ASYNC must be forced within interval + one floor; durable=" + w.snapshot().durableOffset());
                    Thread.sleep(0, 200_000);
                }
                assertTrue(w.snapshot().fsyncs() >= 1);
                long elapsed = System.nanoTime() - t0;
                assertTrue(elapsed < interval + interval + 100_000_000L,
                        "elapsed_ns=" + elapsed + " bound=" + (2 * interval));
            } finally {
                w.close();
            }
        }
    }

    /**
     * Close still forces residual dirty data (including NONE bytes left unsynced).
     * Mutation: drop forceInline at end of close when written &gt; durable → durable stays behind after close attempt.
     */
    @Test
    void closeForcesResidualDirty() throws Exception {
        var pool = new SharedLedgerPool(1);
        try (var log = SegmentedLog.open(dir.resolve("close-dirty"))) {
            var w = new LedgerWriter(log, Durability.ASYNC, 0, 64, 4 << 20,
                    10_000_000_000L /* large floor: syncer won't help before close */, pool);
            w.append(List.of(b("n")), Durability.NONE).get(5, TimeUnit.SECONDS);
            assertEquals(0, w.snapshot().durableOffset());
            assertEquals(1, w.snapshot().nextOffset());
            w.close();
            // After close, residual must have been forceInline'd (clean shutdown loses nothing).
            assertEquals(1, w.snapshot().durableOffset(), "close must fsync residual dirty prefix");
            assertTrue(w.snapshot().fsyncs() >= 1);
        }
    }

    /**
     * Shard-level floor: a hot neighbour with a much larger floor must not delay a sibling's due
     * ASYNC force beyond the sibling's {@code fsyncInterval + one shard floor}.
     * <p>
     * Dirty appends happen while both syncers are paused; pause flags are cleared without an unpark
     * so the single shard unpark that starts the scan is consumed by the idle park, not by an
     * in-slice {@code parkNanos}. Hot uses 50× the sibling interval. Restoring per-member
     * {@code parkNanos} inside {@code sharedSyncSlice} then blocks the sibling for ~hotInterval.
     * Mutation: park inside sharedSyncSlice on FLOORED → sibling blocked for ~hotInterval (5/5).
     */
    @Test
    void shardFloorDoesNotBlockSiblingDueForce() throws Exception {
        long dueInterval = 5_000_000L; // 5 ms
        long hotInterval = dueInterval * 50; // 250 ms — HOL parks ~this; shard floor does not
        var pool = new SharedLedgerPool(1);
        try (var logHot = SegmentedLog.open(dir.resolve("hot"));
                var logDue = SegmentedLog.open(dir.resolve("due"))) {
            // Register hot first so it is earlier in the CopyOnWriteArrayList scan (HOL starvation order).
            var hot = new LedgerWriter(logHot, Durability.ASYNC, 0, 64, 4 << 20, hotInterval, pool);
            var due = new LedgerWriter(logDue, Durability.ASYNC, 0, 64, 4 << 20, dueInterval, pool);
            try {
                assertEquals(2, pool.memberCount(0), "both writers on the single shard");
                hot.append(List.of(b("h")), Durability.ASYNC).get(5, TimeUnit.SECONDS);
                long hotDeadline = System.nanoTime() + hotInterval + hotInterval + 200_000_000L;
                while (hot.snapshot().durableOffset() < 1) {
                    assertTrue(System.nanoTime() < hotDeadline, "hot ASYNC never forced");
                    Thread.sleep(0, 100_000);
                }
                hot.pauseSync();
                due.pauseSync();
                hot.append(List.of(b("h2")), Durability.ASYNC).get(5, TimeUnit.SECONDS);
                due.append(List.of(b("d")), Durability.ASYNC).get(5, TimeUnit.SECONDS);
                assertEquals(0, due.snapshot().durableOffset());
                // Clear pause without unpark so the next unpark is consumed waking the idle syncer,
                // not aborting an in-slice parkNanos (HOL mutant).
                Field paused = LedgerWriter.class.getDeclaredField("syncPaused");
                paused.setAccessible(true);
                paused.setBoolean(hot, false);
                paused.setBoolean(due, false);
                Thread.sleep(20); // let shared syncer reach idle park after paused IDLE slices
                long t0 = System.nanoTime();
                pool.unparkSyncer(0);
                long bound = dueInterval + dueInterval; // interval + one shard floor
                long deadline = t0 + bound + 30_000_000L; // +30 ms scheduling slack only
                while (due.snapshot().durableOffset() < 1) {
                    assertTrue(System.nanoTime() < deadline,
                            "sibling ASYNC blocked by neighbour floor (HOL); elapsed_ns="
                                    + (System.nanoTime() - t0) + " bound_ns=" + bound
                                    + " hotInterval_ns=" + hotInterval);
                    Thread.sleep(0, 50_000);
                }
                long elapsed = System.nanoTime() - t0;
                assertTrue(elapsed <= bound + 30_000_000L,
                        "elapsed_ns=" + elapsed + " must be ≤ dueInterval+floor (" + bound
                                + ") + slack — per-member park on hot would wait ~hotInterval="
                                + hotInterval);
            } finally {
                due.resumeSync();
                hot.resumeSync();
                due.close();
                hot.close();
            }
        }
    }

    /**
     * Mixed mode: NONE after a pending ASYNC gets no stronger guarantee at ack time (durable stays 0
     * until the syncer runs), while the earlier ASYNC still schedules a force. After that force,
     * the watermark is {@code written} (whole-file fsync piggyback), not {@code asyncForceThrough} alone.
     * Mutation: set force target to {@code asyncForceThrough} instead of {@code written} → durable stops at 1.
     * Mutation: skip {@code noteAsyncForceThrough} → ASYNC never forced (deadline).
     */
    @Test
    void mixedNoneAfterAsyncDoesNotPromiseNoneDurable() throws Exception {
        long interval = 1_000_000L;
        var pool = new SharedLedgerPool(1);
        try (var log = SegmentedLog.open(dir.resolve("mixed"))) {
            var w = new LedgerWriter(log, Durability.ASYNC, 0, 64, 4 << 20, interval, pool);
            try {
                w.pauseSync(); // hold syncer so ASYNC is pending (asyncForceThrough > durable)
                w.append(List.of(b("a")), Durability.ASYNC).get(5, TimeUnit.SECONDS);
                assertEquals(1, w.asyncForceThroughForTests());
                assertEquals(0, w.snapshot().durableOffset());
                w.append(List.of(b("n")), Durability.NONE).get(5, TimeUnit.SECONDS);
                assertEquals(2, w.snapshot().nextOffset());
                assertEquals(1, w.asyncForceThroughForTests(), "NONE must not advance asyncForceThrough");
                // No promise at ack: durable still 0 while syncer paused (NONE did not look durable).
                assertEquals(0, w.snapshot().durableOffset(), "NONE must not look durable before syncer runs");
                w.resumeSync();
                long deadline = System.nanoTime() + 2_000_000_000L;
                while (w.snapshot().durableOffset() < 1) {
                    assertTrue(System.nanoTime() < deadline, "ASYNC prefix never forced");
                    Thread.sleep(0, 200_000);
                }
                // Force watermark is written (piggyback). Mutant that advances durable only to
                // asyncForceThrough leaves durable at 1 while nextOffset is 2.
                assertEquals(w.snapshot().nextOffset(), w.snapshot().durableOffset(),
                        "ASYNC-driven force watermark must be written, not asyncForceThrough alone");
                assertTrue(w.snapshot().fsyncs() >= 1);
            } finally {
                w.close();
            }
        }
    }

    /**
     * N=32 NONE appends then an ASYNC LAST in the same file: LAST is forced within
     * {@code fsyncInterval + one floor}, and that force covers all preceding NONE bytes
     * (durable watermark ≥ LAST end / {@code nextOffset}).
     * Mutation: never {@code noteAsyncForceThrough} → LAST never forced.
     * Mutation: force watermark = {@code asyncForceThrough} only → durable stops before NONE prefix
     * if LAST end were somehow ahead; with single ASYNC at end, watermark-only-async still reaches
     * LAST end — primary kill is skip-note; watermark mutant killed by {@link #mixedNoneAfterAsyncDoesNotPromiseNoneDurable}.
     * Mutation: treat force target as durable-before-NONE (e.g. cap at offset before LAST) → durable &lt; nextOffset.
     */
    @Test
    void nonePrefixThenAsyncLastForcedCoversAll() throws Exception {
        long interval = 2_000_000L; // 2 ms
        var pool = new SharedLedgerPool(1);
        try (var log = SegmentedLog.open(dir.resolve("none-then-last"))) {
            var w = new LedgerWriter(log, Durability.ASYNC, 0, 256, 4 << 20, interval, pool);
            try {
                w.pauseSync();
                for (int i = 0; i < 32; i++) {
                    w.append(List.of(b("n" + i)), Durability.NONE).get(5, TimeUnit.SECONDS);
                }
                assertEquals(32, w.snapshot().nextOffset());
                assertEquals(0, w.snapshot().durableOffset());
                assertEquals(0, w.asyncForceThroughForTests(), "NONE prefix must not schedule force");
                w.append(List.of(b("LAST")), Durability.ASYNC).get(5, TimeUnit.SECONDS);
                long lastEnd = w.snapshot().nextOffset();
                assertEquals(33, lastEnd);
                assertEquals(33, w.asyncForceThroughForTests());
                assertEquals(0, w.snapshot().durableOffset(), "paused: nothing durable yet");
                long t0 = System.nanoTime();
                w.resumeSync();
                long bound = interval + interval; // interval + one floor
                long deadline = t0 + bound + 200_000_000L;
                while (w.snapshot().durableOffset() < lastEnd) {
                    assertTrue(System.nanoTime() < deadline,
                            "ASYNC LAST not forced within interval+floor; durable="
                                    + w.snapshot().durableOffset() + " need=" + lastEnd);
                    Thread.sleep(0, 100_000);
                }
                assertEquals(lastEnd, w.snapshot().durableOffset(),
                        "force covering LAST must piggyback all preceding NONE bytes");
                assertTrue(w.snapshot().fsyncs() >= 1);
                long elapsed = System.nanoTime() - t0;
                assertTrue(elapsed <= bound + 100_000_000L,
                        "elapsed_ns=" + elapsed + " bound=" + bound);
            } finally {
                w.close();
            }
        }
    }

    /**
     * Recovery semantics unchanged from master: constructor sets {@code durable = log.nextOffset()},
     * so a reopened writer reports the recovered prefix as durable (it survived a restart).
     * Does not claim a stronger guarantee than master (no new fsync-on-open).
     * Mutation: leave durable at 0 on open → durableOffset &lt; nextOffset right after reopen with data.
     */
    @Test
    void recoveryTreatsReopenedBytesAsDurable() throws Exception {
        Path p = dir.resolve("rec");
        try (var log = SegmentedLog.open(p)) {
            var w = new LedgerWriter(log, Durability.ASYNC, 0, 64, 4 << 20, 0, null);
            try {
                w.append(List.of(b("r")), Durability.ASYNC).get(5, TimeUnit.SECONDS);
                w.force();
                assertEquals(1, w.snapshot().durableOffset());
            } finally {
                w.close();
            }
        }
        try (var log = SegmentedLog.open(p)) {
            var w = new LedgerWriter(log, Durability.ASYNC, 0, 64, 4 << 20, 0, null);
            try {
                assertEquals(1, log.nextOffset());
                assertEquals(1, w.snapshot().nextOffset());
                assertEquals(1, w.snapshot().durableOffset(), "recovered data is durable");
                assertEquals(w.snapshot().durableOffset(), w.asyncForceThroughForTests());
            } finally {
                w.close();
            }
        }
    }
}
