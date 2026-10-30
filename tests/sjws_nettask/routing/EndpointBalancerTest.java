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
import sjws_nettask.core.RequestHeaders;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sjws_nettask.core.Request;
import sjws_nettask.core.StaticFileHandler;
import sjws_nettask.routing.discovery.Endpoint;
import sjws_nettask.routing.discovery.Endpoint.Health;
import sjws_nettask.routing.discovery.LbPolicy;
import sjws_nettask.routing.discovery.StaticUpstreamSource;
import sjws_nettask.routing.discovery.UpstreamSet;

class EndpointBalancerTest {

    private final List<HttpServer> servers = new ArrayList<>();
    private final AtomicLong now = new AtomicLong(1_000_000_000L);
    private final List<EndpointBalancer> balancers = new ArrayList<>();
    /** Answers its name; a name in {@code failing} answers 503. */
    private final java.util.Set<String> failing = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @BeforeEach
    void start() throws Exception {
        for (var name : List.of("a", "b", "c")) {
            var s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            s.createContext("/", ex -> {
                boolean fail = this.failing.contains(name);
                var body = name.getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(fail ? 503 : 200, body.length);
                ex.getResponseBody().write(body);
                ex.close();
            });
            s.start();
            this.servers.add(s);
        }
    }

    @AfterEach
    void stop() {
        this.balancers.forEach(EndpointBalancer::close);
        this.servers.forEach(s -> s.stop(0));
    }

    private Endpoint ep(int i, int weight) {
        return new Endpoint(null, "127.0.0.1", this.servers.get(i).getAddress().getPort(), weight, null, Health.READY, null);
    }

    private static Endpoint dead() throws Exception {
        try (var s = new ServerSocket(0)) {
            return Endpoint.of("127.0.0.1", s.getLocalPort());
        }
    }

    private static UpstreamSet set(long v, LbPolicy p, Endpoint... e) {
        return new UpstreamSet("api", v, e, p);
    }

    private EndpointBalancer balancer(StaticUpstreamSource src, Resilience.Retry retry, EndpointBalancer.Outlier o,
            Duration grace) {
        EndpointBalancer.Pools pools = e -> new ProxyHandler(new ProxyHandler.Settings(
                URI.create("http://" + e.authority()), Duration.ofMillis(500), Duration.ofSeconds(2), 1024, "http", null));
        var b = new EndpointBalancer(src, pools, retry, o, grace, this.now::get);
        this.balancers.add(b);
        return b;
    }

    private EndpointBalancer balancer(UpstreamSet initial) {
        return balancer(new StaticUpstreamSource(initial), null, null, Duration.ofSeconds(10));
    }

    private static String body(sjws_nettask.core.Response r) {
        return ProxyHandlerTest.text(r);
    }

    private Map<String, Integer> spread(EndpointBalancer b, int n) {
        var counts = new HashMap<String, Integer>();
        for (int i = 0; i < n; i++) {
            var r = b.handle(new Request("GET", "/"));
            counts.merge(r.status() == 200 ? body(r) : "status" + r.status(), 1, Integer::sum);
        }
        return counts;
    }

    @Test
    void neverPopulatedIs503() {
        var b = balancer(new StaticUpstreamSource("api"), null, null, Duration.ZERO);
        assertEquals(503, b.handle(new Request("GET", "/")).status());
        assertNull(b.currentSet());
    }

    @Test
    void weightedRandomFollowsWeightsAndSuspectGetsNone() {
        var b = balancer(set(1, null, ep(0, 3), ep(1, 1),
                new Endpoint(null, "127.0.0.1", this.servers.get(2).getAddress().getPort(), 5, null, Health.SUSPECT, null)));
        var counts = spread(b, 400);
        assertNull(counts.get("c"), counts.toString());
        assertTrue(counts.get("a") > 240 && counts.get("b") > 60, counts.toString());
    }

    @Test
    void allSuspectStillServes() {
        var s = new Endpoint(null, "127.0.0.1", this.servers.get(0).getAddress().getPort(), 1, null, Health.SUSPECT, null);
        var b = balancer(set(1, LbPolicy.ROUND_ROBIN, s));
        assertEquals("a", body(b.handle(new Request("GET", "/"))));
    }

    @Test
    void roundRobinCyclesAndLeastRequestServes() {
        var rr = balancer(set(1, LbPolicy.ROUND_ROBIN, ep(0, 1), ep(1, 1), ep(2, 0)));
        var seq = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            seq.append(body(rr.handle(new Request("GET", "/"))));
        }
        assertTrue(seq.toString().equals("abab") || seq.toString().equals("baba"), seq.toString());
        var lr = balancer(set(1, LbPolicy.LEAST_REQUEST, ep(0, 1), ep(1, 1)));
        var counts = spread(lr, 100);
        assertEquals(100, counts.getOrDefault("a", 0) + counts.getOrDefault("b", 0));
    }

    @Test
    void swapsKeepPoolsAndCloseRemovedOnesAfterGrace() throws Exception {
        var src = new StaticUpstreamSource(set(1, null, ep(0, 1), ep(1, 1)));
        var b = balancer(src, null, null, Duration.ofMillis(50));
        var poolA = b.poolOf(ep(0, 1).authority());
        var poolB = b.poolOf(ep(1, 1).authority());
        assertTrue(src.update(set(2, null, ep(0, 1), ep(2, 1))));
        assertSame(poolA, b.poolOf(ep(0, 1).authority()));
        assertNull(b.poolOf(ep(1, 1).authority()));
        assertEquals(2, b.poolCount());
        assertEquals(2, b.currentSet().version());
        Thread.sleep(300);
        assertEquals(502, poolB.proxy.handle(new Request("GET", "/")).status()); // closed after the grace
        var counts = spread(b, 60);
        assertNull(counts.get("b"));
        b.close();
        b.close();
        assertTrue(src.update(set(3, null, ep(1, 1)))); // a closed balancer ignores later sets
        assertEquals(503, b.handle(new Request("GET", "/")).status());
    }

    @Test
    void aRemovedEndpointDropsIdleConnectionsAtOnceButServesThroughTheGrace() throws Exception {
        var src = new StaticUpstreamSource(set(1, null, ep(0, 1), ep(1, 1)));
        var b = balancer(src, null, null, Duration.ofSeconds(30));
        var poolB = b.poolOf(ep(1, 1).authority());
        assertEquals("b", body(poolB.proxy.handle(new Request("GET", "/")))); // read: the connection goes idle
        for (int i = 0; i < 100 && poolB.proxy.idleConnections() == 0; i++) {
            Thread.sleep(10);
        }
        assertEquals(1, poolB.proxy.idleConnections());
        assertTrue(src.update(set(2, null, ep(0, 1))));
        assertEquals(0, poolB.proxy.idleConnections()); // retired: idle sockets closed now
        assertEquals("b", body(poolB.proxy.handle(new Request("GET", "/")))); // a late request still works
        assertEquals(0, poolB.proxy.idleConnections()); // and its connection is not kept
        b.close();
    }

    @Test
    void consecutiveFailuresEjectThenOneProbeReadmits() {
        var o = new EndpointBalancer.Outlier(3, 100, 1_000_000, Duration.ofSeconds(10), Duration.ofSeconds(1), 100);
        var b = balancer(new StaticUpstreamSource(set(1, null, ep(0, 1), ep(1, 1))), null, o, Duration.ZERO);
        this.failing.add("a");
        var counts = spread(b, 60);
        assertTrue(counts.getOrDefault("status503", 0) <= 3, counts.toString()); // ejected after 3 in a row
        assertEquals(1, b.ejectedCount());
        var pool = b.poolOf(ep(0, 1).authority());
        assertTrue(pool.health.ejected());
        this.now.addAndGet(1_100_000_000L); // eject_for elapsed: one probe, which fails
        spread(b, 40);
        assertTrue(pool.health.ejected());
        this.now.addAndGet(1_100_000_000L); // doubled: still out
        assertFalse(pool.health.available(this.now.get()));
        this.failing.clear();
        this.now.addAndGet(1_000_000_000L);
        spread(b, 40); // probe succeeds
        assertFalse(pool.health.ejected());
        assertEquals(0, b.ejectedCount());
        assertTrue(spread(b, 60).getOrDefault("a", 0) > 5);
    }

    @Test
    void errorRateWindowEjects() {
        var o = new EndpointBalancer.Outlier(1000, 30, 10, Duration.ofSeconds(10), Duration.ofSeconds(60), 100);
        var b = balancer(new StaticUpstreamSource(set(1, LbPolicy.ROUND_ROBIN, ep(0, 1))), null, o, Duration.ZERO);
        var h = b.poolOf(ep(0, 1).authority()).health;
        for (int i = 0; i < 7; i++) {
            h.success(this.now.get());
        }
        h.failure(this.now.get());
        h.failure(this.now.get());
        assertFalse(h.ejected()); // 2 of 9 is under the minimum count
        h.failure(this.now.get()); // 3 of 10 = 30%
        assertTrue(h.ejected());
        h.success(this.now.get()); // a straggler success readmits
        assertFalse(h.ejected());
        this.now.addAndGet(20_000_000_000L); // the window rotated: old failures are gone
        for (int i = 0; i < 9; i++) {
            h.success(this.now.get());
        }
        h.failure(this.now.get());
        assertFalse(h.ejected());
    }

    @Test
    void panicIgnoresEjectionPastMaxEjection() {
        var o = new EndpointBalancer.Outlier(1, 100, 1_000_000, Duration.ofSeconds(10), Duration.ofSeconds(60), 0);
        var b = balancer(new StaticUpstreamSource(set(1, null, ep(0, 1), ep(1, 1))), null, o, Duration.ZERO);
        this.failing.add("a");
        this.failing.add("b");
        spread(b, 20);
        assertEquals(2, b.ejectedCount());
        this.failing.clear();
        var counts = spread(b, 40); // max_ejection 0%: panic, everything still used
        assertEquals(40, counts.getOrDefault("a", 0) + counts.getOrDefault("b", 0), counts.toString());
    }

    @Test
    void everythingEjectedBelowPanicStillAnswers() {
        var o = new EndpointBalancer.Outlier(1, 100, 1_000_000, Duration.ofSeconds(10), Duration.ofSeconds(60), 100);
        var b = balancer(new StaticUpstreamSource(set(1, null, ep(0, 1))), null, o, Duration.ZERO);
        this.failing.add("a");
        assertEquals(503, b.handle(new Request("GET", "/")).status());
        this.failing.clear();
        assertEquals(200, b.handle(new Request("GET", "/")).status()); // no other endpoint: use it anyway
    }

    @Test
    void retriesGoToADifferentEndpoint() throws Exception {
        var retry = new Resilience.Retry(1, EnumSet.of(Resilience.On.CONNECT, Resilience.On.STATUS_503), 100);
        var o = new EndpointBalancer.Outlier(1000, 100, 1_000_000, Duration.ofSeconds(10), Duration.ofSeconds(1), 100);
        var b = balancer(new StaticUpstreamSource(set(1, LbPolicy.ROUND_ROBIN, dead(), ep(1, 1))), retry, o, Duration.ZERO);
        for (int i = 0; i < 20; i++) {
            assertEquals(200, b.handle(new Request("GET", "/")).status());
        }
        this.failing.add("b");
        var single = balancer(new StaticUpstreamSource(set(1, null, ep(1, 1))), retry, o, Duration.ZERO);
        assertEquals(503, single.handle(new Request("GET", "/")).status()); // retried on the same one, then relayed
        int post = b.handle(new Request("POST", "/")).status(); // not retried: the dead one's 502 or b's 503
        assertTrue(post == 502 || post == 503, "POST " + post);
        var noRetry = balancer(new StaticUpstreamSource(set(1, null, dead())), null, o, Duration.ZERO);
        assertEquals(502, noRetry.handle(new Request("GET", "/")).status());
    }

    @Test
    void interruptReleasesTheProbe() {
        var o = new EndpointBalancer.Outlier(1, 100, 1_000_000, Duration.ofSeconds(10), Duration.ofSeconds(1), 100);
        var b = balancer(new StaticUpstreamSource(set(1, null, ep(0, 1))), null, o, Duration.ZERO);
        this.failing.add("a");
        b.handle(new Request("GET", "/"));
        this.failing.clear();
        this.now.addAndGet(2_000_000_000L);
        Thread.currentThread().interrupt();
        try {
            assertEquals(502, b.handle(new Request("GET", "/")).status());
        } finally {
            Thread.interrupted();
        }
        assertEquals(200, b.handle(new Request("GET", "/")).status());
        assertFalse(b.poolOf(ep(0, 1).authority()).health.ejected());
    }

    @Test
    void outlierValidation() {
        var d = Duration.ofSeconds(1);
        assertThrows(IllegalArgumentException.class, () -> new EndpointBalancer.Outlier(0, 50, 1, d, d, 50));
        assertThrows(IllegalArgumentException.class, () -> new EndpointBalancer.Outlier(1, 0, 1, d, d, 50));
        assertThrows(IllegalArgumentException.class, () -> new EndpointBalancer.Outlier(1, 101, 1, d, d, 50));
        assertThrows(IllegalArgumentException.class, () -> new EndpointBalancer.Outlier(1, 50, 0, d, d, 50));
        assertThrows(IllegalArgumentException.class, () -> new EndpointBalancer.Outlier(1, 50, 1, Duration.ofMillis(1), d, 50));
        assertThrows(IllegalArgumentException.class, () -> new EndpointBalancer.Outlier(1, 50, 1, d, Duration.ZERO, 50));
        assertThrows(IllegalArgumentException.class, () -> new EndpointBalancer.Outlier(1, 50, 1, d, d, -1));
        assertThrows(IllegalArgumentException.class, () -> new EndpointBalancer.Outlier(1, 50, 1, d, d, 101));
        assertThrows(NullPointerException.class, () -> new EndpointBalancer.Outlier(1, 50, 1, null, d, 50));
        assertThrows(NullPointerException.class, () -> new EndpointBalancer(null, e -> null, null, null));
    }

    /**
     * #78 / Codex P2: a peer-budget share refusal is local admission. Repeated 503s from that
     * path must not eject the endpoint; a real upstream 503 still must.
     */
    @Test
    void peerBudgetRefusalsDoNotEjectButUpstreamFailuresStillDo() throws Exception {
        var budget = new ByteBudget(1 << 20, 64 * 1024);
        var peer = new InetSocketAddress(InetAddress.getByAddress(new byte[] {10, 4, 5, 6}), 0);
        budget.add(64 * 1024, budget.peerStripe(peer)); // peer already at its share
        var o = new EndpointBalancer.Outlier(3, 100, 1_000_000, Duration.ofSeconds(10), Duration.ofSeconds(1), 100);
        EndpointBalancer.Pools pools = e -> new ProxyHandler(new ProxyHandler.Settings(
                URI.create("http://" + e.authority()), Duration.ofMillis(500), Duration.ofSeconds(2), 1024, "http",
                null), budget);
        var src = new StaticUpstreamSource(set(1, null, ep(0, 1)));
        var b = new EndpointBalancer(src, pools, null, o, Duration.ZERO, this.now::get);
        this.balancers.add(b);
        var req = new Request("GET", "/", RequestHeaders.of(Map.of()), peer, false);
        for (int i = 0; i < 20; i++) {
            var r = b.handle(req);
            assertEquals(503, r.status(), "local peer-budget refusal");
            assertTrue(ProxyHandler.isLocalRefusal(r));
        }
        var pool = b.poolOf(ep(0, 1).authority());
        assertFalse(pool.health.ejected(), "share-exhaustion 503s must not mark the endpoint unhealthy");
        assertEquals(0, b.ejectedCount());

        // Real upstream 503s still count toward ejection.
        this.failing.add("a");
        var other = new Request("GET", "/"); // no peer → keyless stripe, not at share
        for (int i = 0; i < 3; i++) {
            assertEquals(503, b.handle(other).status());
        }
        assertTrue(pool.health.ejected(), "real upstream 503s must still eject");
        assertEquals(1, b.ejectedCount());
    }

    /**
     * Codex P2 on #298: a local peer-budget refusal on a half-open recovery probe must release
     * the probe flag, otherwise the endpoint stays reserved and never re-enters rotation.
     */
    @Test
    void localRefusalReleasesHalfOpenProbe() throws Exception {
        var budget = new ByteBudget(1 << 20, 64 * 1024);
        var peer = new InetSocketAddress(InetAddress.getByAddress(new byte[] {10, 9, 9, 9}), 0);
        budget.add(64 * 1024, budget.peerStripe(peer));
        assertTrue(budget.exhausted(budget.peerStripe(peer)));
        var o = new EndpointBalancer.Outlier(3, 100, 1_000_000, Duration.ofSeconds(10), Duration.ofSeconds(1), 100);
        EndpointBalancer.Pools pools = e -> new ProxyHandler(new ProxyHandler.Settings(
                URI.create("http://" + e.authority()), Duration.ofMillis(500), Duration.ofSeconds(2), 1024, "http",
                null), budget);
        // Single endpoint: after eject_for, pick() must take the half-open probe on it.
        var src = new StaticUpstreamSource(set(1, null, ep(0, 1)));
        var b = new EndpointBalancer(src, pools, null, o, Duration.ZERO, this.now::get);
        this.balancers.add(b);
        var poolA = b.poolOf(ep(0, 1).authority());
        this.failing.add("a");
        for (int i = 0; i < 3; i++) {
            assertEquals(503, b.handle(new Request("GET", "/")).status());
        }
        assertTrue(poolA.health.ejected());
        this.failing.clear();
        this.now.addAndGet(1_100_000_000L); // eject_for elapsed → next pick takes the probe
        var req = new Request("GET", "/", RequestHeaders.of(Map.of()), peer, false);
        var r = b.handle(req);
        assertEquals(503, r.status(), "local peer-budget refusal on the recovery probe");
        assertTrue(ProxyHandler.isLocalRefusal(r));
        // Without release(), available() would return false forever (probe stuck true).
        assertTrue(poolA.health.available(this.now.get()), "half-open probe must be released after local refusal");
        poolA.health.release();
    }

    /**
     * Codex P1 on #298: local-refusal markers must not pin Response instances forever.
     */
    @Test
    void localRefusalMarkersDoNotPinResponsesForever() {
        int before = ProxyHandler.localRefusalRegistrySize();
        for (int i = 0; i < 200; i++) {
            var r = ProxyHandler.localRefusal(StaticFileHandler.error(503, false));
            assertTrue(ProxyHandler.isLocalRefusal(r));
            // drop strong ref each iteration
        }
        boolean cleared = false;
        for (int i = 0; i < 50; i++) {
            System.gc();
            System.runFinalization();
            if (ProxyHandler.localRefusalRegistrySize() <= before + 2) {
                cleared = true;
                break;
            }
            try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        assertTrue(cleared,
                "weak local-refusal registry still held "
                        + ProxyHandler.localRefusalRegistrySize() + " entries after GC");
    }


}
