package sjws_nettask.messaging.delivery;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sjws_nettask.messaging.ledger.Durability;

/** Heartbeats: extend(…) moves a held lease's deadline, fenced by the attempt, durable through replay. */
class LeaseExtendTest {

    @TempDir Path dir;
    final AtomicLong now = new AtomicLong(1_000_000);
    static final RetryPolicy POLICY = new RetryPolicy(5, 10, 100);

    Partition open() throws IOException {
        var p = Partition.open(dir, "t", POLICY, now::get, Durability.ASYNC, 64 * 1024);
        p.snapshotPolicy(0, 0, 10_000);
        return p;
    }

    static byte[] b(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    @Test
    void heartbeatKeepsTheLeaseAndExpiryStillWorksWithout() throws Exception {
        try (var p = open()) {
            p.subscribe("g", true);
            long off = p.produce(0, 0, b("task")).offset();
            var a = p.poll("g", "A", 1, 1_000).getFirst();
            for (int i = 0; i < 10; i++) { // 10 heartbeats over 5 s, lease 1 s
                now.addAndGet(500);
                assertEquals(LeaseResult.OK, p.extend("g", off, a.attempt(), 1_000));
                assertTrue(p.poll("g", "B", 1, 1_000).isEmpty(), "held lease is not redelivered");
            }
            now.addAndGet(1_200); // heartbeats stop: expiry (+ backoff) redelivers
            var b = p.poll("g", "B", 1, 1_000).getFirst();
            assertEquals(2, b.attempt());
            assertEquals(LeaseResult.STALE_ATTEMPT, p.extend("g", off, a.attempt(), 1_000), "stale worker cannot revive its lease");
            assertEquals(LeaseResult.OK, p.ack("g", off, b.attempt()));
            assertEquals(LeaseResult.NOT_IN_FLIGHT, p.extend("g", off, b.attempt(), 1_000));
            assertThrows(IllegalArgumentException.class, () -> p.extend("g", off, 2, 0));
        }
    }

    @Test
    void refusedExtendWritesNothingAndShorteningIsAllowed() throws Exception {
        try (var p = open()) {
            p.subscribe("g", true);
            long off = p.produce(0, 0, b("t")).offset();
            var a = p.poll("g", "A", 1, 60_000).getFirst();
            long end = p.counts().nextOffset();
            assertEquals(LeaseResult.STALE_ATTEMPT, p.extend("g", off, a.attempt() + 1, 1_000));
            assertEquals(LeaseResult.NOT_IN_FLIGHT, p.extend("g", off + 100, 1, 1_000));
            assertEquals(end, p.counts().nextOffset());
            assertEquals(LeaseResult.OK, p.extend("g", off, a.attempt(), 1)); // shorten to 1 ms
            assertEquals(end + 1, p.counts().nextOffset());
            now.addAndGet(20);
            assertEquals(2, p.poll("g", "B", 1, 1_000).getFirst().attempt());
        }
    }

    @Test
    void batchedExtendIsOneRecordAndReportsPerLease() throws Exception {
        try (var p = open()) {
            p.subscribe("g", true);
            for (int i = 0; i < 5; i++) p.produce(0, 0, b("t" + i));
            var ds = p.poll("g", "A", 5, 1_000);
            assertEquals(5, ds.size());
            p.ack("g", ds.get(4).offset(), ds.get(4).attempt());
            long[] offs = {ds.get(0).offset(), ds.get(1).offset(), ds.get(2).offset(), ds.get(3).offset(), ds.get(4).offset(), ds.get(0).offset()};
            int[] atts = {1, 1, 7, 1, 1, 1};
            long end = p.counts().nextOffset();
            var r = p.extend("g", offs, atts, 10_000);
            assertEquals(List.of(LeaseResult.OK, LeaseResult.OK, LeaseResult.STALE_ATTEMPT, LeaseResult.OK,
                    LeaseResult.NOT_IN_FLIGHT, LeaseResult.OK), r);
            assertEquals(end + 1, p.counts().nextOffset(), "one record for the whole batch");
            assertEquals(List.of(LeaseResult.NOT_IN_FLIGHT), p.extend("g", new long[] {ds.get(4).offset()}, new int[] {1}, 10_000));
            assertEquals(end + 1, p.counts().nextOffset(), "nothing accepted: nothing written");
            now.addAndGet(2_000); // offset 2 was not extended (stale token): it alone expires
            var again = p.poll("g", "B", 10, 1_000);
            assertEquals(1, again.size());
            assertEquals(ds.get(2).offset(), again.getFirst().offset());
            assertThrows(IllegalArgumentException.class, () -> p.extend("g", new long[] {1}, new int[0], 10));
            assertThrows(IllegalArgumentException.class, () -> p.extend("g", new long[0], new int[0], 0));
            assertEquals(List.of(), p.extend("g", new long[0], new int[0], 10));
        }
    }

    @Test
    void extendedDeadlinesSurviveCrashReplayAndSnapshots() throws Exception {
        long off, barrier;
        String before;
        try (var p = open()) {
            p.subscribe("g", true);
            off = p.produce(0, 0, b("t")).offset();
            p.produce(0, 0, b("u"));
            var ds = p.poll("g", "A", 2, 1_000);
            p.snapshotNow().get(); // snapshot before the extend: replay applies the EXTEND record
            assertEquals(LeaseResult.OK, p.extend("g", off, ds.get(0).attempt(), 60_000));
            assertEquals(List.of(LeaseResult.OK), p.extend("g", new long[] {ds.get(1).offset()}, new int[] {1}, 30_000));
            var bp = p.produce(0, 0, b("barrier"), Durability.GROUP);
            bp.durable().get();
            barrier = bp.offset();
            before = p.canonicalState();
        }
        try (var q = open()) {
            assertEquals(before, q.canonicalState(), "replay exact");
            assertTrue(q.replayedEntries() >= 3);
            now.addAndGet(5_000);
            var d = q.poll("g", "B", 5, 1_000);
            assertEquals(List.of(barrier), d.stream().map(Delivery::offset).toList(), "only the barrier is free; extended leases hold");
            assertEquals(LeaseResult.OK, q.ack("g", off, 1));
            q.snapshotNow().get();
        }
        try (var r = open()) {
            assertEquals(0, r.replayedEntries(), "recovered from the snapshot that includes the extended lease");
            now.addAndGet(30_000);
            assertEquals(2, r.poll("g", "C", 5, 1_000).size(), "the 30 s lease and the barrier expired; the acked one is gone");
        }
    }

    @Test
    void extendRecordRoundTripsAndRejectsGarbage() {
        var e = new LedgerEntry.Extend("g", 123, new long[] {1, 5}, new int[] {1, 3});
        var raw = LedgerEntry.encode(e);
        assertEquals(e, LedgerEntry.decode(raw));
        assertEquals(e.hashCode(), LedgerEntry.decode(raw).hashCode());
        assertNotEquals(e, new LedgerEntry.Extend("g", 123, new long[] {1, 5}, new int[] {1, 4}));
        assertNotEquals(e, "x");
        assertThrows(IllegalArgumentException.class, () -> new LedgerEntry.Extend("g", 1, new long[0], new int[0]));
        assertThrows(IllegalArgumentException.class, () -> new LedgerEntry.Extend("g", 1, new long[1], new int[2]));
        var bad = raw.clone();
        int countPos = 1 + 2 + 1 + 8;
        bad[countPos] = 0x7f; // absurd count
        assertThrows(IllegalArgumentException.class, () -> LedgerEntry.decode(bad));
    }
}
