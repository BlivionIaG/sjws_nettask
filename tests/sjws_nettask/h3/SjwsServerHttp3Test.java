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
package sjws_nettask.h3;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sjws_nettask.h3.H3Fixture.assertParity;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.EncryptedPrivateKeyInfo;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.PBEParameterSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.HttpTestSupport;
import sjws_nettask.ServerConfig;
import sjws_nettask.SjwsServer;
import sjws_nettask.Transport;
import sjws_nettask.tls.TlsMaterial;

/**
 * Milestone M2 end to end: the full {@link SjwsServer} (HTTP/1.1 + HTTP/3 through
 * {@code H3Transports.defaultFactory()}), Alt-Svc on HTTP/1.1, HTTP/3 parity, and the fallbacks.
 */
@Tag("h3")
class SjwsServerHttp3Test {

    @TempDir
    Path base;
    Path root;
    TlsMaterial tls;
    Path cert;
    Path key;
    SjwsServer server;

    @BeforeAll
    static void natives() {
        org.junit.jupiter.api.Assumptions.assumeTrue(Http3Server.isAvailable(),
                () -> "native QUIC unavailable: " + Http3Server.unavailabilityCause());
    }

    @BeforeEach
    void setUp() throws Exception {
        this.root = H3Fixture.webRoot(this.base);
        this.tls = TlsMaterial.selfSigned("localhost");
        this.cert = Files.writeString(this.base.resolve("cert.pem"), pem("CERTIFICATE", this.tls.certificate().getEncoded()));
        this.key = Files.writeString(this.base.resolve("key.pem"), pem("PRIVATE KEY", this.tls.key().getEncoded()));
    }

    @AfterEach
    void tearDown() {
        if (this.server != null) {
            this.server.close();
        }
    }

    static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder().encodeToString(der) + "\n-----END " + type
                + "-----\n";
    }

    ServerConfig.Builder config() {
        return ServerConfig.builder(this.root).port(0).h3Enabled(true).h3Port(0).h3Host("127.0.0.1")
                .tlsCert(this.cert).tlsKey(this.key).altSvc(true).altSvcMaxAge(Duration.ofSeconds(3600));
    }

    @Test
    void altSvcAdvertisesTheRealUdpPortAndHttp3MatchesHttp1() throws Exception {
        this.server = SjwsServer.start(config().build());
        assertNull(this.server.h3Failure());
        int udp = this.server.udpPort();
        assertTrue(udp > 0);
        var expected = "h3=\":" + udp + "\"; ma=3600";
        var tcp = this.server.tcpPort();
        var h1 = HttpTestSupport.get(tcp, "/hello.txt", 4000);
        assertEquals(expected, h1.headers().get("alt-svc"));
        assertEquals(expected, HttpTestSupport.get(tcp, "/missing", 4000).headers().get("alt-svc"));
        assertEquals(expected, HttpTestSupport.rawRequest(tcp,
                "HEAD /hello.txt HTTP/1.1\r\nHost: localhost\r\n\r\n", 4000).headers().get("alt-svc"));
        assertTrue(this.server.describe().contains("HTTP/3 on udp/" + udp + " (cert " + this.cert + ")"),
                this.server.describe());

        try (var client = new H3TestClient(this.tls.certificate())) { // trusts exactly the configured PEM cert
            var quic = client.connect(new java.net.InetSocketAddress("127.0.0.1", udp));
            for (var path : new String[] {"/hello.txt", "/bin.dat", "/sub/", "/missing", "/sub", "/%zz"}) {
                var h3 = H3TestClient.get(quic, path);
                assertParity(this.server.handler(), "GET", path, Map.of(), h3);
                assertNull(h3.header("alt-svc"), "Alt-Svc is an HTTP/1.1-side header");
                var real = HttpTestSupport.get(tcp, path, 4000); // the real HTTP/1.1 listener agrees too
                assertEquals(real.statusCode(), h3.status(), path);
                assertArrayEquals(real.body(), h3.body(), path);
                assertEquals(real.headers().get("content-type"), h3.header("content-type"), path);
                assertEquals(real.headers().get("content-length"), h3.header("content-length"), path);
            }
        }
        this.server.close();
        assertNull(this.server.altSvc().current(), "withdrawn on close");
    }

    @Test
    void encryptedKeyWithPasswordFromConfig() throws Exception {
        var encrypted = Files.writeString(this.base.resolve("enc.pem"),
                pem("ENCRYPTED PRIVATE KEY", encrypt(this.tls.key().getEncoded(), "pa55")));
        this.server = SjwsServer.start(config().tlsKey(encrypted).tlsKeyPassword("pa55").build());
        assertNull(this.server.h3Failure());
        try (var client = new H3TestClient(this.tls.certificate())) {
            var quic = client.connect(new java.net.InetSocketAddress("127.0.0.1", this.server.udpPort()));
            assertEquals(200, H3TestClient.get(quic, "/hello.txt").status());
        }
    }

    @Test
    void wrongKeyPasswordFallsBackToHttp1() throws Exception {
        var encrypted = Files.writeString(this.base.resolve("enc.pem"),
                pem("ENCRYPTED PRIVATE KEY", encrypt(this.tls.key().getEncoded(), "pa55")));
        this.server = quietly(() -> SjwsServer.start(config().tlsKey(encrypted).tlsKeyPassword("nope").build()));
        assertNotNull(this.server.h3Failure());
        assertTrue(this.server.h3Failure().getMessage().contains("could not decrypt"));
        assertNull(HttpTestSupport.get(this.server.tcpPort(), "/hello.txt", 4000).headers().get("alt-svc"));
    }

    static byte[] encrypt(byte[] pkcs8, String password) throws Exception {
        var algorithm = "PBEWithSHA1AndDESede";
        var salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        var cipher = Cipher.getInstance(algorithm);
        cipher.init(Cipher.ENCRYPT_MODE,
                SecretKeyFactory.getInstance(algorithm).generateSecret(new PBEKeySpec(password.toCharArray())),
                new PBEParameterSpec(salt, 2048));
        return new EncryptedPrivateKeyInfo(cipher.getParameters(), cipher.doFinal(pkcs8)).getEncoded();
    }

    @Test
    void busyUdpPortFallsBackToHttp1WithoutAltSvc() throws Exception {
        try (var busy = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            int udp = busy.getLocalPort();
            this.server = quietly(() -> SjwsServer.start(config().h3Port(udp).build()));
            assertNotNull(this.server.h3Failure());
            assertTrue(this.server.h3Failure().getMessage().contains("udp/127.0.0.1:" + udp),
                    this.server.h3Failure().getMessage());
            assertEquals(-1, this.server.udpPort());
            assertTrue(this.server.describe().contains("HTTP/3 unavailable"));
            var r = HttpTestSupport.get(this.server.tcpPort(), "/hello.txt", 4000);
            assertEquals(200, r.statusCode());
            assertNull(r.headers().get("alt-svc"));
            assertNull(this.server.altSvc().current());
        }
    }

    @Test
    void missingNativesFallBackWithAClearWarning() throws Exception {
        Transport.Factory missing = (c, h) -> new Http3Transport(c, h, () -> false,
                () -> new UnsatisfiedLinkError("no libnetty_quiche42 in java.library.path"));
        var log = new java.io.ByteArrayOutputStream();
        var err = System.err;
        System.setErr(new java.io.PrintStream(log, true, StandardCharsets.UTF_8));
        try {
            this.server = SjwsServer.start(config().build(), missing);
        } finally {
            System.setErr(err);
        }
        var warning = log.toString(StandardCharsets.UTF_8);
        assertTrue(warning.contains("WARN: HTTP/3 not started, serving HTTP/1.1 only (no Alt-Svc)"), warning);
        assertTrue(warning.contains("native QUIC library not available"), warning);
        assertTrue(warning.contains("no libnetty_quiche42"), warning);
        assertTrue(warning.contains("io.netty.native.workdir"), warning);
        assertTrue(this.server.h3Failure().getCause() instanceof UnsatisfiedLinkError);
        assertNull(HttpTestSupport.get(this.server.tcpPort(), "/hello.txt", 4000).headers().get("alt-svc"));
    }

    @Test
    void transportContract() throws Exception {
        var unknown = new Http3Transport(config().build(), new sjws_nettask.core.StaticFileHandler(this.root),
                () -> false, () -> null);
        assertEquals("HTTP/3", unknown.name());
        assertThrows(IllegalStateException.class, unknown::port);
        assertTrue(assertThrows(IOException.class, unknown::start).getMessage().contains("unknown cause"));
        unknown.close(); // safe before start
        assertNull(unknown.server());

        var real = new Http3Transport(config().build(), new sjws_nettask.core.StaticFileHandler(this.root));
        real.start();
        try {
            assertEquals(real.server().boundPort(), real.port());
            assertThrows(IllegalStateException.class, real::start);
        } finally {
            real.close();
            real.close();
        }
    }

    interface IoSupplier<T> {
        T get() throws IOException;
    }

    static <T> T quietly(IoSupplier<T> action) throws IOException {
        var err = System.err;
        System.setErr(new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));
        try {
            return action.get();
        } finally {
            System.setErr(err);
        }
    }

    // --- process level: the real main() with --h3 ------------------------------------------------

    @Test
    void mainWithH3ServesBothProtocols() throws Exception {
        int port;
        try (var probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        var cmd = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), "sjws_nettask.Sjws_nettask", String.valueOf(port),
                this.root.toString(), "--h3", "--h3-host=127.0.0.1", "--cert=" + this.cert, "--key=" + this.key,
                "--alt-svc", "--alt-svc-max-age=120"));
        var pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        pb.environment().keySet().removeIf(k -> k.startsWith("WEB_SERVER_"));
        var p = pb.start();
        try {
            var reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
            var log = new StringBuilder();
            String line;
            int udp = -1;
            while ((line = reader.readLine()) != null) {
                log.append(line).append('\n');
                var m = Pattern.compile("^HTTP/1\\.1 on tcp/(\\d+), HTTP/3 on udp/(\\d+)").matcher(line);
                if (m.find()) {
                    assertEquals(port, Integer.parseInt(m.group(1)));
                    udp = Integer.parseInt(m.group(2));
                    break;
                }
                if (line.startsWith("HTTP/1.1 on tcp/")) {
                    break;
                }
            }
            assertEquals(port, udp, "HTTP/3 defaults to the TCP port number:\n" + log);
            assertTrue(log.toString().contains("browsers ignore Alt-Svc from http:// origins"), log.toString());
            var h1 = HttpTestSupport.get(port, "/hello.txt", 4000);
            assertEquals("h3=\":" + udp + "\"; ma=120", h1.headers().get("alt-svc"));
            try (var client = new H3TestClient(this.tls.certificate())) {
                var quic = client.connect(new java.net.InetSocketAddress("127.0.0.1", udp));
                var h3 = H3TestClient.get(quic, "/hello.txt");
                assertEquals(200, h3.status());
                assertArrayEquals(h1.body(), h3.body());
                assertEquals(h1.headers().get("last-modified"), h3.header("last-modified"));
                assertEquals(h1.headers().get("content-type"), h3.header("content-type"));
                var large = H3TestClient.get(quic, "/large.bin");
                assertArrayEquals(Files.readAllBytes(this.root.resolve("large.bin")), large.body());
            }
        } finally {
            p.destroy(); // SIGTERM: shutdown hook closes both listeners
            assertTrue(p.waitFor(10, TimeUnit.SECONDS), "should stop on SIGTERM");
        }
        try (var udpAgain = new DatagramSocket(port, InetAddress.getByName("127.0.0.1"))) {
            assertEquals(port, udpAgain.getLocalPort(), "UDP port released");
        }
    }
}
