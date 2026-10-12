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

import java.util.List;
import org.junit.jupiter.api.Test;
import sjws_nettask.core.Body;
import sjws_nettask.core.Handler;
import sjws_nettask.core.Request;
import sjws_nettask.core.RequestHeaders;
import sjws_nettask.core.Response;
import sjws_nettask.core.ResponseHeaders;
import sjws_nettask.routing.Router.Route;

class RouterTest {

    /** Echoes the target it received in an x-target header. */
    private static Handler echo(String name) {
        return r -> new Response(200, ResponseHeaders.of().add("x-h", name).add("x-target", r.target()),
                Body.Empty.INSTANCE);
    }

    private static Request req(String host, String target) {
        return new Request("GET", target, new RequestHeaders(null, null, null, null, null, host));
    }

    @Test
    void prefixMatchesOnSegmentBoundary() {
        var router = new Router(List.of(new Route("/api", echo("api"))), echo("fb"));
        assertEquals("api", router.handle(req(null, "/api")).headers().get("x-h"));
        assertEquals("api", router.handle(req(null, "/api/x")).headers().get("x-h"));
        assertEquals("api", router.handle(req(null, "/api?q=1")).headers().get("x-h"));
        assertEquals("fb", router.handle(req(null, "/apix")).headers().get("x-h"));
        var slash = new Router(List.of(new Route("/a/", echo("a"))), null);
        assertEquals("a", slash.handle(req(null, "/a/b")).headers().get("x-h"));
        assertEquals(404, slash.handle(req(null, "/b")).status());
    }

    @Test
    void virtualHostsAndWildcards() {
        var router = new Router(List.of(
                new Route("Example.com", "/", null, echo("exact")),
                new Route("*.example.com", "/", null, echo("wild")),
                new Route("[::1]", "/", null, echo("v6"))), null);
        assertEquals("exact", router.handle(req("EXAMPLE.com:8080", "/")).headers().get("x-h"));
        assertEquals("wild", router.handle(req("www.example.com", "/")).headers().get("x-h"));
        assertEquals("v6", router.handle(req("[::1]:80", "/")).headers().get("x-h"));
        assertEquals(404, router.handle(req("other.org", "/")).status());
        assertEquals(404, router.handle(req(null, "/")).status());
        assertEquals(-1, Router.hostEnd("[::1"));
        assertEquals(3, router.routes().size());
        assertEquals(404, router.handle(req("example.com.evil", "/")).status());
        assertEquals(404, router.handle(req("example.co", "/")).status());
        assertEquals("exact", router.handle(req("example.com", "/")).headers().get("x-h"));
        assertEquals(404, router.handle(req(".example.com", "/")).status());
        assertEquals(404, router.handle(req("xexample.com", "/")).status());
        assertEquals("wild", router.handle(req("A.B.EXAMPLE.COM:1", "/")).headers().get("x-h"));
    }

    @Test
    void hostRules() {
        var router = new Router(List.of(
                new Route("example.com", "/", null, echo("exact")),
                new Route("*.example.com", "/", null, echo("wild")),
                new Route("k.test", "/", null, echo("k")),
                new Route(null, "/", null, echo("any"))), null);
        // one trailing dot of the request host is ignored, with or without a port
        assertEquals("exact", router.handle(req("example.com.", "/")).headers().get("x-h"));
        assertEquals("exact", router.handle(req("Example.COM.:8080", "/")).headers().get("x-h"));
        assertEquals("wild", router.handle(req("a.example.com.", "/")).headers().get("x-h"));
        assertEquals("any", router.handle(req("example.com..", "/")).headers().get("x-h"));
        // ASCII-only case folding: KELVIN SIGN is not 'k'
        assertEquals("k", router.handle(req("K.TEST", "/")).headers().get("x-h"));
        assertEquals("any", router.handle(req("\u212a.test", "/")).headers().get("x-h"));
        // an unclosed '[' matches host-less routes only
        assertEquals("any", router.handle(req("[::1", "/")).headers().get("x-h"));
        assertFalse(Router.hostMatches("[::1]", "[::1"));
        assertTrue(Router.hostMatches("[::1]", "[::1]:443"));
        assertTrue(Router.prefixCovers("/a/", "/a/b"));
        assertFalse(Router.prefixCovers("/a/b", "/a/"));
    }

    @Test
    void rewritesPrefix() {
        var router = new Router(List.of(
                new Route(null, "/api", "/", echo("strip")),
                new Route(null, "/old", "/new", echo("ren")),
                new Route(null, "/v1/", "/v2/", echo("v"))), null);
        assertEquals("/x?q", router.handle(req(null, "/api/x?q")).headers().get("x-target"));
        assertEquals("/", router.handle(req(null, "/api")).headers().get("x-target"));
        assertEquals("/new/a", router.handle(req(null, "/old/a")).headers().get("x-target"));
        assertEquals("/new?q", router.handle(req(null, "/old?q")).headers().get("x-target"));
        assertEquals("/new", router.handle(req(null, "/old")).headers().get("x-target"));
        assertEquals("/v2/z", router.handle(req(null, "/v1/z")).headers().get("x-target"));
        var keep = new Router(List.of(new Route(null, "/k", "/k", echo("k"))), null);
        var r = new Request("HEAD", "/k/1");
        assertEquals("/k/1", keep.handle(r).headers().get("x-target"));
    }

    @Test
    void notFoundHonoursHead() {
        var r = new Router(List.of(), null).handle(new Request("HEAD", "/"));
        assertEquals(404, r.status());
        assertTrue(r.headOnly());
    }

    @Test
    void dotDotSegmentsAre400() {
        var router = new Router(List.of(new Route("/api", echo("api"))), echo("fb"));
        for (var t : new String[] {"/api/../secret", "/..", "/a/%2e%2e/b", "/a/.%2E", "/a/%2E.?x", "/api/..",
                // path confusion: forms an upstream may read as ".." (servlet ";" parameters, backslash, encoded separators)
                "/api/..;/admin", "/api/..;x/admin", "/api/%2e%2e;/admin", "/api/..\\admin", "/api\\..\\admin",
                "/api/..%2fadmin", "/api/..%2Fadmin", "/api/%2e%2e%2fadmin", "/api%2f..%2fadmin", "/api/..%5cadmin",
                "/api/..%5Cadmin", "/api/x/..;"}) {
            assertEquals(400, router.handle(req(null, t)).status(), t);
        }
        for (var t : new String[] {"/api/.x", "/a/...", "/a/..b", "/a/.", "/a?x=/../", "/%2e", "/a/%2", "/a/%2f%2e",
                "/api/.;/x", "/api/x;..", "/api/a..;/b", "/api/...;/b", "/api/%2e;/b", "/a\\b", "/a%2fb", "/a/..x%2f"}) {
            assertNotEquals(400, router.handle(req(null, t)).status(), t);
        }
    }

    @Test
    void routeValidation() {
        assertThrows(IllegalArgumentException.class, () -> new Route("", "/", null, echo("a")));
        assertThrows(IllegalArgumentException.class, () -> new Route("*.", "/", null, echo("a")));
        assertThrows(IllegalArgumentException.class, () -> new Route("h:80", "/", null, echo("a")));
        assertThrows(IllegalArgumentException.class, () -> new Route("[::1", "/", null, echo("a")));
        assertThrows(IllegalArgumentException.class, () -> new Route("[::1]x", "/", null, echo("a")));
        assertThrows(IllegalArgumentException.class, () -> new Route("example.com.", "/", null, echo("a")));
        assertThrows(IllegalArgumentException.class, () -> new Route(null, "/a?b", null, echo("a")));
        assertThrows(IllegalArgumentException.class, () -> new Route(null, "/a#b", null, echo("a")));
        assertThrows(IllegalArgumentException.class, () -> new Route("x", echo("a")));
        assertThrows(IllegalArgumentException.class, () -> new Route(null, "/", "x", echo("a")));
        assertThrows(NullPointerException.class, () -> new Route(null, echo("a")));
        assertThrows(NullPointerException.class, () -> new Route("/", null));
    }

    @Test
    void redirects() {
        var plain = new RedirectHandler(301, "https://example.com/", false).handle(req(null, "/a"));
        assertEquals(301, plain.status());
        assertEquals("https://example.com/", plain.headers().get("location"));
        var keep = new RedirectHandler(308, "https://example.com/", true);
        assertEquals("https://example.com/a?b", keep.handle(req(null, "/a?b")).headers().get("location"));
        var noSlash = new RedirectHandler(302, "https://e.org", true);
        assertEquals("https://e.org/a", noSlash.handle(req(null, "/a")).headers().get("location"));
        var routed = new Router(List.of(new Route(null, "/old", "/", new RedirectHandler(307, "/new/", true))), null);
        assertEquals("/new/x", routed.handle(req(null, "/old/x")).headers().get("location"));
        // open redirect: a relative location must never become protocol-relative ("//host", "/\\host")
        var toRoot = new Router(List.of(new Route(null, "/old", "/", new RedirectHandler(301, "/", true))), null);
        for (var t : new String[] {"/old//evil.example", "/old///evil.example/x", "/old/\\evil.example",
                "/old/\\/evil.example", "/old//\\evil.example"}) {
            var loc = toRoot.handle(req(null, t)).headers().get("location");
            assertFalse(loc.startsWith("//") || loc.startsWith("/\\"), t + " -> " + loc);
        }
        assertEquals("/evil.example", toRoot.handle(req(null, "/old//evil.example")).headers().get("location"));
        assertEquals("/x?q", toRoot.handle(req(null, "/old/x?q")).headers().get("location"));
        assertEquals("/", toRoot.handle(req(null, "/old")).headers().get("location"));
        assertEquals("https://e.org/a", noSlash.handle(req(null, "//a")).headers().get("location"));
        assertEquals("https://example.com/a", keep.handle(req(null, "//a")).headers().get("location"));
        assertThrows(IllegalArgumentException.class, () -> new RedirectHandler(200, "/", false));
        assertThrows(IllegalArgumentException.class, () -> new RedirectHandler(301, "", false));
        assertThrows(IllegalArgumentException.class, () -> new RedirectHandler(301, null, false));
        assertThrows(IllegalArgumentException.class, () -> new RedirectHandler(301, "/a\r\n", false));
        assertThrows(IllegalArgumentException.class, () -> new RedirectHandler(301, "/a\n", false));
    }
}
