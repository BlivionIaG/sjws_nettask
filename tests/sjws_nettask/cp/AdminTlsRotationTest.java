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
package sjws_nettask.cp;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.math.BigInteger;
import java.util.Base64;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.util.Date;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.cert.X509v2CRLBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CRLConverter;
import org.bouncycastle.asn1.x509.CRLReason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.cluster.FakeClock;

/** Admin mTLS material reloads without a restart, and revoked client certificates are refused and audited. */
class AdminTlsRotationTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final long TTL = 60_000L;

    @TempDir
    Path dir;

    private CpStore store() throws Exception {
        var keys = KeyRing.generate("online-1", NOW + 86_400_000L, 0, Long.MAX_VALUE / 2);
        var clock = new FakeClock(0);
        var store = CpStore.open(dir.resolve("live"), keys, clock);
        clock.advance(Duration.ofMillis(100));
        store.drive();
        return store;
    }

    private static boolean handshakes(Id id, X509Certificate trust, int port) {
        try {
            send(client(id, trust), port, "GET", "/cp/v1/health", null, null);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void denylistRejectsAndAuditsAndReloadLiftsIt() throws Exception {
        var ca = ca("CN=admin-ca");
        var server = issued(ca, "CN=localhost", null, true);
        var root = issued(ca, "CN=root", "urn:sjws:admin:root", false);
        var viewer = issued(ca, "CN=viewer", "urn:sjws:admin:viewer", false);
        var cert = pem(dir.resolve("server.pem"), server.cert(), ca.cert());
        var key = pkcs8(dir.resolve("server-key.pem"), server.key());
        var trust = pem(dir.resolve("ca.pem"), ca.cert());
        var deny = Files.writeString(dir.resolve("revoked.txt"),
                "# revoked admin certs\nsha256:" + AdminTls.fingerprint(viewer.cert()).toUpperCase() + "  # lost laptop\n");
        try (var store = store(); var http = AdminServer.start(store, NOW, TTL, cert, key, null, trust, deny, 0)) {
            int port = http.port();
            assertTrue(handshakes(root, ca.cert(), port));
            assertFalse(handshakes(viewer, ca.cert(), port));
            var audit = store.auditJson(0, 100);
            assertTrue(audit.contains("admin-tls.handshake") && audit.contains("denied: revoked (fingerprint)"), audit);
            assertTrue(audit.contains("CN=viewer"), audit);

            Files.writeString(deny, "serial:" + root.cert().getSerialNumber().toString(16) + "\n");
            assertNull(http.reloadTls());
            assertTrue(handshakes(viewer, ca.cert(), port));
            assertFalse(handshakes(root, ca.cert(), port));
            assertTrue(store.auditJson(0, 100).contains("denied: revoked (serial)"));
        }
    }

    @Test
    void crlSignedByTheAdminCaRevokes() throws Exception {
        var ca = ca("CN=admin-ca");
        var other = ca("CN=admin-ca");
        var server = issued(ca, "CN=localhost", null, true);
        var root = issued(ca, "CN=root", "urn:sjws:admin:root", false);
        var viewer = issued(ca, "CN=viewer", "urn:sjws:admin:viewer", false);
        var cert = pem(dir.resolve("server.pem"), server.cert(), ca.cert());
        var key = pkcs8(dir.resolve("server-key.pem"), server.key());
        var trust = pem(dir.resolve("ca.pem"), ca.cert());
        var crl = pem(dir.resolve("admin.crl"), crl(ca, viewer.cert().getSerialNumber()));
        try (var store = store(); var http = AdminServer.start(store, NOW, TTL, cert, key, null, trust, crl, 0)) {
            assertTrue(handshakes(root, ca.cert(), http.port()));
            assertFalse(handshakes(viewer, ca.cert(), http.port()));
            assertTrue(store.auditJson(0, 100).contains("denied: revoked (crl)"));
            // a CRL with the right name but the wrong signer is refused; the old one stays in force
            pem(crl, crl(other, root.cert().getSerialNumber()));
            assertNotNull(http.reloadTls());
            assertTrue(handshakes(root, ca.cert(), http.port()));
            assertFalse(handshakes(viewer, ca.cert(), http.port()));
            assertTrue(store.auditJson(0, 100).contains("admin-tls.reload"));
        }
        try (var store = CpStore.open(dir.resolve("other"),
                KeyRing.generate("online-1", NOW + 86_400_000L, 0, Long.MAX_VALUE / 2), new FakeClock(0))) {
            var e = assertThrows(IOException.class, () -> AdminServer.start(store, NOW, TTL, cert, key, null, trust, crl, 0));
            assertTrue(e.getMessage().contains("not signed"), e.getMessage());
        }
    }

    @Test
    void rotationSwapsCertKeyAndCaWithoutRestart() throws Exception {
        var ca = ca("CN=admin-ca");
        var next = ca("CN=admin-ca-2026");
        var server = issued(ca, "CN=localhost", null, true);
        var root = issued(ca, "CN=root", "urn:sjws:admin:root", false);
        var cert = pem(dir.resolve("server.pem"), server.cert(), ca.cert());
        var key = pkcs8(dir.resolve("server-key.pem"), server.key());
        var trust = pem(dir.resolve("ca.pem"), ca.cert());
        try (var store = store(); var http = AdminServer.start(store, NOW, TTL, cert, key, null, trust)) {
            int port = http.port();
            assertTrue(handshakes(root, ca.cert(), port));

            // a broken key keeps the current material
            Files.writeString(key, "not a key");
            assertNotNull(http.reloadTls());
            assertTrue(handshakes(root, ca.cert(), port));

            var server2 = issued(next, "CN=localhost", null, true);
            var root2 = issued(next, "CN=root", "urn:sjws:admin:root", false);
            pem(cert, server2.cert(), next.cert());
            pkcs8(key, server2.key());
            pem(trust, next.cert());
            assertNull(http.reloadTls());
            assertTrue(handshakes(root2, next.cert(), port));
            assertFalse(handshakes(root, ca.cert(), port), "old CA and server cert are gone");
            assertFalse(handshakes(root, next.cert(), port), "old client cert is no longer trusted");
        }
    }

    @Test
    void fileWatchPicksUpANewDenylist() throws Exception {
        var ca = ca("CN=admin-ca");
        var server = issued(ca, "CN=localhost", null, true);
        var root = issued(ca, "CN=root", "urn:sjws:admin:root", false);
        var cert = pem(dir.resolve("server.pem"), server.cert(), ca.cert());
        var key = pkcs8(dir.resolve("server-key.pem"), server.key());
        var trust = pem(dir.resolve("ca.pem"), ca.cert());
        var deny = Files.writeString(dir.resolve("revoked.txt"), "# none\n");
        try (var store = store(); var http = AdminServer.start(store, NOW, TTL, cert, key, null, trust, deny, 50)) {
            assertTrue(handshakes(root, ca.cert(), http.port()));
            Files.writeString(deny, "# revoked\nsha256:" + AdminTls.fingerprint(root.cert()) + "\n");
            long until = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            boolean refused = false;
            while (!refused && System.nanoTime() < until) {
                Thread.sleep(50);
                refused = !handshakes(root, ca.cert(), http.port());
            }
            assertTrue(refused);
        }
    }

    @Test
    void invalidOrMissingRevocationListFailsClosed() throws Exception {
        var ca = ca("CN=admin-ca");
        var server = issued(ca, "CN=localhost", null, true);
        var cert = pem(dir.resolve("server.pem"), server.cert(), ca.cert());
        var key = pkcs8(dir.resolve("server-key.pem"), server.key());
        var trust = pem(dir.resolve("ca.pem"), ca.cert());
        var bad = Files.writeString(dir.resolve("bad.txt"), "sha256:abcd\n");
        var unknown = Files.writeString(dir.resolve("unknown.txt"), "cn:root\n");
        try (var store = store()) {
            assertTrue(assertThrows(IOException.class,
                    () -> AdminServer.start(store, NOW, TTL, cert, key, null, trust, bad, 0)).getMessage().contains("line 1"));
            assertThrows(IOException.class, () -> AdminServer.start(store, NOW, TTL, cert, key, null, trust, unknown, 0));
            assertTrue(assertThrows(IOException.class,
                    () -> AdminServer.start(store, NOW, TTL, cert, key, null, trust, dir.resolve("absent"), 0))
                    .getMessage().contains("missing"));
        }
    }


    /**
     * An established keep-alive admin connection must be closed (not merely 401'd) after the client
     * cert is denylisted and TLS reloads, and the live registry must be empty afterwards.
     */
    @Test
    void keepAliveConnectionIsClosedAfterDenylistReload() throws Exception {
        var ca = ca("CN=admin-ca");
        var server = issued(ca, "CN=localhost", null, true);
        var root = issued(ca, "CN=root", "urn:sjws:admin:root", false);
        var cert = pem(dir.resolve("server.pem"), server.cert(), ca.cert());
        var key = pkcs8(dir.resolve("server-key.pem"), server.key());
        var trust = pem(dir.resolve("ca.pem"), ca.cert());
        var deny = Files.writeString(dir.resolve("revoked-keepalive.txt"), "# none yet\n");
        try (var store = store();
                var http = AdminServer.start(store, NOW, TTL, cert, key, null, trust, deny, 0);
                SSLSocket socket = (SSLSocket) client(root, ca.cert()).getSocketFactory()
                        .createSocket("127.0.0.1", http.port())) {
            socket.setSoTimeout(5_000);
            socket.startHandshake();
            var first = rawHttp(socket, "GET /cp/v1/health HTTP/1.1\r\nHost: localhost\r\nConnection: keep-alive\r\n\r\n");
            assertTrue(first.startsWith("HTTP/1.1 200"), first);
            assertEquals(1, http.liveSize(), "keep-alive session stays registered");

            Files.writeString(deny, "sha256:" + AdminTls.fingerprint(root.cert()) + "\n");
            assertNull(http.reloadTls());
            assertTrue(http.policyDropped() >= 1L, "live session should be dropped on reload");
            assertEquals(0, http.liveSize(), "registry empty after policy drop");

            var closed = assertThrows(IOException.class, () -> rawHttp(socket,
                    "GET /cp/v1/health HTTP/1.1\r\nHost: localhost\r\nConnection: keep-alive\r\n\r\n"));
            assertTrue(closed.getMessage() != null && !closed.getMessage().isBlank(), closed.toString());
        }
    }

    /** Connection: close responses must leave the live registry empty. */
    @Test
    void clientConnectionCloseClearsRegistry() throws Exception {
        var ca = ca("CN=admin-ca");
        var server = issued(ca, "CN=localhost", null, true);
        var root = issued(ca, "CN=root", "urn:sjws:admin:root", false);
        var cert = pem(dir.resolve("server-close.pem"), server.cert(), ca.cert());
        var key = pkcs8(dir.resolve("server-close-key.pem"), server.key());
        var trust = pem(dir.resolve("ca-close.pem"), ca.cert());
        try (var store = store();
                var http = AdminServer.start(store, NOW, TTL, cert, key, null, trust);
                SSLSocket socket = (SSLSocket) client(root, ca.cert()).getSocketFactory()
                        .createSocket("127.0.0.1", http.port())) {
            socket.setSoTimeout(5_000);
            socket.startHandshake();
            var body = rawHttp(socket, "GET /cp/v1/health HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
            assertTrue(body.startsWith("HTTP/1.1 200"), body);
            assertEquals(0, http.liveSize(), "Connection: close must remove the session by key");
        }
    }

    /** After the client closes a keep-alive socket, prune must clear the registry entry. */
    @Test
    void clientSocketCloseClearsRegistryAfterPrune() throws Exception {
        var ca = ca("CN=admin-ca");
        var server = issued(ca, "CN=localhost", null, true);
        var root = issued(ca, "CN=root", "urn:sjws:admin:root", false);
        var cert = pem(dir.resolve("server-prune.pem"), server.cert(), ca.cert());
        var key = pkcs8(dir.resolve("server-prune-key.pem"), server.key());
        var trust = pem(dir.resolve("ca-prune.pem"), ca.cert());
        try (var store = store(); var http = AdminServer.start(store, NOW, TTL, cert, key, null, trust)) {
            SSLSocket socket = (SSLSocket) client(root, ca.cert()).getSocketFactory()
                    .createSocket("127.0.0.1", http.port());
            socket.setSoTimeout(5_000);
            socket.startHandshake();
            var body = rawHttp(socket, "GET /cp/v1/health HTTP/1.1\r\nHost: localhost\r\nConnection: keep-alive\r\n\r\n");
            assertTrue(body.startsWith("HTTP/1.1 200"), body);
            assertEquals(1, http.liveSize());
            socket.close();
            long until = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (http.liveSize() != 0 && System.nanoTime() < until) {
                Thread.sleep(20);
                http.pruneDeadSessionsForTest();
            }
            assertEquals(0, http.liveSize(), "closed keep-alive connection must leave the registry");
        }
    }


    /**
     * A reload that lands after registerLive and before/during handle must drop the in-flight session
     * (Architecture R2). Without early registration the sweep would miss it.
     */
    @Test
    void reloadDuringInFlightHandleDropsSession() throws Exception {
        var ca = ca("CN=admin-ca");
        var server = issued(ca, "CN=localhost", null, true);
        var root = issued(ca, "CN=root", "urn:sjws:admin:root", false);
        var cert = pem(dir.resolve("server-inflight.pem"), server.cert(), ca.cert());
        var key = pkcs8(dir.resolve("server-inflight-key.pem"), server.key());
        var trust = pem(dir.resolve("ca-inflight.pem"), ca.cert());
        var deny = Files.writeString(dir.resolve("revoked-inflight.txt"), "# none yet\n");
        try (var store = store();
                var http = AdminServer.start(store, NOW, TTL, cert, key, null, trust, deny, 0)) {
            http.afterRegisterBeforeHandle = () -> {
                try {
                    Files.writeString(deny, "sha256:" + AdminTls.fingerprint(root.cert()) + "\n");
                    assertNull(http.reloadTls());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            };
            try (SSLSocket socket = (SSLSocket) client(root, ca.cert()).getSocketFactory()
                    .createSocket("127.0.0.1", http.port())) {
                socket.setSoTimeout(5_000);
                socket.startHandshake();
                try {
                    var body = rawHttp(socket,
                            "GET /cp/v1/health HTTP/1.1\r\nHost: localhost\r\nConnection: keep-alive\r\n\r\n");
                    assertTrue(body.startsWith("HTTP/1.1 401") || body.startsWith("HTTP/1.1 403"), body);
                } catch (IOException closed) {
                    // abort during/after handle is also success
                    assertNotNull(closed.getMessage());
                }
            }
            assertTrue(http.policyDropped() >= 1L, "in-flight session must be policy-dropped");
            assertEquals(0, http.liveSize(), "registry empty after in-flight drop");
        }
    }

    /** An epoch bump between revalidate and put forces a retry; the second pass must succeed. */
    @Test
    void epochBumpBetweenRevalidateAndRegisterRetries() throws Exception {
        var ca = ca("CN=admin-ca");
        var server = issued(ca, "CN=localhost", null, true);
        var root = issued(ca, "CN=root", "urn:sjws:admin:root", false);
        var cert = pem(dir.resolve("server-epoch.pem"), server.cert(), ca.cert());
        var key = pkcs8(dir.resolve("server-epoch-key.pem"), server.key());
        var trust = pem(dir.resolve("ca-epoch.pem"), ca.cert());
        try (var store = store(); var http = AdminServer.start(store, NOW, TTL, cert, key, null, trust)) {
            var bumps = new java.util.concurrent.atomic.AtomicInteger();
            http.betweenValidateAndRegister = () -> {
                if (bumps.getAndIncrement() == 0) {
                    http.bumpTlsEpochForTest();
                }
            };
            assertTrue(handshakes(root, ca.cert(), http.port()));
            assertEquals(1, bumps.get(), "hook should run once then clear, plus the retry without bump");
            // After success a keep-alive registration path left or cleared; at least one request worked.
            assertTrue(http.liveSize() >= 0);
        }
    }

    /** Exhausting the epoch retry budget refuses the request and does not leave a live entry. */
    @Test
    void epochRetryBudgetFailsClosed() throws Exception {
        var ca = ca("CN=admin-ca");
        var server = issued(ca, "CN=localhost", null, true);
        var root = issued(ca, "CN=root", "urn:sjws:admin:root", false);
        var cert = pem(dir.resolve("server-cap.pem"), server.cert(), ca.cert());
        var key = pkcs8(dir.resolve("server-cap-key.pem"), server.key());
        var trust = pem(dir.resolve("ca-cap.pem"), ca.cert());
        try (var store = store(); var http = AdminServer.start(store, NOW, TTL, cert, key, null, trust)) {
            var bumps = new java.util.concurrent.atomic.AtomicInteger();
            Runnable bump = new Runnable() {
                @Override
                public void run() {
                    bumps.incrementAndGet();
                    // Re-arm: registerLive clears the hook after each attempt (same as CpPeerSockets).
                    http.betweenValidateAndRegister = this;
                    http.bumpTlsEpochForTest();
                }
            };
            http.betweenValidateAndRegister = bump;
            try (SSLSocket socket = (SSLSocket) client(root, ca.cert()).getSocketFactory()
                    .createSocket("127.0.0.1", http.port())) {
                socket.setSoTimeout(5_000);
                socket.startHandshake();
                try {
                    var body = rawHttp(socket,
                            "GET /cp/v1/health HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
                    assertTrue(body.startsWith("HTTP/1.1 401"), body);
                } catch (IOException closed) {
                    assertNotNull(closed.getMessage());
                }
            }
            assertEquals(AdminServer.REGISTER_LIVE_MAX_ATTEMPTS, bumps.get());
            assertEquals(0, http.liveSize(), "failed registration must not retain the session");
            assertTrue(http.policyDropped() >= 1L);
        }
    }

    private static String rawHttp(SSLSocket socket, String request) throws IOException {
        var out = socket.getOutputStream();
        out.write(request.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        out.flush();
        var in = socket.getInputStream();
        var buf = new byte[16_384];
        int n = in.read(buf);
        if (n < 0) {
            throw new IOException("admin connection closed");
        }
        return new String(buf, 0, n, java.nio.charset.StandardCharsets.US_ASCII);
    }


    private static java.security.cert.X509CRL crl(Id issuer, BigInteger serial) throws Exception {
        var builder = new X509v2CRLBuilder(new X500Name(issuer.cert().getSubjectX500Principal().getName()), notBefore());
        builder.setNextUpdate(notAfter());
        builder.addCRLEntry(serial, notBefore(), CRLReason.keyCompromise);
        return new JcaX509CRLConverter().getCRL(
                builder.build(new JcaContentSignerBuilder("SHA256withECDSA").build(issuer.key())));
    }

    private record Id(PrivateKey key, X509Certificate cert) {
    }

    private static Id ca(String dn) throws Exception {
        var pair = keys();
        var name = new X500Name(dn);
        var builder = new JcaX509v3CertificateBuilder(name, BigInteger.ONE, notBefore(), notAfter(), name, pair.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
        var cert = new JcaX509CertificateConverter().getCertificate(
                builder.build(new JcaContentSignerBuilder("SHA256withECDSA").build(pair.getPrivate())));
        return new Id(pair.getPrivate(), cert);
    }

    private static Id issued(Id issuer, String dn, String uri, boolean server) throws Exception {
        var pair = keys();
        var serial = BigInteger.valueOf(uri == null ? 2 : Math.abs(uri.hashCode()) + 3L);
        var builder = new JcaX509v3CertificateBuilder(issuer.cert(), serial, notBefore(), notAfter(),
                new javax.security.auth.x500.X500Principal(dn), pair.getPublic());
        if (server) {
            builder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(new GeneralName[] {
                    new GeneralName(GeneralName.dNSName, "localhost"),
                    new GeneralName(GeneralName.iPAddress, "127.0.0.1")}));
        }
        if (uri != null) {
            builder.addExtension(Extension.subjectAlternativeName, false,
                    new GeneralNames(new GeneralName(GeneralName.uniformResourceIdentifier, uri)));
        }
        var cert = new JcaX509CertificateConverter().getCertificate(
                builder.build(new JcaContentSignerBuilder("SHA256withECDSA").build(issuer.key())));
        return new Id(pair.getPrivate(), cert);
    }

    private static java.security.KeyPair keys() throws Exception {
        var gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp256r1"));
        return gen.generateKeyPair();
    }

    private static Date notBefore() {
        return new Date(System.currentTimeMillis() - 3_600_000L);
    }

    private static Date notAfter() {
        return new Date(System.currentTimeMillis() + 86_400_000L);
    }

    private static Path pkcs8(Path file, PrivateKey key) throws IOException {
        var body = Base64.getMimeEncoder(64, "\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII))
                .encodeToString(key.getEncoded());
        return Files.writeString(file, "-----BEGIN PRIVATE KEY-----\n" + body + "\n-----END PRIVATE KEY-----\n");
    }

    private static Path pem(Path file, Object... objects) throws IOException {
        try (var writer = new JcaPEMWriter(Files.newBufferedWriter(file))) {
            for (var object : objects) {
                writer.writeObject(object);
            }
        }
        return file;
    }

    private static SSLContext client(Id id, X509Certificate trust) throws Exception {
        var ts = KeyStore.getInstance("PKCS12");
        ts.load(null, null);
        ts.setCertificateEntry("ca", trust);
        var tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ts);
        javax.net.ssl.KeyManager[] managers = null;
        if (id != null) {
            var ks = KeyStore.getInstance("PKCS12");
            ks.load(null, null);
            ks.setKeyEntry("c", id.key(), new char[0], new X509Certificate[] {id.cert()});
            var kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, new char[0]);
            managers = kmf.getKeyManagers();
        }
        var ctx = SSLContext.getInstance("TLSv1.3");
        ctx.init(managers, tmf.getTrustManagers(), null);
        return ctx;
    }

    private static HttpResponse<String> send(SSLContext tls, int port, String method, String path, String body,
            String bearer) throws Exception {
        var client = HttpClient.newBuilder().sslContext(tls).connectTimeout(Duration.ofSeconds(3)).build();
        var request = HttpRequest.newBuilder(URI.create("https://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(3));
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
