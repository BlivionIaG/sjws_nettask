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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.config.ConfigLoader;

/** A shutdown timeout set before the content port binds reaches the transports the bind creates. */
@Timeout(20)
class LateBindShutdownTimeoutTest {

    @TempDir
    Path dir;

    @Test
    void lateBoundTransportsGetTheStoredShutdownTimeout() throws Exception {
        var html = Files.createDirectory(dir.resolve("html"));
        Files.writeString(html.resolve("index.html"), "ok");
        var cert = dir.resolve("cert.pem");
        var key = dir.resolve("key.pem");
        mintLocalhostPem(cert, key);
        var yaml = dir.resolve("sjws.yaml");
        Files.writeString(yaml, "schema: 1\nnode:\n  mode: data\n  state_dir: " + dir.resolve("state").toAbsolutePath()
                + "\nserver:\n  port: 0\n  root: " + html.toAbsolutePath() + "\n");
        var loaded = ConfigLoader.load(new String[] {"--config=" + yaml, "--h2", "--h2-port=0", "--h2c-port=0",
                "--no-h3", "--cert=" + cert, "--key=" + key}, Map.of());
        assertTrue(loaded.ok(), loaded.errors().toString());
        var seen = new AtomicReference<Duration>();
        Transport fakeH2 = new Transport() {
            @Override public String name() { return "fake-h2"; }
            @Override public Transport start() { return this; }
            @Override public int port() { return 0; }
            @Override public void close() { }
            @Override public void shutdownTimeout(Duration timeout) { seen.set(timeout); }
        };
        try (var server = SjwsServer.start(loaded, null, false)) {
            var factory = SjwsServer.class.getDeclaredField("contentH2Factory");
            factory.setAccessible(true);
            factory.set(server, (Transport.Factory) (c, h) -> fakeH2);
            assertFalse(server.contentBound());
            server.shutdownTimeout(Duration.ofSeconds(60));
            assertTrue(server.bindContentPort());
            assertEquals(Duration.ofSeconds(60), seen.get());
            var ms = ClientManager.class.getDeclaredField("shutdownTimeoutMs");
            ms.setAccessible(true);
            assertEquals(60_000L, ms.getLong(server.clientManager()));
        }
    }

    private static void mintLocalhostPem(Path cert, Path key) throws Exception {
        var pb = new ProcessBuilder("/usr/bin/openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes",
                "-keyout", key.toString(), "-out", cert.toString(), "-days", "1", "-subj", "/CN=localhost");
        pb.redirectErrorStream(true);
        var proc = pb.start();
        var out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(proc.waitFor() == 0, out);
    }
}
