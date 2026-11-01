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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.SjwsServer;
import sjws_nettask.config.ConfigLoader;
import sjws_nettask.core.FilterDocument;
import sjws_nettask.core.FilterScope;
import sjws_nettask.core.RequestFilter;
import sjws_nettask.core.RequestFilters;
import sjws_nettask.core.Response;
import sjws_nettask.core.ResponseHeaders;
import sjws_nettask.core.Body;
import sjws_nettask.gateway.GatewayFilters;

/**
 * #446: HTTP/1.1 ALPN fallback on the HTTP/2 TLS listener must fill
 * {@code RequestHeaders.fields()} (and attach bodies) under the same {@code attachBodies} gate as
 * h2/h2c streams, so gateway filters and proxy header forwarding match across protocols.
 */
class Http1FallbackFieldsTest {

    @TempDir
    Path dir;

    private SjwsServer server;
    private HttpServer upstream;

    @AfterEach
    void stop() {
        if (this.server != null) {
            this.server.close();
            this.server = null;
        }
        if (this.upstream != null) {
            this.upstream.stop(0);
            this.upstream = null;
        }
    }

    @Test
    void alpnHttp11FallbackFillsFieldsWhenAttachBodiesOn() throws Exception {
        var seen = new AtomicReference<List<String>>();
        startStaticWithFilter((request, next) -> {
            var values = request.headers().fields().get("x-api-key");
            seen.set(values == null ? List.of() : List.copyOf(values));
            return next.proceed(request);
        });

        int h2 = ((Http2Server) this.server.h2()).port();
        var client = pinnedTrustClient(this.dir.resolve("cert.pem"), HttpClient.Version.HTTP_1_1);
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
        var h1Fields = new AtomicReference<Map<String, List<String>>>();
        var h2Fields = new AtomicReference<Map<String, List<String>>>();
        startStaticWithFilter((request, next) -> {
            var copy = copyFields(request.headers().fields());
            if ("HTTP/2".equals(request.protocol())) {
                h2Fields.set(copy);
            } else {
                h1Fields.set(copy);
            }
            return next.proceed(request);
        });

        int h2 = ((Http2Server) this.server.h2()).port();
        var headers = new String[] {
                "x-api-key", "secret",
                "authorization", "Bearer tok",
                "x-multi", "one",
                "x-multi", "two",
                "x-custom", "alpha"};

        var h1Client = pinnedTrustClient(this.dir.resolve("cert.pem"), HttpClient.Version.HTTP_1_1);
        var h1 = h1Client.send(get(h2, headers), HttpResponse.BodyHandlers.ofString());
        assertEquals(HttpClient.Version.HTTP_1_1, h1.version());
        assertEquals(200, h1.statusCode());

        var h2Client = pinnedTrustClient(this.dir.resolve("cert.pem"), HttpClient.Version.HTTP_2);
        var h2Resp = h2Client.send(get(h2, headers), HttpResponse.BodyHandlers.ofString());
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

    @Test
    void apiKeyFilterParityOnAlpnHttp11AndH2() throws Exception {
        startStaticWithGateway(FilterDocument.of(1, List.of(
                FilterDocument.FilterEntry.of("api-key", 0, Map.of("keys", "secret", "header", "x-api-key"),
                        FilterScope.all()))));

        int h2 = ((Http2Server) this.server.h2()).port();
        var h1 = pinnedTrustClient(this.dir.resolve("cert.pem"), HttpClient.Version.HTTP_1_1);
        var h2c = pinnedTrustClient(this.dir.resolve("cert.pem"), HttpClient.Version.HTTP_2);

        assertEquals(401, send(h1, h2, null).statusCode(), "ALPN http/1.1 missing key");
        assertEquals(401, send(h1, h2, "wrong").statusCode(), "ALPN http/1.1 wrong key");
        var h1Ok = send(h1, h2, "secret");
        assertEquals(HttpClient.Version.HTTP_1_1, h1Ok.version());
        assertEquals(200, h1Ok.statusCode(), "ALPN http/1.1 correct key must reach the handler");

        assertEquals(401, send(h2c, h2, null).statusCode(), "h2 missing key");
        assertEquals(401, send(h2c, h2, "wrong").statusCode(), "h2 wrong key");
        var h2Ok = send(h2c, h2, "secret");
        assertEquals(HttpClient.Version.HTTP_2, h2Ok.version());
        assertEquals(200, h2Ok.statusCode(), "h2 correct key must reach the handler");
    }

    @Test
    void rateLimitByHeaderPartitionsOnAlpnHttp11Fallback() throws Exception {
        startStaticWithGateway(FilterDocument.of(1, List.of(
                FilterDocument.FilterEntry.of("rate-limit", 0,
                        Map.of("by", "header", "header", "x-tenant", "capacity", "1",
                                "refill-per-second", "0.0001", "status", "429"),
                        FilterScope.all()))));

        int h2 = ((Http2Server) this.server.h2()).port();
        var client = pinnedTrustClient(this.dir.resolve("cert.pem"), HttpClient.Version.HTTP_1_1);

        assertEquals(200, sendHeader(client, h2, "x-tenant", "a").statusCode(), "first a");
        assertEquals(200, sendHeader(client, h2, "x-tenant", "b").statusCode(),
                "distinct header value must use its own bucket on the ALPN fallback");
        assertEquals(429, sendHeader(client, h2, "x-tenant", "a").statusCode(),
                "second a must hit the per-header capacity");
    }

    @Test
    void requestSizeEnforcesContentLengthOnAlpnHttp11Fallback() throws Exception {
        startStaticWithGateway(FilterDocument.of(1, List.of(
                FilterDocument.FilterEntry.of("request-size", 0, Map.of("max-bytes", "4"),
                        FilterScope.all()))));

        int h2 = ((Http2Server) this.server.h2()).port();
        var client = pinnedTrustClient(this.dir.resolve("cert.pem"), HttpClient.Version.HTTP_1_1);
        var oversized = client.send(
                HttpRequest.newBuilder(URI.create("https://127.0.0.1:" + h2 + "/hello.txt"))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "text/plain")
                        .POST(HttpRequest.BodyPublishers.ofString("too-big!!"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(HttpClient.Version.HTTP_1_1, oversized.version());
        assertEquals(413, oversized.statusCode(),
                "request-size must read Content-Length from fields() on the ALPN fallback");

        var ok = client.send(
                HttpRequest.newBuilder(URI.create("https://127.0.0.1:" + h2 + "/hello.txt"))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "text/plain")
                        .POST(HttpRequest.BodyPublishers.ofString("ok"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(HttpClient.Version.HTTP_1_1, ok.version());
        assertTrue(ok.statusCode() == 200 || ok.statusCode() == 405,
                "under max-bytes the size filter must not 413; got " + ok.statusCode());
        assertNotEquals(413, ok.statusCode());
    }

    @Test
    void alpnHttp11FallbackAttachesBodyWhenAttachBodiesOn() throws Exception {
        var seen = new AtomicReference<String>();
        startStaticWithFilter((request, next) -> {
            if (!"POST".equals(request.method())) {
                return next.proceed(request);
            }
            try {
                var body = request.body();
                assertTrue(body.length() > 0, "body must be attached under attachBodies");
                byte[] buf = new byte[(int) body.length()];
                int n = 0;
                int r;
                while ((r = body.read(buf, n, buf.length - n)) >= 0) {
                    n += r;
                    if (n == buf.length) {
                        break;
                    }
                }
                seen.set(new String(buf, 0, n, StandardCharsets.UTF_8));
            } catch (java.io.IOException e) {
                throw new AssertionError(e);
            }
            return new Response(200, ResponseHeaders.of(), Body.Bytes.of("got".getBytes(StandardCharsets.UTF_8)));
        });

        int h2 = ((Http2Server) this.server.h2()).port();
        var client = pinnedTrustClient(this.dir.resolve("cert.pem"), HttpClient.Version.HTTP_1_1);
        var response = client.send(
                HttpRequest.newBuilder(URI.create("https://127.0.0.1:" + h2 + "/hello.txt"))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "text/plain")
                        .POST(HttpRequest.BodyPublishers.ofString("payload-446"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(HttpClient.Version.HTTP_1_1, response.version());
        assertEquals(200, response.statusCode());
        assertEquals("payload-446", seen.get(), "ALPN http/1.1 fallback must attach the request body");
    }

    @Test
    void proxyHeaderForwardingParityOnAlpnHttp11Fallback() throws Exception {
        var seen = new AtomicReference<Headers>();
        this.upstream = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        this.upstream.createContext("/", ex -> {
            seen.set(new Headers());
            seen.get().putAll(ex.getRequestHeaders());
            byte[] body = "up".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        this.upstream.setExecutor(Executors.newCachedThreadPool());
        this.upstream.start();
        String base = "http://127.0.0.1:" + this.upstream.getAddress().getPort();

        var www = Files.createDirectories(this.dir.resolve("www-proxy"));
        Files.writeString(www.resolve("hello.txt"), "hello");
        var yaml = this.dir.resolve("sjws-proxy.yaml");
        Files.writeString(yaml, """
                server:
                  port: 0
                  root: "%s"
                routing:
                  enabled: true
                  upstreams:
                    api: { url: "%s", connect_timeout: 5s, response_timeout: 30s, max_body: 16m }
                  routes:
                    - { prefix: /api, rewrite: /, proxy: api }
                """.formatted(www.toAbsolutePath(), base));
        var cert = this.dir.resolve("cert.pem");
        var key = this.dir.resolve("key.pem");
        if (!Files.exists(cert)) {
            mintLocalhostPem(cert, key);
        }

        var filters = RequestFilters.create();
        GatewayFilters.install(filters);
        var loaded = ConfigLoader.load(new String[] {
                "--config=" + yaml, "--h2", "--h2-port=0", "--h2c-port=0", "--no-h3",
                "--cert=" + cert, "--key=" + key}, Map.of());
        assertTrue(loaded.ok(), loaded.errors().toString());
        this.server = SjwsServer.start(loaded, filters);

        int h2 = ((Http2Server) this.server.h2()).port();
        // Raw TLS+HTTP/1.1: JDK HttpClient forbids setting Connection/TE/Upgrade/etc.
        String req = "GET /api/x HTTP/1.1\r\n"
                + "Host: 127.0.0.1\r\n"
                + "x-custom: alpha\r\n"
                + "authorization: Bearer tok\r\n"
                + "Connection: close, X-Secret\r\n"
                + "X-Secret: nope\r\n"
                + "Keep-Alive: timeout=5\r\n"
                + "TE: trailers\r\n"
                + "Upgrade: h2c\r\n"
                + "Proxy-Authorization: Basic x\r\n"
                + "X-Forwarded-For: 6.6.6.6\r\n"
                + "\r\n";
        var raw = rawAlpnHttp11(cert, h2, req);
        assertTrue(raw.startsWith("HTTP/1.1 200 "), raw);
        assertTrue(raw.contains("up"), raw);

        var s = seen.get();
        assertNotNull(s);
        assertEquals(List.of("alpha"), s.get("X-custom"), "end-to-end header forwarded");
        assertEquals(List.of("Bearer tok"), s.get("Authorization"));
        assertNull(s.getFirst("X-secret"), "Connection-listed header must not be forwarded");
        assertNull(s.getFirst("Keep-Alive"));
        assertNull(s.getFirst("TE"));
        assertNull(s.getFirst("Upgrade"));
        assertNull(s.getFirst("Proxy-Authorization"));
        var xff = s.getFirst("X-Forwarded-For");
        assertNotNull(xff);
        assertFalse(xff.contains("6.6.6.6"), "client-supplied X-Forwarded-For must not be trusted");
        assertTrue(xff.contains("127.0.0.1") || xff.contains("0:0:0:0:0:0:0:1") || xff.contains("::1"),
                "X-Forwarded-For should carry the peer, got " + xff);
    }

    private void startStaticWithFilter(sjws_nettask.core.RequestFilter filter) throws Exception {
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
        var filters = RequestFilters.create().add(filter);
        var loaded = ConfigLoader.load(new String[] {
                "--config=" + yaml, "--h2", "--h2-port=0", "--h2c-port=0", "--no-h3",
                "--cert=" + cert, "--key=" + key}, Map.of());
        assertTrue(loaded.ok(), loaded.errors().toString());
        this.server = SjwsServer.start(loaded, filters);
        assertTrue(((Http2Server) this.server.h2()).attachBodies(), "filter chain arms attachBodies");
    }

    private void startStaticWithGateway(FilterDocument document) throws Exception {
        var www = Files.createDirectories(this.dir.resolve("www-gw"));
        Files.writeString(www.resolve("hello.txt"), "hello");
        var yaml = this.dir.resolve("sjws-gw.yaml");
        Files.writeString(yaml, """
                server:
                  port: 0
                  root: "%s"
                """.formatted(www.toAbsolutePath()));
        var cert = this.dir.resolve("cert.pem");
        var key = this.dir.resolve("key.pem");
        if (!Files.exists(cert)) {
            mintLocalhostPem(cert, key);
        }
        var filters = RequestFilters.create();
        GatewayFilters.install(filters);
        var loaded = ConfigLoader.load(new String[] {
                "--config=" + yaml, "--h2", "--h2-port=0", "--h2c-port=0", "--no-h3",
                "--cert=" + cert, "--key=" + key}, Map.of());
        assertTrue(loaded.ok(), loaded.errors().toString());
        this.server = SjwsServer.start(loaded, filters);
        this.server.chainControl(filters, Set.of()).apply(document);
        assertTrue(((Http2Server) this.server.h2()).attachBodies());
    }

    private static HttpRequest get(int port, String... headers) {
        var b = HttpRequest.newBuilder(URI.create("https://127.0.0.1:" + port + "/hello.txt"))
                .timeout(Duration.ofSeconds(10)).GET();
        if (headers.length > 0) {
            b.headers(headers);
        }
        return b.build();
    }

    private static HttpResponse<String> send(HttpClient client, int port, String apiKey) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("https://127.0.0.1:" + port + "/hello.txt"))
                .timeout(Duration.ofSeconds(10)).GET();
        if (apiKey != null) {
            b.header("x-api-key", apiKey);
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> sendHeader(HttpClient client, int port, String name, String value)
            throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create("https://127.0.0.1:" + port + "/hello.txt"))
                        .timeout(Duration.ofSeconds(10)).header(name, value).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static Map<String, List<String>> copyFields(Map<String, List<String>> fields) {
        var copy = new LinkedHashMap<String, List<String>>();
        for (var e : fields.entrySet()) {
            copy.put(e.getKey(), List.copyOf(e.getValue()));
        }
        return Map.copyOf(copy);
    }


    private static String rawAlpnHttp11(Path pem, int port, String request) throws Exception {
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
        try (var s = (SSLSocket) ctx.getSocketFactory().createSocket("127.0.0.1", port)) {
            var p = s.getSSLParameters();
            p.setServerNames(java.util.List.of(new SNIHostName("localhost")));
            p.setApplicationProtocols(new String[] {"http/1.1"});
            s.setSSLParameters(p);
            s.startHandshake();
            assertEquals("http/1.1", s.getApplicationProtocol());
            s.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
            s.getOutputStream().flush();
            s.shutdownOutput();
            return new String(s.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
        }
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
