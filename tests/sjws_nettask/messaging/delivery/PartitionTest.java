package sjws_nettask.messaging.delivery;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sjws_nettask.messaging.ledger.Durability;
import sjws_nettask.messaging.ledger.SegmentedLog;

class PartitionTest {

    @TempDir Path dir;
    final AtomicLong now = new AtomicLong(1_000_000);
    final List<Partition> open = new ArrayList<>();
    static final RetryPolicy POLICY = new RetryPolicy(3, 100, 1_000);

    static byte[] b(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    static String s(byte[] b) { return new String(b, StandardCharsets.UTF_8); }

    Partition open(String topic) throws IOException {
        var p = Partition.open(dir, topic, POLICY, now::get, Durability.ASYNC, 64 * 1024);
        open.add(p);
        return p;
    }

    @AfterEach void closeAll() throws IOException { for (var p : open) p.close(); }

    @Test
    void entryCodecRoundTripAndRejectsGarbage() {
        List<LedgerEntry> all = List.of(new LedgerEntry.Enqueue(7, 9, 11, "g", 3, 2, "why", b("pay")),
                new LedgerEntry.Subscribe("grp", 5), new LedgerEntry.Deliver("grp", 5, "c1", 99, 1),
                new LedgerEntry.Ack("grp", 5), new LedgerEntry.Nack("grp", 5, 77), new LedgerEntry.DeadLetter("grp", 5, "r"));
        for (var e : all) {
            var d = LedgerEntry.decode(LedgerEntry.encode(e));
            if (e instanceof LedgerEntry.Enqueue x) {
                var y = (LedgerEntry.Enqueue) d;
                assertArrayEquals(x.payload(), y.payload());
                assertEquals(x.originReason(), y.originReason());
                assertEquals(x.seq(), y.seq());
            } else assertEquals(e, d);
        }
        assertThrows(IllegalArgumentException.class, () -> LedgerEntry.decode(new byte[] {42}));
        assertThrows(IllegalArgumentException.class, () -> LedgerEntry.decode(new byte[] {LedgerEntry.ACK_T, 0}));
        var ok = LedgerEntry.encode(new LedgerEntry.Ack("g", 1));
        var longer = java.util.Arrays.copyOf(ok, ok.length + 1);
        assertThrows(IllegalArgumentException.class, () -> LedgerEntry.decode(longer));
        assertThrows(IllegalArgumentException.class, () -> LedgerEntry.encode(new LedgerEntry.Ack("x".repeat(40_000), 1)));
    }

    @Test
    void retryPolicyBackoff() {
        assertEquals(0, POLICY.backoffMs(0));
        assertEquals(100, POLICY.backoffMs(1));
        assertEquals(400, POLICY.backoffMs(3));
        assertEquals(1_000, POLICY.backoffMs(50));
        assertEquals(0, new RetryPolicy(1, 0, 0).backoffMs(3));
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(1, 5, 1));
    }

    @Test
    void produceSubscribePollAckAndCommittedOffset() throws Exception {
        var p = open("orders");
        p.eagerSnapshots(true);
        assertTrue(p.subscribe("billing", true));
        assertFalse(p.subscribe("billing", false));
        for (int i = 0; i < 5; i++) p.produce(0, 0, b("m" + i)).durable().get();
        var got = p.poll("billing", "c1", 3, 10_000);
        assertEquals(List.of("m0", "m1", "m2"), got.stream().map(d -> s(d.payload())).toList());
        assertTrue(got.stream().allMatch(d -> d.attempt() == 1 && d.deadlineMs() == now.get() + 10_000));
        var insp = new LedgerInspector(p);
        assertEquals(new DeliverySnapshot.Counts(2, 3, 0, 0, 0), insp.counts("billing"));
        assertTrue(p.ack("billing", got.get(1).offset()));
        assertFalse(p.ack("billing", got.get(1).offset()), "duplicate ack");
        var v = p.snapshot().groups().get("billing");
        assertEquals(got.get(0).offset(), v.committedOffset(), "hole at m0 holds the low watermark");
        assertTrue(p.ack("billing", got.get(0).offset()));
        assertTrue(p.ack("billing", got.get(2).offset()));
        assertEquals(new DeliverySnapshot.Counts(2, 0, 3, 0, 0), insp.counts("billing"));
        assertTrue(p.snapshot().groups().get("billing").committedOffset() > got.get(2).offset());
        assertEquals(2, p.snapshot().liveMessages(), "acked payloads released");
        assertEquals(5, p.snapshot().enqueuedTotal());
        assertEquals(List.of("m3", "m4"), p.poll("billing", "c1", 10, 1).stream().map(d -> s(d.payload())).toList());
        assertTrue(p.poll("billing", "c1", 10, 1).isEmpty());
        assertEquals("orders", p.topic());
        assertThrows(IllegalStateException.class, () -> p.poll("nope", "c", 1, 1));
        assertThrows(IllegalArgumentException.class, () -> p.poll("billing", "c", 0, 1));
        assertThrows(IllegalArgumentException.class, () -> insp.counts("nope"));
    }

    @Test
    void groupsHaveIndependentOffsetsAndPayloadIsKeptUntilAllResolve() throws Exception {
        var p = open("t");
        p.subscribe("a", true);
        p.subscribe("b", true);
        long o = p.produce(0, 0, b("x")).offset();
        var da = p.poll("a", "ca", 1, 100).getFirst();
        p.ack("a", da.offset());
        assertEquals(1, p.snapshot().liveMessages());
        var db = p.poll("b", "cb", 1, 100).getFirst();
        assertEquals(o, db.offset());
        assertEquals("x", s(db.payload()));
        p.ack("b", db.offset());
        assertEquals(0, p.snapshot().liveMessages());
        p.subscribe("late", false);
        p.produce(0, 0, b("y"));
        assertEquals("y", s(p.poll("late", "c", 5, 100).getFirst().payload()));
        assertEquals(List.of("a", "b", "late"), List.copyOf(new LedgerInspector(p).counts().keySet()));
    }

    @Test
    void visibilityTimeoutRedeliversAfterBackoffAndOldLeaseCanStillAck() throws Exception {
        var p = open("t");
        p.subscribe("g", true);
        p.produce(0, 0, b("x"));
        var d1 = p.poll("g", "c1", 1, 500).getFirst();
        now.addAndGet(500);
        assertTrue(p.poll("g", "c2", 1, 500).isEmpty(), "expired but inside backoff(1)=100ms");
        now.addAndGet(100);
        var d2 = p.poll("g", "c2", 1, 500).getFirst();
        assertEquals(d1.offset(), d2.offset());
        assertEquals(2, d2.attempt());
        var inf = new LedgerInspector(p).page("g", MessageState.IN_FLIGHT, 0, 10);
        assertEquals("c2", inf.getFirst().consumer());
        assertEquals(1, p.snapshot().groups().get("g").counts().redeliveries());
        assertTrue(p.ack("g", d1.offset()));
    }

    @Test
    void nackBackoffThenMaxAttemptsGoesToDlq() throws Exception {
        var p = open("pay");
        p.eagerSnapshots(true);
        p.subscribe("g", true);
        long o = p.produce(42, 1, b("poison")).offset();
        for (int attempt = 1; attempt <= 3; attempt++) {
            var d = p.poll("g", "c", 1, 1_000);
            assertEquals(1, d.size(), "attempt " + attempt);
            assertEquals(attempt, d.getFirst().attempt());
            assertTrue(p.nack("g", o, 0));
            if (attempt < 3) {
                var pend = new LedgerInspector(p).page("g", MessageState.ENQUEUED, 0, 10);
                assertEquals(attempt, pend.getFirst().attempts());
                assertEquals(now.get() + POLICY.backoffMs(attempt), pend.getFirst().timeMs());
                assertTrue(p.poll("g", "c", 1, 1_000).isEmpty(), "inside backoff");
                now.addAndGet(POLICY.backoffMs(attempt));
            }
        }
        var insp = new LedgerInspector(p);
        assertEquals(new DeliverySnapshot.Counts(0, 0, 0, 1, 2), insp.counts("g"));
        var dead = insp.page("g", MessageState.DEAD_LETTERED, 0, 10).getFirst();
        assertEquals("nacked:max-attempts", dead.reason());
        assertEquals(3, dead.attempts());
        assertEquals(6, dead.size());
        assertTrue(insp.page("g", MessageState.ACKED, 0, 10).isEmpty());
        assertFalse(p.nack("g", o, 0));
        var dlq = p.deadLetterQueue();
        assertEquals("pay.dlq", dlq.topic());
        assertNull(dlq.deadLetterQueue());
        dlq.subscribe("ops", true);
        var dd = dlq.poll("ops", "admin", 10, 1_000);
        assertEquals(1, dd.size());
        assertEquals("poison", s(dd.getFirst().payload()));
        assertEquals("g", dd.getFirst().originGroup());
        assertEquals(o, dd.getFirst().originOffset());
        assertEquals(3, dd.getFirst().originAttempts());
        assertEquals("nacked:max-attempts", dd.getFirst().originReason());
        assertNotNull(insp.deadLetterQueue());
        assertNull(new LedgerInspector(dlq).deadLetterQueue());
    }

    @Test
    void expiredLeaseAtMaxAttemptsIsDeadLetteredOnPoll() throws Exception {
        var p = open("t");
        p.subscribe("g", true);
        p.produce(0, 0, b("x"));
        for (int i = 0; i < 3; i++) { assertEquals(1, p.poll("g", "c", 1, 10).size()); now.addAndGet(10_000); }
        assertTrue(p.poll("g", "c", 1, 10).isEmpty());
        var dead = new LedgerInspector(p).page("g", MessageState.DEAD_LETTERED, 0, 5);
        assertEquals("lease-expired:max-attempts", dead.getFirst().reason());
    }

    @Test
    void rejectDeadLettersImmediately() throws Exception {
        var p = open("t");
        p.subscribe("g", true);
        p.produce(0, 0, b("bad"));
        var d = p.poll("g", "c", 1, 100).getFirst();
        assertTrue(p.reject("g", d.offset(), "schema"));
        assertFalse(p.reject("g", d.offset(), "again"));
        assertEquals("rejected:schema", new LedgerInspector(p).page("g", MessageState.DEAD_LETTERED, 0, 1).getFirst().reason());
        assertEquals(1, new LedgerInspector(p).deadLetterQueue().snapshot().enqueuedTotal());
    }

    @Test
    void producerDedup() throws Exception {
        var p = open("t");
        var r1 = p.produce(5, 1, b("a"));
        var r2 = p.produce(5, 1, b("a-retry"));
        assertFalse(r1.duplicate());
        assertTrue(r2.duplicate());
        assertEquals(r1.offset(), r2.offset());
        assertEquals(r1.offset(), r2.durable().get());
        assertFalse(p.produce(6, 1, b("other producer")).duplicate());
        assertFalse(p.produce(0, 1, b("anon")).duplicate());
        assertFalse(p.produce(0, 1, b("anon")).duplicate());
        for (long s = 2; s < 2 + DeliveryState.DEDUP_WINDOW + 5; s++) p.produce(5, s, b("n"));
        var old = p.produce(5, 1, b("ancient"));
        assertTrue(old.duplicate());
        assertEquals(-1, old.offset(), "older than the dedup window: rejected");
        var inWindow = p.produce(5, 100, b("retry"));
        assertTrue(inWindow.duplicate());
        assertTrue(inWindow.offset() >= 0);
    }

    @Test
    void ackWithDurabilityOverrideAndLazySnapshots() throws Exception {
        var p = open("t");
        p.subscribe("g", true);
        p.produce(0, 0, b("x"), Durability.GROUP).durable().get();
        var d = p.poll("g", "c", 1, 100).getFirst();
        var s0 = p.snapshot();
        assertNotNull(p.ack("g", d.offset(), Durability.GROUP).get());
        assertNull(p.ack("g", d.offset(), Durability.GROUP));
        var s1 = p.snapshot();
        assertTrue(s1.version() > s0.version(), "lazy snapshot refreshed on read");
        assertSame(s1, p.snapshot(), "unchanged state: same immutable snapshot");
        assertThrows(UnsupportedOperationException.class, () -> s1.groups().put("x", null));
        assertThrows(UnsupportedOperationException.class, () -> s1.groups().get("g").inFlight().add(null));
    }

    @Test
    void pagingIsByOffset() throws Exception {
        var p = open("t");
        p.subscribe("g", true);
        for (int i = 0; i < 50; i++) p.produce(0, 0, b("m" + i));
        p.poll("g", "c", 10, 1000);
        var insp = new LedgerInspector(p);
        var page1 = insp.page("g", MessageState.ENQUEUED, 0, 15);
        assertEquals(15, page1.size());
        var page2 = insp.page("g", MessageState.ENQUEUED, page1.getLast().offset() + 1, 100);
        assertEquals(25, page2.size());
        assertTrue(page2.getFirst().offset() > page1.getLast().offset());
        assertEquals(10, insp.page("g", MessageState.IN_FLIGHT, 0, 100).size());
        assertTrue(insp.page("g", MessageState.IN_FLIGHT, 0, -1).isEmpty());
    }

    // ---- crash recovery ----

    /** Random workload; returns canonical state after every operation keyed by ledger nextOffset. */
    HashMap<Long, String> workload(Partition p, Random r, int ops) {
        var states = new HashMap<Long, String>();
        var inflight = new ArrayList<Long>();
        p.subscribe("g1", true);
        p.subscribe("g2", true);
        states.put(p.snapshot().nextOffset(), p.canonicalState());
        for (int i = 0; i < ops; i++) {
            String g = r.nextBoolean() ? "g1" : "g2";
            switch (r.nextInt(7)) {
                case 0, 1 -> p.produce(r.nextInt(3), r.nextInt(40), b("p" + r.nextInt(1000)));
                case 2 -> p.poll(g, "c" + r.nextInt(3), 1 + r.nextInt(3), 50 + r.nextInt(500)).forEach(d -> inflight.add(d.offset()));
                case 3 -> { if (!inflight.isEmpty()) p.ack(g, inflight.remove(r.nextInt(inflight.size()))); }
                case 4 -> { if (!inflight.isEmpty()) p.nack(g, inflight.get(r.nextInt(inflight.size())), r.nextInt(200)); }
                case 5 -> { if (!inflight.isEmpty() && r.nextInt(5) == 0) p.reject(g, inflight.get(r.nextInt(inflight.size())), "r"); }
                default -> now.addAndGet(r.nextInt(800));
            }
            var c = p.canonicalState();
            states.put(Long.parseLong(c.substring(5, c.indexOf(' '))), c);
        }
        return states;
    }

    @RepeatedTest(5)
    void reopenReplaysExactState() throws Exception {
        var r = new Random();
        var p = open("t");
        workload(p, r, 400);
        String before = p.canonicalState();
        String dlqBefore = p.deadLetterQueue().canonicalState();
        p.close();
        open.remove(p);
        var q = open("t");
        assertEquals(before, q.canonicalState());
        assertEquals(dlqBefore, q.deadLetterQueue().canonicalState());
        workload2(q, r);
    }

    private void workload2(Partition q, Random r) {
        // keeps working after recovery
        q.produce(0, 0, b("after"));
        assertFalse(q.poll("g1", "c", 100, 100).isEmpty());
    }

    /** Torn tail: cutting the log at any byte recovers some operation-boundary state or a consistent prefix of one. */
    @Test
    void tornTailRecoversAPrefixState() throws Exception {
        var r = new Random(7);
        var p = open("t");
        var states = workload(p, r, 150);
        p.close();
        open.remove(p);
        var file = dir.resolve("t").resolve(String.format("%020d.log", 0));
        byte[] full = Files.readAllBytes(file);
        int matched = 0;
        for (int i = 0; i < 60; i++) {
            int cut = r.nextInt(full.length + 1);
            var d = dir.resolve("cut" + i);
            Files.createDirectories(d.resolve("t"));
            Files.write(d.resolve("t").resolve(file.getFileName()), java.util.Arrays.copyOf(full, cut));
            try (var q = Partition.open(d, "t", POLICY, now::get, Durability.ASYNC, 64 * 1024)) {
                var c = q.canonicalState();
                long next = Long.parseLong(c.substring(5, c.indexOf(' ')));
                var expected = states.get(next);
                if (expected != null) { assertEquals(expected, c, "cut " + cut); matched++; }
                q.produce(0, 0, b("x"));
            }
        }
        assertTrue(matched > 10, "matched " + matched);
    }

    @Test
    void crashBetweenDlqCopyAndMarkDoesNotDuplicate() throws Exception {
        var p = open("t");
        p.subscribe("g", true);
        p.produce(0, 0, b("x"));
        var d = p.poll("g", "c", 1, 100).getFirst();
        p.reject("g", d.offset(), "boom");
        p.close();
        open.remove(p);
        // Drop the final DeadLetter record from the source log (simulates a crash after the DLQ copy).
        var file = dir.resolve("t").resolve(String.format("%020d.log", 0));
        try (var log = SegmentedLog.open(dir.resolve("t"))) {
            var batches = log.readAll();
            assertTrue(LedgerEntry.decode(batches.getLast().records().getFirst()) instanceof LedgerEntry.DeadLetter);
        }
        long size = Files.size(file);
        try (var log = SegmentedLog.open(dir.resolve("t"))) {
            var last = log.readAll().getLast();
            int frame = last.encode().remaining();
            try (var ch = FileChannel.open(file, StandardOpenOption.WRITE)) { ch.truncate(size - frame); }
        }
        var q = open("t");
        var insp = new LedgerInspector(q);
        assertEquals(1, insp.counts("g").inFlight());
        assertTrue(q.reject("g", d.offset(), "boom"));
        assertEquals(1, insp.deadLetterQueue().snapshot().enqueuedTotal(), "DLQ copy deduplicated by origin");
        assertEquals(1, insp.counts("g").deadLettered());
    }

    @Test
    void corruptEntryFailsOpenCleanly() throws Exception {
        try (var log = SegmentedLog.open(dir.resolve("bad"))) { log.append(List.of(new byte[] {99}), 0); }
        assertThrows(IOException.class, () -> Partition.open(dir, "bad", POLICY, now::get));
        assertThrows(IllegalArgumentException.class, () -> Partition.open(dir, "a/b", POLICY, now::get));
    }
}
