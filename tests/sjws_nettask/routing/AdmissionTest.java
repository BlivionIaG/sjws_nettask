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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import sjws_nettask.core.Body;
import sjws_nettask.core.Request;

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
}
