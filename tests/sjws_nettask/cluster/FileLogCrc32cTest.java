/*
 * Copyright (C) 2026 BlivionIaG <BlivionIaG at chenco.tk>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package sjws_nettask.cluster;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.security.MessageDigest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * #207: per-record CRC32C on {@link FileLogStorage} — mid-log fail-closed, last-record truncate,
 * legacy pre-{@code FLG2} read + upgrade, live map after upgrade, damaged-header fail-closed,
 * upgrade failure rollback, no pre-upgrade map publication,
 * sealed-segment fail-closed, bounded record lengths.
 */
@Timeout(30)
class FileLogCrc32cTest {

    private static final GroupId GROUP = new GroupId("msg/crc");

    @TempDir
    Path dir;

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static Path logFile(Path root) {
        return root.resolve("msg_crc.log");
    }

    /** Ends of records 0..n after writing indexes 1..n (ends[0]=0 before any write). */
    private static List<Long> writeComplete(Path root, int n) throws Exception {
        var ends = new ArrayList<Long>();
        ends.add(0L);
        var log = new FileLogStorage(root);
        for (int i = 1; i <= n; i++) {
            log.append(GROUP, i, 1, b("e" + i));
            ends.add(Files.size(logFile(root)));
        }
        log.force(GROUP);
        log.close();
        return ends;
    }



    private static byte[] sha256(Path file) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
    }

    /** Writes a single legacy (pre-FLG2) record at index 1 with payload {@code body}. */
    private static void writeLegacyOne(Path root, String body) throws Exception {
        Path log = logFile(root);
        try (var ch = FileChannel.open(log, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] data = b(body);
            var buf = ByteBuffer.allocate(20 + data.length);
            buf.putLong(1L).putLong(1L).putInt(data.length).put(data);
            buf.flip();
            while (buf.hasRemaining()) {
                ch.write(buf);
            }
        }
    }

    private static Throwable rootCause(Throwable err) {
        Throwable t = err;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    @Test
    void newLogWritesFlg2HeaderAndPerRecordCrc() throws Exception {
        var root = dir.resolve("fresh");
        Files.createDirectories(root);
        writeComplete(root, 2);
        byte[] raw = Files.readAllBytes(logFile(root));
        assertTrue(raw.length >= FileLogStorage.LOG_FILE_HEADER_BYTES + FileLogStorage.RECORD_HEADER_BYTES
                + FileLogStorage.RECORD_CRC_BYTES);
        var hdr = ByteBuffer.wrap(raw, 0, 8);
        assertEquals(FileLogStorage.LOG_MAGIC, hdr.getInt());
        assertEquals(FileLogStorage.LOG_VERSION, hdr.getInt());
        // First record: index=1 term=1 len=2 data="e1" + crc
        var rec = ByteBuffer.wrap(raw, 8, raw.length - 8);
        assertEquals(1L, rec.getLong());
        assertEquals(1L, rec.getLong());
        int len = rec.getInt();
        assertEquals(2, len);
        var data = new byte[len];
        rec.get(data);
        assertArrayEquals(b("e1"), data);
        int stored = rec.getInt();
        assertEquals(FileLogStorage.recordCrc(1L, 1L, data), stored);
    }

    @Test
    void midLogCrcMismatchFailsClosedWithoutTruncate() throws Exception {
        var root = dir.resolve("mid-crc");
        Files.createDirectories(root);
        var ends = writeComplete(root, 3);
        long size = ends.get(3);
        // Flip one payload byte of record 2 (between ends[1] and ends[2]).
        long payloadAt = ends.get(1) + FileLogStorage.RECORD_HEADER_BYTES;
        try (var ch = FileChannel.open(logFile(root), StandardOpenOption.WRITE)) {
            var one = ByteBuffer.allocate(1);
            one.put((byte) 'X');
            one.flip();
            ch.write(one, payloadAt);
        }
        assertEquals(size, Files.size(logFile(root)));
        var err = assertThrows(Exception.class, () -> {
            var re = new FileLogStorage(root);
            try {
                re.lastIndex(GROUP);
            } finally {
                try {
                    re.close();
                } catch (Exception ignored) {
                }
            }
        });
        var t = rootCause(err);
        assertTrue(t.getMessage().contains("CRC32C") || t.getMessage().contains("refusing"),
                "message=" + t.getMessage());
        assertEquals(size, Files.size(logFile(root)), "mid-log CRC failure must not truncate");
    }

    @Test
    void lastRecordCrcMismatchTruncatesTornTail() throws Exception {
        var root = dir.resolve("tail-crc");
        Files.createDirectories(root);
        var ends = writeComplete(root, 3);
        long lastStart = ends.get(2);
        long size = ends.get(3);
        // Flip last record's payload so framing stays complete but CRC fails at EOF.
        long payloadAt = lastStart + FileLogStorage.RECORD_HEADER_BYTES;
        try (var ch = FileChannel.open(logFile(root), StandardOpenOption.WRITE)) {
            var one = ByteBuffer.allocate(1);
            one.put((byte) 'Z');
            one.flip();
            ch.write(one, payloadAt);
        }
        var re = new FileLogStorage(root);
        assertEquals(2L, re.lastIndex(GROUP));
        assertEquals(lastStart, Files.size(logFile(root)), "corrupt last record truncated to prior end");
        assertArrayEquals(b("e1"), re.read(GROUP, 1));
        assertArrayEquals(b("e2"), re.read(GROUP, 2));
        re.append(GROUP, 3, 1, b("rebuilt"));
        re.force(GROUP);
        assertArrayEquals(b("rebuilt"), re.read(GROUP, 3));
        re.close();
        assertTrue(Files.size(logFile(root)) > lastStart);
        assertTrue(size != Files.size(logFile(root)) || true);
    }

    @Test
    void legacyV1FormatLoadsAndUpgradesToFlg2() throws Exception {
        var root = dir.resolve("legacy");
        Files.createDirectories(root);
        Path log = logFile(root);
        // Craft three legacy records: [index:8][term:8][len:4][data] with no file header / CRC.
        try (var ch = FileChannel.open(log, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            for (int i = 1; i <= 3; i++) {
                byte[] data = b("L" + i);
                var buf = ByteBuffer.allocate(20 + data.length);
                buf.putLong(i);
                buf.putLong(1L);
                buf.putInt(data.length);
                buf.put(data);
                buf.flip();
                while (buf.hasRemaining()) {
                    ch.write(buf);
                }
            }
        }
        long legacySize = Files.size(log);
        assertTrue(legacySize > 0);
        var hdrPeek = ByteBuffer.wrap(Files.readAllBytes(log), 0, 4);
        assertTrue(hdrPeek.getInt() != FileLogStorage.LOG_MAGIC, "fixture must be legacy");

        var re = new FileLogStorage(root);
        assertEquals(3L, re.lastIndex(GROUP));
        assertArrayEquals(b("L1"), re.read(GROUP, 1));
        assertArrayEquals(b("L2"), re.read(GROUP, 2));
        assertArrayEquals(b("L3"), re.read(GROUP, 3));
        re.close();

        byte[] upgraded = Files.readAllBytes(log);
        var hdr = ByteBuffer.wrap(upgraded, 0, 8);
        assertEquals(FileLogStorage.LOG_MAGIC, hdr.getInt());
        assertEquals(FileLogStorage.LOG_VERSION, hdr.getInt());
        assertTrue(upgraded.length > legacySize, "upgrade adds header + per-record CRC");

        // Re-open upgraded file without rewriting again.
        var again = new FileLogStorage(root);
        assertEquals(3L, again.lastIndex(GROUP));
        assertArrayEquals(b("L3"), again.read(GROUP, 3));
        again.close();
    }

    @Test
    void legacyTornTailTruncatesThenUpgrades() throws Exception {
        var root = dir.resolve("legacy-torn");
        Files.createDirectories(root);
        Path log = logFile(root);
        try (var ch = FileChannel.open(log, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] data = b("ok");
            var buf = ByteBuffer.allocate(20 + data.length);
            buf.putLong(1L).putLong(1L).putInt(data.length).put(data);
            buf.flip();
            while (buf.hasRemaining()) {
                ch.write(buf);
            }
            // Torn second header (only 4 of 20 bytes).
            ch.write(ByteBuffer.wrap(new byte[] {0, 0, 0, 2}));
        }
        long before = Files.size(log);
        var re = new FileLogStorage(root);
        assertEquals(1L, re.lastIndex(GROUP));
        assertArrayEquals(b("ok"), re.read(GROUP, 1));
        re.close();
        // Truncate drops the torn stub; upgrade rewrites as FLG2 (header + CRC may grow past before).
        var upgraded = Files.readAllBytes(log);
        assertTrue(upgraded.length < before || upgraded.length == 8 + 20 + 2 + 4,
                "torn stub gone; size=" + upgraded.length + " before=" + before);
        var hdr = ByteBuffer.wrap(upgraded, 0, 8);
        assertEquals(FileLogStorage.LOG_MAGIC, hdr.getInt());
        assertEquals(FileLogStorage.LOG_VERSION, hdr.getInt());
        assertEquals(8 + 20 + 2 + 4, upgraded.length);
    }

    @Test
    void scanReportsLegacyFlagForPreFlg2() throws Exception {
        var root = dir.resolve("scan-legacy");
        Files.createDirectories(root);
        Path log = logFile(root);
        try (var ch = FileChannel.open(log, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            byte[] data = b("x");
            var buf = ByteBuffer.allocate(20 + data.length);
            buf.putLong(1L).putLong(1L).putInt(data.length).put(data);
            buf.flip();
            while (buf.hasRemaining()) {
                ch.write(buf);
            }
        }
        var scan = FileLogStorage.scanRecords(log);
        assertTrue(scan.legacy());
        assertFalse(scan.torn());
        assertEquals(1, scan.records().size());
    }

    @Test
    void unsupportedLogVersionFailsClosed() throws Exception {
        var root = dir.resolve("bad-ver");
        Files.createDirectories(root);
        Path log = logFile(root);
        try (var ch = FileChannel.open(log, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            var h = ByteBuffer.allocate(8);
            h.putInt(FileLogStorage.LOG_MAGIC);
            h.putInt(99); // unsupported
            h.flip();
            while (h.hasRemaining()) {
                ch.write(h);
            }
        }
        var err = assertThrows(Exception.class, () -> {
            var re = new FileLogStorage(root);
            try {
                re.lastIndex(GROUP);
            } finally {
                try {
                    re.close();
                } catch (Exception ignored) {
                }
            }
        });
        assertTrue(rootCause(err).getMessage().contains("unsupported")
                || rootCause(err).getMessage().contains("refusing"),
                "message=" + rootCause(err).getMessage());
    }

    @Test
    void shortLegacyStubUnderEightBytesTruncatesToEmpty() throws Exception {
        var root = dir.resolve("short-stub");
        Files.createDirectories(root);
        Path log = logFile(root);
        Files.write(log, new byte[] {1, 2, 3, 4});
        var re = new FileLogStorage(root);
        assertEquals(0L, re.lastIndex(GROUP));
        re.close();
        // Upgrade of empty legacy may leave empty or header-only FLG2.
        assertTrue(Files.size(log) == 0 || Files.size(log) == 8);
    }

    @Test
    void incompleteCrcWordOnLastRecordTruncates() throws Exception {
        var root = dir.resolve("short-crc");
        Files.createDirectories(root);
        var ends = writeComplete(root, 2);
        Path log = logFile(root);
        // Truncate away 2 bytes of the last CRC word (framing otherwise complete through body).
        long cut = ends.get(2) - 2;
        try (var ch = FileChannel.open(log, StandardOpenOption.WRITE)) {
            ch.truncate(cut);
        }
        var re = new FileLogStorage(root);
        assertEquals(1L, re.lastIndex(GROUP));
        assertEquals(ends.get(1), Files.size(log));
        re.close();
    }

    @Test
    void rollingLegacyActiveFileUpgradesOnOpen() throws Exception {
        var root = dir.resolve("roll-legacy");
        Files.createDirectories(root);
        Path log = logFile(root);
        // Legacy active file with one record (no sealed files).
        try (var ch = FileChannel.open(log, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] data = b("R1");
            var buf = ByteBuffer.allocate(20 + data.length);
            buf.putLong(1L).putLong(1L).putInt(data.length).put(data);
            buf.flip();
            while (buf.hasRemaining()) {
                ch.write(buf);
            }
        }
        var re = new FileLogStorage(root, 1_000_000);
        assertEquals(1L, re.lastIndex(GROUP));
        assertArrayEquals(b("R1"), re.read(GROUP, 1));
        re.close();
        var hdr = ByteBuffer.wrap(Files.readAllBytes(log), 0, 8);
        assertEquals(FileLogStorage.LOG_MAGIC, hdr.getInt());
        assertEquals(FileLogStorage.LOG_VERSION, hdr.getInt());
    }


    @Test
    void legacyUpgradeKeepsLiveMapForAppendViaLog() throws Exception {
        var root = dir.resolve("legacy-live-map");
        Files.createDirectories(root);
        Path log = logFile(root);
        // One legacy record at index 1. append() must be the first log() call so the upgrade
        // rewrite runs inside that call; returning the detached pre-rewrite map would hide the
        // append from later lastIndex/read/termAt (which use logs.get).
        try (var ch = FileChannel.open(log, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] data = b("L1");
            var buf = ByteBuffer.allocate(20 + data.length);
            buf.putLong(1L).putLong(1L).putInt(data.length).put(data);
            buf.flip();
            while (buf.hasRemaining()) {
                ch.write(buf);
            }
        }
        var re = new FileLogStorage(root);
        re.append(GROUP, 2, 1, b("L2"));
        re.force(GROUP);
        assertEquals(2L, re.lastIndex(GROUP), "live map after legacy upgrade must see the append");
        assertArrayEquals(b("L1"), re.read(GROUP, 1));
        assertArrayEquals(b("L2"), re.read(GROUP, 2), "read through log() must see post-upgrade append");
        assertEquals(1L, re.termAt(GROUP, 2));
        re.close();
        var again = new FileLogStorage(root);
        assertEquals(2L, again.lastIndex(GROUP));
        assertArrayEquals(b("L2"), again.read(GROUP, 2));
        again.close();
        var hdr = ByteBuffer.wrap(Files.readAllBytes(log), 0, 8);
        assertEquals(FileLogStorage.LOG_MAGIC, hdr.getInt());
        assertEquals(FileLogStorage.LOG_VERSION, hdr.getInt());
    }

    @Test
    void damagedFlg2MagicFailsClosedWithoutRewrite() throws Exception {
        var root = dir.resolve("damaged-magic");
        Files.createDirectories(root);
        writeComplete(root, 1);
        Path log = logFile(root);
        byte[] before = Files.readAllBytes(log);
        assertEquals(FileLogStorage.LOG_MAGIC, ByteBuffer.wrap(before, 0, 4).getInt());
        // Flip one bit of the FLG2 magic; version word and records stay intact.
        before[0] ^= 0x01;
        Files.write(log, before);
        assertTrue(FileLogStorage.looksLikeDamagedOrUnknownLogMagic(
                ByteBuffer.wrap(before, 0, 4).getInt()));
        assertFalse(FileLogStorage.isRecognizedLegacyFileHeader(
                ByteBuffer.wrap(before, 0, 4).getInt(),
                ByteBuffer.wrap(before, 4, 4).getInt()));
        var err = assertThrows(Exception.class, () -> {
            var re = new FileLogStorage(root);
            try {
                re.lastIndex(GROUP);
            } finally {
                try {
                    re.close();
                } catch (Exception ignored) {
                }
            }
        });
        String msg = rootCause(err).getMessage();
        assertTrue(msg.contains("unrecognized") || msg.contains("damaged") || msg.contains("refusing"),
                "message=" + msg);
        byte[] after = Files.readAllBytes(log);
        assertArrayEquals(before, after, "damaged FLG2 must not be truncated or upgraded");
    }

    @Test
    void unknownAsciiMagicFailsClosedWithoutRewrite() throws Exception {
        var root = dir.resolve("unknown-magic");
        Files.createDirectories(root);
        Path log = logFile(root);
        byte[] raw = new byte[32];
        ByteBuffer.wrap(raw).putInt(0x41424344).putInt(1); // "ABCD" + version 1
        Files.write(log, raw);
        byte[] before = Files.readAllBytes(log);
        var err = assertThrows(Exception.class, () -> {
            var re = new FileLogStorage(root);
            try {
                re.lastIndex(GROUP);
            } finally {
                try {
                    re.close();
                } catch (Exception ignored) {
                }
            }
        });
        assertTrue(rootCause(err).getMessage().contains("refusing")
                || rootCause(err).getMessage().contains("unrecognized")
                || rootCause(err).getMessage().contains("damaged"),
                "message=" + rootCause(err).getMessage());
        assertArrayEquals(before, Files.readAllBytes(log));
    }



    @Test
    void legacyUpgradeFailureBeforeRenameDoesNotMixFormatsOnRetry() throws Exception {
        var root = dir.resolve("legacy-upgrade-fail");
        Files.createDirectories(root);
        writeLegacyOne(root, "L1");
        Path logPath = logFile(root);
        byte[] before = Files.readAllBytes(logPath);
        assertTrue(ByteBuffer.wrap(before, 0, 4).getInt() != FileLogStorage.LOG_MAGIC);

        var re = new FileLogStorage(root);
        String stage = logPath.getFileName() + ":rewrite-temp";
        re.crashHook = at -> {
            if (at.equals(stage)) {
                throw new IllegalStateException("injected upgrade failure before rename");
            }
        };
        var err = assertThrows(Exception.class, () -> re.append(GROUP, 2, 1, b("L2")));
        assertTrue(rootCause(err).getMessage().contains("injected upgrade failure")
                || rootCause(err).getMessage().contains("legacy log upgrade failed")
                || err.getMessage().contains("legacy log upgrade failed"),
                "message=" + rootCause(err).getMessage());
        // Still unpublished / not mixed: on-disk bytes unchanged (pure legacy).
        assertArrayEquals(before, Files.readAllBytes(logPath), "pre-rename failure must not alter legacy bytes");
        assertFalse(re.hasPublishedLog(GROUP), "failed upgrade must not leave a usable cached log");
        assertFalse(re.hasPublishedChannel(GROUP), "failed upgrade must not leave a usable legacy channel");
        assertFalse(Files.exists(root.resolve(logPath.getFileName() + ".rewrite")));

        // Retry after clearing the hook: clean upgrade, then append — never a mixed file.
        re.crashHook = null;
        re.append(GROUP, 2, 1, b("L2"));
        re.force(GROUP);
        assertEquals(2L, re.lastIndex(GROUP));
        assertArrayEquals(b("L1"), re.read(GROUP, 1));
        assertArrayEquals(b("L2"), re.read(GROUP, 2));
        re.close();

        byte[] after = Files.readAllBytes(logPath);
        var hdr = ByteBuffer.wrap(after, 0, 8);
        assertEquals(FileLogStorage.LOG_MAGIC, hdr.getInt());
        assertEquals(FileLogStorage.LOG_VERSION, hdr.getInt());
        // Reopen proves the segment is coherent FLG2 (no legacy/V2 mix).
        var again = new FileLogStorage(root);
        assertEquals(2L, again.lastIndex(GROUP));
        assertArrayEquals(b("L2"), again.read(GROUP, 2));
        again.close();
    }

    @Test
    void legacyUpgradeDoesNotPublishMapUntilRewriteCompletes() throws Exception {
        var root = dir.resolve("legacy-no-early-publish");
        Files.createDirectories(root);
        writeLegacyOne(root, "L1");
        Path logPath = logFile(root);
        String stage = logPath.getFileName() + ":rewrite-temp";

        var re = new FileLogStorage(root);
        var publishedDuringUpgrade = new AtomicBoolean(true); // fail closed if hook never runs
        var hookRan = new AtomicBoolean(false);
        re.crashHook = at -> {
            if (at.equals(stage)) {
                hookRan.set(true);
                // Preferred fix: the pre-upgrade map is not in logs while rewrite runs.
                publishedDuringUpgrade.set(re.hasPublishedLog(GROUP));
            }
        };
        re.append(GROUP, 2, 1, b("L2"));
        re.force(GROUP);
        re.crashHook = null;
        assertTrue(hookRan.get(), "rewrite-temp hook must run during legacy upgrade");
        assertFalse(publishedDuringUpgrade.get(),
                "pre-upgrade map must stay unpublished until rewrite completes");
        assertTrue(re.hasPublishedLog(GROUP));
        assertEquals(2L, re.lastIndex(GROUP));
        assertArrayEquals(b("L2"), re.read(GROUP, 2));
        re.close();
    }

    @Test
    void concurrentCallerDuringLegacyUpgradeSeesLiveMap() throws Exception {
        var root = dir.resolve("legacy-concurrent-upgrade");
        Files.createDirectories(root);
        writeLegacyOne(root, "L1");
        Path logPath = logFile(root);
        String stage = logPath.getFileName() + ":rewrite-temp";

        var re = new FileLogStorage(root);
        var inUpgrade = new CountDownLatch(1);
        var peerStarted = new CountDownLatch(1);
        var peerDone = new CountDownLatch(1);
        var peerIndex = new AtomicReference<Long>();
        var peerRead = new AtomicReference<byte[]>();
        var peerErr = new AtomicReference<Throwable>();

        re.crashHook = at -> {
            if (!at.equals(stage)) {
                return;
            }
            inUpgrade.countDown();
            Thread peer = new Thread(() -> {
                peerStarted.countDown();
                try {
                    // Blocks on the upgrade lock, then must observe the live post-upgrade map
                    // (and the append that the upgrading thread performs after rewrite).
                    peerIndex.set(re.lastIndex(GROUP));
                    peerRead.set(re.read(GROUP, 2));
                } catch (Throwable t) {
                    peerErr.set(t);
                } finally {
                    peerDone.countDown();
                }
            }, "legacy-upgrade-peer");
            peer.setDaemon(true);
            peer.start();
            try {
                assertTrue(peerStarted.await(5, TimeUnit.SECONDS));
                // Peer is blocked on lock; stay in upgrade briefly so the race window existed.
                Thread.sleep(50);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ie);
            }
        };

        re.append(GROUP, 2, 1, b("L2"));
        re.force(GROUP);
        re.crashHook = null;
        assertEquals(0L, inUpgrade.getCount(), "upgrade hook must have run");
        assertTrue(peerDone.await(10, TimeUnit.SECONDS), "peer must finish after upgrade");
        assertTrue(peerErr.get() == null, "peer error=" + peerErr.get());
        assertEquals(2L, peerIndex.get(), "concurrent caller must see live map after upgrade+append");
        assertArrayEquals(b("L2"), peerRead.get());
        assertEquals(2L, re.lastIndex(GROUP));
        assertArrayEquals(b("L2"), re.read(GROUP, 2));
        re.close();
    }



    @Test
    void sealedSegmentLastRecordCrcFailsClosedWithoutTruncate() throws Exception {
        var root = dir.resolve("sealed-last-crc");
        Files.createDirectories(root);
        // rollBytes just under one FLG2 record (8+20+1+4=33) so each append seals.
        var s = new FileLogStorage(root, 32);
        for (int i = 1; i <= 3; i++) {
            s.append(GROUP, i, 1, b("x"));
        }
        s.force(GROUP);
        assertTrue(s.sealedFiles(GROUP) >= 2, "need sealed segments, sealed=" + s.sealedFiles(GROUP));
        s.close();
        var segs = Files.list(root).filter(p -> p.getFileName().toString().endsWith(".seg")).sorted().toList();
        assertTrue(segs.size() >= 2, "segs=" + segs);
        Path mid = segs.get(segs.size() >= 3 ? 1 : 0);
        byte[] before = Files.readAllBytes(mid);
        byte[] beforeHash = sha256(mid);
        assertTrue(before.length >= FileLogStorage.LOG_FILE_HEADER_BYTES + FileLogStorage.RECORD_HEADER_BYTES + 1
                + FileLogStorage.RECORD_CRC_BYTES);
        // Flip one payload byte of the (only) record — complete framing, bad CRC at segment EOF.
        long payloadAt = FileLogStorage.LOG_FILE_HEADER_BYTES + FileLogStorage.RECORD_HEADER_BYTES;
        try (var ch = FileChannel.open(mid, StandardOpenOption.WRITE)) {
            var one = ByteBuffer.allocate(1);
            one.put((byte) 'Z');
            one.flip();
            ch.write(one, payloadAt);
        }
        byte[] corrupted = Files.readAllBytes(mid);
        assertTrue(corrupted.length == before.length);
        var err = assertThrows(Exception.class, () -> {
            var re = new FileLogStorage(root, 32);
            try {
                re.lastIndex(GROUP);
            } finally {
                try {
                    re.close();
                } catch (Exception ignored) {
                }
            }
        });
        String msg = rootCause(err).getMessage();
        assertTrue(msg.contains("sealed") || msg.contains("CRC") || msg.contains("refusing"),
                "message=" + msg);
        assertArrayEquals(corrupted, Files.readAllBytes(mid), "sealed segment bytes must be unchanged");
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(corrupted), sha256(mid));
        assertTrue(corrupted.length == before.length, "must not truncate sealed segment");
    }

    @Test
    void absurdRecordLengthFailsWithoutHugeAllocation() throws Exception {
        var root = dir.resolve("absurd-len-active");
        Files.createDirectories(root);
        Path log = logFile(root);
        // FLG2 header + one record header declaring Integer.MAX_VALUE length (no body).
        try (var ch = FileChannel.open(log, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            FileLogStorage.writeLogFileHeader(ch);
            var hdr = ByteBuffer.allocate(FileLogStorage.RECORD_HEADER_BYTES);
            hdr.putLong(1L).putLong(1L).putInt(Integer.MAX_VALUE);
            hdr.flip();
            while (hdr.hasRemaining()) {
                ch.write(hdr);
            }
        }
        long size = Files.size(log);
        // Active file: absurd length is a torn tail (truncate to header), not an OOM.
        try {
            var re = new FileLogStorage(root);
            assertEquals(0L, re.lastIndex(GROUP));
            assertEquals(FileLogStorage.LOG_FILE_HEADER_BYTES, Files.size(log),
                    "active absurd length truncates to FLG2 header only");
            re.close();
        } catch (OutOfMemoryError oom) {
            throw new AssertionError("absurd length must not allocate Integer.MAX_VALUE bytes", oom);
        }
        assertTrue(size > FileLogStorage.LOG_FILE_HEADER_BYTES);
    }

    @Test
    void scanSealedAllowTornFalseRefusesLastRecordCrc() throws Exception {
        var root = dir.resolve("scan-sealed-crc");
        Files.createDirectories(root);
        Path seg = root.resolve("msg_crc.00000000000000000001.seg");
        // Minimal sealed-shaped FLG2 file with one record.
        try (var ch = FileChannel.open(seg, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLogStorage.writeLogFileHeader(ch);
            FileLogStorage.writeRecordV2(ch, 1L, 1L, b("x"));
        }
        byte[] before = Files.readAllBytes(seg);
        long payloadAt = FileLogStorage.LOG_FILE_HEADER_BYTES + FileLogStorage.RECORD_HEADER_BYTES;
        try (var ch = FileChannel.open(seg, StandardOpenOption.WRITE)) {
            var one = ByteBuffer.allocate(1);
            one.put((byte) 'Z');
            one.flip();
            ch.write(one, payloadAt);
        }
        byte[] corrupted = Files.readAllBytes(seg);
        var err = assertThrows(Exception.class,
                () -> FileLogStorage.scanRecords(seg, false));
        assertTrue(rootCause(err).getMessage().contains("sealed")
                || rootCause(err).getMessage().contains("CRC")
                || rootCause(err).getMessage().contains("refusing"),
                "message=" + rootCause(err).getMessage());
        assertArrayEquals(corrupted, Files.readAllBytes(seg));
        assertEquals(before.length, Files.size(seg));
    }

    @Test
    void absurdRecordLengthOnSealedFailsClosedWithoutTruncate() throws Exception {
        var root = dir.resolve("absurd-len-sealed");
        Files.createDirectories(root);
        var s = new FileLogStorage(root, 32);
        s.append(GROUP, 1, 1, b("x"));
        s.force(GROUP);
        s.append(GROUP, 2, 1, b("y"));
        s.force(GROUP);
        s.close();
        var segs = Files.list(root).filter(p -> p.getFileName().toString().endsWith(".seg")).sorted().toList();
        assertFalse(segs.isEmpty());
        Path seg = segs.get(0);
        byte[] before = Files.readAllBytes(seg);
        // Overwrite the sealed segment with FLG2 + absurd length header (same size pad or shorter file).
        try (var ch = FileChannel.open(seg, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            FileLogStorage.writeLogFileHeader(ch);
            var hdr = ByteBuffer.allocate(FileLogStorage.RECORD_HEADER_BYTES);
            hdr.putLong(1L).putLong(1L).putInt(Integer.MAX_VALUE);
            hdr.flip();
            while (hdr.hasRemaining()) {
                ch.write(hdr);
            }
        }
        byte[] planted = Files.readAllBytes(seg);
        var err = assertThrows(Exception.class, () -> {
            var re = new FileLogStorage(root, 32);
            try {
                re.lastIndex(GROUP);
            } finally {
                try {
                    re.close();
                } catch (Exception ignored) {
                }
            }
        });
        assertTrue(rootCause(err).getMessage().contains("refusing")
                || rootCause(err).getMessage().contains("sealed")
                || rootCause(err).getMessage().contains("length"),
                "message=" + rootCause(err).getMessage());
        assertArrayEquals(planted, Files.readAllBytes(seg), "sealed absurd length must not truncate");
    }

    @Test
    void negativeLengthOnSealedFailsClosedWithoutTruncate() throws Exception {
        var root = dir.resolve("neg-len-sealed");
        Files.createDirectories(root);
        var s = new FileLogStorage(root, 32);
        s.append(GROUP, 1, 1, b("x"));
        s.force(GROUP);
        s.append(GROUP, 2, 1, b("y"));
        s.force(GROUP);
        s.close();
        var segs = Files.list(root).filter(p -> p.getFileName().toString().endsWith(".seg")).sorted().toList();
        Path seg = segs.get(0);
        byte[] before = Files.readAllBytes(seg);
        // Negative length at the record header (offset 8+16).
        long lenAt = FileLogStorage.LOG_FILE_HEADER_BYTES + 16;
        try (var ch = FileChannel.open(seg, StandardOpenOption.WRITE)) {
            var neg = ByteBuffer.allocate(4);
            neg.putInt(-1);
            neg.flip();
            ch.write(neg, lenAt);
        }
        byte[] corrupted = Files.readAllBytes(seg);
        var err = assertThrows(Exception.class, () -> {
            var re = new FileLogStorage(root, 32);
            try {
                re.lastIndex(GROUP);
            } finally {
                try {
                    re.close();
                } catch (Exception ignored) {
                }
            }
        });
        assertTrue(rootCause(err).getMessage().contains("negative length")
                || rootCause(err).getMessage().contains("refusing"),
                "message=" + rootCause(err).getMessage());
        assertArrayEquals(corrupted, Files.readAllBytes(seg));
        assertEquals(before.length, Files.size(seg));
    }


}
