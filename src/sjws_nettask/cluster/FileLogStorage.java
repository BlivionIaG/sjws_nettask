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
 *
 * <p><b>Rolling mode</b> ({@link #FileLogStorage(Path, long)} with a finite {@code rollBytes}, #270): once the active
 * {@code <group>.log} reaches {@code rollBytes} it is fsynced and renamed to the sealed
 * {@code <group>.<first index, 20 digits>.seg}, and a fresh {@code <group>.log} takes the appends. A prefix truncation
 * then drops the entries from memory and unlinks the sealed files it covers wholly: no copy, and the lock is held only
 * for the in-memory removal, so a snapshot truncation never stalls appends of any group for a rewrite. Unlinks are not
 * directory-fsynced: after a crash a deleted file may come back, and loading keeps only the contiguous run of indexes
 * that ends at the newest entry, so a resurrected older file is ignored (and anything it holds is covered by the
 * durable snapshot that allowed the truncation). A suffix truncation (rare: a conflicting uncommitted tail) rewrites
 * every kept entry into a new active file first and only then deletes the sealed files; loading treats the active file
 * as authoritative from its first index on.
 */
public final class FileLogStorage implements LogStorage {

    private final Path root;
    private final ReentrantLock lock = new ReentrantLock();
    /** Serializes snapshot file writes; separate from {@link #lock} so appends never wait for them. */
    private final ReentrantLock snapLock = new ReentrantLock();
    private final Map<GroupId, NavigableMap<Long, Rec>> logs = new ConcurrentHashMap<>();
    private final Map<GroupId, FileChannel> channels = new ConcurrentHashMap<>();
    private final Map<GroupId, AtomicInteger> forceCounts = new ConcurrentHashMap<>();
    private final Map<GroupId, WitnessCursor> cursors = new ConcurrentHashMap<>();
    /** Rolling mode: size at which the active file is sealed; {@link Long#MAX_VALUE} = one file per group. */
    private final long rollBytes;
    /** Rolling mode, per group: sealed files by first index (guarded by {@link #lock}). */
    private final Map<GroupId, java.util.TreeMap<Long, Sealed>> sealed = new ConcurrentHashMap<>();

    /** A sealed segment: its path and the index range it holds. */
    private record Sealed(Path path, long first, long last) {
    }

    public FileLogStorage(Path root) throws IOException {
        this(root, Long.MAX_VALUE);
    }

    /**
     * @param rollBytes seal the active file once it holds this many bytes (rolling mode, see the class doc);
     *                  {@link Long#MAX_VALUE} keeps one file per group
     */
    public FileLogStorage(Path root, long rollBytes) throws IOException {
        if (rollBytes < 1) {
            throw new IllegalArgumentException("rollBytes");
        }
        this.root = root;
        this.rollBytes = rollBytes;
        Files.createDirectories(root);
    }

    private boolean rolling() {
        return rollBytes != Long.MAX_VALUE;
    }

    private String safe(GroupId group) {
        return group.value().replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private Path sealedPath(GroupId group, long first) {
        return root.resolve(safe(group) + "." + String.format("%020d", first) + ".seg");
    }

    /** Reads one file of {@code [index][term][len][data]} records; stops at a torn tail. */
    private static java.util.TreeMap<Long, Rec> readRecords(Path path) throws IOException {
        var out = new java.util.TreeMap<Long, Rec>();
        if (!Files.exists(path) || Files.size(path) == 0) {
            return out;
        }
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
                if (len < 0 || len > in.size() - in.position()) {
                    break; // torn tail
                }
                var data = new byte[len];
                var body = ByteBuffer.wrap(data);
                while (body.hasRemaining()) {
                    if (in.read(body) < 0) {
                        break;
                    }
                }
                if (body.hasRemaining()) {
                    break;
                }
                out.put(index, new Rec(term, data));
            }
        }
        return out;
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
            if (rolling()) {
                loadRolling(group, loaded);
            } else if (Files.exists(path) && Files.size(path) > 0) {
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
            maybeRoll(group, channel, log, index);
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
        var log = log(group);
        if (!rolling()) {
            rewrite(group, log.headMap(fromIndex, false));
            return;
        }
        lock.lock();
        try {
            // Every kept entry into one new active file first; the sealed files go only after the rename.
            rewrite(group, log.headMap(fromIndex, false));
            var tracked = sealed.remove(group);
            sealed.put(group, new java.util.TreeMap<>());
            if (tracked != null) {
                for (var s : tracked.values()) {
                    Files.deleteIfExists(s.path);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void truncatePrefix(GroupId group, long throughIndex) throws IOException {
        var log = log(group);
        if (!rolling()) {
            rewrite(group, log.tailMap(throughIndex, false));
            return;
        }
        var drop = new java.util.ArrayList<Path>();
        lock.lock();
        try {
            checkUsable();
            logs.get(group).headMap(throughIndex, true).clear(); // the current map: a suffix rewrite may have swapped it
            var tracked = sealed.get(group);
            if (tracked != null) {
                var it = tracked.values().iterator();
                while (it.hasNext()) {
                    var s = it.next();
                    if (s.last > throughIndex) {
                        break;
                    }
                    drop.add(s.path);
                    it.remove();
                }
            }
        } finally {
            lock.unlock();
        }
        for (var p : drop) { // outside the lock: unlinking a large file is not free
            Files.deleteIfExists(p);
        }
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

    /**
     * Rolling mode load: sealed files in first-index order, then the active file, which is authoritative from its first
     * index on (a suffix truncation rewrote it before deleting sealed files). Keeps only the contiguous run of indexes
     * ending at the newest entry; sealed files left with nothing in that run are deleted.
     */
    private void loadRolling(GroupId group, NavigableMap<Long, Rec> loaded) throws IOException {
        var files = new java.util.TreeMap<Long, Path>();
        var prefix = safe(group) + ".";
        try (var list = Files.list(root)) {
            for (var p : (Iterable<Path>) list::iterator) {
                var name = p.getFileName().toString();
                if (name.startsWith(prefix) && name.endsWith(".seg")) {
                    var digits = name.substring(prefix.length(), name.length() - 4);
                    if (digits.length() == 20 && digits.chars().allMatch(Character::isDigit)) {
                        files.put(Long.parseLong(digits), p);
                    }
                }
            }
        }
        var ranges = new java.util.TreeMap<Long, long[]>();
        for (var f : files.entrySet()) {
            var recs = readRecords(f.getValue());
            if (!recs.isEmpty()) {
                ranges.put(f.getKey(), new long[] {recs.firstKey(), recs.lastKey()});
                loaded.putAll(recs);
            }
        }
        var active = readRecords(path(group));
        if (!active.isEmpty()) {
            loaded.tailMap(active.firstKey(), true).clear();
            loaded.putAll(active);
        }
        if (!loaded.isEmpty()) {
            long expect = loaded.lastKey();
            long keepFrom = expect;
            for (long k : loaded.descendingKeySet()) {
                if (k != expect) {
                    break;
                }
                keepFrom = k;
                expect--;
            }
            loaded.headMap(keepFrom, false).clear();
        }
        var tracked = new java.util.TreeMap<Long, Sealed>();
        long activeFirst = active.isEmpty() ? Long.MAX_VALUE : active.firstKey();
        for (var f : files.entrySet()) {
            var r = ranges.get(f.getKey());
            if (r == null || r[0] >= activeFirst || loaded.isEmpty() || r[1] < loaded.firstKey()) {
                Files.deleteIfExists(f.getValue());
                continue;
            }
            tracked.put(r[0], new Sealed(f.getValue(), r[0], Math.min(r[1], activeFirst - 1)));
        }
        sealed.put(group, tracked);
    }

    /** Rolling mode, under the lock: seal the active file once it reached {@link #rollBytes}. */
    private void maybeRoll(GroupId group, FileChannel channel, NavigableMap<Long, Rec> log, long lastIndex)
            throws IOException {
        if (!rolling() || channel.size() < rollBytes) {
            return;
        }
        var tracked = sealed.computeIfAbsent(group, g -> new java.util.TreeMap<>());
        long first = tracked.isEmpty() ? log.firstKey() : tracked.lastEntry().getValue().last + 1;
        channel.force(true); // every byte of the sealed file is durable before force() stops covering it
        channel.close();
        var target = sealedPath(group, first);
        Files.move(path(group), target, StandardCopyOption.ATOMIC_MOVE);
        try (var dir = FileChannel.open(root, StandardOpenOption.READ)) {
            dir.force(true);
        }
        tracked.put(first, new Sealed(target, first, lastIndex));
        channels.put(group, FileChannel.open(path(group), StandardOpenOption.CREATE, StandardOpenOption.READ,
                StandardOpenOption.WRITE));
    }

    /** Rolling mode: sealed files of {@code group} (tests, disk accounting). */
    int sealedFiles(GroupId group) {
        lock.lock();
        try {
            var t = sealed.get(group);
            return t == null ? 0 : t.size();
        } finally {
            lock.unlock();
        }
    }

    /** Bytes on disk for {@code group}'s entries: the active file plus every sealed one (disk accounting, tests). */
    public long entryBytes(GroupId group) throws IOException {
        log(group);
        lock.lock();
        try {
            long n = Files.exists(path(group)) ? Files.size(path(group)) : 0;
            var t = sealed.get(group);
            if (t != null) {
                for (var s : t.values()) {
                    n += Files.exists(s.path) ? Files.size(s.path) : 0;
                }
            }
            return n;
        } finally {
            lock.unlock();
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

    /** {@code "SJSN"}: the snapshot file magic. */
    static final int SNAP_MAGIC = 0x534A534E;
    static final int SNAP_VERSION = 1;
    /** magic, version, index, term, length. */
    static final int SNAP_HEADER_BYTES = 4 + 4 + 8 + 8 + 4;

    @Override
    public boolean persistsSnapshots() {
        return true;
    }

    /**
     * {@code {group}.snap}: magic, version, last included index and term, image length, image, then
     * the CRC32C of everything before it. Mode 0600 from creation (the image can hold token hashes
     * and RBAC state), temp file + fsync + atomic move + directory fsync.
     */
    @Override
    public void saveSnapshot(GroupId group, LogSnapshot snapshot) throws IOException {
        var data = snapshot.data();
        var buf = ByteBuffer.allocate(SNAP_HEADER_BYTES + data.length + 4);
        buf.putInt(SNAP_MAGIC);
        buf.putInt(SNAP_VERSION);
        buf.putLong(snapshot.lastIncludedIndex());
        buf.putLong(snapshot.lastIncludedTerm());
        buf.putInt(data.length);
        buf.put(data);
        var crc = new java.util.zip.CRC32C();
        crc.update(buf.array(), 0, buf.position());
        buf.putInt((int) crc.getValue());
        buf.flip();
        var target = snapPath(group);
        var tmp = root.resolve(target.getFileName() + ".tmp");
        snapLock.lock(); // not the append lock: a snapshot's fsyncs never hold up appends of any group (#270)
        try {
            Files.deleteIfExists(tmp);
            var attrs = new java.util.ArrayList<java.nio.file.attribute.FileAttribute<?>>();
            if (root.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                attrs.add(java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
            }
            try (var channel = FileChannel.open(tmp, java.util.Set.of(StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE, java.nio.file.LinkOption.NOFOLLOW_LINKS),
                    attrs.toArray(new java.nio.file.attribute.FileAttribute<?>[0]))) {
                while (buf.hasRemaining()) {
                    channel.write(buf);
                }
                channel.force(true);
            }
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            try (var dir = FileChannel.open(root, StandardOpenOption.READ)) {
                dir.force(true);
            }
        } finally {
            snapLock.unlock();
        }
    }

    /**
     * Read with {@link sjws_nettask.config.SecureKeyFile} rules: leaf opened without following
     * links, regular file, mode 0600/0400, owned by this process, parents not writable by others.
     * Any failed check, size, magic, version, length or CRC refuses to start.
     */
    @Override
    public LogSnapshot loadSnapshot(GroupId group, int maxBytes) throws IOException {
        var path = snapPath(group);
        if (!Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        byte[] bytes;
        try {
            bytes = sjws_nettask.config.SecureKeyFile.read(path, "snapshot " + path,
                    (int) Math.min(Integer.MAX_VALUE - 8, (long) maxBytes + SNAP_HEADER_BYTES + 4 + 1));
        } catch (RuntimeException refused) {
            throw new IOException("snapshot " + path + " refused (" + refused.getMessage() + "); refusing to start",
                    refused);
        }
        if (bytes.length < SNAP_HEADER_BYTES + 4) {
            throw new IOException("snapshot " + path + " is truncated; refusing to start");
        }
        var buf = ByteBuffer.wrap(bytes);
        if (buf.getInt(0) != SNAP_MAGIC) {
            throw new IOException("snapshot " + path + " has a bad magic; refusing to start");
        }
        if (buf.getInt(4) != SNAP_VERSION) {
            throw new IOException("snapshot " + path + " has unsupported version " + buf.getInt(4)
                    + "; refusing to start");
        }
        int length = buf.getInt(24);
        if (length < 0 || length > maxBytes) {
            throw new IOException("snapshot " + path + " image of " + length + " bytes exceeds the limit of " + maxBytes
                    + "; refusing to start");
        }
        if (bytes.length != SNAP_HEADER_BYTES + length + 4) {
            throw new IOException("snapshot " + path + " has " + bytes.length + " bytes, header says "
                    + (SNAP_HEADER_BYTES + length + 4) + "; refusing to start");
        }
        var crc = new java.util.zip.CRC32C();
        crc.update(bytes, 0, SNAP_HEADER_BYTES + length);
        if ((int) crc.getValue() != buf.getInt(SNAP_HEADER_BYTES + length)) {
            throw new IOException("snapshot " + path + " fails its CRC32C; refusing to start");
        }
        long index = buf.getLong(8);
        long term = buf.getLong(16);
        if (index < 0 || term < 0) {
            throw new IOException("snapshot " + path + " has a negative index or term; refusing to start");
        }
        return new LogSnapshot(group, index, term, java.util.Arrays.copyOfRange(bytes, SNAP_HEADER_BYTES,
                SNAP_HEADER_BYTES + length));
    }

    private Path snapPath(GroupId group) {
        var safe = group.value().replaceAll("[^A-Za-z0-9._-]", "_");
        return root.resolve(safe + ".snap");
    }
}
