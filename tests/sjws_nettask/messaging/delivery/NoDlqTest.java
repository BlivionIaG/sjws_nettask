package sjws_nettask.messaging.delivery;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sjws_nettask.messaging.ledger.Durability;
import sjws_nettask.messaging.ledger.SegmentedLog;

/** A topic opened with {@code deadLetterQueue = false}: no {@code .dlq} partition; exhausted/rejected messages are dropped in place. */
class NoDlqTest {

    @TempDir Path dir;
    final AtomicLong now = new AtomicLong(1_000_000);

    Partition open(boolean dlq) throws Exception {
        return Partition.open(dir, "t", new RetryPolicy(2, 10, 10), now::get, Durability.ASYNC,
                SegmentedLog.DEFAULT_SEGMENT_BYTES, dlq);
    }

    @Test
    void exhaustedAndRejectedMessagesAreDroppedWithoutDlq() throws Exception {
        String before;
        long a, b;
        try (var p = open(false)) {
            assertNull(p.deadLetterQueue());
            assertNull(new LedgerInspector(p).deadLetterQueue());
            assertFalse(Files.exists(dir.resolve("t.dlq")), "no DLQ directory is created");
            p.subscribe("g", true);
            a = p.produce(0, 0, new byte[] {1}).offset();
            b = p.produce(0, 0, new byte[] {2}).offset();
            var d = p.poll("g", "w", 2, 1_000);
            assertEquals(LeaseResult.OK, p.reject("g", b, d.get(1).attempt(), "poison"));
            assertEquals(LeaseResult.OK, p.nack("g", a, d.get(0).attempt(), 0));
            now.addAndGet(100);
            var d2 = p.poll("g", "w", 2, 1_000);
            assertEquals(1, d2.size());
            assertEquals(2, d2.getFirst().attempt());
            assertEquals(LeaseResult.OK, p.nack("g", a, 2, 0)); // max attempts reached: dropped
            now.addAndGet(100);
            assertTrue(p.poll("g", "w", 10, 1_000).isEmpty(), "dropped messages are never redelivered");
            assertEquals(2, p.counts().groups().get("g").counts().deadLettered());
            before = p.canonicalState();
        }
        try (var p = open(false)) {
            assertEquals(before, p.canonicalState(), "replay is exact");
            assertTrue(p.poll("g", "w", 10, 1_000).isEmpty());
        }
    }

    @Test
    void leaseExpiryAtMaxAttemptsDropsAndDefaultStillHasDlq() throws Exception {
        try (var p = open(false)) {
            p.subscribe("g", true);
            p.produce(0, 0, new byte[] {1});
            p.poll("g", "w", 1, 10);
            now.addAndGet(100);
            p.poll("g", "w", 1, 10);
            now.addAndGet(100);
            assertTrue(p.poll("g", "w", 1, 10).isEmpty());
            assertEquals(1, p.counts().groups().get("g").counts().deadLettered());
        }
        try (var p = Partition.open(dir.resolve("x"), "t", RetryPolicy.DEFAULT, now::get)) {
            assertNotNull(p.deadLetterQueue(), "the default open keeps its DLQ");
        }
    }
}
