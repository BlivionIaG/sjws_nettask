package sjws_nettask.messaging.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The narrow #270 export/install API of the ledger: segment files, frame positions, replace, writer reload. */
class SnapshotLedgerApiTest {

    @TempDir Path dir;

    static List<byte[]> recs(int i, int n) {
        var out = new ArrayList<byte[]>();
        for (int k = 0; k < n; k++) out.add(("r" + i + "." + k + "-" + "p".repeat(40)).getBytes(StandardCharsets.UTF_8));
        return out;
    }

    static List<String> all(SegmentedLog log) throws Exception {
        var out = new ArrayList<String>();
        for (var b : log.readAll()) {
            long o = b.baseOffset();
            for (var r : b.records()) out.add((o++) + ":" + new String(r, StandardCharsets.UTF_8));
        }
        return out;
    }

    @Test
    void positionsOfFramesAndSegmentFiles() throws Exception {
        try (var log = SegmentedLog.open(dir.resolve("a"), 4096)) {
            var bases = new ArrayList<Long>();
            for (int i = 0; i < 200; i++) bases.add(log.append(recs(i, 2), i));
            var files = log.segmentFiles();
            assertTrue(files.size() > 3);
            assertTrue(files.getLast().active());
            assertFalse(files.getFirst().active());
            for (int i = 0; i + 1 < files.size(); i++) assertEquals(files.get(i).nextOffset(), files.get(i + 1).baseOffset());
            for (var f : files) {
                long base = f.baseOffset();
                assertEquals(0, log.positionOf(base, base), "first frame at 0");
                assertEquals(Files.size(f.file()), log.positionOf(base, f.nextOffset()), "next offset: the size");
                assertEquals(-1, log.positionOf(base, base + 1), "inside a 2-record batch: no frame starts there");
                assertEquals(-1, log.positionOf(base, f.nextOffset() + 1));
                if (base > 0) assertEquals(-1, log.positionOf(base, base - 1));
                long prev = -1;
                for (long b : bases) {
                    if (b < base || b >= f.nextOffset()) continue;
                    long p = log.positionOf(base, b);
                    assertTrue(p > prev, "frames in file order");
                    prev = p;
                }
            }
            assertEquals(-1, log.positionOf(12345, 12345), "no such segment");
        }
    }

    @Test
    void replaceWithSwapsEveryFileAndIgnoresStrangers() throws Exception {
        var staged = dir.resolve("staged");
        List<String> want;
        try (var src = SegmentedLog.open(dir.resolve("src"), 4096)) {
            for (int i = 0; i < 120; i++) src.append(recs(i, 1), i);
            src.force();
            want = all(src);
            Files.createDirectories(staged);
            for (var f : src.segmentFiles()) Files.copy(f.file(), staged.resolve(f.file().getFileName()));
        }
        var first = dir.resolve("src").resolve(String.format("%020d.log", 0)).getFileName().toString();
        assertTrue(Files.exists(staged.resolve(first)));
        Files.write(staged.resolve("README"), new byte[] {1});
        Files.write(staged.resolve("1.log"), new byte[] {1});
        Files.write(staged.resolve(String.format("%020d.index", 0)), new byte[] {1});
        Files.write(staged.resolve("abcdefghijklmnopqrst.log"), new byte[] {1});
        var dst = dir.resolve("dst");
        try (var log = SegmentedLog.open(dst, 4096)) {
            for (int i = 0; i < 300; i++) log.append(recs(1000 + i, 1), i);
            log.force();
            for (var stale : List.of(".cmp", ".compacted.tmp", ".index.tmp")) Files.write(dst.resolve(String.format("%020d", 7) + stale), new byte[] {1});
            Files.write(dst.resolve("keep.me"), new byte[] {9});
            var writer = new LedgerWriter(log, Durability.ASYNC);
            try {
                log.replaceWith(staged);
                writer.reload();
                assertEquals(want, all(log));
                assertEquals(log.nextOffset(), writer.snapshot().nextOffset());
                assertEquals(log.nextOffset(), writer.snapshot().durableOffset());
                assertTrue(Files.exists(dst.resolve("keep.me")), "only numbered segment files are replaced");
                assertFalse(Files.exists(dst.resolve(String.format("%020d.cmp", 7))));
                assertFalse(Files.exists(dst.resolve("README")));
                // Appends continue at the installed end.
                long at = writer.append(List.of("after".getBytes(StandardCharsets.UTF_8))).get();
                assertEquals(want.size(), at);
                assertEquals(log, writer.log());
            } finally {
                writer.close();
            }
        }
        try (var reopened = SegmentedLog.open(dst, 4096)) {
            assertEquals(want.size() + 1, all(reopened).size());
        }
    }

    @Test
    void replaceKeepsCompactionMarkers() throws Exception {
        var staged = Files.createDirectories(dir.resolve("staged"));
        try (var src = SegmentedLog.open(dir.resolve("src"), 2048)) {
            for (int i = 0; i < 60; i++) src.append(recs(i, 1), i);
            src.force();
            for (var f : src.segmentFiles()) Files.copy(f.file(), staged.resolve(f.file().getFileName()));
            var f0 = src.segmentFiles().getFirst();
            Files.write(staged.resolve(f0.marker().getFileName()), ByteBuffer.allocate(8).putLong(f0.nextOffset()).array());
        }
        try (var log = SegmentedLog.open(dir.resolve("dst"), 2048)) {
            log.replaceWith(staged);
            var f0 = log.segmentFiles().getFirst();
            assertTrue(Files.exists(f0.marker()));
            assertTrue(log.segments().getFirst().compacted());
            assertEquals(60, all(log).size());
        }
    }

    @Test
    void replicaAndReplicatorDefaults() throws Exception {
        Replica r = new Replica() {
            @Override public java.util.concurrent.CompletableFuture<Long> append(List<byte[]> records, Durability mode) { return null; }
            @Override public java.util.concurrent.CompletableFuture<Long> appendNoWait(List<byte[]> records, Durability mode) { return null; }
            @Override public void awaitCapacity() { }
            @Override public boolean leaderReady() { return false; }
            @Override public long term() { return 0; }
            @Override public String leaderName() { return null; }
            @Override public String leaderUrl() { return null; }
            @Override public java.util.concurrent.CompletableFuture<Void> leaderRead() { return null; }
            @Override public void close() { }
        };
        assertEquals(0, r.logStartOffset());
        assertTrue(r.advanceLogStart(5).isCompletedExceptionally());
        var seen = new ArrayList<Object>();
        PartitionReplicator p = new PartitionReplicator() {
            @Override public java.util.Set<String> topics() { return java.util.Set.of("t"); }
            @Override public Replica open(String topic, LedgerWriter local, Durability mode, java.util.function.Consumer<List<byte[]>> onApplied) {
                seen.add(onApplied);
                return r;
            }
            @Override public void close() { }
        };
        java.util.function.Consumer<List<byte[]>> applied = x -> { };
        var hooks = new ReplicaHooks(applied, x -> { }, new java.util.concurrent.locks.ReentrantLock(), () -> { }, null);
        assertEquals(r, p.open("t", null, Durability.ASYNC, hooks));
        assertEquals(List.of(applied), seen);
    }

    @Test
    void reloadOnAnIdleWriter() throws Exception {
        try (var log = SegmentedLog.open(dir.resolve("q"), 4096)) {
            var w = new LedgerWriter(log, Durability.ASYNC);
            try {
                w.reload(); // idle: fine
                assertEquals(log.nextOffset(), w.snapshot().nextOffset());
            } finally {
                w.close();
            }
        }
    }

    /** #288 Codex P1: force() must publish only the watermark captured before log.force(), not written after. */
    @Test
    void forcePublishesWatermarkCapturedBeforeFsync() throws Exception {
        try (var log = SegmentedLog.open(dir.resolve("force-wm"), 1 << 20)) {
            var w = new LedgerWriter(log, Durability.ASYNC, 0, 64, 4 << 20, 0);
            try {
                w.pauseSync(); // only our force() advances durable
                w.append(List.of("a".getBytes(StandardCharsets.UTF_8))).get();
                assertEquals(1, w.snapshot().nextOffset());
                var appended = new java.util.concurrent.atomic.AtomicBoolean();
                w.afterLogForce = () -> {
                    try {
                        w.append(List.of("b".getBytes(StandardCharsets.UTF_8))).get();
                        appended.set(true);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                };
                w.force();
                assertTrue(appended.get());
                assertEquals(2, w.snapshot().nextOffset(), "writer advanced during force");
                assertEquals(1, w.snapshot().durableOffset(), "durable covers only the pre-fsync watermark");
            } finally {
                w.afterLogForce = null;
                w.close();
            }
        }
    }

    /** #288 Codex P1: replaceWith invalidates an in-flight prepareCompaction so commit cannot close installed segments. */
    @Test
    void replaceWithInvalidatesPendingCompaction() throws Exception {
        var staged = Files.createDirectories(dir.resolve("cmp-staged"));
        List<String> want;
        try (var src = SegmentedLog.open(dir.resolve("cmp-src"), 2048)) {
            for (int i = 0; i < 40; i++) src.append(recs(i, 1), i);
            src.force();
            while (src.segmentFiles().size() < 2) src.append(recs(1000 + (int) src.nextOffset(), 1), 0);
            src.force();
            want = all(src);
            for (var f : src.segmentFiles()) {
                Files.copy(f.file(), staged.resolve(f.file().getFileName()));
                if (Files.exists(f.marker())) Files.copy(f.marker(), staged.resolve(f.marker().getFileName()));
            }
        }
        try (var log = SegmentedLog.open(dir.resolve("cmp-dst"), 2048)) {
            for (int i = 0; i < 40; i++) log.append(recs(i, 1), i);
            log.force();
            while (log.segmentFiles().size() < 2) log.append(recs(2000 + (int) log.nextOffset(), 1), 0);
            log.force();
            long base = log.segmentFiles().getFirst().baseOffset();
            log.prepareCompaction(base, o -> o % 2 == 0);
            assertTrue(Files.isRegularFile(dir.resolve("cmp-dst").resolve(String.format("%020d.cmp", base))));
            log.replaceWith(staged);
            log.commitCompaction(base); // must no-op: epoch moved / .cmp gone
            assertFalse(Files.isRegularFile(dir.resolve("cmp-dst").resolve(String.format("%020d.cmp", base))));
            assertEquals(want, all(log));
            // Installed segments stay open for reads (no ClosedChannelException).
            assertEquals(want.size(), log.nextOffset());
        }
    }
}
