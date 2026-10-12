package sjws_nettask.messaging.ledger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Append-only segmented log for one partition. Not thread-safe: owned by a single writer
 * ({@link LedgerWriter}); reads from other threads must go through the writer's published snapshot
 * bounds (never past {@link LedgerSnapshot#nextOffset}).
 */
public final class SegmentedLog implements AutoCloseable {

    public static final long DEFAULT_SEGMENT_BYTES = 1L << 30;
    private static final Pattern LOG = Pattern.compile("(\\d{20})\\.log");

    private final Path dir;
    private final long segmentBytes;
    private final List<Segment> segments = new ArrayList<>();
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
        long o = startOffset();
        while (o < nextOffset()) {
            var b = read(o);
            out.add(b);
            o = b.nextOffset();
        }
        return out;
    }

    @Override public void close() throws IOException {
        IOException first = null;
        for (var s : segments) try { s.close(); } catch (IOException e) { if (first == null) first = e; }
        if (first != null) throw first;
    }
}
