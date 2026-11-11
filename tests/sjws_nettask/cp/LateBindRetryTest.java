/*
 * Copyright (C) 2026 BlivionIaG <BlivionIaG at chenco.tk>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package sjws_nettask.cp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.HttpTestSupport;
import sjws_nettask.SjwsServer;
import sjws_nettask.config.ConfigLoader;

/** A content bind that fails while the port is busy is retried on a later watch tick. */
@Timeout(30)
class LateBindRetryTest {

    @TempDir
    Path dir;

    @Test
    void aFailedLateBindIsRetriedOnTheNextWatchTick() throws Exception {
        var html = Files.createDirectory(dir.resolve("html"));
        Files.writeString(html.resolve("index.html"), "ok");
        var state = dir.resolve("state");
        var cp = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        cp.createContext("/cp/v1/watch", exchange -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        cp.start();
        var busy = new ServerSocket(0);
        try {
            int port = busy.getLocalPort();
            var yaml = dir.resolve("sjws.yaml");
            Files.writeString(yaml, "schema: 1\nnode:\n  mode: data\n  state_dir: " + state.toAbsolutePath()
                    + "\nserver:\n  port: " + port + "\n  root: " + html.toAbsolutePath() + "\n");
            var loaded = ConfigLoader.load(new String[] {"--config=" + yaml}, Map.of());
            assertTrue(loaded.ok(), loaded.errors().toString());
            var fixture = DataReadinessTest.persistVerifiedLastGoodWithKeys(dir, state, loaded.document(), 3);
            try (var server = SjwsServer.start(loaded, null, false, fixture.pin());
                    var watch = DataWatch.open("http://127.0.0.1:" + cp.getAddress().getPort(), "node-1", "tok",
                            "secret".getBytes(StandardCharsets.UTF_8), fixture.pin(), state, server, null,
                            DataPlaneGuard.WALL_CLOCK)) {
                assertTrue(server.dataPlane().hasLastGood());
                assertFalse(server.contentBound(), "the port is still taken");
                busy.close();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (!server.contentBound() && System.nanoTime() < deadline) {
                    Thread.sleep(25);
                }
                assertTrue(server.contentBound(), "a later watch tick binds once the port is free");
                assertEquals("ok", HttpTestSupport.get(server.tcpPort(), "/index.html", 2_000).bodyText());
            }
        } finally {
            busy.close();
            cp.stop(0);
        }
    }
}
