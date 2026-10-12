package sjws_nettask.messaging.delivery;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sjws_nettask.messaging.ledger.Durability;
import sjws_nettask.messaging.ledger.SegmentedLog;

class SnapshotRetentionTest {

    @TempDir Path dir;
    final AtomicLong now = new AtomicLong(1_000_000);
    static final RetryPolicy POLICY = new RetryPolicy(3, 100, 1_000);
    static final long SEG = 4096;

    static byte[] b(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    Partition open() throws IOException { return open(dir); }

    Partition open(Path d) throws IOException {
        var p = Partition.open(d, "t", POLICY, now::get, Durability.ASYNC, SEG);
        p.snapshotPolicy(0, 0, 10_000);
        p.payloadCacheBytes(0); // force payload reads from segments
        return p;
    }

    static long next(String canonical) { return Long.parseLong(canonical.substring(5, canonical.indexOf(' '))); }

    /** Random workload; returns canonical state after each op keyed by next offset. Acks most deliveries. */
    HashMap<Long, String> workload(Partition p, Random r, int ops, boolean maintain) throws Exception {
        var states = new HashMap<Long, String>();
        var inflight = new ArrayList<long[]>(); // {group, offset}
        p.subscribe("g1", true);
        p.subscribe("g2", true);
        for (int i = 0; i < ops; i++) {
            int g = r.nextInt(2);
            String gn = "g" + (g + 1);
            switch (r.nextInt(8)) {
                case 0, 1, 2 -> p.produce(r.nextInt(3), r.nextInt(50), b("p" + r.nextInt(1000) + "x".repeat(r.nextInt(80))));
                case 3, 4 -> p.poll(gn, "c", 1 + r.nextInt(3), 50 + r.nextInt(500)).forEach(d -> inflight.add(new long[] {g, d.offset()}));
                case 5 -> { if (!inflight.isEmpty()) { var x = inflight.remove(r.nextInt(inflight.size())); p.ack("g" + (x[0] + 1), x[1]); } }
                case 6 -> { if (!inflight.isEmpty()) { var x = inflight.get(r.nextInt(inflight.size())); p.nack("g" + (x[0] + 1), x[1], r.nextInt(200)); } }
                default -> {
                    now.addAndGet(r.nextInt(800));
                    if (maintain && r.nextInt(10) == 0) { p.snapshotNow().get(); p.maintain(); }
                }
            }
            var c = p.canonicalState();
            states.put(next(c), c);
        }
        return states;
    }

    @Test
    void serializeRoundTripIsExactAndRejectsCorruption() throws Exception {
        try (var p = open()) {
            workload(p, new Random(1), 300, false);
            var st = stateOf(p);
            var raw = st.serialize();
            var back = DeliveryState.deserialize(raw, POLICY);
            assertEquals(st.canonical(), back.canonical());
            var bad = raw.clone();
            bad[20] ^= 1;
            assertThrows(IllegalArgumentException.class, () -> DeliveryState.deserialize(bad, POLICY));
            assertThrows(IllegalArgumentException.class, () -> DeliveryState.deserialize(new byte[5], POLICY));
            var wrongMagic = withCrc(new byte[] {0, 0, 0, 1, 0, 0, 0, 1});
            assertThrows(IllegalArgumentException.class, () -> DeliveryState.deserialize(wrongMagic, POLICY));
            var trunc = withCrc(java.util.Arrays.copyOf(raw, 30));
            assertThrows(IllegalArgumentException.class, () -> DeliveryState.deserialize(trunc, POLICY));
            var trailing = withCrc(java.util.Arrays.copyOf(java.util.Arrays.copyOf(raw, raw.length - 4), raw.length - 4 + 3));
            assertThrows(IllegalArgumentException.class, () -> DeliveryState.deserialize(trailing, POLICY));
        }
    }

    private static byte[] withCrc(byte[] body) {
        var c = new java.util.zip.CRC32C();
        c.update(body);
        return java.nio.ByteBuffer.allocate(body.length + 4).put(body).putInt((int) c.getValue()).array();
    }

    private static DeliveryState stateOf(Partition p) throws Exception {
        var f = Partition.class.getDeclaredField("state");
        f.setAccessible(true);
        return (DeliveryState) f.get(p);
    }

    @RepeatedTest(4)
    void recoveryFromSnapshotReplaysOnlyTheTailAndIsExact() throws Exception {
        var r = new Random();
        String before, dlqBefore;
        long total;
        try (var p = open()) {
            p.snapshotEveryEntries(97);
            workload(p, r, 600, true);
            p.snapshotNow().get();
            p.produce(0, 0, b("tail-1"));
            p.produce(0, 0, b("tail-2"));
            before = p.canonicalState();
            dlqBefore = p.deadLetterQueue().canonicalState();
            total = p.counts().nextOffset();
        }
        try (var q = open()) {
            assertEquals(before, q.canonicalState());
            assertEquals(dlqBefore, q.deadLetterQueue().canonicalState());
            assertEquals(total - 2, q.recoveredFromSnapshot());
            assertEquals(2, q.replayedEntries());
            // payloads come back from segments, including any in compacted segments
            q.subscribe("late", true);
            for (var d : q.poll("late", "c", 10_000, 1000)) assertTrue(d.payload().length > 0);
        }
    }

    @Test
    void corruptNewestSnapshotFallsBackToTheOlderOne() throws Exception {
        String before;
        try (var p = open()) {
            p.subscribe("g", true);
            for (int i = 0; i < 30; i++) p.produce(0, 0, b("a" + i));
            p.snapshotNow().get();
            for (int i = 0; i < 30; i++) p.produce(0, 0, b("b" + i));
            p.snapshotNow().get();
            p.produce(0, 0, b("c"));
            before = p.canonicalState();
        }
        var snaps = new ArrayList<Path>();
        try (var s = Files.list(dir.resolve("t"))) { s.filter(f -> f.toString().endsWith(".snap")).sorted().forEach(snaps::add); }
        assertEquals(2, snaps.size());
        Files.write(snaps.get(1), new byte[] {1, 2, 3});
        try (var q = open()) {
            assertEquals(before, q.canonicalState());
            assertEquals(31, q.replayedEntries());
        }
    }

    @Test
    void retentionDeletesOnlyBelowTheLowWatermarkAndCompactionKeepsPinnedPayloads() throws Exception {
        long pinned;
        String before;
        try (var p = open()) {
            p.subscribe("g", true);
            pinned = p.produce(0, 0, b("PINNED-" + "z".repeat(100))).offset();
            var held = p.poll("g", "slow", 1, 1_000_000).getFirst(); // stays in flight
            assertEquals(pinned, held.offset());
            for (int i = 0; i < 400; i++) {
                p.produce(0, 0, b("m" + i + "y".repeat(100)));
                for (var d : p.poll("g", "c", 1, 1000)) assertTrue(p.ack("g", d.offset()));
            }
            var r0 = p.maintain();
            assertEquals(0, r0.deletedSegments(), "no snapshot yet: nothing below the low watermark");
            assertEquals(0, r0.compactedSegments());
            p.snapshotNow().get(); // also waits until the writer has written everything
            long bytes0 = p.logBytes();
            var r1 = p.maintain();
            assertEquals(pinned, r1.lowWatermark(), "the in-flight message pins the watermark");
            assertTrue(r1.compactedSegments() >= 1, "pinned first segment compacted: " + r1);
            assertTrue(r1.reclaimedBytes() > 0);
            assertTrue(p.logBytes() < bytes0, p.logBytes() + " vs " + bytes0 + " " + r1);
            assertNotNull(r1.dlq());
            // the pinned payload is still readable from the compacted segment
            now.addAndGet(2_000_000);
            var again = p.poll("g", "c2", 1, 1000).getFirst();
            assertEquals(pinned, again.offset());
            assertTrue(new String(again.payload(), StandardCharsets.UTF_8).startsWith("PINNED-"));
            assertTrue(p.ack("g", pinned));
            p.snapshotNow().get();
            var r2 = p.maintain();
            assertTrue(r2.deletedSegments() > 0, "everything resolved: segments below the watermark deleted " + r2);
            assertTrue(p.logBytes() < SEG * 3, "log shrank to about the active segment: " + p.logBytes());
            p.produce(0, 0, b("after"));
            before = p.canonicalState();
        }
        try (var q = open()) {
            assertEquals(before, q.canonicalState());
            assertEquals("after", new String(q.poll("g", "c", 5, 1000).getFirst().payload(), StandardCharsets.UTF_8));
            try (var log = SegmentedLog.open(dir.resolve("t"), SEG)) { assertTrue(log.startOffset() > 0); }
        }
    }

    @Test
    void keepResolvedDelaysDeletionAndPolicyIsValidated() throws Exception {
        try (var p = open()) {
            p.retention(new RetentionPolicy(3_600_000, 0));
            p.subscribe("g", true);
            for (int i = 0; i < 200; i++) { p.produce(0, 0, new byte[100]); for (var d : p.poll("g", "c", 1, 1000)) p.ack("g", d.offset()); }
            p.snapshotNow().get();
            var r = p.maintain();
            assertEquals(0, r.deletedSegments(), "younger than keepResolvedMs");
            assertEquals(0, r.compactedSegments(), "compaction off");
            p.retention(RetentionPolicy.DEFAULT);
            var mr = p.maintain();
            assertTrue(mr.deletedSegments() > 0, mr + " segs=" + segs(dir.resolve("t")));
        }
        assertThrows(IllegalArgumentException.class, () -> new RetentionPolicy(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> new RetentionPolicy(0, 2));
    }

    static String segs(Path d) throws IOException { try (var l = SegmentedLog.open(d, SEG)) { return l.segments().toString(); } }

    @Test
    void missingSnapshotAfterRetentionFailsLoudly() throws Exception {
        try (var p = open()) {
            p.subscribe("g", true);
            for (int i = 0; i < 200; i++) { p.produce(0, 0, new byte[100]); for (var d : p.poll("g", "c", 1, 1000)) p.ack("g", d.offset()); }
            p.snapshotNow().get();
            var mr = p.maintain();
            assertTrue(mr.deletedSegments() > 0, mr + " segs=" + segs(dir.resolve("t")));
        }
        try (var s = Files.list(dir.resolve("t"))) { for (var f : s.filter(x -> x.toString().endsWith(".snap")).toList()) Files.delete(f); }
        var e = assertThrows(IOException.class, this::open);
        assertTrue(e.getMessage().startsWith("no usable state snapshot"), e.getMessage());
    }

    @Test
    void snapshotIntervalValidationAndAutomaticSnapshots() throws Exception {
        try (var p = open()) {
            assertThrows(IllegalArgumentException.class, () -> p.snapshotEveryEntries(0));
            assertThrows(IllegalArgumentException.class, () -> p.payloadCacheBytes(-1));
            p.snapshotEveryEntries(10);
            p.subscribe("g", true);
            for (int i = 0; i < 35; i++) p.produce(0, 0, b("x"));
            assertEquals(p.snapshotNow().get(), p.snapshotNow().get(), "same offset: no second write");
        }
        try (var s = Files.list(dir.resolve("t"))) { assertEquals(2, s.filter(x -> x.toString().endsWith(".snap")).count(), "keeps two"); }
        try (var q = open()) { assertTrue(q.recoveredFromSnapshot() > 0); assertEquals(0, q.replayedEntries()); }
    }

    /** Torn tail after a snapshot: every cut recovers a recorded operation-boundary state. */
    @Test
    void tornTailAfterSnapshotRecoversPrefixStates() throws Exception {
        var r = new Random(11);
        HashMap<Long, String> states;
        try (var p = open()) {
            p.snapshotEveryEntries(1_000_000);
            states = workload(p, r, 80, false);
            p.snapshotNow().get();
            states.putAll(workload2(p, r));
        }
        var seg = new ArrayList<Path>();
        try (var s = Files.list(dir.resolve("t"))) { s.filter(f -> f.toString().endsWith(".log")).sorted().forEach(seg::add); }
        var last = seg.getLast();
        byte[] full = Files.readAllBytes(last);
        int matched = 0;
        for (int i = 0; i < 40; i++) {
            var d = dir.resolve("cut" + i);
            copyDir(dir.resolve("t"), d.resolve("t"));
            Files.write(d.resolve("t").resolve(last.getFileName()), java.util.Arrays.copyOf(full, r.nextInt(full.length + 1)));
            try (var q = open(d)) {
                var c = q.canonicalState();
                var exp = states.get(next(c));
                if (exp != null) { assertEquals(exp, c); matched++; }
            }
        }
        assertTrue(matched > 5, "matched " + matched);
    }

    private HashMap<Long, String> workload2(Partition p, Random r) throws Exception {
        var states = new HashMap<Long, String>();
        for (int i = 0; i < 60; i++) {
            if (r.nextBoolean()) p.produce(0, 0, b("w" + i)); else p.poll("g1", "c", 1, 100).forEach(d -> p.ack("g1", d.offset()));
            var c = p.canonicalState();
            states.put(next(c), c);
        }
        return states;
    }

    private static void copyDir(Path from, Path to) throws IOException {
        Files.createDirectories(to);
        try (var s = Files.list(from)) { for (var f : s.toList()) Files.copy(f, to.resolve(f.getFileName())); }
    }

    @Test
    void interruptedCompactionRollsBackOrForward() throws Exception {
        var d = dir.resolve("c");
        try (var log = SegmentedLog.open(d, 256)) { for (int i = 0; i < 20; i++) log.append(List.of(new byte[60]), 0); }
        // .cmp without marker: rolled back
        Files.write(d.resolve(String.format("%020d.cmp", 0)), new byte[] {9, 9});
        try (var log = SegmentedLog.open(d, 256)) {
            assertEquals(20, log.nextOffset());
            assertFalse(Files.exists(d.resolve(String.format("%020d.cmp", 0))));
            // full compaction keeping offset 1 only, then reopen: gaps are accepted and the end offset is preserved
            long sz = log.prepareCompaction(0, o -> o == 1);
            assertTrue(sz > 0);
            log.commitCompaction(0);
            assertNull(log.read(0));
            assertEquals(1, log.read(1).baseOffset());
            assertThrows(IllegalArgumentException.class, () -> log.prepareCompaction(log.segments().getLast().baseOffset(), o -> true));
            assertThrows(IllegalArgumentException.class, () -> log.deleteSegment(12345));
        }
        try (var log = SegmentedLog.open(d, 256)) {
            assertEquals(20, log.nextOffset());
            assertEquals(1, log.read(1).baseOffset());
            assertTrue(log.segments().getFirst().compacted());
            // marker + .cmp (crash after marker, before rename): rolled forward
            long base = log.segments().get(1).baseOffset();
            log.prepareCompaction(base, o -> false);
            assertThrows(IllegalArgumentException.class, () -> log.deleteSegment(base), "only the oldest can be deleted");
        }
        try (var log = SegmentedLog.open(d, 256)) {
            assertEquals(20, log.nextOffset());
            assertTrue(log.segments().get(1).compacted());
            assertEquals(0, log.segments().get(1).bytes());
            long b2 = log.segments().get(2).baseOffset();
            log.scanFrom(0, rb -> assertTrue(rb.baseOffset() == 1 || rb.baseOffset() >= b2));
        }
    }
}
