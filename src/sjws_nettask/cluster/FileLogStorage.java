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
 * that ends at the newest entry when that run starts at {@code snapIndex + 1} (or at/below snapIndex); a suffix that
 * starts above {@code snapIndex + 1} refuses to load. A resurrected older file outside the kept run is ignored when
 * the durable snapshot covers it. A suffix truncation (rare: a conflicting uncommitted tail) rewrites
 * every kept entry into a new active file first and only then deletes the sealed files; loading treats the active file
 * as authoritative from its first index on.
 *
 * <p>On open, a torn (half-written) last record is detected by length/framing, truncated, and the file and directory
 * are fsynced (#241). Mid-log framing corruption fails closed. Each record carries a CRC32C over
 * {@code index|term|len|data} (#207): a mismatch on the last record is treated as a corrupt/torn tail and truncated;
 * a mismatch earlier in the file fails closed. On-disk format is versioned ({@code FLG2} file header); legacy
 * pre-CRC segments are readable and upgraded to the CRC format on open (active file rewrite).
 */
public final class FileLogStorage implements LogStorage {

    private final Path root;
    private final ReentrantLock lock = new ReentrantLock();
    /** Serializes snapshot file writes; separate from {@link #lock} so appends never wait for them. */
    private final ReentrantLock snapLock = new ReentrantLock();
    private static final System.Logger LOG = System.getLogger(FileLogStorage.class.getName());
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

    /**
     * One file scan: complete records and the end offset of the last one. A torn or CRC-corrupt tail
     * ({@code lastGoodPos < fileSize}) is truncated by the caller with an fsync of the file and directory
     * (#241, #207). Header and body reads loop until full or EOF: a short mid-file read is never treated
     * as torn. A complete header with a negative length, or a CRC mismatch with bytes after the record,
     * fails closed. {@code legacy} is true when the file had no {@code FLG2} header (pre-#207).
     */
    record Scan(java.util.TreeMap<Long, Rec> records, long lastGoodPos, long fileSize, boolean legacy) {
        boolean torn() {
            return lastGoodPos < fileSize;
        }
    }

    /** {@code "FLG2"}: versioned Raft log segment with per-record CRC32C (#207). */
    static final int LOG_MAGIC = 0x464C4732;
    static final int LOG_VERSION = 1;
    static final int LOG_FILE_HEADER_BYTES = 8;
    static final int RECORD_HEADER_BYTES = 20;
    static final int RECORD_CRC_BYTES = 4;

    /**
     * True when {@code magic} is a near-miss on {@link #LOG_MAGIC} (bit flips) or a four-byte
     * printable ASCII format tag other than {@code FLG2}. Such headers are never treated as legacy.
     */
    static boolean looksLikeDamagedOrUnknownLogMagic(int magic) {
        if (magic == LOG_MAGIC) {
            return false;
        }
        int dist = Integer.bitCount(magic ^ LOG_MAGIC);
        if (dist > 0 && dist <= 8) {
            return true;
        }
        for (int shift = 24; shift >= 0; shift -= 8) {
            int b = (magic >>> shift) & 0xff;
            if (b < 0x20 || b > 0x7e) {
                return false;
            }
        }
        return true;
    }

    /**
     * True when the first eight bytes are a recognized pre-{@code FLG2} record header: not a
     * damaged/unknown format tag, and the putative Raft index is in a sane positive range.
     */
    static boolean isRecognizedLegacyFileHeader(int magic, int versionWord) {
        if (looksLikeDamagedOrUnknownLogMagic(magic)) {
            return false;
        }
        long putativeIndex = ((long) magic << 32) | (versionWord & 0xffffffffL);
        return putativeIndex >= 1L && putativeIndex <= 1_000_000_000_000L;
    }

    /** CRC32C of {@code index|term|len|data} (the bytes preceding the trailing CRC word). */
    static int recordCrc(long index, long term, byte[] data) {
        var buf = ByteBuffer.allocate(RECORD_HEADER_BYTES + data.length);
        buf.putLong(index);
        buf.putLong(term);
        buf.putInt(data.length);
        buf.put(data);
        var crc = new java.util.zip.CRC32C();
        crc.update(buf.array(), 0, buf.capacity());
        return (int) crc.getValue();
    }

    static void writeLogFileHeader(FileChannel ch) throws IOException {
        var h = ByteBuffer.allocate(LOG_FILE_HEADER_BYTES);
        h.putInt(LOG_MAGIC);
        h.putInt(LOG_VERSION);
        h.flip();
        while (h.hasRemaining()) {
            ch.write(h);
        }
    }

    static void writeRecordV2(FileChannel ch, long index, long term, byte[] data) throws IOException {
        int crc = recordCrc(index, term, data);
        var buf = ByteBuffer.allocate(RECORD_HEADER_BYTES + data.length + RECORD_CRC_BYTES);
        buf.putLong(index);
        buf.putLong(term);
        buf.putInt(data.length);
        buf.put(data);
        buf.putInt(crc);
        buf.flip();
        while (buf.hasRemaining()) {
            ch.write(buf);
        }
    }

    /** Ensures a brand-new empty segment starts with the {@code FLG2} header before the first record. */
    private static void ensureLogFileHeader(FileChannel channel) throws IOException {
        if (channel.size() == 0) {
            writeLogFileHeader(channel);
        }
    }

    /**
     * Fills {@code buf} by looping {@link java.nio.channels.ReadableByteChannel#read}. Returns the
     * number of bytes read, or a short count when EOF is reached before the buffer is full (0 when
     * EOF is hit with nothing read yet). A short mid-file {@code read} is retried until full or EOF.
     */
    static int readFullyOrEof(java.nio.channels.ReadableByteChannel in, ByteBuffer buf)
            throws IOException {
        int total = 0;
        while (buf.hasRemaining()) {
            int n = in.read(buf);
            if (n < 0) {
                return total;
            }
            total += n;
        }
        return total;
    }

    static Scan scanRecords(Path path) throws IOException {
        var out = new java.util.TreeMap<Long, Rec>();
        if (!Files.exists(path) || Files.size(path) == 0) {
            return new Scan(out, 0L, 0L, false);
        }
        long fileSize = Files.size(path);
        try (var in = FileChannel.open(path, StandardOpenOption.READ)) {
            return scanRecords(in, fileSize, path);
        }
    }

    /**
     * Scans {@code in} as a Raft log of {@code fileSize} bytes. Package-visible so tests can inject
     * a short-read {@link java.nio.channels.ReadableByteChannel} wrapper. Detects {@code FLG2}
     * (CRC32C records) vs recognized legacy pre-#207 framing from the first eight bytes. A damaged
     * or unrecognized header fails closed (no truncate, no upgrade rewrite).
     */
    static Scan scanRecords(java.nio.channels.ReadableByteChannel in, long fileSize, Path path)
            throws IOException {
        var out = new java.util.TreeMap<Long, Rec>();
        if (fileSize == 0) {
            return new Scan(out, 0L, 0L, false);
        }
        var peek = ByteBuffer.allocate(LOG_FILE_HEADER_BYTES);
        int peekGot = readFullyOrEof(in, peek);
        if (peekGot < LOG_FILE_HEADER_BYTES) {
            // Fewer than 8 bytes: cannot be a FLG2 header; legacy torn stub.
            return new Scan(out, 0L, fileSize, true);
        }
        peek.flip();
        int magic = peek.getInt(0);
        int version = peek.getInt(4);
        boolean v2;
        boolean legacy;
        long pos;
        long lastGood;
        ByteBuffer pendingHeader = null;
        if (magic == LOG_MAGIC) {
            if (version != LOG_VERSION) {
                throw new IOException("unsupported log format version " + version + " in "
                        + path.getFileName() + "; refusing to start");
            }
            v2 = true;
            legacy = false;
            pos = LOG_FILE_HEADER_BYTES;
            lastGood = LOG_FILE_HEADER_BYTES;
        } else if (!isRecognizedLegacyFileHeader(magic, version)) {
            // Damaged FLG2 (e.g. flipped magic bits) or any unrecognized header: fail closed.
            // Never fall through to the destructive legacy truncate/upgrade path.
            throw new IOException("unrecognized or damaged log file header in "
                    + path.getFileName() + "; refusing to start");
        } else {
            v2 = false;
            legacy = true;
            pos = 0L;
            lastGood = 0L;
            pendingHeader = ByteBuffer.allocate(RECORD_HEADER_BYTES);
            peek.position(0);
            pendingHeader.put(peek);
        }
        var header = ByteBuffer.allocate(RECORD_HEADER_BYTES);
        var crcBuf = ByteBuffer.allocate(RECORD_CRC_BYTES);
        while (pos < fileSize) {
            long recordStart = pos;
            header.clear();
            if (pendingHeader != null) {
                pendingHeader.flip();
                header.put(pendingHeader);
                pendingHeader = null;
                int need = RECORD_HEADER_BYTES - header.position();
                if (need > 0) {
                    var rest = header.slice();
                    int got = readFullyOrEof(in, rest);
                    if (got < need) {
                        break; // torn before complete header
                    }
                    header.position(RECORD_HEADER_BYTES);
                }
            } else {
                int got = readFullyOrEof(in, header);
                if (got < RECORD_HEADER_BYTES) {
                    break; // real EOF before a complete header: torn tail
                }
            }
            pos += RECORD_HEADER_BYTES;
            header.flip();
            long index = header.getLong();
            long term = header.getLong();
            int len = header.getInt();
            if (len < 0) {
                // A crash leaves an incomplete header, not a complete one with a negative length.
                throw new IOException("corrupt log record framing in " + path.getFileName()
                        + " at offset " + recordStart + ": negative length " + len
                        + "; refusing to start");
            }
            long remaining = fileSize - pos;
            long needBody = len + (v2 ? RECORD_CRC_BYTES : 0);
            if (needBody > remaining) {
                break; // body/CRC would run past EOF: torn tail
            }
            var data = new byte[len];
            var body = ByteBuffer.wrap(data);
            int bodyGot = readFullyOrEof(in, body);
            if (bodyGot < len) {
                break; // real EOF mid-body: torn tail
            }
            pos += len;
            if (v2) {
                crcBuf.clear();
                int crcGot = readFullyOrEof(in, crcBuf);
                if (crcGot < RECORD_CRC_BYTES) {
                    break; // torn before CRC word
                }
                pos += RECORD_CRC_BYTES;
                crcBuf.flip();
                int stored = crcBuf.getInt();
                int expect = recordCrc(index, term, data);
                if (stored != expect) {
                    if (pos >= fileSize) {
                        // Last framed record fails CRC: corrupt/torn tail — drop it.
                        break;
                    }
                    throw new IOException("corrupt log record CRC32C in " + path.getFileName()
                            + " at offset " + recordStart + "; refusing to start");
                }
            }
            out.put(index, new Rec(term, data));
            lastGood = pos;
        }
        return new Scan(out, lastGood, fileSize, legacy);
    }

    /** Scans {@code path} and, when the tail is torn, truncates it and fsyncs the file and directory. */
    private java.util.TreeMap<Long, Rec> readRecords(Path path) throws IOException {
        return readRecords(path, false);
    }

    /**
     * @param upgradeLegacy when true and the file is pre-{@code FLG2}, rewrite it with a header and
     *                      per-record CRC32C (active {@code .log} on rolling open; sealed {@code .seg}
     *                      stay readable as legacy until prefix-truncated).
     */
    private java.util.TreeMap<Long, Rec> readRecords(Path path, boolean upgradeLegacy) throws IOException {
        var scan = scanRecords(path);
        if (scan.torn()) {
            truncateTornTail(path, null, scan.lastGoodPos, scan.fileSize);
        }
        if (upgradeLegacy && scan.legacy() && Files.exists(path) && Files.size(path) > 0) {
            writeV2Segment(path, scan.records);
        }
        return scan.records;
    }

    /** Crash-safe rewrite of {@code target} as an {@code FLG2} segment (header + CRC records). */
    private void writeV2Segment(Path target, NavigableMap<Long, Rec> keep) throws IOException {
        var tmp = target.resolveSibling(target.getFileName().toString() + ".upgrade");
        Files.deleteIfExists(tmp);
        try (var out = FileChannel.open(tmp, java.util.Set.of(StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, java.nio.file.LinkOption.NOFOLLOW_LINKS), OWNER_ONLY)) {
            writeLogFileHeader(out);
            for (var entry : keep.entrySet()) {
                writeRecordV2(out, entry.getKey(), entry.getValue().term, entry.getValue().data);
            }
            out.force(true);
        }
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        try (var dir = FileChannel.open(root, StandardOpenOption.READ)) {
            dir.force(true);
        }
    }

    /**
     * Truncates a torn tail to {@code lastGoodPos}. Pass an open writable {@code channel} when the
     * caller already holds one for {@code path}; otherwise a channel is opened for the truncate.
     */
    private void truncateTornTail(Path path, FileChannel channel, long lastGoodPos, long fileSize)
            throws IOException {
        if (channel != null) {
            channel.truncate(lastGoodPos);
            channel.force(true);
        } else {
            try (var ch = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                ch.truncate(lastGoodPos);
                ch.force(true);
            }
        }
        try (var dir = FileChannel.open(root, StandardOpenOption.READ)) {
            dir.force(true);
        }
        LOG.log(System.Logger.Level.WARNING, "truncated torn Raft log tail of {0}: {1} -> {2} bytes",
                path.getFileName(), fileSize, lastGoodPos);
    }

    /**
     * Non-rolling segments are rewritten on every truncate, so indexes in one file must be a
     * contiguous run. A gap is mid-log corruption: refuse to start rather than silently drop a
     * committed suffix. Rolling mode uses {@link #loadRolling}'s keep-suffix rule instead, because
     * a snapshot prefix truncate can leave a gap in the active file on purpose.
     */
    private static void refuseNonContiguous(Path path, NavigableMap<Long, Rec> loaded) throws IOException {
        if (loaded.size() < 2) {
            return;
        }
        Long prev = null;
        for (long index : loaded.keySet()) {
            if (prev != null && index != prev + 1) {
                throw new IOException("corrupt log record framing in " + path.getFileName()
                        + ": index " + index + " after " + prev + "; refusing to start");
            }
            prev = index;
        }
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
            Scan pendingTorn = null;
            boolean upgradeLegacy = false;
            if (rolling()) {
                loadRolling(group, loaded);
            } else if (Files.exists(path) && Files.size(path) > 0) {
                pendingTorn = scanRecords(path);
                loaded.putAll(pendingTorn.records);
                refuseNonContiguous(path, loaded);
                upgradeLegacy = pendingTorn.legacy();
            }
            var channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ,
                    StandardOpenOption.WRITE);
            channels.put(group, channel);
            logs.put(group, loaded);
            if (pendingTorn != null && pendingTorn.torn()) {
                truncateTornTail(path, channel, pendingTorn.lastGoodPos, pendingTorn.fileSize);
            }
            if (upgradeLegacy) {
                // One-time rewrite to FLG2 + per-record CRC32C (#207), like the vote-file upgrade.
                // rewrite() installs a fresh map in logs; return that live map, not the detached
                // pre-rewrite instance, so the first append/read after upgrade stays coherent.
                rewrite(group, loaded);
            }
            return logs.get(group);
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
            ensureLogFileHeader(channel);
            channel.position(channel.size());
            writeRecordV2(channel, index, term, copy);
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
                writeLogFileHeader(out);
                for (var entry : keep.entrySet()) {
                    var rec = entry.getValue();
                    writeRecordV2(out, entry.getKey(), rec.term, rec.data);
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
     * ending at the newest entry; sealed files left with nothing in that run are deleted. The kept suffix must start at
     * {@code snapIndex + 1} (exact continuity after the durable snapshot) or at/below {@code snapIndex} (covered by the
     * snapshot). A suffix that starts above {@code snapIndex + 1} refuses to load: the dropped prefix is not covered.
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
        var active = readRecords(path(group), true);
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
            refuseUncoveredRollingSuffix(group, loaded);
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

    /**
     * After the rolling keep-suffix rule, the first live index must equal {@code snapIndex + 1} or be
     * ≤ {@code snapIndex} (snapshot covers it). A hole that leaves the live suffix starting above
     * {@code snapIndex + 1} would drop an uncovered prefix: refuse to start.
     */
    private void refuseUncoveredRollingSuffix(GroupId group, NavigableMap<Long, Rec> loaded)
            throws IOException {
        if (loaded.isEmpty()) {
            return;
        }
        long snapIndex = durableSnapIndex(group);
        long firstLive = loaded.firstKey();
        if (firstLive > snapIndex + 1) {
            throw new IOException("corrupt log record framing in " + path(group).getFileName()
                    + ": live suffix starts at index " + firstLive + " but snapshot covers through "
                    + snapIndex + " (expected first live index " + (snapIndex + 1)
                    + "); refusing to start");
        }
    }

    /**
     * {@code lastIncludedIndex} of the durable snapshot for {@code group}, or {@code 0} when there is
     * no snapshot file (matches {@link RaftGroup}'s default {@code snapIndex}). Only the snap header
     * is read; full image validation remains {@link #loadSnapshot}'s job on group open.
     */
    private long durableSnapIndex(GroupId group) throws IOException {
        var path = snapPath(group);
        if (!Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return 0L;
        }
        try (var in = FileChannel.open(path, StandardOpenOption.READ)) {
            var buf = ByteBuffer.allocate(SNAP_HEADER_BYTES);
            int got = readFullyOrEof(in, buf);
            if (got < SNAP_HEADER_BYTES) {
                throw new IOException("snapshot " + path + " is truncated; refusing to start");
            }
            buf.flip();
            if (buf.getInt() != SNAP_MAGIC) {
                throw new IOException("snapshot " + path + " has a bad magic; refusing to start");
            }
            if (buf.getInt() != SNAP_VERSION) {
                throw new IOException("snapshot " + path + " has unsupported version " + buf.getInt(4)
                        + "; refusing to start");
            }
            long index = buf.getLong();
            if (index < 0) {
                throw new IOException("snapshot " + path + " has a negative index; refusing to start");
            }
            return index;
        }
    }

    /** Rolling mode, under the lock: seal the active file once it reached {@link #rollBytes}. */
    private void maybeRoll(GroupId group, FileChannel channel, NavigableMap<Long, Rec> log, long lastIndex)
            throws IOException {
        if (!rolling() || channel.size() < rollBytes) {
            return;
        }
        var tracked = sealed.get(group);
        if (tracked == null) { // no computeIfAbsent (SourceGuardTest)
            tracked = new java.util.TreeMap<>();
            sealed.put(group, tracked);
        }
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
            // Reject a stale save before it replaces a newer durable image (#288 Codex P1).
            // installFromLeader / another recordSnapshot may have published a higher index while we were off the group lock.
            if (Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                try (var existing = FileChannel.open(target, StandardOpenOption.READ,
                        java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    var hdr = ByteBuffer.allocate(16);
                    while (hdr.hasRemaining()) {
                        if (existing.read(hdr) < 0) break;
                    }
                    if (hdr.position() >= 16) {
                        hdr.flip();
                        int magic = hdr.getInt();
                        int ver = hdr.getInt();
                        long existingIndex = hdr.getLong();
                        if (magic == SNAP_MAGIC && ver == SNAP_VERSION
                                && existingIndex > snapshot.lastIncludedIndex()) {
                            throw new IOException("stale snapshot save: index " + snapshot.lastIncludedIndex()
                                    + " would replace durable index " + existingIndex);
                        }
                    }
                }
            }
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
