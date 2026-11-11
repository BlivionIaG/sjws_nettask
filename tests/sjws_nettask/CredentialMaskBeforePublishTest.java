/*
 * Copyright (C) 2026 BlivionIaG <BlivionIaG at chenco.tk>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package sjws_nettask;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.config.ConfigLoader;
import sjws_nettask.core.Handler;
import sjws_nettask.core.RequestFilters;
import sjws_nettask.gateway.GatewayFilters;
import sjws_nettask.observe.ObserverSlot;

/** A published config's credential query names are masked before a transport is armed with it. */
class CredentialMaskBeforePublishTest {

    @TempDir
    Path dir;

    @Test
    void maskedNamesAreRefreshedBeforeTheTransportIsArmed() throws Exception {
        var first = Files.createDirectory(dir.resolve("first"));
        Files.writeString(first.resolve("index.html"), "first");
        var second = Files.createDirectory(dir.resolve("second"));
        Files.writeString(second.resolve("index.html"), "second");
        var cert = dir.resolve("cert.pem");
        var key = dir.resolve("key.pem");
        mintLocalhostPem(cert, key);
        var yaml = dir.resolve("sjws.yaml");
        Files.writeString(yaml, "server:\n  port: 0\n  root: " + first.toAbsolutePath() + "\n");
        var seenAtArm = new AtomicReference<List<String>>();
        var server = new AtomicReference<SjwsServer>();
        Transport fakeH2 = new Transport() {
            @Override public String name() { return "fake-h2"; }
            @Override public Transport start() { return this; }
            @Override public int port() { return 0; }
            @Override public void close() { }
            @Override public void armHandler(Handler handler) {
                var running = server.get();
                if (running != null) {
                    seenAtArm.set(maskedNames(running));
                }
            }
        };
        Transport.Factory h3Unused = (config, handler) -> {
            throw new AssertionError("h3 is disabled");
        };
        var loaded = ConfigLoader.load(new String[] {"--config=" + yaml, "--h2", "--h2-port=0", "--h2c-port=0",
                "--no-h3", "--cert=" + cert, "--key=" + key}, Map.of());
        assertTrue(loaded.ok(), loaded.errors().toString());
        try (var started = SjwsServer.start(loaded, h3Unused, (c, h) -> fakeH2,
                GatewayFilters.install(RequestFilters.create()), null)) {
            server.set(started);
            Files.writeString(yaml, "server:\n  port: 0\n  root: " + second.toAbsolutePath()
                    + "\nmiddleware:\n  - name: api-key\n    options:\n      keys: K-1\n      query: fresh_key\n");
            var result = started.reload();
            assertTrue(result.applied(), result.errors().toString());
            assertNotNull(seenAtArm.get(), "the reload armed HTTP/2");
            assertTrue(seenAtArm.get().contains("fresh_key"), seenAtArm.get().toString());
        }
    }

    private static List<String> maskedNames(SjwsServer server) {
        try {
            var slotField = SjwsServer.class.getDeclaredField("slot");
            slotField.setAccessible(true);
            var slot = (ObserverSlot) slotField.get(server);
            var names = ObserverSlot.class.getDeclaredField("credentialQueryParams");
            names.setAccessible(true);
            return Arrays.asList((String[]) names.get(slot));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
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
