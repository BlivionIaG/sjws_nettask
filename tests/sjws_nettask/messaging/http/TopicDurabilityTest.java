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
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sjws_nettask.messaging.ledger.Durability;
import sjws_nettask.messaging.ledger.LedgerWriter;

class TopicDurabilityTest {

    @TempDir Path dir;

    @Test
    void parsesModesAndOverrideLists() {
        assertEquals(Durability.NONE, LogTopics.parseDurability(" None "));
        assertEquals(Durability.ASYNC, LogTopics.parseDurability("async"));
        assertEquals(Durability.GROUP, LogTopics.parseDurability("GROUP"));
        assertEquals(Durability.EACH, LogTopics.parseDurability("each"));
        assertThrows(IllegalArgumentException.class, () -> LogTopics.parseDurability("fsync"));
        assertEquals(Map.of("orders", Durability.GROUP, "logs.v1", Durability.NONE),
                LogTopics.parseTopicDurability("orders:group, logs.v1:none,"));
        assertEquals(Map.of(), LogTopics.parseTopicDurability(""));
        assertThrows(IllegalArgumentException.class, () -> LogTopics.parseTopicDurability("orders"));
        assertThrows(IllegalArgumentException.class, () -> LogTopics.parseTopicDurability("../x:group"));
        assertThrows(IllegalArgumentException.class, () -> LogTopics.parseTopicDurability("orders:maybe"));
    }

    @Test
    void moduleFallsBackOnBadSettings() {
        assertEquals(LedgerWriter.DEFAULT_DURABILITY, MessagingModule.durability(""));
        assertEquals(LedgerWriter.DEFAULT_DURABILITY, MessagingModule.durability("bogus"));
        assertEquals(Durability.GROUP, MessagingModule.durability("group"));
        assertEquals(Map.of(), MessagingModule.topicDurability("a:bogus"));
        assertEquals(Map.of("a", Durability.EACH), MessagingModule.topicDurability("a:each"));
    }

    @Test
    void eachTopicWriterUsesItsOverrideElseTheDefault() throws Exception {
        var m = new MessagingModule(dir, 1 << 20, LogRetention.NONE, 10_000, Durability.NONE, Map.of("safe", Durability.GROUP));
        try {
            var t = m.topics();
            assertEquals(Durability.GROUP, t.durability("safe"));
            assertEquals(Durability.NONE, t.durability("fast"));
            var h = new MessagingHttp(t);
            assertEquals(200, MessagingHttpTest.post(h, "safe", MessagingHttpTest.recs("a"), null).status());
            assertEquals(200, MessagingHttpTest.post(h, "fast", MessagingHttpTest.recs("b"), null).status());
            assertEquals(Durability.GROUP, t.get("safe", false).writer().durability());
            assertEquals(Durability.NONE, t.get("fast", false).writer().durability());
            // a GROUP-default topic's acks are fsynced: durable offset has caught up when the produce returns
            var snap = t.get("safe", false).writer().snapshot();
            assertEquals(snap.nextOffset(), snap.durableOffset());
        } finally {
            m.close();
        }
    }

    @Test
    void requestHeaderCanOnlyStrengthenTheTopicPolicy() throws Exception {
        assertEquals(Durability.GROUP, MessagingHttp.atLeast(Durability.NONE, Durability.GROUP));  // clamped up
        assertEquals(Durability.GROUP, MessagingHttp.atLeast(Durability.ASYNC, Durability.GROUP));
        assertEquals(Durability.GROUP, MessagingHttp.atLeast(Durability.GROUP, Durability.NONE));  // strengthening is kept
        assertEquals(Durability.NONE, MessagingHttp.atLeast(Durability.NONE, Durability.NONE));
        assertEquals(null, MessagingHttp.atLeast(null, Durability.GROUP));                         // no header: writer default
        var m = new MessagingModule(dir, 1 << 20, LogRetention.NONE, 10_000, Durability.ASYNC, Map.of("safe", Durability.GROUP));
        try {
            var h = new MessagingHttp(m.topics());
            for (int i = 0; i < 20; i++) {
                // "none" is not rejected, it is clamped: the ack still waits for the fsync of the GROUP topic
                var r = MessagingHttpTest.post(h, "safe", MessagingHttpTest.recs("v" + i), "none");
                assertEquals(200, r.status());
                assertEquals("group", r.headers().get("durability-effective")); // the client can see the clamp
                var snap = m.topics().get("safe", false).writer().snapshot();
                assertEquals(snap.nextOffset(), snap.durableOffset());
            }
        } finally {
            m.close();
        }
    }

    @Test
    void effectiveDurabilityHeaderNamesTheModeThatApplied() throws Exception {
        var m = new MessagingModule(dir, 1 << 20, LogRetention.NONE, 10_000, Durability.ASYNC, Map.of("safe", Durability.GROUP));
        try {
            var h = new MessagingHttp(m.topics());
            assertEquals("async", MessagingHttpTest.post(h, "plain", MessagingHttpTest.recs("a"), null).headers().get("durability-effective"));
            assertEquals("async", MessagingHttpTest.post(h, "plain", MessagingHttpTest.recs("b"), "none").headers().get("durability-effective")); // clamped up to the async default
            assertEquals("group", MessagingHttpTest.post(h, "plain", MessagingHttpTest.recs("c"), "group").headers().get("durability-effective"));
            assertEquals("group", MessagingHttpTest.post(h, "safe", MessagingHttpTest.recs("d"), null).headers().get("durability-effective"));
            assertEquals("group", MessagingHttpTest.post(h, "safe", MessagingHttpTest.recs("e"), "async").headers().get("durability-effective"));
        } finally {
            m.close();
        }
    }
}
