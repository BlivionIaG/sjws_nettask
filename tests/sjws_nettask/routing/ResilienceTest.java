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
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sjws_nettask.core.Request;
import sjws_nettask.routing.Resilience.On;

class ResilienceTest {

    private HttpServer upstream;
    private URI base;
    private final AtomicInteger hits = new AtomicInteger();
    /** The first {@code failFirst} requests answer 503 (or reset, for /reset). */
    private volatile int failFirst;

    @BeforeEach
    void start() throws Exception {
        this.upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.upstream.createContext("/", ex -> {
            int n = this.hits.incrementAndGet();
            boolean fail = n <= this.failFirst;
            if (fail && ex.getRequestURI().getPath().startsWith("/reset")) {
                ex.close();
                return;
            }
            byte[] body = (fail ? "down" : "ok").getBytes();
            ex.sendResponseHeaders(fail ? 503 : 200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        this.upstream.start();
        this.base = URI.create("http://127.0.0.1:" + this.upstream.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        this.upstream.stop(0);
    }

    private static Resilience.Retry retry(int attempts, int budget) {
        return new Resilience.Retry(attempts, EnumSet.of(On.CONNECT, On.RESET, On.STATUS_503), budget);
    }

    private ProxyHandler proxy(URI uri, Resilience r) {
        return new ProxyHandler(new ProxyHandler.Settings(uri, Duration.ofSeconds(1), Duration.ofSeconds(2), 1024,
                "http", null), ByteBudget.shared(), r);
    }

    private static URI dead() throws Exception {
        try (var s = new ServerSocket(0)) {
            return URI.create("http://127.0.0.1:" + s.getLocalPort());
        }
    }

    @Test
    void validation() {
        assertThrows(IllegalArgumentException.class, () -> new Resilience(null, null));
        assertThrows(IllegalArgumentException.class, () -> new Resilience.Retry(0, Set.of(On.CONNECT), 20));
        assertThrows(IllegalArgumentException.class, () -> new Resilience.Retry(6, Set.of(On.CONNECT), 20));
        assertThrows(IllegalArgumentException.class, () -> new Resilience.Retry(1, Set.of(On.CONNECT), 0));
        assertThrows(IllegalArgumentException.class, () -> new Resilience.Retry(1, Set.of(On.CONNECT), 101));
        assertThrows(IllegalArgumentException.class, () -> new Resilience.Circuit(0, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new Resilience.Circuit(1, Duration.ZERO));
    }

    @Test
    void budgetRefillsAndCaps() {
        var b = new Resilience.RetryBudget(50);
        for (int i = 0; i < 10; i++) {
            assertTrue(b.withdraw());
        }
        assertFalse(b.withdraw());
        b.deposit();
        assertEquals(500, b.tokens());
        assertFalse(b.withdraw());
        b.deposit();
        assertTrue(b.withdraw());
        for (int i = 0; i < 100; i++) {
            b.deposit();
        }
        assertEquals(10_000, b.tokens());
    }

    @Test
    void breakerStates() throws Exception {
        var c = new Resilience.CircuitBreaker(new Resilience.Circuit(2, Duration.ofMillis(50)));
        assertTrue(c.allow());
        c.success();
        c.failure();
        assertEquals(Resilience.CircuitBreaker.State.CLOSED, c.state());
        c.failure();
        assertEquals(Resilience.CircuitBreaker.State.OPEN, c.state());
        assertFalse(c.allow());
        assertTrue(c.retryAfterSeconds() >= 1);
        Thread.sleep(80);
        assertEquals(Resilience.CircuitBreaker.State.HALF_OPEN, c.state());
        assertTrue(c.allow());
        assertFalse(c.allow()); // one probe at a time
        c.release();
        assertTrue(c.allow());
        c.failure(); // failed probe: open for 2x
        assertEquals(Resilience.CircuitBreaker.State.OPEN, c.state());
        Thread.sleep(130);
        assertTrue(c.allow());
        c.success();
        assertEquals(Resilience.CircuitBreaker.State.CLOSED, c.state());
        assertTrue(c.allow());
    }

    @Test
    void retries503ThenSucceeds() {
        this.failFirst = 2;
        var p = proxy(this.base, new Resilience(retry(2, 20), null));
        var r = p.handle(new Request("GET", "/"));
        assertEquals(200, r.status());
        assertEquals("ok", ProxyHandlerTest.text(r));
        assertEquals(3, this.hits.get());
    }

    @Test
    void retriesAReset() {
        this.failFirst = 1;
        var p = proxy(this.base, new Resilience(retry(1, 20), null));
        assertEquals(200, p.handle(new Request("GET", "/reset")).status());
    }

    @Test
    void attemptsAreBounded() {
        this.failFirst = 100;
        var r = proxy(this.base, new Resilience(retry(2, 100), null)).handle(new Request("GET", "/"));
        assertEquals(503, r.status());
        assertEquals("down", ProxyHandlerTest.text(r));
        assertEquals(3, this.hits.get());
    }

    @Test
    void postAndUnlistedFailuresAreNotRetried() {
        this.failFirst = 100;
        var p = proxy(this.base, new Resilience(retry(3, 100), null));
        assertEquals(503, p.handle(new Request("POST", "/")).status());
        assertEquals(1, this.hits.get());
        var only502 = proxy(this.base, new Resilience(new Resilience.Retry(3, Set.of(On.STATUS_502), 100), null));
        assertEquals(503, only502.handle(new Request("GET", "/")).status());
        assertEquals(2, this.hits.get());
    }

    @Test
    void budgetStopsRetryStorms() {
        this.failFirst = 1000;
        var p = proxy(this.base, new Resilience(retry(1, 1), null));
        for (int i = 0; i < 30; i++) {
            assertEquals(503, p.handle(new Request("GET", "/")).status());
        }
        assertTrue(this.hits.get() <= 30 + 11, "hits " + this.hits.get());
    }

    @Test
    void connectFailureIsRetriedThen502() throws Exception {
        var p = proxy(dead(), new Resilience(retry(2, 20), null));
        assertEquals(502, p.handle(new Request("GET", "/")).status());
    }

    @Test
    void openBreakerAnswers503WithRetryAfter() throws Exception {
        var p = proxy(dead(), new Resilience(null, new Resilience.Circuit(2, Duration.ofSeconds(30))));
        assertEquals("CLOSED", p.circuitState());
        assertEquals(502, p.handle(new Request("GET", "/")).status());
        assertEquals(502, p.handle(new Request("GET", "/")).status());
        assertEquals("OPEN", p.circuitState());
        var r = p.handle(new Request("HEAD", "/"));
        assertEquals(503, r.status());
        assertEquals("30", r.headers().get("retry-after"));
    }

    @Test
    void halfOpenProbeCloses() throws Exception {
        this.failFirst = 2;
        var p = proxy(this.base, new Resilience(null, new Resilience.Circuit(2, Duration.ofMillis(50))));
        assertEquals(503, p.handle(new Request("GET", "/")).status());
        assertEquals(503, p.handle(new Request("GET", "/")).status());
        assertEquals("OPEN", p.circuitState());
        assertEquals(503, p.handle(new Request("GET", "/")).status());
        assertEquals(2, this.hits.get()); // short-circuited
        Thread.sleep(80);
        assertEquals("HALF_OPEN", p.circuitState());
        assertEquals(200, p.handle(new Request("GET", "/")).status());
        assertEquals("CLOSED", p.circuitState());
    }

    @Test
    void interruptReleasesTheProbe() throws Exception {
        this.failFirst = 1;
        var p = proxy(this.base, new Resilience(retry(1, 20), new Resilience.Circuit(1, Duration.ofMillis(30))));
        p.handle(new Request("POST", "/"));
        Thread.sleep(50);
        Thread.currentThread().interrupt();
        try {
            assertEquals(502, p.handle(new Request("GET", "/")).status());
        } finally {
            Thread.interrupted();
        }
        assertEquals(200, p.handle(new Request("GET", "/")).status()); // the probe was not stuck
    }

    @Test
    void aRecoveredRetryIsASuccessForTheBreaker() {
        this.failFirst = 1;
        var p = proxy(this.base, new Resilience(retry(1, 20), new Resilience.Circuit(1, Duration.ofSeconds(30))));
        assertEquals(200, p.handle(new Request("GET", "/")).status());
        assertEquals("CLOSED", p.circuitState());
    }

    @Test
    void offIsThePlainPath() {
        assertEquals("CLOSED", new ProxyHandler(new ProxyHandler.Settings(this.base)).circuitState());
    }
}
