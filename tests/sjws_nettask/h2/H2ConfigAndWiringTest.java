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
