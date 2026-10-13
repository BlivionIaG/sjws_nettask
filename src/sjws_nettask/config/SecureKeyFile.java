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
import java.nio.file.attribute.BasicFileAttributes;
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
 * file is refused. The file must be a regular file, mode 0600 or 0400, owned by this process.
 *
 * <p>On Linux, owner and mode are {@code fstat} of {@code /proc/self/fd/N}. That descriptor is
 * trusted only when its {@link BasicFileAttributes#fileKey() fileKey} (device and inode) equals
 * the opened file. Another descriptor of the same inode is the same file. A descriptor whose
 * fileKey differs is ignored.
 *
 * <p>With no {@code /proc} (non-Linux), there is no descriptor to identify. Owner and mode are
 * a {@code NOFOLLOW} {@code lstat} of the path while the channel is still open, so a replace
 * between that check and the read can still change which inode was accepted. The numeric uid
 * is unknown. A parent is accepted, and a sticky directory is exempt, when its owner name is
 * {@code root} or {@code user.name}. The file owner is compared to {@code user.name} the same
 * way. Whoever starts the JVM can set {@code user.name}; they already control the process.
 *
 * <p>Every directory from that resolved parent up to the root must be owned by root or this
 * process. A group- or world-writable directory is refused unless it is sticky and owned by
 * root or this process (the {@code /tmp} case). The file bytes are not part of the error text.
 * Java keeps the returned copy; it is not wiped.
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
     * Refuses when any directory from {@code resolvedParent} up to the root is not owned by
     * root or this process, or is group- or world-writable without the sticky bit. A sticky
     * directory owned by root or this process may be group- or world-writable.
     * {@code resolvedParent} must already be absolute and free of symlinks ({@link Path#toRealPath()}).
     * Without {@code /proc}, "this process" is the owner name {@code user.name} and root is the
     * owner name {@code root}.
     */
    public static void requireSecureParents(Path resolvedParent, String name) {
        int self = selfUid();
        for (Path dir = resolvedParent; dir != null; dir = dir.getParent()) {
            int mode;
            int uid;
            try {
                mode = ((Integer) Files.getAttribute(dir, "unix:mode", LinkOption.NOFOLLOW_LINKS)).intValue();
                uid = ((Integer) Files.getAttribute(dir, "unix:uid", LinkOption.NOFOLLOW_LINKS)).intValue();
                if (!ownedByRootOrUs(dir, uid, self)) {
                    throw new ConfigException(name + " has a parent directory not owned by root or this process");
                }
            } catch (UnsupportedOperationException e) {
                throw new ConfigException(name + " cannot be checked (filesystem has no POSIX permissions)");
            } catch (IOException e) {
                throw new ConfigException("Cannot read " + name);
            }
            if ((mode & GROUP_OR_OTHER_WRITE) != 0 && (mode & STICKY) == 0) {
                throw new ConfigException(name + " has a group- or world-writable parent directory");
            }
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
            if (!sameFile(opened, procFd)) {
                throw new ConfigException(name + " cannot be checked");
            }
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
            // no numeric uid; owner names root and user.name are the fallback
        }
        return -1;
    }

    /**
     * Root, or this process. With a numeric uid that is {@code uid == 0} or {@code uid == self}.
     * Without one, the owner name {@code root} or {@code user.name}.
     */
    private static boolean ownedByRootOrUs(Path dir, int uid, int self) throws IOException {
        if (uid == 0 || (self >= 0 && uid == self)) {
            return true;
        }
        if (self >= 0) {
            return false;
        }
        var owner = Files.readAttributes(dir, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS).owner();
        if ("root".equals(owner.getName())) {
            return true;
        }
        var me = dir.getFileSystem().getUserPrincipalLookupService()
                .lookupPrincipalByName(System.getProperty("user.name"));
        return me.equals(owner);
    }

    /**
     * Numeric uid from {@code /proc/self}, or the owner name {@code user.name} when that file is
     * absent. Whoever starts the JVM can set {@code user.name}; they already control the process.
     */
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
     * A {@code /proc/self/fd/N} whose device and inode are the opened file, or null when this
     * system has no proc fd directory. {@code stat} of that path is {@code fstat} of the
     * descriptor. The descriptor is not trusted until {@link #sameFile} matches.
     */
    private static Path linuxFd(Path opened, String name) throws IOException {
        var dir = Path.of("/proc/self/fd");
        if (!Files.isDirectory(dir)) {
            return null;
        }
        Object want = fileKey(opened, true);
        if (want == null) {
            throw new ConfigException(name + " cannot be checked");
        }
        try (var stream = Files.newDirectoryStream(dir)) {
            for (var entry : stream) {
                Path candidate = dir.resolve(entry.getFileName().toString());
                try {
                    Object key = fileKey(candidate, false);
                    if (want.equals(key) && sameFile(opened, candidate)) {
                        return candidate;
                    }
                } catch (IOException ignored) {
                    continue;
                }
            }
        }
        throw new ConfigException(name + " cannot be checked");
    }

    /** Device and inode of {@code descriptor} are the opened file. Package tests call this. */
    static boolean sameFile(Path opened, Path descriptor) throws IOException {
        Object want = fileKey(opened, true);
        Object got = fileKey(descriptor, false);
        return want != null && want.equals(got);
    }

    /** {@code st_dev} and {@code st_ino}. {@code nofollow} is {@code lstat}; otherwise {@code stat}. */
    private static Object fileKey(Path path, boolean nofollow) throws IOException {
        BasicFileAttributes attrs = nofollow
                ? Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
                : Files.readAttributes(path, BasicFileAttributes.class);
        return attrs.fileKey();
    }
}
