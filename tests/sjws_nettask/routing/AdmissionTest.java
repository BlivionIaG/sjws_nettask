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
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.EnumSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import sjws_nettask.core.Body;
import sjws_nettask.core.Request;
import sjws_nettask.core.RequestHeaders;
import sjws_nettask.core.StaticFileHandler;

class AdmissionTest {

    private HttpServer upstream;
    private ProxyHandler proxy;
    private CountDownLatch release = new CountDownLatch(0);

    @AfterEach
    void stop() {
        this.release.countDown();
        if (this.proxy != null) {
            this.proxy.close();
        }
        if (this.upstream != null) {
            this.upstream.stop(0);
        }
    }

    @Test
    void ofIsNullWhenUnset() {
        assertNull(Admission.of(0, 0));
        assertThrows(IllegalArgumentException.class, () -> Admission.of(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> Admission.of(0, -1));
        var a = Admission.of(2, 0);
        assertTrue(a.limitsPending());
        assertFalse(a.limitsConnections());
        var first = a.tryPending();
        var second = a.tryPending();
        assertNotNull(first);
        assertNotNull(second);
        assertEquals(2, a.pending());
        assertNull(a.tryPending());
        first.release();
        first.release(); // idempotent
        assertEquals(1, a.pending());
        assertNotNull(a.tryPending());
        assertEquals(2, a.pending());
        var connectionsOnly = Admission.of(0, 2);
        assertFalse(connectionsOnly.limitsPending());
        assertTrue(connectionsOnly.limitsConnections());
        assertThrows(IllegalStateException.class, () -> a.tryConnection()); // pending-only: connections unset
        assertThrows(IllegalStateException.class, connectionsOnly::tryPending);
        var hold = connectionsOnly.tryConnection();
        assertNotNull(hold);
        hold.release();
    }

    @Test
    void underTheLimitSucceedsAndOverReturns503() throws Exception {
        var hits = new AtomicInteger();
        start(ex -> {
            hits.incrementAndGet();
            var body = "ok".getBytes();
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        this.proxy = proxy(Admission.of(2, 0));
        var a = this.proxy.handle(new Request("GET", "/"));
        var b = this.proxy.handle(new Request("GET", "/"));
        assertEquals(200, a.status());
        assertEquals("ok", ProxyHandlerTest.text(a));
        assertEquals(200, b.status());
        assertEquals("ok", ProxyHandlerTest.text(b));
        assertEquals(2, hits.get());
        assertEquals(0, this.proxy.admission().pending());
    }

    @Test
    void atTheLimitIs503UntilTheHeldExchangeCloses() throws Exception {
        var entered = new CountDownLatch(1);
        this.release = new CountDownLatch(1);
        var hits = new AtomicInteger();
        start(ex -> {
            hits.incrementAndGet();
            entered.countDown();
            if (!this.release.await(8, TimeUnit.SECONDS)) {
                throw new RuntimeException("release timed out");
            }
            var body = "ok".getBytes();
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        this.proxy = proxy(Admission.of(1, 0));
        var held = new java.util.concurrent.CompletableFuture<sjws_nettask.core.Response>();
        var t = Thread.ofPlatform().start(() -> held.complete(this.proxy.handle(new Request("GET", "/"))));
        assertTrue(entered.await(8, TimeUnit.SECONDS));
        assertEquals(1, this.proxy.admission().pending());
        var refused = this.proxy.handle(new Request("GET", "/"));
        assertEquals(503, refused.status());
        assertEquals("1", refused.headers().get("retry-after"));
        assertTrue(ProxyHandler.isLocalRefusal(refused));
        assertEquals(1, hits.get()); // the refused request never contacted the upstream
        this.release.countDown();
        var ok = held.get(8, TimeUnit.SECONDS);
        t.join();
        assertEquals(200, ok.status());
        ProxyHandlerTest.text(ok);
        assertEquals(0, this.proxy.admission().pending());
        var again = this.proxy.handle(new Request("GET", "/"));
        assertEquals(200, again.status());
        assertEquals("ok", ProxyHandlerTest.text(again));
        assertEquals(2, hits.get());
    }

    @Test
    void abortReleasesThePendingSlot() throws Exception {
        var entered = new CountDownLatch(1);
        this.release = new CountDownLatch(1);
        start(ex -> {
            entered.countDown();
            if (!this.release.await(8, TimeUnit.SECONDS)) {
                throw new RuntimeException("release timed out");
            }
            var body = "late".getBytes();
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        this.proxy = proxy(Admission.of(1, 0));
        var held = new java.util.concurrent.CompletableFuture<sjws_nettask.core.Response>();
        Thread.ofPlatform().start(() -> held.complete(this.proxy.handle(new Request("GET", "/"))));
        assertTrue(entered.await(8, TimeUnit.SECONDS));
        // still in flight: we cannot abort until the head arrives. Instead, after release, close
        // the stream without draining (client abort) and check the slot is free.
        this.release.countDown();
        var response = held.get(8, TimeUnit.SECONDS);
        assertEquals(200, response.status());
        assertEquals(1, this.proxy.admission().pending());
        Body.discard(response.body());
        assertEquals(0, this.proxy.admission().pending());
        var again = this.proxy.handle(new Request("GET", "/"));
        assertEquals(200, again.status());
        Body.discard(again.body());
    }

    @Test
    void timeoutReleasesThePendingSlot() throws Exception {
        start(ex -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            ex.sendResponseHeaders(200, 0);
            ex.close();
        });
        this.proxy = new ProxyHandler(new ProxyHandler.Settings(this.base(), Duration.ofMillis(200),
                Duration.ofMillis(200), 1024, "http", null), ByteBudget.shared(), null, Admission.of(1, 0));
        assertEquals(504, this.proxy.handle(new Request("GET", "/")).status());
        assertEquals(0, this.proxy.admission().pending());
        // the next request is also a timeout, but it is admitted (no leaked slot)
        assertEquals(504, this.proxy.handle(new Request("GET", "/")).status());
        assertEquals(0, this.proxy.admission().pending());
    }

    @Test
    void pendingRefusalDoesNotTripTheCircuit() throws Exception {
        var entered = new CountDownLatch(1);
        this.release = new CountDownLatch(1);
        var hits = new AtomicInteger();
        start(ex -> {
            hits.incrementAndGet();
            entered.countDown();
            if (!this.release.await(8, TimeUnit.SECONDS)) {
                throw new RuntimeException("release timed out");
            }
            var body = "ok".getBytes();
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        var resilience = new Resilience(null, new Resilience.Circuit(1, Duration.ofSeconds(30)));
        this.proxy = new ProxyHandler(new ProxyHandler.Settings(this.base(), Duration.ofSeconds(2),
                Duration.ofSeconds(2), 1024, "http", null), ByteBudget.shared(), resilience, Admission.of(1, 0));
        var held = new java.util.concurrent.CompletableFuture<sjws_nettask.core.Response>();
        Thread.ofPlatform().start(() -> held.complete(this.proxy.handle(new Request("GET", "/"))));
        assertTrue(entered.await(8, TimeUnit.SECONDS));
        for (int i = 0; i < 5; i++) {
            var refused = this.proxy.handle(new Request("GET", "/"));
            assertEquals(503, refused.status());
            assertTrue(ProxyHandler.isLocalRefusal(refused));
        }
        assertEquals("CLOSED", this.proxy.circuitState());
        this.release.countDown();
        ProxyHandlerTest.text(held.get(8, TimeUnit.SECONDS));
        assertEquals("CLOSED", this.proxy.circuitState());
        assertEquals(1, hits.get());
    }

    /** An unknown-length body that grows past max_body mid-forward is a local 413 that frees its pending slot. */
    @Test
    void oversizedStreamedBodyIs413AndReleasesThePendingSlot() throws Exception {
        start(ex -> {
            ex.getRequestBody().transferTo(java.io.OutputStream.nullOutputStream());
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        sjws_nettask.core.RequestBody oversized = new sjws_nettask.core.RequestBody() {
            @Override
            public long length() {
                return -1;
            }

            @Override
            public int read(byte[] into, int off, int len) {
                return Math.min(len, 8192);
            }
        };
        this.proxy = proxy(Admission.of(1, 0));
        assertEquals(413, this.proxy.handle(new Request("POST", "/").withBody(oversized)).status());
        assertEquals(0, this.proxy.admission().pending());
        // the balancer's one-shot path answers the same local 413 instead of throwing
        assertEquals(413, this.proxy.attempt(new Request("POST", "/").withBody(oversized)).status());
        var resilience = new Resilience(null, new Resilience.Circuit(1, Duration.ofSeconds(30)));
        try (var gated = new ProxyHandler(new ProxyHandler.Settings(this.base(), Duration.ofSeconds(2),
                Duration.ofSeconds(2), 1024, "http", null), ByteBudget.shared(), resilience, Admission.of(1, 0))) {
            assertEquals(413, gated.handle(new Request("POST", "/").withBody(oversized)).status());
            assertEquals(0, gated.admission().pending());
            assertEquals("CLOSED", gated.circuitState(), "a local 413 must not trip the circuit");
            var ok = gated.handle(new Request("GET", "/"));
            assertEquals(200, ok.status());
            ProxyHandlerTest.text(ok); // the slot is held until the body is consumed
            assertEquals(0, gated.admission().pending());
        }
    }

    @Test
    void maxConnectionsRefusesASecondSocketAndReusesIdle() throws Exception {
        var entered = new CountDownLatch(1);
        this.release = new CountDownLatch(1);
        var hits = new AtomicInteger();
        start(ex -> {
            hits.incrementAndGet();
            entered.countDown();
            if (!this.release.await(8, TimeUnit.SECONDS)) {
                throw new RuntimeException("release timed out");
            }
            var body = "ok".getBytes();
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        this.proxy = proxy(Admission.of(0, 1));
        var held = new java.util.concurrent.CompletableFuture<sjws_nettask.core.Response>();
        Thread.ofPlatform().start(() -> held.complete(this.proxy.handle(new Request("GET", "/"))));
        assertTrue(entered.await(8, TimeUnit.SECONDS));
        var refused = this.proxy.handle(new Request("GET", "/"));
        assertEquals(503, refused.status());
        assertEquals("1", refused.headers().get("retry-after"));
        assertTrue(ProxyHandler.isLocalRefusal(refused));
        assertEquals(1, hits.get());
        this.release.countDown();
        ProxyHandlerTest.text(held.get(8, TimeUnit.SECONDS));
        assertEquals(1, this.proxy.admission().connections()); // idle keep-alive still counts
        var again = this.proxy.handle(new Request("GET", "/"));
        assertEquals(200, again.status());
        assertEquals("ok", ProxyHandlerTest.text(again));
        assertEquals(2, hits.get());
    }

    @Test
    void withPendingReleasesANonStreamResponseAndAttemptRejectsLocally() throws Exception {
        start(ex -> {
            ex.sendResponseHeaders(200, 0);
            ex.close();
        });
        var admission = Admission.of(1, 0);
        var hold = admission.tryPending();
        var released = ProxyHandler.withPending(StaticFileHandler.error(502, false), hold);
        assertEquals(502, released.status());
        assertEquals(0, admission.pending());
        assertEquals(Resilience.On.STATUS_502, ProxyHandler.kind(502));
        assertEquals(Resilience.On.STATUS_504, ProxyHandler.kind(504));
        this.proxy = proxy(Admission.of(1, 0));
        var te = new Request("POST", "/", RequestHeaders.of(java.util.Map.of("transfer-encoding", java.util.List.of("chunked"))),
                null, false);
        assertEquals(501, this.proxy.attempt(te).status());
        var declared = new Request("POST", "/", RequestHeaders.of(java.util.Map.of("content-length", java.util.List.of("3"))),
                null, false);
        assertEquals(501, this.proxy.attempt(declared).status());
        var huge = new sjws_nettask.core.RequestBody() {
            @Override
            public long length() {
                return 10_000;
            }

            @Override
            public int read(byte[] into, int off, int len) {
                return -1;
            }
        };
        assertEquals(413, this.proxy.attempt(new Request("POST", "/").withBody(huge)).status());
        assertEquals(400, this.proxy.attempt(new Request("OPTIONS", "*")).status());
        assertEquals(0, this.proxy.admission().pending());
    }

    @Test
    void attemptConnectionLimitIsALocal503() throws Exception {
        var entered = new CountDownLatch(1);
        this.release = new CountDownLatch(1);
        start(ex -> {
            entered.countDown();
            if (!this.release.await(8, TimeUnit.SECONDS)) {
                throw new RuntimeException("release timed out");
            }
            var body = "ok".getBytes();
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        this.proxy = proxy(Admission.of(0, 1));
        var held = new java.util.concurrent.CompletableFuture<sjws_nettask.core.Response>();
        Thread.ofPlatform().start(() -> {
            try {
                held.complete(this.proxy.attempt(new Request("GET", "/")));
            } catch (Exception e) {
                held.completeExceptionally(e);
            }
        });
        assertTrue(entered.await(8, TimeUnit.SECONDS));
        var refused = this.proxy.attempt(new Request("GET", "/"));
        assertEquals(503, refused.status());
        assertTrue(ProxyHandler.isLocalRefusal(refused));
        this.release.countDown();
        assertEquals(200, held.get(8, TimeUnit.SECONDS).status());
    }

    @Test
    void connectFailureAndTimeoutReleaseTheConnectionSlot() throws Exception {
        int closed;
        try (var s = new java.net.ServerSocket(0)) {
            closed = s.getLocalPort();
        }
        var failed = Admission.of(0, 1);
        var refused = new ProxyHandler(new ProxyHandler.Settings(URI.create("http://127.0.0.1:" + closed),
                Duration.ofMillis(200), Duration.ofMillis(200), 1024, "http", null), ByteBudget.shared(), null, failed);
        try {
            assertEquals(502, refused.handle(new Request("GET", "/")).status());
            awaitNoConnections(failed);
            assertEquals(502, refused.handle(new Request("GET", "/")).status());
            awaitNoConnections(failed);
        } finally {
            refused.close();
        }
        var timed = Admission.of(0, 1);
        var blackhole = new ProxyHandler(new ProxyHandler.Settings(URI.create("http://192.0.2.1:9"),
                Duration.ofMillis(200), Duration.ofMillis(200), 1024, "http", null), ByteBudget.shared(), null, timed);
        try {
            assertEquals(504, blackhole.handle(new Request("GET", "/")).status());
            awaitNoConnections(timed);
        } finally {
            blackhole.close();
        }
    }

    @Test
    void resilientPathReleasesPendingWhenTheCircuitIsOpenAndOnAConnectionLimit() throws Exception {
        start(ex -> {
            var body = "ok".getBytes();
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        var open = new Resilience(null, new Resilience.Circuit(1, Duration.ofSeconds(30)));
        var pending = Admission.of(1, 0);
        int closedPort;
        try (var s = new java.net.ServerSocket(0)) {
            closedPort = s.getLocalPort();
        }
        var trip = new ProxyHandler(new ProxyHandler.Settings(URI.create("http://127.0.0.1:" + closedPort),
                Duration.ofMillis(200), Duration.ofMillis(200), 1024, "http", null), ByteBudget.shared(), open, pending);
        try {
            assertEquals(502, trip.handle(new Request("GET", "/")).status());
            var refused = trip.handle(new Request("GET", "/"));
            assertEquals(503, refused.status());
            assertEquals(0, pending.pending());
        } finally {
            trip.close();
        }
        var entered = new CountDownLatch(1);
        this.release = new CountDownLatch(1);
        this.upstream.stop(0);
        start(ex -> {
            entered.countDown();
            if (!this.release.await(8, TimeUnit.SECONDS)) {
                throw new RuntimeException("release timed out");
            }
            var body = "ok".getBytes();
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        var resilience = new Resilience(null, new Resilience.Circuit(5, Duration.ofSeconds(30)));
        this.proxy = new ProxyHandler(new ProxyHandler.Settings(this.base(), Duration.ofSeconds(2), Duration.ofSeconds(2),
                1024, "http", null), ByteBudget.shared(), resilience, Admission.of(0, 1));
        var held = new java.util.concurrent.CompletableFuture<sjws_nettask.core.Response>();
        Thread.ofPlatform().start(() -> held.complete(this.proxy.handle(new Request("GET", "/"))));
        assertTrue(entered.await(8, TimeUnit.SECONDS));
        var limited = this.proxy.handle(new Request("GET", "/"));
        assertEquals(503, limited.status());
        assertTrue(ProxyHandler.isLocalRefusal(limited));
        assertEquals("CLOSED", this.proxy.circuitState());
        this.release.countDown();
        assertEquals(200, held.get(8, TimeUnit.SECONDS).status());
        Body.discard(held.get().body());
    }

    @Test
    void retryAfterADiscarded5xxWaitsForTheConnectionSlot() throws Exception {
        var hits = new AtomicInteger();
        var failBody = "f".repeat(8192).getBytes();
        start(ex -> {
            int n = hits.incrementAndGet();
            if (n == 1) {
                ex.sendResponseHeaders(503, failBody.length);
                ex.getResponseBody().write(failBody);
            } else {
                var body = "ok".getBytes();
                ex.sendResponseHeaders(200, body.length);
                ex.getResponseBody().write(body);
            }
            ex.close();
        });
        var retry = new Resilience.Retry(1, EnumSet.of(Resilience.On.STATUS_503), 100);
        this.proxy = new ProxyHandler(new ProxyHandler.Settings(base(), Duration.ofSeconds(2), Duration.ofSeconds(2),
                1024, "http", null), ByteBudget.shared(), new Resilience(retry, null), Admission.of(0, 1));
        var response = this.proxy.handle(new Request("GET", "/"));
        assertEquals(200, response.status(), "the retry must not see a local 503 at the connection cap");
        assertEquals("ok", ProxyHandlerTest.text(response));
        assertEquals(2, hits.get());
    }

    @FunctionalInterface
    private interface Exchange {
        void handle(com.sun.net.httpserver.HttpExchange ex) throws java.io.IOException, InterruptedException;
    }

    private void start(Exchange handler) throws Exception {
        this.upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.upstream.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(r -> {
            var t = new Thread(r);
            t.setDaemon(true);
            return t;
        }));
        this.upstream.createContext("/", ex -> {
            try {
                handler.handle(ex);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                ex.close();
            }
        });
        this.upstream.start();
    }

    private URI base() {
        return URI.create("http://127.0.0.1:" + this.upstream.getAddress().getPort());
    }

    private ProxyHandler proxy(Admission admission) {
        return new ProxyHandler(new ProxyHandler.Settings(base(), Duration.ofSeconds(2), Duration.ofSeconds(2), 1024,
                "http", null), ByteBudget.shared(), null, admission);
    }

    /** closeFuture listeners can lag the handle() return; wait for the cap to drop. */
    private static void awaitNoConnections(Admission admission) throws InterruptedException {
        for (int i = 0; i < 50 && admission.connections() > 0; i++) {
            Thread.sleep(20);
        }
        assertEquals(0, admission.connections());
    }

    @Test
    void retryAfterADiscarded5xxDoesNotWaitWhenConnectionLimitingIsDisabled() throws Exception {
        assertDoesNotWaitOnDiscardedClose(null);
        if (this.proxy != null) {
            this.proxy.close();
            this.proxy = null;
        }
        if (this.upstream != null) {
            this.upstream.stop(0);
            this.upstream = null;
        }
        assertDoesNotWaitOnDiscardedClose(Admission.of(1, 0));
    }

    private void assertDoesNotWaitOnDiscardedClose(Admission admission) throws Exception {
        var hits = new AtomicInteger();
        var enteredSecond = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        start(ex -> {
            int n = hits.incrementAndGet();
            if (n == 1) {
                var failBody = "f".repeat(8192).getBytes();
                ex.sendResponseHeaders(503, failBody.length);
                ex.getResponseBody().write(failBody);
                if (!releaseFirst.await(8, TimeUnit.SECONDS)) {
                    throw new RuntimeException("release timed out");
                }
            } else {
                enteredSecond.countDown();
                var body = "ok".getBytes();
                ex.sendResponseHeaders(200, body.length);
                ex.getResponseBody().write(body);
            }
            ex.close();
        });
        var retry = new Resilience.Retry(1, EnumSet.of(Resilience.On.STATUS_503), 100);
        this.proxy = new ProxyHandler(new ProxyHandler.Settings(base(), Duration.ofSeconds(5), Duration.ofSeconds(2),
                1024, "http", null), ByteBudget.shared(), new Resilience(retry, null), admission);
        long t0 = System.nanoTime();
        var response = this.proxy.handle(new Request("GET", "/"));
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        assertEquals(200, response.status());
        assertEquals("ok", ProxyHandlerTest.text(response));
        assertEquals(2, hits.get());
        assertTrue(enteredSecond.await(0, TimeUnit.MILLISECONDS),
                "the retry must start before the discarded 5xx connection closes");
        assertTrue(ms < 1500, "must not await the discarded close when uncapped: " + ms);
        releaseFirst.countDown();
    }
}
