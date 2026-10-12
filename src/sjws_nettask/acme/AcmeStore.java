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
package sjws_nettask.acme;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Set;
import org.shredzone.acme4j.util.KeyPairUtils;
import sjws_nettask.tls.TlsMaterial;

/**
 * Account key, domain key and certificate chain on disk. New keys are PKCS#8 PEM ({@code PRIVATE
 * KEY}) so {@link TlsMaterial#fromPem} can load the domain key, and owner-read/write when the
 * filesystem allows it. A legacy PKCS#1 {@code RSA PRIVATE KEY} file is still read. If
 * {@code directory} does not match the CA the account URL was issued by, the URL is ignored and
 * the key is kept.
 */
public final class AcmeStore {

    private static final Set<PosixFilePermission> OWNER_ONLY = EnumSet.of(PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE);

    private final Path directory;

    public AcmeStore(Path directory) {
        this.directory = directory;
    }

    public Path directory() {
        return this.directory;
    }

    public KeyPair accountKey() throws IOException {
        return key("account.key");
    }

    public KeyPair domainKey() throws IOException {
        return key("domain.key");
    }

    /** @return the account URL when it was stored for this directory, otherwise null */
    public URL accountUrl(String acmeDirectory) throws IOException {
        var marker = this.directory.resolve("directory");
        var url = this.directory.resolve("account.url");
        if (!Files.isRegularFile(marker) || !Files.isRegularFile(url)) {
            return null;
        }
        if (!Files.readString(marker).trim().equals(acmeDirectory)) {
            return null;
        }
        return URI.create(Files.readString(url).trim()).toURL();
    }

    public void saveAccount(URL url, String acmeDirectory) throws IOException {
        Files.createDirectories(this.directory);
        Files.writeString(this.directory.resolve("directory"), acmeDirectory + "\n");
        Files.writeString(this.directory.resolve("account.url"), url + "\n");
    }

    public void writeCertificate(org.shredzone.acme4j.Certificate certificate) throws IOException {
        var writer = new StringWriter();
        certificate.writeCertificate(writer);
        Files.createDirectories(this.directory);
        Files.writeString(this.directory.resolve("cert.pem"), writer.toString());
    }

    /** @return the stored chain, or null when there is no certificate yet */
    public TlsMaterial loadCertificate() throws IOException {
        var cert = this.directory.resolve("cert.pem");
        var key = this.directory.resolve("domain.key");
        if (!Files.isRegularFile(cert) || !Files.isRegularFile(key)) {
            return null;
        }
        return TlsMaterial.fromPem(cert, key, null);
    }

    private KeyPair key(String name) throws IOException {
        Files.createDirectories(this.directory);
        var path = this.directory.resolve(name);
        if (Files.isRegularFile(path)) {
            return readKey(path);
        }
        var pair = KeyPairUtils.createKeyPair(2048);
        var body = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(pair.getPrivate().getEncoded());
        Files.writeString(path, "-----BEGIN PRIVATE KEY-----\n" + body + "\n-----END PRIVATE KEY-----\n");
        try {
            Files.setPosixFilePermissions(path, OWNER_ONLY);
        } catch (UnsupportedOperationException e) {
            // filesystems without POSIX permissions keep the process umask
        }
        return pair;
    }

    private static KeyPair readKey(Path path) throws IOException {
        var pem = Files.readString(path);
        if (pem.contains("-----BEGIN PRIVATE KEY-----")) {
            return pkcs8(pem, path);
        }
        try (var reader = new StringReader(pem)) {
            return KeyPairUtils.readKeyPair(reader);
        }
    }

    private static KeyPair pkcs8(String pem, Path path) throws IOException {
        int begin = pem.indexOf("-----BEGIN PRIVATE KEY-----");
        int end = pem.indexOf("-----END PRIVATE KEY-----", begin);
        if (end < 0) {
            throw new IOException("invalid PKCS#8 key in " + path);
        }
        var body = pem.substring(begin + "-----BEGIN PRIVATE KEY-----".length(), end).replaceAll("\\s", "");
        try {
            var factory = KeyFactory.getInstance("RSA");
            var privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)));
            if (!(privateKey instanceof RSAPrivateCrtKey crt)) {
                throw new IOException("ACME key " + path + " is not an RSA key");
            }
            var publicKey = factory.generatePublic(new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent()));
            return new KeyPair(publicKey, privateKey);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IOException("invalid PKCS#8 key in " + path, e);
        }
    }
}
