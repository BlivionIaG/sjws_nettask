package sjws_nettask.messaging.ledger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

/**
 * One {@code <baseOffset:020d>.log} file plus its sparse {@code .index} (relative offset → position,
 * one entry per {@link #INDEX_INTERVAL} bytes). The index is rebuilt by scanning on open, so it never
 * has to be fsynced; it is persisted when a segment is sealed so sealed segments open without a scan.
 */
final class Segment implements AutoCloseable {

    static final int INDEX_INTERVAL = 4096;

    final long baseOffset;
    final Path logPath;
    final Path indexPath;
    private final FileChannel ch;
    private long size;
    private long nextOffset;
    private long[] idxOff = new long[16];
    private long[] idxPos = new long[16];
    private int idxLen;
    private long lastIndexedPos = -INDEX_INTERVAL;

    private Segment(Path dir, long baseOffset) throws IOException {
        this.baseOffset = baseOffset;
        this.logPath = dir.resolve(name(baseOffset, ".log"));
        this.indexPath = dir.resolve(name(baseOffset, ".index"));
        this.ch = FileChannel.open(logPath, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        this.nextOffset = baseOffset;
    }

    static String name(long base, String ext) { return String.format("%020d%s", base, ext); }

    static Segment create(Path dir, long baseOffset) throws IOException {
        var s = new Segment(dir, baseOffset);
        s.ch.truncate(0);
        Files.deleteIfExists(s.indexPath);
        return s;
    }

    /**
     * Opens an existing segment. {@code trusted} sealed segments with an index use it; otherwise the
     * file is scanned and truncated at the first torn or corrupt frame.
     * @return the number of bytes truncated
     */
    static Segment open(Path dir, long baseOffset, boolean sealed, long[] truncated) throws IOException {
        var s = new Segment(dir, baseOffset);
        if (sealed && s.loadIndex()) return s;
        truncated[0] += s.recover();
        return s;
    }

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
    private long recover() throws IOException {
        long fileSize = ch.size(), pos = 0;
        var lenBuf = ByteBuffer.allocate(4);
        long expect = baseOffset;
        while (pos + RecordBatch.HEADER_BYTES <= fileSize) {
            lenBuf.clear();
            if (ch.read(lenBuf, pos) != 4) break;
            int len = lenBuf.getInt(0);
            if (len < RecordBatch.HEADER_BYTES - 4 || len > RecordBatch.MAX_FRAME_BYTES || pos + 4 + len > fileSize) break;
            var frame = ByteBuffer.allocate(4 + len);
            while (frame.hasRemaining() && ch.read(frame, pos + frame.position()) > 0) { }
            frame.flip();
            RecordBatch rb;
            try { rb = RecordBatch.decode(frame); } catch (RecordBatch.CorruptBatchException e) { break; }
            if (rb.baseOffset() != expect) break;
            maybeIndex(rb.baseOffset(), pos);
            expect = rb.nextOffset();
            pos += 4 + len;
        }
        size = pos;
        nextOffset = expect;
        if (pos < fileSize) ch.truncate(pos);
        return fileSize - pos;
    }

    private void maybeIndex(long offset, long pos) {
        if (pos - lastIndexedPos >= INDEX_INTERVAL) addIndex(offset, pos);
    }

    private void addIndex(long offset, long pos) {
        if (idxLen == idxOff.length) {
            idxOff = Arrays.copyOf(idxOff, idxLen * 2);
            idxPos = Arrays.copyOf(idxPos, idxLen * 2);
        }
        idxOff[idxLen] = offset;
        idxPos[idxLen++] = pos;
        lastIndexedPos = pos;
    }

    long size() { return size; }
    long nextOffset() { return nextOffset; }
    int indexEntries() { return idxLen; }

    /** Appends already-encoded frames (whose offsets the caller assigned contiguously). */
    void append(ByteBuffer frames, long firstOffset, long[] frameOffsets, int[] frameLens, int n, long newNext) throws IOException {
        long pos = size;
        for (int i = 0; i < n; i++) { maybeIndex(frameOffsets[i], pos); pos += frameLens[i]; }
        long at = size;
        while (frames.hasRemaining()) at += ch.write(frames, at);
        size = at;
        nextOffset = newNext;
    }

    void force() throws IOException { ch.force(false); }

    /** Reads the batch containing {@code offset}, or null if it is not in this segment. */
    RecordBatch read(long offset) throws IOException {
        if (offset < baseOffset || offset >= nextOffset) return null;
        int i = Arrays.binarySearch(idxOff, 0, idxLen, offset);
        long pos = i >= 0 ? idxPos[i] : (i == -1 ? 0 : idxPos[-i - 2]);
        var lenBuf = ByteBuffer.allocate(4);
        while (pos < size) {
            lenBuf.clear();
            ch.read(lenBuf, pos);
            int len = lenBuf.getInt(0);
            var frame = ByteBuffer.allocate(4 + len);
            while (frame.hasRemaining() && ch.read(frame, pos + frame.position()) > 0) { }
            try {
                var rb = RecordBatch.decode(frame.flip());
                if (offset < rb.nextOffset()) return rb;
            } catch (RecordBatch.CorruptBatchException e) {
                throw new IOException("corrupt committed batch at " + logPath + ":" + pos, e);
            }
            pos += 4 + len;
        }
        return null;
    }

    /** Persists the index (sealing). Written to a temp file and renamed atomically. */
    void seal() throws IOException {
        force();
        var b = ByteBuffer.allocate(16 + 16 * idxLen).putLong(size).putLong(nextOffset);
        for (int i = 0; i < idxLen; i++) b.putLong(idxOff[i]).putLong(idxPos[i]);
        var tmp = indexPath.resolveSibling(indexPath.getFileName() + ".tmp");
        Files.write(tmp, b.array());
        Files.move(tmp, indexPath, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    @Override public void close() throws IOException { ch.close(); }
}
