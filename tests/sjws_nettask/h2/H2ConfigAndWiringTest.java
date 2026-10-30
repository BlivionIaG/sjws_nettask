package sjws_nettask.h2;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.ServerConfig;
import sjws_nettask.SjwsServer;

/** --h2 / --h2-port / --h2c-port parsing, and the HTTP/2 transport's wiring into {@link SjwsServer}. */
class H2ConfigAndWiringTest {

    @TempDir
    Path root;

    private static ServerConfig.Parsed parse(Map<String, String> env, String... args) {
        return ServerConfig.parse(args, env, f -> "pw");
    }

    @Test
    void defaultsAreOff() {
        var c = ServerConfig.defaults(".");
        assertFalse(c.h2Enabled());
        assertEquals(8443, c.h2Port());
        assertEquals(ServerConfig.H2C_DISABLED, c.h2cPort());
    }

    @Test
    void flagsAndEnvironment() {
        var c = parse(Map.of(), "--h2", "--h2-port=9443", "--h2c-port=9080").config();
        assertTrue(c.h2Enabled());
        assertEquals(9443, c.h2Port());
        assertEquals(9080, c.h2cPort());
        var e = parse(Map.of("WEB_SERVER_H2", "yes", "WEB_SERVER_H2_PORT", "1443", "WEB_SERVER_H2C_PORT", "1080"),
                "--no-h2", "--h2-port=9443").config();
        assertTrue(e.h2Enabled(), "environment wins");
        assertEquals(1443, e.h2Port());
        assertEquals(1080, e.h2cPort());
        assertFalse(parse(Map.of(), "--h2", "--no-h2").config().h2Enabled());
        assertTrue(parse(Map.of(), "--h2=on").config().h2Enabled());
    }

    @Test
    void h2OptionsWithoutH2AreWarnedAndH2cIsOff() {
        var p = parse(Map.of(), "--h2c-port=9080");
        assertEquals(ServerConfig.H2C_DISABLED, p.config().h2cPort());
        assertTrue(p.warnings().stream().anyMatch(w -> w.contains("HTTP/2 is disabled") && w.contains("--h2c-port")),
                p.warnings().toString());
    }

    @Test
    void certificateOptionsAreNotReportedAsUselessWithH2() {
        var p = parse(Map.of(), "--h2", "--cert=a.pem", "--key=k.pem");
        assertTrue(p.warnings().stream().noneMatch(w -> w.contains("HTTP/3 is disabled")), p.warnings().toString());
        var without = parse(Map.of(), "--cert=a.pem", "--key=k.pem");
        assertTrue(without.warnings().stream().anyMatch(w -> w.contains("HTTP/3 is disabled")));
    }

    @Test
    void keyPasswordFileIsReadForH2() {
        var p = parse(Map.of(), "--h2", "--cert=a.pem", "--key=k.pem", "--key-password-file=pw.txt");
        assertEquals("pw", p.config().tlsKeyPassword());
    }

    @Test
    void invalidPortsAreRejected() {
        var b = ServerConfig.builder(Path.of("."));
        assertThrows(IllegalArgumentException.class, () -> b.h2Port(-1).build());
        assertThrows(IllegalArgumentException.class, () -> b.h2Port(1).h2cPort(-2).build());
        assertThrows(IllegalArgumentException.class, () -> b.h2Port(70000).build());
        assertThrows(IllegalArgumentException.class, () -> b.h2Port(1).h2cPort(70000).build());
        assertTrue(ServerConfig.defaults(".").toString().contains("h2Enabled=false"));
    }

    private ServerConfig config() throws Exception {
        Files.writeString(this.root.resolve("a.txt"), "a");
        return ServerConfig.builder(this.root).port(0).h2Enabled(true).h2Port(0).h2cPort(0).build();
    }

    /** Filters (access rules, routes, middleware) apply to HTTP/2 exactly as to HTTP/1.1. */
    /**
     * #289 P1: {@link SjwsServer#startH2} arms HTTP/2 with a live-snapshot lambda that is never a
     * {@link sjws_nettask.core.StaticFileHandler}. Bodies must stay off for static-only production wiring.
     */
    /**
     * #289: YAML {@code routing.enabled} compiles a router into the live snapshot while {@code override}
     * stays null. Bodies must be on so proxy routes receive DATA through the live-snapshot arm.
     */
    @Test
    void yamlRoutingEnablesBodiesThroughLiveSnapshot() throws Exception {
        Files.writeString(this.root.resolve("a.txt"), "a");
        var yaml = this.root.resolve("sjws.yaml");
        Files.writeString(yaml, """
                server:
                  port: 0
                  root: "%s"
                routing:
                  enabled: true
                  upstreams:
                    loop: { url: "http://127.0.0.1:9" }
                  routes:
                    - { prefix: /api, proxy: loop }
                """.formatted(this.root.toAbsolutePath()));
        var loaded = sjws_nettask.config.ConfigLoader.load(
                new String[] {"--config=" + yaml, "--h2", "--h2-port=0", "--h2c-port=0", "--no-h3"}, Map.of());
        assertTrue(loaded.ok(), loaded.errors().toString());
        try (var server = SjwsServer.start(loaded)) {
            assertFalse(server.requestHandler() instanceof sjws_nettask.core.StaticFileHandler,
                    "live snapshot should be a router");
            assertTrue(((Http2Server) server.h2()).attachBodies(),
                    "YAML routing under the live-snapshot arm must enable bodies");
        }
    }

    /**
     * #289: publish/reload must refresh attachBodies from the live handler (and keep it on when
     * an observe/chain-control handler is armed). Covers the publish-path branches jacoco gates.
     */
    @Test
    void publishRefreshAttachBodiesOnReload() throws Exception {
        Files.writeString(this.root.resolve("a.txt"), "a");
        var yaml = this.root.resolve("sjws.yaml");
        var rootAbs = this.root.toAbsolutePath();
        Files.writeString(yaml, """
                server:
                  port: 0
                  root: "%s"
                routing:
                  enabled: true
                  upstreams:
                    loop: { url: "http://127.0.0.1:9" }
                  routes:
                    - { prefix: /api, proxy: loop }
                """.formatted(rootAbs));
        var loaded = sjws_nettask.config.ConfigLoader.load(
                new String[] {"--config=" + yaml, "--h2", "--h2-port=0", "--h2c-port=0", "--no-h3"}, Map.of());
        assertTrue(loaded.ok(), loaded.errors().toString());
        try (var server = SjwsServer.start(loaded)) {
            var h2 = (Http2Server) server.h2();
            assertTrue(h2.attachBodies(), "routing at start");
            Files.writeString(yaml, """
                    server:
                      port: 0
                      root: "%s"
                      idle_timeout: 33s
                    routing:
                      enabled: true
                      upstreams:
                        loop: { url: "http://127.0.0.1:9" }
                      routes:
                        - { prefix: /api, proxy: loop }
                    """.formatted(rootAbs));
            assertTrue(server.reload().applied(), "routing reload must apply");
            assertTrue(h2.attachBodies(), "publish keeps bodies on while live handler is a router");
        }
        Files.writeString(yaml, """
                server:
                  port: 0
                  root: "%s"
                """.formatted(rootAbs));
        loaded = sjws_nettask.config.ConfigLoader.load(
                new String[] {"--config=" + yaml, "--h2", "--h2-port=0", "--h2c-port=0", "--no-h3"}, Map.of());
        assertTrue(loaded.ok(), loaded.errors().toString());
        try (var server = SjwsServer.start(loaded)) {
            var h2 = (Http2Server) server.h2();
            assertFalse(h2.attachBodies());
            server.armMetrics();
            assertTrue(h2.attachBodies(), "armMetrics installs an observe chain that needs bodies");
            Files.writeString(yaml, """
                    server:
                      port: 0
                      root: "%s"
                      idle_timeout: 34s
                    """.formatted(rootAbs));
            assertTrue(server.reload().applied());
            assertTrue(h2.attachBodies(),
                    "publish must keep bodies on when armed is non-null even if live handler is static");
        }
    }

    @Test
    void productionStaticArmKeepsBodiesOff() throws Exception {
        try (var server = SjwsServer.start(config())) {
            var h2 = (Http2Server) server.h2();
            assertNotNull(h2);
            assertFalse(h2.attachBodies(),
                    "production live-snapshot arm must not enable bodies on a static-only server");
        }
    }

    /** A filter chain at startup needs bodies/fields (RequestSizeFilter, echo, etc.). */
    @Test
    void aFilterChainAtStartupEnablesBodies() throws Exception {
        var filters = sjws_nettask.core.RequestFilters.create().add((request, next) -> next.proceed(request));
        try (var server = SjwsServer.start(config(), filters)) {
            assertTrue(((Http2Server) server.h2()).attachBodies());
        }
    }

    /** Hot-swap style: arming a chain turns bodies on; re-arming the static live snapshot turns them off. */
    @Test
    void armHandlerHotSwapTogglesBodies() throws Exception {
        try (var server = SjwsServer.start(config())) {
            var h2 = (Http2Server) server.h2();
            assertFalse(h2.attachBodies());
            var chain = sjws_nettask.core.RequestFilters.create()
                    .add((request, next) -> next.proceed(request))
                    .build(request -> sjws_nettask.core.StaticFileHandler.error(404, false));
            h2.armHandler(chain);
            assertTrue(h2.attachBodies());
            h2.armHandler(request -> server.handler().handle(request), false);
            assertFalse(h2.attachBodies());
        }
    }

    @Test
    void requestFiltersApplyOverHttp2() throws Exception {
        var filters = sjws_nettask.core.RequestFilters.create().add((request, next) ->
                request.target().startsWith("/blocked")
                        ? sjws_nettask.core.StaticFileHandler.error(403, request.headOnly())
                        : next.proceed(request));
        try (var server = SjwsServer.start(config(), filters);
                var h2 = new RawH2(((sjws_nettask.h2.Http2Server) server.h2()).h2cPort(), 65535)) {
            h2.get(1, "/blocked/a.txt");
            h2.get(3, "/a.txt");
            String s1 = null, s3 = null;
            for (RawH2.Frame f; (s1 == null || s3 == null) && (f = h2.read()) != null;) {
                if (f.type() == RawH2.HEADERS) {
                    var status = statusOf(f.payload());
                    if (f.stream() == 1) {
                        s1 = status;
                    } else if (f.stream() == 3) {
                        s3 = status;
                    }
                }
            }
            assertEquals("403", s1, "the filter ran on HTTP/2");
            assertEquals("200", s3);
        }
    }

    /** Observability on at startup: the HTTP/2 handler is the filter chain, and the peer is loopback. */
    @Test
    void recordingAtStartupCountsHttp2() throws Exception {
        var config = config().toBuilder().metricsExposure(ServerConfig.MetricsExposure.LOCALHOST).build();
        try (var server = SjwsServer.start(config);
                var h2 = new RawH2(((Http2Server) server.h2()).h2cPort(), 65535)) {
            h2.get(1, "/metrics");
            assertEquals("200", awaitStatus(h2, 1));
            var snap = awaitHttp2(server, 1);
            assertEquals(0, snap.protocol("HTTP/1.1"));
        }
    }

    /** A server that started dark still counts the next HTTP/2 request after metricsSnapshot(). */
    @Test
    void metricsSnapshotCountsTheNextHttp2Request() throws Exception {
        try (var server = SjwsServer.start(config());
                var h2 = new RawH2(((Http2Server) server.h2()).h2cPort(), 65535)) {
            h2.get(1, "/a.txt");
            assertEquals("200", awaitStatus(h2, 1));
            assertEquals(0, server.metrics().snapshot().requests());
            server.metricsSnapshot();
            h2.get(3, "/a.txt");
            assertEquals("200", awaitStatus(h2, 3));
            var snap = awaitHttp2(server, 1);
            assertEquals(0, snap.protocol("HTTP/1.1"));
        }
    }

    @Test
    void accessRulesApplyOverHttp2() throws Exception {
        Files.writeString(this.root.resolve("a.txt"), "a");
        Files.writeString(this.root.resolve("old.bak"), "old");
        Files.writeString(this.root.resolve(".env"), "SECRET=1");
        Files.createDirectories(this.root.resolve("private"));
        Files.writeString(this.root.resolve("private/x.txt"), "x");
        // the rules come from the command line, as in production
        var parsed = ServerConfig.parse(new String[] {"--deny=*.bak", "--access-rule=hide:/private"}, Map.of());
        assertTrue(parsed.ok(), parsed.errors().toString());
        try (var server = SjwsServer.start(config().toBuilder().access(parsed.config().access()).build());
                var h2 = new RawH2(((sjws_nettask.h2.Http2Server) server.h2()).h2cPort(), 65535)) {
            String[] paths = {"/a.txt", "/old.bak", "/.env", "/private/x.txt", "/sub/../old.bak"};
            var statuses = new String[paths.length];
            for (int i = 0; i < paths.length; i++) {
                h2.get(2 * i + 1, paths[i]);
            }
            int seen = 0;
            var hpack = new io.netty.handler.codec.http2.DefaultHttp2HeadersDecoder(); // stateful: dynamic table
            for (RawH2.Frame f; seen < paths.length && (f = h2.read()) != null;) {
                if (f.type() == RawH2.HEADERS && f.stream() % 2 == 1 && statuses[f.stream() / 2] == null) {
                    var buf = io.netty.buffer.Unpooled.wrappedBuffer(f.payload());
                    statuses[f.stream() / 2] = hpack.decodeHeaders(f.stream(), buf).status().toString();
                    seen++;
                }
            }
            assertArrayEquals(new String[] {"200", "403", "404", "404", "403"}, statuses,
                    "deny list 403, dotfile 404, hide rule 404 over HTTP/2");
        }
    }

    private static String awaitStatus(RawH2 h2, int stream) throws Exception {
        var deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            var frame = h2.read();
            if (frame != null && frame.type() == RawH2.HEADERS && frame.stream() == stream) {
                return statusOf(frame.payload());
            }
        }
        fail("no HEADERS on stream " + stream);
        return "";
    }

    private static sjws_nettask.observe.Snapshot awaitHttp2(SjwsServer server, long atLeast) {
        var deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
        var last = server.metrics().snapshot();
        while (last.requests() < atLeast || last.protocol("HTTP/2") < atLeast) {
            if (System.nanoTime() >= deadline) {
                fail("counters stayed at requests=" + last.requests() + " http/2=" + last.protocol("HTTP/2"));
            }
            Thread.yield();
            last = server.metrics().snapshot();
        }
        return last;
    }

    /** :status from a server HEADERS block: Netty encodes it as an indexed field (static table 8-14). */
    private static String statusOf(byte[] block) {
        return switch (block[0] & 0xff) {
            case 0x88 -> "200";
            case 0x8b -> "206";
            case 0x8c -> "304";
            case 0x8d -> "400";
            case 0x8e -> "404";
            default -> new String(block, java.nio.charset.StandardCharsets.ISO_8859_1).contains("403") ? "403" : "?";
        };
    }

    @Test
    void serverStartsAndDescribesHttp2() throws Exception {
        try (var server = SjwsServer.start(config())) {
            assertTrue(server.h2Port() > 0);
            assertNull(server.h2Failure());
            assertTrue(server.describe().contains("HTTP/2 on tcp/" + server.h2Port() + " (h2c tcp/"),
                    server.describe());
            assertEquals("HTTP/2", server.h2().name());
        }
    }

    @Test
    void busyPortLeavesHttp11Running() throws Exception {
        var err = new ByteArrayOutputStream();
        var old = System.err;
        try (var busy = new ServerSocket(0)) {
            System.setErr(new PrintStream(err, true));
            try (var server = SjwsServer.start(config().toBuilder().h2Port(busy.getLocalPort()).build())) {
                assertEquals(-1, server.h2Port());
                assertNull(server.h2());
                assertNotNull(server.h2Failure());
                assertTrue(server.describe().contains("HTTP/2 unavailable"));
                assertTrue(server.tcpPort() > 0);
            }
        } finally {
            System.setErr(old);
        }
        assertTrue(err.toString().contains("HTTP/2 not started"), err.toString());
    }

    /** A fake transport: optionally failing start and/or close. */
    static sjws_nettask.Transport fake(boolean failStart, boolean failClose) {
        return new sjws_nettask.Transport() {
            @Override
            public String name() {
                return "fake-h2";
            }

            @Override
            public sjws_nettask.Transport start() throws Exception {
                if (failStart) {
                    throw new java.io.IOException("start failed");
                }
                return this;
            }

            @Override
            public int port() {
                return 4242;
            }

            @Override
            public void close() {
                if (failClose) {
                    throw new IllegalStateException("close failed");
                }
            }
        };
    }

    private String stderr(ThrowingRunnable r) throws Exception {
        var err = new ByteArrayOutputStream();
        var old = System.err;
        System.setErr(new PrintStream(err, true));
        try {
            r.run();
        } finally {
            System.setErr(old);
        }
        return err.toString();
    }

    interface ThrowingRunnable {
        void run() throws Exception;
    }

    @Test
    void failingTransportIsClosedAndItsCloseErrorSuppressed() throws Exception {
        var config = config();
        var holder = new SjwsServer[1];
        var err = stderr(() -> holder[0] = SjwsServer.start(config, (c, h) -> null, (c, h) -> fake(true, true)));
        try (var server = holder[0]) {
            assertEquals(1, server.h2Failure().getSuppressed().length);
            assertTrue(err.contains("HTTP/2 not started"), err);
        }
        var nullTransport = stderr(() -> SjwsServer.start(config, (c, h) -> null, (c, h) -> null).close());
        assertTrue(nullTransport.contains("HTTP/2 not started"), nullTransport);
    }

    @Test
    void foreignTransportIsDescribedAndItsCloseErrorReported() throws Exception {
        var config = config();
        var server = SjwsServer.start(config, (c, h) -> null, (c, h) -> fake(false, true));
        assertEquals(4242, server.h2Port());
        assertTrue(server.describe().contains("HTTP/2 on tcp/4242, serving"), server.describe());
        var err = stderr(server::close);
        assertTrue(err.contains("error closing fake-h2"), err);
    }

    @Test
    void moreConfigCases() {
        assertFalse(parse(Map.of(), "--h2=false").config().h2Enabled());
        var both = parse(Map.of(), "--h2-port=1", "--h2c-port=2");
        assertTrue(both.warnings().stream().anyMatch(w -> w.contains("have no effect (add --h2)")), both.warnings().toString());
        var env = parse(Map.of("WEB_SERVER_TLS_CERT", "a.pem", "WEB_SERVER_TLS_KEY", "k.pem"), "--h2");
        assertTrue(env.warnings().stream().noneMatch(w -> w.contains("HTTP/3 is disabled")), env.warnings().toString());
        var mixed = parse(Map.of(), "--h2", "--cert=a.pem", "--key=k.pem", "--h3-port=5");
        assertTrue(mixed.warnings().stream().anyMatch(w -> w.contains("HTTP/3 is disabled, so --h3-port has")),
                mixed.warnings().toString());
    }

    @Test
    void h2DisabledStartsNothing() throws Exception {
        try (var server = SjwsServer.start(config().toBuilder().h2Enabled(false).build())) {
            assertEquals(-1, server.h2Port());
            assertFalse(server.describe().contains("HTTP/2"));
        }
    }
}
