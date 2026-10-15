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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.NavigableMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One segment file per group. {@link #force} calls {@link FileChannel#force}. A truncate rewrites
 * the segment from the in-memory index into a temp file and renames it into place (see
 * {@link #rewrite}); the hot path only appends.
 */
public final class FileLogStorage implements LogStorage {

    private final Path root;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<GroupId, NavigableMap<Long, Rec>> logs = new ConcurrentHashMap<>();
    private final Map<GroupId, FileChannel> channels = new ConcurrentHashMap<>();
    private final Map<GroupId, AtomicInteger> forceCounts = new ConcurrentHashMap<>();
    private final Map<GroupId, WitnessCursor> cursors = new ConcurrentHashMap<>();

    public FileLogStorage(Path root) throws IOException {
        this.root = root;
        Files.createDirectories(root);
    }

    private NavigableMap<Long, Rec> log(GroupId group) throws IOException {
        var existing = logs.get(group);
        if (existing != null) {
            return existing;
        }
        lock.lock();
        try {
            existing = logs.get(group);
            if (existing != null) {
                return existing;
            }
            var loaded = new ConcurrentSkipListMap<Long, Rec>();
            var path = path(group);
            Files.deleteIfExists(rewritePath(group));
            if (Files.exists(path) && Files.size(path) > 0) {
                try (var in = FileChannel.open(path, StandardOpenOption.READ)) {
                    var header = ByteBuffer.allocate(20);
                    while (in.position() < in.size()) {
                        header.clear();
                        if (in.read(header) != 20) {
                            break;
                        }
                        header.flip();
                        long index = header.getLong();
                        long term = header.getLong();
                        int len = header.getInt();
                        var data = new byte[len];
                        var body = ByteBuffer.wrap(data);
                        while (body.hasRemaining()) {
                            if (in.read(body) < 0) {
                                break;
                            }
                        }
                        loaded.put(index, new Rec(term, data));
                    }
                }
            }
            channels.put(group, FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ,
                    StandardOpenOption.WRITE));
            logs.put(group, loaded);
            return loaded;
        } finally {
            lock.unlock();
        }
    }

    private Path path(GroupId group) {
        var safe = group.value().replaceAll("[^A-Za-z0-9._-]", "_");
        return root.resolve(safe + ".log");
    }

    @Override
    public void append(GroupId group, long index, long term, byte[] data) throws IOException {
        if (index < 1) {
            throw new IllegalArgumentException("index");
        }
        checkUsable();
        var copy = data.clone();
        var log = log(group);
        lock.lock();
        try {
            var channel = channels.get(group);
            channel.position(channel.size());
            var header = ByteBuffer.allocate(20 + copy.length);
            header.putLong(index);
            header.putLong(term);
            header.putInt(copy.length);
            header.put(copy);
            header.flip();
            while (header.hasRemaining()) {
                channel.write(header);
            }
            log.put(index, new Rec(term, copy));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public byte[] read(GroupId group, long index) throws IOException {
        var rec = log(group).get(index);
        return rec == null ? null : rec.data.clone();
    }

    @Override
    public long termAt(GroupId group, long index) throws IOException {
        var rec = log(group).get(index);
        return rec == null ? 0L : rec.term;
    }

    /**
     * Loads the group's file on first use. Before this change a restarted node reported 0 here until some
     * other call loaded the file, so it compared candidates against an empty log.
     */
    @Override
    public long lastIndex(GroupId group) {
        NavigableMap<Long, Rec> log;
        try {
            log = log(group);
        } catch (IOException failed) {
            throw new java.io.UncheckedIOException(failed);
        }
        if (log.isEmpty()) {
            return 0L;
        }
        return log.lastKey();
    }

    @Override
    public boolean survivesRestart() {
        return true;
    }

    @Override
    public void truncateSuffix(GroupId group, long fromIndex) throws IOException {
        rewrite(group, log(group).headMap(fromIndex, false));
    }

    @Override
    public void truncatePrefix(GroupId group, long throughIndex) throws IOException {
        rewrite(group, log(group).tailMap(throughIndex, false));
    }

    /**
     * Rewrites the segment crash-safely: the kept records go to {@code <group>.log.rewrite}, which is
     * fsynced, atomically renamed over the segment, and the directory is fsynced. A crash at any point
     * leaves either the whole old segment or the whole new one, never a half-written file. The temp
     * is created fresh ({@code CREATE_NEW}, links not followed, mode 0600, so the segment ends up
     * 0600 too). A failure after the rename fails the storage closed (see {@link #checkUsable}).
     * Before this change the segment was truncated to zero in place and refilled without an fsync.
     */
    private void rewrite(GroupId group, NavigableMap<Long, Rec> keep) throws IOException {
        lock.lock();
        try {
            var target = path(group);
            var tmp = rewritePath(group);
            var fresh = new ConcurrentSkipListMap<Long, Rec>();
            checkUsable();
            Files.deleteIfExists(tmp);
            try (var out = FileChannel.open(tmp, java.util.Set.of(StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE, java.nio.file.LinkOption.NOFOLLOW_LINKS),
                    OWNER_ONLY)) {
                for (var entry : keep.entrySet()) {
                    var rec = entry.getValue();
                    var header = ByteBuffer.allocate(20 + rec.data.length);
                    header.putLong(entry.getKey());
                    header.putLong(rec.term);
                    header.putInt(rec.data.length);
                    header.put(rec.data);
                    header.flip();
                    while (header.hasRemaining()) {
                        out.write(header);
                    }
                    fresh.put(entry.getKey(), rec);
                }
                crashPoint(target.getFileName() + ":rewrite-written");
                out.force(true);
            }
            crashPoint(target.getFileName() + ":rewrite-temp");
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            // Past the rename the disk holds the new segment. Any failure from here on leaves this
            // instance unsure what is durable, so it fails closed: every later append, force and
            // truncate is refused until the process restarts and reloads the segment from disk.
            try {
                crashPoint(target.getFileName() + ":rewrite-renamed");
                var reopened = FileChannel.open(target, StandardOpenOption.READ, StandardOpenOption.WRITE);
                var old = channels.put(group, reopened);
                logs.put(group, fresh);
                if (old != null) {
                    old.close();
                }
                crashPoint(target.getFileName() + ":rewrite-dir-sync");
                try (var dir = FileChannel.open(root, StandardOpenOption.READ)) {
                    dir.force(true);
                }
            } catch (IOException | RuntimeException failed) {
                failedClosed = new IOException("log storage failed after renaming the rewritten segment of " + group
                        + "; restart to recover from disk", failed);
                throw failed;
            }
            crashPoint(target.getFileName() + ":rewrite-dir-synced");
        } finally {
            lock.unlock();
        }
    }

    /** rw------- for the rewrite temp file, like the snapshot and vote files. */
    private static final java.nio.file.attribute.FileAttribute<?> OWNER_ONLY = java.nio.file.attribute.PosixFilePermissions
            .asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));

    /** Set once a segment rewrite fails past its rename; then appends, forces and truncates are refused. */
    private volatile IOException failedClosed;

    private void checkUsable() throws IOException {
        var failed = failedClosed;
        if (failed != null) {
            throw new IOException(failed.getMessage(), failed);
        }
    }

    private Path rewritePath(GroupId group) {
        return root.resolve(path(group).getFileName() + ".rewrite");
    }

    @Override
    public void force(GroupId group) throws IOException {
        checkUsable();
        log(group);
        lock.lock();
        try {
            channels.get(group).force(true);
            var cursor = cursors.get(group);
            if (cursor != null && cursor.dirty()) {
                writeCursor(group, cursor.stagedIndex(), cursor.stagedTerm());
                cursor.publish();
            }
            var count = forceCounts.get(group);
            if (count == null) {
                var created = new AtomicInteger();
                var raced = forceCounts.putIfAbsent(group, created);
                count = raced == null ? created : raced;
            }
            count.incrementAndGet();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public long witnessIndex(GroupId group) throws IOException {
        lock.lock();
        try {
            return cursorLocked(group).index();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public long witnessTerm(GroupId group) throws IOException {
        lock.lock();
        try {
            return cursorLocked(group).term();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void noteWitness(GroupId group, long index, long term) throws IOException {
        lock.lock();
        try {
            cursorLocked(group).note(index, term);
        } finally {
            lock.unlock();
        }
    }

    private WitnessCursor cursorLocked(GroupId group) throws IOException {
        var existing = cursors.get(group);
        if (existing != null) {
            return existing;
        }
        var created = new WitnessCursor();
        var loaded = readCursor(group);
        created.load(loaded.index, loaded.term);
        cursors.put(group, created);
        return created;
    }

    private CursorFile readCursor(GroupId group) throws IOException {
        var path = witnessPath(group);
        if (!Files.isRegularFile(path) || Files.size(path) < 16) {
            return new CursorFile(0, 0);
        }
        var buf = ByteBuffer.wrap(Files.readAllBytes(path));
        return new CursorFile(buf.getLong(), buf.getLong());
    }

    private void writeCursor(GroupId group, long index, long term) throws IOException {
        var buf = ByteBuffer.allocate(16);
        buf.putLong(index);
        buf.putLong(term);
        buf.flip();
        writeAtomically(witnessPath(group), buf);
    }

    private void writeAtomically(Path target, ByteBuffer buf) throws IOException {
        var tmp = root.resolve(target.getFileName().toString() + ".tmp");
        try (var channel = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            while (buf.hasRemaining()) {
                channel.write(buf);
            }
            channel.force(true);
        }
        crashPoint(target.getFileName() + ":temp");
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        try (var dir = FileChannel.open(root, StandardOpenOption.READ)) {
            dir.force(true);
        }
    }

    private Path witnessPath(GroupId group) {
        var safe = group.value().replaceAll("[^A-Za-z0-9._-]", "_");
        return root.resolve(safe + ".witness");
    }

    private record CursorFile(long index, long term) {
    }

    /** Tests only: throws at a named point to simulate a crash there. Null in production. */
    volatile java.util.function.Consumer<String> crashHook;

    private void crashPoint(String stage) throws IOException {
        var hook = crashHook;
        if (hook != null) {
            hook.accept(stage);
        }
    }

    /** {@code "SJVT"}: the vote file magic. */
    static final int VOTE_MAGIC = 0x534A5654;
    static final int VOTE_VERSION = 1;
    static final int VOTE_FILE_BYTES = 24;

    /**
     * {@code {group}.vote}, 24 bytes big-endian: magic, version, term, votedFor wire id, then the
     * CRC32C of the first 20 bytes. Temp file + fsync + atomic move + directory fsync.
     */
    @Override
    public void saveHardState(GroupId group, long term, int votedFor) throws IOException {
        lock.lock();
        try {
            var buf = ByteBuffer.allocate(VOTE_FILE_BYTES);
            buf.putInt(VOTE_MAGIC);
            buf.putInt(VOTE_VERSION);
            buf.putLong(term);
            buf.putInt(votedFor);
            var crc = new java.util.zip.CRC32C();
            crc.update(buf.array(), 0, 20);
            buf.putInt((int) crc.getValue());
            buf.flip();
            writeAtomically(votePath(group), buf);
            crashPoint("vote-written");
            var marker = voteMarkerPath(group);
            if (!Files.exists(marker, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                // After the vote file: a crash between the two leaves a vote file and no marker, which
                // loads as saved state. The other order would leave a marker and no vote, refusing startup.
                writeAtomically(marker, ByteBuffer.wrap(new byte[] {1}));
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * A missing vote file is an upgrade only while the first-save marker is also missing. Once a
     * vote has been saved, a missing vote file means it was deleted, and startup is refused.
     */
    @Override
    public boolean hasHardState(GroupId group) throws IOException {
        if (Files.exists(votePath(group), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return true;
        }
        if (Files.exists(voteMarkerPath(group), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("vote file " + votePath(group) + " is missing but " + voteMarkerPath(group)
                    + " shows a vote was saved; refusing to start with an unknown term");
        }
        return false;
    }

    private Path voteMarkerPath(GroupId group) {
        var safe = group.value().replaceAll("[^A-Za-z0-9._-]", "_");
        return root.resolve(safe + ".vote-saved");
    }

    @Override
    public long hardStateTerm(GroupId group) throws IOException {
        var buf = readVote(group);
        return buf == null ? 0L : buf.getLong(8);
    }

    @Override
    public int hardStateVote(GroupId group) throws IOException {
        var buf = readVote(group);
        return buf == null ? 0 : buf.getInt(16);
    }

    /** Null when absent. Anything else that is not a valid version-1 record refuses to load. */
    private ByteBuffer readVote(GroupId group) throws IOException {
        var path = votePath(group);
        if (!hasHardState(group)) {
            return null;
        }
        java.nio.channels.SeekableByteChannel channel;
        // NOFOLLOW_LINKS: a symlink in place of the vote file fails here (ELOOP) instead of being read.
        try {
            channel = Files.newByteChannel(path, java.util.Set.of(StandardOpenOption.READ,
                    java.nio.file.LinkOption.NOFOLLOW_LINKS));
        } catch (IOException refused) {
            throw new IOException("vote file " + path + " cannot be opened without following links ("
                    + refused.getMessage() + "); refusing to start", refused);
        }
        byte[] bytes;
        try (channel) {
            var buf = ByteBuffer.allocate(VOTE_FILE_BYTES + 1);
            while (buf.hasRemaining() && channel.read(buf) > 0) {
                // read at most one byte past the expected size
            }
            bytes = java.util.Arrays.copyOf(buf.array(), buf.position());
        }
        if (bytes.length != VOTE_FILE_BYTES) {
            throw new IOException("vote file " + path + " has " + bytes.length + " bytes, expected "
                    + VOTE_FILE_BYTES + "; refusing to start with an unknown term");
        }
        var buf = ByteBuffer.wrap(bytes);
        if (buf.getInt(0) != VOTE_MAGIC) {
            throw new IOException("vote file " + path + " has a bad magic; refusing to start");
        }
        if (buf.getInt(4) != VOTE_VERSION) {
            throw new IOException("vote file " + path + " has unsupported version " + buf.getInt(4)
                    + "; refusing to start");
        }
        var crc = new java.util.zip.CRC32C();
        crc.update(bytes, 0, 20);
        if ((int) crc.getValue() != buf.getInt(20)) {
            throw new IOException("vote file " + path + " fails its CRC32C; refusing to start");
        }
        return buf;
    }

    private Path votePath(GroupId group) {
        var safe = group.value().replaceAll("[^A-Za-z0-9._-]", "_");
        return root.resolve(safe + ".vote");
    }

    @Override
    public int forces(GroupId group) {
        var count = forceCounts.get(group);
        return count == null ? 0 : count.get();
    }

    public void close() throws IOException {
        lock.lock();
        try {
            for (var channel : channels.values()) {
                channel.close();
            }
            channels.clear();
        } finally {
            lock.unlock();
        }
    }

    private record Rec(long term, byte[] data) {
    }
}
