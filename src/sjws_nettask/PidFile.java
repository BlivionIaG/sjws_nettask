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
package sjws_nettask;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;

/**
 * PID file for the running server. The owner holds an exclusive lock for its lifetime, so a
 * crash (lock released by the kernel) is distinguishable from "still running". The file stores
 * the pid and the process start time in epoch millis; a recycled pid whose start time differs
 * is treated as stale and is never signalled.
 *
 * <pre>
 * 12345
 * 1712345678901
 * </pre>
 *
 * The file should live on a local, trusted directory ({@code /run}, not an untrusted shared
 * folder): the open follows a last-moment symlink swap. A directory, fifo, device or other
 * non-regular file is stale by type. Its {@code st_size} is a filesystem detail (4096 on ext4,
 * 40 on tmpfs) and is never treated as a content length. The size cap applies only to regular
 * files. {@link #claim} still refuses a symlink, and refuses any other non-regular path.
 */
final class PidFile implements AutoCloseable {

    /** Regular pid files longer than this are garbage. Not applied to directories or symlinks. */
    private static final long MAX_BYTES = 512;

    /** What {@link #read(Path)} found. {@code startedMillis} is -1 when the file omitted it. */
    record Snapshot(boolean present, long pid, long startedMillis, boolean running) {
        static final Snapshot ABSENT = new Snapshot(false, -1, -1, false);

        boolean stale() {
            return this.present && !this.running;
        }
    }

    /** Result of {@link #claim(Path)}. {@code file} is non-null only when {@code acquired}. */
    record Claim(boolean acquired, PidFile file, Snapshot snapshot) {
    }

    private final Path path;
    private final FileChannel channel;
    private final long pid;
    private final long startedMillis;
    private final AtomicBoolean released = new AtomicBoolean();

    private PidFile(Path path, FileChannel channel, long pid, long startedMillis) {
        this.path = path;
        this.channel = channel;
        this.pid = pid;
        this.startedMillis = startedMillis;
    }

    static String text(long pid, long startedMillis) {
        return pid + "\n" + startedMillis + "\n";
    }

    /**
     * @return a snapshot; a missing file is {@link Snapshot#ABSENT}, never an exception.
     *         Attributes are read with {@link LinkOption#NOFOLLOW_LINKS}. A symlink is then
     *         classified by its target; a directory or other non-regular file is stale garbage
     *         without being opened.
     */
    static Snapshot read(Path path) {
        BasicFileAttributes self;
        try {
            self = selfAttributes(path);
        } catch (IOException e) {
            return Snapshot.ABSENT;
        }
        if (self.isSymbolicLink()) {
            return readFollowed(path);
        }
        if (!self.isRegularFile()) {
            return garbage();
        }
        return readRegular(path, self.size());
    }

    /**
     * Claims {@code path} for this process, replacing a stale file. Fails (without changing the
     * file) when a live owner already holds it or another claim in this JVM holds the lock.
     * A file that names this process but not this process's start time is stale: the pid was
     * recycled onto us, and the previous owner is gone because this call holds the lock.
     * A symlink is refused without being followed. A directory or other non-regular file is
     * refused without being opened.
     */
    static Claim claim(Path path) throws IOException {
        BasicFileAttributes self;
        try {
            self = selfAttributes(path);
        } catch (NoSuchFileException e) {
            self = null;
        }
        if (self != null && self.isSymbolicLink()) {
            throw new IOException("pid file is a symlink");
        }
        if (self != null && !self.isRegularFile()) {
            throw new IOException("pid file is not a regular file");
        }
        var parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        var channel = FileChannel.open(path, CREATE, READ, WRITE);
        boolean keep = false;
        try {
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException e) {
                return new Claim(false, null, read(path));
            }
            if (lock == null) {
                return new Claim(false, null, read(path));
            }
            var existing = parse(readChannel(channel));
            if (blocksStart(existing)) {
                try {
                    lock.release();
                } catch (IOException e) {
                    // closing the channel below releases the lock
                }
                return new Claim(false, null, existing);
            }
            long pid = ProcessHandle.current().pid();
            long started = ProcessHandle.current().info().startInstant().map(Instant::toEpochMilli).orElse(-1L);
            write(channel, pid, started);
            var file = new PidFile(path, channel, pid, started);
            keep = true;
            return new Claim(true, file, existing);
        } finally {
            if (!keep) {
                try {
                    channel.close();
                } catch (IOException e) {
                    // keep the claim result (or the write failure) rather than hiding it
                }
            }
        }
    }

    /**
     * Deletes {@code path} when it still contains {@code snap} and no live owner holds the lock.
     * A server that claimed the file between the caller's read and this call keeps its file.
     */
    static void removeIfSame(Snapshot snap, Path path) throws IOException {
        BasicFileAttributes self;
        try {
            self = selfAttributes(path);
        } catch (NoSuchFileException e) {
            return;
        }
        // Do not open a directory, symlink, fifo or device: a fifo blocks, and a directory
        // is unlinked only when it is empty.
        if (self.isSymbolicLink() || !self.isRegularFile()) {
            if (sameOwner(snap, read(path))) {
                Files.deleteIfExists(path);
            }
            return;
        }
        FileChannel channel;
        try {
            channel = FileChannel.open(path, READ, WRITE);
        } catch (java.nio.file.NoSuchFileException e) {
            return;
        } catch (java.nio.file.AccessDeniedException e) {
            // A read-only stale file can still be unlinked when the directory is writable.
            if (sameOwner(snap, read(path))) {
                Files.deleteIfExists(path);
            }
            return;
        }
        try {
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException e) {
                return;
            }
            if (lock == null) {
                return;
            }
            try {
                if (sameOwner(snap, parse(readChannel(channel)))) {
                    Files.deleteIfExists(path);
                }
            } finally {
                try {
                    lock.release();
                } catch (IOException e) {
                    // closing the channel releases the lock
                }
            }
        } finally {
            channel.close();
        }
    }

    /**
     * Removes the file when it still names this process, then releases the lock. Idempotent.
     * On Linux the directory entry is removed while the channel is still open, so a concurrent
     * claim cannot observe our pid and then lose the file.
     */
    @Override
    public void close() throws IOException {
        release();
    }

    void release() throws IOException {
        if (!this.released.compareAndSet(false, true)) {
            return;
        }
        IOException failure = null;
        try {
            var current = parse(readChannel(this.channel));
            if (current.pid == this.pid && current.startedMillis == this.startedMillis) {
                try {
                    Files.deleteIfExists(this.path);
                } catch (IOException e) {
                    failure = e;
                    this.channel.truncate(0);
                    this.channel.force(true);
                }
            }
        } finally {
            try {
                this.channel.close();
            } catch (IOException e) {
                if (failure == null) {
                    failure = e;
                }
            }
        }
        if (Files.exists(this.path)) {
            var snap = read(this.path);
            if (!snap.running || (snap.pid == this.pid && snap.startedMillis == this.startedMillis)) {
                try {
                    Files.deleteIfExists(this.path);
                } catch (IOException e) {
                    if (failure == null) {
                        failure = e;
                    }
                }
            }
        }
        if (failure != null && Files.exists(this.path)) {
            throw failure;
        }
    }

    Path path() {
        return this.path;
    }

    private static void write(FileChannel channel, long pid, long started) throws IOException {
        var bytes = text(pid, started).getBytes(StandardCharsets.UTF_8);
        channel.truncate(0);
        channel.position(0);
        channel.write(ByteBuffer.wrap(bytes));
        channel.force(true);
    }

    private static BasicFileAttributes selfAttributes(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    /** Stale garbage: the path exists, but it is not a pid this process may signal. */
    private static Snapshot garbage() {
        return new Snapshot(true, -1, -1, false);
    }

    /** Follow a symlink and classify the final target. A broken link is absent. */
    private static Snapshot readFollowed(Path path) {
        BasicFileAttributes target;
        try {
            target = Files.readAttributes(path, BasicFileAttributes.class);
        } catch (IOException e) {
            return Snapshot.ABSENT;
        }
        if (!target.isRegularFile()) {
            return garbage();
        }
        return readRegular(path, target.size());
    }

    /** {@code size} is the regular file's length, never a directory's {@code st_size}. */
    private static Snapshot readRegular(Path path, long size) {
        if (size > MAX_BYTES) {
            return garbage();
        }
        try {
            return parse(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return Snapshot.ABSENT;
        }
    }

    private static String readChannel(FileChannel channel) throws IOException {
        channel.position(0);
        int len = (int) Math.min(channel.size(), MAX_BYTES);
        var buf = ByteBuffer.allocate(len);
        while (buf.hasRemaining()) {
            if (channel.read(buf) < 0) {
                break;
            }
        }
        return new String(buf.array(), 0, buf.position(), StandardCharsets.UTF_8);
    }

    static Snapshot parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Snapshot.ABSENT;
        }
        var lines = raw.split("\\R", -1);
        if (lines.length == 0 || !lines[0].matches("[0-9]{1,19}")) {
            return new Snapshot(true, -1, -1, false);
        }
        long pid = Long.parseLong(lines[0]);
        long started = -1;
        if (lines.length > 1 && lines[1].matches("[0-9]{1,19}")) {
            started = Long.parseLong(lines[1]);
        }
        return new Snapshot(true, pid, started, isLive(pid, started));
    }

    /**
     * True when {@code snap} names a live server that a new start must not replace.
     * A file that names this process with no start time does not: {@link #isLive} accepts that
     * file, but the pid was recycled onto us and this process did not write it.
     */
    static boolean blocksStart(Snapshot snap) {
        if (!snap.running()) {
            return false;
        }
        return !(snap.pid == ProcessHandle.current().pid() && snap.startedMillis < 0);
    }

    /**
     * True when {@code snap} is the pid file of {@code handle}: same pid, and the same start time
     * when both sides know one. A file with no start time does not match a process that has one,
     * so a recycled pid cannot look ready before the child rewrites the file.
     */
    static boolean identifies(Snapshot snap, ProcessHandle handle) {
        if (!snap.running() || snap.pid() != handle.pid()) {
            return false;
        }
        long actual = handle.info().startInstant().map(Instant::toEpochMilli).orElse(-1L);
        if (snap.startedMillis() < 0 || actual < 0) {
            return snap.startedMillis() < 0 && actual < 0;
        }
        return snap.startedMillis() == actual;
    }

    /**
     * A pid is ours only when it is alive and, if both sides know a start time, the times match.
     * A recycled pid (alive, different start) is not live. When the OS cannot report a start time,
     * an alive pid is accepted: Linux reports it, so this fallback is for other platforms.
     */
    static boolean isLive(long pid, long startedMillis) {
        if (pid <= 0) {
            return false;
        }
        var handle = ProcessHandle.of(pid).filter(ProcessHandle::isAlive).orElse(null);
        if (handle == null) {
            return false;
        }
        if (startedMillis < 0) {
            return true;
        }
        var actual = handle.info().startInstant().map(Instant::toEpochMilli).orElse(-1L);
        return actual < 0 || actual == startedMillis;
    }

    /** Same pid and start time, so {@code --stop} does not delete a file a newer process just wrote. */
    static boolean sameOwner(Snapshot before, Snapshot after) {
        return after.present && after.pid == before.pid && after.startedMillis == before.startedMillis;
    }
}
