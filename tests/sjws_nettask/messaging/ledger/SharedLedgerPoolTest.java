package sjws_nettask.messaging.ledger;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SharedLedgerPoolTest {

    @TempDir Path dir;

    /** #219 review: concurrent register/unregister must never leave a live writer without a syncer. */
    @Test
    void concurrentStartStopNeverLeavesAWriterWithoutASyncer() throws Exception {
        var pool = SharedLedgerPool.sharedOrNull();
        org.junit.jupiter.api.Assumptions.assumeTrue(pool != null, "shared syncers disabled");
        int threads = Math.max(4, pool.size() * 2), rounds = 60;
        var errors = new CopyOnWriteArrayList<Throwable>();
        var go = new CountDownLatch(1);
        var ts = new ArrayList<Thread>();
        for (int t = 0; t < threads; t++) {
            int id = t;
            ts.add(Thread.ofPlatform().start(() -> {
                try {
                    go.await();
                    for (int r = 0; r < rounds; r++) {
                        try (var log = SegmentedLog.open(dir.resolve("w" + id + "-" + r));
                                var w = new LedgerWriter(log, Durability.ASYNC, 0, 128, 1 << 16, 100_000L)) {
                            long base = w.append(List.of("x".getBytes(StandardCharsets.UTF_8))).get();
                            long deadline = System.nanoTime() + 5_000_000_000L;
                            while (w.snapshot().durableOffset() < base + 1) {
                                if (System.nanoTime() > deadline) throw new AssertionError("ASYNC durable hung: " + w.snapshot());
                                Thread.sleep(0, 200_000);
                            }
                        }
                    }
                } catch (Throwable e) {
                    errors.add(e);
                }
            }));
        }
        go.countDown();
        for (var t : ts) t.join(120_000);
        assertTrue(errors.isEmpty(), () -> "errors: " + errors);
        // With every writer gone, each shard's syncer retires itself (zero cost when unused).
        long deadline = System.nanoTime() + 5_000_000_000L;
        for (int s = 0; s < pool.size(); s++) {
            while (pool.memberCount(s) == 0 && pool.syncerThread(s) != null && System.nanoTime() < deadline) Thread.sleep(1);
        }
        for (int s = 0; s < pool.size(); s++) {
            if (pool.memberCount(s) == 0) assertNull(pool.syncerThread(s), "shard " + s + " syncer still running");
        }
    }

    @Test
    void resolveSizeParsesTheProperty() {
        String old = System.getProperty(SharedLedgerPool.PROPERTY);
        int cpus = Math.max(1, Runtime.getRuntime().availableProcessors());
        try {
            System.clearProperty(SharedLedgerPool.PROPERTY);
            assertEquals(cpus, SharedLedgerPool.resolveSize());
            for (var v : List.of(" ", "auto", "AUTO", "true")) {
                System.setProperty(SharedLedgerPool.PROPERTY, v);
                assertEquals(cpus, SharedLedgerPool.resolveSize(), v);
            }
            for (var v : List.of("0", "false", "off")) {
                System.setProperty(SharedLedgerPool.PROPERTY, v);
                assertEquals(0, SharedLedgerPool.resolveSize(), v);
            }
            System.setProperty(SharedLedgerPool.PROPERTY, " 3 ");
            assertEquals(3, SharedLedgerPool.resolveSize());
            System.setProperty(SharedLedgerPool.PROPERTY, "-1");
            assertThrows(IllegalArgumentException.class, SharedLedgerPool::resolveSize);
            System.setProperty(SharedLedgerPool.PROPERTY, "x");
            assertThrows(NumberFormatException.class, SharedLedgerPool::resolveSize);
        } finally {
            if (old == null) System.clearProperty(SharedLedgerPool.PROPERTY);
            else System.setProperty(SharedLedgerPool.PROPERTY, old);
        }
    }

    @Test
    void emptyPoolHasNoShardsAndIgnoresOutOfRangeShards() {
        var p = new SharedLedgerPool(0);
        assertEquals(0, p.size());
        p.unparkSyncer(0);
        p.unparkSyncer(-1);
        p.unregister(null, 0);
        p.unregister(null, -1);
    }

    /** Least-loaded placement prefers an empty shard; syncers start on first member and retire on last. */
    @Test
    void registerSpreadsWritersAndSyncersFollowMembership() throws Exception {
        var p = new SharedLedgerPool(2);
        try (var l1 = SegmentedLog.open(dir.resolve("a")); var w1 = new LedgerWriter(l1, Durability.ASYNC);
                var l2 = SegmentedLog.open(dir.resolve("b")); var w2 = new LedgerWriter(l2, Durability.ASYNC);
                var l3 = SegmentedLog.open(dir.resolve("c")); var w3 = new LedgerWriter(l3, Durability.ASYNC)) {
            int s1 = p.register(w1), s2 = p.register(w2);
            assertNotEquals(s1, s2, "second writer goes to the empty shard");
            assertNotNull(p.syncerThread(s1));
            assertNotNull(p.syncerThread(s2));
            p.unregister(w2, s2);
            int s3 = p.register(w3); // shard s2 is empty again: preferred
            assertEquals(s2, s3);
            int s4 = p.register(w2); // both have one: ties keep the lowest index
            assertEquals(0, s4);
            assertEquals(2, p.memberCount(0));
            assertEquals(3, p.memberCount(0) + p.memberCount(1));
            p.unparkSyncer(s1);
            p.unparkSyncer(5);
            p.unregister(w1, 7); // out of range: ignored
            assertEquals(3, p.memberCount(0) + p.memberCount(1));
            p.unregister(w1, s1);
            p.unregister(w2, s4);
            p.unregister(w3, s3);
            long deadline = System.nanoTime() + 5_000_000_000L;
            while ((p.syncerThread(0) != null || p.syncerThread(1) != null) && System.nanoTime() < deadline) Thread.sleep(1);
            assertNull(p.syncerThread(0));
            assertNull(p.syncerThread(1));
            // Restart after retirement.
            int s5 = p.register(w1);
            assertNotNull(p.syncerThread(s5));
            p.unregister(w1, s5);
        }
    }
}
