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
package sjws_nettask.cp;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * Anti-rollback floor {@code (maxBundleSerial, maxVersionFloor, bundleSha256)} persisted as
 * {@code trust.state} (section 2.2). Written temp, fsync, rename, fsync dir; mode 0600.
 */
record TrustState(long maxBundleSerial, long maxVersionFloor, String bundleSha256) {

    static final String FILE = "trust.state";
    private static final String MAGIC = "sjws-cp-trust-state 1";
    private static final int MAX_BYTES = 1024;

    /** Null when absent; throws when present but unreadable, insecure or malformed. */
    static TrustState read(Path dir, boolean insecure) throws CpKeyException {
        var file = dir.resolve(FILE);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        byte[] bytes = KeyRingLoader.readFile(file, FILE, MAX_BYTES, true, insecure);
        var lines = new String(bytes, StandardCharsets.US_ASCII).split("\n", -1);
        try {
            if (lines.length != 5 || !MAGIC.equals(lines[0]) || !lines[4].isEmpty()
                    || !lines[1].startsWith("serial=") || !lines[2].startsWith("floor=")
                    || !lines[3].startsWith("sha256=")) {
                throw new NumberFormatException();
            }
            long serial = Long.parseLong(lines[1].substring(7));
            long floor = Long.parseLong(lines[2].substring(6));
            var sha = lines[3].substring(7);
            if (serial < 0 || floor < 0 || !sha.matches("[0-9a-f]{64}|")) {
                throw new NumberFormatException();
            }
            return new TrustState(serial, floor, sha);
        } catch (NumberFormatException e) {
            throw new CpKeyException(file + " is malformed");
        }
    }

    void write(Path dir) throws CpKeyException {
        var text = MAGIC + "\nserial=" + maxBundleSerial + "\nfloor=" + maxVersionFloor + "\nsha256=" + bundleSha256
                + "\n";
        var tmp = dir.resolve(FILE + ".tmp");
        try {
            Files.deleteIfExists(tmp);
            if (dir.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            } else {
                Files.createFile(tmp);
            }
            try (var ch = FileChannel.open(tmp, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                ch.write(ByteBuffer.wrap(text.getBytes(StandardCharsets.US_ASCII)));
                ch.force(true);
            }
            Files.move(tmp, dir.resolve(FILE), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            try (var d = FileChannel.open(dir, StandardOpenOption.READ)) {
                d.force(true);
            }
        } catch (IOException e) {
            throw new CpKeyException("cannot persist " + dir.resolve(FILE) + ": " + e.getMessage(), e);
        }
    }
}
