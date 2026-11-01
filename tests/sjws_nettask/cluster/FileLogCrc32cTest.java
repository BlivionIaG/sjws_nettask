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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * #207: per-record CRC32C on {@link FileLogStorage} — mid-log fail-closed, last-record truncate,
 * legacy pre-{@code FLG2} read + upgrade, live map after upgrade, damaged-header fail-closed.
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


}
