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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.config.ConfigLoader;
import sjws_nettask.core.FilterDocument;
import sjws_nettask.core.FilterScope;
import sjws_nettask.core.Handler;
import sjws_nettask.core.RequestFilters;
import sjws_nettask.gateway.GatewayFilters;

/**
 * #307 ownership after #302: custom h2Factory armH2 path + TLS-enabled publish must keep
 * credential query names on the live FilterDocument (access log), never resurrect middleware.
 */
class AccessLogCredentialOwnershipTransportsTest {

    private static final String SECRET = "S2-CUSTOM-H2-MASK";

    @TempDir
    Path dir;

    private SjwsServer server;

    @AfterEach
    void stop() {
        if (this.server != null) {
            this.server.close();
            this.server = null;
        }
    }

    @Test
    void controlDocOwnershipSurvivesCustomH2AndTlsReload() throws Exception {
        Files.writeString(this.dir.resolve("index.html"), "ok");
        var www2 = Files.createDirectories(this.dir.resolve("www2"));
        Files.writeString(www2.resolve("index.html"), "ok2");
        var log = this.dir.resolve("access.log");
        var yaml = this.dir.resolve("sjws.yaml");
        var cert = this.dir.resolve("bench-cert.pem");
        var key = this.dir.resolve("bench-key.pem");
        mintLocalhostPem(cert, key);
        writeYaml(yaml, this.dir, log);

        var armCalls = new AtomicInteger();
        var armed = new AtomicReference<Handler>();
        Transport fakeH2 = new Transport() {
            @Override public String name() { return "fake-h2"; }
            @Override public Transport start() { return this; }
            @Override public int port() { return 0; }
            @Override public void close() { }
            @Override public void armHandler(Handler handler) {
                armCalls.incrementAndGet();
                armed.set(handler);
            }
        };
        Transport.Factory h3Unused = (config, handler) -> {
            throw new AssertionError("h3 factory must not run when h3 is disabled");
        };
        var loaded = ConfigLoader.load(new String[] {
                "--config=" + yaml, "--h2", "--h2-port=0", "--h2c-port=0", "--no-h3",
                "--cert=" + cert, "--key=" + key}, Map.of());
        assertTrue(loaded.ok(), loaded.errors().toString());
        this.server = SjwsServer.start(loaded, h3Unused, (c, h) -> fakeH2,
                GatewayFilters.install(RequestFilters.create()), null);

        var control = this.server.chainControl(GatewayFilters.install(RequestFilters.create()), Set.of());
        control.apply(FilterDocument.of(1, List.of(
                FilterDocument.FilterEntry.of("api-key", 0,
                        Map.of("keys", SECRET, "query", "from_doc"), FilterScope.all()))));
        assertTrue(armCalls.get() >= 1, "apply must arm custom h2");

        hit("/index.html?from_doc=" + SECRET + "&from_mw=" + SECRET + "&cust=1");
        var line = lineContaining(awaitLog(log, "cust=1"), "cust=1");
        assertTrue(line.contains("from_doc=***"), line);

        int before = armCalls.get();
        writeYaml(yaml, www2, log);
        assertTrue(this.server.reload().applied());
        assertTrue(armCalls.get() > before, "TLS/config publish must arm custom h2 via armH2");
        hit("/index.html?from_doc=" + SECRET + "&from_mw=" + SECRET + "&cust-after=1");
        var after = lineContaining(awaitLog(log, "cust-after=1"), "cust-after=1");
        assertTrue(after.contains("from_doc=***"), after);
        assertFalse(after.contains("from_doc=" + SECRET), after);

        control.apply(FilterDocument.empty(2));
        writeYaml(yaml, this.dir, log);
        assertTrue(this.server.reload().applied());
        hit("/index.html?from_doc=" + SECRET + "&from_mw=" + SECRET + "&cust-clear=1");
        var cleared = lineContaining(awaitLog(log, "cust-clear=1"), "cust-clear=1");
        assertFalse(cleared.contains("***"), cleared);
    }

    private void hit(String target) throws Exception {
        HttpTestSupport.rawRequest(this.server.tcpPort(),
                "GET " + target + " HTTP/1.1\r\nHost: h\r\nx-api-key: " + SECRET + "\r\n\r\n", 4000);
    }

    private static void writeYaml(Path yaml, Path root, Path log) throws Exception {
        Files.writeString(yaml, """
                server:
                  port: 0
                  root: %s
                  access_log: %s
                middleware:
                  - name: api-key
                    options:
                      keys: %s
                      query: from_mw
                """.formatted(root.toAbsolutePath(), log.toAbsolutePath(), SECRET));
    }

    private static void mintLocalhostPem(Path cert, Path key) throws Exception {
        var pb = new ProcessBuilder("/usr/bin/openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes",
                "-keyout", key.toString(), "-out", cert.toString(), "-days", "1", "-subj", "/CN=localhost");
        pb.redirectErrorStream(true);
        var proc = pb.start();
        var out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(proc.waitFor() == 0, out);
    }

    private static String lineContaining(String text, String needle) {
        for (var line : text.split("\n")) {
            if (line.contains(needle)) {
                return line;
            }
        }
        return text;
    }

    private static String awaitLog(Path log, String needle) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        String text = Files.exists(log) ? Files.readString(log) : "";
        while (!text.contains(needle)) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("access log missing " + needle + ": " + text);
            }
            Thread.yield();
            text = Files.exists(log) ? Files.readString(log) : "";
        }
        return text;
    }
}
