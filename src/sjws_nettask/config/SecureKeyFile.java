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
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.function.Consumer;

/**
 * Reads a secret file without following the leaf. Control-plane key files should call this
 * instead of opening the path themselves; HA calls it only when HA is enabled and a key file
 * is set.
 *
 * <p>The parent directory is resolved with {@link Path#toRealPath()} (directory symlinks are
 * followed once, here). The leaf name under that directory is opened with
 * {@link LinkOption#NOFOLLOW_LINKS} and is never resolved, so a symlink swapped in for the
 * file is refused. Owner and mode come from that opened file: a regular file, mode 0600 or
 * 0400, owned by this process. On Linux those attributes are {@code fstat} of the descriptor
 * ({@code /proc/self/fd}).
 *
 * <p>Every directory from that resolved parent up to the root is refused when it is group- or
 * world-writable, unless it is sticky and owned by root or this process (the {@code /tmp}
 * case). A directory another user owns is allowed when it is not group- or world-writable.
 * The file bytes are not part of the error text. Java keeps the returned copy; it is not wiped.
 */
public final class SecureKeyFile {

    /** {@code S_IWGRP | S_IWOTH}. {@link PosixFilePermission} cannot name the sticky bit. */
    private static final int GROUP_OR_OTHER_WRITE = 022;
    /** {@code S_ISVTX}. */
    private static final int STICKY = 01000;

    /**
     * Runs after the parent is resolved and immediately before the leaf is opened. Tests in
     * this package swap the leaf there. Production leaves this null.
     */
    static volatile Consumer<Path> beforeOpen;

    private SecureKeyFile() {
    }

    /**
     * File bytes, at most {@code maxBytes}. {@code name} is the config key used in errors
     * (for example {@code ha.cluster_key_file}); it is not a path and must not be the secret.
     */
    public static byte[] read(Path path, String name, int maxBytes) {
        if (!path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            throw new ConfigException(name + " cannot be checked (filesystem has no POSIX permissions)");
        }
        Path absolute = path.toAbsolutePath();
        Path parent = absolute.getParent();
        Path fileName = absolute.getFileName();
        if (parent == null || fileName == null) {
            throw new ConfigException("Cannot read " + name);
        }
        final Path realParent;
        try {
            realParent = parent.toRealPath();
        } catch (IOException e) {
            throw new ConfigException("Cannot read " + name);
        }
        requireSecureParents(realParent, name);
        Path opened = realParent.resolve(fileName);
        var hook = beforeOpen;
        if (hook != null) {
            hook.accept(opened);
        }
        try (var channel = FileChannel.open(opened, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            checkedKey(channel, opened, name);
            if (channel.size() > maxBytes) {
                throw new ConfigException(name + " is too large");
            }
            var buf = ByteBuffer.allocate((int) channel.size());
            while (buf.hasRemaining()) {
                if (channel.read(buf) < 0) {
                    break;
                }
            }
            buf.flip();
            var bytes = new byte[buf.remaining()];
            buf.get(bytes);
            return bytes;
        } catch (UnsupportedOperationException e) {
            throw new ConfigException(name + " cannot be checked (filesystem has no POSIX permissions)");
        } catch (IOException e) {
            if (Files.isSymbolicLink(opened)) {
                throw new ConfigException(name + " is a symlink");
            }
            throw new ConfigException("Cannot read " + name);
        }
    }

    /**
     * Refuses when any directory from {@code resolvedParent} up to the root is group- or
     * world-writable, unless that directory is sticky and owned by root or this process.
     * {@code resolvedParent} must already be absolute and free of symlinks ({@link Path#toRealPath()}).
     */
    public static void requireSecureParents(Path resolvedParent, String name) {
        int self = selfUid();
        for (Path dir = resolvedParent; dir != null; dir = dir.getParent()) {
            int mode;
            int uid;
            try {
                mode = ((Integer) Files.getAttribute(dir, "unix:mode", LinkOption.NOFOLLOW_LINKS)).intValue();
                uid = ((Integer) Files.getAttribute(dir, "unix:uid", LinkOption.NOFOLLOW_LINKS)).intValue();
            } catch (UnsupportedOperationException e) {
                throw new ConfigException(name + " cannot be checked (filesystem has no POSIX permissions)");
            } catch (IOException e) {
                throw new ConfigException("Cannot read " + name);
            }
            if ((mode & GROUP_OR_OTHER_WRITE) == 0) {
                continue;
            }
            if ((mode & STICKY) != 0 && (uid == 0 || uid == self)) {
                continue;
            }
            throw new ConfigException(name + " has a group- or world-writable parent directory");
        }
    }

    /** Attributes of the inode {@code channel} already has open. */
    private static void checkedKey(FileChannel channel, Path opened, String name) throws IOException {
        if (!channel.isOpen()) {
            throw new IOException(name);
        }
        PosixFileAttributes attrs;
        int owner;
        var procFd = linuxFd(opened, name);
        if (procFd != null) {
            attrs = Files.readAttributes(procFd, PosixFileAttributes.class);
            owner = ((Integer) Files.getAttribute(procFd, "unix:uid")).intValue();
        } else {
            attrs = Files.readAttributes(opened, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            owner = ((Integer) Files.getAttribute(opened, "unix:uid", LinkOption.NOFOLLOW_LINKS)).intValue();
        }
        if (attrs.isSymbolicLink()) {
            throw new ConfigException(name + " is a symlink");
        }
        if (!attrs.isRegularFile()) {
            throw new ConfigException(name + " is not a file");
        }
        if (!ownedByThisProcess(opened, owner, attrs)) {
            throw new ConfigException(name + " must be owned by this process");
        }
        var perms = attrs.permissions();
        boolean ownerRead = perms.contains(PosixFilePermission.OWNER_READ);
        boolean secret = perms.contains(PosixFilePermission.GROUP_READ)
                || perms.contains(PosixFilePermission.GROUP_WRITE)
                || perms.contains(PosixFilePermission.GROUP_EXECUTE)
                || perms.contains(PosixFilePermission.OTHERS_READ)
                || perms.contains(PosixFilePermission.OTHERS_WRITE)
                || perms.contains(PosixFilePermission.OTHERS_EXECUTE);
        if (!ownerRead || secret || perms.contains(PosixFilePermission.OWNER_EXECUTE)) {
            throw new ConfigException(name + " must be mode 0600 or 0400");
        }
    }

    /** This process's uid when {@code /proc/self} exists; otherwise {@code -1}. */
    private static int selfUid() {
        try {
            var self = Path.of("/proc/self");
            if (Files.exists(self)) {
                return ((Integer) Files.getAttribute(self, "unix:uid")).intValue();
            }
        } catch (IOException ignored) {
            // no numeric uid; only a root-owned sticky directory is exempt
        }
        return -1;
    }

    /** This process's uid when {@code /proc/self} exists; otherwise the same owner name. */
    private static boolean ownedByThisProcess(Path opened, int ownerUid, PosixFileAttributes attrs) throws IOException {
        int uid = selfUid();
        if (uid >= 0) {
            return ownerUid == uid;
        }
        var me = opened.getFileSystem().getUserPrincipalLookupService()
                .lookupPrincipalByName(System.getProperty("user.name"));
        return me.equals(attrs.owner());
    }

    /**
     * {@code /proc/self/fd/N} for the single descriptor whose target is {@code opened}, or null
     * when this system has no proc fd directory. More than one match fails closed.
     * {@code stat} of that path is {@code fstat} of the descriptor.
     */
    private static Path linuxFd(Path opened, String name) throws IOException {
        var dir = Path.of("/proc/self/fd");
        if (!Files.isDirectory(dir)) {
            return null;
        }
        String want = opened.toString();
        int found = -1;
        int matches = 0;
        try (var stream = Files.newDirectoryStream(dir)) {
            for (var entry : stream) {
                Path link;
                try {
                    link = Files.readSymbolicLink(entry);
                } catch (IOException ignored) {
                    continue;
                }
                if (!link.toString().equals(want)) {
                    continue;
                }
                matches++;
                found = Integer.parseInt(entry.getFileName().toString());
            }
        }
        if (matches != 1) {
            throw new ConfigException(name + " cannot be checked");
        }
        return dir.resolve(Integer.toString(found));
    }
}
