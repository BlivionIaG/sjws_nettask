package sjws_nettask.messaging.delivery;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sjws_nettask.messaging.ledger.Durability;

/** #224: the optional CancelProbe makes only extend answer CANCELLED, after the fence, writing nothing. */
class CancelProbeTest {

    @TempDir Path dir;
    final AtomicLong now = new AtomicLong(1_000_000);
    static final RetryPolicy POLICY = new RetryPolicy(5, 10, 100);
    final Set<Long> cancelled = ConcurrentHashMap.newKeySet();
    final AtomicInteger asked = new AtomicInteger();
    final CancelProbe probe = (g, off) -> {
        asked.incrementAndGet();
        return "g".equals(g) && cancelled.contains(off);
    };

    Partition open(String topic, CancelProbe probe) throws IOException {
        return Partition.open(dir, topic, POLICY, now::get, Durability.ASYNC, 64 * 1024, true, probe);
    }

    static byte[] b(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    @Test
    void probeUnsetBehavesExactlyAsToday() throws Exception {
        try (var a = open("a", null); var b = Partition.open(dir, "b", POLICY, now::get, Durability.ASYNC, 64 * 1024)) {
            for (var p : List.of(a, b)) {
                p.subscribe("g", true);
                long off = p.produce(0, 0, b("t")).offset();
                var d = p.poll("g", "A", 1, 1_000).getFirst();
                assertEquals(LeaseResult.OK, p.extend("g", off, d.attempt(), 1_000));
                assertEquals(List.of(LeaseResult.OK, LeaseResult.NOT_IN_FLIGHT),
                        p.extend("g", new long[] {off, off + 9}, new int[] {d.attempt(), 1}, 1_000));
                assertEquals(LeaseResult.STALE_ATTEMPT, p.extend("g", off, d.attempt() + 1, 1_000));
                assertEquals(LeaseResult.OK, p.ack("g", off, d.attempt()));
                assertEquals(LeaseResult.NOT_IN_FLIGHT, p.extend("g", off, d.attempt(), 1_000));
            }
            assertEquals(a.counts().nextOffset(), b.counts().nextOffset(), "same records written with and without");
        }
    }

    @Test
    void probeSetReturnsCancelledOnlyOnExtendAndWritesNothing() throws Exception {
        try (var p = open("t", probe)) {
            p.subscribe("g", true);
            long off = p.produce(0, 0, b("t")).offset();
            long other = p.produce(0, 1, b("u")).offset();
            var ds = p.poll("g", "A", 2, 1_000);
            var d = ds.get(0);
            var e = ds.get(1);
            cancelled.add(off);
            long end = p.counts().nextOffset();
            assertEquals(LeaseResult.CANCELLED, p.extend("g", off, d.attempt(), 60_000));
            assertEquals(end, p.counts().nextOffset(), "CANCELLED writes no EXTEND record");
            // batch: the cancelled lease is refused, the other one is extended in one record
            assertEquals(List.of(LeaseResult.CANCELLED, LeaseResult.OK),
                    p.extend("g", new long[] {off, other}, new int[] {d.attempt(), e.attempt()}, 60_000));
            assertEquals(end + 1, p.counts().nextOffset());
            // not extended and not released: still held until its old deadline, then redelivered
            assertTrue(p.poll("g", "B", 1, 1_000).isEmpty());
            now.addAndGet(1_500);
            var again = p.poll("g", "B", 1, 1_000);
            assertEquals(1, again.size(), "cancelled lease ran out at its old deadline");
            assertEquals(off, again.getFirst().offset());
            // ack/nack/reject ignore the probe
            int before = asked.get();
            assertEquals(LeaseResult.STALE_ATTEMPT, p.ack("g", off, d.attempt()));
            assertEquals(LeaseResult.OK, p.ack("g", off, again.getFirst().attempt()));
            assertEquals(LeaseResult.OK, p.ack("g", other, e.attempt()));
            assertEquals(before, asked.get(), "settles never ask the probe");
        }
    }

    @Test
    void cancelledLeaseCanStillBeNackedOrRejected() throws Exception {
        try (var p = open("t", probe)) {
            p.subscribe("g", true);
            long a = p.produce(0, 0, b("a")).offset();
            long r = p.produce(0, 1, b("r")).offset();
            var ds = p.poll("g", "A", 2, 60_000);
            cancelled.add(a);
            cancelled.add(r);
            assertEquals(LeaseResult.CANCELLED, p.extend("g", a, ds.get(0).attempt(), 1_000));
            assertEquals(LeaseResult.OK, p.nack("g", a, ds.get(0).attempt(), 0));
            assertEquals(LeaseResult.OK, p.reject("g", r, ds.get(1).attempt(), "cancelled"));
        }
    }

    @Test
    void fenceTakesPrecedenceOverTheProbe() throws Exception {
        try (var p = open("t", probe)) {
            p.subscribe("g", true);
            long off = p.produce(0, 0, b("t")).offset();
            var d = p.poll("g", "A", 1, 1_000).getFirst();
            cancelled.add(off);
            cancelled.add(off + 50);
            int before = asked.get();
            assertEquals(LeaseResult.STALE_ATTEMPT, p.extend("g", off, d.attempt() + 1, 1_000));
            assertEquals(LeaseResult.NOT_IN_FLIGHT, p.extend("g", off + 50, 1, 1_000));
            assertEquals(List.of(LeaseResult.STALE_ATTEMPT, LeaseResult.NOT_IN_FLIGHT),
                    p.extend("g", new long[] {off, off + 50}, new int[] {d.attempt() + 1, 1}, 1_000));
            assertEquals(before, asked.get(), "the probe is not asked when the fence refuses");
            // a group the probe does not cancel extends normally
            p.subscribe("h", true);
            var h = p.poll("h", "A", 1, 1_000).getFirst();
            assertEquals(LeaseResult.OK, p.extend("h", off, h.attempt(), 1_000));
        }
    }

    @Test
    void openWithoutDlqCreatesNoDeadLetterPartition() throws Exception {
        try (var p = Partition.openWithoutDlq(dir, "stream.n1#0", new RetryPolicy(1, 10, 100), now::get)) {
            p.subscribe("g", true);
            long off = p.produce(0, 0, b("c")).offset();
            var d = p.poll("g", "A", 1, 1_000).getFirst();
            assertEquals(LeaseResult.OK, p.reject("g", off, d.attempt(), "x"));
            assertTrue(p.poll("g", "A", 1, 1_000).isEmpty());
        }
        assertTrue(Files.isDirectory(dir.resolve("stream.n1#0")));
        assertFalse(Files.exists(dir.resolve("stream.n1#0" + Partition.DLQ_SUFFIX)), "no .dlq directory");
        assertEquals(LeaseResult.CANCELLED, LeaseResult.values()[3], "appended last: existing ordinals unchanged");
        assertFalse(LeaseResult.CANCELLED.ok());
    }
}
