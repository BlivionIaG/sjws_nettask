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

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.NoSuchFileException;

/**
 * Descriptor-relative open for platforms without {@link java.nio.file.SecureDirectoryStream}.
 * Loaded only when {@link SafeOpen} takes this path (macOS); Linux keeps the SDS path and must not
 * load this class. Flag values are the host libc's: Darwin and Linux differ.
 */
final class SafeOpenAt {

    private static final boolean DARWIN = System.getProperty("os.name", "").toLowerCase().contains("mac");

    /** Package-private test hook: run between components (and before the leaf). Null in production. */
    static volatile Runnable betweenComponents;

    private static final int O_RDONLY = 0;
    private static final int O_CLOEXEC;
    private static final int O_DIRECTORY;
    private static final int O_NOFOLLOW;
    private static final int AT_FDCWD;
    private static final int AT_SYMLINK_NOFOLLOW;
    private static final int ELOOP;
    private static final int EACCES = 13;
    private static final int ENOENT = 2;
    private static final int ENOTDIR;
    private static final int S_IFMT = 0170000;
    private static final int S_IFREG = 0100000;

    /** {@code struct stat} field offsets for the host ABI. */
    private static final long STAT_DEV;
    private static final long STAT_INO;
    private static final long STAT_MODE;
    private static final int STAT_BUF = 256;

    private static final MethodHandle OPENAT;
    private static final MethodHandle FSTATAT;
    private static final MethodHandle CLOSE;
    private static final VarHandle ERRNO;
    private static final MemoryLayout CAPTURE = Linker.Option.captureStateLayout();
    private static final Field FD_FIELD;

    static {
        if (DARWIN) {
            // sys/fcntl.h / sys/stat.h on Darwin
            O_NOFOLLOW = 0x0100;
            O_DIRECTORY = 0x100000;
            O_CLOEXEC = 0x01000000;
            AT_FDCWD = -2;
            AT_SYMLINK_NOFOLLOW = 0x0020;
            ELOOP = 62;
            ENOTDIR = 20;
            STAT_DEV = 0;
            STAT_MODE = 4;
            STAT_INO = 8;
        } else {
            // Linux glibc
            O_NOFOLLOW = 0400000;
            O_DIRECTORY = 0200000;
            O_CLOEXEC = 02000000;
            AT_FDCWD = -100;
            AT_SYMLINK_NOFOLLOW = 0x100;
            ELOOP = 40;
            ENOTDIR = 20;
            STAT_DEV = 0;
            STAT_INO = 8;
            STAT_MODE = 24;
        }
        try {
            var linker = Linker.nativeLinker();
            var lookup = linker.defaultLookup();
            var capture = Linker.Option.captureCallState("errno");
            OPENAT = linker.downcallHandle(lookup.findOrThrow("openat"), FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT), capture);
            FSTATAT = linker.downcallHandle(lookup.findOrThrow("fstatat"), FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT), capture);
            CLOSE = linker.downcallHandle(lookup.findOrThrow("close"),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
            ERRNO = CAPTURE.varHandle(MemoryLayout.PathElement.groupElement("errno"));
            FD_FIELD = FileDescriptor.class.getDeclaredField("fd");
            FD_FIELD.setAccessible(true);
        } catch (Throwable t) {
            throw new ExceptionInInitializerError(t);
        }
    }

    private SafeOpenAt() {
    }

    static FileChannel open(Body.FileBody file) throws IOException {
        var root = file.root();
        var relative = root.relativize(file.realPath());
        if (relative.getNameCount() == 0 || relative.toString().isEmpty()) {
            throw new SafeOpen.FileChangedException("not a file inside the root: " + file.realPath());
        }
        try (var arena = Arena.ofConfined()) {
            var errno = arena.allocate(CAPTURE);
            int dir = openat(errno, AT_FDCWD, root.toString(), O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
            try {
                for (int i = 0; i < relative.getNameCount() - 1; i++) {
                    runHook();
                    int next = openat(errno, dir, relative.getName(i).toString(),
                            O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
                    closeQuietly(dir);
                    dir = next;
                }
                runHook();
                var leaf = relative.getFileName().toString();
                var st = arena.allocate(STAT_BUF);
                fstatat(errno, dir, leaf, st, AT_SYMLINK_NOFOLLOW);
                checkStat(st, file);
                int fd = openat(errno, dir, leaf, O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
                closeQuietly(dir);
                dir = -1;
                try {
                    return channel(fd);
                } catch (IOException | RuntimeException e) {
                    closeQuietly(fd);
                    throw e;
                }
            } finally {
                if (dir >= 0) {
                    closeQuietly(dir);
                }
            }
        } catch (SafeOpen.FileChangedException | AccessDeniedException | NoSuchFileException e) {
            throw e;
        } catch (IOException e) {
            throw e;
        }
    }

    private static void runHook() {
        var hook = betweenComponents;
        if (hook != null) {
            hook.run();
        }
    }

    private static void checkStat(MemorySegment st, Body.FileBody file) throws SafeOpen.FileChangedException {
        int mode = DARWIN ? Short.toUnsignedInt(st.get(ValueLayout.JAVA_SHORT_UNALIGNED, STAT_MODE))
                : st.get(ValueLayout.JAVA_INT_UNALIGNED, STAT_MODE);
        if ((mode & S_IFMT) != S_IFREG) {
            throw new SafeOpen.FileChangedException("not a regular file (anymore): " + file.realPath());
        }
        long dev = DARWIN ? Integer.toUnsignedLong(st.get(ValueLayout.JAVA_INT_UNALIGNED, STAT_DEV))
                : st.get(ValueLayout.JAVA_LONG_UNALIGNED, STAT_DEV);
        long ino = st.get(ValueLayout.JAVA_LONG_UNALIGNED, STAT_INO);
        if (!sameKey(file.expected().fileKey(), dev, ino)) {
            throw new SafeOpen.FileChangedException("file replaced since it was resolved: " + file.realPath());
        }
    }

    /** Matches OpenJDK {@code UnixFileKey#toString}: {@code (dev=<hex>,ino=<dec>)}. */
    static boolean sameKey(Object fileKey, long dev, long ino) {
        if (fileKey == null) {
            return true;
        }
        var s = fileKey.toString();
        var left = s.indexOf("dev=");
        var mid = s.indexOf(",ino=");
        var right = s.indexOf(')', mid);
        if (left < 0 || mid < 0 || right < 0) {
            return false;
        }
        try {
            long keyDev = Long.parseLong(s.substring(left + 4, mid), 16);
            long keyIno = Long.parseLong(s.substring(mid + 5, right));
            return keyDev == dev && keyIno == ino;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static int openat(MemorySegment errno, int dirfd, String name, int flags) throws IOException {
        try (var arena = Arena.ofConfined()) {
            int fd = (int) OPENAT.invoke(errno, dirfd, arena.allocateFrom(name), flags);
            if (fd >= 0) {
                return fd;
            }
            throw forErrno((int) ERRNO.get(errno, 0L), name);
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException("openat failed: " + name, t);
        }
    }

    private static void fstatat(MemorySegment errno, int dirfd, String name, MemorySegment st, int flag)
            throws IOException {
        try (var arena = Arena.ofConfined()) {
            int rc = (int) FSTATAT.invoke(errno, dirfd, arena.allocateFrom(name), st, flag);
            if (rc == 0) {
                return;
            }
            throw forErrno((int) ERRNO.get(errno, 0L), name);
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException("fstatat failed: " + name, t);
        }
    }

    private static IOException forErrno(int err, String name) {
        if (err == EACCES) {
            return new AccessDeniedException(name, null, "directory not readable (search-only)");
        }
        if (err == ENOENT) {
            return new NoSuchFileException(name);
        }
        if (err == ELOOP || err == ENOTDIR) {
            var e = new SafeOpen.FileChangedException("path changed since it was resolved: " + name);
            e.initCause(new IOException("errno=" + err));
            return e;
        }
        return new IOException("errno=" + err + " for " + name);
    }

    private static void closeQuietly(int fd) {
        if (fd < 0) {
            return;
        }
        try {
            CLOSE.invoke(fd);
        } catch (Throwable ignored) {
            // best-effort
        }
    }

    private static FileChannel channel(int fd) throws IOException {
        try {
            var desc = new FileDescriptor();
            FD_FIELD.setInt(desc, fd);
            // FileInputStream takes ownership of the descriptor; closing the channel closes the fd.
            return new FileInputStream(desc).getChannel();
        } catch (IllegalAccessException e) {
            closeQuietly(fd);
            throw new IOException("cannot wrap file descriptor", e);
        }
    }
}
