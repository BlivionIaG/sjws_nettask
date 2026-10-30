/*
 * Copyright (C) 2026 BlivionIaG <BlivionIaG at chenco.tk>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package sjws_nettask.h3;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.HttpTestSupport;
import sjws_nettask.SjwsServer;
import sjws_nettask.config.ConfigLoader;
import sjws_nettask.core.FilterDocument;
import sjws_nettask.core.FilterScope;
import sjws_nettask.core.RequestFilters;
import sjws_nettask.gateway.GatewayFilters;
import sjws_nettask.tls.TlsMaterial;

/**
 * #307 + #302: after a live control document adds/clears api-key query names, a config reload
 * that publishes through chainLeaf must keep access-log masking aligned on h3 (and h1).
 */
@Tag("h3")
class AccessLogCredentialOwnershipH3Test {

    private static final String SECRET = "S2-H3-MASK-SECRET";

    @TempDir
    Path dir;

    TlsMaterial tls;
    Path cert;
    Path key;
    SjwsServer server;

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeTrue(Http3Server.isAvailable(),
                () -> "native QUIC unavailable: " + Http3Server.unavailabilityCause());
        this.tls = TlsMaterial.selfSigned("localhost");
        this.cert = Files.writeString(this.dir.resolve("cert.pem"),
                pem("CERTIFICATE", this.tls.certificate().getEncoded()));
        this.key = Files.writeString(this.dir.resolve("key.pem"),
                pem("PRIVATE KEY", this.tls.key().getEncoded()));
    }

    @AfterEach
    void stop() {
        if (this.server != null) {
            this.server.close();
            this.server = null;
        }
    }

    @Test
    void controlDocOwnershipSurvivesReloadOverH3() throws Exception {
        Files.writeString(this.dir.resolve("index.html"), "ok");
        var www2 = Files.createDirectories(this.dir.resolve("www2"));
        Files.writeString(www2.resolve("index.html"), "ok2");
        var log = this.dir.resolve("access.log");
        var yaml = this.dir.resolve("sjws.yaml");
        writeYaml(yaml, this.dir, log);

        var loaded = ConfigLoader.load(new String[] {
                "--config=" + yaml,
                "--h2", "--h2-port=0", "--h2c-port=0",
                "--h3", "--h3-port=0", "--h3-host=127.0.0.1",
                "--cert=" + this.cert, "--key=" + this.key}, Map.of());
        assertTrue(loaded.ok(), loaded.errors().toString());
        this.server = SjwsServer.start(loaded);

        var control = this.server.chainControl(GatewayFilters.install(RequestFilters.create()), Set.of());
        control.apply(FilterDocument.of(1, List.of(
                FilterDocument.FilterEntry.of("api-key", 0,
                        Map.of("keys", SECRET, "query", "from_doc"), FilterScope.all()))));

        HttpTestSupport.rawRequest(this.server.tcpPort(),
                "GET /index.html?from_doc=" + SECRET + "&h1=1 HTTP/1.1\r\nHost: h\r\nx-api-key: " + SECRET
                        + "\r\n\r\n",
                4000);
        int udp = this.server.udpPort();
        assertTrue(udp > 0);
        try (var client = new H3TestClient(this.tls.certificate())) {
            H3TestClient.request(client.connect(new InetSocketAddress("127.0.0.1", udp)), "GET",
                    "/index.html?from_doc=" + SECRET + "&h3=1", Map.of("x-api-key", SECRET))
                    .get(30, TimeUnit.SECONDS);
            var before = lineContaining(awaitLog(log, "h3=1"), "h3=1");
            assertTrue(before.contains("from_doc=***"), before);

            writeYaml(yaml, www2, log);
            assertTrue(this.server.reload().applied());
            H3TestClient.request(client.connect(new InetSocketAddress("127.0.0.1", udp)), "GET",
                    "/index.html?from_doc=" + SECRET + "&h3-after=1", Map.of("x-api-key", SECRET))
                    .get(30, TimeUnit.SECONDS);
            var after = lineContaining(awaitLog(log, "h3-after=1"), "h3-after=1");
            assertTrue(after.contains("from_doc=***"), after);

            control.apply(FilterDocument.empty(2));
            writeYaml(yaml, this.dir, log);
            assertTrue(this.server.reload().applied());
            H3TestClient.request(client.connect(new InetSocketAddress("127.0.0.1", udp)), "GET",
                    "/index.html?from_doc=" + SECRET + "&h3-clear=1", Map.of("x-api-key", SECRET))
                    .get(30, TimeUnit.SECONDS);
            var cleared = lineContaining(awaitLog(log, "h3-clear=1"), "h3-clear=1");
            assertFalse(cleared.contains("***"), cleared);
        }
    }

    private void writeYaml(Path yaml, Path root, Path log) throws Exception {
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

    private static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(der)
                + "\n-----END " + type + "-----\n";
    }
}
