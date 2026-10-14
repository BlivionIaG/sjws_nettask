package sjws_nettask.messaging.ledger;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class LedgerTest {

    @TempDir Path dir;

    static byte[] b(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    static List<byte[]> recs(Random r, int maxCount, int maxSize) {
        int n = 1 + r.nextInt(maxCount);
        var out = new ArrayList<byte[]>();
        for (int i = 0; i < n; i++) { var x = new byte[r.nextInt(maxSize + 1)]; r.nextBytes(x); out.add(x); }
        return out;
    }

    @Test
    void batchRoundTripAndCrc() throws Exception {
        var rb = new RecordBatch(42, 7, List.of(b("a"), b(""), b("hello")));
        var enc = rb.encode();
        var dec = RecordBatch.decode(enc.duplicate());
        assertEquals(42, dec.baseOffset());
        assertEquals(7, dec.timestampMs());
        assertEquals(3, dec.count());
        assertEquals(45, dec.nextOffset());
        assertArrayEquals(b("hello"), dec.records().get(2));
        for (int i = 8; i < enc.limit(); i++) {
            var bad = ByteBuffer.allocate(enc.limit()).put(enc.duplicate()).flip();
            bad.put(i, (byte) (bad.get(i) ^ 1));
            assertThrows(RecordBatch.CorruptBatchException.class, () -> RecordBatch.decode(bad), "flip at " + i);
        }
        assertThrows(RecordBatch.CorruptBatchException.class, () -> RecordBatch.decode(ByteBuffer.allocate(3)));
        assertThrows(RecordBatch.CorruptBatchException.class, () -> RecordBatch.decode(enc.duplicate().limit(enc.limit() - 1)));
        assertThrows(IllegalArgumentException.class, () -> new RecordBatch(0, 0, List.of()));
        assertThrows(IllegalArgumentException.class, () -> RecordBatch.encodedSize(List.of(new byte[RecordBatch.MAX_FRAME_BYTES])));
    }

    @Test
    void appendReadReopenAndRoll() throws IOException {
        try (var log = SegmentedLog.open(dir, 512)) {
            for (int i = 0; i < 200; i++) assertEquals(i * 2L, log.append(List.of(b("r" + i), b("s" + i)), i));
            log.force();
            assertTrue(log.segmentCount() > 5);
            assertEquals(400, log.nextOffset());
            assertArrayEquals(b("s77"), log.read(155).records().get(1));
            assertNull(log.read(400));
            assertNull(log.read(-1));
        }
        assertTrue(Files.exists(dir.resolve(Segment.name(0, ".index"))));
        try (var log = SegmentedLog.open(dir, 512)) {
            assertEquals(400, log.nextOffset());
            assertEquals(0, log.recoveredTruncatedBytes());
            var all = log.readAll();
            assertEquals(200, all.size());
            for (int i = 0; i < 200; i++) assertEquals(i * 2L, all.get(i).baseOffset());
            assertEquals(402, log.append(List.of(b("x"), b("y")), 0) + 2);
        }
    }

    @Test
    void sparseIndexOnLargeSegment() throws IOException {
        try (var log = SegmentedLog.open(dir)) {
            for (int i = 0; i < 2000; i++) log.append(List.of(new byte[100]), i);
            for (int o : new int[] {0, 1, 999, 1500, 1999}) assertEquals(o, log.read(o).baseOffset());
        }
        try (var log = SegmentedLog.open(dir)) { // active segment: rebuilt by scan
            assertEquals(2000, log.nextOffset());
            assertEquals(1234, log.read(1234).baseOffset());
        }
    }

    @Test
    void staleOrCorruptIndexFallsBackToScan() throws IOException {
        try (var log = SegmentedLog.open(dir, 256)) { for (int i = 0; i < 20; i++) log.append(List.of(new byte[50]), 0); }
        Files.write(dir.resolve(Segment.name(0, ".index")), new byte[] {1, 2, 3});
        try (var log = SegmentedLog.open(dir, 256)) { assertEquals(20, log.nextOffset()); assertEquals(5, log.read(5).baseOffset()); }
        Files.write(dir.resolve(Segment.name(0, ".index")), new byte[32]);
        try (var log = SegmentedLog.open(dir, 256)) { assertEquals(20, log.nextOffset()); }
    }

    /** Property: cutting the active segment at any byte keeps exactly the complete frames before the cut. */
    @Test
    void tornTailAtEveryByte() throws IOException {
        var src = dir.resolve("src");
        var ends = new ArrayList<long[]>(); // {fileEndPos, nextOffset}
        try (var log = SegmentedLog.open(src)) {
            var r = new Random(1);
            for (int i = 0; i < 12; i++) { log.append(recs(r, 4, 40), i); }
        }
        var file = src.resolve(Segment.name(0, ".log"));
        byte[] full = Files.readAllBytes(file);
        { // compute frame boundaries
            var bb = ByteBuffer.wrap(full);
            long pos = 0, next = 0;
            ends.add(new long[] {0, 0});
            while (pos < full.length) { int len = bb.getInt((int) pos); var rb = decodeAt(full, pos, len); pos += 4 + len; next = rb.nextOffset(); ends.add(new long[] {pos, next}); }
        }
        for (int cut = 0; cut <= full.length; cut++) {
            var d = dir.resolve("cut" + cut);
            Files.createDirectories(d);
            Files.write(d.resolve(Segment.name(0, ".log")), java.util.Arrays.copyOf(full, cut));
            long expectPos = 0, expectNext = 0;
            for (var e : ends) if (e[0] <= cut) { expectPos = e[0]; expectNext = e[1]; }
            try (var log = SegmentedLog.open(d)) {
                assertEquals(expectNext, log.nextOffset(), "cut " + cut);
                assertEquals(cut - expectPos, log.recoveredTruncatedBytes(), "cut " + cut);
                assertEquals(expectPos, Files.size(d.resolve(Segment.name(0, ".log"))));
                log.append(List.of(b("after")), 0); // log stays appendable after recovery
                assertArrayEquals(b("after"), log.read(expectNext).records().get(0));
            }
        }
    }

    private static RecordBatch decodeAt(byte[] full, long pos, int len) {
        try { return RecordBatch.decode(ByteBuffer.wrap(full, (int) pos, 4 + len).slice()); } catch (Exception e) { throw new AssertionError(e); }
    }

    /** Property: random corruption (bit flips, garbage) in the tail never yields a corrupt or out-of-order batch. */
    @RepeatedTest(25)
    void randomTailCorruptionRecoversPrefix() throws Exception {
        var r = new Random(System.nanoTime());
        var written = new ArrayList<List<byte[]>>();
        try (var log = SegmentedLog.open(dir)) {
            int n = 1 + r.nextInt(30);
            for (int i = 0; i < n; i++) { var x = recs(r, 5, 64); written.add(x); log.append(x, i); }
        }
        var file = dir.resolve(Segment.name(0, ".log"));
        try (var ch = FileChannel.open(file, StandardOpenOption.WRITE, StandardOpenOption.READ)) {
            long size = ch.size();
            switch (r.nextInt(3)) {
                case 0 -> { long p = r.nextLong(size); var one = ByteBuffer.allocate(1); ch.read(one, p); one.put(0, (byte) (one.get(0) ^ (1 << r.nextInt(8)))); ch.write(one.flip(), p); }
                case 1 -> { var g = new byte[r.nextInt(200)]; r.nextBytes(g); ch.write(ByteBuffer.wrap(g), size); }
                default -> ch.truncate(r.nextLong(size + 1));
            }
        }
        try (var log = SegmentedLog.open(dir)) {
            var all = log.readAll();
            long o = 0;
            for (int i = 0; i < all.size(); i++) {
                assertEquals(o, all.get(i).baseOffset());
                var exp = written.get(i);
                assertEquals(exp.size(), all.get(i).count());
                for (int k = 0; k < exp.size(); k++) assertArrayEquals(exp.get(k), all.get(i).records().get(k));
                o = all.get(i).nextOffset();
            }
            assertEquals(o, log.nextOffset());
        }
    }

    @Test
    void gapAfterTornSealedSegmentDropsLaterSegments() throws IOException {
        try (var log = SegmentedLog.open(dir, 200)) { for (int i = 0; i < 10; i++) log.append(List.of(new byte[60]), 0); }
        assertTrue(Files.exists(dir.resolve(Segment.name(2, ".log"))));
        Files.delete(dir.resolve(Segment.name(0, ".index"))); // force scan of sealed segment 0
        try (var ch = FileChannel.open(dir.resolve(Segment.name(0, ".log")), StandardOpenOption.WRITE)) { ch.truncate(ch.size() - 1); }
        try (var log = SegmentedLog.open(dir, 200)) {
            assertEquals(1, log.nextOffset());
            assertEquals(1, log.segmentCount());
            assertTrue(log.recoveredTruncatedBytes() > 0);
        }
        assertFalse(Files.exists(dir.resolve(Segment.name(2, ".log"))));
    }

    @ParameterizedTest
    @EnumSource(Durability.class)
    void writerCommitsConcurrentProducersInOrder(Durability mode) throws Exception {
        try (var log = SegmentedLog.open(dir, 4096)) {
            var w = new LedgerWriter(log, mode, mode == Durability.GROUP ? 50_000 : 0, 128, 1024);
            assertEquals(0, w.snapshot().commitSeq());
            assertEquals(mode, w.durability());
            int producers = 8, per = 200;
            var futures = new ArrayList<CompletableFuture<Long>>();
            var threads = new ArrayList<Thread>();
            var lock = new Object();
            for (int p = 0; p < producers; p++) {
                int id = p;
                threads.add(Thread.ofVirtual().start(() -> {
                    for (int i = 0; i < per; i++) {
                        try { var f = w.append(List.of(b(id + ":" + i))); synchronized (lock) { futures.add(f); } }
                        catch (InterruptedException e) { throw new RuntimeException(e); }
                    }
                }));
            }
            for (var t : threads) t.join();
            var offsets = new java.util.TreeSet<Long>();
            for (var f : futures) offsets.add(f.get());
            assertEquals(producers * per, offsets.size());
            assertEquals(0L, offsets.first());
            assertEquals(producers * per - 1L, offsets.last());
            var s = w.snapshot();
            assertEquals(producers * per, s.nextOffset());
            assertEquals(producers * per, s.committedRecords());
            assertEquals(producers * per, s.committedBatches());
            if (mode == Durability.NONE) { assertEquals(0, s.fsyncs()); }
            if (mode.waitsForFsync()) assertEquals(s.nextOffset(), s.durableOffset());
            if (mode == Durability.EACH) assertEquals(producers * per, s.fsyncs());
            if (mode == Durability.GROUP) assertTrue(s.fsyncs() < producers * per, "group commit batched: " + s.fsyncs());
            w.close();
            w.close();
            assertTrue(w.append(List.of(b("late"))).isCompletedExceptionally());
            // per-producer order preserved
            var last = new long[producers];
            java.util.Arrays.fill(last, -1);
            for (var rb : log.readAll()) {
                var str = new String(rb.records().get(0), StandardCharsets.UTF_8).split(":");
                int id = Integer.parseInt(str[0]), i = Integer.parseInt(str[1]);
                assertEquals(last[id] + 1, i);
                last[id] = i;
            }
        }
    }

    @Test
    void snapshotsAreImmutableAndMonotonic() throws Exception {
        try (var log = SegmentedLog.open(dir); var w = new LedgerWriter(log, Durability.GROUP)) {
            var s0 = w.snapshot();
            w.append(List.of(b("a"), b("b"))).get();
            var s1 = w.snapshot();
            assertEquals(0, s0.nextOffset());
            assertEquals(2, s1.nextOffset());
            assertTrue(s1.commitSeq() > s0.commitSeq());
            assertTrue(s1.lastCommitNanos() >= s0.lastCommitNanos());
            assertEquals(1, s1.fsyncs());
            assertEquals(1, s1.segmentCount());
            assertEquals(0, s1.startOffset());
            assertTrue(s1.committedBytes() > 0);
            // committed data readable up to the snapshot bound
            assertArrayEquals(b("b"), log.read(s1.nextOffset() - 1).records().get(1));
        }
    }

    @Test
    void writerRejectsBadInputAndFailsOnIoError() throws Exception {
        var log = SegmentedLog.open(dir);
        var w = new LedgerWriter(log, Durability.GROUP);
        assertThrows(IllegalArgumentException.class, () -> w.append(List.of()));
        log.close(); // channel closed under the writer -> IOException on write
        var f = w.append(List.of(b("x")));
        assertThrows(java.util.concurrent.ExecutionException.class, f::get);
        assertTrue(w.append(List.of(b("y"))).isCompletedExceptionally());
        w.close();
    }

    @Test
    void reopenAfterWriterPreservesEverything() throws Exception {
        try (var log = SegmentedLog.open(dir, 1000); var w = new LedgerWriter(log, Durability.NONE)) {
            for (int i = 0; i < 100; i++) w.append(List.of(b("m" + i)));
            w.append(List.of(b("end"))).get();
        }
        try (var log = SegmentedLog.open(dir, 1000)) {
            assertEquals(101, log.nextOffset());
            assertArrayEquals(b("m42"), log.read(42).records().get(0));
        }
        assertThrows(IllegalArgumentException.class, () -> SegmentedLog.open(dir, 1));
    }

    @Test
    void defaultIsAsyncAckedBeforeFsyncThenDurableInBackground() throws Exception {
        try (var log = SegmentedLog.open(dir); var w = new LedgerWriter(log)) {
            assertEquals(Durability.ASYNC, w.durability());
            assertEquals(Durability.ASYNC, LedgerWriter.DEFAULT_DURABILITY);
            long last = -1;
            for (int i = 0; i < 500; i++) last = w.append(List.of(b("a" + i))).get();
            assertEquals(499, last);
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (w.snapshot().durableOffset() < 500 && System.nanoTime() < deadline) Thread.sleep(1);
            var s = w.snapshot();
            assertEquals(500, s.durableOffset());
            assertEquals(0, s.unsyncedRecords());
            assertTrue(s.fsyncs() >= 1);
        }
    }

    @Test
    void perAppendGroupOverrideOnAsyncWriterWaitsForFsync() throws Exception {
        try (var log = SegmentedLog.open(dir); var w = new LedgerWriter(log, Durability.ASYNC, 0, 1024, 1 << 20, 50_000_000L)) {
            w.append(List.of(b("fast"))).get();
            long base = w.append(List.of(b("x"), b("y")), Durability.GROUP).get();
            assertTrue(w.snapshot().durableOffset() >= base + 2, "durable when acked: " + w.snapshot());
            // EACH as an override behaves like GROUP
            long b2 = w.append(List.of(b("z")), Durability.EACH).get();
            assertTrue(w.snapshot().durableOffset() >= b2 + 1);
        }
    }

    @Test
    void perAppendAsyncOverrideOnGroupWriterSkipsFsync() throws Exception {
        try (var log = SegmentedLog.open(dir); var w = new LedgerWriter(log, Durability.GROUP)) {
            for (int i = 0; i < 20; i++) w.append(List.of(b("q")), Durability.ASYNC).get();
            assertEquals(0, w.snapshot().fsyncs(), "no fsync on the ack path");
            assertEquals(20, w.snapshot().nextOffset());
            w.append(List.of(b("sync")), Durability.GROUP).get();
            assertEquals(1, w.snapshot().fsyncs());
            assertEquals(21, w.snapshot().durableOffset());
        }
    }

    @Test
    void noneWriterServesGroupOverridesAndCloseSyncsTail() throws Exception {
        try (var log = SegmentedLog.open(dir)) {
            var w = new LedgerWriter(log, Durability.NONE);
            for (int i = 0; i < 10; i++) w.append(List.of(b("n"))).get();
            Thread.sleep(20);
            assertEquals(0, w.snapshot().fsyncs());
            assertEquals(10, w.snapshot().unsyncedRecords());
            w.append(List.of(b("g")), Durability.GROUP).get();
            assertTrue(w.snapshot().durableOffset() >= 11);
            w.append(List.of(b("n2"))).get();
            w.close();
            assertEquals(12, w.snapshot().durableOffset());
            assertEquals(0, w.snapshot().unsyncedRecords());
        }
    }

    @Test
    void eachWriterWithAsyncOverride() throws Exception {
        try (var log = SegmentedLog.open(dir); var w = new LedgerWriter(log, Durability.EACH)) {
            w.append(List.of(b("a")), Durability.ASYNC).get();
            assertEquals(0, w.snapshot().fsyncs());
            w.append(List.of(b("b"))).get();
            assertEquals(1, w.snapshot().fsyncs());
        }
    }

    @Test
    void asyncWriterFailsPendingOverridesOnIoError() throws Exception {
        var log = SegmentedLog.open(dir);
        var w = new LedgerWriter(log, Durability.ASYNC, 0, 1024, 1 << 20, 10_000_000_000L);
        w.append(List.of(b("ok"))).get();
        log.close();
        var f = w.append(List.of(b("x")), Durability.GROUP);
        assertThrows(java.util.concurrent.ExecutionException.class, () -> f.get(10, java.util.concurrent.TimeUnit.SECONDS));
        w.close();
    }

    // ---- S1c: never block under a caller's lock; ASYNC fsync floor; NONE leaves the syncer alone ----

    @Test
    void appendNoWaitGoesPastTheSoftBoundWithoutWaitingAndKeepsOrder() throws Exception {
        try (var log = SegmentedLog.open(dir); var w = new LedgerWriter(log, Durability.NONE, 0, 4096, 1 << 20)) {
            var fs = new ArrayList<CompletableFuture<Long>>();
            long t0 = System.nanoTime();
            for (int i = 0; i < 5000; i++) fs.add(w.appendNoWait(List.of(b("n" + i)), null)); // past the soft bound
            assertTrue(System.nanoTime() - t0 < 5_000_000_000L);
            for (int i = 0; i < fs.size(); i++) assertEquals(i, fs.get(i).get());
            w.awaitCapacity(); // drained: returns at once
            assertEquals(5000, w.snapshot().nextOffset());
        }
    }

    @Test
    void awaitCapacityBlocksUntilTheWriterDrains() throws Exception {
        try (var log = SegmentedLog.open(dir); var w = new LedgerWriter(log, Durability.NONE, 0, 4, 1 << 20)) {
            for (int i = 0; i < 2000; i++) w.appendNoWait(List.of(b("x".repeat(512))), null);
            w.awaitCapacity();
            var f = w.append(List.of(b("after"))); // append() still applies backpressure
            assertEquals(2000, f.get());
        }
    }

    @Test
    void noneWriterNeverFsyncsInTheBackground() throws Exception {
        try (var log = SegmentedLog.open(dir); var w = new LedgerWriter(log, Durability.NONE)) {
            for (int i = 0; i < 2000; i++) w.append(List.of(b("n" + i)));
            w.append(List.of(b("end"))).get();
            Thread.sleep(50);
            assertEquals(0, w.snapshot().fsyncs(), "NONE must not wake the syncer");
            // a GROUP override on a NONE writer is still served by the syncer
            long base = w.append(List.of(b("g")), Durability.GROUP).get();
            assertTrue(w.snapshot().durableOffset() > base);
        }
    }

    @Test
    void asyncDefaultCoalescesFsyncsToTheFloor() throws Exception {
        assertEquals(1_000_000L, LedgerWriter.DEFAULT_FSYNC_INTERVAL_NANOS);
        try (var log = SegmentedLog.open(dir); var w = new LedgerWriter(log)) {
            long t0 = System.nanoTime();
            long last = -1;
            for (int i = 0; i < 20_000; i++) last = w.append(List.of(b("a" + i))).get();
            long ms = (System.nanoTime() - t0) / 1_000_000;
            long fs = w.snapshot().fsyncs();
            assertTrue(fs <= ms + 2, "fsyncs " + fs + " in " + ms + " ms");
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (w.snapshot().durableOffset() <= last && System.nanoTime() < deadline) Thread.sleep(1);
            assertEquals(last + 1, w.snapshot().durableOffset());
        }
    }
}
