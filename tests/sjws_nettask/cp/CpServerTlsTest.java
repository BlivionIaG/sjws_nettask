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

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** CP listener TLS: 1.3 only, client auth required, key perms checked, reload keeps old material on failure. */
class CpServerTlsTest {

    @TempDir
    Path dir;

    @Test
    void tls13ClientAuthAndReload() throws Exception {
        var cfg = material(false);
        var tls = CpTls.serverContext(cfg);
        var params = tls.parameters();
        assertArrayEquals(new String[] {"TLSv1.3"}, params.getProtocols());
        assertTrue(params.getNeedClientAuth());
        var ctx = tls.context();
        assertEquals(1, tls.generation());
        tls.reloadTls();
        assertEquals(2, tls.generation());

        // Broken cert: reload refused, old material and context stay.
        var good = Files.readAllBytes(cfg.certPem());
        Files.writeString(cfg.certPem(), "not a certificate");
        var e = assertThrows(CpKeyException.class, tls::reloadTls);
        assertTrue(e.getMessage().contains("keeping previous"), e.getMessage());
        assertEquals(2, tls.generation());
        assertSame(ctx, tls.context());
        Files.write(cfg.certPem(), good);

        // Key loosened after start: reload refused before any parsing.
        Files.setPosixFilePermissions(cfg.keyPem(), PosixFilePermissions.fromString("rw-r--r--"));
        assertThrows(CpKeyException.class, tls::reloadTls);
        assertEquals(2, tls.generation());
        assertThrows(CpKeyException.class, () -> CpTls.serverContext(cfg));
        var insecure = new CpListenerConfig(cfg.name(), cfg.certPem(), cfg.keyPem(), cfg.caPem(), null, true);
        assertEquals(1, CpTls.serverContext(insecure).generation());
    }

    @Test
    void badMaterialRefusesToStart() throws Exception {
        var cfg = material(false);
        var noCa = new CpListenerConfig("cp-admin", cfg.certPem(), cfg.keyPem(), dir.resolve("absent.pem"), null, false);
        assertThrows(CpKeyException.class, () -> CpTls.serverContext(noCa));
        assertThrows(IllegalArgumentException.class,
                () -> new CpListenerConfig(" ", cfg.certPem(), cfg.keyPem(), cfg.caPem(), null, false));
        assertThrows(IllegalArgumentException.class,
                () -> new CpListenerConfig("x", null, cfg.keyPem(), cfg.caPem(), null, false));
        assertThrows(IllegalArgumentException.class,
                () -> new CpKeyConfig(null, dir, dir, null, false, 0));
        assertThrows(IllegalArgumentException.class, () -> new CpKeyConfig(dir, dir, dir, null, false, -1));
        assertNull(new CpKeyConfig(dir, dir, dir, " ", false, 0).rootPubSha256());
    }

    /** CONTROL hosting: both listeners use CpServerTls.context() and parameters() (client cert required). */
    @Test
    void watchAndAdminListenersRequireClientCertificates() throws Exception {
        var tls = CpTls.serverContext(material(false));
        var keys = KeyRing.generate("online-1", Long.MAX_VALUE, 0, Long.MAX_VALUE);
        try (var store = CpStore.open(dir.resolve("state"), keys, sjws_nettask.cluster.Clock.SYSTEM);
                var watch = WatchServer.startMtls(store, new java.net.InetSocketAddress("127.0.0.1", 0),
                        System::currentTimeMillis, tls);
                var admin = AdminServer.startMtls(store, new java.net.InetSocketAddress("127.0.0.1", 0),
                        System.currentTimeMillis(), 60_000L, tls)) {
            for (int port : new int[] {watch.port(), admin.port()}) {
                assertThrows(java.io.IOException.class, () -> handshakeWithoutClientCert(port), "port " + port);
            }
        }
    }

    /** A CONTROL host on Security's listener TLS: mTLS listeners, and SIGHUP reloads each listener's material. */
    @Test
    void controlHostServesMtlsAndReloadsListenerTls() throws Exception {
        var tls = CpTls.serverContext(material(false));
        var deps = new CpTestDeps() {
            @Override
            public CpServerTls serverTls(String listener) {
                return tls;
            }
        };
        var y = new java.util.HashMap<String, Object>(java.util.Map.of("nodeId", "cp-1", "stateDir",
                dir.resolve("s").toString(), "keyDir", dir.resolve("k").toString(), "rootPub",
                dir.resolve("r.pub").toString(), "watch", java.util.Map.of("listen", "127.0.0.1:0"), "admin",
                java.util.Map.of("listen", "127.0.0.1:0")));
        var config = CpConfig.parse(k -> null, java.util.Map.of(), y, null);
        try (var host = (CpHost) CpLaunch.start(config, deps, new java.io.PrintStream(java.io.OutputStream.nullOutputStream()))) {
            long before = tls.generation();
            assertNull(host.reload());
            assertEquals(before + 2, tls.generation(), "watch and admin listener TLS both reloaded");
            assertEquals(java.util.List.of("keys", "store", "watch", "admin", "ready", "reloaded"), host.events());
        }
    }

    private static void handshakeWithoutClientCert(int port) throws Exception {
        var trustAll = new javax.net.ssl.X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] c, String a) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] c, String a) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        var ctx = javax.net.ssl.SSLContext.getInstance("TLSv1.3");
        ctx.init(null, new javax.net.ssl.TrustManager[] {trustAll}, null);
        try (var socket = (javax.net.ssl.SSLSocket) ctx.getSocketFactory().createSocket("127.0.0.1", port)) {
            socket.setSoTimeout(5_000);
            socket.startHandshake();
            var out = socket.getOutputStream();
            out.write("GET /cp/v1/health HTTP/1.1\r\nHost: x\r\n\r\n".getBytes());
            out.flush();
            if (socket.getInputStream().read() < 0) {
                throw new java.io.EOFException("closed without a client certificate");
            }
        }
    }

    private CpListenerConfig material(boolean insecure) throws Exception {
        var caKeys = ec();
        var caName = new X500Name("CN=cp-ca");
        var caB = new JcaX509v3CertificateBuilder(caName, BigInteger.ONE, new Date(System.currentTimeMillis() - 3_600_000L),
                new Date(System.currentTimeMillis() + 86_400_000L), caName, caKeys.getPublic());
        caB.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
        var ca = cert(caB, caKeys);
        var leafKeys = ec();
        var leafB = new JcaX509v3CertificateBuilder(caName, BigInteger.TWO, new Date(System.currentTimeMillis() - 3_600_000L),
                new Date(System.currentTimeMillis() + 86_400_000L), new X500Name("CN=cp-1"), leafKeys.getPublic());
        var leaf = cert(leafB, caKeys);
        var certPem = pem(dir.resolve("cert.pem"), leaf, ca);
        var caPem = pem(dir.resolve("ca.pem"), ca);
        var keyPem = dir.resolve("key.pem");
        Files.writeString(keyPem, "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(leafKeys.getPrivate().getEncoded()) + "\n-----END PRIVATE KEY-----\n");
        Files.setPosixFilePermissions(keyPem, PosixFilePermissions.fromString("rw-------"));
        return new CpListenerConfig("cp-admin", certPem, keyPem, caPem, null, insecure);
    }

    private static X509Certificate cert(JcaX509v3CertificateBuilder b, KeyPair signer) throws Exception {
        return new JcaX509CertificateConverter().getCertificate(
                b.build(new JcaContentSignerBuilder("SHA256withECDSA").build(signer.getPrivate())));
    }

    private static KeyPair ec() throws Exception {
        var gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp256r1"));
        return gen.generateKeyPair();
    }

    private static Path pem(Path file, Object... objects) throws Exception {
        try (var w = new JcaPEMWriter(Files.newBufferedWriter(file))) {
            for (var o : objects) {
                w.writeObject(o);
            }
        }
        return file;
    }
}
