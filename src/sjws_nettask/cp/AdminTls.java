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

import java.io.IOException;
import java.math.BigInteger;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSessionContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.security.auth.x500.X500Principal;
import sjws_nettask.tls.TlsMaterial;

/**
 * Reloadable mTLS material for the admin listener. The {@link SSLContext} is built once around a
 * key manager and a trust manager that delegate to the current {@link Material}; {@link #reload()}
 * swaps that material atomically, so new handshakes use the new certificate, key, CA bundle and
 * revocation list with no restart. A failed reload keeps the previous material.
 *
 * <p>Revocation is checked at the handshake, after normal chain validation, against every
 * certificate in the client chain. The revocation file is optional. It is either PEM X.509 CRLs
 * (each must be signed by a certificate in the trust bundle) or a denylist with one entry per
 * line: {@code sha256:<hex fingerprint>} or {@code serial:<hex serial>}; {@code #} starts a
 * comment. A configured but missing or unreadable file fails closed. Each rejection is reported to
 * the {@link Audit} sink.
 */
public final class AdminTls {

    /** Where security events go. Rejections are written before the handshake fails. */
    @FunctionalInterface
    public interface Audit {
        void record(String actor, String action, String target, String outcome);
    }

    /** One immutable generation of key, trust and revocation state. */
    record Material(X509ExtendedKeyManager keys, X509ExtendedTrustManager trust, X509Certificate[] anchors,
            Set<String> fingerprints, Set<BigInteger> serials, List<X509CRL> crls, Stamp stamp, long gen) {

        String revokedBy(X509Certificate cert) {
            if (fingerprints.isEmpty() && serials.isEmpty() && crls.isEmpty()) {
                return null;
            }
            if (fingerprints.contains(fingerprint(cert))) {
                return "fingerprint";
            }
            if (serials.contains(cert.getSerialNumber())) {
                return "serial";
            }
            for (var crl : crls) {
                if (crl.getIssuerX500Principal().equals(cert.getIssuerX500Principal()) && crl.isRevoked(cert)) {
                    return "crl";
                }
            }
            return null;
        }
    }

    /** Modification times and sizes of the watched files, to detect changes. */
    record Stamp(List<String> parts) {
    }

    private final Path certPem;
    private final Path keyPem;
    private final String keyPassword;
    private final Path trustBundle;
    private final Path revocations;
    private final Audit audit;
    private final String label;
    /** Peer links ({@code cp-peer}) dial as well as listen: same material, same revocation checks. */
    private final boolean actsAsClient;
    private final SSLContext context;
    private final AtomicLong generation = new AtomicLong();
    private volatile Material current;
    private volatile Material previous;

    private AdminTls(String label, Path certPem, Path keyPem, String keyPassword, Path trustBundle, Path revocations,
            Audit audit, boolean actsAsClient) throws IOException {
        this.label = label;
        this.actsAsClient = actsAsClient;
        this.certPem = certPem;
        this.keyPem = keyPem;
        this.keyPassword = keyPassword;
        this.trustBundle = trustBundle;
        this.revocations = revocations;
        this.audit = Objects.requireNonNull(audit, "audit");
        this.current = load(1);
        this.generation.set(1);
        try {
            this.context = SSLContext.getInstance("TLSv1.3");
            this.context.init(new KeyManager[] {new Keys()}, new TrustManager[] {new Trust()}, null);
        } catch (GeneralSecurityException e) {
            throw new IOException("admin mTLS material rejected: " + e.getMessage(), e);
        }
    }

    /**
     * Loads the material once. Throws when the certificate, key or trust bundle is missing or
     * empty, or when a configured revocation file is missing or invalid.
     *
     * @param revocations CRL or denylist file, or null for none
     */
    public static AdminTls open(Path certPem, Path keyPem, String keyPassword, Path trustBundle, Path revocations,
            Audit audit) throws IOException {
        if (certPem == null || keyPem == null || trustBundle == null) {
            throw new IOException("admin listener requires a server certificate, a key, and an admin CA trust bundle");
        }
        return new AdminTls("admin-tls", certPem, keyPem, keyPassword, trustBundle, revocations, audit, false);
    }

    /**
     * Same reloadable, revocation-checked server context for another mTLS listener (the CP watch
     * channel). {@code label} prefixes audit actions, e.g. {@code cp-tls.handshake}.
     */
    static AdminTls openListener(String label, Path certPem, Path keyPem, String keyPassword, Path trustBundle,
            Path revocations, Audit audit) throws IOException {
        return openListener(label, certPem, keyPem, keyPassword, trustBundle, revocations, audit, false);
    }

    /**
     * @param actsAsClient also dial with this context (CONTROL voter links): the client side presents the same
     *        certificate and checks the server's chain against the same CA and revocations. Off for the admin and
     *        watch listeners, which never act as a TLS client.
     */
    static AdminTls openListener(String label, Path certPem, Path keyPem, String keyPassword, Path trustBundle,
            Path revocations, Audit audit, boolean actsAsClient) throws IOException {
        if (certPem == null || keyPem == null || trustBundle == null) {
            throw new IOException(label + " requires a certificate, a key, and a CA trust bundle");
        }
        return new AdminTls(label, certPem, keyPem, keyPassword, trustBundle, revocations, audit, actsAsClient);
    }

    public SSLContext context() {
        return context;
    }

    /** The delegating managers installed in {@link #context()}; for tests. */
    X509ExtendedTrustManager trustManager() {
        return new Trust();
    }

    X509ExtendedKeyManager keyManager() {
        return new Keys();
    }

    /** Number of successful loads, starting at 1. */
    public long generation() {
        return generation.get();
    }

    /**
     * Re-reads every file and swaps the material if all of it is valid. Cached sessions are
     * invalidated so that resumed handshakes are re-checked. Never throws.
     *
     * @return null on success, otherwise why the previous material was kept
     */
    public String reload() {
        try {
            var next = load(generation.get() + 1);
            previous = current;
            current = next;
            generation.set(next.gen());
            invalidateSessions(context.getServerSessionContext());
            audit.record("system", label + ".reload", String.valueOf(trustBundle), "ok");
            return null;
        } catch (IOException | RuntimeException e) {
            var why = String.valueOf(e.getMessage());
            audit.record("system", label + ".reload", String.valueOf(trustBundle), "failed: " + why);
            return why;
        }
    }

    /** Reloads when any watched file changed since the last successful load. @return true if it reloaded */
    public boolean reloadIfChanged() {
        var now = stamp();
        if (now.equals(current.stamp())) {
            return false;
        }
        return reload() == null;
    }

    private static void invalidateSessions(SSLSessionContext sessions) {
        if (sessions == null) {
            return;
        }
        var ids = sessions.getIds();
        while (ids.hasMoreElements()) {
            var session = sessions.getSession(ids.nextElement());
            if (session != null) {
                session.invalidate();
            }
        }
    }

    private Stamp stamp() {
        var parts = new ArrayList<String>();
        for (var p : new Path[] {certPem, keyPem, trustBundle, revocations}) {
            if (p == null) {
                parts.add("-");
                continue;
            }
            try {
                FileTime t = Files.getLastModifiedTime(p);
                parts.add(t.toMillis() + ":" + Files.size(p));
            } catch (IOException e) {
                parts.add("absent");
            }
        }
        return new Stamp(List.copyOf(parts));
    }

    private Material load(long gen) throws IOException {
        var stamp = stamp();
        var material = TlsMaterial.fromPem(certPem, keyPem, keyPassword);
        var anchors = trustCertificates(trustBundle);
        if (anchors.length == 0) {
            throw new IOException("admin CA trust bundle " + trustBundle + " contains no certificates");
        }
        try {
            var store = KeyStore.getInstance("PKCS12");
            store.load(null, null);
            for (int i = 0; i < anchors.length; i++) {
                store.setCertificateEntry("ca" + i, anchors[i]);
            }
            var tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(store);
            X509ExtendedTrustManager trust = null;
            for (var tm : tmf.getTrustManagers()) {
                if (tm instanceof X509ExtendedTrustManager x) {
                    trust = x;
                }
            }
            X509ExtendedKeyManager keys = null;
            for (var km : material.keyManagerFactory().getKeyManagers()) {
                if (km instanceof X509ExtendedKeyManager x) {
                    keys = x;
                }
            }
            if (trust == null || keys == null) {
                throw new IOException("admin mTLS material rejected: no X.509 key or trust manager");
            }
            var fingerprints = new HashSet<String>();
            var serials = new HashSet<BigInteger>();
            var crls = new ArrayList<X509CRL>();
            if (revocations != null) {
                readRevocations(revocations, anchors, fingerprints, serials, crls);
            }
            return new Material(keys, trust, anchors, Set.copyOf(fingerprints), Set.copyOf(serials),
                    List.copyOf(crls), stamp, gen);
        } catch (GeneralSecurityException e) {
            throw new IOException("admin mTLS material rejected: " + e.getMessage(), e);
        }
    }

    static X509Certificate[] trustCertificates(Path trustBundle) throws IOException {
        if (!Files.isRegularFile(trustBundle)) {
            throw new IOException("admin CA trust bundle is missing: " + trustBundle);
        }
        try (var in = Files.newInputStream(trustBundle)) {
            var certs = new ArrayList<X509Certificate>();
            for (var cert : CertificateFactory.getInstance("X.509").generateCertificates(in)) {
                if (cert instanceof X509Certificate x509) {
                    certs.add(x509);
                }
            }
            return certs.toArray(X509Certificate[]::new);
        } catch (CertificateException e) {
            throw new IOException("admin CA trust bundle rejected: " + e.getMessage(), e);
        }
    }

    private static void readRevocations(Path file, X509Certificate[] anchors, Set<String> fingerprints,
            Set<BigInteger> serials, List<X509CRL> crls) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IOException("admin revocation list is missing: " + file);
        }
        var text = Files.readString(file, StandardCharsets.US_ASCII);
        if (text.contains("-----BEGIN X509 CRL-----")) {
            try (var in = Files.newInputStream(file)) {
                for (var crl : CertificateFactory.getInstance("X.509").generateCRLs(in)) {
                    var x = (X509CRL) crl;
                    if (!signedByAnchor(x, anchors)) {
                        throw new IOException("admin CRL is not signed by the admin CA: " + x.getIssuerX500Principal());
                    }
                    crls.add(x);
                }
            } catch (java.security.cert.CRLException | CertificateException e) {
                throw new IOException("admin CRL rejected: " + e.getMessage(), e);
            }
            return;
        }
        int n = 0;
        for (var raw : text.split("\n", -1)) {
            n++;
            int hash = raw.indexOf('#');
            var line = (hash >= 0 ? raw.substring(0, hash) : raw).strip().toLowerCase(Locale.ROOT);
            if (line.isEmpty()) {
                continue;
            }
            try {
                if (line.startsWith("sha256:")) {
                    var hex = line.substring(7).replace(":", "");
                    if (hex.length() != 64) {
                        throw new IllegalArgumentException("fingerprint must be 32 bytes");
                    }
                    fingerprints.add(HexFormat.of().formatHex(HexFormat.of().parseHex(hex)));
                } else if (line.startsWith("serial:")) {
                    var hex = line.substring(7).replace(":", "");
                    serials.add(new BigInteger(hex, 16));
                } else {
                    throw new IllegalArgumentException("expected sha256:<hex> or serial:<hex>");
                }
            } catch (IllegalArgumentException e) {
                throw new IOException("admin revocation list " + file + " line " + n + ": " + e.getMessage(), e);
            }
        }
    }

    private static boolean signedByAnchor(X509CRL crl, X509Certificate[] anchors) {
        for (var a : anchors) {
            if (!a.getSubjectX500Principal().equals(crl.getIssuerX500Principal())) {
                continue;
            }
            try {
                crl.verify(a.getPublicKey());
                return true;
            } catch (GeneralSecurityException e) {
                // try the next anchor with the same name
            }
        }
        return false;
    }

    /** Lower-case hex SHA-256 of the DER encoding. */
    static String fingerprint(X509Certificate cert) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()));
        } catch (NoSuchAlgorithmException | CertificateEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private void checkRevoked(X509Certificate[] chain) throws CertificateException {
        var m = current;
        for (var cert : chain) {
            var why = m.revokedBy(cert);
            if (why != null) {
                var actor = cert == chain[0] ? subject(chain[0]) : subject(chain[0]) + " via " + subject(cert);
                audit.record(actor, label + ".handshake",
                        "serial:" + cert.getSerialNumber().toString(16) + " sha256:" + fingerprint(cert),
                        "denied: revoked (" + why + ")");
                throw new CertificateException("client certificate is revoked");
            }
        }
    }

    private static String subject(X509Certificate cert) {
        return cert.getSubjectX500Principal().getName(X500Principal.RFC2253);
    }

    private final class Trust extends X509ExtendedTrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            current.trust().checkClientTrusted(chain, authType, socket);
            checkRevoked(chain);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                throws CertificateException {
            current.trust().checkClientTrusted(chain, authType, engine);
            checkRevoked(chain);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            current.trust().checkClientTrusted(chain, authType);
            checkRevoked(chain);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            if (actsAsClient) {
                current.trust().checkServerTrusted(chain, authType, socket);
                checkRevoked(chain);
                return;
            }
            throw new CertificateException("admin listener does not act as a TLS client");
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                throws CertificateException {
            if (actsAsClient) {
                current.trust().checkServerTrusted(chain, authType, engine);
                checkRevoked(chain);
                return;
            }
            throw new CertificateException("admin listener does not act as a TLS client");
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            if (actsAsClient) {
                current.trust().checkServerTrusted(chain, authType);
                checkRevoked(chain);
                return;
            }
            throw new CertificateException("admin listener does not act as a TLS client");
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return current.anchors().clone();
        }
    }

    /**
     * Aliases carry the generation ({@code <gen>/<alias>}) so a handshake that chose an alias
     * just before a reload still gets the matching chain and key.
     */
    private final class Keys extends X509ExtendedKeyManager {
        private String tag(Material m, String alias) {
            return alias == null ? null : m.gen() + "/" + alias;
        }

        private Material of(String tagged) {
            var m = current;
            if (tagged == null || tagged.startsWith(m.gen() + "/")) {
                return m;
            }
            var p = previous;
            return p != null && tagged.startsWith(p.gen() + "/") ? p : null;
        }

        private String[] tagAll(Material m, String[] aliases) {
            if (aliases == null) {
                return null;
            }
            var out = new String[aliases.length];
            for (int i = 0; i < aliases.length; i++) {
                out[i] = tag(m, aliases[i]);
            }
            return out;
        }

        private static String untag(String tagged) {
            return tagged.substring(tagged.indexOf('/') + 1);
        }

        @Override
        public String[] getClientAliases(String keyType, Principal[] issuers) {
            if (!actsAsClient) {
                return null;
            }
            var m = current;
            return tagAll(m, m.keys().getClientAliases(keyType, issuers));
        }

        @Override
        public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
            if (!actsAsClient) {
                return null;
            }
            var m = current;
            return tag(m, m.keys().chooseClientAlias(keyType, issuers, socket));
        }

        @Override
        public String[] getServerAliases(String keyType, Principal[] issuers) {
            var m = current;
            return tagAll(m, m.keys().getServerAliases(keyType, issuers));
        }

        @Override
        public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
            var m = current;
            return tag(m, m.keys().chooseServerAlias(keyType, issuers, socket));
        }

        @Override
        public String chooseEngineServerAlias(String keyType, Principal[] issuers, SSLEngine engine) {
            var m = current;
            return tag(m, m.keys().chooseEngineServerAlias(keyType, issuers, engine));
        }

        @Override
        public X509Certificate[] getCertificateChain(String alias) {
            var m = of(alias);
            return m == null || alias == null ? null : m.keys().getCertificateChain(untag(alias));
        }

        @Override
        public PrivateKey getPrivateKey(String alias) {
            var m = of(alias);
            return m == null || alias == null ? null : m.keys().getPrivateKey(untag(alias));
        }
    }
}
