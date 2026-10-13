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
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import sjws_nettask.config.ConfigException;
import sjws_nettask.config.SecureKeyFile;

/** {@link KeyRing#load} (control-plane-hosting.md section 2.3). Every refusal throws; nothing is generated. */
final class KeyRingLoader {

    static final long MAX_VALIDITY_MILLIS = 90L * 24 * 3600 * 1000;
    static final long MAX_SKEW_MILLIS = 5L * 60 * 1000;
    static final String ROOT_PUB = "root.pub";
    static final String BUNDLE = "trust.bundle";
    static final String ONLINE_KEY = "online.key";
    private static final int MAX_KEY_BYTES = 16 * 1024;
    private static final System.Logger LOG = System.getLogger(KeyRing.class.getName());

    private KeyRingLoader() {
    }

    static KeyRing load(CpKeyConfig cfg, long now) throws CpKeyException {
        boolean insecure = cfg.insecureSkipPermissionChecks();
        if (insecure) {
            LOG.log(Level.WARNING, "cp.insecureSkipPermissionChecks is ON: owner/mode checks on control-plane key"
                    + " files are skipped (symlink and regular-file checks stay). Development only.");
        }
        var keyDir = checkDir(cfg.keyDir(), "cp.keyDir", true, insecure);
        var stateDir = checkDir(cfg.stateDir(), "cp.stateDir", false, insecure);

        // Root pin.
        var rootSpki = decodePem(readFile(cfg.rootPub(), ROOT_PUB, MAX_KEY_BYTES, false, insecure), "PUBLIC KEY");
        if (SnapshotCrypto.publicKey(rootSpki) == null) {
            throw new CpKeyException("root.pub is not an Ed25519 public key");
        }
        var fingerprint = TrustBundle.fingerprint(rootSpki);
        if (cfg.rootPubSha256() != null) {
            var want = cfg.rootPubSha256().replace(":", "").trim().toLowerCase(Locale.ROOT);
            if (!want.equals(fingerprint)) {
                throw new CpKeyException("root.pub fingerprint " + fingerprint + " does not match cp.rootPubSha256");
            }
        }
        LOG.log(Level.INFO, "cp root key fingerprint sha256=" + fingerprint);

        // Bundle: signature, validity, 90-day cap.
        var bundle = TrustBundle.parse(readFile(keyDir.resolve(BUNDLE), BUNDLE, TrustBundle.MAX_BYTES, false, insecure),
                rootSpki);
        if (now >= bundle.notAfterMillis()) {
            throw new CpKeyException("trust.bundle serial " + bundle.bundleSerial() + " has expired");
        }
        if (bundle.issuedAtMillis() > now + MAX_SKEW_MILLIS) {
            throw new CpKeyException("trust.bundle issuedAt is in the future");
        }
        if (bundle.notAfterMillis() - bundle.issuedAtMillis() > MAX_VALIDITY_MILLIS) {
            throw new CpKeyException("trust.bundle validity exceeds 90 days");
        }

        // Anti-rollback: max of the stateDir and keyDir floors, plus cp.minBundleSerial.
        var primary = TrustState.read(stateDir, insecure);
        var copy = TrustState.read(keyDir, insecure);
        if (primary != null && copy != null && !primary.equals(copy)) {
            LOG.log(Level.WARNING, "trust.state in cp.stateDir (serial " + primary.maxBundleSerial()
                    + ") and cp.keyDir (serial " + copy.maxBundleSerial() + ") differ; using the higher floor");
        } else if ((primary == null) != (copy == null)) {
            LOG.log(Level.WARNING, "trust.state missing in " + (primary == null ? "cp.stateDir" : "cp.keyDir")
                    + "; using the remaining copy");
        }
        var state = max(primary, copy);
        if (state == null) {
            if (cfg.minBundleSerial() == 0) {
                throw new CpKeyException("no trust.state in cp.stateDir or cp.keyDir. Refusing trust-on-first-use:"
                        + " set cp.minBundleSerial to the current bundle serial (or run `sjws-cp trust init`) to seed it");
            }
            LOG.log(Level.WARNING, "audit cp.trust.init: seeding trust.state from cp.minBundleSerial="
                    + cfg.minBundleSerial());
        }
        long serialFloor = Math.max(state == null ? 0 : state.maxBundleSerial(), cfg.minBundleSerial());
        long versionFloor = state == null ? 0 : state.maxVersionFloor();
        if (bundle.bundleSerial() < serialFloor) {
            throw new CpKeyException("trust.bundle serial " + bundle.bundleSerial() + " is below the floor "
                    + serialFloor + " (rollback refused)");
        }
        if (bundle.versionFloor() < versionFloor) {
            throw new CpKeyException("trust.bundle versionFloor " + bundle.versionFloor() + " is below "
                    + versionFloor + " (rollback refused)");
        }
        if (state != null && bundle.bundleSerial() == state.maxBundleSerial() && !state.bundleSha256().isEmpty()
                && !state.bundleSha256().equals(bundle.sha256())) {
            throw new CpKeyException("trust.bundle serial " + bundle.bundleSerial()
                    + " differs from the bundle already accepted with that serial");
        }

        // Online key: must match an unexpired bundle key with notAfter within 90 days of issue.
        var keyBytes = readFile(keyDir.resolve(ONLINE_KEY), ONLINE_KEY, MAX_KEY_BYTES, true, insecure);
        PrivateKey online;
        byte[] der = null;
        try {
            der = decodePem(keyBytes, "PRIVATE KEY");
            online = KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (GeneralSecurityException | CpKeyException e) {
            throw new CpKeyException("online.key is not an Ed25519 PKCS#8 key");
        } finally {
            Arrays.fill(keyBytes, (byte) 0);
            if (der != null) {
                Arrays.fill(der, (byte) 0);
            }
        }
        TrustKey match = matching(online, bundle);
        if (match == null) {
            throw new CpKeyException("online.key does not match any key in trust.bundle");
        }
        if (now >= match.notAfterMillis()) {
            throw new CpKeyException("online key " + match.keyId() + " has expired");
        }
        if (match.notAfterMillis() - bundle.issuedAtMillis() > MAX_VALIDITY_MILLIS) {
            throw new CpKeyException("online key " + match.keyId() + " notAfter exceeds 90 days");
        }

        // Advance both floors before the ring is used.
        var next = new TrustState(bundle.bundleSerial(), Math.max(versionFloor, bundle.versionFloor()), bundle.sha256());
        if (!next.equals(primary)) {
            next.write(stateDir);
        }
        if (!next.equals(copy)) {
            next.write(keyDir);
        }
        LOG.log(Level.INFO, "audit cp.trust.load: serial=" + bundle.bundleSerial() + " floor=" + bundle.versionFloor()
                + " key=" + match.keyId() + " root=" + fingerprint);
        return KeyRing.loaded(rootSpki, online, match, bundle, fingerprint, insecure);
    }

    private static TrustState max(TrustState a, TrustState b) {
        if (a == null || b == null) {
            return a == null ? b : a;
        }
        if (a.maxBundleSerial() != b.maxBundleSerial()) {
            var hi = a.maxBundleSerial() > b.maxBundleSerial() ? a : b;
            return new TrustState(hi.maxBundleSerial(), Math.max(a.maxVersionFloor(), b.maxVersionFloor()),
                    hi.bundleSha256());
        }
        var sha = a.bundleSha256().equals(b.bundleSha256()) ? a.bundleSha256() : "";
        return new TrustState(a.maxBundleSerial(), Math.max(a.maxVersionFloor(), b.maxVersionFloor()), sha);
    }

    private static TrustKey matching(PrivateKey online, TrustBundle bundle) {
        var challenge = new byte[32];
        new SecureRandom().nextBytes(challenge);
        byte[] signature;
        try {
            var sig = java.security.Signature.getInstance("Ed25519");
            sig.initSign(online);
            sig.update(challenge);
            signature = sig.sign();
        } catch (GeneralSecurityException e) {
            return null;
        }
        for (var key : bundle.keys()) {
            if (SnapshotCrypto.verify(SnapshotCrypto.publicKey(key.publicKey()), challenge, signature)) {
                return key;
            }
        }
        return null;
    }

    /** Real path of a key or state directory, checked StrictModes-style. */
    static Path checkDir(Path dir, String name, boolean ownerOnly, boolean insecure) throws CpKeyException {
        final Path real;
        try {
            real = dir.toRealPath();
            if (!Files.isDirectory(real)) {
                throw new CpKeyException(name + " is not a directory");
            }
            if (insecure) {
                return real;
            }
            SecureKeyFile.requireSecureParents(real, name);
            if (ownerOnly) {
                int mode = (Integer) Files.getAttribute(real, "unix:mode", LinkOption.NOFOLLOW_LINKS);
                int uid = (Integer) Files.getAttribute(real, "unix:uid", LinkOption.NOFOLLOW_LINKS);
                int self = (Integer) Files.getAttribute(Path.of("/proc/self"), "unix:uid");
                if (uid != self || (mode & 077) != 0) {
                    throw new CpKeyException(name + " must be owned by this process and mode 0700");
                }
            }
            return real;
        } catch (ConfigException e) {
            throw new CpKeyException(e.getMessage(), e);
        } catch (IOException | UnsupportedOperationException | IllegalArgumentException e) {
            throw new CpKeyException(name + " cannot be checked");
        }
    }

    /** Secret files via {@link SecureKeyFile#read}, trust files via {@link SecureKeyFile#readPublic}. */
    static byte[] readFile(Path path, String name, int max, boolean secret, boolean insecure) throws CpKeyException {
        if (!insecure) {
            try {
                return secret ? SecureKeyFile.read(path, name, max) : SecureKeyFile.readPublic(path, name, max);
            } catch (ConfigException e) {
                throw new CpKeyException(e.getMessage(), e);
            }
        }
        try {
            var attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attrs.isSymbolicLink()) {
                throw new CpKeyException(name + " is a symlink");
            }
            if (!attrs.isRegularFile()) {
                throw new CpKeyException(name + " is not a file");
            }
            if (attrs.size() > max) {
                throw new CpKeyException(name + " is too large");
            }
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new CpKeyException("Cannot read " + name);
        }
    }

    /** DER as is, or the base64 body of a {@code -----BEGIN <label>-----} block. */
    static byte[] decodePem(byte[] bytes, String label) throws CpKeyException {
        var text = new String(bytes, StandardCharsets.US_ASCII);
        var begin = "-----BEGIN " + label + "-----";
        int start = text.indexOf(begin);
        if (start < 0) {
            return bytes.clone();
        }
        int end = text.indexOf("-----END " + label + "-----", start);
        if (end < 0) {
            throw new CpKeyException("PEM " + label + " block is not terminated");
        }
        try {
            return Base64.getMimeDecoder().decode(text.substring(start + begin.length(), end));
        } catch (IllegalArgumentException e) {
            throw new CpKeyException("PEM " + label + " block is not base64");
        }
    }
}
