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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.config.ConfigLoader;
import sjws_nettask.core.Handler;
import sjws_nettask.core.Request;

/** Turning metrics on later arms HTTP/2 with a generation pinned to the snapshot live at that time. */
class ArmedHttp2GenerationTest {

    @TempDir
    Path dir;

    @Test
    void theMetricsArmPinsTheCurrentSnapshotForHttp2() throws Exception {
        var first = Files.createDirectory(dir.resolve("first"));
        Files.writeString(first.resolve("first.html"), "first");
        var second = Files.createDirectory(dir.resolve("second"));
        Files.writeString(second.resolve("second.html"), "second");
        var yaml = dir.resolve("sjws.yaml");
        Files.writeString(yaml, "server:\n  port: 0\n  root: " + first.toAbsolutePath() + "\n");
        var lastArmed = new AtomicReference<Handler>();
        Transport fakeH2 = new Transport() {
            @Override public String name() { return "fake-h2"; }
            @Override public Transport start() { return this; }
            @Override public int port() { return 0; }
            @Override public void close() { }
            @Override public void armHandler(Handler handler) { lastArmed.set(handler); }
        };
        Transport.Factory h3Unused = (config, handler) -> {
            throw new AssertionError("h3 is disabled");
        };
        var loaded = ConfigLoader.load(new String[] {"--config=" + yaml, "--h2", "--h2-port=0", "--h2c-port=0",
                "--no-h3"}, Map.of());
        assertTrue(loaded.ok(), loaded.errors().toString());
        try (var server = SjwsServer.start(loaded, h3Unused, (c, h) -> fakeH2, null, null)) {
            server.armMetrics();
            var armedAtMetrics = lastArmed.get();
            assertNotNull(armedAtMetrics, "turning metrics on armed HTTP/2");
            server.armMetrics();
            assertEquals(armedAtMetrics, lastArmed.get(), "a second arm keeps the generation");

            Files.writeString(yaml, "server:\n  port: 0\n  root: " + second.toAbsolutePath() + "\n");
            assertTrue(server.reload().applied());
            assertNotSame(armedAtMetrics, lastArmed.get(), "the reload published a new generation");
            assertEquals(200, armedAtMetrics.handle(new Request("GET", "/first.html")).status(),
                    "a task holding the earlier generation keeps its snapshot");
            assertEquals(404, armedAtMetrics.handle(new Request("GET", "/second.html")).status());
            assertEquals(200, lastArmed.get().handle(new Request("GET", "/second.html")).status());
        }
    }
}
