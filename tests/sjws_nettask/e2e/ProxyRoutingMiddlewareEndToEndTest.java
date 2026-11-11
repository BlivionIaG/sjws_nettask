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
package sjws_nettask.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.SjwsServer;
import sjws_nettask.config.ConfigLoader;
import sjws_nettask.core.MiddlewareSpec;
import sjws_nettask.core.RequestFilters;
import sjws_nettask.gateway.GatewayFilters;

/**
 * Composed path: a real {@link SjwsServer} with a route table, at least two gateway
 * filters, and a proxy/upstream, driven over the HTTP/1.1 listener. Component tests
 * under {@code routing/} and {@code gateway/} cover the same pieces in isolation.
 */
@Tag("e2e")
@Timeout(20)
class ProxyRoutingMiddlewareEndToEndTest {

    private static final byte[] BIG = ("A".repeat(256 * 1024)).getBytes(StandardCharsets.US_ASCII);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(8);

    private static final ProxySelector DIRECT = new ProxySelector() {
        @Override
        public List<Proxy> select(URI uri) {
            return List.of(Proxy.NO_PROXY);
        }

        @Override
        public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
        }
    };

    @TempDir
    Path dir;

    private HttpServer upstream;
    private final AtomicInteger hits = new AtomicInteger();
    private final CountDownLatch gatedFirstChunk = new CountDownLatch(1);
    private final CountDownLatch gatedRelease = new CountDownLatch(1);
    private SjwsServer server;
    private HttpClient client;
    private int port;

    @BeforeEach
    void start() throws Exception {
        this.upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.upstream.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        this.upstream.createContext("/", exchange -> {
            this.hits.incrementAndGet();
            var path = exchange.getRequestURI().getPath();
            var method = exchange.getRequestMethod();
            byte[] body = switch (path) {
                case "/hello" -> {
                    var seen = exchange.getRequestHeaders().getFirst("x-from-edge");
                    exchange.getResponseHeaders().set("x-echo-from-edge", seen == null ? "" : seen);
                    yield "upstream-hello".getBytes(StandardCharsets.UTF_8);
                }
                case "/big" -> BIG;
                case "/gated" -> {
                    var first = "BEGIN".repeat(1024).getBytes(StandardCharsets.US_ASCII);
                    exchange.sendResponseHeaders(200, first.length + BIG.length);
                    exchange.getResponseBody().write(first);
                    exchange.getResponseBody().flush();
                    this.gatedFirstChunk.countDown();
                    try {
                        if (!this.gatedRelease.await(10, TimeUnit.SECONDS)) {
                            throw new IOException("gated upstream timed out");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("gated upstream interrupted", e);
                    }
                    try {
                        exchange.getResponseBody().write(BIG);
                    } catch (IOException cancelled) {
                        // the client closed while this remainder was still held
                    }
                    exchange.close();
                    yield null;
                }
                case "/echo" -> exchange.getRequestBody().readAllBytes();
                default -> {
                    exchange.sendResponseHeaders(404, 0);
                    exchange.close();
                    yield null;
                }
            };
            if (body == null) {
                return;
            }
            if ("HEAD".equals(method)) {
                exchange.sendResponseHeaders(200, body.length);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        this.upstream.start();
        var upstreamUrl = "http://127.0.0.1:" + this.upstream.getAddress().getPort();

        var www = Files.createDirectories(this.dir.resolve("www"));
        Files.writeString(www.resolve("index.html"), "static-ok\n", StandardCharsets.UTF_8);
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
                    - { prefix: /, static: true }
                """.formatted(www.toAbsolutePath(), upstreamUrl));
        var loaded = ConfigLoader.load(new String[] {"--config=" + yaml, "--no-h3"}, Map.of());
        assertTrue(loaded.ok(), loaded.errors().toString());
        var filters = GatewayFilters.install(RequestFilters.create())
                .add(MiddlewareSpec.of("request-size", 0, null, Map.of("max-target", "80")))
                .add(MiddlewareSpec.of("cors", 10, "/api/*",
                        Map.of("origins", "https://app.example", "methods", "GET,POST")))
                .add(MiddlewareSpec.of("header-rewrite", 20, "/api/*",
                        Map.of("response-set", "x-edge: yes", "request-set", "x-from-edge: 1")));
        this.server = SjwsServer.start(loaded, filters);
        this.port = this.server.tcpPort();
        assertTrue(this.port > 0);
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2))
                .proxy(DIRECT)
                .build();
    }

    @AfterEach
    void stop() {
        if (this.server != null) {
            this.server.close();
        }
        if (this.client != null) {
            this.client.close();
        }
        if (this.upstream != null) {
            this.upstream.stop(0);
        }
    }

    @Test
    void filtersWrapTheProxyAndPreserveOrder() throws Exception {
        var response = get("/api/hello");
        assertEquals(200, response.statusCode());
        assertEquals("upstream-hello", response.body());
        assertEquals("yes", response.headers().firstValue("x-edge").orElseThrow());
        assertEquals("1", response.headers().firstValue("x-echo-from-edge").orElseThrow());
        assertEquals(1, this.hits.get());
    }

    @Test
    void staticFileStillServesBesideTheProxy() throws Exception {
        var response = get("/index.html");
        assertEquals(200, response.statusCode());
        assertEquals("static-ok\n", response.body());
        assertEquals(0, this.hits.get(), "static traffic must not hit the upstream");
        assertTrue(response.headers().firstValue("x-edge").isEmpty(), "rewrite is scoped to /api/*");
    }

    @Test
    void requestSizeAndCorsShortCircuitBeforeTheUpstream() throws Exception {
        int before = this.hits.get();
        var tooLong = get("/api/" + "a".repeat(80));
        assertEquals(414, tooLong.statusCode());
        assertTrue(tooLong.headers().firstValue("x-edge").isEmpty(),
                "request-size is outside header-rewrite, so a 414 has no x-edge");
        assertEquals(before, this.hits.get());

        var preflight = this.client.send(HttpRequest.newBuilder(uri("/api/hello"))
                .timeout(REQUEST_TIMEOUT)
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", "https://app.example")
                .header("Access-Control-Request-Method", "POST")
                .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(204, preflight.statusCode());
        assertEquals("https://app.example",
                preflight.headers().firstValue("access-control-allow-origin").orElseThrow());
        assertEquals(before, this.hits.get(), "CORS preflight must not reach the upstream");
    }

    @Test
    void upstreamFailureIsAnErrorAndTheChainStaysUp() throws Exception {
        this.upstream.stop(0);
        this.upstream = null;
        var failed = get("/api/hello");
        assertTrue(failed.statusCode() == 502 || failed.statusCode() == 504, "status " + failed.statusCode());
        assertEquals("yes", failed.headers().firstValue("x-edge").orElseThrow(),
                "header-rewrite still runs on the error response");
        var local = get("/index.html");
        assertEquals(200, local.statusCode());
        assertEquals("static-ok\n", local.body());
    }

    @Test
    void streamingBodyAndACancelledReadLeaveTheServerUp() throws Exception {
        var big = getBytes("/api/big");
        assertEquals(200, big.statusCode());
        assertEquals(sha(BIG), sha(big.body()));
        assertEquals("yes", big.headers().firstValue("x-edge").orElseThrow());

        try (var socket = new Socket("127.0.0.1", this.port)) {
            socket.setSoTimeout(8000);
            socket.getOutputStream().write("""
                    GET /api/gated HTTP/1.1\r
                    Host: 127.0.0.1\r
                    \r
                    """.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            var incoming = socket.getInputStream();
            var buf = new byte[4096];
            int got = 0;
            int headerEnd = -1;
            var assembled = new byte[16 * 1024];
            while (got < assembled.length) {
                int n = incoming.read(buf);
                assertTrue(n > 0, "upstream closed before headers and a body chunk arrived");
                System.arraycopy(buf, 0, assembled, got, n);
                got += n;
                headerEnd = indexOfHeaderEnd(assembled, got);
                if (headerEnd >= 0 && got > headerEnd) {
                    break;
                }
            }
            assertTrue(headerEnd >= 0, "received HTTP headers");
            int bodyBytes = got - headerEnd;
            assertTrue(bodyBytes > 0, "received the start of the gated body");
            assertTrue(this.gatedFirstChunk.await(5, TimeUnit.SECONDS),
                    "upstream must have sent the first chunk and be holding the remainder");
            assertEquals(1, this.gatedRelease.getCount(), "remainder must still be gated when the client closes");
        } finally {
            this.gatedRelease.countDown();
        }

        var hello = get("/api/hello");
        assertEquals(200, hello.statusCode());
        assertEquals("upstream-hello", hello.body());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return this.client.send(HttpRequest.newBuilder(uri(path)).timeout(REQUEST_TIMEOUT).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<byte[]> getBytes(String path) throws Exception {
        return this.client.send(HttpRequest.newBuilder(uri(path)).timeout(REQUEST_TIMEOUT).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + this.port + path);
    }

    private static String sha(byte[] body) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
    }

    private static int indexOfHeaderEnd(byte[] data, int len) {
        for (int i = 0; i + 3 < len; i++) {
            if (data[i] == '\r' && data[i + 1] == '\n' && data[i + 2] == '\r' && data[i + 3] == '\n') {
                return i + 4;
            }
        }
        return -1;
    }

}
