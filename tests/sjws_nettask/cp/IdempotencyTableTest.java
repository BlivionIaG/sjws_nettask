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
package sjws_nettask.cp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** #259: window and cap eviction, two-replica agreement, snapshot at an eviction boundary. */
class IdempotencyTableTest {

    static byte[] bytes(IdempotencyTable t) throws IOException {
        var out = new ByteArrayOutputStream();
        try (var d = new DataOutputStream(out)) {
            t.write(d);
        }
        return out.toByteArray();
    }

    static IdempotencyTable read(byte[] b, long window, int max) throws IOException {
        return IdempotencyTable.read(new DataInputStream(new ByteArrayInputStream(b)), window, max);
    }

    @Test
    void windowEvictsAtExactlyIMinusWAndKeepsIMinusWPlusOne() {
        // W=4: at index 10, floor = 6 → index 6 gone, index 7 kept.
        var t = new IdempotencyTable(4, 100);
        t.remember("k6", "fp", 200, "b6", 6);
        t.remember("k7", "fp", 200, "b7", 7);
        t.remember("k8", "fp", 200, "b8", 8);
        t.remember("k9", "fp", 200, "b9", 9);
        t.remember("k10", "fp", 200, "b10", 10);
        assertNull(t.get("k6"), "appliedIndex == i - W is evicted under documented <=");
        assertEquals(200, t.get("k7").status(), "appliedIndex == i - W + 1 still dedups");
        assertEquals("b7", t.get("k7").body());
        assertEquals(4, t.size());
        assertEquals(1, t.evictions());
    }

    @Test
    void sameKeyDifferentFingerprintStaysAsFirstAndUnknownAfterEvictionAppliesAgain() {
        var t = new IdempotencyTable(3, 100);
        t.remember("k", "fp-a", 200, "first", 1);
        t.remember("k", "fp-b", 200, "second", 2); // first wins; no second entry
        assertEquals("fp-a", t.get("k").fingerprint());
        assertEquals("first", t.get("k").body());
        t.remember("a", "fp", 200, "a", 2);
        t.remember("b", "fp", 200, "b", 3);
        t.remember("c", "fp", 200, "c", 4); // evicts k at 1: 4 - 3 = 1
        assertNull(t.get("k"), "evicted key is unknown: a retry applies again (no KeyExpired)");
    }

    @Test
    void capEvictsEldestByAppliedIndex() {
        var t = new IdempotencyTable(100, 2);
        t.remember("a", "fp", 200, "a", 1);
        t.remember("b", "fp", 200, "b", 2);
        t.remember("c", "fp", 200, "c", 3);
        assertNull(t.get("a"));
        assertEquals(200, t.get("b").status());
        assertEquals(200, t.get("c").status());
        assertEquals(1, t.evictions());
        assertEquals(2, t.size());
    }

    @Test
    void twoReplicasAgreeOnEveryVerdictAcrossWindowAndCapEviction() throws IOException {
        var a = new IdempotencyTable(5, 4);
        var b = new IdempotencyTable(5, 4);
        var rnd = new Random(7);
        for (int i = 1; i <= 500; i++) {
            String key = "k" + rnd.nextInt(12);
            String fp = rnd.nextBoolean() ? "fp-" + key : "other-" + i;
            for (var t : new IdempotencyTable[] {a, b}) {
                var before = t.get(key);
                if (before == null) {
                    t.remember(key, fp, 200, "body-" + i, i);
                } else {
                    // retry verdict: same fp → cached body; different fp → still the first (409 at admission)
                    assertEquals(before.fingerprint().equals(fp) ? before.body() : before.body(), before.body());
                    t.remember(key, fp, 200, "ignored", i); // apply path: containsKey skip + evict
                }
            }
            assertArrayEquals(bytes(a), bytes(b), "index " + i);
        }
        assertTrue(a.evictions() > 0);
        assertTrue(a.size() <= 4);
    }

    @Test
    void snapshotAtEvictionBoundaryInstallsExactlySurvivingEntries() throws IOException {
        long w = 4;
        var live = new IdempotencyTable(w, 100);
        // Apply through s=10: entries at 6..10; floor = 10-4 = 6 → drop 6, keep 7..10.
        for (long i = 1; i <= 10; i++) {
            live.remember("k" + i, "fp" + i, 200, "b" + i, i);
        }
        assertNull(live.get("k6"));
        assertEquals(200, live.get("k7").status());
        byte[] image = bytes(live);
        var restored = read(image, w, 100);
        assertArrayEquals(image, bytes(restored));
        assertNull(restored.get("k6"));
        assertEquals("b7", restored.get("k7").body());
        // Continue past the boundary: both evict alike.
        live.remember("k11", "fp", 200, "b11", 11); // drops k7 (floor=7)
        restored.remember("k11", "fp", 200, "b11", 11);
        assertArrayEquals(bytes(live), bytes(restored));
        assertNull(restored.get("k7"));
        assertEquals(200, restored.get("k8").status());
    }

    @Test
    void readRefusesMismatchedWindowOrCap() throws IOException {
        var t = new IdempotencyTable(4, 3);
        t.remember("k", "fp", 200, "b", 1);
        byte[] image = bytes(t);
        assertThrows(IOException.class, () -> read(image, 5, 3), "mismatched window");
        assertThrows(IOException.class, () -> read(image, 4, 9), "mismatched cap");
        assertThrows(IOException.class, () -> IdempotencyTable.read(new DataInputStream(new ByteArrayInputStream(image))),
                "production read expects WINDOW/MAX_KEYS");
    }

    @Test
    void replaceWithInstallsAndDoesNotMerge() {
        var host = new IdempotencyTable(4, 10);
        host.remember("old", "fp", 200, "old", 1);
        var snap = new IdempotencyTable(4, 10);
        snap.remember("new", "fp", 200, "new", 8);
        host.replaceWith(snap);
        assertNull(host.get("old"), "install replaces, does not merge");
        assertEquals("new", host.get("new").body());
    }

    @Test
    void replaceWithRejectsMismatchedBoundsAndCtorRejectsNonPositive() {
        assertThrows(IllegalArgumentException.class, () -> new IdempotencyTable(0, 10));
        assertThrows(IllegalArgumentException.class, () -> new IdempotencyTable(10, 0));
        var host = new IdempotencyTable(4, 10);
        var other = new IdempotencyTable(5, 10);
        assertThrows(IllegalArgumentException.class, () -> host.replaceWith(other));
    }

    @Test
    void readRejectsCorruptPayloads() throws IOException {
        var t = new IdempotencyTable(4, 3);
        t.remember("k", "fp", 200, "b", 1);
        byte[] good = bytes(t);
        // truncate
        assertThrows(IOException.class, () -> read(java.util.Arrays.copyOf(good, 8), 4, 3));
        // bad format
        var badFormat = good.clone();
        badFormat[3] = 99;
        assertThrows(IOException.class, () -> read(badFormat, 4, 3));
        // production read of test-sized table
        assertThrows(IOException.class, () -> IdempotencyTable.read(
                new DataInputStream(new ByteArrayInputStream(good))));
        // empty table round-trip
        var empty = new IdempotencyTable(4, 3);
        var round = read(bytes(empty), 4, 3);
        assertEquals(0, round.size());
        assertEquals(0, round.evictions());
        // contains / get on empty
        assertNull(empty.get("nope"));
        assertTrue(!empty.contains("nope"));
        // remember rejects bad args
        assertThrows(IllegalArgumentException.class, () -> empty.remember(null, "fp", 200, "b", 1));
        assertThrows(IllegalArgumentException.class, () -> empty.remember("k", "fp", 200, "b", 0));
    }

    @Test
    void productionDefaultsRoundTrip() throws IOException {
        var t = new IdempotencyTable();
        assertEquals(IdempotencyTable.WINDOW, t.window());
        assertEquals(IdempotencyTable.MAX_KEYS, t.maxKeys());
        t.remember("only", "fp", 200, "body", 1);
        var restored = IdempotencyTable.read(new DataInputStream(new ByteArrayInputStream(bytes(t))));
        assertEquals("body", restored.get("only").body());
        assertEquals(1, restored.size());
    }

}
