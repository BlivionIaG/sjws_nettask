package sjws_nettask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.config.ConfigLoader;
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


    /**
     * Codex P2 4421410868: custom h2 Transport via start(..., h2Factory, ...) must still receive
     * {@link Transport#armHandler} on chain-control apply and metrics enable. The Http2Server
     * generation path must not skip the interface for other implementations.
     */
    @Test
    void customH2TransportReceivesChainControlAndMetricsArms() throws Exception {
        Files.writeString(root.resolve("hello.txt"), "hello");
        var armed = new AtomicReference<Handler>();
        var armCalls = new AtomicInteger();
        Transport fakeH2 = new Transport() {
            @Override
            public String name() {
                return "fake-h2";
            }

            @Override
            public Transport start() {
                return this;
            }

            @Override
            public int port() {
                return 9443;
            }

            @Override
            public void close() {
            }

            @Override
            public void armHandler(Handler handler) {
                armCalls.incrementAndGet();
                armed.set(handler);
            }
        };
        Transport.Factory h3Unused = (config, handler) -> {
            throw new AssertionError("h3 factory must not run when h3 is disabled");
        };
        Transport.Factory h2Factory = (config, handler) -> fakeH2;
        // Public overload: start(ServerConfig, h3Factory, h2Factory, filters, terminal).
        this.server = SjwsServer.start(
                ServerConfig.builder(root).port(0).h2Enabled(true).h2Port(0).h2cPort(0).build(),
                h3Unused, h2Factory, registry(), null);
        assertSame(fakeH2, this.server.h2());
        assertEquals(0, armCalls.get(), "start alone must not require a late arm");

        var control = this.server.chainControl(registry(), Set.of());
        control.apply(doc(1, "auth", FilterScope.all()));
        assertTrue(armCalls.get() >= 1, "chain-control apply must armHandler the custom h2 transport");
        assertSame(control.published(), armed.get(),
                "custom h2 must receive the armed chain-control handler");
        assertTrue(armed.get() instanceof FilterChain);
        // Auth-style filter visible through the published chain (mark id=auth).
        assertEquals("auth", HttpTestSupport.get(this.server.tcpPort(), "/hello.txt", 4000).headers().get("x-mark"));

        int afterApply = armCalls.get();
        this.server.armMetrics();
        assertTrue(armCalls.get() > afterApply,
                "armMetrics must armHandler the custom h2 transport with the observe chain");
        assertNotNull(armed.get());
        this.server.close();
        this.server = null;

        // File-backed reload covers publish()'s custom-h2 else-if (jacoco BRANCH).
        var yaml = root.resolve("sjws-custom-h2.yaml");
        Files.writeString(yaml, """
                server:
                  port: 0
                  root: "%s"
                """.formatted(root.toAbsolutePath()));
        var loaded = ConfigLoader.load(new String[] {
                "--config=" + yaml, "--h2", "--h2-port=0", "--h2c-port=0", "--no-h3"}, Map.of());
        assertTrue(loaded.ok(), loaded.errors().toString());
        armCalls.set(0);
        armed.set(null);
        this.server = SjwsServer.start(loaded, h3Unused, h2Factory, registry(), null);
        var control2 = this.server.chainControl(registry(), Set.of());
        control2.apply(doc(1, "auth", FilterScope.all()));
        int afterApply2 = armCalls.get();
        var www2 = Files.createDirectories(root.resolve("www2"));
        Files.writeString(www2.resolve("hello.txt"), "hello2");
        Files.writeString(yaml, """
                server:
                  port: 0
                  root: "%s"
                """.formatted(www2.toAbsolutePath()));
        assertTrue(this.server.reload().applied());
        assertTrue(armCalls.get() > afterApply2, "publish must armHandler custom h2 on config reload");
        assertEquals("auth", HttpTestSupport.get(this.server.tcpPort(), "/hello.txt", 4000).headers().get("x-mark"));
        assertEquals("hello2", HttpTestSupport.get(this.server.tcpPort(), "/hello.txt", 4000).bodyText());
    }


    @Test
    void customH2PublishWithoutLiveControl() throws Exception {
        Files.writeString(root.resolve("plain.txt"), "plain");
        var armCalls = new AtomicInteger();
        Transport fakeH2 = new Transport() {
            @Override public String name() { return "fake-h2"; }
            @Override public Transport start() { return this; }
            @Override public int port() { return 9444; }
            @Override public void close() {}
            @Override public void armHandler(Handler handler) { armCalls.incrementAndGet(); }
        };
        Transport.Factory h3Unused = (c, h) -> { throw new AssertionError("no h3"); };
        var yaml = root.resolve("sjws-custom-h2-nl.yaml");
        Files.writeString(yaml, """
                server:
                  port: 0
                  root: "%s"
                """.formatted(root.toAbsolutePath()));
        var loaded = ConfigLoader.load(new String[] {
                "--config=" + yaml, "--h2", "--h2-port=0", "--h2c-port=0", "--no-h3"}, Map.of());
        assertTrue(loaded.ok(), loaded.errors().toString());
        // Startup filters → override != null; control never applied → publishTerminal uses override.
        var startup = registry().add((request, next) -> {
            var response = next.proceed(request);
            response.headers().set("x-startup", "1");
            return response;
        });
        this.server = SjwsServer.start(loaded, h3Unused, (c, h) -> fakeH2, startup, null);
        // Open the slot without apply so control != null && !isLive() on publish.
        this.server.chainControl(startup, Set.of());
        int before = armCalls.get();
        var www2 = Files.createDirectories(root.resolve("www-nl2"));
        Files.writeString(www2.resolve("plain.txt"), "plain2");
        Files.writeString(yaml, """
                server:
                  port: 0
                  root: "%s"
                """.formatted(www2.toAbsolutePath()));
        assertTrue(this.server.reload().applied());
        assertTrue(armCalls.get() > before, "publish arms custom h2 when control is not live");
        assertEquals("1", HttpTestSupport.get(this.server.tcpPort(), "/plain.txt", 4000).headers().get("x-startup"));
        assertEquals("plain2", HttpTestSupport.get(this.server.tcpPort(), "/plain.txt", 4000).bodyText());
    }


    /**
     * Middleware Chain on #302: custom publish must hold withPublishLock across compile +
     * armHandler + armed.set so a concurrent apply cannot lose to a stale publish arm.
     */
    @Test
    void customH2PublishHoldsLockAcrossArm() throws Exception {
        Files.writeString(root.resolve("hello.txt"), "hello");
        var yaml = root.resolve("sjws-lock.yaml");
        Files.writeString(yaml, """
                server:
                  port: 0
                  root: "%s"
                """.formatted(root.toAbsolutePath()));
        var loaded = ConfigLoader.load(new String[] {
                "--config=" + yaml, "--h2", "--h2-port=0", "--h2c-port=0", "--no-h3"}, Map.of());
        assertTrue(loaded.ok(), loaded.errors().toString());

        var enteredArm = new CountDownLatch(1);
        var releaseArm = new CountDownLatch(1);
        var lastArmed = new AtomicReference<Handler>();
        var armCalls = new AtomicInteger();
        Transport fakeH2 = new Transport() {
            @Override public String name() { return "fake-h2"; }
            @Override public Transport start() { return this; }
            @Override public int port() { return 9445; }
            @Override public void close() {}
            @Override
            public void armHandler(Handler handler) {
                int n = armCalls.incrementAndGet();
                if (n == 2) { // first post-apply publish arm
                    enteredArm.countDown();
                    try {
                        assertTrue(releaseArm.await(15, TimeUnit.SECONDS));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                }
                lastArmed.set(handler);
            }
        };
        Transport.Factory h3Unused = (c, h) -> { throw new AssertionError("no h3"); };
        this.server = SjwsServer.start(loaded, h3Unused, (c, h) -> fakeH2, registry(), null);
        var control = this.server.chainControl(registry(), Set.of());
        control.apply(doc(1, "one", FilterScope.all()));
        assertEquals(1, armCalls.get());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> reload = pool.submit(() -> {
                try {
                    var www2 = Files.createDirectories(root.resolve("www-lock2"));
                    Files.writeString(www2.resolve("hello.txt"), "hello2");
                    Files.writeString(yaml, """
                            server:
                              port: 0
                              root: "%s"
                            """.formatted(www2.toAbsolutePath()));
                    assertTrue(this.server.reload().applied());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            assertTrue(enteredArm.await(15, TimeUnit.SECONDS), "reload must reach custom armHandler");
            Future<?> apply = pool.submit(() -> control.apply(doc(2, "two", FilterScope.all())));
            Thread.sleep(200);
            releaseArm.countDown();
            reload.get(20, TimeUnit.SECONDS);
            apply.get(20, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertSame(control.published(), lastArmed.get(),
                "final custom arm must be the last compile (apply doc=two), not a stale publish chain");
        assertEquals("two", HttpTestSupport.get(this.server.tcpPort(), "/hello.txt", 4000).headers().get("x-mark"));
    }

    private SjwsServer stopAndStart(ServerConfig config, RequestFilters filters) throws Exception {
        this.server.close();
        this.server = filters == null ? SjwsServer.start(config) : SjwsServer.start(config, filters);
        return this.server;
    }
}
