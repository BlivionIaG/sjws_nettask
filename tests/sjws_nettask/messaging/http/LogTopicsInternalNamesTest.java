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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import sjws_nettask.messaging.ledger.Durability;

/** #217: open/create rejects user topic names starting with {@code __}. */
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
}
