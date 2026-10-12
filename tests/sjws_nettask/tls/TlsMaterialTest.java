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
package sjws_nettask.tls;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.AlgorithmParameters;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Date;
import javax.crypto.Cipher;
import javax.crypto.EncryptedPrivateKeyInfo;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.PBEParameterSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.ServerConfig;

class TlsMaterialTest {

    @TempDir
    Path tmp;

    static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der)
                + "\n-----END " + type + "-----\n";
    }

    /** Writes cert.pem (leaf + optional extra certs) and key.pem (unencrypted PKCS#8). */
    static Path[] writePem(Path dir, TlsMaterial m) throws Exception {
        var cert = dir.resolve("cert.pem");
        var key = dir.resolve("key.pem");
        var sb = new StringBuilder();
        for (var c : m.chain()) {
            sb.append(pem("CERTIFICATE", c.getEncoded()));
        }
        Files.writeString(cert, sb);
        Files.writeString(key, pem("PRIVATE KEY", m.key().getEncoded()));
        return new Path[] {cert, key};
    }

    /** Legacy PKCS#12 PBE (the JDK 21 encoder can't write PBES2; openssl's PBES2 is covered separately). */
    static byte[] encrypt(PrivateKey key, String password) throws Exception {
        var algorithm = "PBEWithSHA1AndDESede";
        var salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        var secret = SecretKeyFactory.getInstance(algorithm).generateSecret(new PBEKeySpec(password.toCharArray()));
        var cipher = Cipher.getInstance(algorithm);
        cipher.init(Cipher.ENCRYPT_MODE, secret, new PBEParameterSpec(salt, 10_000));
        AlgorithmParameters params = cipher.getParameters();
        return new EncryptedPrivateKeyInfo(params, cipher.doFinal(key.getEncoded())).getEncoded();
    }

    @Test
    void selfSignedIsValidForLocalhostAndKeyMatches() throws Exception {
        var m = TlsMaterial.selfSigned("localhost");
        assertTrue(m.selfSigned());
        X509Certificate cert = m.certificate();
        cert.checkValidity();
        cert.checkValidity(new Date(System.currentTimeMillis() + 300L * 86_400_000L));
        assertTrue(cert.getSubjectX500Principal().getName().contains("CN=localhost"));
        assertEquals("EC", m.key().getAlgorithm());
        assertEquals(1, m.chain().length);
        // round trip through PEM proves the pair matches (fromPem verifies a signature)
        var files = writePem(this.tmp, m);
        assertEquals(m.fingerprint(), TlsMaterial.fromPem(files[0], files[1], null).fingerprint());
    }

    @Test
    void fingerprintFormatAndUniqueness() {
        var a = TlsMaterial.selfSigned("localhost");
        var b = TlsMaterial.selfSigned("localhost");
        assertTrue(a.fingerprint().matches("([0-9A-F]{2}:){31}[0-9A-F]{2}"), a.fingerprint());
        assertNotEquals(a.fingerprint(), b.fingerprint());
        assertTrue(a.toString().contains(a.fingerprint()));
        assertTrue(a.toString().contains("selfSigned=true"));
        assertFalse(a.toString().contains(Base64.getEncoder().encodeToString(a.key().getEncoded())), "key leaked");
    }

    @Test
    void chainIsDefensivelyCopied() {
        var m = TlsMaterial.selfSigned("localhost");
        var chain = m.chain();
        chain[0] = null;
        assertEquals(m.certificate(), m.chain()[0]);
        assertNotSame(m.chain(), m.chain());
        assertThrows(IllegalArgumentException.class, () -> new TlsMaterial(m.key(), new X509Certificate[0], false));
        assertThrows(NullPointerException.class, () -> new TlsMaterial(null, m.chain(), false));
    }

    @Test
    void pemRoundTripRsaWithChain() throws Exception {
        // RSA leaf plus a second certificate in the chain file
        var rsa = rsaMaterial();
        var other = TlsMaterial.selfSigned("other");
        var withChain = new TlsMaterial(rsa.key(), new X509Certificate[] {rsa.certificate(), other.certificate()}, false);
        var files = writePem(this.tmp, withChain);
        var loaded = TlsMaterial.fromPem(files[0], files[1], null);
        assertFalse(loaded.selfSigned());
        assertEquals(2, loaded.chain().length);
        assertEquals(rsa.certificate(), loaded.certificate());
        assertArrayEquals(rsa.key().getEncoded(), loaded.key().getEncoded());
    }

    static TlsMaterial rsaMaterial() throws Exception {
        return keytoolMaterial("RSA");
    }

    /** Key pair + certificate made with the JDK's keytool (the server only generates EC itself). */
    static TlsMaterial keytoolMaterial(String algorithm) throws Exception {
        var dir = Files.createTempDirectory("rsa");
        var store = dir.resolve("ks.p12");
        var keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        var p = new ProcessBuilder(keytool, "-genkeypair", "-alias", "a", "-keyalg", algorithm,
                "-dname", "CN=localhost", "-validity", "2", "-storetype", "PKCS12", "-keystore", store.toString(),
                "-storepass", "changeit", "-keypass", "changeit").redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assertEquals(0, p.waitFor());
        var ks = java.security.KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(store)) {
            ks.load(in, "changeit".toCharArray());
        }
        return new TlsMaterial((PrivateKey) ks.getKey("a", "changeit".toCharArray()),
                new X509Certificate[] {(X509Certificate) ks.getCertificate("a")}, false);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"RSA", "Ed25519", "DSA", "RSASSA-PSS"})
    void pemRoundTripOtherKeyTypes(String algorithm) throws Exception {
        var m = keytoolMaterial(algorithm);
        var files = writePem(Files.createDirectory(this.tmp.resolve(algorithm)), m);
        var loaded = TlsMaterial.fromPem(files[0], files[1], null);
        assertArrayEquals(m.key().getEncoded(), loaded.key().getEncoded());
        assertEquals(m.certificate(), loaded.certificate());
    }

    @Test
    void unreadableKeyFile() throws Exception {
        var files = writePem(this.tmp, TlsMaterial.selfSigned("localhost"));
        Files.setPosixFilePermissions(files[1], java.nio.file.attribute.PosixFilePermissions.fromString("---------"));
        var e = assertThrows(IOException.class, () -> TlsMaterial.fromPem(files[0], files[1], null));
        assertTrue(e.getMessage().contains("not readable"), e.getMessage());
    }

    @Test
    void encryptedPkcs8NeedsTheRightPassword() throws Exception {
        var m = TlsMaterial.selfSigned("localhost");
        var cert = this.tmp.resolve("c.pem");
        var key = this.tmp.resolve("k.pem");
        Files.writeString(cert, pem("CERTIFICATE", m.certificate().getEncoded()));
        Files.writeString(key, pem("ENCRYPTED PRIVATE KEY", encrypt(m.key(), "s3cret")));

        var loaded = TlsMaterial.fromPem(cert, key, "s3cret");
        assertArrayEquals(m.key().getEncoded(), loaded.key().getEncoded());

        var wrong = assertThrows(IOException.class, () -> TlsMaterial.fromPem(cert, key, "nope"));
        assertTrue(wrong.getMessage().contains("could not decrypt"), wrong.getMessage());
        assertFalse(wrong.getMessage().contains("nope"), "password must not appear in messages");
        var missing = assertThrows(IOException.class, () -> TlsMaterial.fromPem(cert, key, null));
        assertTrue(missing.getMessage().contains("encrypted"), missing.getMessage());
    }

    @Test
    void opensslPbes2EncryptedKey() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(opensslAvailable(), "openssl not on PATH");
        var m = TlsMaterial.selfSigned("localhost");
        var files = writePem(this.tmp, m);
        var encrypted = this.tmp.resolve("enc.pem");
        var p = new ProcessBuilder("openssl", "pkcs8", "-topk8", "-v2", "aes-256-cbc", "-v2prf", "hmacWithSHA256",
                "-in", files[1].toString(), "-out", encrypted.toString(), "-passout", "pass:s3cret")
                .redirectErrorStream(true).start();
        var out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, p.waitFor(), out);
        assertTrue(Files.readString(encrypted).contains("ENCRYPTED PRIVATE KEY"));
        assertArrayEquals(m.key().getEncoded(), TlsMaterial.fromPem(files[0], encrypted, "s3cret").key().getEncoded());
    }

    static boolean opensslAvailable() {
        try {
            var p = new ProcessBuilder("openssl", "version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    @Test
    void mismatchedPairIsRejected() throws Exception {
        var a = writePem(Files.createDirectory(this.tmp.resolve("a")), TlsMaterial.selfSigned("localhost"));
        var b = writePem(Files.createDirectory(this.tmp.resolve("b")), TlsMaterial.selfSigned("localhost"));
        var e = assertThrows(IOException.class, () -> TlsMaterial.fromPem(a[0], b[1], null));
        assertTrue(e.getMessage().contains("does not match"), e.getMessage());
        // RSA key with an EC certificate: the signature check fails with an exception, still "no match"
        var rsa = writePem(Files.createDirectory(this.tmp.resolve("rsa")), rsaMaterial());
        assertThrows(IOException.class, () -> TlsMaterial.fromPem(a[0], rsa[1], null));
    }

    @Test
    void ed25519KeyIsSupported() throws Exception {
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var file = this.tmp.resolve("ed.pem");
        Files.writeString(file, pem("PRIVATE KEY", pair.getPrivate().getEncoded()));
        assertEquals("EdDSA", TlsMaterial.readKey(file, null).getAlgorithm());
    }

    @Test
    void clearErrorsForBadFiles() throws Exception {
        var good = writePem(this.tmp, TlsMaterial.selfSigned("localhost"));
        var missing = this.tmp.resolve("missing.pem");
        var e1 = assertThrows(IOException.class, () -> TlsMaterial.fromPem(missing, good[1], null));
        assertTrue(e1.getMessage().contains("missing or not readable"), e1.getMessage());
        assertThrows(IOException.class, () -> TlsMaterial.fromPem(good[0], missing, null));
        assertThrows(IOException.class, () -> TlsMaterial.fromPem(this.tmp, good[1], null)); // a directory

        var empty = Files.writeString(this.tmp.resolve("empty.pem"), "hello\n");
        assertTrue(assertThrows(IOException.class, () -> TlsMaterial.fromPem(empty, good[1], null)).getMessage()
                .contains("no PEM CERTIFICATE"));
        assertTrue(assertThrows(IOException.class, () -> TlsMaterial.fromPem(good[0], empty, null)).getMessage()
                .contains("no PEM PRIVATE KEY"));

        var garbageCert = Files.writeString(this.tmp.resolve("gc.pem"), pem("CERTIFICATE", new byte[] {1, 2, 3}));
        assertTrue(assertThrows(IOException.class, () -> TlsMaterial.fromPem(garbageCert, good[1], null))
                .getMessage().contains("invalid certificate"));
        var garbageKey = Files.writeString(this.tmp.resolve("gk.pem"), pem("PRIVATE KEY", new byte[] {1, 2, 3}));
        assertTrue(assertThrows(IOException.class, () -> TlsMaterial.fromPem(good[0], garbageKey, null))
                .getMessage().contains("unsupported or malformed"));
        var badBase64 = Files.writeString(this.tmp.resolve("b64.pem"),
                "-----BEGIN CERTIFICATE-----\nA===B\n-----END CERTIFICATE-----\n");
        assertThrows(IOException.class, () -> TlsMaterial.fromPem(badBase64, good[1], null));

        var pkcs1 = Files.writeString(this.tmp.resolve("rsa1.pem"), pem("RSA PRIVATE KEY", new byte[] {1}));
        assertTrue(assertThrows(IOException.class, () -> TlsMaterial.fromPem(good[0], pkcs1, null)).getMessage()
                .contains("PKCS#8"));
        var sec1 = Files.writeString(this.tmp.resolve("ec1.pem"), pem("EC PRIVATE KEY", new byte[] {1}));
        assertThrows(IOException.class, () -> TlsMaterial.fromPem(good[0], sec1, null));
        var badEnc = Files.writeString(this.tmp.resolve("enc.pem"), pem("ENCRYPTED PRIVATE KEY", new byte[] {1}));
        assertThrows(IOException.class, () -> TlsMaterial.fromPem(good[0], badEnc, "pw"));
    }

    @Test
    void resolvePicksPemOrSelfSigned() throws Exception {
        var m = TlsMaterial.selfSigned("localhost");
        var files = writePem(this.tmp, m);
        var withPem = ServerConfig.builder(this.tmp).tlsCert(files[0]).tlsKey(files[1]).build();
        var loaded = TlsMaterial.resolve(withPem);
        assertFalse(loaded.selfSigned());
        assertEquals(m.fingerprint(), loaded.fingerprint());

        var err = System.err;
        var captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        TlsMaterial generated;
        try {
            generated = TlsMaterial.resolve(ServerConfig.defaults(this.tmp));
        } finally {
            System.setErr(err);
        }
        assertTrue(generated.selfSigned());
        var log = captured.toString(StandardCharsets.UTF_8);
        assertTrue(log.contains(generated.fingerprint()), log);
        assertTrue(log.contains("self-signed"), log);
    }

    @Test
    void keyManagerFactoryForAFutureHttpsListener() throws Exception {
        var m = TlsMaterial.selfSigned("localhost");
        var kmf = m.keyManagerFactory();
        var km = (javax.net.ssl.X509KeyManager) kmf.getKeyManagers()[0];
        assertEquals(m.certificate(), km.getCertificateChain("server")[0]);
        var ctx = javax.net.ssl.SSLContext.getInstance("TLSv1.3");
        ctx.init(kmf.getKeyManagers(), null, null);
        assertTrue(java.util.List.of(ctx.createSSLEngine().getSupportedProtocols()).contains("TLSv1.3"));
    }

    @Test
    void warnsAboutReadableKeyAndCertificateOutsideItsValidity() throws Exception {
        var material = TlsMaterial.selfSigned("localhost");
        var key = java.nio.file.Files.writeString(this.tmp.resolve("k.pem"), "x");
        org.junit.jupiter.api.Assumptions.assumeTrue(key.getFileSystem().supportedFileAttributeViews().contains("posix"));
        java.nio.file.Files.setPosixFilePermissions(key, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        var now = java.time.Instant.now();
        assertTrue(TlsMaterial.warnings(key, material.certificate(), now).isEmpty());
        java.nio.file.Files.setPosixFilePermissions(key, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r-----"));
        var w = TlsMaterial.warnings(key, material.certificate(), now);
        assertEquals(1, w.size(), w.toString());
        assertTrue(w.get(0).contains("readable by group/others"));
        java.nio.file.Files.setPosixFilePermissions(key, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        assertTrue(TlsMaterial.warnings(key, material.certificate(), now.plus(java.time.Duration.ofDays(400)))
                .get(0).contains("expired"));
        assertTrue(TlsMaterial.warnings(key, material.certificate(), now.minus(java.time.Duration.ofDays(2)))
                .get(0).contains("not valid before"));
        assertTrue(TlsMaterial.warnings(this.tmp.resolve("missing.pem"), material.certificate(), now).isEmpty());
    }

    @org.junit.jupiter.api.Test
    void derLengthsUseTheShortestForm() {
        for (int len : new int[] {0x7f, 0x80, 0xff, 0x100, 0xffff, 0x10000, 0x123456}) {
            byte[] der = SelfSignedDer.tlv(0x04, new byte[len]);
            int hdr = len < 0x80 ? 2 : len < 0x100 ? 3 : len < 0x10000 ? 4 : 5;
            org.junit.jupiter.api.Assertions.assertEquals(hdr + len, der.length, "length " + len);
            if (hdr == 5) {
                org.junit.jupiter.api.Assertions.assertEquals((byte) 0x83, der[1]);
                org.junit.jupiter.api.Assertions.assertEquals(len, (der[2] & 0xff) << 16 | (der[3] & 0xff) << 8 | der[4] & 0xff);
            }
        }
    }

    @Test
    void selfSignedNamesTheHostLocalhostAndLoopbackAddresses() throws Exception {
        var sans = TlsMaterial.selfSigned("example.test").certificate().getSubjectAlternativeNames().stream()
                .map(l -> l.get(0) + "=" + l.get(1)).toList();
        assertEquals(java.util.List.of("2=example.test", "2=localhost", "7=127.0.0.1", "7=0:0:0:0:0:0:0:1"), sans);
        var local = TlsMaterial.selfSigned("localhost").certificate().getSubjectAlternativeNames().stream()
                .map(l -> l.get(0) + "=" + l.get(1)).toList();
        assertEquals(java.util.List.of("2=localhost", "7=127.0.0.1", "7=0:0:0:0:0:0:0:1"), local);
    }

    @Test
    void keyOfAnotherAlgorithmIsRefused() throws Exception {
        var files = writePem(this.tmp, TlsMaterial.selfSigned("localhost"));
        var ed = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPrivate();
        java.nio.file.Files.writeString(files[1], "-----BEGIN PRIVATE KEY-----\n"
                + java.util.Base64.getMimeEncoder().encodeToString(ed.getEncoded()) + "\n-----END PRIVATE KEY-----\n");
        assertThrows(java.io.IOException.class, () -> TlsMaterial.fromPem(files[0], files[1], null));
    }
}
