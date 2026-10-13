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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.List;

/** Domain-separated Ed25519 inputs. Integers are big-endian. Strings are length-prefixed. */
public final class SnapshotCrypto {

    public static final byte[] SNAPSHOT_DOMAIN = "sjws-cp-snapshot-v1".getBytes(StandardCharsets.US_ASCII);
    public static final byte[] TRUST_DOMAIN = "sjws-cp-trust-v1".getBytes(StandardCharsets.US_ASCII);
    public static final byte KIND_FULL = 1;
    public static final long MAX_VERSION_JUMP = 1L << 20;

    private SnapshotCrypto() {
    }

    public static byte[] snapshotInput(SignedSnapshot snapshot) {
        var keyId = snapshot.keyId().getBytes(StandardCharsets.UTF_8);
        var projection = snapshot.projectionId().getBytes(StandardCharsets.UTF_8);
        var hash = snapshot.canonicalHash();
        var nonce = snapshot.nonce();
        var buffer = ByteBuffer.allocate(SNAPSHOT_DOMAIN.length + 4 + keyId.length + 8 + 4 + 8 + 8 + 8 + 1 + 4
                + projection.length + hash.length + 8 + 8 + nonce.length);
        buffer.put(SNAPSHOT_DOMAIN);
        putString(buffer, keyId);
        buffer.putLong(snapshot.version());
        buffer.putInt(snapshot.schema());
        buffer.putLong(snapshot.epoch());
        buffer.putLong(snapshot.raftIndex());
        buffer.putLong(snapshot.parentVersion());
        buffer.put(snapshot.kind());
        putString(buffer, projection);
        buffer.put(hash);
        buffer.putLong(snapshot.issuedAtMillis());
        buffer.putLong(snapshot.expiresAtMillis());
        buffer.put(nonce);
        return buffer.array();
    }

    public static byte[] trustInput(long versionFloor, List<TrustKey> keys) {
        int size = TRUST_DOMAIN.length + 8 + 4;
        for (var key : keys) {
            size += 4 + key.keyId().getBytes(StandardCharsets.UTF_8).length + 4 + key.publicKey().length + 8 + 8 + 8;
        }
        var buffer = ByteBuffer.allocate(size);
        buffer.put(TRUST_DOMAIN);
        buffer.putLong(versionFloor);
        buffer.putInt(keys.size());
        for (var key : keys) {
            putString(buffer, key.keyId().getBytes(StandardCharsets.UTF_8));
            putString(buffer, key.publicKey());
            buffer.putLong(key.notAfterMillis());
            buffer.putLong(key.versionMin());
            buffer.putLong(key.versionMax());
        }
        return buffer.array();
    }

    public static boolean verify(PublicKey key, byte[] input, byte[] signature) {
        if (key == null || input == null || signature == null || signature.length == 0) {
            return false;
        }
        try {
            var sig = Signature.getInstance("Ed25519");
            sig.initVerify(key);
            sig.update(input);
            return sig.verify(signature);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    public static PublicKey publicKey(byte[] spki) {
        try {
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(spki));
        } catch (GeneralSecurityException e) {
            return null;
        }
    }

    private static void putString(ByteBuffer buffer, byte[] raw) {
        buffer.putInt(raw.length);
        buffer.put(raw);
    }
}
