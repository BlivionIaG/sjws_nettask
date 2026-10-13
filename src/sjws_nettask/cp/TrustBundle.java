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

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/**
 * The root-signed {@code trust.bundle} (control-plane-hosting.md section 2.1). Canonical, versioned,
 * domain-separated binary; parsed strictly.
 *
 * <pre>
 * "sjws-cp-trust-bundle\0" || u8 formatVersion || u8 purpose(=1 trust-bundle)
 *  || u64 bundleSerial || u64 issuedAt || u64 notAfter || u64 versionFloor
 *  || u16 n || n x ( lp keyId || lp SPKI || u64 notAfter || u64 versionMin || u64 versionMax || u8 purpose(=2) )
 *  || lp rootKeyId || 64-byte Ed25519 root signature over all bytes above
 * </pre>
 * {@code lp} is a u32 length prefix. {@code rootKeyId} is the hex SHA-256 of the root SPKI.
 */
public record TrustBundle(long bundleSerial, long issuedAtMillis, long notAfterMillis, long versionFloor,
        List<TrustKey> keys, String rootKeyId, byte[] encoded) {

    static final byte[] DOMAIN = "sjws-cp-trust-bundle\0".getBytes(StandardCharsets.US_ASCII);
    static final byte FORMAT_VERSION = 1;
    static final byte PURPOSE_BUNDLE = 1;
    static final byte PURPOSE_SNAPSHOT_SIGNING = 2;
    static final int MAX_KEYS = 64;
    static final int MAX_KEY_ID = 128;
    static final int MAX_SPKI = 128;
    static final int SIGNATURE_BYTES = 64;
    static final int MAX_BYTES = 64 * 1024;

    public TrustBundle {
        keys = List.copyOf(keys);
        encoded = encoded.clone();
    }

    @Override
    public byte[] encoded() {
        return encoded.clone();
    }

    /** SHA-256 of the exact file bytes, hex. */
    public String sha256() {
        return fingerprint(encoded);
    }

    /** Ceremony and test helper: canonical bytes signed by {@code rootPrivate}. Keys must be sorted by keyId. */
    public static byte[] sign(PrivateKey rootPrivate, byte[] rootSpki, long bundleSerial, long issuedAtMillis,
            long notAfterMillis, long versionFloor, List<TrustKey> keys) {
        var body = body(bundleSerial, issuedAtMillis, notAfterMillis, versionFloor, keys, fingerprint(rootSpki));
        try {
            var sig = Signature.getInstance("Ed25519");
            sig.initSign(rootPrivate);
            sig.update(body);
            var signature = sig.sign();
            var out = Arrays.copyOf(body, body.length + signature.length);
            System.arraycopy(signature, 0, out, body.length, signature.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Strict parse plus root signature and {@code rootKeyId} check. Expiry and anti-rollback are the caller's. */
    public static TrustBundle parse(byte[] bytes, byte[] rootSpki) throws CpKeyException {
        if (bytes.length > MAX_BYTES || bytes.length < DOMAIN.length + SIGNATURE_BYTES) {
            throw new CpKeyException("trust.bundle has an invalid size");
        }
        int bodyLength = bytes.length - SIGNATURE_BYTES;
        var buf = ByteBuffer.wrap(bytes, 0, bodyLength);
        try {
            var domain = new byte[DOMAIN.length];
            buf.get(domain);
            if (!Arrays.equals(domain, DOMAIN)) {
                throw new CpKeyException("trust.bundle has the wrong domain label");
            }
            if (buf.get() != FORMAT_VERSION || buf.get() != PURPOSE_BUNDLE) {
                throw new CpKeyException("trust.bundle has an unknown format version or purpose");
            }
            long serial = buf.getLong();
            long issuedAt = buf.getLong();
            long notAfter = buf.getLong();
            long floor = buf.getLong();
            if (serial < 0 || floor < 0 || notAfter <= issuedAt) {
                throw new CpKeyException("trust.bundle has invalid serial, floor or validity");
            }
            int n = Short.toUnsignedInt(buf.getShort());
            if (n == 0 || n > MAX_KEYS) {
                throw new CpKeyException("trust.bundle key count out of range");
            }
            var keys = new ArrayList<TrustKey>(n);
            String previous = null;
            for (int i = 0; i < n; i++) {
                var keyId = new String(lp(buf, MAX_KEY_ID), StandardCharsets.UTF_8);
                var spki = lp(buf, MAX_SPKI);
                long keyNotAfter = buf.getLong();
                long vMin = buf.getLong();
                long vMax = buf.getLong();
                if (buf.get() != PURPOSE_SNAPSHOT_SIGNING) {
                    throw new CpKeyException("trust.bundle key has an unknown purpose");
                }
                if (previous != null && previous.compareTo(keyId) >= 0) {
                    throw new CpKeyException("trust.bundle keyIds are not sorted and unique");
                }
                previous = keyId;
                try {
                    keys.add(new TrustKey(keyId, spki, keyNotAfter, vMin, vMax));
                } catch (IllegalArgumentException e) {
                    throw new CpKeyException("trust.bundle key is invalid");
                }
            }
            var rootKeyId = new String(lp(buf, MAX_KEY_ID), StandardCharsets.US_ASCII);
            if (buf.hasRemaining()) {
                throw new CpKeyException("trust.bundle has trailing bytes");
            }
            if (!rootKeyId.equals(fingerprint(rootSpki))) {
                throw new CpKeyException("trust.bundle was not issued for the pinned root");
            }
            PublicKey root = SnapshotCrypto.publicKey(rootSpki);
            var signature = Arrays.copyOfRange(bytes, bodyLength, bytes.length);
            if (!SnapshotCrypto.verify(root, Arrays.copyOf(bytes, bodyLength), signature)) {
                throw new CpKeyException("trust.bundle root signature does not verify");
            }
            return new TrustBundle(serial, issuedAt, notAfter, floor, keys, rootKeyId, bytes);
        } catch (BufferUnderflowException e) {
            throw new CpKeyException("trust.bundle is truncated");
        }
    }

    static String fingerprint(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] body(long serial, long issuedAt, long notAfter, long floor, List<TrustKey> keys,
            String rootKeyId) {
        int size = DOMAIN.length + 2 + 32 + 2;
        for (var k : keys) {
            size += 4 + k.keyId().getBytes(StandardCharsets.UTF_8).length + 4 + k.publicKey().length + 24 + 1;
        }
        var root = rootKeyId.getBytes(StandardCharsets.US_ASCII);
        size += 4 + root.length;
        var buf = ByteBuffer.allocate(size);
        buf.put(DOMAIN).put(FORMAT_VERSION).put(PURPOSE_BUNDLE);
        buf.putLong(serial).putLong(issuedAt).putLong(notAfter).putLong(floor);
        buf.putShort((short) keys.size());
        for (var k : keys) {
            var id = k.keyId().getBytes(StandardCharsets.UTF_8);
            buf.putInt(id.length).put(id);
            buf.putInt(k.publicKey().length).put(k.publicKey());
            buf.putLong(k.notAfterMillis()).putLong(k.versionMin()).putLong(k.versionMax());
            buf.put(PURPOSE_SNAPSHOT_SIGNING);
        }
        buf.putInt(root.length).put(root);
        return buf.array();
    }

    private static byte[] lp(ByteBuffer buf, int max) throws CpKeyException {
        int len = buf.getInt();
        if (len <= 0 || len > max || len > buf.remaining()) {
            throw new CpKeyException("trust.bundle field length out of range");
        }
        var out = new byte[len];
        buf.get(out);
        return out;
    }
}
