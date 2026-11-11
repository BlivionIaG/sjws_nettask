/*
 * Copyright (C) 2026 BlivionIaG <BlivionIaG at chenco.tk>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package sjws_nettask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.acme.ChallengeBoard;
import sjws_nettask.config.ConfigDocument;
import sjws_nettask.config.ConfigLoader;
import sjws_nettask.config.ConfigSnapshot;
import sjws_nettask.core.Handler;
import sjws_nettask.core.Request;

/** On a non-HA data node each HTTP/2 generation pins its own snapshot behind the readiness check. */
class Http2GenerationLeafTest {

    @TempDir
    Path dir;

    @Test
    void anHttp2GenerationPinsItsOwnSnapshotOnADataNode() throws Exception {
        var first = Files.createDirectory(dir.resolve("first"));
        Files.writeString(first.resolve("first.html"), "first");
        var second = Files.createDirectory(dir.resolve("second"));
        Files.writeString(second.resolve("second.html"), "second");
        var state = dir.resolve("state");
        var yaml = dir.resolve("sjws.yaml");
        Files.writeString(yaml, "schema: 1\nnode:\n  mode: data\n  state_dir: " + state.toAbsolutePath()
                + "\nserver:\n  port: 0\n  root: " + first.toAbsolutePath() + "\n");
        var loaded = ConfigLoader.load(new String[] {"--config=" + yaml}, Map.of());
        assertTrue(loaded.ok(), loaded.errors().toString());
        var doc = loaded.document();
        var next = ConfigSnapshot.compile(ConfigDocument.of(doc.server().toBuilder().root(second).build(), doc.acme(),
                doc.routing(), doc.middleware(), doc.access(), doc.node(), doc.schemaVersion(), 2, doc.configFile())
                .withStateDir(state), new ChallengeBoard(), 2);
        try (var server = SjwsServer.start(loaded, null, false)) {
            Method terminal = SjwsServer.class.getDeclaredMethod("publishTerminal", ConfigSnapshot.class);
            terminal.setAccessible(true);
            var leaf = (Handler) terminal.invoke(server, next);
            assertInstanceOf(DataReadyHandler.class, leaf);
            assertEquals(200, leaf.handle(new Request("GET", "/second.html")).status(),
                    "the generation serves the snapshot it was armed with, not the live one");
            assertEquals(404, leaf.handle(new Request("GET", "/first.html")).status());
            assertEquals(503, leaf.handle(new Request("GET", "/readyz")).status());
            assertEquals(200, server.requestHandler().handle(new Request("GET", "/first.html")).status());
        }
    }
}
