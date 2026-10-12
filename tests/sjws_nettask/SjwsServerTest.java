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
    static final class FakeTransport implements Transport {

        final int port;
        final Exception failure;
        final AtomicInteger starts = new AtomicInteger();
        final AtomicInteger closes = new AtomicInteger();

        FakeTransport(int port, Exception failure) {
            this.port = port;
            this.failure = failure;
        }

        @Override
        public String name() {
            return "fake-h3";
        }

        @Override
        public void start() throws Exception {
            starts.incrementAndGet();
            if (failure != null) {
                throw failure;
            }
        }

        @Override
        public int port() {
            return port;
        }

        @Override
        public void close() {
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
}
