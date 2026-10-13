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
package sjws_nettask.config;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import sjws_nettask.acme.ChallengeBoard;
import sjws_nettask.core.Body;
import sjws_nettask.core.Problem;
import sjws_nettask.core.Request;
import sjws_nettask.core.Response;
import sjws_nettask.core.StaticFileHandler;
import sjws_nettask.routing.ProblemErrorHandler;
import sjws_nettask.routing.Router;

/** Per-route {@code errors: problem} (RFC 9457) and {@code listing:} settings, from YAML to the wire. */
class RouteErrorsTest {

    @TempDir
    Path root;

    private HttpServer upstream;

    @AfterEach
    void stop() {
        if (this.upstream != null) {
            this.upstream.stop(0);
        }
    }

    private ConfigLoader.Loaded loadRaw(String routing) throws IOException {
        var file = this.root.resolve("sjws.yaml");
        Files.writeString(file, "server:\n  port: 0\n  root: \"" + this.root.resolve("www") + "\"\n" + routing);
        Files.createDirectories(this.root.resolve("www/dir"));
        Files.writeString(this.root.resolve("www/dir/a.txt"), "a");
        var locked = this.root.resolve("www/locked.txt");
        Files.writeString(locked, "s");
        locked.toFile().setReadable(false, false);
        return ConfigLoader.load(new String[] {"--config=" + file, "--no-h3"}, Map.of());
    }

    private Router router(String routes) throws IOException {
        var loaded = loadRaw("routing:\n  enabled: true\n" + routes);
        assertTrue(loaded.ok(), loaded.errors().toString());
        return assertInstanceOf(Router.class,
                ConfigSnapshot.compile(loaded.document(), new ChallengeBoard(), 1).handler());
    }

    private static String body(Response r) {
        if (r.body() instanceof Body.Stream st) { // proxied bodies stream (phase 2)
            var out = new java.io.ByteArrayOutputStream();
            try (var src = st.source()) {
                for (var buf = src.next(); buf != null; buf = src.next()) {
                    var c = new byte[buf.remaining()];
                    buf.get(c);
                    out.write(c, 0, c.length);
                }
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            return out.toString(StandardCharsets.UTF_8);
        }
        var b = (Body.Bytes) r.body();
        var bytes = new byte[(int) b.length()];
        b.data().duplicate().get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Test
    void parsesTheRouteSettings() throws IOException {
        var loaded = loadRaw("""
                routing:
                  enabled: true
                  routes:
                    - { prefix: /api, static: true, errors: problem, problem_type: "https://errors.example.com/" }
                    - { prefix: /p, static: true, errors: problem }
                    - { prefix: /h, static: true, errors: html, listing: false }
                    - { prefix: /, static: true }
                """);
        assertTrue(loaded.ok(), loaded.errors().toString());
        var routes = loaded.document().routing().routes().routes();
        assertEquals(new RouteSpec.Errors(true, "https://errors.example.com/"), routes.get(0).errors());
        assertEquals(new RouteSpec.Errors(true, null), routes.get(1).errors());
        assertSame(RouteSpec.Errors.HTML, routes.get(2).errors());
        assertEquals(new RouteSpec.Action.Static(false), routes.get(2).action());
        assertSame(RouteSpec.Errors.HTML, routes.get(3).errors());
        assertEquals(new RouteSpec.Action.Static(true), routes.get(3).action());
    }

    @ParameterizedTest
    @ValueSource(strings = {"errors: xml", "errors: [problem]", "problem_type: \"https://e/\"",
        "errors: problem, problem_type: \"relative/path\"", "errors: problem, problem_type: \"http://bad uri\""})
    void rejectsBadErrorSettings(String extra) throws IOException {
        var loaded = loadRaw("routing:\n  enabled: true\n  routes:\n    - { prefix: /, static: true, " + extra + " }\n");
        assertFalse(loaded.ok());
        assertTrue(loaded.errors().toString().contains("routing.routes[0]."), loaded.errors().toString());
    }

    @Test
    void listingOnlyOnStaticRoutes() throws IOException {
        var loaded = loadRaw("routing:\n  enabled: true\n  routes:\n"
                + "    - { prefix: /, redirect: { status: 301, location: /x }, listing: false }\n");
        assertFalse(loaded.ok());
        assertTrue(loaded.errors().toString().contains("listing is only valid on a static route"));
    }

    @Test
    void routesThatDoNotOptInAreNotWrapped() throws IOException {
        var router = router("  routes:\n    - { prefix: /a, rewrite: /, static: true }\n    - { prefix: /, static: true }\n");
        for (var r : router.routes()) {
            assertInstanceOf(StaticFileHandler.class, r.handler()); // the shared file handler, no wrapper
        }
        assertEquals("/", router.routes().get(0).rewrite());
        var html = router.handle(new Request("GET", "/missing"));
        assertEquals(404, html.status());
        assertTrue(html.headers().get("content-type").startsWith("text/html"));
    }

    @Test
    void listingOffAnswers404() throws IOException {
        var router = router("  routes:\n    - { prefix: /off, rewrite: /, static: true, listing: false }\n"
                + "    - { prefix: /, static: true }\n");
        assertEquals(404, router.handle(new Request("GET", "/off/dir/")).status());
        assertEquals(200, router.handle(new Request("GET", "/off/dir/a.txt")).status());
        var listed = router.handle(new Request("GET", "/dir/"));
        assertEquals(200, listed.status());
        assertTrue(body(listed).contains("a.txt"));
        var files = (StaticFileHandler) router.routes().get(1).handler();
        assertSame(files.cache(), ((StaticFileHandler) router.routes().get(0).handler()).cache());
        assertSame(files, files.withListings(true));
        assertFalse(files.withListings(false).listings());
    }

    @Test
    void staticErrorsAsProblemJsonWithTheClientsPath() throws IOException {
        var router = router("  routes:\n"
                + "    - { prefix: /api, rewrite: /, static: true, errors: problem, problem_type: \"https://e.example/\" }\n"
                + "    - { prefix: /p, static: true, errors: problem }\n");
        var r = router.handle(new Request("GET", "/api/nope?x=1"));
        assertEquals(404, r.status());
        assertEquals(Problem.CONTENT_TYPE, r.headers().get("content-type"));
        assertEquals("no-store", r.headers().get("cache-control"));
        assertEquals("nosniff", r.headers().get("x-content-type-options"));
        assertNull(r.headers().get("content-security-policy"));
        assertEquals("{\"type\":\"https://e.example/404\",\"title\":\"Not Found\",\"status\":404,"
                + "\"detail\":\"No resource exists at this location.\",\"instance\":\"/api/nope\"}", body(r));
        assertEquals(200, router.handle(new Request("GET", "/api/dir/a.txt")).status()); // rewrite still applies

        var forbidden = router.handle(new Request("GET", "/api/locked.txt"));
        org.junit.jupiter.api.Assumptions.assumeTrue(forbidden.status() != 200, "running as root");
        assertEquals(403, forbidden.status());
        assertTrue(body(forbidden).startsWith("{\"type\":\"https://e.example/403\",\"title\":\"Forbidden\""));

        var post = router.handle(new Request("POST", "/p/x"));
        assertEquals(405, post.status());
        assertNotNull(post.headers().get("allow"), "semantic fields of the HTML page are kept");
        assertTrue(body(post).startsWith("{\"type\":\"about:blank\",\"title\":\"Method Not Allowed\",\"status\":405"));

        var head = router.handle(new Request("HEAD", "/p/nope"));
        assertFalse(head.sendsBody());
        assertTrue(head.contentLength() > 0);
        assertEquals(Problem.CONTENT_TYPE, head.headers().get("content-type"));
    }

    @Test
    void proxyErrorsAndUpstreamBodies() throws IOException {
        this.upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.upstream.createContext("/busy", ex -> {
            var b = "upstream says busy".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/plain");
            ex.sendResponseHeaders(503, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        this.upstream.createContext("/slow", ex -> {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            ex.close();
        });
        this.upstream.start();
        int port = this.upstream.getAddress().getPort();
        var router = router("  upstreams:\n"
                + "    up: { url: \"http://127.0.0.1:" + port + "\", response_timeout: 1s }\n"
                + "    dead: { url: \"http://127.0.0.1:9\", connect_timeout: 1s }\n"
                + "  routes:\n"
                + "    - { prefix: /up, rewrite: /, proxy: up, errors: problem }\n"
                + "    - { prefix: /dead, proxy: dead, errors: problem }\n");
        var busy = router.handle(new Request("GET", "/up/busy"));
        assertEquals(503, busy.status());
        assertEquals("upstream says busy", body(busy), "an upstream's own error body is relayed untouched");

        var bad = router.handle(new Request("GET", "/dead/x"));
        assertEquals(502, bad.status());
        assertTrue(body(bad).contains("\"status\":502") && body(bad).contains("\"instance\":\"/dead/x\""), body(bad));

        var slow = router.handle(new Request("GET", "/up/slow"));
        assertEquals(504, slow.status());
        assertTrue(body(slow).contains("\"title\":\"Gateway Timeout\""), body(slow));
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 429, 502, 503, 504})
    void gatewayStatusesFromAnyInnerHandler(int status) {
        var h = new ProblemErrorHandler(request -> {
            var r = StaticFileHandler.error(status, request.headOnly());
            r.headers().add("retry-after", "5");
            return r;
        }, "/", null, null);
        var r = h.handle(new Request("GET", "/x"));
        assertEquals(status, r.status());
        assertEquals(Problem.CONTENT_TYPE, r.headers().get("content-type"));
        assertEquals("5", r.headers().get("retry-after"));
        assertTrue(body(r).contains("\"status\":" + status + ",\"detail\":\"" + Problem.detail(status) + "\""));
    }

    @Test
    void unsupportedAndSuccessfulResponsesPassThrough() {
        var teapot = StaticFileHandler.error(418, false);
        assertSame(teapot, new ProblemErrorHandler(r -> teapot, "/", null, null).handle(new Request("GET", "/x")));
        var ok = StaticFileHandler.html(200, "<p>ok</p>", false);
        assertSame(ok, new ProblemErrorHandler(r -> ok, "/", null, null).handle(new Request("GET", "/x")));
        assertFalse(Problem.supports(418));
        // a non-origin-form target has no path to report
        var star = new ProblemErrorHandler(r -> StaticFileHandler.error(400, false), "/", null, null)
                .handle(new Request("OPTIONS", "*"));
        assertFalse(body(star).contains("instance"));
    }

    @Test
    void jsonEscaping() {
        var r = Problem.of(404, null, "/a\"b\\c\n\u0001\u2028é", null, false);
        assertTrue(body(r).endsWith("\"instance\":\"/a\\\"b\\\\c\\n\\u0001\\u2028é\"}"), body(r));
        assertTrue(body(Problem.of(404, null, "\r\t", null, false)).endsWith("\"instance\":\"\\r\\t\"}"));
    }
}
