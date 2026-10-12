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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateException;
import java.security.spec.ECGenParameterSpec;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.EncryptedPrivateKeyInfo;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.net.ssl.KeyManagerFactory;
import sjws_nettask.ServerConfig;

/**
 * Server key and certificate chain, independent of the transport: the HTTP/3 listener builds a
 * QUIC (BoringSSL) context from it, and a future HTTPS/TCP listener can use {@link #keyManagerFactory()}.
 * <p>
 * Either loaded from PEM files (X.509 chain + PKCS#8 key, optionally encrypted) or generated as
 * a self-signed certificate for development. Secrets are never logged or put into messages.
 *
 * @param key private key matching {@code chain[0]}
 * @param chain certificate chain, leaf first (never empty)
 * @param selfSigned true if generated at startup
 */
public record TlsMaterial(PrivateKey key, X509Certificate[] chain, boolean selfSigned) {

    private static final Pattern PEM = Pattern.compile(
            "-----BEGIN ([A-Z0-9 ]+)-----([A-Za-z0-9+/=\\s]*?)-----END \\1-----");
    private static final String[] KEY_ALGORITHMS = {"RSA", "EC", "Ed25519", "Ed448", "RSASSA-PSS", "DSA"};

    public TlsMaterial {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(chain, "chain");
        if (chain.length == 0) {
            throw new IllegalArgumentException("empty certificate chain");
        }
        chain = chain.clone();
    }

    @Override
    public X509Certificate[] chain() {
        return this.chain.clone();
    }

    public X509Certificate certificate() {
        return this.chain[0];
    }

    /** @return SHA-256 fingerprint of the leaf certificate, "AB:CD:..." */
    public String fingerprint() {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(this.chain[0].getEncoded());
            return HexFormat.ofDelimiter(":").withUpperCase().formatHex(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** JSSE adapter for a TCP/TLS listener (in-memory key store, never written to disk). */
    public KeyManagerFactory keyManagerFactory() throws GeneralSecurityException {
        var password = new char[0];
        var store = KeyStore.getInstance("PKCS12");
        try {
            store.load(null, null);
        } catch (IOException e) {
            throw new GeneralSecurityException(e);
        }
        store.setKeyEntry("server", this.key, password, this.chain.clone());
        var kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(store, password);
        return kmf;
    }

    @Override
    public String toString() {
        return "TlsMaterial[subject=" + this.chain[0].getSubjectX500Principal() + ", selfSigned=" + this.selfSigned
                + ", sha256=" + fingerprint() + "]";
    }

    /**
     * PEM files from the configuration if set, otherwise a fresh self-signed certificate for
     * "localhost" (a warning with its fingerprint is printed to stderr).
     *
     * @throws IOException if the configured files are missing, unreadable, malformed or don't match
     */
    public static TlsMaterial resolve(ServerConfig config) throws IOException {
        if (config.tlsCert() != null) {
            var material = fromPem(config.tlsCert(), config.tlsKey(), config.tlsKeyPassword());
            warnings(config.tlsKey(), material.certificate(), java.time.Instant.now())
                    .forEach(w -> System.err.println("WARN: " + w));
            return material;
        }
        var material = selfSigned("localhost");
        System.err.println("WARN: no TLS certificate configured, using a self-signed one for localhost (SHA-256 "
                + material.fingerprint() + "); browsers will reject it, use curl -k or pin this fingerprint");
        return material;
    }

    /**
     * Configuration smells that don't stop the server: a private key file other users can read, and
     * a leaf certificate outside its validity period (clients will reject it).
     */
    static List<String> warnings(Path keyFile, X509Certificate leaf, java.time.Instant now) {
        var list = new ArrayList<String>();
        try {
            var perms = Files.getPosixFilePermissions(keyFile);
            if (perms.contains(java.nio.file.attribute.PosixFilePermission.GROUP_READ)
                    || perms.contains(java.nio.file.attribute.PosixFilePermission.OTHERS_READ)) {
                list.add("TLS key " + keyFile + " is readable by group/others ("
                        + java.nio.file.attribute.PosixFilePermissions.toString(perms) + "); chmod 600 it");
            }
        } catch (UnsupportedOperationException | IOException e) {
            // no POSIX permissions (Windows): nothing to check
        }
        try {
            leaf.checkValidity(java.util.Date.from(now));
        } catch (java.security.cert.CertificateExpiredException e) {
            list.add("TLS certificate " + leaf.getSubjectX500Principal() + " expired on " + leaf.getNotAfter().toInstant()
                    + "; clients will reject it");
        } catch (java.security.cert.CertificateNotYetValidException e) {
            list.add("TLS certificate " + leaf.getSubjectX500Principal() + " is not valid before "
                    + leaf.getNotBefore().toInstant() + "; clients will reject it");
        }
        return list;
    }

    /**
     * Generates an EC P-256 self-signed certificate (SHA256withECDSA), valid from one day ago for
     * one year, with subjectAltName DNS:{@code fqdn}, DNS:localhost and IP:127.0.0.1, IP:::1.
     * Pure JDK (no BouncyCastle, no keytool process).
     */
    public static TlsMaterial selfSigned(String fqdn) {
        try {
            var generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            var pair = generator.generateKeyPair();
            var now = System.currentTimeMillis();
            var tbs = SelfSignedDer.tbsCertificate(fqdn, pair.getPublic().getEncoded(), new SecureRandom(),
                    now - 86_400_000L, now + 365L * 86_400_000L);
            var signer = Signature.getInstance("SHA256withECDSA");
            signer.initSign(pair.getPrivate());
            signer.update(tbs);
            var der = SelfSignedDer.certificate(tbs, signer.sign());
            var cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(der));
            cert.verify(pair.getPublic());
            return new TlsMaterial(pair.getPrivate(), new X509Certificate[] {cert}, true);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("could not generate a self-signed certificate", e);
        }
    }

    /**
     * @param certFile PEM file with one or more certificates, leaf first
     * @param keyFile PEM file with a PKCS#8 key ("PRIVATE KEY" or "ENCRYPTED PRIVATE KEY")
     * @param password password of an encrypted key, or null
     */
    public static TlsMaterial fromPem(Path certFile, Path keyFile, String password) throws IOException {
        Objects.requireNonNull(certFile, "certFile");
        Objects.requireNonNull(keyFile, "keyFile");
        var chain = readCertificates(certFile);
        var key = readKey(keyFile, password);
        if (!matches(key, chain[0])) {
            throw new IOException("TLS key " + keyFile + " does not match the certificate in " + certFile);
        }
        return new TlsMaterial(key, chain, false);
    }

    static X509Certificate[] readCertificates(Path file) throws IOException {
        var blocks = blocks(read(file, "certificate"), "CERTIFICATE");
        if (blocks.isEmpty()) {
            throw new IOException("no PEM CERTIFICATE block in " + file);
        }
        var list = new ArrayList<X509Certificate>();
        try {
            var factory = CertificateFactory.getInstance("X.509");
            for (var der : blocks) {
                list.add((X509Certificate) factory.generateCertificate(new ByteArrayInputStream(der)));
            }
        } catch (CertificateException e) {
            throw new IOException("invalid certificate in " + file + ": " + e.getMessage(), e);
        }
        return list.toArray(X509Certificate[]::new);
    }

    static PrivateKey readKey(Path file, String password) throws IOException {
        var pem = read(file, "key");
        if (pem.contains("-----BEGIN RSA PRIVATE KEY-----") || pem.contains("-----BEGIN EC PRIVATE KEY-----")) {
            throw new IOException("TLS key " + file + " is PKCS#1/SEC1; convert it to PKCS#8 with "
                    + "'openssl pkcs8 -topk8 -nocrypt -in old.pem -out new.pem'");
        }
        byte[] pkcs8;
        var plain = blocks(pem, "PRIVATE KEY");
        var encrypted = blocks(pem, "ENCRYPTED PRIVATE KEY");
        if (!plain.isEmpty()) {
            pkcs8 = plain.get(0);
        } else if (!encrypted.isEmpty()) {
            if (password == null) {
                throw new IOException("TLS key " + file + " is encrypted but no password was given");
            }
            pkcs8 = decrypt(encrypted.get(0), password, file);
        } else {
            throw new IOException("no PEM PRIVATE KEY block in " + file);
        }
        var spec = new PKCS8EncodedKeySpec(pkcs8);
        for (var algorithm : KEY_ALGORITHMS) {
            try {
                return KeyFactory.getInstance(algorithm).generatePrivate(spec);
            } catch (InvalidKeySpecException | java.security.NoSuchAlgorithmException e) {
                // try the next algorithm
            }
        }
        throw new IOException("unsupported or malformed private key in " + file);
    }

    private static byte[] decrypt(byte[] der, String password, Path file) throws IOException {
        try {
            var info = new EncryptedPrivateKeyInfo(der);
            var params = info.getAlgParameters();
            // PBES2 keys report "PBES2" (or its OID) as their name; the parameters name the real scheme.
            var name = info.getAlgName();
            var algorithm = name.equals("PBES2") || name.equals("1.2.840.113549.1.5.13") ? params.toString() : name;
            var factory = SecretKeyFactory.getInstance(algorithm);
            var cipher = Cipher.getInstance(algorithm);
            var chars = password.toCharArray();
            var spec = new PBEKeySpec(chars);
            try {
                cipher.init(Cipher.DECRYPT_MODE, factory.generateSecret(spec), params);
            } finally {
                spec.clearPassword(); // the copies we made; the caller's String can't be cleared
                java.util.Arrays.fill(chars, '\0');
            }
            return info.getKeySpec(cipher).getEncoded();
        } catch (GeneralSecurityException | IOException e) {
            throw new IOException("could not decrypt TLS key " + file + " (wrong password or unsupported scheme)", e);
        }
    }

    private static boolean matches(PrivateKey key, X509Certificate cert) {
        var algorithm = switch (key.getAlgorithm()) {
            case "RSA" -> "SHA256withRSA";
            case "EC" -> "SHA256withECDSA";
            case "DSA" -> "SHA256withDSA";
            case "RSASSA-PSS" -> null;
            default -> key.getAlgorithm(); // Ed25519, Ed448, EdDSA
        };
        try {
            if (algorithm == null) {
                return key.getAlgorithm().equals(cert.getPublicKey().getAlgorithm());
            }
            var challenge = new byte[32];
            new SecureRandom().nextBytes(challenge);
            var signer = Signature.getInstance(algorithm);
            signer.initSign(key);
            signer.update(challenge);
            var signature = signer.sign();
            var verifier = Signature.getInstance(algorithm);
            verifier.initVerify(cert.getPublicKey());
            verifier.update(challenge);
            return verifier.verify(signature);
        } catch (GeneralSecurityException e) {
            return false; // e.g. an RSA key with an EC certificate
        }
    }

    private static String read(Path file, String what) throws IOException {
        if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
            throw new IOException("TLS " + what + " file " + file + " is missing or not readable");
        }
        return Files.readString(file, StandardCharsets.ISO_8859_1);
    }

    private static List<byte[]> blocks(String pem, String type) throws IOException {
        var list = new ArrayList<byte[]>();
        var m = PEM.matcher(pem);
        while (m.find()) {
            if (m.group(1).equals(type)) {
                try {
                    list.add(Base64.getMimeDecoder().decode(m.group(2)));
                } catch (IllegalArgumentException e) {
                    throw new IOException("bad base64 in PEM " + type + " block", e);
                }
            }
        }
        return list;
    }
}
