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
package sjws_nettask.core;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;

/**
 * Opens the file of a {@link Body.FileBody} for sending, so that what is sent is the file the
 * handler resolved, inside the document root. Shared by every transport writer (HTTP/1.1 and
 * HTTP/3), so their guarantees cannot drift apart.
 * <p>
 * <b>Secure mode</b> (Linux and other platforms where {@link Files#newDirectoryStream} returns a
 * {@link SecureDirectoryStream}, i.e. {@code openat}): starting from a handle on the resolved root,
 * every directory component and the file itself are opened relative to the previous handle with
 * {@code NOFOLLOW_LINKS}. A component that was replaced by a symlink in the meantime fails
 * ({@code ELOOP}/{@code ENOTDIR}), so the opened file is always inside the root, whatever is
 * renamed concurrently. The identity ({@link Validator#fileKey()}) is then read through the same
 * pinned parent directory handle. The JDK offers no {@code fstat} of the opened channel, so a file
 * renamed over the name between the open and that check could still be sent; it is necessarily a
 * regular file in the same (pinned, in-root) directory, and its length is enforced by the writers.
 * <p>
 * <b>Fallback</b> (Windows, macOS: no {@code SecureDirectoryStream}): the path is opened with
 * {@code NOFOLLOW_LINKS} (last component only), then immediately re-resolved with
 * {@code toRealPath()}: it must still be the same real path inside the root, and the file key (where
 * the platform has one) must match. This narrows the race window to the time between the open and
 * the re-check but cannot close it; serve untrusted-writable roots from a platform with secure mode.
 */
public final class SafeOpen {

    /** True where secure mode is available on the default file system. */
    public static final boolean SECURE = probe();

    /** The file is not (or no longer) the one that was resolved: nothing may be sent. */
    public static final class FileChangedException extends IOException {

        private static final long serialVersionUID = 1L;

        FileChangedException(String message) {
            super(message);
        }
    }

    private SafeOpen() {
    }

    private static boolean probe() {
        try (var ds = Files.newDirectoryStream(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath())) {
            return ds instanceof SecureDirectoryStream;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * @return a read channel on the file; the caller closes it
     * @throws FileChangedException if a component was swapped (symlink, other inode) since resolution
     * @throws IOException if the file can't be opened (deleted, unreadable)
     */
    public static FileChannel open(Body.FileBody file) throws IOException {
        return open(file, SECURE);
    }

    /** @param secure false forces the fallback (tests; platforms without secure mode) */
    static FileChannel open(Body.FileBody file, boolean secure) throws IOException {
        var root = file.root();
        var path = file.realPath();
        var relative = root.relativize(path);
        if (relative.getNameCount() == 0 || relative.toString().isEmpty()) {
            throw new FileChangedException("not a file inside the root: " + path);
        }
        if (secure) {
            var top = Files.newDirectoryStream(root);
            if (top instanceof SecureDirectoryStream<Path> sds) {
                return openSecure(sds, relative, file);
            }
            top.close();
        }
        return openFallback(file);
    }

    private static FileChannel openSecure(SecureDirectoryStream<Path> top, Path relative, Body.FileBody file)
            throws IOException {
        SecureDirectoryStream<Path> dir = top;
        try {
            for (int i = 0; i < relative.getNameCount() - 1; i++) {
                DirectoryStream<Path> next;
                try {
                    next = dir.newDirectoryStream(relative.getName(i), LinkOption.NOFOLLOW_LINKS);
                } catch (IOException e) { // ELOOP / ENOTDIR: a component is now a symlink or a file
                    throw changed(file, e);
                }
                dir.close();
                dir = (SecureDirectoryStream<Path>) next;
            }
            var leaf = relative.getFileName();
            SeekableByteChannel channel;
            try {
                channel = dir.newByteChannel(leaf, Set.<OpenOption>of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
            } catch (IOException e) {
                if (Files.isSymbolicLink(file.realPath())) {
                    throw changed(file, e);
                }
                throw e;
            }
            try {
                if (!(channel instanceof FileChannel fc)) {
                    throw new IOException("not a file channel: " + channel.getClass().getName());
                }
                var attributes = dir.getFileAttributeView(leaf, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
                        .readAttributes();
                check(attributes, file);
                return fc;
            } catch (IOException | RuntimeException e) {
                channel.close();
                throw e;
            }
        } finally {
            dir.close();
        }
    }

    private static FileChannel openFallback(Body.FileBody file) throws IOException {
        var path = file.realPath();
        FileChannel channel;
        try {
            channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            if (Files.isSymbolicLink(path)) {
                throw changed(file, e);
            }
            throw e;
        }
        try {
            var real = path.toRealPath();
            if (!real.equals(path) || !real.startsWith(file.root())) {
                throw new FileChangedException("path changed since it was resolved: " + path);
            }
            check(Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS), file);
            return channel;
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
    }

    private static void check(BasicFileAttributes attributes, Body.FileBody file) throws FileChangedException {
        if (!attributes.isRegularFile() || !file.expected().sameFile(attributes)) {
            throw new FileChangedException("file replaced since it was resolved: " + file.realPath());
        }
    }

    private static FileChangedException changed(Body.FileBody file, IOException cause) {
        var e = new FileChangedException("path changed since it was resolved: " + file.realPath());
        e.initCause(cause);
        return e;
    }
}
