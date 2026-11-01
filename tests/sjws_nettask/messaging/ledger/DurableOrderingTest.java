package sjws_nettask.messaging.ledger;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * #329: publish → durable happens-before that GROUP/EACH acks and durable observers rely on.
 * Mutation target: moving {@code durable = watermark} (and {@code publish}) before {@code log.force()}
 * in {@link LedgerWriter#forceInline} makes {@link #durableNotAdvancedBetweenForceAndStore} and
 * {@link #groupAckDoesNotCompleteBeforeDurableStore} fail.
 */
class DurableOrderingTest {

    @TempDir Path dir;

    private static byte[] b(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    /**
     * After {@code log.force()} returns and before the durable store, snapshot.durableOffset must still
     * be the pre-force value. Fails if durable is published before force (or before the hook).
     */
    @Test
    void durableNotAdvancedBetweenForceAndStore() throws Exception {
        try (var log = SegmentedLog.open(dir.resolve("hb-force"), 1 << 20)) {
            var w = new LedgerWriter(log, Durability.ASYNC, 0, 64, 4 << 20, 0);
            try {
                w.pauseSync();
                w.append(List.of(b("a"))).get();
                assertEquals(0, w.snapshot().durableOffset());
                var duringHook = new AtomicLong(-1);
                var sawUnsynced = new AtomicBoolean();
                w.afterLogForce = () -> {
                    long d = w.snapshot().durableOffset();
                    duringHook.set(d);
                    // Records past durable must not look durable yet; nextOffset already includes "a".
                    if (d >= 1) sawUnsynced.set(true);
                };
                w.force();
                assertEquals(0, duringHook.get(),
                        "durable must not advance until after afterLogForce (after fsync, before store)");
                assertFalse(sawUnsynced.get());
                assertEquals(1, w.snapshot().durableOffset());
                assertNotNull(log.read(0));
            } finally {
                w.afterLogForce = null;
                w.close();
            }
        }
    }

    /**
     * A GROUP ack must not complete while afterLogForce is running (durable not stored yet).
     * Also: an observer that sees durable &gt;= N can read record N-1.
     */
    @Test
    void groupAckDoesNotCompleteBeforeDurableStore() throws Exception {
        try (var log = SegmentedLog.open(dir.resolve("hb-ack"), 1 << 20)) {
            // Dedicated syncer (no shared pool interference): fsyncInterval 0, GROUP default.
            var w = new LedgerWriter(log, Durability.GROUP, 0, 64, 4 << 20, 0);
            try {
                w.pauseSync(); // only writeGroup's forceInline advances durable / runs the hook
                var inHook = new CountDownLatch(1);
                var releaseHook = new CountDownLatch(1);
                var ackDoneInHook = new AtomicBoolean();
                var durableInHook = new AtomicLong(-1);
                var ackRef = new AtomicReference<CompletableFuture<Long>>();

                w.afterLogForce = () -> {
                    durableInHook.set(w.snapshot().durableOffset());
                    var ack = ackRef.get();
                    if (ack != null && ack.isDone()) ackDoneInHook.set(true);
                    inHook.countDown();
                    try {
                        if (!releaseHook.await(5, TimeUnit.SECONDS)) {
                            throw new RuntimeException("releaseHook timeout");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                };

                var producer = Thread.ofPlatform().start(() -> {
                    try {
                        var f = w.append(List.of(b("g")));
                        ackRef.set(f);
                        f.get(10, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });

                assertTrue(inHook.await(10, TimeUnit.SECONDS), "force hook not reached");
                assertEquals(0, durableInHook.get(), "durable still 0 inside hook");
                assertFalse(ackDoneInHook.get(), "GROUP ack must not complete before durable store");
                var ack = ackRef.get();
                assertNotNull(ack);
                assertFalse(ack.isDone(), "ack still pending in hook");

                releaseHook.countDown();
                producer.join(10_000);
                assertTrue(ack.isDone());
                assertEquals(0L, ack.get());
                assertEquals(1, w.snapshot().durableOffset());
                assertArrayEquals(b("g"), log.read(0).records().get(0));
            } finally {
                w.afterLogForce = null;
                w.close();
            }
        }
    }

    /**
     * Shared syncer path: same force→hook→durable ordering via sharedSyncSlice.
     */
    @Test
    void sharedSyncSliceDurableNotAdvancedInAfterLogForce() throws Exception {
        var pool = SharedLedgerPool.sharedOrNull();
        org.junit.jupiter.api.Assumptions.assumeTrue(pool != null, "shared syncers disabled");
        try (var log = SegmentedLog.open(dir.resolve("hb-shared"), 1 << 20)) {
            var w = new LedgerWriter(log, Durability.ASYNC, 0, 64, 4 << 20, 0);
            try {
                var duringHook = new AtomicLong(-1);
                var hooked = new CountDownLatch(1);
                w.afterLogForce = () -> {
                    duringHook.set(w.snapshot().durableOffset());
                    hooked.countDown();
                };
                w.append(List.of(b("s"))).get();
                long deadline = System.nanoTime() + 10_000_000_000L;
                while (w.snapshot().durableOffset() < 1 && System.nanoTime() < deadline) {
                    Thread.sleep(1);
                }
                assertTrue(hooked.await(1, TimeUnit.SECONDS), "shared syncer did not invoke afterLogForce");
                assertEquals(0, duringHook.get(), "shared path must store durable after hook");
                assertEquals(1, w.snapshot().durableOffset());
                assertNotNull(log.read(0));
            } finally {
                w.afterLogForce = null;
                w.close();
            }
        }
    }

    /**
     * Stress: under ASYNC churn, durable must keep advancing (pinged / unpark not lost).
     * A lost wakeup would leave durable stuck below written. (NONE-only no longer schedules forces —
     * see SyncerDirtinessTest.noneOnlyAppendsIssueNoBackgroundFsyncs.)
     */
    @Test
    void pingedUnparkEventuallyAdvancesDurableUnderChurn() throws Exception {
        var pool = SharedLedgerPool.sharedOrNull();
        org.junit.jupiter.api.Assumptions.assumeTrue(pool != null, "shared syncers disabled");
        int writers = Math.min(16, Math.max(4, pool.size() * 2));
        var logs = new ArrayList<SegmentedLog>();
        var ws = new ArrayList<LedgerWriter>();
        try {
            for (int i = 0; i < writers; i++) {
                var log = SegmentedLog.open(dir.resolve("ping-" + i), 1 << 20);
                logs.add(log);
                ws.add(new LedgerWriter(log, Durability.ASYNC, 0, 256, 1 << 20, 100_000L));
            }
            byte[] payload = b("x");
            for (int round = 0; round < 200; round++) {
                for (var w : ws) {
                    w.append(List.of(payload), Durability.ASYNC).get();
                }
            }
            long deadline = System.nanoTime() + 15_000_000_000L;
            for (var w : ws) {
                long want = w.snapshot().nextOffset();
                while (w.snapshot().durableOffset() < want) {
                    if (System.nanoTime() > deadline) {
                        fail("durable hung (possible lost pinged/unpark): " + w.snapshot());
                    }
                    Thread.sleep(1);
                }
            }
        } finally {
            for (var w : ws) w.close();
            for (var log : logs) log.close();
        }
    }
}
