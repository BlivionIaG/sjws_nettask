/*
 * Copyright (C) 2026 BlivionIaG <BlivionIaG at chenco.tk>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package sjws_nettask.routing;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.HttpTestSupport;
import sjws_nettask.SjwsServer;
import sjws_nettask.config.ConfigLoader;
import sjws_nettask.core.Body;
import sjws_nettask.core.Request;
import sjws_nettask.core.RequestBody;
import sjws_nettask.core.RequestHeaders;

/** Phase 2: the Netty upstream client, streaming both ways, pooling, and the edge cases. */
class ProxyStreamingTest {

    @TempDir
    Path dir;

    private HttpServer upstream;
    private URI base;
    private final Set<Integer> upstreamPorts = ConcurrentHashMap.newKeySet();
    private final AtomicInteger requests = new AtomicInteger();
    private static final byte[] BIG = new byte[4 * 1024 * 1024 + 7];

    static {
        new Random(42).nextBytes(BIG);
    }

    @BeforeEach
    void start() throws IOException {
        this.upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.upstream.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        this.upstream.createContext("/", ex -> {
            this.requests.incrementAndGet();
            this.upstreamPorts.add(ex.getRemoteAddress().getPort());
            var path = ex.getRequestURI().getPath();
            byte[] body;
            switch (path) {
                case "/big" -> body = BIG;
                case "/echo" -> body = ex.getRequestBody().readAllBytes();
                case "/host" -> body = String.valueOf(ex.getRequestHeaders().getFirst("Host")).getBytes(StandardCharsets.UTF_8);
                case "/nocontent" -> {
                    ex.sendResponseHeaders(204, -1);
                    ex.close();
                    return;
                }
                case "/chunked" -> {
                    ex.sendResponseHeaders(200, 0); // 0 = chunked
                    for (int i = 0; i < 5; i++) {
                        ex.getResponseBody().write(("part" + i + ";").getBytes(StandardCharsets.UTF_8));
                        ex.getResponseBody().flush();
                    }
                    ex.close();
                    return;
                }
                default -> body = ("ok " + path).getBytes(StandardCharsets.UTF_8);
            }
            if ("HEAD".equals(ex.getRequestMethod())) {
                ex.getResponseHeaders().set("Content-Length", Integer.toString(body.length));
                ex.sendResponseHeaders(200, -1);
            } else {
                ex.sendResponseHeaders(200, body.length);
                ex.getResponseBody().write(body);
            }
            ex.close();
        });
        this.upstream.start();
        this.base = URI.create("http://127.0.0.1:" + this.upstream.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        this.upstream.stop(0);
    }

    private static String sha(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private static byte[] drain(Body body) throws IOException {
        var out = new ByteArrayOutputStream();
        try (var source = ((Body.Stream) body).source()) {
            for (var d = source.next(); d != null; d = source.next()) {
                var chunk = new byte[d.remaining()];
                d.get(chunk);
                out.write(chunk);
            }
        }
        return out.toByteArray();
    }

    private ProxyHandler proxy(boolean preserveHost, Duration idle) {
        return new ProxyHandler(new ProxyHandler.Settings(this.base, Duration.ofSeconds(2), Duration.ofSeconds(5), idle,
                1 << 20, null, List.of(), preserveHost, 8));
    }

    @Test
    void streamsAndReusesOneConnection() throws Exception {
        try (var p = proxy(false, Duration.ofSeconds(5))) {
            var r = p.handle(new Request("GET", "/big"));
            assertEquals(BIG.length, r.body().length());
            assertEquals(sha(BIG), sha(drain(r.body())));
            assertEquals(1, p.idleConnections(), "the finished exchange went back to the pool");
            assertEquals("ok /a", new String(drain(p.handle(new Request("GET", "/a")).body()), StandardCharsets.UTF_8));
            assertEquals(1, this.upstreamPorts.size(), "both requests used one upstream connection");
            assertEquals("127.0.0.1:" + this.upstream.getAddress().getPort(),
                    new String(drain(p.handle(new Request("GET", "/host",
                            new RequestHeaders(null, null, null, null, null, "client.test"))).body()), StandardCharsets.UTF_8));
        }
    }

    @Test
    void preserveHostAndNoContentAndHead() throws Exception {
        try (var p = proxy(true, Duration.ofSeconds(5))) {
            var host = p.handle(new Request("GET", "/host", new RequestHeaders(null, null, null, null, null, "client.test")));
            assertEquals("client.test", new String(drain(host.body()), StandardCharsets.UTF_8));
            var none = p.handle(new Request("GET", "/nocontent"));
            assertEquals(204, none.status());
            assertInstanceOf(Body.Empty.class, none.body());
            var head = p.handle(new Request("HEAD", "/big"));
            assertEquals(200, head.status());
            assertEquals(BIG.length, head.contentLength());
            assertNull(((Body.Stream) head.body()).source().next());
            assertTrue(p.idleConnections() >= 1);
        }
    }

    @Test
    void chunkedUpstreamHasUnknownLength() throws Exception {
        try (var p = proxy(false, Duration.ofSeconds(5))) {
            var r = p.handle(new Request("GET", "/chunked"));
            assertEquals(-1, r.body().length());
            assertEquals("part0;part1;part2;part3;part4;", new String(drain(r.body()), StandardCharsets.UTF_8));
        }
    }

    @Test
    void requestBodyIsStreamedUpstream() throws Exception {
        var data = new byte[300_000];
        new Random(7).nextBytes(data);
        var in = new java.io.ByteArrayInputStream(data);
        RequestBody body = new RequestBody() {
            @Override
            public long length() {
                return data.length;
            }

            @Override
            public int read(byte[] into, int off, int len) {
                return in.read(into, off, len);
            }
        };
        try (var p = proxy(false, Duration.ofSeconds(5))) {
            var r = p.handle(new Request("POST", "/echo").withBody(body));
            assertEquals(200, r.status());
            assertEquals(sha(data), sha(drain(r.body())));
            RequestBody shortBody = new RequestBody() {
                @Override
                public long length() {
                    return 10;
                }

                @Override
                public int read(byte[] into, int off, int len) {
                    return -1;
                }
            };
            assertEquals(502, p.handle(new Request("POST", "/echo").withBody(shortBody)).status());
            var declared = new Request("POST", "/echo", RequestHeaders.of(Map.of("content-length", List.of("5"))));
            assertEquals(501, p.handle(declared).status(), "a body the transport did not attach");
            RequestBody unknown = new RequestBody() {
                @Override
                public long length() {
                    return -1;
                }

                @Override
                public int read(byte[] into, int off, int len) {
                    return -1;
                }
            };
            assertEquals(501, p.handle(new Request("POST", "/echo").withBody(unknown)).status());
        }
    }

    /** A raw upstream: answers the first request keep-alive, drops the second without a response, answers on a new connection. */
    @Test
    void staleKeepAliveConnectionIsRetriedOnce() throws Exception {
        try (var raw = new ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress())) {
            var accepted = new AtomicInteger();
            var t = Thread.ofVirtual().start(() -> {
                try {
                    var first = raw.accept();
                    accepted.incrementAndGet();
                    readHead(first.getInputStream());
                    first.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nhi".getBytes());
                    readHead(first.getInputStream()); // the second request on the pooled connection ...
                    first.close(); // ... is dropped without an answer
                    var second = raw.accept();
                    accepted.incrementAndGet();
                    readHead(second.getInputStream());
                    second.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 5\r\nConnection: close\r\n\r\nagain".getBytes());
                    second.close();
                } catch (IOException e) {
                    // test ends
                }
            });
            var p = new ProxyHandler(new ProxyHandler.Settings(URI.create("http://127.0.0.1:" + raw.getLocalPort())));
            try (p) {
                assertEquals("hi", new String(drain(p.handle(new Request("GET", "/1")).body())));
                assertEquals("again", new String(drain(p.handle(new Request("GET", "/2")).body())));
                assertEquals(2, accepted.get());
                assertEquals(0, p.idleConnections(), "Connection: close is not pooled");
            }
            t.join(2000);
        }
    }

    @Test
    void stalledBodyAbortsAndMalformedHeadIs502() throws Exception {
        try (var raw = new ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress())) {
            var t = Thread.ofVirtual().start(() -> {
                try {
                    var s = raw.accept();
                    readHead(s.getInputStream());
                    s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\n0123456789".getBytes());
                    var s2 = raw.accept();
                    readHead(s2.getInputStream());
                    s2.getOutputStream().write("NOT HTTP AT ALL\r\n\r\n".getBytes());
                    Thread.sleep(2000);
                    s.close();
                    s2.close();
                } catch (IOException | InterruptedException e) {
                    // test ends
                }
            });
            var p = new ProxyHandler(new ProxyHandler.Settings(URI.create("http://127.0.0.1:" + raw.getLocalPort()),
                    Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofMillis(300), 1024, null, List.of(), false, 4));
            try (p) {
                var r = p.handle(new Request("GET", "/stall"));
                assertEquals(200, r.status());
                assertEquals(100, r.body().length());
                var source = ((Body.Stream) r.body()).source();
                assertNotNull(source.next());
                assertThrows(IOException.class, source::next, "no byte within the idle read timeout");
                source.close();
                source.close(); // idempotent
                assertEquals(0, p.idleConnections(), "an unfinished exchange is never pooled");
                assertEquals(502, p.handle(new Request("GET", "/garbage")).status());
            }
            t.interrupt();
        }
    }

    @Test
    void tlsUpstreamThatIsNotTlsIs502AndSettingsAreChecked() {
        try (var p = new ProxyHandler(new ProxyHandler.Settings(URI.create("https://127.0.0.1:" + this.base.getPort())))) {
            assertEquals(502, p.handle(new Request("GET", "/")).status());
        }
        assertThrows(IllegalArgumentException.class, () -> new ProxyHandler.Settings(URI.create("http:///x")));
        var d = Duration.ofSeconds(1);
        assertThrows(IllegalArgumentException.class, () -> new ProxyHandler.Settings(this.base, d, d, d, 1, null, null, false, -1));
        assertThrows(NullPointerException.class, () -> new ProxyHandler.Settings(this.base, d, d, null, 1, null, null, false, 1));
    }

    @Test
    void unroutableConnectTimesOutOrFails() {
        var d = Duration.ofMillis(200);
        try (var p = new ProxyHandler(new ProxyHandler.Settings(URI.create("http://10.255.255.1:81"), d, d, d, 1, null,
                List.of(), false, 1))) {
            int status = p.handle(new Request("GET", "/")).status();
            assertTrue(status == 504 || status == 502, "status " + status);
        }
    }

    private static void readHead(InputStream in) throws IOException {
        int state = 0;
        while (state < 4) {
            int c = in.read();
            if (c < 0) {
                throw new IOException("eof");
            }
            state = (c == '\r' && (state == 0 || state == 2)) || (c == '\n' && (state == 1 || state == 3)) ? state + 1 : 0;
        }
    }

    /** End to end: config file, HTTP/1.1 client, POST body, 4 MiB stream, chunked, HTTP/1.0 close-delimited, reload and stop close pools. */
    @Test
    void throughTheServer() throws Exception {
        var www = Files.createDirectories(this.dir.resolve("www"));
        var yaml = this.dir.resolve("sjws.yaml");
        Files.writeString(yaml, """
                server:
                  port: 0
                  root: "%s"
                routing:
                  enabled: true
                  upstreams:
                    api: { url: "%s", idle_read_timeout: 5s }
                  routes:
                    - { prefix: /api, rewrite: /, proxy: api }
                """.formatted(www, this.base));
        var loaded = ConfigLoader.load(new String[] {"--config=" + yaml, "--no-h3"}, Map.of());
        assertTrue(loaded.ok(), loaded.errors().toString());
        try (var server = SjwsServer.start(loaded)) {
            int port = server.tcpPort();
            var client = java.net.http.HttpClient.newHttpClient();
            var big = client.send(java.net.http.HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/big")).build(),
                    java.net.http.HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, big.statusCode());
            assertEquals(sha(BIG), sha(big.body()));
            var payload = "x".repeat(200_000);
            var echo = client.send(java.net.http.HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/echo"))
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(payload)).build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(payload, echo.body());
            var chunked = client.send(java.net.http.HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/chunked")).build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals("part0;part1;part2;part3;part4;", chunked.body());
            assertEquals("chunked", chunked.headers().firstValue("transfer-encoding").orElse(null));
            try (var s = new Socket("127.0.0.1", port)) {
                s.setSoTimeout(5000);
                s.getOutputStream().write("GET /api/chunked HTTP/1.0\r\nHost: x\r\n\r\n".getBytes());
                var all = new String(s.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
                assertTrue(all.startsWith("HTTP/1.1 200"), all);
                assertFalse(all.toLowerCase().contains("transfer-encoding"), all);
                assertTrue(all.contains("Connection: close"), all);
                assertTrue(all.endsWith("\r\n\r\npart0;part1;part2;part3;part4;"), all);
            }
            // an expect-continue POST whose body the static handler never reads: answered, then closed
            var rejected = HttpTestSupport.rawRequest(port,
                    "POST /index.html HTTP/1.1\r\nHost: x\r\nContent-Length: 5\r\nExpect: 100-continue\r\n\r\n", 5000);
            assertEquals(405, rejected.statusCode());
            assertTrue(server.reload().applied());
        }
    }
}
