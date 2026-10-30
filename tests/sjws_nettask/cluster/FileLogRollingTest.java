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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** #270: {@link FileLogStorage} rolling mode. Prefix truncation unlinks sealed files; reloads keep the live run. */
class FileLogRollingTest {

    static final GroupId G = new GroupId("msg/t");
    @TempDir Path dir;

    static byte[] data(long i) {
        return ("entry-" + i).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }

    FileLogStorage fill(long n) throws Exception {
        var s = new FileLogStorage(dir, 256);
        for (long i = 1; i <= n; i++) s.append(G, i, 1 + i / 50, data(i));
        return s;
    }

    @Test
    void rollsAndPrefixTruncationUnlinksSealedFiles() throws Exception {
        var s = fill(200);
        assertTrue(s.sealedFiles(G) > 5, "rolled into several sealed files");
        long before = s.entryBytes(G);
        // Durable snapshot must cover the truncated prefix so reopen's keep-suffix is bound to snapIndex+1 (#316 B2).
        s.saveSnapshot(G, new LogSnapshot(G, 150, 1 + 150 / 50, new byte[] {1}));
        s.truncatePrefix(G, 150);
        assertTrue(s.entryBytes(G) < before / 2, "sealed files wholly below the cut are gone");
        assertNull(s.read(G, 150));
        assertArrayEquals(data(151), s.read(G, 151));
        assertEquals(200, s.lastIndex(G));
        s.force(G);
        s.close();
        var re = new FileLogStorage(dir, 256);
        assertEquals(200, re.lastIndex(G));
        assertArrayEquals(data(200), re.read(G, 200));
        assertEquals(1 + 170 / 50, re.termAt(G, 170));
        for (long i = 151; i <= 200; i++) assertArrayEquals(data(i), re.read(G, i), "index " + i);
        re.close();
    }

    @Test
    void aResurrectedOlderFileIsIgnoredOnLoad() throws Exception {
        var s = fill(200);
        var first = Files.list(dir).filter(p -> p.toString().endsWith(".seg")).sorted().findFirst().orElseThrow();
        var copy = Files.readAllBytes(first);
        s.saveSnapshot(G, new LogSnapshot(G, 150, 1 + 150 / 50, new byte[] {1}));
        s.truncatePrefix(G, 150);
        s.close();
        Files.write(first, copy); // the unlink did not survive a crash
        var re = new FileLogStorage(dir, 256);
        assertEquals(200, re.lastIndex(G));
        assertNull(re.read(G, 1), "not contiguous with the live run: dropped");
        assertArrayEquals(data(151), re.read(G, 151));
        assertTrue(Files.notExists(first), "the stale file is deleted on load");
        re.close();
    }

    @Test
    void suffixTruncationAcrossSealedFilesSurvivesACrashBeforeTheDeletes() throws Exception {
        var s = fill(200);
        var sealedBefore = Files.list(dir).filter(p -> p.toString().endsWith(".seg")).sorted().toList();
        var copies = new java.util.ArrayList<byte[]>();
        for (var p : sealedBefore) copies.add(Files.readAllBytes(p));
        s.truncateSuffix(G, 120);
        assertEquals(119, s.lastIndex(G));
        s.close();
        // Crash after the active rewrite, before the sealed files were deleted: they come back with stale entries.
        for (int i = 0; i < sealedBefore.size(); i++) Files.write(sealedBefore.get(i), copies.get(i));
        var re = new FileLogStorage(dir, 256);
        assertEquals(119, re.lastIndex(G), "the active file is authoritative from its first index on");
        re.append(G, 120, 9, data(9120));
        re.force(G);
        re.close();
        re = new FileLogStorage(dir, 256);
        assertEquals(120, re.lastIndex(G));
        assertEquals(9, re.termAt(G, 120));
        assertArrayEquals(data(9120), re.read(G, 120));
        assertNull(re.read(G, 121), "the stale suffix stays truncated");
        assertArrayEquals(data(1), re.read(G, 1));
        re.close();
    }

    @Test
    void oneFileModeIsUnchangedAndRollBytesIsChecked() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new FileLogStorage(dir, 0));
        var s = new FileLogStorage(dir);
        for (long i = 1; i <= 100; i++) s.append(G, i, 1, data(i));
        assertEquals(0, s.sealedFiles(G));
        s.truncatePrefix(G, 50);
        assertNull(s.read(G, 50));
        assertTrue(s.entryBytes(G) > 0);
        s.close();
    }
}
