package sjws_nettask.messaging.ledger;

import java.nio.charset.StandardCharsets;

/**
 * In-tree XXH64 (xxHash 64-bit, seed 0 by default) for sticky writer-shard selection (#336 / #337).
 * No new dependency. Algorithm matches cyan4973/xxHash reference; see {@link XXH64Test} KATs.
 */
public final class XXH64 {

    private static final long P1 = 0x9E3779B185EBCA87L;
    private static final long P2 = 0xC2B2AE3D27D4EB4FL;
    private static final long P3 = 0x165667B19E3779F9L;
    private static final long P4 = 0x85EBCA77C2B2AE63L;
    private static final long P5 = 0x27D4EB2F165667C5L;

    private XXH64() {}

    /** XXH64 of {@code data} with seed 0. */
    public static long hash(byte[] data) {
        return hash(data, 0, data == null ? 0 : data.length, 0L);
    }

    /** XXH64 of UTF-8 bytes of {@code s} with seed 0. */
    public static long hashUtf8(String s) {
        return hash(s.getBytes(StandardCharsets.UTF_8));
    }

    public static long hash(byte[] data, int off, int len, long seed) {
        if (data == null) throw new NullPointerException("data");
        if (off < 0 || len < 0 || off + len > data.length) throw new IndexOutOfBoundsException();
        int end = off + len;
        int i = off;
        long h;
        if (len >= 32) {
            long v1 = seed + P1 + P2;
            long v2 = seed + P2;
            long v3 = seed;
            long v4 = seed - P1;
            while (i + 32 <= end) {
                v1 = round(v1, readLong(data, i));
                v2 = round(v2, readLong(data, i + 8));
                v3 = round(v3, readLong(data, i + 16));
                v4 = round(v4, readLong(data, i + 24));
                i += 32;
            }
            h = Long.rotateLeft(v1, 1) + Long.rotateLeft(v2, 7) + Long.rotateLeft(v3, 12) + Long.rotateLeft(v4, 18);
            h = merge(h, v1);
            h = merge(h, v2);
            h = merge(h, v3);
            h = merge(h, v4);
        } else {
            h = seed + P5;
        }
        h += len;
        while (i + 8 <= end) {
            long k1 = round(0L, readLong(data, i));
            h ^= k1;
            h = Long.rotateLeft(h, 27) * P1 + P4;
            i += 8;
        }
        if (i + 4 <= end) {
            h ^= (readInt(data, i) & 0xFFFFFFFFL) * P1;
            h = Long.rotateLeft(h, 23) * P2 + P3;
            i += 4;
        }
        while (i < end) {
            h ^= (data[i] & 0xFFL) * P5;
            h = Long.rotateLeft(h, 11) * P1;
            i++;
        }
        h ^= h >>> 33;
        h *= P2;
        h ^= h >>> 29;
        h *= P3;
        h ^= h >>> 32;
        return h;
    }

    private static long round(long acc, long input) {
        acc += input * P2;
        acc = Long.rotateLeft(acc, 31);
        return acc * P1;
    }

    private static long merge(long acc, long val) {
        val = round(0L, val);
        acc ^= val;
        return acc * P1 + P4;
    }

    private static long readLong(byte[] a, int i) {
        return (a[i] & 0xFFL)
                | ((a[i + 1] & 0xFFL) << 8)
                | ((a[i + 2] & 0xFFL) << 16)
                | ((a[i + 3] & 0xFFL) << 24)
                | ((a[i + 4] & 0xFFL) << 32)
                | ((a[i + 5] & 0xFFL) << 40)
                | ((a[i + 6] & 0xFFL) << 48)
                | ((a[i + 7] & 0xFFL) << 56);
    }

    private static int readInt(byte[] a, int i) {
        return (a[i] & 0xFF)
                | ((a[i + 1] & 0xFF) << 8)
                | ((a[i + 2] & 0xFF) << 16)
                | ((a[i + 3] & 0xFF) << 24);
    }
}
