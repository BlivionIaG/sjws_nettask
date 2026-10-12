package sjws_nettask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.core.Body;
import sjws_nettask.core.FilterChain;
import sjws_nettask.core.Handler;
import sjws_nettask.core.MiddlewareSpec;
import sjws_nettask.core.Request;
import sjws_nettask.core.RequestFilters;
import sjws_nettask.core.Response;
import sjws_nettask.core.ResponseHeaders;
import sjws_nettask.core.StaticFileHandler;

/** Filters on the real HTTP/1.1 server, including a failure that must not drop the connection. */
class FilterIntegrationTest {

    @TempDir
    Path root;

    SjwsServer server;

    @AfterEach
    void stop() {
        if (this.server != null) {
            this.server.close();
        }
    }

    private ServerConfig config() {
        return ServerConfig.builder(root).port(0).build();
    }

    @Test
    void noFiltersLeaveTheChainOffAndTheFileUnchanged() throws Exception {
        Files.writeString(root.resolve("hello.txt"), "hello");
        this.server = SjwsServer.start(config());
        assertNull(this.server.filterChain());
        var response = HttpTestSupport.get(this.server.tcpPort(), "/hello.txt", 4000);
        assertEquals(200, response.statusCode());
        assertEquals("hello", response.bodyText());
        assertNull(response.headers().get("x-filter"));
        assertEquals("5", response.headers().get("content-length"));
    }

    @Test
    void filterCanAnnotateAndShortCircuitThroughTheServer() throws Exception {
        Files.writeString(root.resolve("hello.txt"), "hello");
        Files.createDirectories(root.resolve("api"));
        Files.writeString(root.resolve("api").resolve("a.txt"), "api-file");
        var filters = RequestFilters.create()
                .add(-1, (request, next) -> {
                    assertTrue(request.peer() != null && request.peer().getAddress().isLoopbackAddress(),
                            String.valueOf(request.peer()));
                    assertFalse(request.secure());
                    assertEquals(java.util.List.of("localhost"), request.headers().values("host"));
                    if ("GET".equals(request.method())) {
                        assertEquals(java.util.List.of("sjws-tests"), request.headers().values("user-agent"));
                    }
                    assertSame(request.headers().fields(), request.withTarget("/x").headers().fields());
                    return next.proceed(request);
                })
                .factory("mark", spec -> (request, next) -> {
                    var response = next.proceed(request);
                    response.headers().add("x-filter", spec.option("value"));
                    return response;
                })
                .add(MiddlewareSpec.of("mark", 0, null, java.util.Map.of("value", "on")))
                .add(10, "/api/*", (request, next) -> new Response(200,
                        ResponseHeaders.of().add("content-type", "text/plain").add("x-proxy", "1"),
                        Body.Bytes.of("proxied".getBytes(StandardCharsets.UTF_8)), request.headOnly()));
        var seen = new FilterChain[1];
        var cfg = config().toBuilder().h3Enabled(true).h3Port(0).build();
        this.server = SjwsServer.start(cfg, new Transport.Factory() {
            @Override
            public Transport create(ServerConfig c, StaticFileHandler h) {
                throw new AssertionError("server must pass the chain");
            }

            @Override
            public Transport create(ServerConfig c, StaticFileHandler h, FilterChain chain) {
                seen[0] = chain;
                return new SjwsServerTest.FakeTransport(1, null);
            }
        }, filters);
        assertSame(seen[0], this.server.filterChain());
        int port = this.server.tcpPort();
        var file = HttpTestSupport.get(port, "/hello.txt", 4000);
        assertEquals(200, file.statusCode());
        assertEquals("hello", file.bodyText());
        assertEquals("on", file.headers().get("x-filter"));
        var head = HttpTestSupport.rawRequest(port, "HEAD /hello.txt HTTP/1.1\r\nHost: localhost\r\n\r\n", 4000);
        assertEquals(200, head.statusCode());
        assertEquals(0, head.body().length);
        assertEquals("5", head.headers().get("content-length"));
        assertEquals("on", head.headers().get("x-filter"));
        var proxied = HttpTestSupport.get(port, "/api/a.txt", 4000);
        assertEquals(200, proxied.statusCode());
        assertEquals("proxied", proxied.bodyText());
        assertEquals("1", proxied.headers().get("x-proxy"));
        assertFalse(proxied.bodyText().contains("api-file"));
    }

    @Test
    void filterErrorIs500AndTheConnectionStaysUp() throws Throwable {
        Files.writeString(root.resolve("hello.txt"), "hello");
        var filters = RequestFilters.create().add((request, next) -> {
            if (request.target().startsWith("/boom")) {
                throw new IllegalStateException("boom");
            }
            return next.proceed(request);
        });
        var err = SjwsServerTest.stderrOf(() -> this.server = SjwsServer.start(config(), filters));
        assertTrue(err.isEmpty(), err);
        try (var socket = new Socket(InetAddress.getLoopbackAddress(), this.server.tcpPort())) {
            socket.setSoTimeout(4000);
            var out = socket.getOutputStream();
            HttpTestSupport.write(out, "GET /boom HTTP/1.1\r\nHost: localhost\r\n\r\n");
            var failed = HttpTestSupport.readResponse(socket.getInputStream());
            HttpTestSupport.write(out, "GET /hello.txt HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
            var next = HttpTestSupport.readResponse(socket.getInputStream());
            assertEquals(500, failed.statusCode());
            assertTrue(failed.bodyText().contains("500 Internal Server Error"), failed.bodyText());
            assertEquals("keep-alive", failed.headers().get("connection"), failed.rawHead());
            assertEquals(200, next.statusCode());
            assertEquals("hello", next.bodyText());
        }
    }

    @Test
    void terminalReplacesTheFileHandlerAndFiltersWrapIt() throws Exception {
        Files.writeString(root.resolve("hello.txt"), "hello");
        Handler leaf = request -> new Response(200,
                ResponseHeaders.of().add("content-type", "text/plain"),
                Body.Bytes.of(("leaf:" + request.target()).getBytes(StandardCharsets.UTF_8)), request.headOnly());
        var filters = RequestFilters.create().add((request, next) -> {
            var response = next.proceed(request);
            response.headers().add("x-wrap", "1");
            return response;
        });
        var seen = new Object[2];
        var cfg = config().toBuilder().h3Enabled(true).h3Port(0).build();
        this.server = SjwsServer.start(cfg, new Transport.Factory() {
            @Override
            public Transport create(ServerConfig c, StaticFileHandler h) {
                throw new AssertionError("server calls the four-argument factory");
            }

            @Override
            public Transport create(ServerConfig c, StaticFileHandler h, FilterChain chain, Handler terminal) {
                seen[0] = chain;
                seen[1] = terminal;
                return new SjwsServerTest.FakeTransport(1, null);
            }
        }, filters, files -> leaf);
        assertSame(seen[0], this.server.filterChain());
        assertSame(seen[0], seen[1]);
        var response = HttpTestSupport.get(this.server.tcpPort(), "/hello.txt", 4000);
        assertEquals(200, response.statusCode());
        assertEquals("leaf:/hello.txt", response.bodyText());
        assertEquals("1", response.headers().get("x-wrap"));
    }

    @Test
    void absentTerminalAndAnIdentitySelectorStayOnTheFileHandler() throws Exception {
        Files.writeString(root.resolve("hello.txt"), "hello");
        this.server = SjwsServer.start(config(), null, files -> null);
        assertNull(this.server.filterChain());
        assertEquals("hello", HttpTestSupport.get(this.server.tcpPort(), "/hello.txt", 4000).bodyText());
        this.server.close();
        this.server = SjwsServer.start(config(), RequestFilters.create(), files -> files);
        assertNull(this.server.filterChain());
        assertEquals("hello", HttpTestSupport.get(this.server.tcpPort(), "/hello.txt", 4000).bodyText());
        this.server.close();
        Handler leaf = request -> text(request, "routed");
        this.server = SjwsServer.start(config(), null, files -> leaf);
        assertNull(this.server.filterChain());
        assertEquals("routed", HttpTestSupport.get(this.server.tcpPort(), "/hello.txt", 4000).bodyText());
    }

    @Test
    void unknownFilterNameFailsBeforeTheListenerBinds() {
        var filters = RequestFilters.create().add(MiddlewareSpec.of("missing"));
        assertThrows(IllegalArgumentException.class, () -> SjwsServer.start(config(), filters));
        assertNull(this.server);
    }

    private static Response text(Request request, String body) {
        return new Response(200, ResponseHeaders.of().add("content-type", "text/plain"),
                Body.Bytes.of(body.getBytes(StandardCharsets.UTF_8)), request.headOnly());
    }
}
