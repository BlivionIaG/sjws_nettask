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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sjws_nettask.core.Request;
import sjws_nettask.messaging.ledger.Durability;

/** Keyed topics (durable-log-plan §1): sealed segments keep only each key's latest record. */
class KeyedCompactionTest {

    @TempDir Path dir;
    static final long SEG = 1024;

    static byte[] keyed(String k, String v) {
        var kb = k.getBytes(StandardCharsets.UTF_8);
        var vb = v.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(4 + kb.length + vb.length).putInt(kb.length).put(kb).put(vb).array();
    }

    /** POST body of one keyed record. */
    static byte[] body(String k, String v) {
        var r = keyed(k, v);
        return ByteBuffer.allocate(4 + r.length).putInt(r.length).put(r).array();
    }

    LogTopics open() {
        var t = new LogTopics(this.dir, Durability.ASYNC, Map.of(), SEG, LogRetention.NONE, System::currentTimeMillis);
        t.compacted(Set.of("kv"));
        return t;
    }

    /** Fetches the whole topic from its start, stepping over compacted gaps; key -> last value seen, and the record count. */
    static Map<String, String> readAll(MessagingHttp h, LogTopics topics, int[] count) throws Exception {
        var out = new HashMap<String, String>();
        long next = topics.get("kv", false).log().startOffset();
        long end = topics.get("kv", false).writer().snapshot().nextOffset();
        int guard = 0;
        while (next < end) {
            var r = h.handle(new Request("GET", "/messaging/v1/topics/kv/records?offset=" + next + "&maxBytes=4096"));
            assertEquals(200, r.status());
            long n = Long.parseLong(r.headers().get("next-offset"));
            assertTrue(n > next, "fetch makes progress over gaps: " + next);
            for (var b : MessagingHttpTest.batches(MessagingHttpTest.bytes(r))) {
                for (var rec : b.records()) {
                    var k = LogTopics.key(rec);
                    var key = StandardCharsets.UTF_8.decode(k.duplicate()).toString();
                    out.put(key, new String(rec, 4 + k.remaining(), rec.length - 4 - k.remaining(), StandardCharsets.UTF_8));
                    count[0]++;
                }
            }
            next = n;
            assertTrue(++guard < 10_000);
        }
        return out;
    }

    @Test
    void sealedSegmentsKeepOnlyTheLatestValuePerKeyAndSurviveRestart() throws Exception {
        var expect = new HashMap<String, String>();
        int produced = 0;
        try (var topics = open()) {
            var h = new MessagingHttp(topics);
            for (int i = 0; i < 300; i++) {
                String k = "k" + (i % 10), v = "v" + i + "-" + "x".repeat(40);
                assertEquals(200, MessagingHttpTest.post(h, "kv", body(k, v), "group").status());
                expect.put(k, v);
                produced++;
            }
            int[] before = {0};
            assertEquals(expect, readAll(h, topics, before));
            var rep = topics.compact();
            assertTrue(rep.segments() > 0 && rep.reclaimedBytes() > 0, "compacted " + rep);
            int[] after = {0};
            assertEquals(expect, readAll(h, topics, after), "latest value per key survives");
            assertTrue(after[0] < before[0] / 3, "superseded values dropped: " + after[0] + " of " + before[0]);
            assertEquals(0, topics.compact().segments(), "idempotent");
            // produce continues; a new value supersedes a compacted one
            assertEquals(200, MessagingHttpTest.post(h, "kv", body("k3", "new"), "group").status());
            expect.put("k3", "new");
        }
        try (var topics = open()) {
            var h = new MessagingHttp(topics);
            assertEquals(200, MessagingHttpTest.post(h, "kv", body("k0", "after-restart"), "group").status());
            expect.put("k0", "after-restart");
            assertEquals(expect, readAll(h, topics, new int[1]));
        }
        assertTrue(produced == 300);
    }

    @Test
    void keyedTopicsRejectUnkeyedRecordsAndOtherTopicsAreUntouched() throws Exception {
        try (var topics = open()) {
            var h = new MessagingHttp(topics);
            assertEquals(400, MessagingHttpTest.post(h, "kv", MessagingHttpTest.recs("ab"), null).status()); // keyLen > rest
            assertEquals(200, MessagingHttpTest.post(h, "plain", MessagingHttpTest.recs("ab"), null).status());
            for (int i = 0; i < 100; i++) MessagingHttpTest.post(h, "plain", MessagingHttpTest.recs("y".repeat(60) + i), "group");
            assertEquals(0, topics.compact().segments(), "unkeyed topics are never compacted");
        }
    }

    @Test
    void keyParsing() {
        assertEquals(null, LogTopics.key(new byte[3]));
        assertEquals(null, LogTopics.key(new byte[] {0, 0, 0, 5, 1}));
        assertEquals(null, LogTopics.key(new byte[] {(byte) 0x80, 0, 0, 0}));
        assertEquals(0, LogTopics.key(new byte[] {0, 0, 0, 0}).remaining());
        assertEquals(ByteBuffer.wrap(new byte[] {7}), LogTopics.key(new byte[] {0, 0, 0, 1, 7, 9}));
        assertEquals(Set.of("a", "b"), LogTopics.parseTopics(" a, b,,"));
        assertEquals(List.of(), List.copyOf(LogTopics.parseTopics("")));
    }
}
