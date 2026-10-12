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
package sjws_nettask.service;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

import static java.nio.file.StandardOpenOption.WRITE;

/** Temp file, fsync, then replace. Readers see the previous file or the new one. */
final class AtomicFiles {

    private AtomicFiles() {
    }

    static void write(Path path, byte[] body, Set<PosixFilePermission> mode) throws IOException {
        var parent = path.toAbsolutePath().getParent();
        if (parent == null) {
            throw new BootstrapException("cannot write " + path);
        }
        Files.createDirectories(parent);
        Path tmp = createTemp(parent, mode);
        try {
            Files.write(tmp, body);
            try (var channel = FileChannel.open(tmp, WRITE)) {
                channel.force(true);
            }
            try {
                Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
            tmp = null;
            if (PosixMode.supported(path)) {
                Files.setPosixFilePermissions(path, mode);
            }
        } finally {
            if (tmp != null) {
                Files.deleteIfExists(tmp);
            }
        }
    }

    private static Path createTemp(Path directory, Set<PosixFilePermission> mode) throws IOException {
        if (PosixMode.supported(directory)) {
            try {
                return Files.createTempFile(directory, ".sjws-", ".tmp",
                        PosixFilePermissions.asFileAttribute(mode));
            } catch (UnsupportedOperationException e) {
                // advertised posix, but this store cannot set a mode on create
            }
        }
        return Files.createTempFile(directory, ".sjws-", ".tmp");
    }
}
