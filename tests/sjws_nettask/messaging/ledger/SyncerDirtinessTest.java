package sjws_nettask.messaging.ledger;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
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
     * Shard-level floor: a hot neighbour's floor must not delay a sibling's due ASYNC force beyond
     * {@code fsyncInterval + one floor} (defaults: ≤ 2×{@link LedgerWriter#DEFAULT_FSYNC_INTERVAL_NANOS}).
     * Mutation: park inside sharedSyncSlice on FLOORED (old HOL) → sibling waits ~interval of the hot member.
     */
    @Test
    void shardFloorDoesNotBlockSiblingDueForce() throws Exception {
        long interval = 20_000_000L; // 20 ms — large enough to distinguish HOL vs shard floor
        var pool = new SharedLedgerPool(1);
        try (var logHot = SegmentedLog.open(dir.resolve("hot"));
                var logDue = SegmentedLog.open(dir.resolve("due"))) {
            var hot = new LedgerWriter(logHot, Durability.ASYNC, 0, 64, 4 << 20, interval, pool);
            var due = new LedgerWriter(logDue, Durability.ASYNC, 0, 64, 4 << 20, interval, pool);
            try {
                assertEquals(2, pool.memberCount(0), "both writers on the single shard");
                // Establish hot's lastSharedFsync so it is inside its floor window.
                hot.append(List.of(b("h")), Durability.ASYNC).get(5, TimeUnit.SECONDS);
                long hotDeadline = System.nanoTime() + interval + interval + 200_000_000L;
                while (hot.snapshot().durableOffset() < 1) {
                    assertTrue(System.nanoTime() < hotDeadline, "hot ASYNC never forced");
                    Thread.sleep(0, 100_000);
                }
                // Immediately append ASYNC on sibling while hot is still inside its floor (and keep hot dirty).
                hot.append(List.of(b("h2")), Durability.ASYNC).get(5, TimeUnit.SECONDS);
                long t0 = System.nanoTime();
                due.append(List.of(b("d")), Durability.ASYNC).get(5, TimeUnit.SECONDS);
                long bound = interval + interval; // fsyncInterval + at most one shard floor
                long deadline = t0 + bound + 100_000_000L; // +100 ms slack for scheduling
                while (due.snapshot().durableOffset() < 1) {
                    assertTrue(System.nanoTime() < deadline,
                            "sibling ASYNC blocked by neighbour floor (HOL); elapsed_ns="
                                    + (System.nanoTime() - t0) + " bound_ns=" + bound);
                    Thread.sleep(0, 100_000);
                }
                long elapsed = System.nanoTime() - t0;
                assertTrue(elapsed <= bound + 50_000_000L,
                        "elapsed_ns=" + elapsed + " must be ≤ interval+floor (" + bound + ") + slack");
            } finally {
                due.close();
                hot.close();
            }
        }
    }

    /**
     * Mixed mode: NONE after a pending ASYNC gets no stronger guarantee (test must not assert NONE durable
     * before an explicit force/close), while the earlier ASYNC is still forced. Force watermark covers the file.
     * Mutation: treat NONE like ASYNC for dirty marking without changing ASYNC → still passes ASYNC half;
     * mutation that skips noteAsyncForceThrough → ASYNC never forced.
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
                // Do NOT assert offset 1 is durable — NONE has no stronger guarantee than NONE.
                assertEquals(0, w.snapshot().durableOffset(), "NONE must not look durable before syncer runs");
                w.resumeSync();
                long deadline = System.nanoTime() + 2_000_000_000L;
                while (w.snapshot().durableOffset() < 1) {
                    assertTrue(System.nanoTime() < deadline, "ASYNC prefix never forced");
                    Thread.sleep(0, 200_000);
                }
                // After the ASYNC-driven force, watermark is written, so BOTH offsets may be durable
                // (piggyback). That is allowed; the guarantee for NONE is only that we never *promised* it.
                assertTrue(w.snapshot().durableOffset() >= 1, "ASYNC must be covered");
                assertTrue(w.snapshot().fsyncs() >= 1);
            } finally {
                w.close();
            }
        }
    }

    /**
     * Recovery: reopened writer treats recovered bytes as durable (constructor durable = nextOffset).
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
