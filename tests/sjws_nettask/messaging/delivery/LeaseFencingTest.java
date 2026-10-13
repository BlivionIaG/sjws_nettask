package sjws_nettask.messaging.delivery;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sjws_nettask.messaging.ledger.Durability;

/** The delivery attempt is a lease token: a worker whose lease expired cannot settle the re-leased message. */
class LeaseFencingTest {

    @TempDir Path dir;
    final AtomicLong now = new AtomicLong(1_000_000);
    static final RetryPolicy POLICY = new RetryPolicy(5, 10, 100);

    Partition open() throws IOException {
        var p = Partition.open(dir, "t", POLICY, now::get, Durability.ASYNC, 64 * 1024);
        p.snapshotPolicy(0, 0, 10_000);
        return p;
    }

    /** Worker A leases attempt 1, its lease expires, worker B re-leases as attempt 2. Returns {offset}. */
    long staleAndCurrent(Partition p) {
        p.subscribe("g", true);
        long off = p.produce(0, 0, "task".getBytes(StandardCharsets.UTF_8)).offset();
        var a = p.poll("g", "A", 1, 1_000).getFirst();
        assertEquals(1, a.attempt());
        now.addAndGet(1_100); // A's lease expires (+ backoff)
        var b = p.poll("g", "B", 1, 60_000).getFirst();
        assertEquals(off, b.offset());
        assertEquals(2, b.attempt());
        return off;
    }

    @Test
    void staleAckIsRefusedAndCurrentHolderSucceeds() throws Exception {
        try (var p = open()) {
            long off = staleAndCurrent(p);
            assertEquals(LeaseResult.STALE_ATTEMPT, p.ack("g", off, 1));
            assertEquals(LeaseResult.STALE_ATTEMPT, p.ack("g", off, 3), "a future attempt is not current either");
            var before = p.counts();
            assertEquals(LeaseResult.OK, p.ack("g", off, 2));
            assertEquals(LeaseResult.NOT_IN_FLIGHT, p.ack("g", off, 2), "duplicate ack");
            assertEquals(LeaseResult.NOT_IN_FLIGHT, p.ack("g", off, 1));
            assertTrue(p.counts().nextOffset() > before.nextOffset());
        }
    }

    @Test
    void staleNackAndRejectAreRefusedAndWriteNothing() throws Exception {
        try (var p = open()) {
            long off = staleAndCurrent(p);
            long end = p.counts().nextOffset();
            assertEquals(LeaseResult.STALE_ATTEMPT, p.nack("g", off, 1, 0));
            assertEquals(LeaseResult.STALE_ATTEMPT, p.reject("g", off, 1, "stale"));
            assertEquals(end, p.counts().nextOffset(), "refused calls append no ledger record");
            assertEquals(0, p.deadLetterQueue().counts().nextOffset(), "stale reject did not dead-letter");
            assertEquals(LeaseResult.OK, p.nack("g", off, 2, 0));
            now.addAndGet(1_000);
            var c = p.poll("g", "C", 1, 60_000).getFirst();
            assertEquals(3, c.attempt());
            assertEquals(LeaseResult.STALE_ATTEMPT, p.reject("g", off, 2, "B is stale now"));
            assertEquals(LeaseResult.OK, p.reject("g", off, 3, "poison"));
            assertEquals(LeaseResult.NOT_IN_FLIGHT, p.nack("g", off, 3, 0));
            assertTrue(p.deadLetterQueue().counts().nextOffset() > 0);
        }
    }

    @Test
    void fencingSurvivesCrashReplay() throws Exception {
        long off;
        String before;
        try (var p = open()) {
            off = staleAndCurrent(p);
            p.produce(0, 0, "durable-barrier".getBytes(StandardCharsets.UTF_8), Durability.GROUP).durable().get();
            before = p.canonicalState();
        } // close = crash point after the barrier; the lease table is rebuilt by replay
        try (var q = open()) {
            assertEquals(before, q.canonicalState(), "replay exact");
            assertEquals(LeaseResult.STALE_ATTEMPT, q.ack("g", off, 1));
            assertEquals(LeaseResult.STALE_ATTEMPT, q.nack("g", off, 1, 0));
            assertEquals(LeaseResult.STALE_ATTEMPT, q.reject("g", off, 1, "stale"));
            assertEquals(LeaseResult.OK, q.ack("g", off, 2, Durability.GROUP).result());
        }
        try (var r = open()) {
            assertEquals(LeaseResult.NOT_IN_FLIGHT, r.ack("g", off, 2), "the accepted ack is durable");
        }
    }

    @Test
    void unfencedOverloadsKeepTheirOldBehaviour() throws Exception {
        try (var p = open()) {
            long off = staleAndCurrent(p);
            assertTrue(p.ack("g", off), "unfenced ack settles whichever lease is current");
            assertFalse(p.ack("g", off));
            assertNull(p.ack("g", off, (Durability) null));
            assertFalse(p.nack("g", off, 0));
            assertFalse(p.reject("g", off, "x"));
            var r = p.ack("g", off, 1, null);
            assertEquals(LeaseResult.NOT_IN_FLIGHT, r.result());
            assertNull(r.durable());
            assertFalse(LeaseResult.STALE_ATTEMPT.ok());
        }
    }

    @Test
    void expiredButNotReleasedLeaseCanStillBeSettledByItsHolder() throws Exception {
        try (var p = open()) {
            p.subscribe("g", true);
            long off = p.produce(0, 0, "t".getBytes(StandardCharsets.UTF_8)).offset();
            var a = p.poll("g", "A", 1, 1_000).getFirst();
            now.addAndGet(5_000); // expired, but nobody re-leased it: A is still the latest holder
            assertEquals(LeaseResult.OK, p.ack("g", off, a.attempt()));
        }
    }
}
