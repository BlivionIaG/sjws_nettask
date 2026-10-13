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
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
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

/** Opt-in mTLS on the node-to-CP watch channel (cpTls): handshake, CA pinning, revocation, identity binding. */
class CpTlsWatchTest {

    private static final String SECRET = "0123456789abcdef";
    private static final String OK = Base64.getEncoder().encodeToString(SECRET.getBytes(StandardCharsets.UTF_8));

    @TempDir
    Path dir;

    private record Id(PrivateKey key, X509Certificate cert) {
    }

    private static Id ca(String dn) throws Exception {
        var pair = keys();
        var name = new X500Name(dn);
        var builder = new JcaX509v3CertificateBuilder(name, BigInteger.ONE, notBefore(), notAfter(), name, pair.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
        return new Id(pair.getPrivate(), new JcaX509CertificateConverter().getCertificate(
                builder.build(new JcaContentSignerBuilder("SHA256withECDSA").build(pair.getPrivate()))));
    }

    private static long serial = 10;

    private static Id issued(Id issuer, String dn, GeneralName... sans) throws Exception {
        var pair = keys();
        var builder = new JcaX509v3CertificateBuilder(issuer.cert(), BigInteger.valueOf(serial++), notBefore(),
                notAfter(), new javax.security.auth.x500.X500Principal(dn), pair.getPublic());
        if (sans.length > 0) {
            builder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(sans));
        }
        return new Id(pair.getPrivate(), new JcaX509CertificateConverter().getCertificate(
                builder.build(new JcaContentSignerBuilder("SHA256withECDSA").build(issuer.key()))));
    }

    private static GeneralName uri(String v) {
        return new GeneralName(GeneralName.uniformResourceIdentifier, v);
    }

    /** PEM files for one side. */
    private CpTls files(String name, Id id, Id issuer, Id pinned, Path revocations, String peer) throws Exception {
        var cert = pem(dir.resolve(name + ".pem"), id.cert(), issuer.cert());
        var key = pkcs8(dir.resolve(name + "-key.pem"), id.key());
        var ca = pem(dir.resolve(name + "-ca.pem"), pinned.cert());
        return new CpTls(cert, key, ca, revocations, peer, 0);
    }

    private static CpStore elected(Path at) throws Exception {
        var clock = new FakeClock(0);
        var store = CpStore.open(at, KeyRing.generate("online-1", System.currentTimeMillis() + 86_400_000L, 0,
                Long.MAX_VALUE / 2), clock);
        clock.advance(Duration.ofMillis(100));
        store.drive();
        assertTrue(store.leadership().leaderReady());
        return store;
    }

    private static HttpResponse<String> poll(SSLContext tls, String scheme, int port, String node, boolean token)
            throws Exception {
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3));
        if (tls != null) {
            client.sslContext(tls);
        }
        var b = HttpRequest.newBuilder(URI.create(scheme + "://127.0.0.1:" + port + "/cp/v1/watch?waitMs=0"))
                .timeout(Duration.ofSeconds(5)).header(WatchServer.NODE, node);
        if (token) {
            b.header(WatchServer.TOKEN_ID, "t").header(WatchServer.SECRET, OK);
        }
        return client.build().send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void mutualTlsCertificateIsTheOnlyCredential() throws Exception {
        var ca = ca("CN=cp-ca");
        var other = ca("CN=rogue-ca");
        var server = issued(ca, "CN=cp", new GeneralName(GeneralName.dNSName, "localhost"),
                new GeneralName(GeneralName.iPAddress, "127.0.0.1"), uri("urn:sjws:cp"));
        var node = issued(ca, "CN=gw-1", uri("urn:sjws:node:gw-1"));
        var rogue = issued(other, "CN=gw-1", uri("urn:sjws:node:gw-1"));
        var revoked = issued(ca, "CN=gw-3", uri("urn:sjws:node:gw-3"));
        var deny = Files.writeString(dir.resolve("revoked.txt"), "sha256:" + AdminTls.fingerprint(revoked.cert()) + "\n");
        var cp = files("cp", server, ca, ca, deny, null);
        try (var store = elected(dir.resolve("cp-state"));
                var watch = WatchServer.start(store, new InetSocketAddress("127.0.0.1", 0), System::currentTimeMillis, cp)) {
            int port = watch.port();
            var gw1 = files("gw1", node, ca, ca, null, "urn:sjws:cp").client();

            assertEquals(204, poll(gw1, "https", port, "gw-1", false).statusCode(), "certificate is the credential");
            assertEquals(204, poll(gw1, "https", port, "gw-1", true).statusCode(), "token headers are ignored");
            assertEquals(401, poll(gw1, "https", port, "gw-2", false).statusCode());
            var mismatch = poll(gw1, "https", port, "gw-2", true);
            assertEquals(401, mismatch.statusCode());
            assertTrue(mismatch.body().contains("identity"), mismatch.body());
            assertTrue(store.auditJson(0, 100).contains("cp-tls.identity"));

            assertThrows(IOException.class, () -> poll(files("rogue", rogue, other, ca, null, null).client(), "https",
                    port, "gw-1", false), "client cert from another CA");
            assertThrows(IOException.class, () -> poll(files("gw3", revoked, ca, ca, null, null).client(), "https",
                    port, "gw-3", false), "revoked client cert");
            assertTrue(store.auditJson(0, 100).contains("cp-tls.handshake"), store.auditJson(0, 100));
            assertThrows(IOException.class, () -> poll(files("pinned-wrong", node, ca, other, null, null).client(),
                    "https", port, "gw-1", false), "server not under the pinned CA");
            assertThrows(IOException.class, () -> poll(null, "http", port, "gw-1", true), "plaintext client");

            assertEquals(200, store.revoke("gw-1").status());
            assertEquals(403, poll(gw1, "https", port, "gw-1", false).statusCode());
        }
    }

    @Test
    void dataWatchChecksTheControlPlaneIdentity() throws Exception {
        var ca = ca("CN=cp-ca");
        var server = issued(ca, "CN=cp", new GeneralName(GeneralName.iPAddress, "127.0.0.1"), uri("urn:sjws:cp"));
        var node = issued(ca, "CN=gw-1", uri("urn:sjws:node:gw-1"));
        var cp = files("cp", server, ca, ca, null, null);
        try (var store = elected(dir.resolve("cp-state"));
                var watch = WatchServer.start(store, new InetSocketAddress("127.0.0.1", 0), System::currentTimeMillis, cp)) {
            var target = DataWatch.endpoint("127.0.0.1:" + watch.port(), true).resolve("/cp/v1/watch?waitMs=0");
            var good = files("gw1", node, ca, ca, null, "urn:sjws:cp");
            var conn = DataWatch.openSocket(target, good.client());
            conn.setRequestProperty(WatchServer.NODE, "gw-1");
            assertEquals(204, conn.getResponseCode(), "handshake ok, identity bound");
            assertEquals("TLSv1.3", ((javax.net.ssl.HttpsURLConnection) conn).getSSLSession().orElseThrow().getProtocol());
            var wrongPeer = files("gw1b", node, ca, ca, null, "urn:sjws:other-cp");
            var refused = DataWatch.openSocket(target, wrongPeer.client());
            assertThrows(IOException.class, refused::getResponseCode, "CP certificate must carry cp-tls-peer");
        }
    }

    @Test
    void offByDefaultStaysLoopbackPlainAndStoresOnlyAHash() throws Exception {
        assertThrows(IOException.class, () -> DataWatch.endpoint("10.0.0.1:9000"));
        assertEquals("https://10.0.0.1:9000", DataWatch.endpoint("10.0.0.1:9000", true).toString());
        try (var store = elected(dir.resolve("cp-state"))) {
            assertThrows(IOException.class, () -> WatchServer.start(store, new InetSocketAddress("0.0.0.0", 0),
                    System::currentTimeMillis, null));
            try (var watch = WatchServer.start(store, 0, System::currentTimeMillis)) {
                assertEquals(200, store.issueJoinToken("t", SECRET.getBytes(StandardCharsets.UTF_8),
                        System.currentTimeMillis(), JoinTokens.DEFAULT_TTL_MILLIS).status());
                assertEquals(204, poll(null, "http", watch.port(), "gw-1", true).statusCode());
                assertEquals(401, poll(null, "http", watch.port(), "gw-1", false).statusCode(),
                        "without cpTls the token stays the credential");
            }
        }
        try (var files = Files.walk(dir.resolve("cp-state"))) {
            for (var f : files.filter(Files::isRegularFile).toList()) {
                var raw = new String(Files.readAllBytes(f), StandardCharsets.ISO_8859_1);
                assertFalse(raw.contains(SECRET) || raw.contains(OK), "plaintext credential in " + f);
            }
        }
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

}
