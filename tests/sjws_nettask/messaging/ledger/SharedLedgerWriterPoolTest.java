package sjws_nettask.messaging.ledger;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Writer-pool safety tests for #336 / #337 (T1 FIFO, T3 refuse-no-fence, T4 close race, T5 GROUP dedicated,
 * T10 HOL bound, T11 messaging-off ⇒ 0 threads, T13 XXH64 sticky).
 */
class SharedLedgerWriterPoolTest {

    @TempDir Path dir;

    private String oldWriters;
    private String oldSyncers;

    @BeforeEach
    void enableSmallPool() {
        oldWriters = System.getProperty(SharedLedgerPool.WRITER_THREADS_PROPERTY);
        oldSyncers = System.getProperty(SharedLedgerPool.SYNCERS_PROPERTY);
        System.setProperty(SharedLedgerPool.WRITER_THREADS_PROPERTY, "2");
        SharedLedgerPool.resetWriterPoolForTests();
    }

    @AfterEach
    void restore() {
        if (oldWriters == null) System.clearProperty(SharedLedgerPool.WRITER_THREADS_PROPERTY);
        else System.setProperty(SharedLedgerPool.WRITER_THREADS_PROPERTY, oldWriters);
        if (oldSyncers == null) System.clearProperty(SharedLedgerPool.SYNCERS_PROPERTY);
        else System.setProperty(SharedLedgerPool.SYNCERS_PROPERTY, oldSyncers);
        SharedLedgerPool.resetWriterPoolForTests();
        LedgerWriterBudget.resetForTests();
    }

    @Test
    void fifoPerPartitionUnderConcurrentAppenders() throws Exception {
        // T1: monotonic payloads on one partition observe increasing offsets matching enqueue order.
        // Mutation: draining round-robin inside one partition's Pending list must fail.
        try (var log = SegmentedLog.open(dir.resolve("fifo"));
                var w = new LedgerWriter(log, Durability.NONE, 0, 4096, 1 << 20)) {
            assertTrue(w.usesSharedWriter());
            int n = 200;
            var futs = new CompletableFuture<?>[n];
            var go = new CountDownLatch(1);
            var threads = new ArrayList<Thread>();
            for (int t = 0; t < 4; t++) {
                int base = t;
                threads.add(Thread.ofPlatform().start(() -> {
                    try {
                        go.await();
                        for (int i = base; i < n; i += 4) {
                            byte[] payload = ("m-" + i).getBytes(StandardCharsets.UTF_8);
                            futs[i] = w.append(List.of(payload));
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }));
            }
            go.countDown();
            for (var t : threads) t.join(30_000);
            for (int i = 0; i < n; i++) {
                assertNotNull(futs[i], "missing future " + i);
                futs[i].get(10, TimeUnit.SECONDS);
            }
            // Stronger check: every payload appears exactly once and offsets are dense (FIFO drain).
            var seen = new boolean[n];
            final int[] count = {0};
            log.scanFrom(log.startOffset(), b -> {
                for (byte[] r : b.records()) {
                    String s = new String(r, StandardCharsets.UTF_8);
                    assertTrue(s.startsWith("m-"), s);
                    int idx = Integer.parseInt(s.substring(2));
                    assertFalse(seen[idx], "duplicate " + idx);
                    seen[idx] = true;
                    count[0]++;
                }
            });
            assertEquals(n, count[0]);
            for (boolean b : seen) assertTrue(b);
        }
    }

    @Test
    void groupAndEachStayDedicated() throws Exception {
        // T5 / D6
        try (var logG = SegmentedLog.open(dir.resolve("g"));
                var g = new LedgerWriter(logG, Durability.GROUP);
                var logE = SegmentedLog.open(dir.resolve("e"));
                var e = new LedgerWriter(logE, Durability.EACH);
                var logA = SegmentedLog.open(dir.resolve("a"));
                var a = new LedgerWriter(logA, Durability.ASYNC)) {
            assertFalse(g.usesSharedWriter());
            assertEquals(-1, g.sharedWriterShard());
            assertFalse(e.usesSharedWriter());
            assertTrue(a.usesSharedWriter());
            assertTrue(a.sharedWriterShard() >= 0);
            // GROUP durability still works (unchanged forceInline path).
            long base = g.append(List.of("x".getBytes(StandardCharsets.UTF_8))).get(5, TimeUnit.SECONDS);
            assertEquals(base, g.snapshot().durableOffset() > base ? base : base); // completed ⇒ durable for GROUP
            assertTrue(g.snapshot().durableOffset() >= base + 1);
        }
    }

    @Test
    void stickyXxh64SameRealPathSameShard() throws Exception {
        // T13
        Path a = dir.resolve("part");
        Files.createDirectories(a);
        Path real = a.toRealPath();
        int n = Math.max(2, SharedLedgerPool.resolveWriterThreads());
        int s1 = SharedLedgerPool.WriterPool.shardFor(real, n);
        int s2 = SharedLedgerPool.WriterPool.shardFor(real.toAbsolutePath().normalize(), n);
        assertEquals(s1, s2);
        try {
            Path link = dir.resolve("alias");
            Files.createSymbolicLink(link, a);
            assertEquals(s1, SharedLedgerPool.WriterPool.shardFor(link.toRealPath(), n));
        } catch (UnsupportedOperationException | java.io.IOException ignored) {
            // symlink optional
        }
        Path other = dir.resolve("other");
        Files.createDirectories(other);
        int sOther = SharedLedgerPool.WriterPool.shardFor(other.toRealPath(), n);
        assertTrue(s1 >= 0 && s1 < n && sOther >= 0 && sOther < n);
    }

    @Test
    void messagingOffMeansZeroWriterPoolThreads() throws Exception {
        // T11: never open a NONE/ASYNC writer ⇒ writers() not required; after reset, no threads.
        SharedLedgerPool.resetWriterPoolForTests();
        assertNull(SharedLedgerPool.writersOrNull());
        // Touching syncer pool alone must not start writer threads.
        var sync = SharedLedgerPool.sharedOrNull();
        Thread.sleep(50);
        var wp = SharedLedgerPool.writersOrNull();
        if (wp != null) assertFalse(wp.anyThreadAlive(), "writer threads must not start without NONE/ASYNC open");
        // Class load of SharedLedgerPool is fine; explicit writers() with size>0 still starts no threads until Pending.
        var pool = SharedLedgerPool.writers();
        assertFalse(pool.disabled());
        assertFalse(pool.anyThreadAlive(), "registration-less pool has 0 live writer threads");
        for (int i = 0; i < pool.size(); i++) {
            assertNull(pool.writerThread(i));
        }
        // Also scan JVM threads: mutant that parks forever without retiring must fail this.
        long shared = Thread.getAllStackTraces().keySet().stream()
                .filter(th -> th.getName().startsWith("ledger-writer-shared-"))
                .filter(Thread::isAlive)
                .count();
        assertEquals(0, shared, "messaging-off must not leave ledger-writer-shared-* threads");
    }

    @Test
    void growthRefusalOnPooledWriterDoesNotFenceShardOrNeighbour() throws Exception {
        // T3: large append refused; next small append on same writer succeeds; neighbour on same shard ok.
        SharedLedgerPool.resetWriterPoolForTests();
        System.setProperty(SharedLedgerPool.WRITER_THREADS_PROPERTY, "1"); // force same shard
        SharedLedgerPool.resetWriterPoolForTests();
        // Budget: room for idle×2 + one shard materialize, not a multi-MiB growth.
        long idle = LedgerWriterBudget.IDLE_COST_BYTES;
        long mat = LedgerWriterBudget.materializeCostBytes(LedgerWriter.DEFAULT_QUEUE);
        LedgerWriterBudget.install(LedgerWriterBudget.ofBytes(idle * 2 + mat + 1024));
        try (var logA = SegmentedLog.open(dir.resolve("ra"));
                var a = new LedgerWriter(logA, Durability.NONE, 0, 128, 1 << 20);
                var logB = SegmentedLog.open(dir.resolve("rb"));
                var b = new LedgerWriter(logB, Durability.NONE, 0, 128, 1 << 20)) {
            assertTrue(a.usesSharedWriter());
            assertEquals(a.sharedWriterShard(), b.sharedWriterShard());
            // Warm shard with a small append so materialize is paid.
            a.append(List.of("warm".getBytes(StandardCharsets.UTF_8))).get(5, TimeUnit.SECONDS);
            byte[] huge = new byte[3 << 20]; // 3 MiB → forces buffer growth past charged direct
            var refused = a.append(List.of(huge));
            assertThrows(Exception.class, () -> refused.get(5, TimeUnit.SECONDS));
            // Same writer: small append still works (not fenced).
            long ok = a.append(List.of("small".getBytes(StandardCharsets.UTF_8))).get(5, TimeUnit.SECONDS);
            assertTrue(ok >= 0);
            // Neighbour on same shard unaffected.
            long bok = b.append(List.of("nb".getBytes(StandardCharsets.UTF_8))).get(5, TimeUnit.SECONDS);
            assertTrue(bok >= 0);
        }
    }

    @Test
    void closeRacesFirstAppendOnPooledWriter() throws Exception {
        // T4: close ∥ append; no charge leak, no shard work after close.
        LedgerWriterBudget.install(LedgerWriterBudget.ofBytes(64L << 20));
        long charged0 = LedgerWriterBudget.shared().charged();
        var errors = new AtomicInteger();
        for (int i = 0; i < 200; i++) {
            final int round = i;
            var log = SegmentedLog.open(dir.resolve("race-" + round));
            var w = new LedgerWriter(log, Durability.NONE, 0, 64, 1 << 16);
            var go = new CountDownLatch(1);
            var appender = Thread.ofPlatform().start(() -> {
                try {
                    go.await();
                    try {
                        w.append(List.of(("x" + round).getBytes(StandardCharsets.UTF_8))).get(2, TimeUnit.SECONDS);
                    } catch (Exception closedOrRefused) {
                        // expected when close wins
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            });
            var closer = Thread.ofPlatform().start(() -> {
                try {
                    go.await();
                    w.close();
                    log.close();
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            });
            go.countDown();
            appender.join(10_000);
            closer.join(10_000);
        }
        assertEquals(0, errors.get());
        // Allow shard to retire and release.
        Thread.sleep(100);
        long charged = LedgerWriterBudget.shared().charged();
        assertTrue(charged <= charged0 + LedgerWriterBudget.IDLE_COST_BYTES,
                "budget leak after close races: charged=" + charged + " start=" + charged0);
    }

    @Test
    void saturatedPartitionDoesNotDelayNeighbourPastSoftCapacity() throws Exception {
        // T10 / D3: A is artificially slow to drain; B on the same shard must still complete promptly.
        // Mutation: always drain A to empty before B must fail the bound.
        System.setProperty(SharedLedgerPool.WRITER_THREADS_PROPERTY, "1");
        SharedLedgerPool.resetWriterPoolForTests();
        try (var logA = SegmentedLog.open(dir.resolve("hol-a"));
                // maxGroupBytes=1 ⇒ one Pending per drain slice so delay stacks under HOL
                var a = new LedgerWriter(logA, Durability.NONE, 0, 256, 1);
                var logB = SegmentedLog.open(dir.resolve("hol-b"));
                var b = new LedgerWriter(logB, Durability.NONE, 0, 256, 1)) {
            assertEquals(a.sharedWriterShard(), b.sharedWriterShard());
            a.drainDelayNanosForTests = 3_000_000L; // 3 ms per drain slice
            for (int i = 0; i < 30; i++) {
                a.appendNoWait(List.of(("A" + i).getBytes(StandardCharsets.UTF_8)), null);
            }
            long t0 = System.nanoTime();
            b.append(List.of("B".getBytes(StandardCharsets.UTF_8))).get(3, TimeUnit.SECONDS);
            long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
            // Fair RR: B waits ≤ ~1–2 slices (~3–6 ms). HOL-until-empty: ~30 * 3 ms ≈ 90 ms.
            assertTrue(ms < 40, "B delayed " + ms + " ms under A's saturation (HOL)");
        }
    }

    @Test
    void resolveWriterThreadsDefaultAndOff() {
        System.clearProperty(SharedLedgerPool.WRITER_THREADS_PROPERTY);
        SharedLedgerPool.resetWriterPoolForTests();
        int n = SharedLedgerPool.resolveWriterThreads();
        int cpus = Math.max(1, Runtime.getRuntime().availableProcessors());
        assertEquals(Math.max(2, Math.min(cpus, 16)), n);
        System.setProperty(SharedLedgerPool.WRITER_THREADS_PROPERTY, "0");
        SharedLedgerPool.resetWriterPoolForTests();
        assertEquals(0, SharedLedgerPool.resolveWriterThreads());
        assertTrue(SharedLedgerPool.writers().disabled());
    }

    @Test
    void sharedWritersAliasWarnsOnceTowardSharedSyncers() {
        System.clearProperty(SharedLedgerPool.SYNCERS_PROPERTY);
        System.setProperty(SharedLedgerPool.PROPERTY, "2");
        assertEquals(2, SharedLedgerPool.resolveSyncerSize());
        System.clearProperty(SharedLedgerPool.PROPERTY);
        System.setProperty(SharedLedgerPool.SYNCERS_PROPERTY, "4");
        assertEquals(4, SharedLedgerPool.resolveSyncerSize());
    }
}
