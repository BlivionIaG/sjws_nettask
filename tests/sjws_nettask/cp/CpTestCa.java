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

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.Date;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/** A throwaway test CA and node certificates it signs, written as PEM files under a temp directory. */
final class CpTestCa {

    private final Path dir;
    private final KeyPair caKeys = ec();
    private final X500Name caName = new X500Name("CN=cp-test-ca");
    private final X509Certificate ca;
    private final Path caPem;
    private long serial = 2;

    CpTestCa(Path dir) throws Exception {
        this.dir = Files.createDirectories(dir);
        var b = new JcaX509v3CertificateBuilder(caName, BigInteger.ONE, new Date(System.currentTimeMillis() - 3_600_000L),
                new Date(System.currentTimeMillis() + 86_400_000L), caName, caKeys.getPublic());
        b.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
        this.ca = sign(b);
        this.caPem = pem(dir.resolve("ca.pem"), ca);
    }

    Path caPem() {
        return caPem;
    }

    /** Listener material for {@code node}: leaf cert + chain, a 0600 key, and this CA as the trust anchor. */
    CpListenerConfig listener(String listener, String node) throws Exception {
        return listener(listener, node, System.currentTimeMillis() + 86_400_000L, null);
    }

    /** Serial of the last certificate {@link #listener} issued. */
    BigInteger lastSerial() {
        return BigInteger.valueOf(serial - 1);
    }

    /**
     * @param notAfter certificate expiry (in the past for an expired-cert test)
     * @param revocations denylist file for this listener, or null
     */
    CpListenerConfig listener(String listener, String node, long notAfter, Path revocations) throws Exception {
        return listener(listener, node, notAfter, revocations, new String[0]);
    }

    /**
     * A {@code cp-peer} certificate for {@code node} carrying the voter SAN {@code urn:sjws:node:<node>} (#175).
     */
    CpListenerConfig peer(String node) throws Exception {
        return listener("cp-peer", node, System.currentTimeMillis() + 86_400_000L, null, san(node));
    }

    /** The pinned voter SAN the tests use for {@code node}. */
    /** The typed {@code cp.peers} pin for {@code node}; {@link #listener} issues it as a URI SAN. */
    static String san(String node) {
        return "uri:urn:sjws:node:" + node;
    }

    /**
     * @param sans subject alternative names: {@code dns:<name>} is a DNS SAN, {@code uri:<value>} or anything else
     *        a URI SAN; none
     *        gives a CN-only certificate
     */
    CpListenerConfig listener(String listener, String node, long notAfter, Path revocations, String... sans)
            throws Exception {
        var leafKeys = ec();
        var b = new JcaX509v3CertificateBuilder(caName, BigInteger.valueOf(serial++),
                new Date(Math.min(notAfter, System.currentTimeMillis()) - 3_600_000L), new Date(notAfter),
                new X500Name("CN=" + node), leafKeys.getPublic());
        if (sans.length > 0) {
            var names = new org.bouncycastle.asn1.x509.GeneralName[sans.length];
            for (int i = 0; i < sans.length; i++) {
                names[i] = sans[i].startsWith("dns:")
                        ? new org.bouncycastle.asn1.x509.GeneralName(org.bouncycastle.asn1.x509.GeneralName.dNSName,
                                sans[i].substring(4))
                        : new org.bouncycastle.asn1.x509.GeneralName(
                                org.bouncycastle.asn1.x509.GeneralName.uniformResourceIdentifier,
                                sans[i].startsWith("uri:") ? sans[i].substring(4) : sans[i]);
            }
            b.addExtension(Extension.subjectAlternativeName, false,
                    new org.bouncycastle.asn1.x509.GeneralNames(names));
        }
        var leaf = sign(b);
        var file = node + "-" + listener + (sans.length > 0 ? "-" + serial : "");
        var certPem = pem(dir.resolve(file + ".pem"), leaf, ca);
        var keyPem = dir.resolve(file + ".key");
        Files.writeString(keyPem, "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(leafKeys.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n");
        Files.setPosixFilePermissions(keyPem, PosixFilePermissions.fromString("rw-------"));
        return new CpListenerConfig(listener, certPem, keyPem, caPem, revocations, false);
    }

    private X509Certificate sign(JcaX509v3CertificateBuilder b) throws Exception {
        return new JcaX509CertificateConverter().getCertificate(
                b.build(new JcaContentSignerBuilder("SHA256withECDSA").build(caKeys.getPrivate())));
    }

    private static KeyPair ec() {
        try {
            var gen = KeyPairGenerator.getInstance("EC");
            gen.initialize(new ECGenParameterSpec("secp256r1"));
            return gen.generateKeyPair();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
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
