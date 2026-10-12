package sjws_nettask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.core.ChainControl;
import sjws_nettask.core.FilterChain;
import sjws_nettask.core.FilterDocument;
import sjws_nettask.core.FilterScope;
import sjws_nettask.core.Handler;
import sjws_nettask.core.RequestFilters;
import sjws_nettask.core.StaticFileHandler;

/** The slot stays off the request path until a document is published. */
class ChainControlServerTest {

    @TempDir
    Path root;

    SjwsServer server;

    @AfterEach
    void stop() {
        if (this.server != null) {
            this.server.close();
        }
    }

    private RequestFilters registry() {
        return RequestFilters.create().factory("mark", spec -> (request, next) -> {
            var response = next.proceed(request);
            response.headers().set("x-mark", spec.option("id"));
            return response;
        });
    }

    private static FilterDocument doc(long version, String id, FilterScope scope) {
        return FilterDocument.of(version, List.of(
                FilterDocument.FilterEntry.of("mark", 0, Map.of("id", id), scope)));
    }

    @Test
    void openingTheSlotDoesNotChangeTheFilePath() throws Exception {
        Files.writeString(root.resolve("hello.txt"), "hello");
        this.server = SjwsServer.start(ServerConfig.builder(root).port(0).build());
        assertNull(this.server.filterChain());
        var filters = registry();
        var control = this.server.chainControl(filters, Set.of());
        assertNull(this.server.filterChain());
        var before = HttpTestSupport.get(this.server.tcpPort(), "/hello.txt", 4000);
        assertEquals(200, before.statusCode());
        assertEquals("hello", before.bodyText());
        assertNull(before.headers().get("x-mark"));

        control.apply(doc(1, "one", FilterScope.all()));
        assertTrue(this.server.filterChain() instanceof FilterChain);
        var after = HttpTestSupport.get(this.server.tcpPort(), "/hello.txt", 4000);
        assertEquals("one", after.headers().get("x-mark"));
        assertEquals("hello", after.bodyText());

        assertThrows(IllegalArgumentException.class, () -> control.apply("{\"format\":1,\"version\":2,\"filters\":[{}]}"));
        var kept = HttpTestSupport.get(this.server.tcpPort(), "/hello.txt", 4000);
        assertEquals("one", kept.headers().get("x-mark"));

        control.apply(doc(2, "two", FilterScope.route("/api/*")));
        assertNull(HttpTestSupport.get(this.server.tcpPort(), "/hello.txt", 4000).headers().get("x-mark"));
        Files.createDirectories(root.resolve("api"));
        Files.writeString(root.resolve("api").resolve("a.txt"), "api");
        assertEquals("two", HttpTestSupport.get(this.server.tcpPort(), "/api/a.txt", 4000).headers().get("x-mark"));
        assertEquals(control, this.server.chainControl(RequestFilters.create(), Set.of("other")));
    }

    @Test
    void startupFiltersStayUntilTheFirstDocument() throws Exception {
        Files.writeString(root.resolve("hello.txt"), "hello");
        var filters = registry().add((request, next) -> {
            var response = next.proceed(request);
            response.headers().set("x-old", "1");
            return response;
        });
        this.server = SjwsServer.start(ServerConfig.builder(root).port(0).build(), filters);
        assertEquals("1", HttpTestSupport.get(this.server.tcpPort(), "/hello.txt", 4000).headers().get("x-old"));
        var control = this.server.chainControl(filters, Set.of("edge"));
        assertEquals("1", HttpTestSupport.get(this.server.tcpPort(), "/hello.txt", 4000).headers().get("x-old"));
        control.apply(doc(3, "cp", FilterScope.labels("edge")));
        var response = HttpTestSupport.get(this.server.tcpPort(), "/hello.txt", 4000);
        assertEquals("cp", response.headers().get("x-mark"));
        assertNull(response.headers().get("x-old"));
        control.nodeLabels(Set.of());
        control.apply(doc(3, "cp", FilterScope.labels("edge")));
        assertNull(HttpTestSupport.get(this.server.tcpPort(), "/hello.txt", 4000).headers().get("x-mark"));
    }

    @Test
    void healthzSurvivesAPublishedDocumentAndALaterArm() throws Exception {
        Files.writeString(root.resolve("hello.txt"), "hello");
        this.server = SjwsServer.start(ServerConfig.builder(root).port(0).healthz(true).build());
        assertEquals(200, HttpTestSupport.get(this.server.tcpPort(), "/healthz", 4000).statusCode());
        var control = this.server.chainControl(registry(), Set.of());
        control.apply(doc(1, "m", FilterScope.all()));
        assertEquals(200, HttpTestSupport.get(this.server.tcpPort(), "/healthz", 4000).statusCode());
        assertEquals("m", HttpTestSupport.get(this.server.tcpPort(), "/hello.txt", 4000).headers().get("x-mark"));

        this.server = stopAndStart(ServerConfig.builder(root).port(0).build(), null);
        var plain = this.server.chainControl(registry(), Set.of());
        plain.apply(FilterDocument.empty(1));
        assertNull(this.server.filterChain());
        var snap = this.server.metricsSnapshot();
        assertEquals(0, snap.requests());
        HttpTestSupport.get(this.server.tcpPort(), "/hello.txt", 4000);
        assertTrue(this.server.metricsSnapshot().requests() >= 1);
    }

    @Test
    void publishReachesACustomTransport() throws Exception {
        Files.writeString(root.resolve("hello.txt"), "hello");
        var armed = new AtomicReference<Handler>();
        Transport fake = new Transport() {
            @Override
            public String name() {
                return "fake";
            }

            @Override
            public Transport start() {
                return this;
            }

            @Override
            public int port() {
                return 9;
            }

            @Override
            public void close() {
            }

            @Override
            public void armHandler(Handler handler) {
                armed.set(handler);
            }
        };
        this.server = SjwsServer.start(ServerConfig.builder(root).port(0).h3Enabled(true).build(),
                (ServerConfig config, StaticFileHandler handler) -> fake, registry(), null);
        assertNull(armed.get());
        var control = this.server.chainControl(registry(), Set.of());
        assertNull(armed.get());
        control.apply(doc(1, "h", FilterScope.all()));
        assertSame(control.published(), armed.get());
        assertFalse(armed.get() instanceof ChainControl);
        assertTrue(armed.get() instanceof FilterChain);
        control.apply(doc(2, "h2", FilterScope.all()));
        assertSame(control.published(), armed.get());
        assertEquals("h2", HttpTestSupport.get(this.server.tcpPort(), "/hello.txt", 4000).headers().get("x-mark"));
    }

    private SjwsServer stopAndStart(ServerConfig config, RequestFilters filters) throws Exception {
        this.server.close();
        this.server = filters == null ? SjwsServer.start(config) : SjwsServer.start(config, filters);
        return this.server;
    }
}
