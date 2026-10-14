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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sjws_nettask.core.Request;
import sjws_nettask.core.RequestHeaders;
import sjws_nettask.core.Response;
import sjws_nettask.messaging.ledger.Durability;

class LogOffsetsTest {

    @TempDir Path dir;

    static final long SEG = 512;

    static Response put(MessagingHttp h, String topic, String group, String body, String durability) {
        var headers = durability == null ? RequestHeaders.of(Map.of()) : RequestHeaders.of(Map.of("durability", List.of(durability)));
        return h.handle(new Request("PUT", "/messaging/v1/topics/" + topic + "/offsets/" + group, headers)
                .withBody(MessagingHttpTest.body(body.getBytes(StandardCharsets.UTF_8))));
    }

    static Response put(MessagingHttp h, String topic, String group, long off) {
        return put(h, topic, group, "{\"offset\":" + off + "}", null);
    }

    static Response get(MessagingHttp h, String topic, String group) {
        return h.handle(new Request("GET", "/messaging/v1/topics/" + topic + "/offsets/" + group));
    }

    static String text(Response r) {
        return new String(MessagingHttpTest.bytes(r), StandardCharsets.UTF_8);
    }

    LogTopics topics(LogRetention retention, long expiryMs, AtomicLong now) {
        return new LogTopics(dir, Durability.ASYNC, Map.of(), SEG, retention, Set.of(), expiryMs, now::get);
    }

    @Test
    void commitThenGetAndRestartReturnsTheCommittedOffset() throws Exception {
        var now = new AtomicLong(1_000_000);
        try (var topics = topics(LogRetention.NONE, LogOffsets.DEFAULT_EXPIRY_MS, now)) {
            var h = new MessagingHttp(topics);
            LogRetentionTest.fill(h, "t", 10);
            assertEquals(404, get(h, "t", "g").status());
            var r = put(h, "t", "g", 4);
            assertEquals(200, r.status(), text(r));
            assertEquals("group", r.headers().get("durability-effective")); // __offsets default
            assertEquals(200, put(h, "t", "g", 7).status());
            assertEquals(200, put(h, "t", "other", 10).status()); // = log end is allowed
            var g = get(h, "t", "g");
            assertEquals(200, g.status());
            assertEquals("{\"offset\":7,\"lag\":3}", text(g));
            assertTrue(topics.get(LogOffsets.TOPIC, false).topicCompacted());
        }
        try (var topics = topics(LogRetention.NONE, LogOffsets.DEFAULT_EXPIRY_MS, now)) {
            var h = new MessagingHttp(topics);
            assertEquals("{\"offset\":7,\"lag\":3}", text(get(h, "t", "g")));
            assertEquals("{\"offset\":10,\"lag\":0}", text(get(h, "t", "other")));
        }
    }

    @Test
    void validation() throws Exception {
        try (var topics = topics(new LogRetention(SEG, 0), LogOffsets.DEFAULT_EXPIRY_MS, new AtomicLong())) {
            var h = new MessagingHttp(topics);
            assertEquals(404, put(h, "none", "g", 0).status());
            LogRetentionTest.fill(h, "t", 60);
            assertTrue(topics.enforceRetention() > 0);
            long start = topics.get("t", false).log().startOffset();
            assertEquals(416, put(h, "t", "g", start - 1).status());
            assertEquals(400, put(h, "t", "g", 61).status()); // past the log end (60)
            assertEquals(400, put(h, "t", "g", "{\"off\":1}", null).status());
            assertEquals(400, put(h, "t", "g", "not json", null).status());
            assertEquals(400, put(h, "t", "bad%2Fgroup", 1).status());
            assertEquals(400, put(h, "__offsets", "g", 0).status());
            assertEquals(400, MessagingHttpTest.post(h, "__offsets", MessagingHttpTest.recs("x"), null).status());
            assertEquals(405, h.handle(new Request("DELETE", "/messaging/v1/topics/t/offsets/g")).status());
            var r = put(h, "t", "g", "{\"offset\":" + start + "}", "none");
            assertEquals(200, r.status());
            assertEquals("group", r.headers().get("durability-effective")); // header may only strengthen
        }
    }

    @Test
    void aCommittedButUnfetchedGroupPinsRetentionAndExpiryReleasesIt() throws Exception {
        var now = new AtomicLong(1_000_000);
        try (var topics = topics(new LogRetention(2 * SEG, 0), 60_000, now)) {
            var h = new MessagingHttp(topics);
            LogRetentionTest.fill(h, "t", 60);
            assertEquals(200, put(h, "t", "slow", 3).status());
            assertEquals(0, topics.enforceRetention(), "offset 3 lives in the first segment: nothing may go");
            assertEquals(0, topics.get("t", false).log().startOffset());
            assertEquals(200, put(h, "t", "slow", 30).status());
            assertTrue(topics.enforceRetention() > 0);
            long start = topics.get("t", false).log().startOffset();
            assertTrue(start > 0 && start <= 30, "start " + start);
            assertEquals(200, put(h, "t", "slow", start).status());
            now.addAndGet(60_000); // the group goes quiet past expiry
            assertTrue(topics.enforceRetention() > 0);
            assertTrue(topics.get("t", false).log().startOffset() > start);
        }
    }

    @Test
    void floorKeepsCompactionBelowTheOldestCommittedOffset() throws Exception {
        var now = new AtomicLong(1_000_000);
        try (var topics = new LogTopics(dir, Durability.ASYNC, Map.of(), SEG, LogRetention.NONE, Set.of("k"), 60_000, now::get)) {
            var h = new MessagingHttp(topics);
            LogCompactionTest.fill(h, "k", 80, 3);
            assertEquals(200, put(h, "k", "g", 0).status());
            long bytes = topics.get("k", false).log().totalBytes();
            topics.compact();
            assertEquals(bytes, topics.get("k", false).log().totalBytes(), "a consumer at 0 pins every segment");
            assertEquals(200, put(h, "k", "g", 80).status());
            assertTrue(topics.compact().segmentsRewritten() > 0);
        }
    }

    @Test
    void offsetsTopicIsCompactedAndNotSubjectToRetention() throws Exception {
        var now = new AtomicLong(1_000_000);
        try (var topics = topics(new LogRetention(SEG, 1), 60_000, now)) {
            var h = new MessagingHttp(topics);
            LogRetentionTest.fill(h, "t", 5);
            for (int i = 0; i < 200; i++) assertEquals(200, put(h, "t", "g" + (i % 3), i % 6).status());
            var off = topics.get(LogOffsets.TOPIC, false).log();
            assertTrue(off.segmentCount() > 2);
            now.addAndGet(10);
            topics.enforceRetention();
            assertEquals(0, off.startOffset(), "retention never deletes __offsets");
            assertTrue(topics.compact().segmentsRewritten() > 0);
        }
        try (var topics = topics(LogRetention.NONE, 60_000, now)) {
            var h = new MessagingHttp(topics);
            assertEquals("{\"offset\":" + (197 % 6) + ",\"lag\":" + (5 - 197 % 6) + "}", text(get(h, "t", "g2")));
        }
    }

    @Test
    void noOffsetsMeansNothingOpenedAndNoThread() throws Exception {
        var m = new MessagingModule(dir);
        try {
            assertFalse(m.retentionScheduled());
            var h = new MessagingHttp(m.topics());
            LogRetentionTest.fill(h, "t", 3);
            assertFalse(Files.exists(dir.resolve(LogOffsets.TOPIC)));
            assertEquals(200, put(h, "t", "g", 1).status());
            assertTrue(m.retentionScheduled(), "first commit starts maintenance (__offsets compaction)");
        } finally {
            m.close();
        }
        var again = new MessagingModule(dir);
        assertTrue(again.retentionScheduled(), "existing __offsets: maintenance from the start");
        again.close();
    }

    @Test
    void hookSeesTheLag() throws Exception {
        try (var topics = topics(LogRetention.NONE, 60_000, new AtomicLong())) {
            var h = new MessagingHttp(topics);
            var seen = new AtomicLong(-1);
            h.hooks(new MessagingHooks() {
                @Override public void produced(String t, int s, long b, long n) { }
                @Override public void fetched(String t, int s, long b, long n) { }
                @Override public void offsetCommitted(String t, String g, long lag) { seen.set(lag); }
            });
            LogRetentionTest.fill(h, "t", 9);
            put(h, "t", "g", 2);
            assertEquals(7, seen.get());
        }
    }
}
