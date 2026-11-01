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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import sjws_nettask.core.Request;
import sjws_nettask.messaging.ledger.Durability;
import sjws_nettask.messaging.ledger.LedgerWriter;
import sjws_nettask.messaging.ledger.SegmentedLog;

/** Reserved {@code __*} topic checks (#217 / #433 follow-up). */
@Timeout(30)
class LogTopicsInternalNamesTest {

    @TempDir
    Path dir;

    @Test
    void validUserTopicRejectsDoubleUnderscorePrefix() {
        assertTrue(LogTopics.validUserTopic("orders"));
        assertFalse(LogTopics.validUserTopic("__offsets"));
        assertFalse(LogTopics.validUserTopic("__evil"));
        assertTrue(LogTopics.validName("__offsets")); // pattern-ok; reserved separately
    }

    @Test
    void createRejectsUserDoubleUnderscoreTopics() throws Exception {
        try (var topics = new LogTopics(dir.resolve("logs"), Durability.ASYNC)) {
            var err = assertThrows(IllegalArgumentException.class, () -> topics.get("__evil", true));
            assertTrue(err.getMessage().contains("internal"), err.getMessage());
            assertNotNull(topics.get(LogOffsets.TOPIC, true));
            assertNotNull(topics.get(LogOffsets.LOCAL_TOPIC, true));
        }
    }

    /** N1: GET refuses a reserved name even when an on-disk directory already exists. */
    @Test
    void httpGetRefusesReservedTopicWhenDirectoryExists() throws Exception {
        var logs = dir.resolve("logs");
        // Pre-create a directory that LogTopics.get(..., false) would otherwise open for read.
        try (var log = SegmentedLog.open(logs.resolve("__evil"));
                var w = new LedgerWriter(log, Durability.ASYNC)) {
            w.append(List.of("x".getBytes(StandardCharsets.UTF_8))).get();
        }
        try (var topics = new LogTopics(logs, Durability.ASYNC)) {
            var h = new MessagingHttp(topics);
            var get = h.handle(new Request("GET", "/messaging/v1/topics/__evil/records"));
            var getBody = new String(MessagingHttpTest.bytes(get), StandardCharsets.UTF_8);
            assertEquals(400, get.status(), getBody);
            assertTrue(getBody.contains("internal"), getBody);
            assertEquals(400, MessagingHttpTest.post(h, "__evil", MessagingHttpTest.recs("y"), null).status());
            assertEquals(400, MessagingHttpTest.post(h, "__offsets", MessagingHttpTest.recs("y"), null).status());
            // Internal offsets topics still open for the module itself.
            assertNotNull(topics.get(LogOffsets.TOPIC, true));
        }
    }

    /** N2: HTTP doors go through {@link LogTopics#validUserTopic}. */
    @Test
    void httpOffsetsAlsoUseValidUserTopic() throws Exception {
        try (var topics = new LogTopics(dir.resolve("logs"), Durability.ASYNC)) {
            var h = new MessagingHttp(topics);
            assertEquals(400, h.handle(new Request("GET", "/messaging/v1/topics/__evil/offsets/g")).status());
            assertEquals(400, h.handle(new Request("PUT", "/messaging/v1/topics/__offsets/offsets/g")
                    .withBody(MessagingHttpTest.body("{\"offset\":0}".getBytes(StandardCharsets.UTF_8)))).status());
        }
    }
}
