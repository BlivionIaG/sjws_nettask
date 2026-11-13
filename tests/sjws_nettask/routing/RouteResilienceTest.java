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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sjws_nettask.core.Body;
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

    @Test
    void localValidationErrorsDoNotCountAsCircuitSuccess() {
        this.failFirst = 100; // every upstream response is 503
        var wrap = new RouteResilience(this.proxy,
                new Resilience(null, new Resilience.Circuit(2, Duration.ofSeconds(30))));
        assertEquals(503, wrap.handle(new Request("GET", "/")).status());
        assertEquals("CLOSED", wrap.circuitState());
        assertEquals(1, this.hits.get());

        // Local 413/501/400 never contact the upstream and must not reset the failure run.
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
        var oversized = wrap.handle(new Request("POST", "/").withBody(huge));
        assertEquals(413, oversized.status());
        assertTrue(ProxyHandler.isLocalRefusal(oversized));
        assertEquals(1, this.hits.get());
        assertEquals("CLOSED", wrap.circuitState());

        var te = new Request("POST", "/",
                sjws_nettask.core.RequestHeaders.of(Map.of("transfer-encoding", List.of("chunked"))), null, false);
        var notImplemented = wrap.handle(te);
        assertEquals(501, notImplemented.status());
        assertTrue(ProxyHandler.isLocalRefusal(notImplemented));
        assertEquals(1, this.hits.get());

        var badTarget = wrap.handle(new Request("OPTIONS", "*"));
        assertEquals(400, badTarget.status());
        assertTrue(ProxyHandler.isLocalRefusal(badTarget));
        assertEquals(1, this.hits.get());

        // Second real failure opens the circuit: the local refusals did not clear the first.
        assertEquals(503, wrap.handle(new Request("GET", "/")).status());
        assertEquals(2, this.hits.get());
        assertEquals("OPEN", wrap.circuitState());
        assertEquals(503, wrap.handle(new Request("GET", "/")).status());
        assertEquals(2, this.hits.get(), "open circuit must not contact the upstream");
    }

    private HttpServer okServer(AtomicInteger okHits) throws Exception {
        var ok = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ok.createContext("/", ex -> {
            okHits.incrementAndGet();
            var body = "ok".getBytes();
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        ok.start();
        return ok;
    }

    @Test
    void weightedRouteRetryPrefersAnUntriedBackend() throws Exception {
        this.failFirst = Integer.MAX_VALUE; // this.upstream always answers 503
        var okHits = new AtomicInteger();
        var ok = okServer(okHits);
        var healthy = new ProxyHandler(new ProxyHandler.Settings(
                URI.create("http://127.0.0.1:" + ok.getAddress().getPort()), Duration.ofSeconds(1),
                Duration.ofSeconds(2), 1024, "http", null), ByteBudget.shared(), null);
        try {
            var weighted = new WeightedHandler(List.of(
                    new WeightedHandler.Backend(this.proxy, 99, null),
                    new WeightedHandler.Backend(healthy, 1, null)), null);
            var wrap = new RouteResilience(weighted, new Resilience(retry503(1), null));
            for (int i = 0; i < 50; i++) {
                assertEquals(200, wrap.handle(new Request("GET", "/")).status(),
                        "the retry must go to the untried healthy backend, try " + i);
            }
            assertEquals(50, okHits.get());
            assertTrue(this.hits.get() <= 50, "the failing backend is tried at most once per request");

            // A pin keeps its backend on retries.
            var pinned = new WeightedHandler(List.of(
                    new WeightedHandler.Backend(this.proxy, 0, new WeightedHandler.Match(false, "x-canary", null)),
                    new WeightedHandler.Backend(healthy, 1, null)), null);
            var pinWrap = new RouteResilience(pinned, new Resilience(retry503(1), null));
            int before = this.hits.get();
            int okBefore = okHits.get();
            var canary = new Request("GET", "/",
                    sjws_nettask.core.RequestHeaders.of(Map.of("x-canary", List.of("1"))), null, false);
            assertEquals(503, pinWrap.handle(canary).status());
            assertEquals(2, this.hits.get() - before, "a pinned request retries on its pinned backend");
            assertEquals(okBefore, okHits.get());
        } finally {
            healthy.close();
            ok.stop(0);
        }
    }

    @Test
    void weightedRouteRetryScopesEndpointHistoryToEachBalancer() throws Exception {
        int deadPort;
        try (var s = new ServerSocket(0)) {
            deadPort = s.getLocalPort();
        }
        var okHits = new AtomicInteger();
        var ok = okServer(okHits);
        EndpointBalancer.Pools pools = e -> new ProxyHandler(new ProxyHandler.Settings(
                URI.create("http://" + e.authority()), Duration.ofMillis(150), Duration.ofSeconds(1), 1024, "http",
                null), ByteBudget.shared(), null);
        var outlier = new EndpointBalancer.Outlier(100, 100, 1_000_000, Duration.ofSeconds(10),
                Duration.ofSeconds(30), 100);
        // A: one dead endpoint (index 0). B: index 0 is live and preferred, index 1 is dead.
        var a = new EndpointBalancer(new StaticUpstreamSource(new UpstreamSet("a", 1, new Endpoint[] {
                new Endpoint("a-dead", "127.0.0.1", deadPort, 1, Map.of(), Endpoint.Health.READY, null)},
                LbPolicy.WEIGHTED_RANDOM)), pools, null, outlier);
        var b = new EndpointBalancer(new StaticUpstreamSource(new UpstreamSet("b", 1, new Endpoint[] {
                new Endpoint("b-live", "127.0.0.1", ok.getAddress().getPort(), 1_000_000, Map.of(),
                        Endpoint.Health.READY, null),
                new Endpoint("b-dead", "127.0.0.1", deadPort, 1, Map.of(), Endpoint.Health.READY, null)},
                LbPolicy.WEIGHTED_RANDOM)), pools, null, outlier);
        try {
            var weighted = new WeightedHandler(List.of(
                    new WeightedHandler.Backend(a, 1_000_000, null),
                    new WeightedHandler.Backend(b, 1, null)), null);
            var wrap = new RouteResilience(weighted,
                    new Resilience(new Resilience.Retry(1, EnumSet.of(On.CONNECT), 100), null));
            for (int i = 0; i < 10; i++) {
                assertEquals(200, wrap.handle(new Request("GET", "/")).status(),
                        "B's endpoint 0 was never tried and must not be excluded, try " + i);
            }
            assertEquals(10, okHits.get());
        } finally {
            a.close();
            b.close();
            ok.stop(0);
        }
    }

    @Test
    void oneShotTakesAndReleasesASharedPendingSlot() {
        var admission = Admission.of(1, 0);
        var ep = Endpoint.of("127.0.0.1", this.upstream.getAddress().getPort());
        var src = new StaticUpstreamSource(new UpstreamSet("api", 1, new Endpoint[] {ep}, LbPolicy.ROUND_ROBIN));
        EndpointBalancer.Pools pools = e -> new ProxyHandler(new ProxyHandler.Settings(
                URI.create("http://" + e.authority()), Duration.ofSeconds(1), Duration.ofSeconds(2), 1024, "http",
                null), ByteBudget.shared(), null, admission);
        var balancer = new EndpointBalancer(src, pools, null, null, admission);
        try {
            var wrap = new RouteResilience(balancer, new Resilience(retry503(1), null));
            var ok = wrap.handle(new Request("GET", "/"));
            assertEquals(200, ok.status());
            Body.discard(ok.body());
            assertEquals(0, admission.pending());
            var stolen = admission.tryPending();
            assertNotNull(stolen);
            var refused = wrap.handle(new Request("GET", "/"));
            assertEquals(503, refused.status());
            assertTrue(ProxyHandler.isLocalRefusal(refused));
            assertEquals("1", refused.headers().get("retry-after"));
            stolen.release();
            var again = wrap.handle(new Request("GET", "/"));
            assertEquals(200, again.status());
            Body.discard(again.body());
            assertEquals(0, admission.pending());
        } finally {
            balancer.close();
        }
    }

    @Test
    void routeConnectRetryRethrowsUnderAPendingCapAndDoesNotLeakTheSlot() throws Exception {
        URI dead;
        try (var s = new ServerSocket(0)) {
            dead = URI.create("http://127.0.0.1:" + s.getLocalPort());
        }
        var admission = Admission.of(1, 0);
        var deadEp = new Endpoint("dead", "127.0.0.1", dead.getPort(), 1_000_000, Map.of(),
                Endpoint.Health.READY, null);
        var liveEp = new Endpoint("live", "127.0.0.1", this.upstream.getAddress().getPort(), 1, Map.of(),
                Endpoint.Health.READY, null);
        var src = new StaticUpstreamSource(new UpstreamSet("api", 1, new Endpoint[] {deadEp, liveEp},
                LbPolicy.WEIGHTED_RANDOM));
        EndpointBalancer.Pools pools = e -> new ProxyHandler(new ProxyHandler.Settings(
                URI.create("http://" + e.authority()), Duration.ofMillis(150), Duration.ofSeconds(1), 1024, "http",
                null), ByteBudget.shared(), null, admission);
        var balancer = new EndpointBalancer(src, pools, null, new EndpointBalancer.Outlier(100, 100, 1_000_000,
                Duration.ofSeconds(10), Duration.ofSeconds(30), 100), admission);
        try {
            var wrap = new RouteResilience(balancer,
                    new Resilience(new Resilience.Retry(1, EnumSet.of(On.CONNECT), 100), null));
            var recovered = wrap.handle(new Request("GET", "/"));
            assertEquals(200, recovered.status());
            Body.discard(recovered.body());
            assertEquals(1, this.hits.get());
            assertEquals(0, admission.pending());
        } finally {
            balancer.close();
        }
    }

    @Test
    void interruptingOneShotOrHandleReleasesThePendingSlot() throws Exception {
        var admission = Admission.of(1, 0);
        var ep = new Endpoint("blackhole", "192.0.2.1", 9, 1, Map.of(), Endpoint.Health.READY, null);
        var src = new StaticUpstreamSource(new UpstreamSet("api", 1, new Endpoint[] {ep}, LbPolicy.ROUND_ROBIN));
        EndpointBalancer.Pools pools = e -> new ProxyHandler(new ProxyHandler.Settings(
                URI.create("http://" + e.authority()), Duration.ofSeconds(5), Duration.ofSeconds(5), 1024, "http",
                null), ByteBudget.shared(), null, admission);
        var balancer = new EndpointBalancer(src, pools, null, null, admission);
        try {
            var interrupted = new AtomicBoolean();
            var error = new AtomicReference<Throwable>();
            var started = new CountDownLatch(1);
            Thread one = Thread.ofPlatform().start(() -> {
                started.countDown();
                try {
                    balancer.oneShot(new Request("GET", "/"));
                } catch (InterruptedException e) {
                    interrupted.set(true);
                } catch (Throwable e) {
                    error.set(e);
                }
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            Thread.sleep(200);
            one.interrupt();
            one.join(3000);
            assertTrue(interrupted.get(), "oneShot must rethrow the interrupt, not swallow it: " + error.get());
            assertNull(error.get());
            assertEquals(0, admission.pending());

            var returned502 = new AtomicBoolean();
            var handleErr = new AtomicReference<Throwable>();
            var handleStarted = new CountDownLatch(1);
            Thread handle = Thread.ofPlatform().start(() -> {
                handleStarted.countDown();
                try {
                    var r = balancer.handle(new Request("GET", "/"));
                    returned502.set(r.status() == 502);
                } catch (Throwable e) {
                    handleErr.set(e);
                }
            });
            assertTrue(handleStarted.await(2, TimeUnit.SECONDS));
            Thread.sleep(200);
            handle.interrupt();
            handle.join(3000);
            assertTrue(returned502.get(),
                    "handle must return 502 on interrupt, not leak: " + handleErr.get());
            assertNull(handleErr.get());
            assertEquals(0, admission.pending());
        } finally {
            balancer.close();
        }
    }

    @Test
    void oneShotOnAPlainProxyTakesAndReleasesASharedPendingSlot() {
        var admission = Admission.of(1, 0);
        var proxied = new ProxyHandler(new ProxyHandler.Settings(this.base, Duration.ofSeconds(1), Duration.ofSeconds(2),
                1024, "http", null), ByteBudget.shared(), null, admission);
        try {
            var wrap = new RouteResilience(proxied, new Resilience(retry503(1), null));
            var ok = wrap.handle(new Request("GET", "/"));
            assertEquals(200, ok.status());
            Body.discard(ok.body());
            assertEquals(0, admission.pending());
            var stolen = admission.tryPending();
            assertNotNull(stolen);
            var refused = wrap.handle(new Request("GET", "/"));
            assertEquals(503, refused.status());
            assertTrue(ProxyHandler.isLocalRefusal(refused));
            stolen.release();
            var again = wrap.handle(new Request("GET", "/"));
            assertEquals(200, again.status());
            Body.discard(again.body());
            assertEquals(0, admission.pending());

            var weighted = new WeightedHandler(List.of(new WeightedHandler.Backend(proxied, 1, null)), null);
            var wwrap = new RouteResilience(weighted, new Resilience(retry503(1), null));
            stolen = admission.tryPending();
            var wrefused = wwrap.handle(new Request("GET", "/"));
            assertEquals(503, wrefused.status());
            assertTrue(ProxyHandler.isLocalRefusal(wrefused));
            stolen.release();
            var wok = wwrap.handle(new Request("GET", "/"));
            assertEquals(200, wok.status());
            Body.discard(wok.body());
            assertEquals(0, admission.pending());
        } finally {
            proxied.close();
        }
    }

    @Test
    void oneShotOnAPlainProxyHoldsThePendingSlotWhileTheExchangeIsInFlight() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var heldUp = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        heldUp.createContext("/", ex -> {
            entered.countDown();
            try {
                if (!release.await(8, TimeUnit.SECONDS)) {
                    throw new RuntimeException("release timed out");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                ex.close();
                return;
            }
            var body = "ok".getBytes();
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        heldUp.start();
        var admission = Admission.of(1, 0);
        var proxied = new ProxyHandler(new ProxyHandler.Settings(
                URI.create("http://127.0.0.1:" + heldUp.getAddress().getPort()), Duration.ofSeconds(5),
                Duration.ofSeconds(5), 1024, "http", null), ByteBudget.shared(), null, admission);
        try {
            var wrap = new RouteResilience(proxied, new Resilience(retry503(1), null));
            var held = new AtomicReference<sjws_nettask.core.Response>();
            var err = new AtomicReference<Throwable>();
            var t = Thread.ofPlatform().start(() -> {
                try {
                    held.set(wrap.handle(new Request("GET", "/")));
                } catch (Throwable e) {
                    err.set(e);
                }
            });
            assertTrue(entered.await(8, TimeUnit.SECONDS));
            assertEquals(1, admission.pending());
            var refused = wrap.handle(new Request("GET", "/"));
            assertEquals(503, refused.status());
            assertTrue(ProxyHandler.isLocalRefusal(refused));
            release.countDown();
            t.join(8000);
            assertNull(err.get());
            assertEquals(200, held.get().status());
            Body.discard(held.get().body());
            assertEquals(0, admission.pending());
        } finally {
            proxied.close();
            heldUp.stop(0);
        }
    }

    @Test
    void routeRetryExcludesATriedEndpointAfterDiscoveryReordersTheView() throws Exception {
        this.failFirst = Integer.MAX_VALUE;
        var okHits = new AtomicInteger();
        var ok = okServer(okHits);
        var failEp = Endpoint.of("127.0.0.1", this.upstream.getAddress().getPort());
        var okEp = Endpoint.of("127.0.0.1", ok.getAddress().getPort());
        var src = new StaticUpstreamSource(new UpstreamSet("api", 1, new Endpoint[] {failEp}, LbPolicy.ROUND_ROBIN));
        EndpointBalancer.Pools pools = e -> new ProxyHandler(new ProxyHandler.Settings(
                URI.create("http://" + e.authority()), Duration.ofSeconds(1), Duration.ofSeconds(2), 1024, "http",
                null), ByteBudget.shared(), null);
        var balancer = new EndpointBalancer(src, pools, null, null);
        try {
            String[] tried = new String[2];
            int[] nTried = new int[1];
            var first = balancer.oneShot(new Request("GET", "/"), tried, nTried);
            assertEquals(503, first.status());
            Body.discard(first.body());
            assertEquals(1, nTried[0]);
            assertEquals(failEp.authority(), tried[0]);
            assertTrue(src.update(new UpstreamSet("api", 2, new Endpoint[] {okEp, failEp}, LbPolicy.ROUND_ROBIN)));
            var second = balancer.oneShot(new Request("GET", "/"), tried, nTried);
            assertEquals(200, second.status(),
                    "must not treat the new index-0 healthy endpoint as already tried");
            Body.discard(second.body());
            assertEquals(1, okHits.get());
        } finally {
            balancer.close();
            ok.stop(0);
        }
    }

    @Test
    void weightedRouteRetryReinvokesAPlainBackend() {
        var hits = new AtomicInteger();
        Handler plain = request -> StaticFileHandler.error(hits.incrementAndGet() == 1 ? 503 : 200,
                request.headOnly());
        var weighted = new WeightedHandler(List.of(new WeightedHandler.Backend(plain, 1, null)), null);
        var wrap = new RouteResilience(weighted, new Resilience(retry503(1), null));
        assertEquals(200, wrap.handle(new Request("GET", "/")).status());
        assertEquals(2, hits.get(), "route retry should re-invoke a plain weighted backend");
    }

    @Test
    void interruptingARouteOneShotReturns502AndReleasesTheCircuitAndPendingSlot() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var heldUp = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        heldUp.createContext("/", ex -> {
            entered.countDown();
            try {
                release.await(8, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            ex.close();
        });
        heldUp.start();
        var admission = Admission.of(1, 0);
        var proxied = new ProxyHandler(new ProxyHandler.Settings(
                URI.create("http://127.0.0.1:" + heldUp.getAddress().getPort()), Duration.ofSeconds(5),
                Duration.ofSeconds(5), 1024, "http", null), ByteBudget.shared(), null, admission);
        try {
            // threshold 1: counting the interrupt as a failure would open the circuit
            var wrap = new RouteResilience(proxied,
                    new Resilience(retry503(1), new Resilience.Circuit(1, Duration.ofSeconds(30))));
            var status = new AtomicInteger();
            var stillInterrupted = new AtomicBoolean();
            var err = new AtomicReference<Throwable>();
            var t = Thread.ofPlatform().start(() -> {
                try {
                    status.set(wrap.handle(new Request("GET", "/")).status());
                    stillInterrupted.set(Thread.currentThread().isInterrupted());
                } catch (Throwable e) {
                    err.set(e);
                }
            });
            assertTrue(entered.await(8, TimeUnit.SECONDS));
            assertEquals(1, admission.pending());
            t.interrupt();
            t.join(8000);
            assertNull(err.get());
            assertEquals(502, status.get());
            assertTrue(stillInterrupted.get(), "the interrupt flag must be restored");
            assertEquals(0, admission.pending());
            assertEquals("CLOSED", wrap.circuitState());
        } finally {
            release.countDown();
            proxied.close();
            heldUp.stop(0);
        }
    }
}
