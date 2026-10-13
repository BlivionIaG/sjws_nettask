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

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.util.HexFormat;
import java.util.List;
import sjws_nettask.config.ConfigCodec;
import sjws_nettask.config.ConfigDocument;

/**
 * Offline root plus one online Ed25519 key. The root signs the trust bundle. The online key signs
 * snapshots. Callers pin the root public key outside {@code stateDir}.
 */
public final class KeyRing {

    private final PrivateKey rootPrivate;
    private final byte[] rootPublic;
    private final PrivateKey onlinePrivate;
    private final TrustKey online;
    private final long versionFloor;
    private final byte[] trustSignature;
    private final SecureRandom random = new SecureRandom();
    private final TrustBundle bundle;
    private final String rootFingerprint;
    private final boolean insecure;

    private KeyRing(PrivateKey rootPrivate, byte[] rootPublic, PrivateKey onlinePrivate, TrustKey online,
            long versionFloor, byte[] trustSignature) {
        this(rootPrivate, rootPublic, onlinePrivate, online, versionFloor, trustSignature, null, null, false);
    }

    private KeyRing(PrivateKey rootPrivate, byte[] rootPublic, PrivateKey onlinePrivate, TrustKey online,
            long versionFloor, byte[] trustSignature, TrustBundle bundle, String rootFingerprint, boolean insecure) {
        this.bundle = bundle;
        this.rootFingerprint = rootFingerprint;
        this.insecure = insecure;
        this.rootPrivate = rootPrivate;
        this.rootPublic = rootPublic;
        this.onlinePrivate = onlinePrivate;
        this.online = online;
        this.versionFloor = versionFloor;
        this.trustSignature = trustSignature;
    }

    public static KeyRing generate(String keyId, long notAfterMillis, long versionMin, long versionMax) {
        return generate(keyId, notAfterMillis, versionMin, versionMax, 0L);
    }

    public static KeyRing generate(String keyId, long notAfterMillis, long versionMin, long versionMax,
            long versionFloor) {
        var root = pair();
        var online = pair();
        var key = new TrustKey(keyId, online.getPublic().getEncoded(), notAfterMillis, versionMin, versionMax);
        var input = SnapshotCrypto.trustInput(versionFloor, List.of(key));
        var signature = sign(root.getPrivate(), input);
        var spki = root.getPublic().getEncoded();
        // A generated ring also carries a root-signed trust bundle (serial 1), so the watch wire is the same for
        // generated (tests, ceremony) and loaded rings.
        TrustBundle bundle;
        try {
            bundle = TrustBundle.parse(TrustBundle.sign(root.getPrivate(), spki, 1L, Math.min(0L, notAfterMillis - 1), notAfterMillis, versionFloor,
                    List.of(key)), spki);
        } catch (CpKeyException e) {
            throw new IllegalStateException("generated trust bundle does not parse: " + e.getMessage(), e);
        }
        return new KeyRing(root.getPrivate(), spki, online.getPrivate(), key, versionFloor, signature, bundle,
                TrustBundle.fingerprint(spki), false);
    }

    /**
     * Loads this voter's ring from disk, fail closed (control-plane-hosting.md 2.2/2.3): StrictModes
     * permission checks via {@code SecureKeyFile}, {@code root.pub} pin, strict root-signed
     * {@code trust.bundle}, anti-rollback against {@code trust.state} in both {@code stateDir} and
     * {@code keyDir} (the higher wins) and {@code cp.minBundleSerial}, 90-day validity cap, and
     * {@code online.key} matching an unexpired bundle key. Both floors are advanced before return.
     * Nothing is generated. Also the SIGHUP path: build a new ring with this, then swap.
     */
    public static KeyRing load(CpKeyConfig config) throws CpKeyException {
        return KeyRingLoader.load(config, System.currentTimeMillis());
    }

    static KeyRing load(CpKeyConfig config, long nowMillis) throws CpKeyException {
        return KeyRingLoader.load(config, nowMillis);
    }

    static KeyRing loaded(byte[] rootSpki, PrivateKey online, TrustKey key, TrustBundle bundle, String fingerprint,
            boolean insecure) {
        var sig = bundle.encoded();
        return new KeyRing(null, rootSpki.clone(), online, key, bundle.versionFloor(),
                java.util.Arrays.copyOfRange(sig, sig.length - TrustBundle.SIGNATURE_BYTES, sig.length), bundle,
                fingerprint, insecure);
    }

    /** The root-signed trust bundle: the loaded {@code trust.bundle}, or serial 1 for a {@link #generate generated} ring. */
    public TrustBundle bundle() {
        return bundle;
    }

    /** Hex SHA-256 of the root SPKI. */
    public String rootFingerprint() {
        return rootFingerprint;
    }

    /** True when loaded with {@code cp.insecureSkipPermissionChecks}; surface on {@code /cp/v1/health}. */
    public boolean insecurePermissions() {
        return insecure;
    }

    public byte[] rootPublic() {
        return rootPublic.clone();
    }

    public TrustKey online() {
        return online;
    }

    public long versionFloor() {
        return versionFloor;
    }

    public byte[] trustSignature() {
        return trustSignature.clone();
    }

    public List<TrustKey> keys() {
        return bundle == null ? List.of(online) : bundle.keys();
    }

    /**
     * @param version envelope version. A rollback keeps the retained document bytes, so this can be
     *        higher than {@link ConfigDocument#bundleVersion()}
     */
    public SignedSnapshot sign(ConfigDocument document, long version, long epoch, long raftIndex, long issuedAtMillis,
            long expiresAtMillis, long rollbackOf) {
        var yaml = ConfigCodec.text(document);
        var hash = hash(yaml);
        var nonce = new byte[16];
        random.nextBytes(nonce);
        var unsigned = new SignedSnapshot(document.schemaVersion(), version, epoch, raftIndex, 0L,
                SnapshotCrypto.KIND_FULL, "", hash, issuedAtMillis, expiresAtMillis, nonce, online.keyId(),
                new byte[0], rollbackOf, yaml);
        var signature = sign(onlinePrivate, SnapshotCrypto.snapshotInput(unsigned));
        return unsigned.withSignature(signature);
    }

    public static byte[] hash(String yaml) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(yaml.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String hashHex(String yaml) {
        return HexFormat.of().formatHex(hash(yaml));
    }

    private static byte[] sign(PrivateKey key, byte[] input) {
        try {
            var sig = Signature.getInstance("Ed25519");
            sig.initSign(key);
            sig.update(input);
            return sig.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static KeyPair pair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
