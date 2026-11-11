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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sjws_nettask.core.Handler;
import sjws_nettask.core.Request;
import sjws_nettask.core.StaticFileHandler;
import sjws_nettask.routing.Resilience.On;
import sjws_nettask.routing.discovery.Endpoint;
import sjws_nettask.routing.discovery.LbPolicy;
import sjws_nettask.routing.discovery.StaticUpstreamSource;
import sjws_nettask.routing.discovery.UpstreamSet;

class RouteResilienceTest {

    private HttpServer upstream;
    private URI base;
    private final AtomicInteger hits = new AtomicInteger();
    private volatile int failFirst;
    private ProxyHandler proxy;

    @BeforeEach
    void start() throws Exception {
        this.upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.upstream.createContext("/", ex -> {
            int n = this.hits.incrementAndGet();
            boolean fail = n <= this.failFirst;
            var body = (fail ? "down" : "ok").getBytes();
            ex.sendResponseHeaders(fail ? 503 : 200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        this.upstream.start();
        this.base = URI.create("http://127.0.0.1:" + this.upstream.getAddress().getPort());
        this.proxy = new ProxyHandler(new ProxyHandler.Settings(this.base, Duration.ofSeconds(1), Duration.ofSeconds(2),
                1024, "http", null), ByteBudget.shared(), null);
    }

    @AfterEach
    void stop() {
        if (this.proxy != null) {
            this.proxy.close();
        }
        this.upstream.stop(0);
    }

    private static Resilience.Retry retry503(int attempts) {
        return new Resilience.Retry(attempts, EnumSet.of(On.STATUS_503), 100);
    }

    @Test
    void aRouteCanRetryWhileASiblingOnTheSamePoolDoesNot() {
        this.failFirst = 1;
        var retried = new RouteResilience(this.proxy, new Resilience(retry503(1), null));
        assertEquals(200, retried.handle(new Request("GET", "/")).status());
        assertEquals(2, this.hits.get());
        this.failFirst = 3; // next raw request is a failure
        assertEquals(503, this.proxy.handle(new Request("GET", "/")).status());
        assertEquals(3, this.hits.get()); // no extra try on the unwrapped route
    }

    @Test
    void aRouteCircuitDoesNotTripASiblingOnTheSamePool() {
        this.failFirst = 1;
        var isolated = new RouteResilience(this.proxy,
                new Resilience(null, new Resilience.Circuit(1, Duration.ofSeconds(30))));
        assertEquals(503, isolated.handle(new Request("GET", "/")).status());
        assertEquals("OPEN", isolated.circuitState());
        int afterOpen = this.hits.get();
        assertEquals(503, isolated.handle(new Request("GET", "/")).status());
        assertEquals(afterOpen, this.hits.get(), "open circuit must not contact the upstream");
        this.failFirst = 0;
        assertEquals(200, this.proxy.handle(new Request("GET", "/")).status());
        assertEquals(afterOpen + 1, this.hits.get());
    }

    @Test
    void handleFallsThroughToAPlainInner() {
        var hits = new AtomicInteger();
        Handler plain = request -> {
            hits.incrementAndGet();
            return StaticFileHandler.error(503, request.headOnly());
        };
        var wrap = new RouteResilience(plain, new Resilience(retry503(1), null));
        assertEquals(503, wrap.handle(new Request("GET", "/")).status());
        assertEquals(2, hits.get(), "route retry should re-invoke a plain inner");
        assertSame(plain, wrap.inner());
    }

    @Test
    void wrapsAWeightedBackendAndABalancerWithoutNestingRetries() {
        this.failFirst = 1;
        var weighted = new WeightedHandler(List.of(new WeightedHandler.Backend(this.proxy, 1, null)), null);
        var retried = new RouteResilience(weighted, new Resilience(retry503(1), null));
        assertEquals(200, retried.handle(new Request("GET", "/")).status());
        assertEquals(2, this.hits.get());

        var ep = Endpoint.of("127.0.0.1", this.upstream.getAddress().getPort());
        var src = new StaticUpstreamSource(new UpstreamSet("api", 1, new Endpoint[] {ep}, LbPolicy.ROUND_ROBIN));
        EndpointBalancer.Pools pools = e -> new ProxyHandler(new ProxyHandler.Settings(
                URI.create("http://" + e.authority()), Duration.ofSeconds(1), Duration.ofSeconds(2), 1024, "http",
                null), ByteBudget.shared(), new Resilience(retry503(2), null));
        var balancer = new EndpointBalancer(src, pools, retry503(2), null);
        try {
            // Two failures would succeed if the inner retry ran; the route allows only one retry.
            this.failFirst = this.hits.get() + 2;
            var wrap = new RouteResilience(balancer, new Resilience(retry503(1), null));
            int before = this.hits.get();
            assertEquals(503, wrap.handle(new Request("GET", "/")).status());
            assertEquals(2, this.hits.get() - before, "oneShot must skip the balancer's own retries");
        } finally {
            balancer.close();
        }
    }

    @Test
    void connectFailureIsMappedWhenTheRouteOnlyRetries503() throws Exception {
        URI dead;
        try (var s = new ServerSocket(0)) {
            dead = URI.create("http://127.0.0.1:" + s.getLocalPort());
        }
        var deadProxy = new ProxyHandler(new ProxyHandler.Settings(dead, Duration.ofMillis(200), Duration.ofSeconds(1),
                1024, "http", null), ByteBudget.shared(), null);
        try {
            var wrap = new RouteResilience(deadProxy, new Resilience(retry503(1), null));
            assertEquals(502, wrap.handle(new Request("GET", "/")).status());
        } finally {
            deadProxy.close();
        }
    }

    @Test
    void postDoesNotRetryAndLocalAdmissionDoesNotTripTheCircuit() {
        this.failFirst = 1;
        var retried = new RouteResilience(this.proxy, new Resilience(retry503(1), null));
        assertEquals("NONE", retried.circuitState());
        assertEquals(503, retried.handle(new Request("POST", "/")).status());
        assertEquals(1, this.hits.get());

        var hits = new AtomicInteger();
        Handler refused = request -> {
            hits.incrementAndGet();
            return StaticFileHandler.error(503, request.headOnly()).asLocalAdmission();
        };
        var wrap = new RouteResilience(refused,
                new Resilience(retry503(1), new Resilience.Circuit(1, Duration.ofSeconds(30))));
        assertEquals(503, wrap.handle(new Request("GET", "/")).status());
        assertEquals(1, hits.get(), "local admission must not be retried");
        assertEquals("CLOSED", wrap.circuitState());
        this.failFirst = 0;
        var ok = new RouteResilience(this.proxy,
                new Resilience(null, new Resilience.Circuit(1, Duration.ofSeconds(30))));
        assertEquals(200, ok.handle(new Request("GET", "/")).status());
        assertEquals("CLOSED", ok.circuitState());
    }

    @Test
    void connectFailureCanBeRetried() throws Exception {
        URI dead;
        try (var s = new ServerSocket(0)) {
            dead = URI.create("http://127.0.0.1:" + s.getLocalPort());
        }
        var deadProxy = new ProxyHandler(new ProxyHandler.Settings(dead, Duration.ofMillis(150), Duration.ofSeconds(1),
                1024, "http", null), ByteBudget.shared(), null);
        try {
            var wrap = new RouteResilience(deadProxy,
                    new Resilience(new Resilience.Retry(1, EnumSet.of(On.CONNECT), 100), null));
            assertEquals(502, wrap.handle(new Request("GET", "/")).status());
        } finally {
            deadProxy.close();
        }
    }

    @Test
    void balancerConnectRetryUsesTheOtherEndpointAndKeepsTheFailureKind() throws Exception {
        URI dead;
        try (var s = new ServerSocket(0)) {
            dead = URI.create("http://127.0.0.1:" + s.getLocalPort());
        }
        var deadEp = new Endpoint("dead", "127.0.0.1", dead.getPort(), 1_000_000, Map.of(),
                Endpoint.Health.READY, null);
        var liveEp = new Endpoint("live", "127.0.0.1", this.upstream.getAddress().getPort(), 1, Map.of(),
                Endpoint.Health.READY, null);
        var src = new StaticUpstreamSource(new UpstreamSet("api", 1, new Endpoint[] {deadEp, liveEp},
                LbPolicy.WEIGHTED_RANDOM));
        EndpointBalancer.Pools pools = e -> new ProxyHandler(new ProxyHandler.Settings(
                URI.create("http://" + e.authority()), Duration.ofMillis(150), Duration.ofSeconds(1), 1024, "http",
                null), ByteBudget.shared(), null);
        var balancer = new EndpointBalancer(src, pools, null, new EndpointBalancer.Outlier(100, 100, 1_000_000,
                Duration.ofSeconds(10), Duration.ofSeconds(30), 100));
        try {
            var wrap = new RouteResilience(balancer,
                    new Resilience(new Resilience.Retry(1, EnumSet.of(On.CONNECT), 100), null));
            assertEquals(200, wrap.handle(new Request("GET", "/")).status(),
                    "a connect failure must be retried on the other endpoint, not classified as 502");
            assertEquals(1, this.hits.get());
        } finally {
            balancer.close();
        }
    }
}
