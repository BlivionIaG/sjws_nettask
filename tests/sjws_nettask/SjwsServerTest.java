package sjws_nettask;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.HttpTestSupport.Response;

/** Lifecycle + Alt-Svc (H3 design test #18/#20 with the HTTP/3 side stubbed). */
class SjwsServerTest {

    @TempDir
    Path root;
    SjwsServer server;

    /** Fake HTTP/3 transport. */
    static class FakeTransport implements Transport {

        final int port;
        final Exception failure;
        final AtomicInteger starts = new AtomicInteger();
        final AtomicInteger closes = new AtomicInteger();
        final java.util.List<String> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        volatile int tcpPort = -1;

        FakeTransport(int port, Exception failure) {
            this.port = port;
            this.failure = failure;
        }

        @Override
        public String name() {
            return "fake-h3";
        }

        @Override
        public Transport start() throws Exception {
            starts.incrementAndGet();
            if (failure != null) {
                throw failure;
            }
            return this;
        }

        @Override
        public int port() {
            return port;
        }

        @Override
        public void stopAccepting() {
            if (tcpPort < 0) {
                events.add("stopAccepting");
                return;
            }
            boolean tcpOpen;
            try (var s = new java.net.Socket()) {
                s.connect(new java.net.InetSocketAddress("127.0.0.1", tcpPort), 500);
                tcpOpen = true;
            } catch (IOException e) {
                tcpOpen = false;
            }
            events.add("stopAccepting tcp=" + (tcpOpen ? "open" : "closed"));
        }

        @Override
        public void close() {
            events.add("close");
            closes.incrementAndGet();
        }
    }

    @BeforeEach
    void files() throws IOException {
        Files.writeString(root.resolve("hello.txt"), "hello");
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.close();
        }
    }

    private ServerConfig config(boolean h3) {
        return ServerConfig.builder(root).port(0).h3Enabled(h3).h3Port(0).altSvc(true).altSvcMaxAge(Duration.ofSeconds(86400)).build();
    }

    private Response get(String path) throws IOException {
        return HttpTestSupport.get(server.tcpPort(), path, 4000);
    }

    /** Verification V7: --allow-unlistable-dirs reaches the handler shared by both transports. */
    @org.junit.jupiter.params.ParameterizedTest(name = "allowUnlistable={0}")
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void allowUnlistableDirsIsWired(boolean allow) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(sjws_nettask.core.SafeOpen.SECURE);
        org.junit.jupiter.api.Assumptions.assumeFalse("root".equals(System.getProperty("user.name")));
        var xo = Files.createDirectories(root.resolve("xo"));
        Files.writeString(xo.resolve("f.txt"), "f");
        var seen = new java.util.concurrent.atomic.AtomicReference<sjws_nettask.core.StaticFileHandler>();
        var fake = new FakeTransport(4433, null);
        server = SjwsServer.start(config(true).toBuilder().allowUnlistableDirs(allow).build(), (c, h) -> {
            seen.set(h);
            return fake;
        });
        assertEquals(allow, seen.get().allowUnlistableDirs(), "HTTP/3 gets the same policy");
        java.nio.file.Files.setPosixFilePermissions(xo, java.nio.file.attribute.PosixFilePermissions.fromString("--x------"));
        try {
            assertEquals(allow ? 200 : 403, get("/xo/f.txt").statusCode());
        } finally {
            java.nio.file.Files.setPosixFilePermissions(xo, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        }
    }

    /** Runs {@code body} with System.err captured; returns what it printed. */
    static String stderrOf(org.junit.jupiter.api.function.Executable body) throws Throwable {
        var saved = System.err;
        var bytes = new java.io.ByteArrayOutputStream();
        System.setErr(new java.io.PrintStream(bytes, true, java.nio.charset.StandardCharsets.UTF_8));
        try {
            body.execute();
        } finally {
            System.setErr(saved);
        }
        return bytes.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void cacheLargerThanHalfTheHeapIsWarnedAbout() throws Throwable {
        long huge = Runtime.getRuntime().maxMemory();
        var err = stderrOf(() -> server = SjwsServer.start(config(false).toBuilder().cacheBytes(huge).build(), (c, h) -> null));
        assertTrue(err.contains("WARN: --cache-bytes=" + huge + " is more than half of the maximum heap"), err);
        assertEquals(huge, server.config().cacheBytes());
        assertNotNull(server.clientManager());
        assertEquals(200, get("/hello.txt").statusCode());
    }

    @Test
    void failedTransportIsClosedAndItsCloseErrorIsKept() throws Throwable {
        var closeError = new IllegalStateException("close failed too");
        var startError = new java.io.IOException("start failed");
        var fake = new FakeTransport(4433, startError) {
            @Override
            public void close() {
                super.close();
                throw closeError;
            }
        };
        var err = stderrOf(() -> server = SjwsServer.start(config(true), (c, h) -> fake));
        assertSame(startError, server.h3Failure());
        assertArrayEquals(new Throwable[] {closeError}, startError.getSuppressed());
        assertTrue(err.contains("WARN: HTTP/3 not started, serving HTTP/1.1 only (no Alt-Svc): " + startError), err);
        assertTrue(server.describe().contains(", HTTP/3 unavailable, serving "), server.describe());
        assertEquals(200, get("/hello.txt").statusCode());
    }

    @Test
    void errorClosingTheTransportIsReportedAndCloseStillCompletes() throws Throwable {
        var fake = new FakeTransport(4433, null) {
            @Override
            public void close() {
                super.close();
                throw new IllegalStateException("boom");
            }
        };
        server = SjwsServer.start(config(true), (c, h) -> fake);
        int port = server.tcpPort();
        var err = stderrOf(() -> server.close());
        assertTrue(err.contains("WARN: error closing fake-h3: java.lang.IllegalStateException: boom"), err);
        assertEquals(-1, server.tcpPort());
        assertThrows(java.io.IOException.class, () -> HttpTestSupport.get(port, "/hello.txt", 1000));
    }

    @Test
    void closeFromAnInterruptedThreadKeepsTheInterrupt() throws IOException {
        server = SjwsServer.start(config(false), (c, h) -> null);
        Thread.currentThread().interrupt();
        server.close();
        assertTrue(Thread.interrupted(), "interrupt status restored");
        assertEquals(-1, server.tcpPort());
    }

    /** N6: the configured backlog reaches listen(2) (Linux: ss shows it as Send-Q of the listener). */
    @Test
    void listenerUsesTheConfiguredBacklog() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isExecutable(Path.of("/usr/bin/ss"))
                || Files.isExecutable(Path.of("/bin/ss")), "needs ss (Linux)");
        server = SjwsServer.start(config(false).toBuilder().backlog(777).build(), (c, h) -> null);
        var p = new ProcessBuilder("ss", "-ltnH", "sport = :" + server.tcpPort()).redirectErrorStream(true).start();
        var out = new String(p.getInputStream().readAllBytes());
        assertTrue(p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
        var cols = out.trim().split("\\s+");
        assertEquals("LISTEN", cols[0], out);
        assertEquals("777", cols[2], "Send-Q of a listener is its backlog: " + out);
    }

    @Test
    void h3DisabledMeansNoAltSvc() throws IOException {
        var fake = new FakeTransport(4433, null);
        server = SjwsServer.start(config(false), (c, h) -> fake);
        assertEquals(0, fake.starts.get(), "transport must not be created/started when disabled");
        assertEquals(-1, server.udpPort());
        var r = get("/hello.txt");
        assertEquals(200, r.statusCode());
        assertNull(r.headers().get("alt-svc"));
    }

    @Test
    void altSvcAdvertisesTheBoundPortOnEveryHttp1Response() throws IOException {
        var fake = new FakeTransport(4433, null); // h3Port=0 in config: the BOUND port must be advertised
        server = SjwsServer.start(config(true), (c, h) -> {
            assertSame(c.root(), root);
            assertNotNull(h);
            return fake;
        });
        assertEquals(4433, server.udpPort());
        assertNull(server.h3Failure());
        var expected = "h3=\":4433\"; ma=86400";
        assertEquals(expected, get("/hello.txt").headers().get("alt-svc"));
        assertEquals(expected, get("/missing").headers().get("alt-svc"));
        assertEquals(expected, get("/").headers().get("alt-svc")); // listing
        assertEquals(expected, HttpTestSupport.rawRequest(server.tcpPort(),
                "HEAD /hello.txt HTTP/1.1\r\nHost: h\r\n\r\n", 4000).headers().get("alt-svc"));
        assertEquals(expected, HttpTestSupport.rawRequest(server.tcpPort(),
                "POST / HTTP/1.1\r\nHost: h\r\nContent-Length: 0\r\n\r\n", 4000).headers().get("alt-svc"));
        assertEquals(expected, HttpTestSupport.rawRequest(server.tcpPort(),
                "GET / HTTP/1.1\r\n\r\n", 4000).headers().get("alt-svc")); // parser error (no Host): 400
        assertTrue(server.describe().contains("HTTP/3 on udp/4433 (self-signed)"), server.describe());
    }

    @Test
    void h3BindFailureKeepsHttp1WithoutAltSvc() throws IOException {
        var fake = new FakeTransport(4433, new java.net.BindException("Address already in use"));
        server = SjwsServer.start(config(true), (c, h) -> fake);
        assertEquals(1, fake.starts.get());
        assertEquals(1, fake.closes.get(), "a failed transport is closed");
        assertEquals(-1, server.udpPort());
        assertInstanceOf(java.net.BindException.class, server.h3Failure());
        var r = get("/hello.txt");
        assertEquals(200, r.statusCode());
        assertEquals("hello", r.bodyText());
        assertNull(r.headers().get("alt-svc"));
        assertTrue(server.describe().contains("HTTP/3 unavailable"));
    }

    @Test
    void missingNativesAndFactoryErrorsAreTolerated() throws IOException {
        server = SjwsServer.start(config(true), (c, h) -> {
            throw new UnsatisfiedLinkError("no netty_quiche in java.library.path");
        });
        assertInstanceOf(UnsatisfiedLinkError.class, server.h3Failure());
        assertNull(get("/hello.txt").headers().get("alt-svc"));
        server.close();
        server = SjwsServer.start(config(true), (c, h) -> {
            throw new IllegalStateException("bad cert");
        });
        assertEquals(200, get("/hello.txt").statusCode());
        assertNull(get("/hello.txt").headers().get("alt-svc"));
    }

    @Test
    void defaultFactoryStartsRealHttp3() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(sjws_nettask.h3.Http3Server.isAvailable(), "no QUIC natives");
        var err = System.err;
        System.setErr(new java.io.PrintStream(java.io.OutputStream.nullOutputStream())); // self-signed warning
        try {
            server = SjwsServer.start(ServerConfig.builder(root).port(0).h3Enabled(true).h3Port(0)
                    .h3Host("127.0.0.1").build());
        } finally {
            System.setErr(err);
        }
        assertNull(server.h3Failure());
        assertTrue(server.udpPort() > 0);
        assertNull(get("/hello.txt").headers().get("alt-svc"), "Alt-Svc is opt-in (--alt-svc)");
        assertTrue(server.describe().contains("HTTP/3 on udp/" + server.udpPort() + " (self-signed)"),
                server.describe());
    }

    @Test
    void altSvcIsOptIn() throws IOException {
        var fake = new FakeTransport(4433, null);
        server = SjwsServer.start(config(true).toBuilder().altSvc(false).build(), (c, h) -> fake);
        assertEquals(4433, server.udpPort(), "HTTP/3 runs");
        assertNull(server.altSvc().current());
        assertNull(get("/hello.txt").headers().get("alt-svc"));
    }

    /** S-8: HTTP/3 stops accepting when TCP does (not after the TCP accept thread has been joined). */
    @Test
    void h3StopsAcceptingTogetherWithTcp() throws Exception {
        var fake = new FakeTransport(4433, null);
        server = SjwsServer.start(config(true), (c, h) -> fake);
        fake.tcpPort = server.tcpPort();
        server.close();
        assertEquals(java.util.List.of("stopAccepting tcp=open", "close"), fake.events);
    }

    @Test
    void closeIsIdempotentAndStopsBoth() throws Exception {
        var fake = new FakeTransport(4433, null);
        server = SjwsServer.start(config(true), (c, h) -> fake);
        int port = server.tcpPort();
        server.close();
        server.close();
        assertEquals(1, fake.closes.get());
        assertNull(server.altSvc().current());
        server.awaitTermination(); // returns: accept loop ended
        assertThrows(IOException.class, () -> HttpTestSupport.get(port, "/hello.txt", 1000));
    }

    @Test
    void tcpBindFailureClosesTheStartedH3Transport() throws IOException {
        var fake = new FakeTransport(4433, null);
        try (var taken = new ServerSocket(0)) {
            var cfg = config(true).withPort(taken.getLocalPort());
            assertThrows(java.net.BindException.class, () -> SjwsServer.start(cfg, (c, h) -> fake));
        }
        assertEquals(1, fake.starts.get());
        assertEquals(1, fake.closes.get(), "no UDP listener may leak when TCP cannot bind");
    }

    @Test
    void handlerIsSharedWithTheTransport() throws IOException {
        var seen = new java.util.concurrent.atomic.AtomicReference<sjws_nettask.core.StaticFileHandler>();
        server = SjwsServer.start(config(true), (c, h) -> {
            seen.set(h);
            return new FakeTransport(1, null);
        });
        assertSame(server.handler(), seen.get());
    }

    @Test
    void cacheBytesReachTheHandler() throws IOException {
        server = SjwsServer.start(config(false));
        assertEquals(ServerConfig.DEFAULT_CACHE_BYTES, server.handler().cache().maxBytes());
        assertEquals(200, get("/hello.txt").statusCode());
        var r = get("/hello.txt");
        assertEquals("hello", r.bodyText());
        assertNotNull(r.headers().get("etag"));
        assertEquals(1, server.handler().cache().hits());
        server.close();
        server = SjwsServer.start(ServerConfig.builder(root).port(0).cacheBytes(0).build());
        assertFalse(server.handler().cache().enabled());
        assertEquals("hello", get("/hello.txt").bodyText());
        assertEquals(0, server.handler().cache().size());
    }
}
