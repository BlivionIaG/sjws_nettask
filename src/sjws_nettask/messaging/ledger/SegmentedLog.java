package sjws_nettask.messaging.ledger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.CopyOnWriteArrayList;
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
     * segment first when the active one would exceed the segment size.
     */
    void appendFrames(ByteBuffer frames, long[] frameOffsets, int[] frameLens, int n, long newNext) throws IOException {
        var active = segments.getLast();
        if (active.size() > 0 && active.size() + frames.remaining() > segmentBytes) {
            active.seal();
            active = Segment.create(dir, active.nextOffset());
            segments.add(active);
            this.active = active;
        }
        active.append(frames, frameOffsets[0], frameOffsets, frameLens, n, newNext);
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
     * Phase 1 of compaction (no lock needed, readers may continue): writes a copy of sealed segment {@code base} keeping only
     * batches whose base offset {@code keep} accepts, fsynced, plus its marker. Returns the new size in bytes.
     */
    public long prepareCompaction(long base, java.util.function.LongPredicate keep) throws IOException {
        return sealedSegment(base).writeCompacted(keep);
    }

    /** Phase 2 (callers exclude readers of this segment): swaps in the compacted file and persists its index. */
    public void commitCompaction(long base) throws IOException {
        var old = sealedSegment(base);
        int i = segments.indexOf(old);
        old.close();
        var cmp = dir.resolve(Segment.name(base, ".cmp"));
        java.nio.file.Files.move(cmp, old.logPath, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        java.nio.file.Files.deleteIfExists(old.indexPath);
        var seg = Segment.open(dir, base, true, new long[1]);
        seg.seal();
        segments.set(i, seg);
    }

    @Override public void close() throws IOException {
        IOException first = null;
        for (var s : segments) try { s.close(); } catch (IOException e) { if (first == null) first = e; }
        if (first != null) throw first;
    }
}
