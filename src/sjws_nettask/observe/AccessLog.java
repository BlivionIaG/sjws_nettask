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
package sjws_nettask.observe;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;
import sjws_nettask.ServerConfig.AccessLogFormat;

/**
 * Access log written by one background thread. Request threads format a line and hand the bytes to
 * a lock-free queue; they never write the stream and never wait on it. A full queue or a failed
 * stream drops the line and counts it.
 */
final class AccessLog implements Runnable, AutoCloseable {

    static final int CAPACITY = 8192;
    private static final long FLUSH_NANOS = 20_000_000L;
    private static final int FLUSH_BYTES = 32 * 1024;

    private final LogQueue queue;
    private final Path path; // null when logging to an injected stream / stdout
    private volatile OutputStream out;
    private final boolean closeOut;
    private final AccessLogFormat format;
    private final LongAdder dropped = new LongAdder();
    private final Thread thread;
    private final Clock clock = new Clock();
    private volatile boolean stopped;
    private volatile boolean failed;
    /** File key of the inode currently open; null until first open or when unsupported. */
    private Object openedKey;

    /**
     * Package-private reopen-path test seam for key-race retry coverage. Production leaves
     * {@link OpenKeyRaceProbe#NONE} (always no-op).
     */
    static volatile OpenKeyRaceProbe openKeyRaceProbe = OpenKeyRaceProbe.NONE;
    private long nextReopenCheckNanos;
    /** Test/hook: force one reopen check, ignoring the 1s deadline once (#406). */
    private volatile boolean reopenRequested;
    private static final long REOPEN_CHECK_NANOS = 1_000_000_000L;

    private static final Set<PosixFilePermission> FILE_0640 = EnumSet.of(PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE, PosixFilePermission.GROUP_READ);
    private static final Set<PosixFilePermission> DIR_0750 = EnumSet.of(PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_EXECUTE);

    static AccessLog start(String target, AccessLogFormat format) throws IOException {
        if ("-".equals(target)) {
            return new AccessLog(null, unclosable(System.out), false, format, CAPACITY);
        }
        Path path = Path.of(target).toAbsolutePath();
        Opened opened = openFileWithKey(path);
        return new AccessLog(path, opened.stream, true, format, CAPACITY, opened.key);
    }

    /**
     * Opens {@code path} for append. A newly created file is mode {@code 0640}; each directory this
     * process creates for it is {@code 0750}. An existing file keeps its mode; a world-readable
     * existing file logs a WARN and is still appended. After an external rename/replace, the writer
     * thread reopens the configured path (#406) so new lines hit the new inode (and a newly created
     * file gets {@code 0640} again).
     * <p>
     * Directory and nested symlinks are resolved to a real leaf path once; the leaf is opened with
     * {@link LinkOption#NOFOLLOW_LINKS} so create/append bind to that inode (not a follow-through
     * of the configured link that could race a rotator).
     */
    static OutputStream openFile(Path path) throws IOException {
        return openFileWithKey(path).stream;
    }

    private static final int SYMLINK_HOP_CAP = 40;

    /**
     * Resolves {@code configured} to the real leaf path to create or open. The parent is
     * {@link Path#toRealPath()} (follows directory symlinks). Trailing symlink components are
     * walked with {@link Files#readSymbolicLink}, each relative target resolved against the real
     * parent of that link (parent re-realpathed every hop). No whole-path lexical {@code normalize()}.
     */
    static Path resolveLogLeaf(Path configured) throws IOException {
        Path absolute = configured.toAbsolutePath();
        Path parent = absolute.getParent();
        if (parent == null) {
            throw new IOException("access log path has no parent: " + absolute);
        }
        ensureParent(absolute);
        Path realParent = parent.toRealPath();
        Path leaf = realParent.resolve(absolute.getFileName());
        for (int hop = 0; hop < SYMLINK_HOP_CAP; hop++) {
            if (!Files.isSymbolicLink(leaf)) {
                return leaf;
            }
            Path target = Files.readSymbolicLink(leaf);
            Path linkParent = leaf.toAbsolutePath().getParent();
            if (linkParent == null) {
                throw new IOException("symlink has no parent: " + leaf);
            }
            Path realLinkParent = linkParent.toRealPath();
            Path next;
            if (target.isAbsolute()) {
                Path tParent = target.getParent();
                Path tName = target.getFileName();
                if (tParent == null || tName == null) {
                    throw new IOException("symlink target has no parent: " + target);
                }
                ensureParent(tParent.resolve(tName));
                next = tParent.toRealPath().resolve(tName);
            } else {
                Path resolved = realLinkParent.resolve(target);
                Path nextParent = resolved.getParent();
                Path nextName = resolved.getFileName();
                if (nextParent == null || nextName == null) {
                    throw new IOException("symlink target has no parent: " + target);
                }
                ensureParent(resolved);
                next = nextParent.toRealPath().resolve(nextName);
            }
            leaf = next;
        }
        throw new FileSystemException(absolute.toString(), null, "Too many levels of symbolic links");
    }

    /**
     * Opens {@code path} and records a file key that belongs to the opened stream. Resolves to a
     * real leaf, then opens that leaf with {@link LinkOption#NOFOLLOW_LINKS}. If the leaf key
     * changes across the open, close and retry so {@code openedKey} matches the stream inode.
     */
    static Opened openFileWithKey(Path path) throws IOException {
        IOException lastClose = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            Path leaf = resolveLogLeaf(path);
            Object before = fileKeyNoFollow(leaf);
            OutputStream stream = openLeaf(leaf);
            Object after = fileKeyNoFollow(leaf);
            boolean raced = before != null && after != null && !before.equals(after);
            if (openKeyRaceProbe.consumeForceRace()) {
                raced = true;
            }
            if (raced) {
                try {
                    stream.close();
                } catch (IOException e) {
                    lastClose = e;
                }
                continue;
            }
            return new Opened(stream, after);
        }
        if (lastClose != null) {
            System.err.println("WARN: access log close after raced open: " + lastClose);
        }
        Path leaf = resolveLogLeaf(path);
        OutputStream stream = openLeaf(leaf);
        return new Opened(stream, fileKeyNoFollow(leaf));
    }

    /** CREATE_NEW+0640 or append-open the resolved leaf; never follows the leaf path. */
    static OutputStream openLeaf(Path leaf) throws IOException {
        ensureParent(leaf);
        if (!Files.exists(leaf, LinkOption.NOFOLLOW_LINKS)) {
            try {
                return openChannel(leaf, true);
            } catch (FileAlreadyExistsException e) {
                // Create raced; append only if the winner is a regular file, never a planted link.
                if (!Files.exists(leaf, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(leaf)) {
                    throw e;
                }
            }
        }
        if (Files.isSymbolicLink(leaf)) {
            // Dangling or unexpected link at leaf after resolve — do not follow.
            throw new IOException("access log leaf is still a symbolic link: " + leaf);
        }
        warnIfWorldReadable(leaf);
        return openChannel(leaf, false);
    }

    private static OutputStream openChannel(Path leaf, boolean createNew) throws IOException {
        Set<OpenOption> opts = createNew
                ? Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.APPEND,
                        LinkOption.NOFOLLOW_LINKS)
                : Set.of(StandardOpenOption.WRITE, StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS);
        FileChannel ch;
        if (createNew && posix(leaf)) {
            FileAttribute<?> perms = PosixFilePermissions.asFileAttribute(FILE_0640);
            ch = FileChannel.open(leaf, opts, perms);
        } else {
            ch = FileChannel.open(leaf, opts);
        }
        return Channels.newOutputStream(ch);
    }

    /** Stream plus the path file key observed to be stable across that open. */
    static final class Opened {
        final OutputStream stream;
        final Object key;

        Opened(OutputStream stream, Object key) {
            this.stream = stream;
            this.key = key;
        }
    }

    /** Reopen-path only; {@link #NONE} is a no-op final for production. */
    static class OpenKeyRaceProbe {
        static final OpenKeyRaceProbe NONE = new OpenKeyRaceProbe();

        /** @return true once per forced race the test wants to inject */
        boolean consumeForceRace() {
            return false;
        }
    }

    /**
     * Creates each missing ancestor of {@code file} with mode {@code 0750}. An existing directory
     * symlink is accepted (followed). {@link Files#createDirectories} would apply the attribute
     * only to the last segment.
     */
    private static void ensureParent(Path file) throws IOException {
        Path parent = file.getParent();
        if (parent == null || Files.isDirectory(parent)) {
            return;
        }
        createAncestors(parent.toAbsolutePath());
    }

    private static void createAncestors(Path dir) throws IOException {
        if (Files.isDirectory(dir)) {
            return;
        }
        Path parent = dir.getParent();
        if (parent != null && !Files.isDirectory(parent)) {
            createAncestors(parent);
        }
        try {
            if (posix(dir)) {
                Files.createDirectory(dir, PosixFilePermissions.asFileAttribute(DIR_0750));
            } else {
                Files.createDirectory(dir);
            }
        } catch (FileAlreadyExistsException e) {
            // Symlink-to-directory or raced create: accept a directory after following.
            if (!Files.isDirectory(dir)) {
                throw e;
            }
        } catch (UnsupportedOperationException e) {
            Files.createDirectory(dir);
        }
    }

    private static void warnIfWorldReadable(Path path) {
        try {
            if (!posix(path)) {
                return;
            }
            var perms = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
            if (perms.contains(PosixFilePermission.OTHERS_READ) || perms.contains(PosixFilePermission.OTHERS_WRITE)
                    || perms.contains(PosixFilePermission.OTHERS_EXECUTE)) {
                System.err.println("WARN: access log " + path + " is world-accessible ("
                        + PosixFilePermissions.toString(perms) + "); leaving mode unchanged");
            }
        } catch (IOException | UnsupportedOperationException e) {
            // best-effort warning only
        }
    }

    private static boolean posix(Path path) {
        return path.getFileSystem().supportedFileAttributeViews().contains("posix");
    }

    /** @param capacity power of two; tests use a tiny queue to force drops */
    AccessLog(OutputStream out, boolean closeOut, AccessLogFormat format, int capacity) {
        this(null, out, closeOut, format, capacity);
    }

    private AccessLog(Path path, OutputStream out, boolean closeOut, AccessLogFormat format, int capacity) {
        this(path, out, closeOut, format, capacity, path == null ? null : fileKey(path));
    }

    private AccessLog(Path path, OutputStream out, boolean closeOut, AccessLogFormat format, int capacity,
            Object openedKey) {
        this.queue = new LogQueue(capacity);
        this.path = path;
        this.out = out;
        this.closeOut = closeOut;
        this.format = format;
        this.openedKey = openedKey;
        this.nextReopenCheckNanos = System.nanoTime() + REOPEN_CHECK_NANOS;
        this.thread = Thread.ofPlatform().daemon().name("access-log").unstarted(this);
        this.thread.start();
    }

    /** Asks the writer to reopen the configured path (no-op for stdout / injected streams). */
    /** True while a forced reopen check is pending (tests). Cleared when the check runs. */
    boolean reopenForcedPending() {
        return this.reopenRequested;
    }

    void requestReopen() {
        if (this.path != null) {
            this.reopenRequested = true;
            LockSupport.unpark(this.thread);
        }
    }

    boolean headers() {
        return this.format != AccessLogFormat.COMMON;
    }

    long dropped() {
        return this.dropped.sum();
    }

    /**
     * Formats and enqueues one line. Returns immediately. Null strings are logged as {@code -}.
     * The caller still records metrics when this drops the line.
     */
    void offer(String remote, String method, String target, String protocol, int status, long bytes, long durationNanos,
            String referer, String userAgent) {
        if (this.stopped || this.failed || this.queue.isFull()) {
            this.dropped.increment();
            return;
        }
        byte[] line = AccessLine.format(this.format, System.currentTimeMillis(), this.clock.bytes(), remote, method,
                target, protocol, status, bytes, durationNanos, referer, userAgent);
        if (!this.queue.offer(line)) {
            this.dropped.increment();
        }
    }

    @Override
    public void run() {
        BufferedOutputStream buffered = new BufferedOutputStream(this.out, FLUSH_BYTES);
        long pending = 0;
        long flushedAt = System.nanoTime();
        try {
            while (true) {
                this.clock.refresh(System.currentTimeMillis());
                byte[] line = this.queue.poll();
                if (line == null) {
                    if (pending > 0 && System.nanoTime() - flushedAt >= FLUSH_NANOS) {
                        buffered.flush();
                        pending = 0;
                        flushedAt = System.nanoTime();
                    }
                    if (this.stopped) {
                        break;
                    }
                    BufferedOutputStream next = maybeReopen(buffered, pending > 0);
                    if (next != buffered) {
                        pending = 0;
                        flushedAt = System.nanoTime();
                        buffered = next;
                    }
                    LockSupport.parkNanos(1_000_000L);
                    continue;
                }
                buffered.write(line);
                pending += line.length;
                if (pending >= FLUSH_BYTES || System.nanoTime() - flushedAt >= FLUSH_NANOS) {
                    buffered.flush();
                    pending = 0;
                    flushedAt = System.nanoTime();
                }
                // Reopen check also on the busy path so a full queue cannot defer rotation forever.
                BufferedOutputStream next = maybeReopen(buffered, pending > 0);
                if (next != buffered) {
                    pending = 0;
                    flushedAt = System.nanoTime();
                    buffered = next;
                }
            }
            byte[] rest;
            while ((rest = this.queue.poll()) != null) {
                buffered.write(rest);
                pending += rest.length;
                BufferedOutputStream next = maybeReopen(buffered, pending > 0);
                if (next != buffered) {
                    pending = 0;
                    flushedAt = System.nanoTime();
                    buffered = next;
                }
            }
            buffered.flush();
        } catch (java.nio.channels.ClosedByInterruptException e) {
            // FileChannel write/flush is interruptible; close() may interrupt a shutdown flush.
            Thread.currentThread().interrupt();
            if (!this.stopped) {
                this.failed = true;
                System.err.println("WARN: access log write failed: " + e);
            }
        } catch (IOException | RuntimeException e) {
            this.failed = true;
            System.err.println("WARN: access log write failed: " + e);
        } finally {
            if (this.closeOut) {
                try {
                    this.out.close();
                } catch (IOException e) {
                    System.err.println("WARN: closing access log: " + e);
                }
            }
        }
    }

    /**
     * Writer-only. Detects an external rename/replace of {@link #path} (inode / file key changed, or
     * the path is gone) and reopens via {@link #openFile}. Request threads never call this.
     */
    private BufferedOutputStream maybeReopen(BufferedOutputStream buffered, boolean hadPending)
            throws IOException {
        if (this.path == null || this.failed) {
            return buffered;
        }
        boolean forced = this.reopenRequested;
        long now = System.nanoTime();
        if (!forced && now < this.nextReopenCheckNanos) {
            return buffered;
        }
        this.nextReopenCheckNanos = now + REOPEN_CHECK_NANOS;
        this.reopenRequested = false;
        // Follow the configured path for rotation detection; openedKey is the resolved leaf inode.
        boolean exists = Files.exists(this.path);
        Object current = exists ? fileKey(this.path) : null;
        boolean missing = !exists;
        // When the FS has no file keys, only forced reopen or a missing path triggers reopen.
        boolean replaced = exists && this.openedKey != null && current != null && !this.openedKey.equals(current);
        if (!forced && !missing && !replaced) {
            return buffered;
        }
        if (hadPending) {
            buffered.flush();
        }
        OutputStream previous = this.out;
        try {
            Opened opened = openFileWithKey(this.path);
            try {
                previous.close();
            } catch (IOException e) {
                System.err.println("WARN: closing rotated access log: " + e);
            }
            this.out = opened.stream;
            this.openedKey = opened.key;
            return new BufferedOutputStream(opened.stream, FLUSH_BYTES);
        } catch (IOException e) {
            // Keep writing the previous stream. nextReopenCheckNanos was already advanced above so
            // the next attempt waits for the 1s deadline (do not set reopenRequested — that would
            // bypass the deadline and WARN-storm under a persistent failure). Do not set failed.
            System.err.println("WARN: access log reopen failed; keeping previous stream: " + e);
            return buffered;
        }
    }

    /** File key after following symlinks (configured-path rotation poll). */
    private static Object fileKey(Path path) {
        try {
            return Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class).fileKey();
        } catch (IOException | UnsupportedOperationException e) {
            return null;
        }
    }

    /** File key of the leaf without following (matches the NOFOLLOW open). */
    private static Object fileKeyNoFollow(Path path) {
        try {
            return Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
                    .fileKey();
        } catch (IOException | UnsupportedOperationException e) {
            return null;
        }
    }

    @Override
    public void close() {
        this.stopped = true;
        // Unpark first: FileChannel write/flush is interruptible, and aborting a shutdown
        // flush would drop the last queued lines. Interrupt only if the join times out.
        LockSupport.unpark(this.thread);
        try {
            this.thread.join(2_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (this.thread.isAlive()) {
            this.thread.interrupt();
        }
    }

    /** CLF timestamp shared by every request thread; replaced once a second by the writer. */
    private static final class Clock {

        private volatile byte[] bytes = AccessLine.clfTime(System.currentTimeMillis());
        private long second = Long.MIN_VALUE;

        void refresh(long epochMillis) {
            long sec = Math.floorDiv(epochMillis, 1000L);
            if (sec != this.second) {
                this.bytes = AccessLine.clfTime(epochMillis);
                this.second = sec;
            }
        }

        byte[] bytes() {
            return this.bytes;
        }
    }

    /** Writes through to {@code out} and never closes it (stdout). */
    private static OutputStream unclosable(OutputStream out) {
        return new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                out.write(b);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                out.write(b, off, len);
            }

            @Override
            public void flush() throws IOException {
                out.flush();
            }
        };
    }
}
