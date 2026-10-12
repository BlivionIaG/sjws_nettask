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

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import sjws_nettask.core.Body;
import sjws_nettask.core.Handler;
import sjws_nettask.core.Request;
import sjws_nettask.core.RequestHeaders;
import sjws_nettask.core.Response;
import sjws_nettask.core.ResponseHeaders;

/** Edge cases of {@link ProxyHandler}, {@link UpstreamPool} and {@link Router#close()} against a scripted upstream. */
class ProxyEdgeTest {

    /** Answers each accepted connection with the next scripted reply, then closes it. */
    private static ServerSocket script(String... replies) throws IOException {
        var server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().start(() -> {
            for (var reply : replies) {
                try (var s = server.accept()) {
                    readHead(s.getInputStream());
                    s.getOutputStream().write(reply.getBytes(StandardCharsets.ISO_8859_1));
                    s.getOutputStream().flush();
                    s.shutdownOutput();
                    s.getInputStream().read();
                } catch (IOException e) {
                    return;
                }
            }
        });
        return server;
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

    private static ProxyHandler proxy(ServerSocket s, String path) {
        var d = Duration.ofSeconds(2);
        return new ProxyHandler(new ProxyHandler.Settings(URI.create("http://127.0.0.1:" + s.getLocalPort() + path), d, d, d,
                1 << 20, null, List.of(), false, 4));
    }

    private static String drain(Response r) throws IOException {
        if (!(r.body() instanceof Body.Stream st)) {
            return "";
        }
        var out = new java.io.ByteArrayOutputStream();
        try (var src = st.source()) {
            for (var b = src.next(); b != null; b = src.next()) {
                var c = new byte[b.remaining()];
                b.get(c);
                out.write(c);
            }
            assertNull(src.next(), "next() after the end stays null");
        }
        return out.toString(StandardCharsets.ISO_8859_1);
    }

    @Test
    void closeDelimitedResponseAndListedHeadersAreDropped() throws Exception {
        try (var s = script("HTTP/1.0 200 OK\r\nConnection: x-drop\r\nX-Drop: 1\r\nX-Keep: 2\r\n\r\nuntil-close",
                "HTTP/1.1 304 Not Modified\r\nETag: \"e\"\r\n\r\n", "HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok",
                "HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\nnext");
                var p = proxy(s, "/")) {
            var r = p.handle(new Request("GET", "/a"));
            assertEquals(200, r.status());
            assertNull(r.headers().get("x-drop"));
            assertEquals("2", r.headers().get("x-keep"));
            assertEquals(-1, ((Body.Stream) r.body()).length());
            assertEquals("until-close", drain(r));
            var notModified = p.handle(new Request("GET", "/b"));
            assertEquals(304, notModified.status());
            assertInstanceOf(Body.Empty.class, notModified.body());
            var afterContinue = p.handle(new Request("GET", "/c"));
            assertEquals(200, afterContinue.status());
            assertEquals("ok", drain(afterContinue));
            Thread.sleep(300); // the upstream closed the pooled connection; let Netty see it, acquire skips it
            assertEquals("next", drain(p.handle(new Request("GET", "/d"))));
        }
    }

    @Test
    void upstreamPathsAndHeaderValidation() throws Exception {
        try (var s = script("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
                var root = proxy(s, "/");
                var empty = proxy(s, "");
                var base = proxy(s, "/base")) {
            assertEquals("/base/x", base.buildRequest(new Request("GET", "/x"), 0).uri());
            assertEquals("/x", root.buildRequest(new Request("GET", "/x"), 0).uri());
            assertEquals("/x", empty.buildRequest(new Request("GET", "/x"), 0).uri());
            var tab = RequestHeaders.of(Map.of("x-t", List.of("a\tb"), "connection", List.of("x-hop"), "x-hop", List.of("1")));
            var built = root.buildRequest(new Request("GET", "/x", tab), 0);
            assertEquals("a\tb", built.headers().get("x-t"));
            assertNull(built.headers().get("x-hop"));
            for (var bad : List.of("a\u0001b", "a\u0000b")) {
                var h = RequestHeaders.of(Map.of("x-bad", List.of(bad)));
                assertEquals(400, root.handle(new Request("GET", "/x", h)).status(), bad);
            }
            assertEquals(400, root.handle(new Request("GET", "*")).status());
            assertEquals(200, root.handle(new Request("GET", "/ok")).status());
        }
    }

    @Test
    void closedPoolAndNonIoFailures() throws Exception {
        try (var s = script()) {
            var p = proxy(s, "/");
            p.close();
            p.close(); // idempotent
            assertEquals(502, p.handle(new Request("GET", "/")).status());
        }
        var https = new ProxyHandler.Settings(URI.create("https://example.test"));
        assertEquals("https://example.test", https.upstream().toString());
        assertThrows(IllegalArgumentException.class, () -> new ProxyHandler.Settings(URI.create("http://h"),
                Duration.ofSeconds(1), Duration.ofSeconds(1), 1, "ftp", null));
    }

    @Test
    void anUpstreamThatStopsReadingTheBodyTimesOut() throws Exception {
        try (var server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            var held = new java.util.concurrent.CompletableFuture<java.net.Socket>();
            Thread.ofVirtual().start(() -> {
                try {
                    held.complete(server.accept()); // accepted, never read
                } catch (IOException e) {
                    held.completeExceptionally(e);
                }
            });
            var d = Duration.ofMillis(300);
            int size = 64 << 20;
            try (var p = new ProxyHandler(new ProxyHandler.Settings(URI.create("http://127.0.0.1:" + server.getLocalPort()),
                    Duration.ofSeconds(2), d, d, size, null, List.of(), false, 1))) {
                var body = new sjws_nettask.core.RequestBody() {
                    long left = size;

                    @Override
                    public long length() {
                        return size;
                    }

                    @Override
                    public int read(byte[] into, int off, int len) {
                        if (this.left == 0) {
                            return -1;
                        }
                        int n = (int) Math.min(len, this.left);
                        this.left -= n;
                        return n;
                    }
                };
                var h = RequestHeaders.of(Map.of("content-length", List.of(Integer.toString(size))));
                assertEquals(504, p.handle(new Request("POST", "/up", h).withBody(body)).status());
            } finally {
                held.get().close();
            }
        }
        try (var defaultPort = new ProxyHandler(new ProxyHandler.Settings(URI.create("http://example.test")))) {
            assertEquals(0, defaultPort.idleConnections());
        }
    }

    @Test
    void routerClosesEachHandlerOnceAndSurvivesAFailingClose() {
        var closes = new AtomicInteger();
        class Closing implements Handler, AutoCloseable {
            final boolean fail;

            Closing(boolean fail) {
                this.fail = fail;
            }

            @Override
            public Response handle(Request request) {
                return new Response(200, ResponseHeaders.of(), Body.Empty.INSTANCE);
            }

            @Override
            public void close() throws Exception {
                closes.incrementAndGet();
                if (this.fail) {
                    throw new Exception("boom");
                }
            }
        }
        var shared = new Closing(false);
        Handler plain = request -> new Response(204, ResponseHeaders.of(), Body.Empty.INSTANCE);
        var router = new Router(List.of(new Router.Route(null, "/a", null, shared), new Router.Route(null, "/b", null, shared),
                new Router.Route(null, "/c", null, new Closing(true)), new Router.Route(null, "/d", null, plain)), null);
        router.close();
        assertEquals(2, closes.get());
    }
}
