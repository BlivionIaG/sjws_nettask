package sjws_nettask.messaging.ledger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.function.LongPredicate;

/**
 * One {@code <baseOffset:020d>.log} file plus its sparse {@code .index} (relative offset → position,
 * one entry per {@link #INDEX_INTERVAL} bytes). The index is rebuilt by scanning on open, so it never
 * has to be fsynced; it is persisted when a segment is sealed so sealed segments open without a scan.
 *
 * <p>Thread safety: one writer appends (active segment only); any number of readers may call {@link #read}
 * concurrently. The index arrays are published before {@code idxLen} (volatile), and {@code size}/{@code nextOffset}
 * are volatile, so a reader only ever sees fully written frames.
 *
 * <p>A <em>compacted</em> segment (marker file {@code <base>.compacted} holding the original next offset) keeps only some
 * batches, so its offsets have gaps; scans then accept increasing (not contiguous) base offsets.
 */
final class Segment implements AutoCloseable {

    static final int INDEX_INTERVAL = 4096;

    final long baseOffset;
    final Path logPath;
    final Path indexPath;
    final Path markerPath;
    private final FileChannel ch;
    private volatile long size;
    private volatile long nextOffset;
    private volatile long[] idxOff = new long[16];
    private volatile long[] idxPos = new long[16];
    private volatile int idxLen;
    private long lastIndexedPos = -INDEX_INTERVAL;
    private volatile boolean compacted;

    private Segment(Path dir, long baseOffset) throws IOException {
        this.baseOffset = baseOffset;
        this.logPath = dir.resolve(name(baseOffset, ".log"));
        this.indexPath = dir.resolve(name(baseOffset, ".index"));
        this.markerPath = dir.resolve(name(baseOffset, ".compacted"));
        this.ch = FileChannel.open(logPath, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        this.nextOffset = baseOffset;
    }

    static String name(long base, String ext) { return String.format("%020d%s", base, ext); }

    static Segment create(Path dir, long baseOffset) throws IOException {
        var s = new Segment(dir, baseOffset);
        s.ch.truncate(0);
        Files.deleteIfExists(s.indexPath);
        Files.deleteIfExists(s.markerPath);
        return s;
    }

    /**
     * Opens an existing segment. Sealed segments with a valid index use it; otherwise the file is scanned
     * and truncated at the first torn or corrupt frame. Finishes or rolls back an interrupted compaction.
     */
    static Segment open(Path dir, long baseOffset, boolean sealed, long[] truncated) throws IOException {
        var cmp = dir.resolve(name(baseOffset, ".cmp"));
        var marker = dir.resolve(name(baseOffset, ".compacted"));
        if (Files.exists(cmp)) {
            if (Files.exists(marker)) { // marker is written only after .cmp is complete and fsynced: roll forward
                Files.move(cmp, dir.resolve(name(baseOffset, ".log")), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                Files.deleteIfExists(dir.resolve(name(baseOffset, ".index")));
            } else {
                Files.delete(cmp); // roll back
            }
        }
        var s = new Segment(dir, baseOffset);
        long markerNext = -1;
        if (Files.exists(marker)) {
            markerNext = ByteBuffer.wrap(Files.readAllBytes(marker)).getLong();
            s.compacted = true;
        }
        if (sealed && s.loadIndex()) return s;
        truncated[0] += s.recover(markerNext);
        return s;
    }

    boolean compacted() { return compacted; }

    private boolean loadIndex() throws IOException {
        if (!Files.exists(indexPath)) return false;
        var b = ByteBuffer.wrap(Files.readAllBytes(indexPath));
        if (b.remaining() < 16 || (b.remaining() - 16) % 16 != 0) return false;
        long fileSize = b.getLong(), next = b.getLong();
        if (fileSize != ch.size()) return false;
        while (b.hasRemaining()) addIndex(b.getLong(), b.getLong());
        size = fileSize;
        nextOffset = next;
        return true;
    }

    /** Scans from 0, validating each frame; truncates the tail at the first bad one. */
    private long recover(long markerNext) throws IOException {
        long fileSize = ch.size(), pos = 0;
        long expect = baseOffset;
        boolean gaps = markerNext >= 0;
        // Chunked read: one pread per few MiB instead of two per frame.
        var buf = ByteBuffer.allocate(4 << 20);
        long bufStart = 0;
        buf.limit(0);
        while (pos + RecordBatch.HEADER_BYTES <= fileSize) {
            int rel = (int) (pos - bufStart);
            if (buf.limit() - rel < 4) { bufStart = pos; rel = 0; if (!fill(buf, pos, 4, fileSize)) break; }
            int len = buf.getInt(rel);
            if (len < RecordBatch.HEADER_BYTES - 4 || len > RecordBatch.MAX_FRAME_BYTES || pos + 4 + len > fileSize) break;
            if (buf.limit() - rel < 4 + len) {
                if (buf.capacity() < 4 + len) buf = ByteBuffer.allocate(4 + len);
                bufStart = pos; rel = 0;
                if (!fill(buf, pos, 4 + len, fileSize)) break;
            }
            RecordBatch rb;
            try { rb = RecordBatch.decode(buf.duplicate().position(rel).limit(rel + 4 + len).slice()); }
            catch (RecordBatch.CorruptBatchException e) { break; }
            if (gaps ? rb.baseOffset() < expect : rb.baseOffset() != expect) break;
            maybeIndex(rb.baseOffset(), pos);
            expect = rb.nextOffset();
            pos += 4 + len;
        }
        size = pos;
        nextOffset = gaps ? Math.max(expect, markerNext) : expect;
        if (pos < fileSize) ch.truncate(pos);
        return fileSize - pos;
    }

    /** Fills {@code buf} from file position {@code at}; true if at least {@code need} bytes were read. */
    private boolean fill(ByteBuffer buf, long at, int need, long fileSize) throws IOException {
        buf.clear();
        buf.limit((int) Math.min(buf.capacity(), fileSize - at));
        while (buf.hasRemaining()) if (ch.read(buf, at + buf.position()) <= 0) break;
        buf.flip();
        return buf.limit() >= need;
    }

    private void maybeIndex(long offset, long pos) {
        if (pos - lastIndexedPos >= INDEX_INTERVAL) addIndex(offset, pos);
    }

    private void addIndex(long offset, long pos) {
        int n = idxLen;
        long[] o = idxOff, p = idxPos;
        if (n == o.length) {
            o = Arrays.copyOf(o, n * 2);
            p = Arrays.copyOf(p, n * 2);
        }
        o[n] = offset;
        p[n] = pos;
        idxOff = o;
        idxPos = p;
        idxLen = n + 1; // publish last
        lastIndexedPos = pos;
    }

    long size() { return size; }
    long nextOffset() { return nextOffset; }
    int indexEntries() { return idxLen; }
    long lastModifiedMs() throws IOException { return Files.getLastModifiedTime(logPath).toMillis(); }

    /** Appends already-encoded frames (whose offsets the caller assigned contiguously). */
    void append(ByteBuffer frames, long firstOffset, long[] frameOffsets, int[] frameLens, int n, long newNext) throws IOException {
        long pos = size;
        long at = pos;
        while (frames.hasRemaining()) at += ch.write(frames, at);
        for (int i = 0; i < n; i++) { maybeIndex(frameOffsets[i], pos); pos += frameLens[i]; }
        nextOffset = newNext;
        size = at; // publish last
    }

    void force() throws IOException { ch.force(false); }

    /** Reads the batch containing {@code offset}, or null if it is not in this segment (or compacted away). */
    RecordBatch read(long offset) throws IOException {
        long end = size;
        if (offset < baseOffset || offset >= nextOffset) return null;
        int n = idxLen;
        long[] o = idxOff, p = idxPos;
        int i = Arrays.binarySearch(o, 0, n, offset);
        long pos = i >= 0 ? p[i] : (i == -1 ? 0 : p[-i - 2]);
        var lenBuf = ByteBuffer.allocate(4);
        while (pos < end) {
            lenBuf.clear();
            ch.read(lenBuf, pos);
            int len = lenBuf.getInt(0);
            var frame = ByteBuffer.allocate(4 + len);
            while (frame.hasRemaining() && ch.read(frame, pos + frame.position()) > 0) { }
            try {
                var rb = RecordBatch.decode(frame.flip());
                if (offset < rb.nextOffset()) return rb.baseOffset() <= offset ? rb : null;
            } catch (RecordBatch.CorruptBatchException e) {
                throw new IOException("corrupt committed batch at " + logPath + ":" + pos, e);
            }
            pos += 4 + len;
        }
        return null;
    }

    /** Calls {@code sink} for every batch in file order (replay, compaction). */
    void scan(java.util.function.Consumer<RecordBatch> sink) throws IOException { scan(Long.MIN_VALUE, sink); }

    /** Batches ending after {@code fromOffset}, starting at the sparse-index floor; chunked reads. */
    void scan(long fromOffset, java.util.function.Consumer<RecordBatch> sink) throws IOException {
        long end = size, pos = 0;
        int n = idxLen;
        if (fromOffset > baseOffset && n > 0) {
            int i = Arrays.binarySearch(idxOff, 0, n, fromOffset);
            pos = i >= 0 ? idxPos[i] : (i == -1 ? 0 : idxPos[-i - 2]);
        }
        var buf = ByteBuffer.allocate(4 << 20);
        long bufStart = pos;
        buf.limit(0);
        while (pos < end) {
            int rel = (int) (pos - bufStart);
            if (buf.limit() - rel < 4) { bufStart = pos; rel = 0; fill(buf, pos, 4, end); }
            int len = buf.getInt(rel);
            if (buf.limit() - rel < 4 + len) {
                if (buf.capacity() < 4 + len) buf = ByteBuffer.allocate(4 + len);
                bufStart = pos; rel = 0;
                fill(buf, pos, 4 + len, end);
            }
            RecordBatch rb;
            try { rb = RecordBatch.decode(buf.duplicate().position(rel).limit(rel + 4 + len).slice()); }
            catch (RecordBatch.CorruptBatchException | RuntimeException e) { throw new IOException("corrupt batch at " + logPath + ":" + pos, e); }
            if (rb.nextOffset() > fromOffset) sink.accept(rb);
            pos += 4 + len;
        }
    }

    /**
     * Writes {@code <base>.cmp} with only the batches {@code keep} accepts (by base offset), fsyncs it, then the marker.
     * The caller then calls {@link SegmentedLog#commitCompaction}. Safe to run while readers use this (sealed) segment.
     * @return bytes of the compacted file
     */
    long writeCompacted(LongPredicate keep) throws IOException {
        var cmp = logPath.resolveSibling(name(baseOffset, ".cmp"));
        long written = 0;
        try (var out = FileChannel.open(cmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            var err = new IOException[1];
            long[] w = {0};
            scan(rb -> {
                if (err[0] != null || !keep.test(rb.baseOffset())) return;
                try { var b = rb.encode(); while (b.hasRemaining()) w[0] += out.write(b, w[0]); }
                catch (IOException e) { err[0] = e; }
            });
            if (err[0] != null) throw err[0];
            out.force(true);
            written = w[0];
        }
        var tmp = markerPath.resolveSibling(markerPath.getFileName() + ".tmp");
        try (var m = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            m.write(ByteBuffer.allocate(8).putLong(0, nextOffset));
            m.force(true);
        }
        Files.move(tmp, markerPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        return written;
    }

    /** Persists the index (sealing). Written to a temp file and renamed atomically. */
    void seal() throws IOException {
        force();
        int n = idxLen;
        long[] o = idxOff, p = idxPos;
        var b = ByteBuffer.allocate(16 + 16 * n).putLong(size).putLong(nextOffset);
        for (int i = 0; i < n; i++) b.putLong(o[i]).putLong(p[i]);
        var tmp = indexPath.resolveSibling(indexPath.getFileName() + ".tmp");
        Files.write(tmp, b.array());
        Files.move(tmp, indexPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Deletes the files of this (closed) segment. */
    void deleteFiles() throws IOException {
        Files.deleteIfExists(logPath);
        Files.deleteIfExists(indexPath);
        Files.deleteIfExists(markerPath);
    }

    @Override public void close() throws IOException { ch.close(); }
}
