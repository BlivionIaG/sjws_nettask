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
package sjws_nettask.messaging.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sjws_nettask.core.Request;
import sjws_nettask.messaging.ledger.Durability;
import sjws_nettask.messaging.ledger.RecordBatch;
import sjws_nettask.messaging.ledger.SegmentedLog;

class LogCompactionTest {

    @TempDir Path dir;

    static final long SEG = 512;

    /** One record: {@code [klen:int][key][value]}. */
    static byte[] kv(String k, String v) {
        var kb = k.getBytes(StandardCharsets.UTF_8);
        var vb = v.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(4 + kb.length + vb.length).putInt(kb.length).put(kb).put(vb).array();
    }

    /** A produce body holding the given records. */
    static byte[] body(byte[]... records) {
        int n = 0;
        for (var r : records) n += 4 + r.length;
        var b = ByteBuffer.allocate(n);
        for (var r : records) b.putInt(r.length).put(r);
        return b.array();
    }

    static String keyOf(byte[] r) {
        var k = LogCompaction.key(r);
        return k == null ? null : StandardCharsets.UTF_8.decode(k).toString();
    }

    static String valueOf(byte[] r) {
        int n = ByteBuffer.wrap(r).getInt(0);
        return new String(r, 4 + n, r.length - 4 - n, StandardCharsets.UTF_8);
    }

    LogTopics topics(Set<String> compacted) {
        return new LogTopics(dir, Durability.ASYNC, Map.of(), SEG, LogRetention.NONE, compacted, System::currentTimeMillis);
    }

    /** Produces {@code n} single-record batches cycling over {@code keys} keys; value = sequence number. */
    static void fill(MessagingHttp h, String topic, int n, int keys) {
        for (int i = 0; i < n; i++)
            assertEquals(200, MessagingHttpTest.post(h, topic, body(kv("k" + (i % keys), "v" + i + "-" + "p".repeat(40))), "group").status());
    }

    /** Fetches the whole topic from offset 0 following Next-Offset, with a small maxBytes so gaps are crossed often. */
    static List<RecordBatch> fetchAll(MessagingHttp h, String topic) throws Exception {
        var out = new ArrayList<RecordBatch>();
        long next = 0;
        for (int guard = 0; guard < 10_000; guard++) {
            var r = h.handle(new Request("GET", "/messaging/v1/topics/" + topic + "/records?offset=" + next + "&maxBytes=200"));
            assertEquals(200, r.status());
            var bs = MessagingHttpTest.batches(MessagingHttpTest.bytes(r));
            long n = Long.parseLong(r.headers().get("next-offset"));
            if (bs.isEmpty()) {
                assertEquals(next, n);
                return out;
            }
            assertTrue(bs.get(0).nextOffset() > next, "progress");
            out.addAll(bs);
            assertEquals(bs.getLast().nextOffset(), n);
            next = n;
        }
        throw new AssertionError("no end");
    }

    static Map<String, String> lastValues(List<RecordBatch> bs) {
        var m = new LinkedHashMap<String, String>();
        for (var b : bs) for (var r : b.records()) m.put(keyOf(r), valueOf(r));
        return m;
    }

    @Test
    void keepsTheLastValuePerKeyAndItSurvivesARestart() throws Exception {
        Map<String, String> before;
        int recordsBefore;
        try (var topics = topics(Set.of("k"))) {
            var h = new MessagingHttp(topics);
            fill(h, "k", 80, 5);
            var all = fetchAll(h, "k");
            before = lastValues(all);
            recordsBefore = all.size();
            var log = topics.get("k", false).log();
            assertTrue(log.segmentCount() > 4, "segments " + log.segmentCount());
            long bytes = log.totalBytes();
            var res = topics.compact();
            assertTrue(res.segmentsRewritten() > 0, res.toString());
            assertTrue(res.batchesDropped() > 0);
            assertTrue(log.totalBytes() < bytes);
            assertTrue(log.segments().stream().anyMatch(SegmentedLog.SegmentInfo::compacted));
            var after = fetchAll(h, "k");
            assertTrue(after.size() < recordsBefore, after.size() + " vs " + recordsBefore);
            assertEquals(before, lastValues(after));
            assertEquals(0, topics.compact().segmentsRewritten(), "idempotent");
            fill(h, "k", 3, 5); // produce continues after compaction
            before = lastValues(fetchAll(h, "k"));
        }
        try (var topics = topics(Set.of("k"))) {
            var h = new MessagingHttp(topics);
            topics.get("k", false);
            assertEquals(before, lastValues(fetchAll(h, "k")));
        }
    }

    @Test
    void sealedPartHoldsOneBatchPerKeyExceptKeysNewerInLaterSealedSegments() throws Exception {
        try (var topics = topics(Set.of("k"))) {
            var h = new MessagingHttp(topics);
            fill(h, "k", 80, 3);
            topics.compact();
            var log = topics.get("k", false).log();
            long sealedEnd = log.segments().getLast().baseOffset();
            var keys = new ArrayList<String>();
            for (var b : fetchAll(h, "k")) if (b.baseOffset() < sealedEnd) for (var r : b.records()) keys.add(keyOf(r));
            assertEquals(Set.copyOf(keys).size(), keys.size(), "duplicate key in sealed part: " + keys);
        }
    }

    @Test
    void fetchAtACompactedOffsetSkipsToTheNextRetainedBatch() throws Exception {
        try (var topics = topics(Set.of("k"))) {
            var h = new MessagingHttp(topics);
            fill(h, "k", 60, 2);
            topics.compact();
            var log = topics.get("k", false).log();
            assertNull(log.read(0), "offset 0 compacted away");
            var r = h.handle(new Request("GET", "/messaging/v1/topics/k/records?offset=0"));
            assertEquals(200, r.status());
            var bs = MessagingHttpTest.batches(MessagingHttpTest.bytes(r));
            assertFalse(bs.isEmpty());
            assertTrue(bs.get(0).baseOffset() > 0);
            assertEquals(bs.getLast().nextOffset(), Long.parseLong(r.headers().get("next-offset")));
            // offsets stay increasing and never repeat across the gap
            long prev = -1;
            for (var b : fetchAll(h, "k")) { assertTrue(b.baseOffset() > prev); prev = b.baseOffset(); }
        }
    }

    @Test
    void recordsWithoutAKeyAreAlwaysKept() throws Exception {
        try (var topics = topics(Set.of("k"))) {
            var h = new MessagingHttp(topics);
            for (int i = 0; i < 40; i++) {
                assertEquals(200, MessagingHttpTest.post(h, "k", body(new byte[] {1, 2}), "group").status()); // < 4 bytes
                assertEquals(200, MessagingHttpTest.post(h, "k", body(kv("same", "v" + i + "x".repeat(40))), "group").status());
            }
            topics.compact();
            int unkeyed = 0;
            for (var b : fetchAll(h, "k")) for (var r : b.records()) if (keyOf(r) == null) unkeyed++;
            assertEquals(40, unkeyed);
        }
    }

    @Test
    void aBatchStaysWhenAnyOfItsRecordsIsALastValue() throws Exception {
        try (var topics = topics(Set.of("k"))) {
            var h = new MessagingHttp(topics);
            assertEquals(200, MessagingHttpTest.post(h, "k", body(kv("a", "old"), kv("b", "only")), "group").status());
            fill(h, "k", 40, 1); // "k0" values; seals the first batch's segment
            topics.compact();
            var m = lastValues(fetchAll(h, "k"));
            assertEquals("only", m.get("b"));
            assertEquals("old", m.get("a")); // kept with b: a batch is not split
        }
    }

    @Test
    void topicsNotListedAreNeverCompacted() throws Exception {
        try (var topics = topics(Set.of("other"))) {
            var h = new MessagingHttp(topics);
            fill(h, "plain", 60, 2);
            var log = topics.get("plain", false).log();
            long bytes = log.totalBytes();
            assertEquals(0, topics.compact().segmentsRewritten());
            assertEquals(bytes, log.totalBytes());
            assertFalse(topics.get("plain", false).topicCompacted());
        }
        try (var topics = topics(Set.of())) {
            assertFalse(topics.compactionEnabled());
            assertEquals(LogCompaction.Result.NONE, topics.compact());
        }
    }

    @Test
    void moduleSchedulesNoThreadWhenCompactionAndRetentionAreOff() throws Exception {
        var off = new MessagingModule(dir.resolve("off"));
        assertFalse(off.retentionScheduled());
        off.close();
        var on = new MessagingModule(dir.resolve("on"), SEG, LogRetention.NONE, 10, Durability.ASYNC, Map.of(), Set.of("k"));
        assertTrue(on.retentionScheduled());
        on.close();
    }

    @Test
    void keyAndConfigParsing() {
        assertNull(LogCompaction.key(new byte[] {0, 0, 0}));
        assertNull(LogCompaction.key(new byte[] {0, 0, 0, 5, 1}));
        assertNull(LogCompaction.key(new byte[] {(byte) 0xff, 0, 0, 0, 1}));
        assertEquals("ab", keyOf(kv("ab", "zz")));
        assertEquals("", keyOf(kv("", "zz")));
        assertEquals(Set.of("a", "b.c"), LogCompaction.parseTopics(" a, b.c ,,"));
        assertThrows(IllegalArgumentException.class, () -> LogCompaction.parseTopics("ok,bad/name"));
        assertThrows(IllegalArgumentException.class, () -> LogCompaction.parseTopics("__evil"));
        assertThrows(IllegalArgumentException.class, () -> LogCompaction.parseTopics("ok,__offsets"));
        assertEquals(Set.of(), MessagingModule.compactionTopics("bad/name"));
        assertEquals(Set.of(), MessagingModule.compactionTopics("__evil"));
    }

    // --- crash at each step of the copy, fsync, marker, swap protocol ---

    static SegmentedLog keyedLog(Path d) throws Exception {
        var log = SegmentedLog.open(d, SEG);
        for (int i = 0; i < 60; i++) log.append(List.of(kv("k" + (i % 3), "v" + i + "-" + "p".repeat(40))), 1L);
        log.force();
        return log;
    }

    static List<Long> bases(SegmentedLog log) throws Exception {
        var out = new ArrayList<Long>();
        for (var b : log.readAll()) out.add(b.baseOffset());
        return out;
    }

    @Test
    void crashBeforeTheMarkerRollsBack() throws Exception {
        var d = dir.resolve("rb");
        List<Long> full;
        long base;
        try (var log = keyedLog(d)) {
            full = bases(log);
            var p = LogCompaction.prepare(log);
            assertFalse(p.isEmpty());
            base = p.get(0).baseOffset();
        }
        // the copy was complete but the marker never landed: delete it, as if we crashed between fsync and marker
        try (var w = Files.list(d)) {
            for (var m : w.filter(x -> x.toString().endsWith(".compacted")).toList()) Files.delete(m);
        }
        try (var log = SegmentedLog.open(d, SEG)) {
            assertEquals(full, bases(log));
            try (var w = Files.list(d)) { assertTrue(w.noneMatch(x -> x.toString().endsWith(".cmp"))); }
            assertFalse(log.segments().get(0).compacted());
        }
    }

    @Test
    void crashAfterTheMarkerBeforeTheSwapRollsForward() throws Exception {
        var d = dir.resolve("rf");
        List<Long> full;
        Map<String, String> last;
        try (var log = keyedLog(d)) {
            full = bases(log);
            last = lastValues(log.readAll());
            assertFalse(LogCompaction.prepare(log).isEmpty()); // copies + markers written, no commit
        }
        try (var log = SegmentedLog.open(d, SEG)) {
            var now = bases(log);
            assertTrue(now.size() < full.size(), now + " vs " + full);
            assertEquals(last, lastValues(log.readAll()));
            assertTrue(log.segments().get(0).compacted());
            assertEquals(60, log.nextOffset());
            try (var w = Files.list(d)) { assertTrue(w.noneMatch(p -> p.toString().endsWith(".cmp"))); }
        }
    }

    @Test
    void crashAfterTheSwapKeepsTheCompactedSegment() throws Exception {
        var d = dir.resolve("sw");
        List<Long> compacted;
        try (var log = keyedLog(d)) {
            for (var p : LogCompaction.prepare(log)) log.commitCompaction(p.baseOffset());
            compacted = bases(log);
        }
        try (var log = SegmentedLog.open(d, SEG)) {
            assertEquals(compacted, bases(log));
            assertEquals(60, log.nextOffset());
            log.append(List.of(kv("k0", "new")), 1L);
            assertEquals(61, log.nextOffset());
        }
    }

    @Test
    void readCeilCrossesAnEmptiedSegment() throws Exception {
        var d = dir.resolve("ce");
        try (var log = SegmentedLog.open(d, SEG)) {
            for (int i = 0; i < 60; i++) log.append(List.of(kv("same", "v" + i + "-" + "p".repeat(40))), 1L);
            for (var p : LogCompaction.prepare(log)) log.commitCompaction(p.baseOffset());
            // every sealed segment but possibly the last is now empty; the first retained batch is far ahead
            var b = log.readCeil(0);
            assertTrue(b != null && b.baseOffset() > 0);
            assertNull(log.readCeil(log.nextOffset()));
        }
    }
}
