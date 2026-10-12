package sjws_nettask.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Swap semantics of a compiled filter snapshot. The request path is {@link ChainControl#handle}. */
class ChainControlTest {

    @TempDir
    java.nio.file.Path root;

    private static Request req(String target) {
        return new Request("GET", target);
    }

    private static Handler echo() {
        return request -> new Response(200, ResponseHeaders.of().add("x-target", request.target()),
                Body.Bytes.of(request.target().getBytes(StandardCharsets.UTF_8)));
    }

    private static RequestFilters registry() {
        return RequestFilters.create().factory("mark", spec -> (request, next) -> {
            var response = next.proceed(request);
            response.headers().set("x-mark", spec.option("id") == null ? "1" : spec.option("id"));
            return response;
        });
    }

    private static FilterDocument doc(long version, String id, FilterScope scope) {
        return FilterDocument.of(version, List.of(FilterDocument.FilterEntry.of("mark", 0,
                id == null ? Map.of() : Map.of("id", id), scope)));
    }

    @Test
    void applyPublishesOneSnapshotAndKeepsItOnFailure() {
        var published = new AtomicInteger();
        var control = ChainControl.open(registry(), Set.of("edge"), echo(), () -> null, published::incrementAndGet);
        assertEquals(-1, control.version());
        assertFalse(control.isLive());
        assertNull(control.document());
        assertEquals(200, control.handle(req("/x")).status());

        control.apply(doc(0, "a", FilterScope.all()));
        assertTrue(control.isLive());
        assertEquals(1, published.get());
        assertEquals("a", control.handle(req("/x")).headers().get("x-mark"));

        control.apply(doc(0, "a", FilterScope.all()).toJson());
        assertEquals("mark", control.document().filters().get(0).type());
        assertEquals(1, published.get());
        var first = control.published();

        var bad = "{\"format\":1,\"version\":1,\"filters\":[{\"type\":\"missing\"}]}";
        assertThrows(IllegalArgumentException.class, () -> control.apply(bad));
        assertEquals(0, control.version());
        assertEquals("a", control.handle(req("/x")).headers().get("x-mark"));
        assertSame(first, control.published());

        assertThrows(IllegalArgumentException.class, () -> control.apply("not json"));
        assertThrows(IllegalArgumentException.class, () -> control.apply(new byte[] { (byte) 0xff }));
        assertEquals(0, control.version());

        control.apply(doc(2, "b", FilterScope.route("/api/*")).toJsonBytes());
        assertEquals(2, control.version());
        assertNull(control.handle(req("/other")).headers().get("x-mark"));
        assertEquals("b", control.handle(req("/api/x")).headers().get("x-mark"));
        assertThrows(IllegalArgumentException.class, () -> control.apply(doc(1, "old", FilterScope.all())));
        assertEquals("b", control.handle(req("/api/x")).headers().get("x-mark"));
        assertThrows(NullPointerException.class, () -> control.apply((FilterDocument) null));
    }

    @Test
    void invalidEntryDoesNotReplaceTheChainEvenIfAnEarlierEntryCompiled() {
        var created = new AtomicInteger();
        var filters = RequestFilters.create().factory("mark", spec -> {
            created.incrementAndGet();
            return (request, next) -> next.proceed(request);
        });
        var control = ChainControl.open(filters, Set.of(), echo(), null, null);
        control.apply(FilterDocument.of(1, List.of(FilterDocument.FilterEntry.of("mark"))));
        var bad = FilterDocument.of(2, List.of(FilterDocument.FilterEntry.of("mark"),
                FilterDocument.FilterEntry.of("nope")));
        var thrown = assertThrows(IllegalArgumentException.class, () -> control.apply(bad));
        assertTrue(thrown.getMessage().contains("filters[1]"));
        assertEquals(1, control.version());
        assertTrue(created.get() >= 2);
    }

    @Test
    void labelMismatchIsValidatedThenOmitted() {
        var created = new AtomicInteger();
        var filters = RequestFilters.create().factory("mark", spec -> {
            created.incrementAndGet();
            if ("bad".equals(spec.option("id"))) {
                throw new IllegalArgumentException("bad param");
            }
            return (request, next) -> {
                var response = next.proceed(request);
                response.headers().set("x-mark", "on");
                return response;
            };
        });
        var control = ChainControl.open(filters, Set.of("edge"), echo(), null, null);
        var skipped = FilterDocument.of(1, List.of(FilterDocument.FilterEntry.of("mark", 0, Map.of("id", "ok"),
                FilterScope.labels("api"))));
        control.apply(skipped);
        assertEquals(1, created.get());
        assertNull(control.handle(req("/")).headers().get("x-mark"));
        assertTrue(control.published() instanceof Handler);
        assertFalse(control.published() instanceof FilterChain);

        control.nodeLabels(Set.of("api"));
        assertNull(control.handle(req("/")).headers().get("x-mark"));
        control.apply(skipped);
        assertEquals("on", control.handle(req("/")).headers().get("x-mark"));

        var invalid = FilterDocument.of(3, List.of(FilterDocument.FilterEntry.of("mark", 0, Map.of("id", "bad"),
                FilterScope.labels("other"))));
        assertThrows(IllegalArgumentException.class, () -> control.apply(invalid));
        assertEquals("on", control.handle(req("/")).headers().get("x-mark"));
    }

    @Test
    void emptyDocumentCallsTheTerminalDirectly() throws Exception {
        Files.writeString(root.resolve("index.html"), "hello");
        var files = new StaticFileHandler(root);
        var control = ChainControl.open(RequestFilters.create(), Set.of(), files, null, null);
        control.apply(FilterDocument.empty(4));
        assertEquals(200, control.handle(req("/index.html")).status());
        assertTrue(control.published() instanceof StaticFileHandler);
    }

    @Test
    void inFlightRequestFinishesOnTheSnapshotItEntered() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var filters = RequestFilters.create().factory("hold", spec -> (request, next) -> {
            if ("old".equals(spec.option("id"))) {
                entered.countDown();
                try {
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            var response = next.proceed(request);
            response.headers().set("x-id", spec.option("id"));
            return response;
        });
        var control = ChainControl.open(filters, Set.of(), echo(), null, null);
        control.apply(FilterDocument.of(1, List.of(
                FilterDocument.FilterEntry.of("hold", 0, Map.of("id", "old"), FilterScope.all()))));
        var seen = new AtomicReference<Response>();
        var thread = new Thread(() -> seen.set(control.handle(req("/"))));
        thread.start();
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        control.apply(FilterDocument.of(2, List.of(
                FilterDocument.FilterEntry.of("hold", 0, Map.of("id", "new"), FilterScope.all()))));
        assertEquals("new", control.handle(req("/other")).headers().get("x-id"));
        release.countDown();
        thread.join(5000);
        assertEquals("old", seen.get().headers().get("x-id"));
    }

    @Test
    void outerFilterStaysOutsideUnlessTheOrderIsNegative() {
        var trace = new java.util.ArrayList<String>();
        RequestFilter outer = (request, next) -> {
            trace.add("outer");
            return next.proceed(request);
        };
        var filters = RequestFilters.create().factory("mark", spec -> (request, next) -> {
            trace.add(spec.option("id"));
            return next.proceed(request);
        });
        var control = ChainControl.open(filters, Set.of(), echo(), () -> outer, null);
        control.apply(FilterDocument.of(1, List.of(
                FilterDocument.FilterEntry.of("mark", -5, Map.of("id", "neg"), FilterScope.all()),
                FilterDocument.FilterEntry.of("mark", 0, Map.of("id", "zero"), FilterScope.all()))));
        control.handle(req("/"));
        assertEquals(List.of("neg", "outer", "zero"), trace);

        trace.clear();
        control.pinOuter((request, next) -> {
            trace.add("pinned");
            return next.proceed(request);
        });
        control.handle(req("/"));
        assertEquals(List.of("neg", "pinned", "zero"), trace);
        control.pinOuter((request, next) -> next.proceed(request));
        trace.clear();
        control.handle(req("/"));
        assertEquals(List.of("neg", "pinned", "zero"), trace);
    }

    @Test
    void factoryThatReturnsNullFailsTheDocument() {
        var filters = RequestFilters.create().factory("n", spec -> null);
        var control = ChainControl.open(filters, Set.of(), echo(), null, null);
        assertThrows(IllegalArgumentException.class,
                () -> control.apply(FilterDocument.of(1, List.of(FilterDocument.FilterEntry.of("n")))));
        assertEquals(-1, control.version());
        assertThrows(NullPointerException.class,
                () -> ChainControl.open(null, Set.of(), echo(), null, null));
        assertThrows(NullPointerException.class,
                () -> ChainControl.open(filters, Set.of(), null, null, null));
        assertThrows(NullPointerException.class, () -> control.pinOuter(null));
        control.nodeLabels(null);
        control.apply(FilterDocument.empty(0));
        assertEquals(0, control.version());
    }

    @Test
    void bothRouteAndLabelsAreRequired() {
        var filters = registry();
        var control = ChainControl.open(filters, Set.of("edge"), echo(), null, null);
        control.apply(doc(1, "hit", FilterScope.of("/only", "edge")));
        assertEquals("hit", control.handle(req("/only")).headers().get("x-mark"));
        assertNull(control.handle(req("/other")).headers().get("x-mark"));
        control.nodeLabels(Set.of("other"));
        control.apply(doc(1, "hit", FilterScope.of("/only", "edge")));
        assertNull(control.handle(req("/only")).headers().get("x-mark"));
    }

    @Test
    void publishConsumerReceivesTheCompiledHandler() {
        var seen = new ArrayList<Handler>();
        var terminal = echo();
        var control = ChainControl.open(registry(), Set.of(), terminal, null, null, seen::add);
        assertTrue(seen.isEmpty());
        control.pinOuter((request, next) -> {
            var response = next.proceed(request);
            response.headers().set("x-outer", "1");
            return response;
        });
        assertTrue(seen.isEmpty());
        var early = ChainControl.open(registry(), Set.of(), terminal, null, null, seen::add);
        early.apply(FilterDocument.empty(0));
        assertSame(terminal, early.published());

        control.apply(FilterDocument.empty(1));
        assertEquals(2, seen.size());
        assertSame(terminal, seen.get(0));
        assertTrue(seen.get(1) instanceof FilterChain);
        assertSame(control.published(), seen.get(1));
        assertEquals("1", control.handle(req("/")).headers().get("x-outer"));

        control.apply(doc(2, "a", FilterScope.all()));
        assertEquals(3, seen.size());
        assertTrue(seen.get(2) instanceof FilterChain);
        assertSame(control.published(), seen.get(2));
        assertFalse(seen.get(2) instanceof ChainControl);
        assertEquals("a", control.handle(req("/")).headers().get("x-mark"));
        assertEquals("1", control.handle(req("/")).headers().get("x-outer"));

        var before = seen.size();
        assertThrows(IllegalArgumentException.class,
                () -> control.apply("{\"format\":1,\"version\":3,\"filters\":[{\"type\":\"missing\"}]}"));
        assertEquals(before, seen.size());
        assertSame(seen.get(2), control.published());
    }

    @Test
    void failedPinOuterLeavesTheSlotRetryable() {
        var fail = new AtomicInteger();
        var filters = RequestFilters.create().factory("mark", spec -> {
            if (fail.getAndIncrement() == 1) {
                throw new IllegalArgumentException("boom");
            }
            return (request, next) -> {
                var response = next.proceed(request);
                response.headers().set("x-mark", "on");
                return response;
            };
        });
        var seen = new ArrayList<Handler>();
        var control = ChainControl.open(filters, Set.of(), echo(), null, null, seen::add);
        control.apply(FilterDocument.of(1, List.of(FilterDocument.FilterEntry.of("mark"))));
        var published = control.published();
        assertEquals(1, seen.size());
        RequestFilter outer = (request, next) -> {
            var response = next.proceed(request);
            response.headers().set("x-outer", "1");
            return response;
        };
        assertThrows(IllegalArgumentException.class, () -> control.pinOuter(outer));
        assertSame(published, control.published());
        assertEquals(1, seen.size());
        assertNull(control.handle(req("/")).headers().get("x-outer"));
        assertEquals("on", control.handle(req("/")).headers().get("x-mark"));
        control.pinOuter(outer);
        assertEquals("1", control.handle(req("/")).headers().get("x-outer"));
        assertEquals("on", control.handle(req("/")).headers().get("x-mark"));
        assertEquals(2, seen.size());
        assertSame(control.published(), seen.get(1));
        control.pinOuter((request, next) -> next.proceed(request));
        assertEquals(2, seen.size());
    }
}
