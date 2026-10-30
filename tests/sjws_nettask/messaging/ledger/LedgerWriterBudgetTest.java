package sjws_nettask.messaging.ledger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** #333: fail-closed writer memory budget and lazy queue/direct materialization. */
class LedgerWriterBudgetTest {
    /** #336: this suite asserts per-writer materialize charging; force dedicated writers. */
    @BeforeAll
    static void dedicatedWritersForBudgetSuite() {
        System.setProperty(SharedLedgerPool.WRITER_THREADS_PROPERTY, "0");
        SharedLedgerPool.resetWriterPoolForTests();
    }

    @AfterAll
    static void restoreWriterPoolProperty() {
        System.clearProperty(SharedLedgerPool.WRITER_THREADS_PROPERTY);
        SharedLedgerPool.resetWriterPoolForTests();
    }


    @TempDir Path dir;

    @BeforeEach
    @AfterEach
    void resetBudget() {
        LedgerWriterBudget.resetForTests();
    }

    @Test
    void idleWriterDoesNotMaterializeQueueOrDirectBuffer() throws Exception {
        LedgerWriterBudget.install(LedgerWriterBudget.ofBytes(0)); // unlimited
        try (var log = SegmentedLog.open(dir.resolve("idle"))) {
            var w = new LedgerWriter(log, Durability.NONE);
            try {
                assertFalse(w.materialized(), "idle must not allocate the queue");
                assertEquals(LedgerWriterBudget.IDLE_COST_BYTES, LedgerWriterBudget.shared().charged());
            } finally {
                w.close();
            }
            assertEquals(0, LedgerWriterBudget.shared().charged());
            assertEquals(0, LedgerWriterBudget.shared().writers());
        }
    }

    @Test
    void firstAppendMaterializesQueueAndKeepsProduceWorking() throws Exception {
        LedgerWriterBudget.install(LedgerWriterBudget.ofBytes(0));
        try (var log = SegmentedLog.open(dir.resolve("m"))) {
            var w = new LedgerWriter(log, Durability.NONE, 0, 128, 1 << 16);
            try {
                assertFalse(w.materialized());
                long base = w.append(List.of(new byte[] {1, 2, 3}), null).get();
                assertEquals(0, base);
                assertTrue(w.materialized());
                long full = LedgerWriterBudget.fullCostBytes(128);
                assertEquals(full, LedgerWriterBudget.shared().charged());
            } finally {
                w.close();
            }
        }
    }

    @Test
    void acquireIdleFailsClosedWhenBudgetTooSmall() {
        LedgerWriterBudget.install(LedgerWriterBudget.ofBytes(LedgerWriterBudget.IDLE_COST_BYTES));
        // First idle ok
        assertDoesNotThrow(() -> LedgerWriterBudget.shared().acquireIdle());
        // Second refused
        var e = assertThrows(LedgerBudgetExhaustedException.class, () -> LedgerWriterBudget.shared().acquireIdle());
        assertTrue(e.getMessage().contains("exhausted"), e.getMessage());
        assertEquals(1, LedgerWriterBudget.shared().refusals());
        LedgerWriterBudget.shared().release(LedgerWriterBudget.IDLE_COST_BYTES);
    }

    @Test
    void partitionOpenPastBudgetLeavesNoOpenWriterAndExistingStayHealthy() throws Exception {
        // Room for two fully materialized writers (produce must succeed); a third idle open is refused.
        long one = LedgerWriterBudget.fullCostBytes(LedgerWriter.DEFAULT_QUEUE);
        LedgerWriterBudget.install(LedgerWriterBudget.ofBytes(one * 2));
        var p1 = sjws_nettask.messaging.delivery.Partition.openWithoutDlq(dir.resolve("a"), "t1",
                sjws_nettask.messaging.delivery.RetryPolicy.DEFAULT, System::currentTimeMillis);
        try {
            var p2 = sjws_nettask.messaging.delivery.Partition.openWithoutDlq(dir.resolve("b"), "t2",
                    sjws_nettask.messaging.delivery.RetryPolicy.DEFAULT, System::currentTimeMillis);
            try {
                assertEquals(2, LedgerWriterBudget.shared().writers());
                // Materialize both first so the budget is at the two-writer full-cost ceiling.
                var r0 = p1.produce(0, 0, new byte[] {1});
                assertTrue(r0.durable().get(5, java.util.concurrent.TimeUnit.SECONDS) >= 0);
                var r1 = p2.produce(0, 0, new byte[] {2});
                assertTrue(r1.durable().get(5, java.util.concurrent.TimeUnit.SECONDS) >= 0);
                assertEquals(one * 2, LedgerWriterBudget.shared().charged());
                var refused = assertThrows(LedgerBudgetExhaustedException.class,
                        () -> sjws_nettask.messaging.delivery.Partition.openWithoutDlq(dir.resolve("c"), "t3",
                                sjws_nettask.messaging.delivery.RetryPolicy.DEFAULT, System::currentTimeMillis));
                assertTrue(refused.getMessage().contains("exhausted"), refused.getMessage());
                assertEquals(2, LedgerWriterBudget.shared().writers(), "refused open must not keep a charge");
                // Existing partitions still produce and consume after the refusal
                var r = p1.produce(0, 0, new byte[] {9});
                assertTrue(r.durable().get(5, java.util.concurrent.TimeUnit.SECONDS) >= 0);
                assertTrue(p1.subscribe("g", true));
                var got = p1.poll("g", "c", 2, 5_000);
                assertTrue(got.size() >= 1);
                var r2 = p2.produce(0, 0, new byte[] {8});
                assertTrue(r2.durable().get(5, java.util.concurrent.TimeUnit.SECONDS) >= 0);
            } finally {
                p2.close();
            }
        } finally {
            p1.close();
        }
        assertEquals(0, LedgerWriterBudget.shared().writers());
        assertEquals(0, LedgerWriterBudget.shared().charged());
    }

    @Test
    void materializeFailsClosedWithoutLeakingQueue() throws Exception {
        // Idle fits; materialize of DEFAULT_QUEUE does not
        long idleOnly = LedgerWriterBudget.IDLE_COST_BYTES * 2;
        LedgerWriterBudget.install(LedgerWriterBudget.ofBytes(idleOnly));
        try (var log = SegmentedLog.open(dir.resolve("mat"))) {
            var w = new LedgerWriter(log, Durability.NONE); // DEFAULT_QUEUE materialize is huge
            try {
                assertFalse(w.materialized());
                var e = assertThrows(LedgerBudgetExhaustedException.class,
                        () -> w.append(List.of(new byte[] {1}), null));
                assertTrue(e.getMessage().contains("exhausted"), e.getMessage());
                assertFalse(w.materialized(), "failed materialize must not publish a queue");
                assertEquals(LedgerWriterBudget.IDLE_COST_BYTES, LedgerWriterBudget.shared().charged());
            } finally {
                w.close();
            }
        }
    }

    @Test
    void resolveRejectsMalformedFraction() {
        withProps(java.util.Map.of(LedgerWriterBudget.FRACTION_PROPERTY, "2.5"), () -> {
            LedgerWriterBudget.resetForTests();
            assertThrows(IllegalArgumentException.class, LedgerWriterBudget::shared);
        });
    }

    @Test
    void resolveBytesOffAndPositiveAndRejectsTiny() {
        withProps(java.util.Map.of(LedgerWriterBudget.BYTES_PROPERTY, "off"), () -> {
            LedgerWriterBudget.resetForTests();
            assertTrue(LedgerWriterBudget.shared().unlimited());
        });
        withProps(java.util.Map.of(LedgerWriterBudget.BYTES_PROPERTY, "false"), () -> {
            LedgerWriterBudget.resetForTests();
            assertTrue(LedgerWriterBudget.shared().unlimited());
        });
        withProps(java.util.Map.of(LedgerWriterBudget.BYTES_PROPERTY, "unlimited"), () -> {
            LedgerWriterBudget.resetForTests();
            assertTrue(LedgerWriterBudget.shared().unlimited());
        });
        withProps(java.util.Map.of(LedgerWriterBudget.BYTES_PROPERTY, "0"), () -> {
            LedgerWriterBudget.resetForTests();
            assertTrue(LedgerWriterBudget.shared().unlimited());
        });
        long ok = LedgerWriterBudget.IDLE_COST_BYTES * 10;
        withProps(java.util.Map.of(LedgerWriterBudget.BYTES_PROPERTY, Long.toString(ok)), () -> {
            LedgerWriterBudget.resetForTests();
            var b = LedgerWriterBudget.shared();
            assertFalse(b.unlimited());
            assertEquals(ok, b.budget());
        });
        withProps(java.util.Map.of(LedgerWriterBudget.BYTES_PROPERTY, "1"), () -> {
            LedgerWriterBudget.resetForTests();
            assertThrows(IllegalArgumentException.class, LedgerWriterBudget::shared);
        });
        withProps(java.util.Map.of(LedgerWriterBudget.BYTES_PROPERTY, "nope"), () -> {
            LedgerWriterBudget.resetForTests();
            assertThrows(IllegalArgumentException.class, LedgerWriterBudget::shared);
        });
        withProps(java.util.Map.of(LedgerWriterBudget.BYTES_PROPERTY, "-5"), () -> {
            LedgerWriterBudget.resetForTests();
            assertThrows(IllegalArgumentException.class, LedgerWriterBudget::shared);
        });
    }

    @Test
    void resolveFractionDefaultAndRejectsBad() {
        withProps(java.util.Map.of(), () -> {
            System.clearProperty(LedgerWriterBudget.BYTES_PROPERTY);
            System.clearProperty(LedgerWriterBudget.FRACTION_PROPERTY);
            LedgerWriterBudget.resetForTests();
            var b = LedgerWriterBudget.shared();
            assertFalse(b.unlimited());
            assertTrue(b.budget() >= LedgerWriterBudget.IDLE_COST_BYTES);
            assertTrue(b.source().contains("heapFraction"));
        });
        withProps(java.util.Map.of(LedgerWriterBudget.FRACTION_PROPERTY, "0"), () -> {
            LedgerWriterBudget.resetForTests();
            assertThrows(IllegalArgumentException.class, LedgerWriterBudget::shared);
        });
        withProps(java.util.Map.of(LedgerWriterBudget.FRACTION_PROPERTY, "not-a-double"), () -> {
            LedgerWriterBudget.resetForTests();
            assertThrows(IllegalArgumentException.class, LedgerWriterBudget::shared);
        });
    }

    @Test
    void ofBytesAndFullCostRejectBadArgs() {
        assertThrows(IllegalArgumentException.class, () -> LedgerWriterBudget.ofBytes(-1));
        assertThrows(IllegalArgumentException.class, () -> LedgerWriterBudget.fullCostBytes(0));
        LedgerWriterBudget.install(LedgerWriterBudget.ofBytes(0));
        assertThrows(IllegalArgumentException.class, () -> LedgerWriterBudget.shared().releaseBytes(-1));
    }

    @Test
    void releaseBytesZeroIsNoOpAndInstallRoundTrip() {
        LedgerWriterBudget.install(LedgerWriterBudget.ofBytes(0));
        long before = LedgerWriterBudget.shared().charged();
        LedgerWriterBudget.shared().releaseBytes(0);
        assertEquals(before, LedgerWriterBudget.shared().charged());
        var other = LedgerWriterBudget.ofBytes(LedgerWriterBudget.IDLE_COST_BYTES * 4);
        LedgerWriterBudget.install(other);
        assertSame(other, LedgerWriterBudget.shared());
    }

    private static void withProps(java.util.Map<String, String> set, Runnable body) {
        var prevBytes = System.getProperty(LedgerWriterBudget.BYTES_PROPERTY);
        var prevFrac = System.getProperty(LedgerWriterBudget.FRACTION_PROPERTY);
        try {
            System.clearProperty(LedgerWriterBudget.BYTES_PROPERTY);
            System.clearProperty(LedgerWriterBudget.FRACTION_PROPERTY);
            set.forEach(System::setProperty);
            body.run();
        } finally {
            if (prevBytes == null) System.clearProperty(LedgerWriterBudget.BYTES_PROPERTY);
            else System.setProperty(LedgerWriterBudget.BYTES_PROPERTY, prevBytes);
            if (prevFrac == null) System.clearProperty(LedgerWriterBudget.FRACTION_PROPERTY);
            else System.setProperty(LedgerWriterBudget.FRACTION_PROPERTY, prevFrac);
            LedgerWriterBudget.resetForTests();
        }
    }


    /**
     * #335 Agent Swarm: {@code close} must not race first-append {@code materialize} — that orphaned the
     * writer thread and leaked the materialize budget charge. Mutation: flipping {@code closed} outside
     * {@code materializeLock} again makes this fail (live {@code ledger-writer-*} threads or charged &gt; 0).
     */
    @Test
    void closeRacesFirstAppendDoesNotLeakThreadOrBudget() throws Exception {
        LedgerWriterBudget.install(LedgerWriterBudget.ofBytes(0));
        // Dedicated syncer per writer so close joins it; shared pool is out of scope for this race.
        int iters = 3_000;
        var appendWins = new AtomicInteger();
        var closedWins = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            for (int i = 0; i < iters; i++) {
                Path d = dir.resolve("race-" + i);
                var log = SegmentedLog.open(d);
                var w = new LedgerWriter(log, Durability.NONE, 0, 64, 1 << 16);
                var gate = new CyclicBarrier(2);
                var done = new CountDownLatch(2);
                pool.execute(() -> {
                    try {
                        gate.await(5, TimeUnit.SECONDS);
                        var f = w.appendNoWait(List.of(new byte[] {1}), Durability.NONE);
                        try {
                            f.get(5, TimeUnit.SECONDS);
                            appendWins.incrementAndGet();
                        } catch (Exception ex) {
                            closedWins.incrementAndGet(); // closed / rejected after race
                        }
                    } catch (IllegalStateException e) {
                        closedWins.incrementAndGet(); // materialize saw closed
                    } catch (Exception e) {
                        closedWins.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
                pool.execute(() -> {
                    try {
                        gate.await(5, TimeUnit.SECONDS);
                        w.close();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        done.countDown();
                    }
                });
                assertTrue(done.await(60, TimeUnit.SECONDS), "iter " + i + " timed out");
                try { w.close(); } catch (Exception ignored) {}
                try { log.close(); } catch (Exception ignored) {}
            }
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));
        }
        long liveWriters = Thread.getAllStackTraces().keySet().stream()
                .filter(th -> th.isAlive() && th.getName() != null && th.getName().startsWith("ledger-writer-race-"))
                .count();
        assertEquals(0, liveWriters, "orphaned ledger-writer threads after close∥first-append stress");
        assertEquals(0, LedgerWriterBudget.shared().charged(), "budget charge leaked");
        assertEquals(0, LedgerWriterBudget.shared().writers(), "writer count leaked");
        assertTrue(appendWins.get() + closedWins.get() >= iters, "stress made no progress");
    }


    /** #335 N1: ctor failure after idle acquire must release the charge. */
    @Test
    void constructorFailureReleasesIdleCharge() throws Exception {
        LedgerWriterBudget.install(LedgerWriterBudget.ofBytes(0));
        LedgerBudgetTestHooks.setCtorFault(new RuntimeException("boom-after-idle"));
        try (var log = SegmentedLog.open(dir.resolve("ctor-fail"))) {
            var boom = assertThrows(RuntimeException.class, () -> new LedgerWriter(log, Durability.NONE));
            assertEquals("boom-after-idle", boom.getMessage());
        } finally {
            LedgerBudgetTestHooks.clearCtorFault();
        }
        assertEquals(0, LedgerWriterBudget.shared().charged(), "idle charge must roll back on ctor failure");
        assertEquals(0, LedgerWriterBudget.shared().writers());
    }

    /**
     * Materialize that fails after acquireMaterialize must releaseBytes and leave the writer idle.
     * Mutation: drop {@code budget.releaseBytes(extra)} in the materialize catch → charged stays at full cost.
     * Fault is per-writer (#335 N3), not a static that can leak across tests.
     */
    @Test
    void materializeFailureAfterAcquireRollsBackCharge() throws Exception {
        LedgerWriterBudget.install(LedgerWriterBudget.ofBytes(0));
        try (var log = SegmentedLog.open(dir.resolve("mat-rollback"))) {
            var w = new LedgerWriter(log, Durability.NONE, 0, 128, 1 << 16);
            try {
                LedgerWriterFaults.setMaterializeFault(w, new RuntimeException("boom-after-acquire"));
                assertEquals(LedgerWriterBudget.IDLE_COST_BYTES, LedgerWriterBudget.shared().charged());
                var boom = assertThrows(RuntimeException.class,
                        () -> w.append(List.of(new byte[] {1}), null));
                assertEquals("boom-after-acquire", boom.getMessage());
                assertFalse(w.materialized());
                assertEquals(LedgerWriterBudget.IDLE_COST_BYTES, LedgerWriterBudget.shared().charged(),
                        "materialize delta must not stick after rollback");
            } finally {
                LedgerWriterFaults.setMaterializeFault(w, null);
                w.close();
            }
        }
        assertEquals(0, LedgerWriterBudget.shared().charged());
    }

    /**
     * #335 B3: growing the direct buffer past the materialize charge must acquire the delta (fail-closed).
     * Mutation: skip {@code budget.acquireExtra} before allocateDirect → charged stays at full while capacity &gt; 1 MiB.
     */
    @Test
    void directBufferGrowthChargesBudgetFailClosed() throws Exception {
        int q = 128;
        long full = LedgerWriterBudget.fullCostBytes(q);
        LedgerWriterBudget.install(LedgerWriterBudget.ofBytes(full)); // no room for +3 MiB growth
        try (var log = SegmentedLog.open(dir.resolve("direct-grow"))) {
            var w = new LedgerWriter(log, Durability.NONE, 0, q, 8 << 20);
            try {
                byte[] big = new byte[2 << 20];
                try {
                    w.append(List.of(big), Durability.NONE).get(5, TimeUnit.SECONDS);
                    fail("expected ledger budget exhaustion on direct growth");
                } catch (Exception e) {
                    Throwable c = e;
                    while (c.getCause() != null && !(c instanceof LedgerBudgetExhaustedException)) c = c.getCause();
                    assertTrue(c instanceof LedgerBudgetExhaustedException, "got " + e);
                    assertTrue(c.getMessage().contains("direct buffer growth")
                            || c.getMessage().contains("exhausted"), c.getMessage());
                }
                // Charge must not include an uncharged 4 MiB buffer — either still at full (failed before grow
                // completed) or idle+partial; never silent over-allocate without acquire.
                assertTrue(LedgerWriterBudget.shared().charged() <= full + LedgerWriterBudget.DIRECT_BUF_BYTES,
                        "charged=" + LedgerWriterBudget.shared().charged());
            } finally {
                w.close();
            }
        }
        assertEquals(0, LedgerWriterBudget.shared().charged());
    }

    /**
     * #335 B3 positive path: with room in the budget, a 2 MiB payload grows the direct buffer and charges the delta.
     */
    @Test
    void directBufferGrowthChargesDeltaWhenBudgetAllows() throws Exception {
        int q = 128;
        long full = LedgerWriterBudget.fullCostBytes(q);
        long growth = 3L << 20; // 4 MiB cap - 1 MiB baseline
        LedgerWriterBudget.install(LedgerWriterBudget.ofBytes(full + growth));
        try (var log = SegmentedLog.open(dir.resolve("direct-ok"))) {
            var w = new LedgerWriter(log, Durability.NONE, 0, q, 8 << 20);
            try {
                byte[] big = new byte[2 << 20];
                long base = w.append(List.of(big), Durability.NONE).get(5, TimeUnit.SECONDS);
                assertEquals(0, base);
                assertEquals(full + growth, LedgerWriterBudget.shared().charged());
            } finally {
                w.close();
            }
        }
        assertEquals(0, LedgerWriterBudget.shared().charged());
    }

    /** #335 N5: {@code budget.bytes=off} resolves unlimited at first class use / shared(). */
    @Test
    void offPropertyResolvesUnlimitedBudget() {
        withProps(java.util.Map.of(LedgerWriterBudget.BYTES_PROPERTY, "off"), () -> {
            LedgerWriterBudget.resetForTests();
            var b = LedgerWriterBudget.shared();
            assertTrue(b.unlimited());
            assertEquals(0, b.budget());
        });
    }

    /**
     * #335 Security B1: a direct-growth budget refusal must fail only that batch (503), not fence the writer.
     * Disk was never touched, matching materialize refusal. Mutation: catching LedgerBudgetExhaustedException
     * in the generic writeGroup fence path (failure=e) makes the follow-up small append fail.
     */
    @Test
    void growthRefusalDoesNotFenceWriter() throws Exception {
        int q = 128;
        long full = LedgerWriterBudget.fullCostBytes(q);
        LedgerWriterBudget.install(LedgerWriterBudget.ofBytes(full)); // room for materialize, not +3 MiB growth
        try (var log = SegmentedLog.open(dir.resolve("nofence"))) {
            var w = new LedgerWriter(log, Durability.NONE, 0, q, 8 << 20);
            try {
                assertEquals(0L, w.append(List.of(new byte[] {1}), Durability.NONE).get(5, TimeUnit.SECONDS));
                assertEquals(full, LedgerWriterBudget.shared().charged());
                byte[] big = new byte[2 << 20];
                try {
                    w.append(List.of(big), Durability.NONE).get(5, TimeUnit.SECONDS);
                    fail("expected ledger budget exhaustion on direct growth");
                } catch (Exception e) {
                    Throwable c = e;
                    while (c.getCause() != null && !(c instanceof LedgerBudgetExhaustedException)) c = c.getCause();
                    assertTrue(c instanceof LedgerBudgetExhaustedException, "got " + e);
                }
                // Still at full materialize charge — growth never stuck; writer not fenced.
                assertEquals(full, LedgerWriterBudget.shared().charged());
                long next = w.append(List.of(new byte[] {2}), Durability.NONE).get(5, TimeUnit.SECONDS);
                assertEquals(1L, next, "small append after growth refusal must succeed (no fence)");
                assertEquals(full, LedgerWriterBudget.shared().charged());
            } finally {
                w.close();
            }
        }
        assertEquals(0, LedgerWriterBudget.shared().charged());
    }

    /**
     * #335 N2: if allocateDirect fails after acquireExtra, the extra charge must roll back.
     * Mutation: drop the releaseBytes in write()'s allocateDirect catch → charged stays above full.
     */
    @Test
    void allocateDirectFailureReleasesExtraCharge() throws Exception {
        int q = 128;
        long full = LedgerWriterBudget.fullCostBytes(q);
        long growth = 3L << 20;
        LedgerWriterBudget.install(LedgerWriterBudget.ofBytes(full + growth));
        try (var log = SegmentedLog.open(dir.resolve("alloc-fail"))) {
            var w = new LedgerWriter(log, Durability.NONE, 0, q, 8 << 20);
            try {
                assertEquals(0L, w.append(List.of(new byte[] {1}), Durability.NONE).get(5, TimeUnit.SECONDS));
                assertEquals(full, LedgerWriterBudget.shared().charged());
                LedgerWriterFaults.setAllocateDirectFault(w, new RuntimeException("boom-allocateDirect"));
                byte[] big = new byte[2 << 20];
                try {
                    w.append(List.of(big), Durability.NONE).get(5, TimeUnit.SECONDS);
                    fail("expected allocateDirect fault");
                } catch (Exception e) {
                    Throwable c = e;
                    while (c.getCause() != null && !"boom-allocateDirect".equals(c.getMessage())) c = c.getCause();
                    assertEquals("boom-allocateDirect", c.getMessage(), "got " + e);
                }
                assertEquals(full, LedgerWriterBudget.shared().charged(),
                        "extra charge must roll back when allocateDirect fails after acquireExtra");
            } finally {
                LedgerWriterFaults.setAllocateDirectFault(w, null);
                w.close();
            }
        }
        assertEquals(0, LedgerWriterBudget.shared().charged());
    }

}
