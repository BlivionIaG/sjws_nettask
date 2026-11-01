/*
 * Copyright (C) 2026 BlivionIaG <BlivionIaG at chenco.tk>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package sjws_nettask.h2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.SjwsServer;
import sjws_nettask.config.ConfigLoader;
import sjws_nettask.core.RequestFilters;

/**
 * #446: HTTP/1.1 ALPN fallback on the HTTP/2 TLS listener must fill
 * {@code RequestHeaders.fields()} under the same {@code attachBodies} gate as h2/h2c streams.
 */
class Http1FallbackFieldsTest {

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
    void alpnHttp11FallbackFillsFieldsWhenAttachBodiesOn() throws Exception {
        var www = Files.createDirectories(this.dir.resolve("www"));
        Files.writeString(www.resolve("hello.txt"), "hello");
        var yaml = this.dir.resolve("sjws.yaml");
        Files.writeString(yaml, """
                server:
                  port: 0
                  root: "%s"
                """.formatted(www.toAbsolutePath()));
        var cert = this.dir.resolve("cert.pem");
        var key = this.dir.resolve("key.pem");
        mintLocalhostPem(cert, key);

        var seen = new AtomicReference<List<String>>();
        var filters = RequestFilters.create().add((request, next) -> {
            var values = request.headers().fields().get("x-api-key");
            seen.set(values == null ? List.of() : List.copyOf(values));
            return next.proceed(request);
        });

        var loaded = ConfigLoader.load(new String[] {
                "--config=" + yaml, "--h2", "--h2-port=0", "--h2c-port=0", "--no-h3",
                "--cert=" + cert, "--key=" + key}, Map.of());
        assertTrue(loaded.ok(), loaded.errors().toString());
        this.server = SjwsServer.start(loaded, filters);
        assertTrue(((Http2Server) this.server.h2()).attachBodies(), "filter chain arms attachBodies");

        int h2 = ((Http2Server) this.server.h2()).port();
        var client = pinnedTrustClient(cert, HttpClient.Version.HTTP_1_1);
        var response = client.send(
                HttpRequest.newBuilder(URI.create("https://127.0.0.1:" + h2 + "/hello.txt"))
                        .timeout(Duration.ofSeconds(10))
                        .header("x-api-key", "secret")
                        .header("authorization", "Bearer tok")
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(HttpClient.Version.HTTP_1_1, response.version(), "ALPN must select http/1.1");
        assertEquals(200, response.statusCode());
        assertEquals("hello", response.body());
        assertEquals(List.of("secret"), seen.get(),
                "Http1FallbackHandler must fill fields() when attachBodies is on (#446)");
    }

    @Test
    void alpnHttp11AndH2FieldsParityForSameHeaders() throws Exception {
        var www = Files.createDirectories(this.dir.resolve("www-parity"));
        Files.writeString(www.resolve("hello.txt"), "hello");
        var yaml = this.dir.resolve("sjws-parity.yaml");
        Files.writeString(yaml, """
                server:
                  port: 0
                  root: "%s"
                """.formatted(www.toAbsolutePath()));
        var cert = this.dir.resolve("parity-cert.pem");
        var key = this.dir.resolve("parity-key.pem");
        mintLocalhostPem(cert, key);

        var h1Fields = new AtomicReference<Map<String, List<String>>>();
        var h2Fields = new AtomicReference<Map<String, List<String>>>();
        var filters = RequestFilters.create().add((request, next) -> {
            var copy = copyFields(request.headers().fields());
            if ("HTTP/2".equals(request.protocol())) {
                h2Fields.set(copy);
            } else {
                h1Fields.set(copy);
            }
            return next.proceed(request);
        });

        var loaded = ConfigLoader.load(new String[] {
                "--config=" + yaml, "--h2", "--h2-port=0", "--h2c-port=0", "--no-h3",
                "--cert=" + cert, "--key=" + key}, Map.of());
        assertTrue(loaded.ok(), loaded.errors().toString());
        this.server = SjwsServer.start(loaded, filters);

        int h2 = ((Http2Server) this.server.h2()).port();
        var headers = new String[] {
                "x-api-key", "secret",
                "authorization", "Bearer tok",
                "x-multi", "one",
                "x-multi", "two",
                "x-custom", "alpha"};

        var h1Client = pinnedTrustClient(cert, HttpClient.Version.HTTP_1_1);
        var h1 = h1Client.send(request(h2, headers), HttpResponse.BodyHandlers.ofString());
        assertEquals(HttpClient.Version.HTTP_1_1, h1.version());
        assertEquals(200, h1.statusCode());

        var h2Client = pinnedTrustClient(cert, HttpClient.Version.HTTP_2);
        var h2Resp = h2Client.send(request(h2, headers), HttpResponse.BodyHandlers.ofString());
        assertEquals(HttpClient.Version.HTTP_2, h2Resp.version());
        assertEquals(200, h2Resp.statusCode());

        assertNotNull(h1Fields.get(), "ALPN http/1.1 filter must observe fields()");
        assertNotNull(h2Fields.get(), "h2 filter must observe fields()");
        assertFalse(h1Fields.get().isEmpty(), "ALPN http/1.1 fields() must not stay empty (#446)");
        assertEquals(h2Fields.get().get("x-api-key"), h1Fields.get().get("x-api-key"));
        assertEquals(h2Fields.get().get("authorization"), h1Fields.get().get("authorization"));
        assertEquals(h2Fields.get().get("x-multi"), h1Fields.get().get("x-multi"));
        assertEquals(h2Fields.get().get("x-custom"), h1Fields.get().get("x-custom"));
        assertEquals(List.of("secret"), h1Fields.get().get("x-api-key"));
        assertEquals(List.of("Bearer tok"), h1Fields.get().get("authorization"));
        assertEquals(List.of("one", "two"), h1Fields.get().get("x-multi"));
    }

    private static HttpRequest request(int port, String... headers) {
        var b = HttpRequest.newBuilder(URI.create("https://127.0.0.1:" + port + "/hello.txt"))
                .timeout(Duration.ofSeconds(10)).GET();
        if (headers.length > 0) {
            b.headers(headers);
        }
        return b.build();
    }

    private static Map<String, List<String>> copyFields(Map<String, List<String>> fields) {
        var copy = new LinkedHashMap<String, List<String>>();
        for (var e : fields.entrySet()) {
            copy.put(e.getKey(), List.copyOf(e.getValue()));
        }
        return Map.copyOf(copy);
    }

    private static void mintLocalhostPem(Path cert, Path key) throws Exception {
        var pb = new ProcessBuilder("/usr/bin/openssl", "req", "-x509", "-newkey", "rsa:2048",
                "-keyout", key.toString(), "-out", cert.toString(),
                "-days", "1", "-nodes", "-subj", "/CN=localhost",
                "-addext", "subjectAltName=DNS:localhost,IP:127.0.0.1");
        pb.redirectErrorStream(true);
        var proc = pb.start();
        var out = new String(proc.getInputStream().readAllBytes());
        if (proc.waitFor() != 0) {
            throw new IllegalStateException("openssl failed: " + out);
        }
    }

    private static HttpClient pinnedTrustClient(Path pem, HttpClient.Version version) throws Exception {
        var certs = java.security.cert.CertificateFactory.getInstance("X.509")
                .generateCertificates(new java.io.ByteArrayInputStream(Files.readAllBytes(pem)));
        var ks = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType());
        ks.load(null, null);
        int i = 0;
        for (var c : certs) {
            ks.setCertificateEntry("bench-" + (i++), c);
        }
        var tmf = javax.net.ssl.TrustManagerFactory.getInstance(
                javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        var ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tmf.getTrustManagers(), null);
        return HttpClient.newBuilder().version(version).sslContext(ctx)
                .connectTimeout(Duration.ofSeconds(5)).build();
    }
}
