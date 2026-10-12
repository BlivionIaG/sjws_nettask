package sjws_nettask.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Chain ordering, short-circuit, failures, route matching and the configuration-shaped registration API. */
class FilterChainTest {

    @TempDir
    Path root;

    private static Request req(String method, String target) {
        return new Request(method, target);
    }

    private static String text(Body body) {
        var data = ((Body.Bytes) body).data().duplicate();
        var bytes = new byte[data.remaining()];
        data.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static RequestFilter mark(List<String> trace, String name) {
        return (request, next) -> {
            trace.add(name);
            return next.proceed(request);
        };
    }

    private static Handler echo() {
        return request -> new Response(200,
                ResponseHeaders.of().add("x-target", request.target()),
                Body.Bytes.of(request.target().getBytes(StandardCharsets.UTF_8)),
                request.headOnly());
    }

    private static String stderr(Runnable body) {
        var saved = System.err;
        var bytes = new ByteArrayOutputStream();
        System.setErr(new PrintStream(bytes, true, StandardCharsets.UTF_8));
        try {
            body.run();
            return bytes.toString(StandardCharsets.UTF_8);
        } finally {
            System.setErr(saved);
        }
    }

    @Test
    void emptyRegistryCompilesToNothing() {
        var filters = RequestFilters.create();
        assertTrue(filters.isEmpty());
        assertNull(filters.build(echo()));
        assertNull(filters.addAll(null).addAll(List.of()).build(echo()));
    }

    @Test
    void filtersRunOutsideInAndSeeTheResponseOnTheWayOut() {
        var trace = new ArrayList<String>();
        var filters = RequestFilters.create()
                .add((request, next) -> {
                    trace.add("a-in");
                    var response = next.proceed(request);
                    trace.add("a-out:" + response.status());
                    response.headers().set("x-a", "1");
                    return new Response(201, response.headers(), response.body(), response.headOnly());
                })
                .add((request, next) -> {
                    trace.add("b-in");
                    var response = next.proceed(request.withTarget("/rewritten"));
                    trace.add("b-out");
                    response.headers().set("x-b", "2");
                    return response;
                });
        var response = filters.build(echo()).handle(req("GET", "/original"));
        assertEquals(List.of("a-in", "b-in", "b-out", "a-out:200"), trace);
        assertEquals(201, response.status());
        assertEquals("1", response.headers().get("x-a"));
        assertEquals("2", response.headers().get("x-b"));
        assertEquals("/rewritten", text(response.body()));
        assertEquals("/rewritten", response.headers().get("x-target"));
        assertFalse(response.headOnly());
    }

    @Test
    void explicitOrderBeatsRegistrationOrderAndTiesStayStable() {
        var trace = new ArrayList<String>();
        var response = RequestFilters.create()
                .add(10, mark(trace, "late"))
                .add(-1, mark(trace, "early"))
                .add(10, mark(trace, "late-tie"))
                .add(mark(trace, "zero"))
                .build(echo())
                .handle(req("GET", "/"));
        assertEquals(200, response.status());
        assertEquals(List.of("early", "zero", "late", "late-tie"), trace);
    }

    @Test
    void shortCircuitSkipsTheRest() {
        var ran = new AtomicInteger();
        var response = RequestFilters.create()
                .add((request, next) -> new Response(204, ResponseHeaders.of(), Body.Empty.INSTANCE, request.headOnly()))
                .add((request, next) -> {
                    ran.incrementAndGet();
                    return next.proceed(request);
                })
                .build(request -> {
                    ran.incrementAndGet();
                    return echo().handle(request);
                })
                .handle(req("HEAD", "/file"));
        assertEquals(0, ran.get());
        assertEquals(204, response.status());
        assertTrue(response.headOnly());
    }

    @Test
    void chainNodesAreReusedAcrossRequests() {
        var seen = new AtomicReference<RequestFilter.Chain>();
        var chain = RequestFilters.create().add((request, next) -> {
            var previous = seen.getAndSet(next);
            if (previous != null) {
                assertSame(previous, next);
            }
            return next.proceed(request);
        }).build(echo());
        chain.handle(req("GET", "/a"));
        chain.handle(req("GET", "/b"));
        assertTrue(seen.get() != null);
    }

    @Test
    void laterRegistrationDoesNotChangeABuiltChain() {
        var filters = RequestFilters.create().add((request, next) -> next.proceed(request));
        var chain = filters.build(echo());
        filters.add((request, next) -> {
            throw new AssertionError("built chain must not see a filter added later");
        });
        assertEquals(200, chain.handle(req("GET", "/z")).status());
    }

    @Test
    void filterFailureBecomes500AndOuterFiltersObserveIt() {
        var response = new AtomicReference<Response>();
        var err = stderr(() -> response.set(RequestFilters.create()
                .add((request, next) -> {
                    var downstream = next.proceed(request);
                    downstream.headers().set("x-saw", String.valueOf(downstream.status()));
                    return downstream;
                })
                .add((request, next) -> {
                    throw new IllegalStateException("boom");
                })
                .build(echo())
                .handle(req("GET", "/"))));
        assertEquals(500, response.get().status());
        assertEquals("500", response.get().headers().get("x-saw"));
        assertTrue(response.get().headers().get("content-type").contains("text/html"), response.get().headers().toString());
        assertTrue(text(response.get().body()).contains("500 Internal Server Error"));
        assertFalse(response.get().headOnly());
        assertTrue(err.contains("Filter filter#1 failed: java.lang.IllegalStateException: boom"), err);
    }

    @Test
    void failureAfterDownstreamReplacesTheResponse() {
        var ran = new AtomicInteger();
        var response = new AtomicReference<Response>();
        stderr(() -> response.set(RequestFilters.create()
                .add((request, next) -> {
                    next.proceed(request);
                    throw new IllegalStateException("after");
                })
                .build(request -> {
                    ran.incrementAndGet();
                    return echo().handle(request);
                })
                .handle(req("HEAD", "/x"))));
        assertEquals(1, ran.get());
        assertEquals(500, response.get().status());
        assertTrue(response.get().headOnly());
    }

    @Test
    void nullResponseAndNullRequestAndHandlerFailureAre500() {
        var nullResponse = new AtomicReference<Response>();
        var err = stderr(() -> nullResponse.set(RequestFilters.create()
                .add((request, next) -> null)
                .build(echo())
                .handle(req("GET", "/"))));
        assertEquals(500, nullResponse.get().status());
        assertTrue(err.contains("returned no response"), err);

        var handlerErr = new AtomicReference<Response>();
        err = stderr(() -> handlerErr.set(RequestFilters.create()
                .add((request, next) -> next.proceed(request))
                .build(request -> {
                    throw new IllegalStateException("disk");
                })
                .handle(req("GET", "/"))));
        assertEquals(500, handlerErr.get().status());
        assertTrue(err.contains("Request handler failed: java.lang.IllegalStateException: disk"), err);

        var nullRequest = new AtomicReference<Response>();
        stderr(() -> nullRequest.set(RequestFilters.create()
                .add((request, next) -> next.proceed(null))
                .build(request -> {
                    throw new NullPointerException("no request");
                })
                .handle(req("GET", "/"))));
        assertEquals(500, nullRequest.get().status());
        assertFalse(nullRequest.get().headOnly());
    }

    @Test
    void routeSkipsOnlyTheFilterThatDoesNotMatch() {
        var api = new AtomicInteger();
        var always = new AtomicInteger();
        var chain = RequestFilters.create()
                .add(0, "/api/*", (request, next) -> {
                    api.incrementAndGet();
                    var response = next.proceed(request);
                    response.headers().add("x-route", "api");
                    return response;
                })
                .add((request, next) -> {
                    always.incrementAndGet();
                    return next.proceed(request);
                })
                .build(echo());
        var hit = chain.handle(req("GET", "/api/x?y=1"));
        var exact = chain.handle(req("GET", "/api"));
        var miss = chain.handle(req("GET", "/apiv2"));
        var other = chain.handle(req("GET", "/health#z"));
        assertEquals("api", hit.headers().get("x-route"));
        assertEquals("api", exact.headers().get("x-route"));
        assertNull(miss.headers().get("x-route"));
        assertNull(other.headers().get("x-route"));
        assertEquals(2, api.get());
        assertEquals(4, always.get());
    }

    @Test
    void middlewareSpecsResolveFactoriesAtBuild() {
        var seen = new AtomicReference<MiddlewareSpec>();
        var filters = RequestFilters.create().addAll(List.of(
                MiddlewareSpec.of("proxy", 20, "/api/*", Map.of("upstream", "http://127.0.0.1:9")),
                MiddlewareSpec.of("log")));
        filters.factory("  log  ", spec -> (request, next) -> {
            seen.set(spec);
            var response = next.proceed(request);
            response.headers().add("x-log", spec.option("format") == null ? "combined" : spec.option("format"));
            return response;
        });
        filters.factory("proxy", spec -> (request, next) -> {
            assertEquals("http://127.0.0.1:9", spec.option("upstream"));
            if (request.target().startsWith("/api")) {
                return new Response(200, ResponseHeaders.of().add("x-proxy", "1"),
                        Body.Bytes.of("proxied".getBytes(StandardCharsets.UTF_8)), false);
            }
            return next.proceed(request);
        });
        var chain = filters.build(echo());
        var proxied = chain.handle(req("GET", "/api/v1"));
        var logged = chain.handle(req("GET", "/index.html"));
        assertEquals("proxied", text(proxied.body()));
        assertEquals("combined", logged.headers().get("x-log"));
        assertEquals("log", seen.get().name());
        assertNull(seen.get().route());
        assertTrue(seen.get().options().isEmpty());
    }

    @Test
    void specOrderOverridesListOrder() {
        var trace = new ArrayList<String>();
        var filters = RequestFilters.create()
                .factory("a", spec -> (request, next) -> {
                    trace.add("a");
                    return next.proceed(request);
                })
                .factory("b", spec -> (request, next) -> {
                    trace.add("b");
                    return next.proceed(request);
                })
                .add(MiddlewareSpec.of("b", 5, null, Map.of()))
                .add(MiddlewareSpec.of("a", 1, "*", Map.of()));
        filters.build(echo()).handle(req("GET", "/"));
        assertEquals(List.of("a", "b"), trace);
    }

    @Test
    void badRegistrationIsRejected() {
        var filters = RequestFilters.create();
        assertThrows(NullPointerException.class, () -> filters.factory(null, spec -> null));
        assertThrows(IllegalArgumentException.class, () -> filters.factory("  ", spec -> null));
        assertThrows(NullPointerException.class, () -> filters.factory("n", null));
        filters.factory("n", spec -> (request, next) -> next.proceed(request));
        assertThrows(IllegalArgumentException.class, () -> filters.factory("n", spec -> null));
        assertThrows(NullPointerException.class, () -> filters.add((RequestFilter) null));
        assertThrows(NullPointerException.class, () -> filters.add((MiddlewareSpec) null));
        assertThrows(IllegalArgumentException.class, () -> filters.add(MiddlewareSpec.of("missing")).build(echo()));
        var nullFactory = RequestFilters.create().factory("n", spec -> null).add(MiddlewareSpec.of("n"));
        assertThrows(IllegalArgumentException.class, () -> nullFactory.build(echo()));
        assertThrows(NullPointerException.class, () -> RequestFilters.create().build(null));
        assertThrows(NullPointerException.class, () -> FilterChain.dispatch(null, null));
        assertThrows(NullPointerException.class, () -> FilterChain.dispatch(null, null, null));
        assertThrows(NullPointerException.class, () -> RequestFilters.create().wrapping(null));
        assertThrows(IllegalArgumentException.class, () -> new MiddlewareSpec("  ", 0, null, Map.of()));
        assertThrows(NullPointerException.class, () -> new MiddlewareSpec(null, 0, null, Map.of()));
    }

    @Test
    void specCopiesOptionsAndNormalizesRoute() {
        var map = new HashMap<String, String>();
        map.put("a", "1");
        var spec = MiddlewareSpec.of("  name  ", 3, "  /health  ", map);
        map.put("a", "changed");
        assertEquals("name", spec.name());
        assertEquals(3, spec.order());
        assertEquals("/health", spec.route());
        assertEquals("1", spec.option("a"));
        assertNull(spec.option("missing"));
        assertThrows(UnsupportedOperationException.class, () -> spec.options().put("b", "2"));
        assertNull(MiddlewareSpec.of("n", 0, "   ", null).route());
        assertTrue(MiddlewareSpec.of("n").options().isEmpty());
    }

    @Test
    void routePatterns() {
        assertTrue(RoutePattern.parse(null).isAll());
        assertTrue(RoutePattern.parse("  ").isAll());
        assertTrue(RoutePattern.parse("*").isAll());
        assertTrue(RoutePattern.parse(" * ").matches("/anything"));
        assertSame(RoutePattern.all(), RoutePattern.parse(null));

        var exact = RoutePattern.parse("/health");
        assertTrue(exact.matches("/health"));
        assertTrue(exact.matches("/health?ready=1"));
        assertTrue(exact.matches("/health#top"));
        assertFalse(exact.matches("/health/"));
        assertFalse(exact.matches("/healthy"));
        assertFalse(exact.matches("/other"));
        assertFalse(exact.matches(""));

        var prefix = RoutePattern.parse("/api/*");
        assertTrue(prefix.matches("/api"));
        assertTrue(prefix.matches("/api/"));
        assertTrue(prefix.matches("/api/v1"));
        assertTrue(prefix.matches("/api/v1?x=1#y"));
        assertFalse(prefix.matches("/apiv2"));
        assertFalse(prefix.matches("/ap"));
        assertFalse(prefix.matches("/"));

        var root = RoutePattern.parse("/*");
        assertTrue(root.matches("/"));
        assertTrue(root.matches("/a"));
        assertTrue(root.matches("/a?q=1"));
        assertFalse(root.matches(""));
        assertFalse(root.matches("*"));

        for (var bad : new String[] {"api", "/a*", "/a/*/b", "/**", "x/*", "http://x"}) {
            assertThrows(IllegalArgumentException.class, () -> RoutePattern.parse(bad), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> RequestFilters.create().add(0, "nope", (q, n) -> n.proceed(q)));
    }

    @Test
    void dispatchBypassesTheChainWhenNoneIsRegistered(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("a.txt"), "a");
        var handler = new StaticFileHandler(dir);
        var direct = handler.handle(req("GET", "/a.txt"));
        var via = FilterChain.dispatch(handler, null).apply(req("GET", "/a.txt"));
        assertEquals(direct.status(), via.status());
        assertEquals(direct.headers().get("etag"), via.headers().get("etag"));
        var hits = new AtomicInteger();
        var chain = RequestFilters.create().add((request, next) -> {
            hits.incrementAndGet();
            return next.proceed(request);
        }).build(handler);
        assertEquals(200, FilterChain.dispatch(handler, chain).apply(req("GET", "/a.txt")).status());
        assertEquals(1, hits.get());
        Handler other = request -> new Response(204, ResponseHeaders.of(), Body.Empty.INSTANCE, false);
        assertEquals(204, FilterChain.dispatch(handler, other, null).apply(req("GET", "/a.txt")).status());
        assertEquals(200, FilterChain.dispatch(handler, handler, null).apply(req("GET", "/a.txt")).status());
        assertEquals(200, FilterChain.dispatch(handler, other, chain).apply(req("GET", "/a.txt")).status());
        assertEquals(2, hits.get());
    }

    @Test
    void wrappingReturnsTheTerminalWhenNothingIsRegistered() {
        var terminal = echo();
        assertSame(terminal, RequestFilters.create().wrapping(terminal));
        var wrapped = RequestFilters.create().add((request, next) -> next.proceed(request)).wrapping(terminal);
        assertEquals(200, wrapped.handle(req("GET", "/x")).status());
        assertTrue(wrapped instanceof FilterChain);
    }

    @Test
    void rewritesKeepPeerSecureAndHeaders() {
        var peer = new java.net.InetSocketAddress("127.0.0.1", 9);
        var headers = RequestHeaders.of(Map.of("host", List.of("example")));
        var request = new Request("GET", "/a", headers, peer, true);
        var seen = new AtomicReference<Request>();
        Handler terminal = incoming -> {
            seen.set(incoming);
            return new Response(200, ResponseHeaders.of(), Body.Empty.INSTANCE, incoming.headOnly());
        };
        RequestFilters.create()
                .add((incoming, next) -> next.proceed(incoming.withTarget("/b").withMethod("HEAD")))
                .build(terminal)
                .handle(request);
        assertEquals("HEAD", seen.get().method());
        assertEquals("/b", seen.get().target());
        assertSame(peer, seen.get().peer());
        assertTrue(seen.get().secure());
        assertSame(headers, seen.get().headers());
        assertTrue(seen.get().headOnly());
    }

    @Test
    void timingAroundProceedSeesDownstreamCompletion() {
        var elapsed = new AtomicInteger();
        var chain = RequestFilters.create().add((request, next) -> {
            long start = System.nanoTime();
            var response = next.proceed(request);
            elapsed.set((int) Math.min(Integer.MAX_VALUE, System.nanoTime() - start));
            response.headers().set("x-nanos", String.valueOf(elapsed.get()));
            return response;
        }).build(echo());
        var response = chain.handle(req("GET", "/t"));
        assertTrue(elapsed.get() >= 0);
        assertEquals(String.valueOf(elapsed.get()), response.headers().get("x-nanos"));
    }
}
