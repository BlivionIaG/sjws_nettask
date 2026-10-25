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
        return ProducerTable.read(new DataInputStream(new ByteArrayInputStream(b)));
    }

    @Test
    void nextSeqIsNewRetryIsTheOriginalBaseAndGapsAreOutOfOrder() {
        var t = new ProducerTable();
        assertEquals(ProducerTable.OUT_OF_ORDER, t.check(0, 7, 2, 1), "an unknown producer starts at 1");
        assertEquals(ProducerTable.OUT_OF_ORDER, t.check(0, 7, 0, 1));
        assertEquals(1, t.expectedSeq(0, 7));
        assertEquals(ProducerTable.NEW, t.check(0, 7, 1, 2));
        t.apply(0, 7, 1, 100, 2);
        assertEquals(100, t.check(0, 7, 1, 2), "retry: original base");
        assertEquals(ProducerTable.SEQ_REUSED, t.check(0, 7, 1, 3), "same seq, another batch");
        assertEquals(ProducerTable.OUT_OF_ORDER, t.check(0, 7, 3, 1), "gap");
        assertEquals(ProducerTable.NEW, t.check(0, 7, 2, 1));
        assertEquals(2, t.expectedSeq(0, 7));
        assertEquals(ProducerTable.NEW, t.check(1, 7, 1, 1), "origin is part of the key");
        assertThrows(IllegalStateException.class, () -> t.apply(0, 7, 3, 0, 1));
        assertThrows(IllegalStateException.class, () -> t.apply(0, 8, 2, 0, 1));
    }

    @Test
    void windowRemembersTheLastBatchesOnly() {
        var t = new ProducerTable();
        int n = ProducerTable.WINDOW + 5;
        for (int s = 1; s <= n; s++) t.apply(0, 1, s, 10L * s, s % 3 + 1);
        for (int s = n - ProducerTable.WINDOW + 1; s <= n; s++) assertEquals(10L * s, t.check(0, 1, s, s % 3 + 1), "seq " + s);
        for (int s = 1; s <= n - ProducerTable.WINDOW; s++) assertEquals(ProducerTable.OUT_OF_WINDOW, t.check(0, 1, s, s % 3 + 1));
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
            a.check(0, 1 + rnd.nextInt(6), 1, 1);
            for (var t : new ProducerTable[] {a, b}) t.apply(0, pid, seq, base, 1);
            base++;
            assertArrayEquals(bytes(a), bytes(b), "entry " + k);
        }
        assertEquals(3, a.size());

        var t = new ProducerTable(2);
        t.apply(0, 1, 1, 0, 1);
        t.apply(0, 2, 1, 1, 1);
        t.check(0, 1, 2, 1); // a lookup: 1 stays the eldest
        t.apply(0, 3, 1, 2, 1);
        assertEquals(1, t.evictions());
        assertEquals(1, t.expectedSeq(0, 1), "evicted: unknown again");
        assertEquals(ProducerTable.OUT_OF_ORDER, t.check(0, 1, 2, 1));
        t.apply(0, 2, 2, 3, 1); // 2 refreshed: 3 is now the eldest
        t.apply(0, 4, 1, 4, 1);
        assertEquals(1, t.expectedSeq(0, 3));
        assertEquals(3, t.expectedSeq(0, 2));
    }

    @Test
    void encodingRoundTripsAndKeepsTheEvictionOrder() throws IOException {
        var t = new ProducerTable(3);
        for (int s = 1; s <= 20; s++) t.apply(0, 9, s, s * 2L, 2);
        t.apply(0, 5, 1, 99, 1);
        t.apply(4, 5, 1, 100, 3);
        var copy = read(bytes(t));
        assertArrayEquals(bytes(t), bytes(copy));
        assertEquals(40, copy.check(0, 9, 20, 2));
        assertEquals(ProducerTable.OUT_OF_WINDOW, copy.check(0, 9, 4, 2));
        for (var x : new ProducerTable[] {t, copy}) x.apply(0, 6, 1, 200, 1);
        assertArrayEquals(bytes(t), bytes(copy), "the restored table evicts like the original");
        assertEquals(1, copy.expectedSeq(0, 9));

        var bad = bytes(t);
        bad[3] = 2; // format 2
        assertThrows(IOException.class, () -> read(bad));
    }
}
