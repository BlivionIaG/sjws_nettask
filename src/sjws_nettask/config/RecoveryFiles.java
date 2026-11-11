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
package sjws_nettask.config;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Reads the files that missing-config recovery uses: the state-dir last-good, the legacy file
 * beside the config, and on the pinned path the trust, floor and pin files. Startup only; the
 * request path never calls this.
 *
 * <p>On a POSIX filesystem every file goes through {@link SecureKeyFile#readPublic}: the leaf is
 * opened with {@link LinkOption#NOFOLLOW_LINKS}, it must be a regular file owned by root or this
 * process and not group- or world-writable, and every parent directory must pass
 * {@link SecureKeyFile#requireSecureParents}. Root is accepted as owner for the same reason as the
 * trust files: root already controls the process.
 *
 * <p>A filesystem without POSIX attributes cannot carry a bootstrap pin (the bootstrap file needs
 * POSIX modes), so only the unpinned legacy path reaches the fallback here. It still refuses a
 * symlinked leaf and anything that is not a regular file, but cannot check owner or mode.
 */
final class RecoveryFiles {

    /** Upper bound for one recovery file. A signed envelope carries the canonical YAML (at most 1MB). */
    static final int MAX_BYTES = 8_000_000;

    private RecoveryFiles() {
    }

    /**
     * File text, or null when nothing is at {@code path} (a dangling symlink counts as present).
     *
     * @throws ConfigException when the file is present but fails a check; the message names
     *         {@code path} and the failed check, never the file content
     */
    static String read(Path path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        String name = "recovery file " + path.toAbsolutePath();
        if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            return new String(SecureKeyFile.readPublic(path, name, MAX_BYTES), StandardCharsets.UTF_8);
        }
        return readNoFollow(path, name);
    }

    private static String readNoFollow(Path path, String name) {
        try {
            var attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attrs.isSymbolicLink()) {
                throw new ConfigException(name + " is a symlink");
            }
            if (!attrs.isRegularFile()) {
                throw new ConfigException(name + " is not a file");
            }
            try (var channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                if (channel.size() > MAX_BYTES) {
                    throw new ConfigException(name + " is too large");
                }
                var buf = ByteBuffer.allocate((int) channel.size());
                while (buf.hasRemaining() && channel.read(buf) >= 0) {
                    // read to the end or the size taken at open
                }
                return new String(buf.array(), 0, buf.position(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new ConfigException("Cannot read " + name);
        }
    }
}
