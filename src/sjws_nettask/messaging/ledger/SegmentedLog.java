package sjws_nettask.messaging.ledger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Append-only segmented log for one partition. One writer ({@link LedgerWriter}) appends; reads
 * ({@link #read}, {@link #scanFrom}) are safe from other threads concurrently and only see fully written batches.
 * Maintenance ({@link #deleteSegment}, compaction) touches sealed segments only and must not race with
 * readers of the same segment (callers serialize it with their reads).
 */
public final class SegmentedLog implements AutoCloseable {

    public static final long DEFAULT_SEGMENT_BYTES = 1L << 30;
    private static final Pattern LOG = Pattern.compile("(\\d{20})\\.log");

    private final Path dir;
    private final long segmentBytes;
    /** Copy-on-write: the writer rolls, maintenance deletes/compacts sealed segments, readers iterate without locks. */
    private final List<Segment> segments = new CopyOnWriteArrayList<>();
    private final long recoveredTruncatedBytes;
    /** Active segment, readable by a background syncer thread while the writer appends. */
    private volatile Segment active;
    /** Held from open to close: one process per ledger directory. */
    private DirectoryLock dirLock;
    /**
     * Bumped by {@link #replaceWith} so an in-flight {@link #prepareCompaction}/{@link #commitCompaction} pair
     * cannot close or overwrite segments that installation just swapped in (#288 Codex P1).
     */
    private final AtomicLong epoch = new AtomicLong();
    /** baseOffset → epoch observed when that compaction was prepared. */
    private final ConcurrentHashMap<Long, Long> preparedAt = new ConcurrentHashMap<>();
    /**
     * Serializes {@link #prepareCompaction}/{@link #commitCompaction} against {@link #replaceWith} so a late
     * {@code .compacted} marker from an overlapped preparation cannot attach to installed segments (#288 Codex P1).
     */
    private final java.util.concurrent.locks.ReentrantLock compactionGate = new java.util.concurrent.locks.ReentrantLock();

    private SegmentedLog(Path dir, long segmentBytes, long truncated) {
        this.dir = dir;
        this.segmentBytes = segmentBytes;
        this.recoveredTruncatedBytes = truncated;
    }

    public static SegmentedLog open(Path dir) throws IOException { return open(dir, DEFAULT_SEGMENT_BYTES); }

    /** Opens (or creates) the log in {@code dir}, recovering a torn tail. */
    public static SegmentedLog open(Path dir, long segmentBytes) throws IOException {
        if (segmentBytes < RecordBatch.HEADER_BYTES) throw new IllegalArgumentException("segmentBytes");
        Files.createDirectories(dir);
        var lock = DirectoryLock.acquire(dir); // fail fast before touching any segment
        try {
            var log = openLocked(dir, segmentBytes);
            log.dirLock = lock;
            return log;
        } catch (IOException | RuntimeException e) {
            lock.close();
            throw e;
        }
    }

    private static SegmentedLog openLocked(Path dir, long segmentBytes) throws IOException {
        var bases = new ArrayList<Long>();
        try (var s = Files.list(dir)) {
            s.forEach(p -> {
                var m = LOG.matcher(p.getFileName().toString());
                if (m.matches()) bases.add(Long.parseLong(m.group(1)));
            });
        }
        bases.sort(null);
        long[] truncated = {0};
        var segs = new ArrayList<Segment>();
        for (int i = 0; i < bases.size(); i++) {
            long base = bases.get(i);
            if (!segs.isEmpty() && segs.getLast().nextOffset() != base) {
                // A gap (e.g. a later segment created before a torn tail was cut): everything from here is suspect.
                for (int j = i; j < bases.size(); j++) {
                    truncated[0] += Files.size(dir.resolve(Segment.name(bases.get(j), ".log")));
                    Files.deleteIfExists(dir.resolve(Segment.name(bases.get(j), ".log")));
                    Files.deleteIfExists(dir.resolve(Segment.name(bases.get(j), ".index")));
                }
                break;
            }
            segs.add(Segment.open(dir, base, i < bases.size() - 1, truncated));
        }
        if (segs.isEmpty()) segs.add(Segment.create(dir, 0));
        return new SegmentedLog(dir, segmentBytes, truncated[0]).adopt(segs);
    }

    private SegmentedLog adopt(List<Segment> segs) { segments.addAll(segs); active = segs.getLast(); return this; }

    public long nextOffset() { return segments.getLast().nextOffset(); }
    public long startOffset() { return segments.getFirst().baseOffset; }
    public int segmentCount() { return segments.size(); }
    public long recoveredTruncatedBytes() { return recoveredTruncatedBytes; }
    Path dir() { return dir; }

    /**
     * Appends a group of already-encoded frames starting at {@link #nextOffset}, rolling to a new
     * segment before any frame that would make the active one exceed the segment size (#328).
     * When the whole group fits, this is one {@link Segment#append} (same hot path as before); a
     * mid-group roll walks frame-by-frame so {@link LedgerWriter} coalescing cannot change bases.
     */
    void appendFrames(ByteBuffer frames, long[] frameOffsets, int[] frameLens, int n, long newNext) throws IOException {
        var active = segments.getLast();
        long total = frames.remaining();
        // Fast path: whole group fits — identical to one-by-one and to the pre-#328 single append.
        if (active.size() + total <= segmentBytes) {
            active.append(frames, frameOffsets[0], frameOffsets, frameLens, n, newNext);
            return;
        }
        // At least one roll (or an empty active with an oversize first frame). Emit frame-by-frame so
        // the same record sequence yields the same bases regardless of how Pendings were grouped.
        int bufPos = frames.position();
        for (int i = 0; i < n; i++) {
            active = segments.getLast();
            int len = frameLens[i];
            if (active.size() > 0 && active.size() + len > segmentBytes) {
                active.seal();
                active = Segment.create(dir, active.nextOffset());
                segments.add(active);
                this.active = active;
            }
            var slice = frames.duplicate().position(bufPos).limit(bufPos + len).slice();
            long end = (i + 1 < n) ? frameOffsets[i + 1] : newNext;
            active.append(slice, frameOffsets[i], new long[] { frameOffsets[i] }, new int[] { len }, 1, end);
            bufPos += len;
        }
        frames.position(bufPos);
    }

    /** Convenience single-batch append (used by tests and tools); returns the base offset. Does not fsync. */
    public long append(List<byte[]> records, long timestampMs) throws IOException {
        long base = nextOffset();
        var b = ByteBuffer.allocate(RecordBatch.encodedSize(records));
        RecordBatch.encode(base, timestampMs, records, b);
        b.flip();
        appendFrames(b, new long[] {base}, new int[] {b.remaining()}, 1, base + records.size());
        return base;
    }

    /**
     * Fsyncs the active segment. Safe to call from one other thread concurrently with appends: a
     * segment is fsynced when sealed, before its successor becomes active, so every byte written
     * before this call started is durable when it returns.
     */
    public void force() throws IOException { active.force(); }

    /** Returns the batch containing {@code offset}, or null when out of range. */
    public RecordBatch read(long offset) throws IOException {
        for (int i = segments.size() - 1; i >= 0; i--) {
            var s = segments.get(i);
            if (offset >= s.baseOffset) return s.read(offset);
        }
        return null;
    }

    /**
     * The first batch holding an offset {@code >= offset}: the batch containing it, or after a compaction gap the next
     * retained one (possibly in a later segment). Null when none (at or past the end).
     */
    public RecordBatch readCeil(long offset) throws IOException {
        int n = segments.size();
        int i = n - 1;
        while (i > 0 && segments.get(i).baseOffset > offset) i--;
        for (; i < n; i++) {
            var s = segments.get(i);
            if (s.nextOffset() <= offset) continue;
            var b = s.read(Math.max(offset, s.baseOffset), true);
            if (b != null) return b;
        }
        return null;
    }

    /** Reads every committed batch in order (recovery, tests, tools). */
    public List<RecordBatch> readAll() throws IOException {
        var out = new ArrayList<RecordBatch>();
        scanFrom(Long.MIN_VALUE, out::add);
        return out;
    }

    /** Calls {@code sink} for every batch whose offsets reach {@code fromOffset} or later, in order. */
    public void scanFrom(long fromOffset, java.util.function.Consumer<RecordBatch> sink) throws IOException {
        for (var seg : segments) {
            if (seg.nextOffset() <= fromOffset) continue;
            seg.scan(fromOffset, sink);
        }
    }

    /** Immutable description of one segment file. */
    public record SegmentInfo(long baseOffset, long nextOffset, long bytes, boolean active, boolean compacted, long lastModifiedMs) { }

    public List<SegmentInfo> segments() throws IOException {
        var out = new ArrayList<SegmentInfo>();
        var act = active;
        for (var seg : segments) out.add(new SegmentInfo(seg.baseOffset, seg.nextOffset(), seg.size(), seg == act, seg.compacted(), seg.lastModifiedMs()));
        return out;
    }

    public long totalBytes() { long n = 0; for (var seg : segments) n += seg.size(); return n; }

    private Segment sealedSegment(long base) {
        for (var seg : segments) if (seg.baseOffset == base) {
            if (seg == active) throw new IllegalArgumentException("active segment " + base);
            return seg;
        }
        throw new IllegalArgumentException("no segment " + base);
    }

    /** Deletes the oldest sealed segment (retention). Only the first segment may be deleted, so offsets stay a prefix cut. */
    public void deleteSegment(long base) throws IOException {
        var seg = sealedSegment(base);
        if (segments.getFirst() != seg) throw new IllegalArgumentException("only the oldest segment can be deleted");
        segments.remove(seg);
        seg.close();
        seg.deleteFiles();
    }

    /**
     * Phase 1 of compaction (readers may continue; serialized against {@link #replaceWith} via {@code compactionGate}):
     * writes a copy of sealed segment {@code base} keeping only batches whose base offset {@code keep} accepts, fsynced,
     * plus its marker. Returns the new size in bytes.
     */
    public long prepareCompaction(long base, java.util.function.LongPredicate keep) throws IOException {
        compactionGate.lock();
        try {
            long at = epoch.get();
            long size = sealedSegment(base).writeCompacted(keep);
            preparedAt.put(base, at);
            return size;
        } finally {
            compactionGate.unlock();
        }
    }

    /** Phase 2 (callers exclude readers of this segment): swaps in the compacted file and persists its index. */
    public void commitCompaction(long base) throws IOException {
        compactionGate.lock();
        try {
            commitCompactionLocked(base);
        } finally {
            compactionGate.unlock();
        }
    }

    private void commitCompactionLocked(long base) throws IOException {
        var cmp = dir.resolve(Segment.name(base, ".cmp"));
        Long at = preparedAt.get(base);
        // Installation may have replaced the ledger (and deleted .cmp) between prepare and commit: do not close
        // the newly installed segment or rename a stale preparation over it (#288 Codex P1).
        if (at == null || at != epoch.get() || !Files.isRegularFile(cmp)) {
            preparedAt.remove(base);
            Files.deleteIfExists(cmp);
            return;
        }
        var old = sealedSegment(base);
        int i = segments.indexOf(old);
        old.close();
        java.nio.file.Files.move(cmp, old.logPath, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        java.nio.file.Files.deleteIfExists(old.indexPath);
        var seg = Segment.open(dir, base, true, new long[1]);
        seg.seal();
        segments.set(i, seg);
        preparedAt.remove(base);
    }

    /**
     * One segment's file for a snapshot export (#270; narrow API for the HA replication layer, Messaging Core owns the
     * format): {@code [baseOffset, nextOffset)} of {@code file}, the segment's {@code .compacted} marker value or -1.
     */
    public record SegmentFile(long baseOffset, long nextOffset, Path file, Path marker, boolean active) { }

    /** The segment files in offset order (#270 snapshot export). Names only; the caller links or reads them. */
    public List<SegmentFile> segmentFiles() {
        var out = new ArrayList<SegmentFile>();
        var act = active;
        for (var seg : segments) out.add(new SegmentFile(seg.baseOffset, seg.nextOffset(), seg.logPath, seg.markerPath, seg == act));
        return out;
    }

    /**
     * File position in the segment starting at {@code base} of the frame whose base offset is {@code offset} (or the
     * segment size when {@code offset} is its next offset); -1 when no frame starts there or no such segment exists.
     */
    public long positionOf(long base, long offset) throws IOException {
        for (var seg : segments) if (seg.baseOffset == base) return seg.positionOf(offset);
        return -1;
    }

    /**
     * #270 snapshot install (narrow lasting export/import surface for the HA replication layer, with
     * {@link #segmentFiles}, {@link #positionOf}, {@link #reload} and {@link #deleteSegment}): replaces every segment of
     * this log with the {@code <base>.log} files (and {@code <base>.compacted} markers) in {@code staged}, hard-linked in
     * (copied where the file system has no links), then reopens them, recovering and validating every frame as on open.
     * The caller guarantees that no append is in flight and excludes readers; the writer must then
     * {@link LedgerWriter#reload}. Not atomic by design: the caller keeps {@code staged} and an install marker until it
     * returns, and redoes the swap after a crash (#288 MC-N5).
     */
    public void replaceWith(Path staged) throws IOException {
        compactionGate.lock();
        try {
            replaceWithLocked(staged);
        } finally {
            compactionGate.unlock();
        }
    }

    private void replaceWithLocked(Path staged) throws IOException {
        epoch.incrementAndGet();
        preparedAt.clear();
        for (var s : segments) s.close();
        try (var list = Files.list(dir)) {
            for (var p : (Iterable<Path>) list::iterator) {
                var n = p.getFileName().toString();
                if (n.length() > 20 && n.substring(0, 20).chars().allMatch(Character::isDigit)
                        && (n.endsWith(".log") || n.endsWith(".index") || n.endsWith(".compacted") || n.endsWith(".cmp")
                                || n.endsWith(".compacted.tmp") || n.endsWith(".index.tmp"))) {
                    Files.delete(p);
                }
            }
        }
        try (var list = Files.list(staged)) {
            for (var p : (Iterable<Path>) list::iterator) {
                var n = p.getFileName().toString();
                if (!(n.endsWith(".log") || n.endsWith(".compacted")) || n.length() < 21
                        || !n.substring(0, 20).chars().allMatch(Character::isDigit)) continue;
                var target = dir.resolve(n);
                try {
                    Files.createLink(target, p);
                } catch (UnsupportedOperationException | IOException e) {
                    Files.copy(p, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        try (var d = java.nio.channels.FileChannel.open(dir, java.nio.file.StandardOpenOption.READ)) {
            d.force(true);
        } catch (IOException ignored) {
            // some file systems refuse a directory fsync; the caller's marker covers a crash here
        }
        var fresh = openLocked(dir, segmentBytes);
        segments.clear();
        segments.addAll(fresh.segments);
        active = segments.getLast();
    }

    @Override public void close() throws IOException {
        IOException first = null;
        for (var s : segments) try { s.close(); } catch (IOException e) { if (first == null) first = e; }
        var l = dirLock;
        dirLock = null;
        if (l != null) try { l.close(); } catch (IOException e) { if (first == null) first = e; }
        if (first != null) throw first;
    }
}
