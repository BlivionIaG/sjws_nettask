/*
 * Copyright (C) 2026 BlivionIaG <BlivionIaG at chenco.tk>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package sjws_nettask;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.config.ConfigLoader;
import sjws_nettask.core.Handler;
import sjws_nettask.core.Request;
import sjws_nettask.core.RequestFilters;
import sjws_nettask.gateway.GatewayFilters;
import sjws_nettask.h2.Http2Server;
import sjws_nettask.observe.ObserverSlot;

/** A published config's credential query names are masked before a transport is armed with it. */
class CredentialMaskBeforePublishTest {

    private static final String SECRET = "S3CR3T-mask-union";

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

    @Test
    void aRemovedNameStaysMaskedOnTheOutgoingGenerationUntilRetirement() throws Exception {
        assertTransitionMasksOutgoingName("old_key", null);
    }

    @Test
    void aRenamedNameStaysMaskedOnTheOutgoingGenerationUntilRetirement() throws Exception {
        var after = assertTransitionMasksOutgoingName("old_key", "new_key");
        assertTrue(after.contains("new_key"), after.toString());
    }

    /**
     * Reloads from {@code outgoingName} to {@code incomingName} (null means no query name).
     * During the transport arm, a request that enters the outgoing generation is still masked.
     * After retirement, the dropped name is no longer in the published list.
     */
    private List<String> assertTransitionMasksOutgoingName(String outgoingName, String incomingName)
            throws Exception {
        var first = Files.createDirectory(dir.resolve("first-" + outgoingName + "-" + incomingName));
        Files.writeString(first.resolve("index.html"), "first");
        var mid = Files.createDirectory(dir.resolve("mid-" + outgoingName + "-" + incomingName));
        Files.writeString(mid.resolve("index.html"), "mid");
        var second = Files.createDirectory(dir.resolve("second-" + outgoingName + "-" + incomingName));
        Files.writeString(second.resolve("index.html"), "second");
        var cert = dir.resolve("cert-" + outgoingName + "-" + incomingName + ".pem");
        var key = dir.resolve("key-" + outgoingName + "-" + incomingName + ".pem");
        mintLocalhostPem(cert, key);
        var log = dir.resolve("access-" + outgoingName + "-" + incomingName + ".log");
        var yaml = dir.resolve("sjws-" + outgoingName + "-" + incomingName + ".yaml");
        Files.writeString(yaml, yaml(first, log, outgoingName));
        var seenAtArm = new AtomicReference<List<String>>();
        var outgoing = new AtomicReference<Handler>();
        var inflight = new AtomicReference<Exception>();
        var server = new AtomicReference<SjwsServer>();
        Transport fakeH2 = new Transport() {
            @Override public String name() { return "fake-h2"; }
            @Override public Transport start() { return this; }
            @Override public int port() { return 0; }
            @Override public void close() { }
            @Override public void armHandler(Handler handler) {
                var running = server.get();
                var previous = outgoing.getAndSet(handler);
                if (running == null || previous == null) {
                    return;
                }
                seenAtArm.set(maskedNames(running));
                try {
                    previous.handle(new Request("GET",
                            "/index.html?" + outgoingName + "=" + SECRET + "&inflight=1"));
                } catch (Exception e) {
                    inflight.set(e);
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
            Files.writeString(yaml, yaml(mid, log, outgoingName));
            assertTrue(started.reload().applied(), "arm the outgoing generation");
            assertNotNull(outgoing.get(), "the first reload armed HTTP/2");

            Files.writeString(yaml, yaml(second, log, incomingName));
            var result = started.reload();
            assertTrue(result.applied(), result.errors().toString());
            if (inflight.get() != null) {
                throw new AssertionError(inflight.get());
            }
            assertNotNull(seenAtArm.get(), "the second reload armed HTTP/2");
            assertTrue(seenAtArm.get().contains(outgoingName),
                    "union at arm still holds the outgoing name: " + seenAtArm.get());
            if (incomingName != null) {
                assertTrue(seenAtArm.get().contains(incomingName), seenAtArm.get().toString());
            }
            var text = awaitLog(log, "inflight=1");
            assertTrue(text.contains(outgoingName + "=***"), text);
            assertFalse(text.contains(SECRET), text);

            var after = maskedNames(started);
            assertFalse(after.contains(outgoingName),
                    "the outgoing-only name is retired once no generation holds it: " + after);
            return after;
        }
    }


    /**
     * A task that already entered the outgoing HTTP/2 generation, and is still queued,
     * keeps that generation's mask names until the generation quiesces.
     */
    @Test
    @Timeout(20)
    void outgoingNamesStayUntilThePreviousGenerationQuiesces() throws Exception {
        var first = Files.createDirectory(dir.resolve("q-first"));
        Files.writeString(first.resolve("index.html"), "first");
        var second = Files.createDirectory(dir.resolve("q-second"));
        Files.writeString(second.resolve("index.html"), "second");
        var cert = dir.resolve("q-cert.pem");
        var key = dir.resolve("q-key.pem");
        mintLocalhostPem(cert, key);
        var log = dir.resolve("q-access.log");
        var yaml = dir.resolve("q-sjws.yaml");
        Files.writeString(yaml, yaml(first, log, "old_key"));
        var loaded = ConfigLoader.load(new String[] {"--config=" + yaml, "--h2", "--h2-port=0", "--h2c-port=0",
                "--no-h3", "--cert=" + cert, "--key=" + key}, Map.of());
        assertTrue(loaded.ok(), loaded.errors().toString());
        try (var started = SjwsServer.start(loaded)) {
            var gen = generation(started);
            assertTrue(gen.tryEnter(), "pin the outgoing generation before the reload");
            var go = new CountDownLatch(1);
            var done = new CountDownLatch(1);
            var error = new AtomicReference<Exception>();
            Thread.ofVirtual().name("queued-after-tryEnter").start(() -> {
                try {
                    go.await();
                    gen.handler().handle(new Request("GET", "/index.html?old_key=" + SECRET + "&queued=1"));
                } catch (Exception e) {
                    error.set(e);
                } finally {
                    gen.leave();
                    done.countDown();
                }
            });
            try {
                Files.writeString(yaml, yaml(second, log, "new_key"));
                var result = started.reload();
                assertTrue(result.applied(), result.errors().toString());
                var held = maskedNames(started);
                assertTrue(held.contains("old_key"),
                        "outgoing name stays while the previous generation is still entered: " + held);
                assertTrue(held.contains("new_key"), held.toString());
            } finally {
                go.countDown();
            }
            assertTrue(done.await(10, TimeUnit.SECONDS), "queued task did not finish");
            if (error.get() != null) {
                throw new AssertionError(error.get());
            }
            var text = awaitLog(log, "queued=1");
            assertTrue(text.contains("old_key=***"), text);
            assertFalse(text.contains(SECRET), text);

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            List<String> after = maskedNames(started);
            while (after.contains("old_key") && System.nanoTime() < deadline) {
                Thread.sleep(20);
                after = maskedNames(started);
            }
            assertFalse(after.contains("old_key"),
                    "outgoing name is retired after the generation quiesces: " + after);
            assertTrue(after.contains("new_key"), after.toString());
        }
    }

    private static Http2Server.Generation generation(SjwsServer server) {
        var transport = server.h2();
        assertTrue(transport instanceof Http2Server, "expected a real HTTP/2 transport: " + transport);
        try {
            var method = Http2Server.class.getDeclaredMethod("generation");
            method.setAccessible(true);
            var gen = (Http2Server.Generation) method.invoke(transport);
            assertNotNull(gen, "HTTP/2 has no current generation");
            return gen;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static String yaml(Path root, Path log, String query) {
        var body = "server:\n  port: 0\n  root: " + root.toAbsolutePath()
                + "\n  access_log: " + log.toAbsolutePath() + "\n";
        if (query == null) {
            return body;
        }
        return body + "middleware:\n  - name: api-key\n    options:\n      keys: " + SECRET
                + "\n      query: " + query + "\n";
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

    private static String awaitLog(Path log, String needle) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        String text = Files.exists(log) ? Files.readString(log) : "";
        while (!text.contains(needle)) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("access log did not contain " + needle + " within 2s: " + text);
            }
            Thread.sleep(20);
            text = Files.exists(log) ? Files.readString(log) : "";
        }
        return text;
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
