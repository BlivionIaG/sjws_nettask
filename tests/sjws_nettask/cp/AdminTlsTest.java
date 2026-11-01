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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.cluster.FakeClock;

/** The admin listener refuses to start without mTLS material, and maps client certs through Rbac. */
class AdminTlsTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final long TTL = 60_000L;

    @TempDir
    Path dir;

    @Test
    void missingMaterialRefusesToStart() throws Exception {
        var keys = KeyRing.generate("online-1", NOW + 86_400_000L, 0, Long.MAX_VALUE / 2);
        var clock = new FakeClock(0);
        try (var store = CpStore.open(dir.resolve("closed"), keys, clock)) {
            var ca = ca("CN=admin-ca");
            var server = issued(ca, "CN=localhost", null, true);
            var cert = pem(dir.resolve("cert.pem"), server.cert(), ca.cert());
            var key = pkcs8(dir.resolve("key.pem"), server.key());
            var trust = dir.resolve("ca.pem");
            assertTrue(assertThrows(IOException.class, () -> AdminServer.start(store, NOW, TTL, null, key, null, trust))
                    .getMessage().contains("trust bundle"));
            assertThrows(IOException.class, () -> AdminServer.start(store, NOW, TTL, cert, null, null, trust));
            assertThrows(IOException.class, () -> AdminServer.start(store, NOW, TTL, cert, key, null, null));
            assertTrue(assertThrows(IOException.class,
                    () -> AdminServer.start(store, NOW, TTL, cert, key, null, dir.resolve("absent.pem")))
                    .getMessage().contains("missing"));
            Files.writeString(trust, "");
            var empty = assertThrows(IOException.class, () -> AdminServer.start(store, NOW, TTL, cert, key, null, trust));
            assertTrue(empty.getMessage().contains("no certificates") || empty.getMessage().contains("rejected"),
                    empty.getMessage());
            Files.writeString(trust, "not a certificate\n");
            assertThrows(IOException.class, () -> AdminServer.start(store, NOW, TTL, cert, key, null, trust));
        }
    }

    @Test
    void mtlsMapsRolesAndIgnoresBearerTokens() throws Exception {
        var ca = ca("CN=admin-ca");
        var other = ca("CN=other-ca");
        var server = issued(ca, "CN=localhost", null, true);
        var admin = issued(ca, "CN=root", "urn:sjws:admin:root", false);
        var viewer = issued(ca, "CN=viewer", "urn:sjws:admin:viewer", false);
        var operator = issued(ca, "CN=operator", "urn:sjws:admin:operator", false);
        var stranger = issued(other, "CN=stranger", "urn:sjws:admin:root", false);
        var certPem = pem(dir.resolve("server.pem"), server.cert(), ca.cert());
        var keyPem = pkcs8(dir.resolve("server-key.pem"), server.key());
        var trustPem = pem(dir.resolve("admin-ca.pem"), ca.cert());
        var keys = KeyRing.generate("online-1", NOW + 86_400_000L, 0, Long.MAX_VALUE / 2);
        var clock = new FakeClock(0);
        try (var store = CpStore.open(dir.resolve("live"), keys, clock);
                var http = AdminServer.start(store, NOW, TTL, certPem, keyPem, null, trustPem)) {
            clock.advance(Duration.ofMillis(100));
            store.drive();
            assertTrue(store.leadership().leaderReady());
            int port = http.port();
            var root = client(admin, ca.cert());
            assertEquals(200, send(root, port, "POST", "/cp/v1/rbac",
                    "{\"san\":\"urn:sjws:admin:viewer\",\"role\":\"viewer\"}", "sjws_root.secret").statusCode());
            assertEquals(200, send(root, port, "POST", "/cp/v1/rbac",
                    "{\"san\":\"urn:sjws:admin:operator\",\"role\":\"operator\"}", null).statusCode());
            var yaml = "server:\n  port: 0\n  root: " + dir.toAbsolutePath() + "\n";
            assertEquals(200, send(root, port, "POST", "/cp/v1/config", yaml, "not-a-credential").statusCode());
            var view = client(viewer, ca.cert());
            assertEquals(200, send(view, port, "GET", "/cp/v1/health", null, "sjws_root.secret").statusCode());
            var blocked = send(view, port, "POST", "/cp/v1/config", yaml, "sjws_root.secret");
            assertEquals(403, blocked.statusCode(), blocked.body());
            var op = client(operator, ca.cert());
            assertEquals(200, send(op, port, "POST", "/cp/v1/rollout",
                    "{\"selectors\":[\"edge\"]}", "Bearer-is-not-a-role").statusCode());
            assertEquals(403, send(op, port, "POST", "/cp/v1/nodes/n1/revoke", "", null).statusCode());
            assertThrows(IOException.class, () -> send(client(null, ca.cert()), port, "GET", "/cp/v1/health", null, "tok"));
            assertThrows(IOException.class, () -> send(client(stranger, ca.cert()), port, "GET", "/cp/v1/health", null, null));
        }
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
        if ("POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method) || "DELETE".equals(method)) {
            request.header("Content-Type", "application/json");
        }
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
