package sjws_nettask.msgrepl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Random;

import org.junit.jupiter.api.Test;

/** M2 producer table: classification, window, deterministic LRU eviction, and the #270 encoding round trip. */
class ProducerTableTest {

    static byte[] bytes(ProducerTable t) throws IOException {
        var out = new ByteArrayOutputStream();
        try (var d = new DataOutputStream(out)) {
            t.write(d);
        }
        return out.toByteArray();
    }

    static ProducerTable read(byte[] b) throws IOException {
        return ProducerTable.read(new DataInputStream(new ByteArrayInputStream(b)), 3);
    }

    @Test
    void nextSeqIsNewRetryIsTheOriginalBaseAndGapsAreOutOfOrder() {
        var t = new ProducerTable();
        assertEquals(ProducerTable.UNKNOWN_PRODUCER, t.check(0, 7, 2, 1, 0), "an unknown producer starts at 1 (#278 HA B2)");
        assertEquals(ProducerTable.OUT_OF_ORDER, t.check(0, 7, 0, 1, 0));
        assertEquals(1, t.expectedSeq(0, 7));
        assertEquals(ProducerTable.NEW, t.check(0, 7, 1, 2, 0));
        t.apply(0, 7, 1, 100, 2, 0);
        assertEquals(100, t.check(0, 7, 1, 2, 0), "retry: original base");
        assertEquals(ProducerTable.SEQ_REUSED, t.check(0, 7, 1, 3, 0), "same seq, another batch");
        assertEquals(ProducerTable.SEQ_REUSED, t.check(0, 7, 1, 2, 0x5eed), "same seq and count, other records (#278 B2)");
        assertEquals(ProducerTable.OUT_OF_ORDER, t.check(0, 7, 3, 1, 0), "gap");
        assertEquals(ProducerTable.NEW, t.check(0, 7, 2, 1, 0));
        assertEquals(2, t.expectedSeq(0, 7));
        assertEquals(ProducerTable.NEW, t.check(1, 7, 1, 1, 0), "origin is part of the key");
        assertThrows(IllegalStateException.class, () -> t.apply(0, 7, 3, 0, 1, 0));
        assertThrows(IllegalStateException.class, () -> t.apply(0, 8, 2, 0, 1, 0));
    }

    @Test
    void windowRemembersTheLastBatchesOnly() {
        var t = new ProducerTable();
        int n = ProducerTable.WINDOW + 5;
        for (int s = 1; s <= n; s++) t.apply(0, 1, s, 10L * s, s % 3 + 1, 0);
        for (int s = n - ProducerTable.WINDOW + 1; s <= n; s++) assertEquals(10L * s, t.check(0, 1, s, s % 3 + 1, 0), "seq " + s);
        for (int s = 1; s <= n - ProducerTable.WINDOW; s++) assertEquals(ProducerTable.OUT_OF_WINDOW, t.check(0, 1, s, s % 3 + 1, 0));
    }

    @Test
    void evictsTheLeastRecentlyAppliedProducerDeterministically() throws IOException {
        var a = new ProducerTable(3);
        var b = new ProducerTable(3);
        var rnd = new Random(42);
        long base = 0;
        for (int k = 0; k < 2000; k++) {
            long pid = 1 + rnd.nextInt(6);
            long seq = a.expectedSeq(0, pid);
            // Lookups never refresh a producer: only appends do.
            a.check(0, 1 + rnd.nextInt(6), 1, 1, 0);
            for (var t : new ProducerTable[] {a, b}) t.apply(0, pid, seq, base, 1, 0);
            base++;
            assertArrayEquals(bytes(a), bytes(b), "entry " + k);
        }
        assertEquals(3, a.size());

        var t = new ProducerTable(2);
        t.apply(0, 1, 1, 0, 1, 0);
        t.apply(0, 2, 1, 1, 1, 0);
        t.check(0, 1, 2, 1, 0); // a lookup: 1 stays the eldest
        t.apply(0, 3, 1, 2, 1, 0);
        assertEquals(1, t.evictions());
        assertEquals(1, t.expectedSeq(0, 1), "evicted: unknown again");
        assertEquals(ProducerTable.UNKNOWN_PRODUCER, t.check(0, 1, 2, 1, 0), "evicted: undecidable, not out of order");
        t.apply(0, 2, 2, 3, 1, 0); // 2 refreshed: 3 is now the eldest
        t.apply(0, 4, 1, 4, 1, 0);
        assertEquals(1, t.expectedSeq(0, 3));
        assertEquals(3, t.expectedSeq(0, 2));
    }

    @Test
    void encodingRoundTripsAndKeepsTheEvictionOrder() throws IOException {
        var t = new ProducerTable(3);
        for (int s = 1; s <= 20; s++) t.apply(0, 9, s, s * 2L, 2, 0);
        t.apply(0, 5, 1, 99, 1, 0);
        t.apply(4, 5, 1, 100, 3, 0);
        var copy = read(bytes(t));
        assertArrayEquals(bytes(t), bytes(copy));
        assertEquals(40, copy.check(0, 9, 20, 2, 0));
        assertEquals(ProducerTable.OUT_OF_WINDOW, copy.check(0, 9, 4, 2, 0));
        for (var x : new ProducerTable[] {t, copy}) x.apply(0, 6, 1, 200, 1, 0);
        assertArrayEquals(bytes(t), bytes(copy), "the restored table evicts like the original");
        assertEquals(1, copy.expectedSeq(0, 9));

        var digests = new ProducerTable(3);
        digests.apply(0, 1, 1, 7, 2, 0xCAFE);
        var restored = read(bytes(digests));
        assertEquals(7, restored.check(0, 1, 1, 2, 0xCAFE), "the digest survives the encoding");
        assertEquals(ProducerTable.SEQ_REUSED, restored.check(0, 1, 1, 2, 0xBEEF));

        var bad = bytes(t);
        bad[3] = 1; // format 1: no digests
        assertThrows(IOException.class, () -> read(bad));
        var otherMax = bytes(t);
        otherMax[11] = 4; // max 4, not this table's 3
        assertThrows(IOException.class, () -> read(otherMax));
        assertThrows(IOException.class, () -> ProducerTable.read(new DataInputStream(new ByteArrayInputStream(bytes(t)))),
                "a restored table must have the state machine's producer limit");
        var negative = new ProducerTable(3);
        negative.apply(0, 1, 1, -5, 1, 0);
        assertThrows(IOException.class, () -> read(bytes(negative)), "negative base");
    }

    /** One producer {@code (0, 1)} with {@code highest} and every window slot {@code (base, count)}, format 2. */
    static byte[] encoded(int max, long evictions, long highest, long base, int count) throws IOException {
        var out = new ByteArrayOutputStream();
        try (var d = new DataOutputStream(out)) {
            d.writeInt(ProducerTable.FORMAT);
            d.writeInt(ProducerTable.WINDOW);
            d.writeInt(max);
            d.writeLong(evictions);
            d.writeInt(1);
            d.writeInt(0);
            d.writeLong(1);
            d.writeLong(highest);
            for (long k = 0; k < Math.min(ProducerTable.WINDOW, Math.max(highest, 0)); k++) {
                d.writeLong(base);
                d.writeInt(count);
                d.writeInt(0);
            }
        }
        return out.toByteArray();
    }

    static ProducerTable readReal(byte[] b) throws IOException {
        return ProducerTable.read(new DataInputStream(new ByteArrayInputStream(b)));
    }

    /** #278 HA B4 / Security N3: every bound of the restore seam. */
    @Test
    @org.junit.jupiter.api.Timeout(10)
    void readRefusesOutOfBoundsTables() throws IOException {
        int max = ProducerTable.MAX_PRODUCERS;
        var ok = readReal(encoded(max, 0, 3, 5, 1));
        assertEquals(4, ok.expectedSeq(0, 1));
        assertEquals(5, ok.check(0, 1, 3, 1, 0));
        readReal(encoded(max, 0, ProducerTable.MAX_SEQ, 0, 1)); // the highest seq a batch may carry
        for (var bad : java.util.List.of(
                encoded(Integer.MAX_VALUE, 0, 3, 5, 1), // another producer limit
                encoded(max - 1, 0, 3, 5, 1),
                encoded(1, 0, 3, 5, 1),
                encoded(max, -1, 3, 5, 1), // negative evictions
                encoded(max, 0, 0, 5, 1), // highest < 1
                encoded(max, 0, -7, 5, 1),
                encoded(max, 0, Long.MAX_VALUE, 5, 1), // highest + 1 would overflow
                encoded(max, 0, 3, -1, 1), // negative base
                encoded(max, 0, 3, 5, 0), // empty batch
                encoded(max, 0, 3, 5, -2))) {
            assertThrows(IOException.class, () -> readReal(bad));
        }
        var t = new ProducerTable();
        assertThrows(IllegalArgumentException.class, () -> t.apply(0, 1, Long.MAX_VALUE, 0, 1, 0));
        assertEquals(ProducerTable.OUT_OF_ORDER, t.check(0, 1, Long.MAX_VALUE, 1, 0));
    }

    /** #278 HA B4: corrupted or truncated encodings either read or throw IOException; never hang or throw anything else. */
    @Test
    @org.junit.jupiter.api.Timeout(30)
    void fuzzedEncodingsReadOrFailWithIOException() throws IOException {
        var t = new ProducerTable();
        for (int s = 1; s <= 20; s++) t.apply(0, 9, s, s * 2L, 2, s);
        t.apply(3, 5, 1, 99, 1, 7);
        var valid = bytes(t);
        var rnd = new Random(278);
        for (int round = 0; round < 20_000; round++) {
            var b = valid.clone();
            int flips = 1 + rnd.nextInt(4);
            for (int k = 0; k < flips; k++) b[rnd.nextInt(b.length)] = (byte) rnd.nextInt(256);
            var in = rnd.nextInt(8) == 0 ? java.util.Arrays.copyOf(b, rnd.nextInt(b.length)) : b;
            try {
                var copy = readReal(in);
                bytes(copy); // a table that reads also writes, in bounded time
            } catch (IOException expected) {
                // refused
            }
        }
    }
}
