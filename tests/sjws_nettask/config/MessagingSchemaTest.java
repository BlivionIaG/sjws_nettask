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
package sjws_nettask.config;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.acme.ChallengeBoard;

/** Topic and group schema in the config snapshot, including destructive-change guards. */
class MessagingSchemaTest {

    @Test
    void roundTripKeepsTopicsAndOmitsADisabledSection(@TempDir Path dir) throws Exception {
        var root = Files.createDirectory(dir.resolve("root"));
        var plain = Files.writeString(dir.resolve("plain.yaml"), "server:\n  port: 0\n  root: " + root + "\n");
        var plainDoc = ConfigLoader.load(new String[] {"--config=" + plain}, Map.of()).document();
        assertTrue(plainDoc.messaging().omittedFromCanonical());
        assertFalse(plainDoc.canonicalText().contains("messaging:"));

        var yaml = """
                server:
                  port: 0
                  root: %s
                messaging:
                  enabled: true
                  defaults:
                    partitions: 3
                    replication: 1
                    durability: async
                  topics:
                    orders:
                      partitions: 6
                      replication: 1
                      durability: async
                      durability_opt_in: [strict, none]
                      groups:
                        billing:
                          start: earliest
                      allow_destructive: false
                  assignment:
                    "orders/0": [n1, n3]
                """.formatted(root);
        var file = Files.writeString(dir.resolve("msg.yaml"), yaml);
        var loaded = ConfigLoader.load(new String[] {"--config=" + file}, Map.of());
        assertTrue(loaded.ok(), loaded.errors().toString());
        var doc = loaded.document();
        assertTrue(doc.messaging().enabled());
        assertEquals(6, doc.messaging().topics().get("orders").partitions());
        assertEquals("earliest", doc.messaging().topics().get("orders").groups().get("billing").start());
        assertEquals(java.util.List.of("n1", "n3"), doc.messaging().assignment().get("orders/0"));
        assertEquals(1, doc.messaging().assignments().size());
        assertTrue(doc.messaging().assignments().get(0).replicas().contains("n1"));
        var again = ConfigLoader.load(new String[] {"--config=" + Files.writeString(dir.resolve("canon.yaml"),
                doc.canonicalText())}, Map.of());
        assertTrue(again.ok(), again.errors().toString());
        assertEquals(doc.messaging(), again.document().messaging());
    }

    @Test
    void destructiveChangesNeedConfirmationAndPartitionDecreaseStaysGated(@TempDir Path dir) throws Exception {
        var root = Files.createDirectory(dir.resolve("root"));
        var current = load(dir, root, topic("orders", 6, 1, false) + group("billing"));
        var board = new ChallengeBoard();
        var snapshot = ConfigSnapshot.compile(current, board, 1);
        var reloader = new ConfigReloader(ConfigLoader.load(new String[] {"--config=" + dir.resolve("doc.yaml")},
                Map.of()));

        var fewer = apply(reloader, snapshot, board, load(dir, root, topic("orders", 2, 1, true)));
        assertFalse(fewer.applied(), fewer.errors().toString());
        assertTrue(fewer.errors().get(0).contains("schema 1"));

        var factor = apply(reloader, snapshot, board, load(dir, root, topic("orders", 6, 3, false)));
        assertFalse(factor.applied());
        assertTrue(factor.errors().get(0).contains("replication"));

        var factorOk = apply(reloader, snapshot, board, load(dir, root, topic("orders", 6, 3, true)));
        assertTrue(factorOk.applied(), factorOk.errors().toString());

        var dropGroup = apply(reloader, snapshot, board, load(dir, root, topic("orders", 6, 1, false)));
        assertFalse(dropGroup.applied());
        assertTrue(dropGroup.errors().get(0).contains("group deletion"));

        var dropTopic = apply(reloader, snapshot, board, load(dir, root, "messaging:\n  enabled: true\n"));
        assertFalse(dropTopic.applied());
        assertTrue(dropTopic.errors().get(0).contains("topic deletion"));

        var marked = load(dir, root, topic("orders", 6, 1, true) + group("billing"));
        var markedSnap = ConfigSnapshot.compile(marked, board, 2);
        var dropped = apply(reloader, markedSnap, board, load(dir, root, "messaging:\n  enabled: true\n"));
        assertTrue(dropped.applied(), dropped.errors().toString());
    }

    @Test
    void diffIsPerTopicAndBadShapesFail(@TempDir Path dir) throws Exception {
        var root = Files.createDirectory(dir.resolve("root"));
        var left = load(dir, root, topic("orders", 4, 1, false) + group("billing"));
        var right = load(dir, root, topic("orders", 6, 1, false) + group("shipping"));
        var changes = ConfigDiff.between(left, right, false);
        assertTrue(changes.stream().anyMatch(change -> change.section().equals("topics/orders")
                && change.detail().contains("partitions")));
        assertTrue(changes.stream().anyMatch(change -> change.section().equals("topics/orders/groups/billing")));
        assertTrue(changes.stream().anyMatch(change -> change.section().equals("topics/orders/groups/shipping")));
        assertTrue(changes.stream().noneMatch(change -> change.section().equals("topics")));

        var bad = load(dir, root, """
                messaging:
                  enabled: true
                  topics:
                    orders:
                      durability: none
                      replication: 1
                """);
        var errors = ConfigValidator.validate(bad);
        assertTrue(errors.stream().anyMatch(error -> error.contains("durability")));

        assertThrows(ConfigException.class, () -> load(dir, root, """
                messaging:
                  enabled: true
                  topics:
                    orders:
                      retry:
                        mystery: 1
                """));
    }

    private static ReloadResult apply(ConfigReloader reloader, ConfigSnapshot current, ChallengeBoard board,
            ConfigDocument incoming) {
        return reloader.apply(current, incoming, false, board, true);
    }

    private static ConfigDocument load(Path dir, Path root, String messaging) throws Exception {
        var file = dir.resolve("doc.yaml");
        Files.writeString(file, "server:\n  port: 0\n  root: " + root + "\n" + messaging);
        var loaded = ConfigLoader.load(new String[] {"--config=" + file}, Map.of());
        if (!loaded.ok()) {
            throw new ConfigException(loaded.errors().toString());
        }
        return loaded.document();
    }

    private static String topic(String name, int partitions, int replication, boolean destructive) {
        return """
                messaging:
                  enabled: true
                  topics:
                    %s:
                      partitions: %d
                      replication: %d
                      durability: %s
                      allow_destructive: %s
                """.formatted(name, partitions, replication, replication == 1 ? "async" : "replicated", destructive);
    }

    private static String group(String name) {
        return """
                      groups:
                        %s:
                          start: earliest
                """.formatted(name);
    }
}
