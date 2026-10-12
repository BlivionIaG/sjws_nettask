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

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import sjws_nettask.core.Body;
import sjws_nettask.core.Handler;
import sjws_nettask.core.Request;
import sjws_nettask.core.RequestHeaders;
import sjws_nettask.core.Response;
import sjws_nettask.core.ResponseHeaders;
import sjws_nettask.routing.Router.Route;

/** {@link CompiledRoutes} against the old linear scan ({@link LinearRouter}), on random tables. */
class CompiledRoutesTest {

    static final String[] ROUTE_HOSTS = {null, null, null, "example.com", "Example.COM", "*.example.com",
            "*.b.example.com", "a.example.com", "[::1]", "[::1", "k.test", "*.test", "xn--bcher-kva.example"};
    static final String[] PREFIXES = {"/", "/a", "/a/", "/ab", "/a/b", "/a/b/", "/b", "/b/c", "/%2e", "/a;x", "/A",
            "/a.b", "/api", "/api/v1", "/api/v1/", "/x/y/z"};
    static final String[] TARGETS = {"", "/", "/a", "/a/", "/ab", "/abc", "/a/b", "/a/b/c", "/a?x", "/a/?", "/A/b",
            "//a", "/a//b", "/a;x/", "/a;x", "/%2e", "http://h/a", "*", "/a\\b", "/a/b?/c", "/b", "/b/c/d", "/bc",
            "/api", "/api/", "/api/v1", "/api/v1x", "/api/v1/z?q=/", "/a.b/c", "/x/y/z", "/x/y", "/?a", "/a/..",
            "/a/%2e%2e/b", "/api/..;/x"};
    static final String[] HOSTS = {null, "", "example.com", "EXAMPLE.com:80", "a.example.com", "x.a.example.com",
            "b.example.com", "c.b.example.com", ".example.com", "example.com.", "[::1]", "[::1]:8080", "[::1", "::1",
            "k.test", "\u212a.test", "K.TEST:1", "a.b.example.com.", "test", "x.test", "XN--BCHER-KVA.example",
            "[::2]", "example.com:", ":80"};

    static Handler named(String name) {
        var response = new Response(200, ResponseHeaders.of().add("x-h", name), Body.Empty.INSTANCE);
        return r -> response;
    }

    static List<Route> randomRoutes(Random rnd, int n) {
        var routes = new ArrayList<Route>();
        for (int i = 0; i < n; i++) {
            var host = ROUTE_HOSTS[rnd.nextInt(ROUTE_HOSTS.length)];
            var prefix = rnd.nextInt(4) == 0 ? "/r" + rnd.nextInt(50) + (rnd.nextBoolean() ? "/" : "")
                    : PREFIXES[rnd.nextInt(PREFIXES.length)];
            routes.add(new Route(host, prefix, null, named("r" + i)));
        }
        return routes;
    }

    static String randomTarget(Random rnd) {
        if (rnd.nextInt(3) == 0) {
            return "/r" + rnd.nextInt(60) + (rnd.nextBoolean() ? "/x" : rnd.nextBoolean() ? "?q" : "");
        }
        return TARGETS[rnd.nextInt(TARGETS.length)];
    }

    static int pathEnd(String target) {
        int q = target.indexOf('?');
        return q < 0 ? target.length() : q;
    }

    @Test
    void sameFirstMatchAsTheLinearScan() {
        long seed = Long.getLong("router.seed", 20261013L);
        var rnd = new Random(seed);
        int checked = 0;
        for (int table = 0; table < 400; table++) {
            var routes = randomRoutes(rnd, 1 + rnd.nextInt(table % 10 == 0 ? 300 : 40));
            var reference = new LinearRouter(routes);
            var compiled = new Router(routes, named("fallback"), true);
            var scanned = new Router(routes, named("fallback"), false);
            for (int k = 0; k < 250; k++) {
                var target = randomTarget(rnd);
                var host = HOSTS[rnd.nextInt(HOSTS.length)];
                int want = reference.matchIndex(host, target, pathEnd(target));
                assertEquals(want, compiled.matchIndex(host, target, pathEnd(target)),
                        () -> "seed=" + seed + " host=" + host + " target=" + target + " routes=" + routes);
                assertEquals(want, scanned.matchIndex(host, target, pathEnd(target)));
                var req = new Request("GET", target, new RequestHeaders(null, null, null, null, null, host));
                var a = compiled.handle(req);
                var b = scanned.handle(req);
                assertEquals(b.status(), a.status());
                assertEquals(b.headers().get("x-h"), a.headers().get("x-h"));
                checked++;
            }
        }
        assertEquals(100_000, checked);
    }

    @Test
    void edgesAroundTheLinearCutoff() {
        for (int n : new int[] {0, 1, Router.LINEAR_MAX, Router.LINEAR_MAX + 1, 10_000}) {
            var routes = new ArrayList<Route>();
            for (int i = 0; i < n; i++) {
                routes.add(new Route(i % 3 == 0 ? "h" + i + ".test" : null, "/p" + i, null, named("p" + i)));
            }
            var router = new Router(routes, null);
            assertEquals(404, router.handle(new Request("GET", "/none")).status());
            if (n > 0) {
                int last = n - 1;
                var host = last % 3 == 0 ? "H" + last + ".TEST:8080" : null;
                var r = router.handle(new Request("GET", "/p" + last + "/x",
                        new RequestHeaders(null, null, null, null, null, host)));
                assertEquals("p" + last, r.headers().get("x-h"));
            }
        }
    }

    @Test
    void duplicatesKeepTheFirstAndCatchAllEndsTheTable() {
        var routes = new ArrayList<Route>();
        for (int i = 0; i < 20; i++) {
            routes.add(new Route(null, i == 10 ? "/" : "/d", null, named("d" + i)));
        }
        var router = new Router(routes, null, true);
        assertEquals(0, router.matchIndex(null, "/d/x", 4));
        assertEquals(10, router.matchIndex("any", "/other", 6));
        assertEquals(-1, router.matchIndex(null, "", 0));
    }

    @Test
    void lookupDoesNotAllocate() {
        var routes = new ArrayList<Route>();
        for (int i = 0; i < 1000; i++) {
            routes.add(new Route(i % 2 == 0 ? null : (i % 4 == 1 ? "*.w" + i + ".test" : "h" + i + ".test"),
                    "/r" + i + (i % 5 == 0 ? "/" : ""), null, named("r" + i)));
        }
        var fallback = named("fb");
        var router = new Router(routes, fallback);
        var requests = new Request[] {
                new Request("GET", "/r0/x", new RequestHeaders(null, null, null, null, null, "a.test")),
                new Request("GET", "/r998?q=1", RequestHeaders.NONE),
                new Request("GET", "/r999/z", new RequestHeaders(null, null, null, null, null, "H999.test:80")),
                new Request("GET", "/r997", new RequestHeaders(null, null, null, null, null, "x.w997.test")),
                new Request("GET", "/miss/a/b/c", new RequestHeaders(null, null, null, null, null, "nowhere.test"))};
        var mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().threadId();
        Object sink = null;
        for (int i = 0; i < 200_000; i++) {
            sink = router.handle(requests[i % requests.length]);
        }
        long before = mx.getThreadAllocatedBytes(tid);
        long baseline = mx.getThreadAllocatedBytes(tid) - before;
        before = mx.getThreadAllocatedBytes(tid);
        for (int i = 0; i < 1_000_000; i++) {
            sink = router.handle(requests[i % requests.length]);
        }
        long allocated = mx.getThreadAllocatedBytes(tid) - before - baseline;
        assertNotNull(sink);
        assertEquals(0, allocated, "bytes allocated by 1e6 routed lookups");
    }
}
