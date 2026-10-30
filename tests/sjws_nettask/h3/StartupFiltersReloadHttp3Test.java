/*
 * Copyright (C) 2026 BlivionIaG <BlivionIaG at chenco.tk>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package sjws_nettask.h3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.SjwsServer;
import sjws_nettask.config.ConfigLoader;
import sjws_nettask.core.FilterDocument;
import sjws_nettask.core.FilterScope;
import sjws_nettask.core.RequestFilters;
import sjws_nettask.tls.TlsMaterial;

/**
 * Codex P2 4421015537 on HTTP/3: publish updates {@code armed}, and h3 replaceHandler reads
 * {@link SjwsServer#requestHandler()}, so resurrecting startup filters via override would show here too.
 */
@Tag("h3")
class StartupFiltersReloadHttp3Test {

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
    void startupFiltersStayRemovedAfterConfigReloadOverH3() throws Exception {
        var www = Files.createDirectories(this.dir.resolve("www"));
        Files.writeString(www.resolve("hello.txt"), "hello");
        var yaml = this.dir.resolve("sjws.yaml");
        Files.writeString(yaml, """
                server:
                  port: 0
                  root: "%s"
                """.formatted(www.toAbsolutePath()));

        var filters = RequestFilters.create().add((request, next) -> {
            var response = next.proceed(request);
            response.headers().set("x-old", "1");
            return response;
        }).factory("mark", spec -> (request, next) -> {
            var response = next.proceed(request);
            response.headers().set("x-mark", spec.option("id"));
            return response;
        });

        // Enable h2 as well so publish() takes the h2 arming path that feeds armed → h3.
        var loaded = ConfigLoader.load(new String[] {
                "--config=" + yaml,
                "--h2", "--h2-port=0", "--h2c-port=0",
                "--h3", "--h3-port=0", "--h3-host=127.0.0.1",
                "--cert=" + this.cert, "--key=" + this.key}, Map.of());
        assertTrue(loaded.ok(), loaded.errors().toString());
        this.server = SjwsServer.start(loaded, filters);

        var control = this.server.chainControl(filters, Set.of());
        control.apply(FilterDocument.of(1, List.of(
                FilterDocument.FilterEntry.of("mark", 0, Map.of("id", "cp"), FilterScope.all()))));

        int udp = this.server.udpPort();
        assertTrue(udp > 0);
        try (var client = new H3TestClient(this.tls.certificate())) {
            var before = H3TestClient.get(client.connect(new java.net.InetSocketAddress("127.0.0.1", udp)),
                    "/hello.txt");
            assertEquals(200, before.status());
            assertEquals("cp", before.header("x-mark"));
            assertNull(before.header("x-old"), "h3: startup filter removed by first document");

            var www2 = Files.createDirectories(this.dir.resolve("www2"));
            Files.writeString(www2.resolve("hello.txt"), "hello2");
            Files.writeString(yaml, """
                    server:
                      port: 0
                      root: "%s"
                    """.formatted(www2.toAbsolutePath()));
            assertTrue(this.server.reload().applied());

            var after = H3TestClient.get(client.connect(new java.net.InetSocketAddress("127.0.0.1", udp)),
                    "/hello.txt");
            assertEquals(200, after.status());
            assertEquals("hello2", new String(after.body(), StandardCharsets.UTF_8));
            assertEquals("cp", after.header("x-mark"), "h3: control filter survives reload");
            assertNull(after.header("x-old"),
                    "h3: startup filter must stay removed after config reload (Codex P2 4421015537)");
        }
    }

    private static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(der)
                + "\n-----END " + type + "-----\n";
    }
}
