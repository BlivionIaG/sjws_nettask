package sjws_nettask;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.config.ConfigLoader;
import sjws_nettask.h3.Http3Server;
import sjws_nettask.tls.TlsMaterial;

/** Reload and startup branches that the happy-path tests do not reach. */
class ReloadCoverageTest {

    @TempDir
    Path dir;

    @Test
    void dedicatedChallengePortRebindsAndABusyPortIsRejected() throws Exception {
        var root = directory("www");
        var storage = dir.resolve("acme");
        var yaml = dir.resolve("sjws.yaml");
        int first = freePort();
        write(yaml, server(root) + acme(storage, first, true));
        try (var server = SjwsServer.start(load(yaml))) {
            assertFalse(connectRefused(first));
            int second = freePort();
            write(yaml, server(root) + acme(storage, second, true));
            var moved = server.reload();
            assertTrue(moved.applied(), moved.errors().toString());
            assertTrue(connectRefused(first));
            assertFalse(connectRefused(second));
            try (var held = new ServerSocket(0)) {
                write(yaml, server(root) + acme(storage, held.getLocalPort(), true));
                var rejected = server.reload();
                assertFalse(rejected.applied());
                assertTrue(rejected.errors().get(0).contains("HTTP-01"));
            }
            assertFalse(connectRefused(second));
            write(yaml, server(root) + acme(storage, 0, true));
            var main = server.reload();
            assertTrue(main.applied(), main.errors().toString());
            assertTrue(connectRefused(second));
            write(yaml, server(root) + acme(storage, 0, false));
            var off = server.reload();
            assertTrue(off.applied(), off.errors().toString());
        }
    }

    @Test
    void startupBindFailuresCloseTheServer() throws Exception {
        var root = directory("www");
        var yaml = dir.resolve("bind.yaml");
        try (var held = new ServerSocket(0)) {
            write(yaml, "server:\n  port: " + held.getLocalPort() + "\n  root: " + root.toAbsolutePath() + "\n");
            assertThrows(IOException.class, () -> SjwsServer.start(load(yaml)));
            var err = new ByteArrayOutputStream();
            int code = Sjws_nettask.serve(load(yaml).document().server(), H3Transports.defaultFactory(),
                    new PrintStream(new ByteArrayOutputStream()), new PrintStream(err), server -> {
                    });
            assertEquals(Sjws_nettask.EXIT_CANNOT_LISTEN, code);
            assertTrue(err.toString().contains("Cannot listen"));
        }
        int challenge = freePort();
        try (var held = new ServerSocket(challenge)) {
            write(yaml, server(root) + acme(dir.resolve("acme2"), challenge, true));
            assertThrows(IOException.class, () -> SjwsServer.start(load(yaml)));
        }
    }

    @Test
    void unchangedChallengePortIsLeftBoundUntilClose() throws Exception {
        var root = directory("stay");
        var yaml = dir.resolve("stay.yaml");
        int port = freePort();
        write(yaml, server(root));
        try (var server = SjwsServer.start(load(yaml))) {
            write(yaml, server(root) + acme(dir.resolve("stay-acme"), port, true));
            assertTrue(server.reload().applied());
            assertFalse(connectRefused(port));
            assertTrue(server.reload().applied());
            assertFalse(connectRefused(port));
        }
        assertTrue(connectRefused(port));
    }

    @Test
    void enablingAcmeOnReloadStartsTheRenewer() throws Exception {
        var root = directory("later");
        var yaml = dir.resolve("later.yaml");
        write(yaml, server(root));
        try (var server = SjwsServer.start(load(yaml))) {
            write(yaml, server(root) + acme(dir.resolve("store"), 0, true));
            var on = server.reload();
            assertTrue(on.applied(), on.errors().toString());
            write(yaml, server(root) + acme(dir.resolve("store"), 0, false));
            assertTrue(server.reload().applied());
        }
    }

    @Test
    void http3ClampStoredCertAndTlsReload() throws Exception {
        var root = directory("h3www");
        var storage = dir.resolve("stored");
        Files.createDirectories(storage);
        Files.writeString(storage.resolve("cert.pem"), "not a certificate\n");
        Files.writeString(storage.resolve("domain.key"), "not a key\n");
        var yaml = dir.resolve("h3.yaml");
        write(yaml, server(root) + """
                http3:
                  enabled: true
                  port: 0
                  host: 127.0.0.1
                  alt_svc: true
                """ + acme(storage, 0, true));
        var failed = new AtomicInteger();
        Transport.Factory boom = (config, handler) -> new Transport() {
            public String name() {
                return "boom";
            }

            public Transport start() throws IOException {
                throw new IOException("no quic");
            }

            public int port() {
                return 9;
            }

            public void close() {
                failed.incrementAndGet();
                throw new IllegalStateException("close");
            }
        };
        try (var server = SjwsServer.start(load(yaml), boom)) {
            assertNotNull(server.h3Failure());
            assertEquals(1, failed.get());
            assertEquals(-1, server.udpPort());
        }
        var cert = dir.resolve("a.crt");
        var key = dir.resolve("a.key");
        writePem(cert, key, "localhost");
        write(yaml, server(root) + """
                http3:
                  enabled: true
                  port: 0
                  host: 127.0.0.1
                  min_rate: 0
                  alt_svc: false
                tls:
                  cert: %s
                  key: %s
                """.formatted(cert.toAbsolutePath(), key.toAbsolutePath()));
        var stops = new AtomicInteger();
        Transport.Factory fake = (config, handler) -> new Transport() {
            public String name() {
                return "fake";
            }

            public Transport start() {
                return this;
            }

            public int port() {
                return 443;
            }

            public void close() {
                stops.incrementAndGet();
            }
        };
        try (var server = SjwsServer.start(load(yaml), fake)) {
            assertEquals(443, server.udpPort());
            assertTrue(server.describe().contains("HTTP/3"));
            var missing = dir.resolve("gone");
            write(yaml, "server:\n  port: 0\n  root: " + missing.toAbsolutePath() + "\n");
            var badRoot = server.reload();
            assertFalse(badRoot.applied());
            assertTrue(badRoot.errors().get(0).contains("Document root"));
            var garbage = dir.resolve("garbage.crt");
            Files.writeString(garbage, "nope\n");
            write(yaml, server(root) + "tls:\n  cert: " + garbage.toAbsolutePath() + "\n  key: " + key.toAbsolutePath()
                    + "\n");
            var badTls = server.reload();
            assertFalse(badTls.applied());
            assertTrue(badTls.errors().get(0).startsWith("tls:"));
            write(yaml, """
                    server:
                      port: 9
                      root: %s
                      idle_timeout: 4
                      backlog: 10
                    http3:
                      enabled: false
                      port: 1
                      host: 0.0.0.0
                      min_rate: 10
                      alt_svc: true
                      alt_svc_max_age: 60
                    """.formatted(root.toAbsolutePath()));
            var clamped = server.reload();
            assertTrue(clamped.applied(), clamped.errors().toString());
            assertTrue(clamped.needsRestart().stream().anyMatch(w -> w.contains("timeouts")));
            assertTrue(clamped.needsRestart().stream().anyMatch(w -> w.contains("http3.host")));
            assertTrue(clamped.needsRestart().stream().anyMatch(w -> w.contains("removing tls.cert")));
            assertEquals(root, server.config().root());
        }
        assertEquals(1, stops.get());
    }

    @Test
    void liveHttp3PicksUpAReplacedCertificateAndAStoredAcmeChain() throws Exception {
        assumeTrue(Http3Server.isAvailable(), "QUIC natives");
        var root = directory("live");
        var firstCert = dir.resolve("one.crt");
        var firstKey = dir.resolve("one.key");
        var secondCert = dir.resolve("two.crt");
        var secondKey = dir.resolve("two.key");
        writePem(firstCert, firstKey, "one.example");
        writePem(secondCert, secondKey, "two.example");
        var yaml = dir.resolve("live.yaml");
        write(yaml, server(root) + """
                http3:
                  enabled: true
                  port: 0
                  host: 127.0.0.1
                  alt_svc: true
                tls:
                  cert: %s
                  key: %s
                """.formatted(firstCert.toAbsolutePath(), firstKey.toAbsolutePath()));
        try (var server = SjwsServer.start(load(yaml))) {
            assertTrue(server.udpPort() > 0);
            write(yaml, server(root) + """
                    http3:
                      enabled: true
                      port: 0
                      host: 127.0.0.1
                      alt_svc: true
                    tls:
                      cert: %s
                      key: %s
                    """.formatted(secondCert.toAbsolutePath(), secondKey.toAbsolutePath()));
            var swapped = server.reload();
            assertTrue(swapped.applied(), swapped.errors().toString());
            assertEquals(secondCert.toAbsolutePath(), server.config().tlsCert().toAbsolutePath());
            write(yaml, server(root) + """
                    http3:
                      enabled: true
                      port: 0
                      host: 127.0.0.1
                      alt_svc: true
                    """);
            var dropped = server.reload();
            assertTrue(dropped.applied(), dropped.errors().toString());
            assertTrue(dropped.needsRestart().stream().anyMatch(w -> w.contains("removing tls.cert")));
            assertNull(server.config().tlsCert());
        }
        var storage = dir.resolve("issued");
        Files.createDirectories(storage);
        writePem(storage.resolve("cert.pem"), storage.resolve("domain.key"), "example.com");
        write(yaml, server(root) + """
                http3:
                  enabled: true
                  port: 0
                  host: 127.0.0.1
                  alt_svc: true
                """ + acme(storage, 0, true));
        try (var server = SjwsServer.start(load(yaml))) {
            assertTrue(server.udpPort() > 0);
            Thread.sleep(500);
            assertNull(server.h3Failure());
        }
    }

    @Test
    void aMissingStoredCertificateLeavesHttp3OnItsGeneratedKey() throws Exception {
        var root = directory("empty-acme");
        var yaml = dir.resolve("empty.yaml");
        write(yaml, server(root) + """
                http3:
                  enabled: true
                  port: 0
                  host: 127.0.0.1
                """ + acme(dir.resolve("empty-store"), 0, true));
        Transport.Factory fake = (config, handler) -> new Transport() {
            public String name() {
                return "fake";
            }

            public Transport start() {
                return this;
            }

            public int port() {
                return 1;
            }

            public void close() {
            }
        };
        try (var server = SjwsServer.start(load(yaml), fake)) {
            assertNull(server.h3Failure());
            assertEquals(1, server.udpPort());
        }
    }

    @Test
    void interruptingTheServerThreadStopsIt() throws Exception {
        var root = directory("halt");
        var code = new int[] {-1};
        var started = new CountDownLatch(1);
        var thread = new Thread(() -> code[0] = Sjws_nettask.run(new String[] {"--port=0", "--root=" + root}, Map.of(),
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream()),
                H3Transports.defaultFactory(), server -> started.countDown()));
        thread.start();
        assertTrue(started.await(5, TimeUnit.SECONDS));
        thread.interrupt();
        thread.join(5000);
        assertEquals(Sjws_nettask.EXIT_OK, code[0]);
    }

    private ConfigLoader.Loaded load(Path yaml) {
        var loaded = ConfigLoader.load(new String[] {"--config=" + yaml.toAbsolutePath()}, Map.of());
        assertTrue(loaded.ok(), loaded.errors().toString());
        return loaded;
    }

    private static void write(Path yaml, String text) throws IOException {
        Files.writeString(yaml, text);
    }

    private Path directory(String name) throws IOException {
        var path = dir.resolve(name);
        Files.createDirectory(path);
        Files.writeString(path.resolve("index.html"), name);
        return path;
    }

    private static String server(Path root) {
        return "server:\n  port: 0\n  root: " + root.toAbsolutePath() + "\n";
    }

    private static String acme(Path storage, int httpPort, boolean enabled) {
        return """
                acme:
                  enabled: %s
                  directory: "http://127.0.0.1:1/directory"
                  email: ops@example.com
                  domains: [example.com]
                  storage: %s
                  http_port: %d
                  accept_terms: true
                """.formatted(enabled, storage.toAbsolutePath(), httpPort);
    }

    private static int freePort() throws IOException {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static boolean connectRefused(int port) {
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 500);
            return false;
        } catch (IOException e) {
            return true;
        }
    }

    private static void writePem(Path cert, Path key, String name) throws Exception {
        var material = TlsMaterial.selfSigned(name);
        Files.writeString(cert, pem("CERTIFICATE", material.chain()[0].getEncoded()));
        Files.writeString(key, pem("PRIVATE KEY", material.key().getEncoded()));
        try {
            Files.setPosixFilePermissions(key, java.util.Set.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException e) {
            // filesystems without POSIX permissions keep the process umask
        }
    }

    private static String pem(String type, byte[] der) {
        var body = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n";
    }
}
