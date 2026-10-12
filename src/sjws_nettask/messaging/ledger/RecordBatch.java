package sjws_nettask.messaging.ledger;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32C;

/**
 * On-disk (= wire) batch frame:
 * {@code [len:int][crc32c:int][baseOffset:long][count:int][timestampMs:long] count×([recLen:int][bytes])}.
 * {@code len} counts the bytes after itself; the CRC32C covers everything after the crc field.
 */
public record RecordBatch(long baseOffset, long timestampMs, List<byte[]> records) {

    public static final int LEN_BYTES = 4;
    public static final int HEADER_BYTES = 4 + 4 + 8 + 4 + 8;
    /** Upper bound on one frame, so a corrupt length can never trigger a huge allocation. */
    public static final int MAX_FRAME_BYTES = 64 << 20;

    public RecordBatch {
        if (records.isEmpty()) throw new IllegalArgumentException("empty batch");
        records = List.copyOf(records);
    }

    public int count() { return records.size(); }

    public long nextOffset() { return baseOffset + records.size(); }

    public static int encodedSize(List<byte[]> records) {
        long n = HEADER_BYTES;
        for (byte[] r : records) n += 4 + r.length;
        if (n > MAX_FRAME_BYTES) throw new IllegalArgumentException("batch too large: " + n);
        return (int) n;
    }

    /** Writes the frame into {@code out} (which must have {@link #encodedSize} bytes remaining). */
    public static void encode(long baseOffset, long timestampMs, List<byte[]> records, ByteBuffer out) {
        int size = encodedSize(records);
        int start = out.position();
        out.putInt(size - LEN_BYTES).putInt(0).putLong(baseOffset).putInt(records.size()).putLong(timestampMs);
        for (byte[] r : records) out.putInt(r.length).put(r);
        out.putInt(start + 4, crc(out, start + 8, start + size));
    }

    public ByteBuffer encode() {
        var b = ByteBuffer.allocate(encodedSize(records));
        encode(baseOffset, timestampMs, records, b);
        return b.flip();
    }

    static int crc(ByteBuffer b, int from, int to) {
        var c = new CRC32C();
        c.update(b.duplicate().limit(to).position(from));
        return (int) c.getValue();
    }

    /** Thrown for any frame that is truncated, oversized or fails its CRC. */
    public static final class CorruptBatchException extends Exception {
        CorruptBatchException(String m) { super(m, null, false, false); }
    }

    /** Decodes one complete frame (exactly {@code frame.remaining()} bytes). */
    public static RecordBatch decode(ByteBuffer frame) throws CorruptBatchException {
        int start = frame.position();
        if (frame.remaining() < HEADER_BYTES) throw new CorruptBatchException("short header");
        int len = frame.getInt(start);
        if (len + LEN_BYTES != frame.remaining()) throw new CorruptBatchException("length mismatch");
        if (crc(frame, start + 8, start + LEN_BYTES + len) != frame.getInt(start + 4))
            throw new CorruptBatchException("crc mismatch");
        var b = frame.duplicate().position(start + 8);
        long base = b.getLong();
        int count = b.getInt();
        long ts = b.getLong();
        if (count <= 0) throw new CorruptBatchException("bad count");
        var recs = new ArrayList<byte[]>(Math.min(count, 1 << 16));
        for (int i = 0; i < count; i++) {
            if (b.remaining() < 4) throw new CorruptBatchException("short record header");
            int n = b.getInt();
            if (n < 0 || n > b.remaining()) throw new CorruptBatchException("bad record length");
            var r = new byte[n];
            b.get(r);
            recs.add(r);
        }
        if (b.hasRemaining()) throw new CorruptBatchException("trailing bytes");
        return new RecordBatch(base, ts, recs);
    }
}
