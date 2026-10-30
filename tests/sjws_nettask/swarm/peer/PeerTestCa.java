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
package sjws_nettask.swarm.peer;

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
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import sjws_nettask.cp.CpListenerConfig;

/** A throwaway swarm-peer CA and node certificates carrying {@code urn:sjws:node:<node>} URI SANs. */
final class PeerTestCa {

    private final Path dir;
    private final KeyPair caKeys = ec();
    private final X500Name caName;
    private final X509Certificate ca;
    private final Path caPem;
    private long serial = 2;

    PeerTestCa(Path dir) throws Exception {
        this.dir = Files.createDirectories(dir);
        this.caName = new X500Name("CN=swarm-peer-test-ca-" + dir.getFileName());
        var b = new JcaX509v3CertificateBuilder(caName, BigInteger.ONE, new Date(System.currentTimeMillis() - 3_600_000L),
                new Date(System.currentTimeMillis() + 86_400_000L), caName, caKeys.getPublic());
        b.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
        this.ca = sign(b);
        this.caPem = pem(dir.resolve("ca.pem"), ca);
    }

    Path caPem() {
        return caPem;
    }

    /** Cert, key and CA for a certificate naming {@code sans} (URI SANs); the CN is {@code node}. */
    CpListenerConfig node(String node, String... sans) throws Exception {
        var leafKeys = ec();
        var b = new JcaX509v3CertificateBuilder(caName, BigInteger.valueOf(serial++),
                new Date(System.currentTimeMillis() - 3_600_000L), new Date(System.currentTimeMillis() + 86_400_000L),
                new X500Name("CN=" + node), leafKeys.getPublic());
        var names = new GeneralName[sans.length];
        for (int i = 0; i < sans.length; i++) {
            names[i] = new GeneralName(GeneralName.uniformResourceIdentifier, sans[i]);
        }
        if (names.length > 0) {
            b.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(names));
        }
        var leaf = sign(b);
        var certPem = pem(dir.resolve(node + "-" + serial + ".pem"), leaf, ca);
        var keyPem = dir.resolve(node + "-" + serial + ".key");
        Files.writeString(keyPem, "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(leafKeys.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n");
        Files.setPosixFilePermissions(keyPem, PosixFilePermissions.fromString("rw-------"));
        return new CpListenerConfig(PeerSockets.LISTENER, certPem, keyPem, caPem, null, false);
    }

    /** A node certificate with the default pinned SAN. */
    CpListenerConfig node(String node) throws Exception {
        return node(node, PeerConfig.SAN_PREFIX + node);
    }

    /** Serial of the last certificate {@link #node} issued. */
    java.math.BigInteger lastSerial() {
        return java.math.BigInteger.valueOf(serial - 1);
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
