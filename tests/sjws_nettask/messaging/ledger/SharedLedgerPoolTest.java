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
}
