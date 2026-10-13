package sjws_nettask.messaging.delivery;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sjws_nettask.messaging.ledger.Durability;

/** {@code ack(group, offsets[], attempts[], durability)}: per-item fencing, one ledger batch, exact replay. */
class BatchAckTest {

    @TempDir Path dir;
    final AtomicLong now = new AtomicLong(1_000_000);

    Partition open() throws IOException {
        var p = Partition.open(dir, "t", new RetryPolicy(5, 10, 100), now::get, Durability.ASYNC, 64 * 1024);
        p.snapshotPolicy(0, 0, 10_000);
        return p;
    }

    @Test
    void mixedBatchSettlesOnlyCurrentLeasesInOneWrite() throws Exception {
        String before;
        try (var p = open()) {
            p.subscribe("g", true);
            long last = 0;
            for (int i = 0; i < 4; i++) last = p.produce(0, 0, new byte[] {(byte) i}).offset();
            var d = p.poll("g", "w", 3, 60_000); // first three leased at attempt 1; last stays queued
            assertEquals(3, d.size());
            long end = p.counts().nextOffset();
            var r = p.ack("g", new long[] {d.get(0).offset(), d.get(1).offset(), d.get(1).offset(), last, d.get(2).offset()},
                    new int[] {1, 2, 1, 1, 1}, Durability.EACH);
            assertEquals(List.of(LeaseResult.OK, LeaseResult.STALE_ATTEMPT, LeaseResult.OK, LeaseResult.NOT_IN_FLIGHT,
                    LeaseResult.OK), r.results());
            assertEquals(end, r.durable().get(), "future yields the batch's first offset");
            assertEquals(end + 3, p.counts().nextOffset(), "exactly the three accepted ACKs were written");
            assertEquals(LeaseResult.NOT_IN_FLIGHT, p.ack("g", d.get(0).offset(), 1));
            before = p.canonicalState();
        }
        try (var p = open()) {
            assertEquals(before, p.canonicalState(), "replay is exact");
            long q = p.poll("g", "w", 10, 60_000).getFirst().offset();
            assertTrue(q > 0);
        }
    }

    @Test
    void duplicateInBatchAcksOnceAndNothingAcceptedWritesNothing() throws Exception {
        try (var p = open()) {
            p.subscribe("g", true);
            p.produce(0, 0, new byte[] {1});
            long off = p.poll("g", "w", 1, 60_000).getFirst().offset();
            long end = p.counts().nextOffset();
            var none = p.ack("g", new long[] {off, 99}, new int[] {7, 1}, null);
            assertEquals(List.of(LeaseResult.STALE_ATTEMPT, LeaseResult.NOT_IN_FLIGHT), none.results());
            assertNull(none.durable());
            assertEquals(end, p.counts().nextOffset());
            var dup = p.ack("g", new long[] {off, off}, new int[] {1, 1}, null);
            assertEquals(List.of(LeaseResult.OK, LeaseResult.NOT_IN_FLIGHT), dup.results());
            assertEquals(end + 1, p.counts().nextOffset());
            assertThrows(IllegalArgumentException.class, () -> p.ack("g", new long[] {1}, new int[0], null));
        }
    }

    @Test
    void largeBatchUsesSetDedupAndAnyAttempt() throws Exception {
        try (var p = open()) {
            p.subscribe("g", true);
            int n = 100;
            for (int i = 0; i < n; i++) p.produce(0, 0, new byte[] {(byte) i});
            var d = p.poll("g", "w", n, 60_000);
            var offs = new long[n + 1];
            var atts = new int[n + 1];
            for (int i = 0; i < n; i++) { offs[i] = d.get(i).offset(); atts[i] = Partition.ANY_ATTEMPT; }
            offs[n] = offs[0];
            atts[n] = 1;
            var r = p.ack("g", offs, atts, null);
            assertEquals(n, r.results().stream().filter(LeaseResult.OK::equals).count());
            assertEquals(LeaseResult.NOT_IN_FLIGHT, r.results().get(n));
            r.durable().get();
            assertTrue(p.poll("g", "w", 1, 60_000).isEmpty());
        }
    }
}
