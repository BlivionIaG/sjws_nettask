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

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sjws_nettask.core.Request;
import sjws_nettask.core.RequestHeaders;
import sjws_nettask.core.Response;
import sjws_nettask.messaging.ledger.Durability;

/** Committed consumer offsets (durable-log-plan §2). */
class ConsumerOffsetsTest {

    @TempDir Path dir;
    static final long SEG = 512;
    final AtomicLong now = new AtomicLong(1_000_000_000L);

    LogTopics open(LogRetention retention) {
        return new LogTopics(this.dir, Durability.ASYNC, SEG, retention, this.now::get);
    }

    static Response put(MessagingHttp h, String topic, String group, String body) {
        return h.handle(new Request("PUT", "/messaging/v1/topics/" + topic + "/offsets/" + group, RequestHeaders.of(Map.of()))
                .withBody(MessagingHttpTest.body(body.getBytes(StandardCharsets.US_ASCII))));
    }

    static Response get(MessagingHttp h, String topic, String group) {
        return h.handle(new Request("GET", "/messaging/v1/topics/" + topic + "/offsets/" + group));
    }

    static String text(Response r) {
        return new String(MessagingHttpTest.bytes(r), StandardCharsets.UTF_8);
    }

    @Test
    void commitReadAndValidate() throws Exception {
        try (var topics = open(LogRetention.NONE)) {
            var h = new MessagingHttp(topics);
            LogRetentionTest.fill(h, "t", 10);
            assertEquals(404, get(h, "t", "g").status());
            assertEquals(404, put(h, "nope", "g", "{\"offset\":0}").status());
            var r = put(h, "t", "g", "{\"offset\":4}");
            assertEquals(200, r.status());
            assertTrue(text(r).startsWith("{\"offset\":4,"), text(r));
            assertTrue(text(get(h, "t", "g")).contains("\"committedAtMs\":" + this.now.get()));
            assertEquals(200, put(h, "t", "g", " { \"offset\" : 10 } ").status()); // = log end: allowed
            assertTrue(text(get(h, "t", "g")).startsWith("{\"offset\":10,"));
            assertEquals(400, put(h, "t", "g", "{\"offset\":11}").status()); // past the end
            assertEquals(400, put(h, "t", "g", "{\"offset\":-1}").status());
            assertEquals(400, put(h, "t", "g", "oops").status());
            assertEquals(400, put(h, "t", "bad group", "{\"offset\":1}").status());
            assertEquals(400, put(h, "__offsets", "g", "{\"offset\":0}").status());
            assertEquals(405, h.handle(new Request("POST", "/messaging/v1/topics/t/offsets/g")).status());
            // the internal topic is not produce/fetchable
            assertEquals(400, MessagingHttpTest.post(h, "__offsets", MessagingHttpTest.recs("x"), null).status());
            assertEquals(400, h.handle(new Request("GET", "/messaging/v1/topics/__offsets/records?offset=0")).status());
        }
    }

    @Test
    void commitsAreDurableAcrossRestartAndTheLatestWins() throws Exception {
        try (var topics = open(LogRetention.NONE)) {
            var h = new MessagingHttp(topics);
            LogRetentionTest.fill(h, "t", 20);
            for (int i = 0; i <= 20; i++) assertEquals(200, put(h, "t", "a", "{\"offset\":" + i + "}").status());
            assertEquals(200, put(h, "t", "b", "{\"offset\":3}").status());
            topics.compact(); // __offsets is compacted: one record per (topic, group) survives in sealed segments
        }
        try (var topics = open(LogRetention.NONE)) {
            var h = new MessagingHttp(topics);
            assertTrue(text(get(h, "t", "a")).startsWith("{\"offset\":20,"));
            assertTrue(text(get(h, "t", "b")).startsWith("{\"offset\":3,"));
            assertEquals(2, topics.offsets().all().get("t").size());
        }
    }

    @Test
    void aCommittedGroupIsARetentionFloorUntilItExpires() throws Exception {
        try (var topics = open(new LogRetention(2 * SEG, 0))) {
            topics.offsetsExpiryMs(60_000);
            var h = new MessagingHttp(topics);
            LogRetentionTest.fill(h, "t", 60);
            assertEquals(200, put(h, "t", "slow", "{\"offset\":2}").status());
            var log = topics.get("t", false).log();
            int before = log.segmentCount();
            assertEquals(0, topics.enforceRetention(), "history from offset 2 is pinned");
            assertEquals(before, log.segmentCount());
            assertTrue(log.startOffset() <= 2);
            assertEquals(200, put(h, "t", "slow", "{\"offset\":30}").status()); // the group moves on
            assertTrue(topics.enforceRetention() > 0);
            assertTrue(log.startOffset() <= 30, "start " + log.startOffset());
            this.now.addAndGet(120_000); // the commit expires: retention is size-only again
            assertTrue(topics.enforceRetention() >= 0);
            assertTrue(log.totalBytes() <= 3 * SEG, "bytes " + log.totalBytes());
        }
    }
}
