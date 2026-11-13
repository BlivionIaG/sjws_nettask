/*
 * Copyright (C) 2026 BlivionIaG <BlivionIaG at chenco.tk>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package sjws_nettask.cp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.SjwsServer;
import sjws_nettask.config.ConfigDocument;
import sjws_nettask.config.ConfigLoader;
import sjws_nettask.config.ConfigReloader;
import sjws_nettask.config.NodeSettings;

/** The local {@code node.max_staleness} fallback follows the YAML on reload, not only at startup. */
class LocalMaxStalenessReloadTest {

    @TempDir
    Path dir;

    @Test
    void aReloadedLocalCeilingAppliesUnlessTheSnapshotNamesOne() throws Exception {
        var html = Files.createDirectory(dir.resolve("html"));
        long now = System.currentTimeMillis();
        var yaml = dir.resolve("guard.yaml");
        Files.writeString(yaml, "schema: 1\nnode:\n  mode: data\n  max_staleness: 1h\n  state_dir: "
                + dir.resolve("s").toAbsolutePath() + "\nserver:\n  port: 0\n  root: " + html.toAbsolutePath() + "\n");
        var guard = DataPlaneGuard.forDocument(ConfigLoader.load(new String[] {"--config=" + yaml}, Map.of()).document());
        guard.applied(DataPlaneGuardTest.snapshot(2, now - Duration.ofMinutes(30).toMillis(), now + 3_600_000L), true);
        assertFalse(guard.pastMaxStaleness(), "30 min under the 1h local ceiling");

        guard.pushedMaxStaleness(Duration.ofHours(2));
        guard.localMaxStaleness(Duration.ofMinutes(15));
        assertFalse(guard.pastMaxStaleness(), "the snapshot's own 2h ceiling still applies");
        guard.pushedMaxStaleness(null);
        assertTrue(guard.pastMaxStaleness(), "falls back to the reloaded 15m ceiling");

        guard.localMaxStaleness(null);
        assertFalse(guard.pastMaxStaleness(), "no local ceiling: only signed expiry applies");
        guard.localMaxStaleness(Duration.ofMinutes(15));
        assertTrue(guard.pastMaxStaleness());

        DataPlaneGuard.inactive().localMaxStaleness(Duration.ofMinutes(1));
        assertFalse(DataPlaneGuard.inactive().pastMaxStaleness());
    }

    @Test
    void sighupAfterLastGoodReResolvesTheLocalCeiling() throws Exception {
        var local = Files.createDirectory(dir.resolve("html-local"));
        Files.writeString(local.resolve("index.html"), "local");
        var cached = Files.createDirectory(dir.resolve("html-cached"));
        Files.writeString(cached.resolve("index.html"), "cached");
        var state = dir.resolve("state");
        var yaml = dir.resolve("node.yaml");
        Files.writeString(yaml, nodeYaml(state, local, "1h"));
        var loaded = ConfigLoader.load(new String[] {"--config=" + yaml}, Map.of());
        var doc = loaded.document();
        var noCeiling = new NodeSettings(NodeSettings.Mode.DATA, doc.node().labels(), null);
        var cachedDoc = ConfigDocument.of(doc.server().toBuilder().root(cached).build(), doc.acme(), doc.routing(),
                doc.middleware(), doc.access(), noCeiling, doc.schemaVersion(), 5, doc.configFile()).withStateDir(state);
        var pin = DataReadinessTest.persistVerifiedLastGood(dir, state, cachedDoc, 5);
        try (var server = SjwsServer.start(loaded, null, true, pin)) {
            assertTrue(server.dataPlane().hasLastGood());
            long later = System.currentTimeMillis() + Duration.ofMinutes(30).toMillis();
            server.dataPlane().clock(() -> later);
            assertFalse(server.dataPlane().pastMaxStaleness(), "the snapshot names none: the local 1h applies");

            Files.writeString(yaml, nodeYaml(state, local, "15m"));
            server.reload();
            assertTrue(server.dataPlane().pastMaxStaleness(), "the reloaded 15m ceiling applies");

            Files.writeString(yaml, nodeYaml(state, local, "forever"));
            server.reload();
            assertTrue(server.dataPlane().pastMaxStaleness(), "an unreadable file keeps the last local ceiling");

            Files.delete(yaml);
            server.reload();
            assertTrue(server.dataPlane().pastMaxStaleness(), "a missing file keeps the last local ceiling");
        }
        assertNull(new ConfigReloader(loaded).readLocal(null));
    }

    @Test
    void aLocalReloadBeforeAnyPushUpdatesTheCeiling() throws Exception {
        var html = Files.createDirectory(dir.resolve("html-plain"));
        Files.writeString(html.resolve("index.html"), "plain");
        var state = dir.resolve("plain-state");
        var yaml = dir.resolve("plain.yaml");
        Files.writeString(yaml, nodeYaml(state, html, "1h"));
        var loaded = ConfigLoader.load(new String[] {"--config=" + yaml}, Map.of());
        try (var server = SjwsServer.start(loaded, null, true)) {
            long now = System.currentTimeMillis();
            server.dataPlane().applied(DataPlaneGuardTest.snapshot(2, now - Duration.ofMinutes(30).toMillis(),
                    now + 3_600_000L), true);
            assertFalse(server.dataPlane().pastMaxStaleness());
            Files.writeString(yaml, nodeYaml(state, html, "15m"));
            var result = server.reload();
            assertTrue(result.applied(), result.errors().toString());
            assertTrue(server.dataPlane().pastMaxStaleness(), "the reloaded 15m ceiling applies");
        }
    }

    private static String nodeYaml(Path state, Path root, String maxStaleness) {
        return "schema: 1\nnode:\n  mode: data\n  max_staleness: " + maxStaleness + "\n  state_dir: "
                + state.toAbsolutePath() + "\nserver:\n  port: 0\n  root: " + root.toAbsolutePath() + "\n";
    }
}
