package sjws_nettask.messaging.ledger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** #333: fail-closed writer memory budget and lazy queue/direct materialization. */
class LedgerWriterBudgetTest {

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
        String prev = System.getProperty(LedgerWriterBudget.FRACTION_PROPERTY);
        try {
            System.setProperty(LedgerWriterBudget.FRACTION_PROPERTY, "2.5");
            LedgerWriterBudget.resetForTests();
            assertThrows(IllegalArgumentException.class, LedgerWriterBudget::shared);
        } finally {
            if (prev == null) System.clearProperty(LedgerWriterBudget.FRACTION_PROPERTY);
            else System.setProperty(LedgerWriterBudget.FRACTION_PROPERTY, prev);
            LedgerWriterBudget.resetForTests();
        }
    }

}
