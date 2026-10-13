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
import org.junit.jupiter.api.Timeout;
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
                java.net.Socket accepted;
                try {
                    accepted = server.accept();
                } catch (IOException e) {
                    return;
                }
                Thread.ofVirtual().start(() -> { // one thread per connection: slow readers do not serialise
                    try (var s = accepted) {
                        readHead(s.getInputStream());
                        s.getOutputStream().write(reply.getBytes(StandardCharsets.ISO_8859_1));
                        s.getOutputStream().flush();
                        s.shutdownOutput();
                        s.getInputStream().read();
                    } catch (IOException e) {
                        // the proxy closed first
                    }
                });
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

    /** F5: a slow reader never makes the proxy hold the upstream response; the global budget bounds the rest. */
    @Test
    void slowReadersAreBoundedByTheWindowAndTheGlobalBudget() throws Exception {
        int size = 8 << 20;
        var big = "HTTP/1.1 200 OK\r\nContent-Length: " + size + "\r\n\r\n" + "x".repeat(size);
        try (var s = script(big, big)) {
            var budget = new ByteBudget(64 * 1024);
            assertThrows(IllegalArgumentException.class, () -> new ByteBudget(0));
            var d = Duration.ofSeconds(5);
            try (var p = new ProxyHandler(new ProxyHandler.Settings(URI.create("http://127.0.0.1:" + s.getLocalPort()), d, d, d,
                    1 << 20, null, List.of(), false, 4), budget)) {
                assertSame(budget, p.budget());
                var a = (Body.Stream) p.handle(new Request("GET", "/a")).body();
                var b = (Body.Stream) p.handle(new Request("GET", "/b")).body();
                long max = 0;
                long total = 0;
                try (var sa = a.source(); var sb = b.source()) {
                    for (var bufA = sa.next(); bufA != null; bufA = sa.next()) {
                        total += bufA.remaining();
                        Thread.sleep(0, 200_000); // a slow client
                        max = Math.max(max, budget.inFlight());
                        var bufB = sb.next();
                        if (bufB != null) {
                            total += bufB.remaining();
                        }
                    }
                    for (var bufB = sb.next(); bufB != null; bufB = sb.next()) {
                        total += bufB.remaining();
                    }
                }
                assertEquals(2L * size, total);
                // the limit plus at most one socket read (128 KiB) per active exchange
                assertTrue(max <= budget.limit() + 2 * 128 * 1024 + 2 * 64 * 1024, "in flight peaked at " + max);
                assertEquals(0, budget.inFlight());
                assertTrue(ByteBudget.shared().limit() > 0);
            }
        }
    }

    @Test
    void closingAStreamEarlyReturnsItsBytesToTheBudget() throws Exception {
        int size = 4 << 20;
        try (var s = script("HTTP/1.1 200 OK\r\nContent-Length: " + size + "\r\n\r\n" + "y".repeat(size))) {
            var budget = new ByteBudget(1 << 20);
            var d = Duration.ofSeconds(5);
            try (var p = new ProxyHandler(new ProxyHandler.Settings(URI.create("http://127.0.0.1:" + s.getLocalPort()), d, d, d,
                    1 << 20, null, List.of(), false, 4), budget)) {
                var body = (Body.Stream) p.handle(new Request("GET", "/big")).body();
                var src = body.source();
                assertNotNull(src.next());
                for (int i = 0; i < 100 && budget.inFlight() < 64 * 1024; i++) {
                    Thread.sleep(10); // let the event loop read ahead
                }
                assertTrue(budget.inFlight() > 0);
                src.close(); // the client went away: queued buffers are released, the connection is not reused
                for (int i = 0; i < 100 && budget.inFlight() != 0; i++) {
                    Thread.sleep(10);
                }
                assertEquals(0, budget.inFlight());
                assertEquals(0, p.idleConnections());
            }
        }
    }

    /** Security review finding 1: a filter that replaces a proxied response must not leak budget or the connection. */
    @Test
    void aReplacedProxiedResponseReleasesItsBudget() throws Exception {
        int size = 8 << 20;
        try (var s = script("HTTP/1.1 200 OK\r\nContent-Length: " + size + "\r\n\r\n" + "z".repeat(size))) {
            var budget = new ByteBudget(16 << 20);
            var d = Duration.ofSeconds(30); // the idle backstop must not be what releases it
            try (var p = new ProxyHandler(new ProxyHandler.Settings(URI.create("http://127.0.0.1:" + s.getLocalPort()), d, d, d,
                    1 << 20, null, List.of(), false, 4), budget)) {
                var chain = sjws_nettask.core.RequestFilters.create().add((q, next) -> {
                    next.proceed(q);
                    return sjws_nettask.core.StaticFileHandler.error(403, false);
                }).build(p);
                assertEquals(403, chain.handle(new Request("GET", "/big")).status());
                for (int i = 0; i < 200 && budget.inFlight() != 0; i++) {
                    Thread.sleep(10);
                }
                assertEquals(0, budget.inFlight());
                assertEquals(0, p.idleConnections());
            }
        }
    }

    /** Backstop: a paused exchange whose body nobody takes is abandoned after twice the idle read timeout. */
    @Test
    void anUnreadPausedStreamIsAbandonedByTheBackstop() throws Exception {
        int size = 8 << 20;
        try (var s = script("HTTP/1.1 200 OK\r\nContent-Length: " + size + "\r\n\r\n" + "w".repeat(size))) {
            var budget = new ByteBudget(16 << 20);
            var idle = Duration.ofMillis(200);
            try (var p = new ProxyHandler(new ProxyHandler.Settings(URI.create("http://127.0.0.1:" + s.getLocalPort()),
                    Duration.ofSeconds(5), Duration.ofSeconds(5), idle, 1 << 20, null, List.of(), false, 4), budget)) {
                var dropped = p.handle(new Request("GET", "/big")); // never written, never closed (a bug elsewhere)
                assertInstanceOf(Body.Stream.class, dropped.body());
                for (int i = 0; i < 100 && budget.inFlight() == 0; i++) {
                    Thread.sleep(10);
                }
                assertTrue(budget.inFlight() > 0);
                for (int i = 0; i < 300 && budget.inFlight() != 0; i++) {
                    Thread.sleep(10);
                }
                assertEquals(0, budget.inFlight(), "the backstop released the window");
                var src = ((Body.Stream) dropped.body()).source();
                assertThrows(IOException.class, () -> {
                    while (src.next() != null) {
                        // a late reader sees the failure, not a truncated body
                    }
                });
                src.close();
            }
        }
    }

    /**
     * Under budget pressure the event loop caps socket reads at 16 KiB. Other exchanges already
     * hold the budget on the pressure line, and this client takes a chunk only once the budget is
     * exhausted: the capped read is queued, and releasing it leaves the pressure line intact so
     * the next refill stays capped.
     */
    @Test
    @Timeout(15)
    void readsShrinkUnderBudgetPressure() throws Exception {
        int size = 1 << 20;
        var budget = new ByteBudget(64 * 1024);
        long heldByOthers = budget.limit() - (budget.limit() >> 2);
        budget.add(heldByOthers);
        try (var s = script("HTTP/1.1 200 OK\r\nContent-Length: " + size + "\r\n\r\n" + "v".repeat(size))) {
            var d = Duration.ofSeconds(5);
            try (var p = new ProxyHandler(new ProxyHandler.Settings(URI.create("http://127.0.0.1:" + s.getLocalPort()), d, d, d,
                    1 << 20, null, List.of(), false, 4), budget)) {
                var src = ((Body.Stream) p.handle(new Request("GET", "/big")).body()).source();
                try {
                    long total = 0;
                    int large = 0;
                    int held = 0;
                    var first = new StringBuilder();
                    while (total < size) {
                        long inFlight = awaitExhausted(budget, total, size, heldByOthers);
                        var b = src.next();
                        if (b == null) {
                            break;
                        }
                        int n = b.remaining();
                        if (first.length() < 64) {
                            if (!first.isEmpty()) {
                                first.append(',');
                            }
                            first.append(n);
                        }
                        if (n > UpstreamPool.SMALL_READ) {
                            large++;
                        }
                        if (inFlight >= budget.limit()) {
                            held++;
                        }
                        total += n;
                    }
                    assertEquals(size, total);
                    assertEquals(heldByOthers, budget.inFlight());
                    assertTrue(held > 8, "takes while exhausted: " + held + " first sizes " + first);
                    assertEquals(0, large, "reads above 16 KiB under pressure, first sizes " + first);
                } finally {
                    src.close();
                }
            }
        } finally {
            budget.release(heldByOthers);
        }
    }

    /**
     * Spins until the budget is exhausted, or this exchange's unread tail is already queued.
     * Exhausted implies pressured, and with {@code heldByOthers} sitting on the pressure line a
     * 16 KiB take cannot clear it.
     */
    private static long awaitExhausted(ByteBudget budget, long taken, long size, long heldByOthers) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (true) {
            long inFlight = budget.inFlight();
            long body = inFlight - heldByOthers;
            if (inFlight >= budget.limit() || body > 0 && taken + body >= size) {
                return inFlight;
            }
            if (System.nanoTime() >= deadline) {
                fail("budget was not exhausted: inFlight=" + inFlight + " taken=" + taken + "/" + size);
            }
            Thread.onSpinWait();
        }
    }

    /** Once the budget is pressured, the allocator must not hand out a buffer above 16 KiB. */
    @Test
    void pressuredReadsStayAtMost16KiB() {
        var handle = (UpstreamPool.Reads) new UpstreamPool.ReadSizes().newHandle();
        for (int i = 0; i < 12; i++) {
            int guess = handle.guess();
            handle.lastBytesRead(guess);
            handle.readComplete();
        }
        assertTrue(handle.guess() > UpstreamPool.SMALL_READ, "adaptive guess should have grown");
        handle.small = true;
        for (int i = 0; i < 8; i++) {
            int guess = handle.guess();
            assertTrue(guess <= UpstreamPool.SMALL_READ, "pressured guess " + guess);
            handle.lastBytesRead(guess);
            handle.readComplete();
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
