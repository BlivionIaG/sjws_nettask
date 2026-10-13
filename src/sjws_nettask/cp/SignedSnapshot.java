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

import java.util.Arrays;
import java.util.HexFormat;

/**
 * One full signed snapshot. {@code kind} is always {@link SnapshotCrypto#KIND_FULL} and
 * {@code parentVersion} is always {@code 0}. The bytes in {@code canonicalYaml} are
 * {@code ConfigCodec.text}. There is no patch.
 *
 * @param rollbackOf retained version this snapshot restores, or {@code 0} when it is not a rollback
 */
public record SignedSnapshot(int schema, long version, long epoch, long raftIndex, long parentVersion, byte kind,
        String projectionId, byte[] canonicalHash, long issuedAtMillis, long expiresAtMillis, byte[] nonce,
        String keyId, byte[] signature, long rollbackOf, String canonicalYaml) {

    public SignedSnapshot {
        if (projectionId == null) {
            projectionId = "";
        }
        if (canonicalYaml == null) {
            canonicalYaml = "";
        }
        if (keyId == null) {
            keyId = "";
        }
        canonicalHash = canonicalHash == null ? new byte[0] : canonicalHash.clone();
        nonce = nonce == null ? new byte[0] : nonce.clone();
        signature = signature == null ? new byte[0] : signature.clone();
    }

    public String canonicalHashHex() {
        return HexFormat.of().formatHex(canonicalHash);
    }

    public SignedSnapshot withSignature(byte[] signature) {
        return new SignedSnapshot(schema, version, epoch, raftIndex, parentVersion, kind, projectionId, canonicalHash,
                issuedAtMillis, expiresAtMillis, nonce, keyId, signature, rollbackOf, canonicalYaml);
    }

    public SignedSnapshot withKind(byte kind) {
        return new SignedSnapshot(schema, version, epoch, raftIndex, parentVersion, kind, projectionId, canonicalHash,
                issuedAtMillis, expiresAtMillis, nonce, keyId, signature, rollbackOf, canonicalYaml);
    }

    public SignedSnapshot withParentVersion(long parentVersion) {
        return new SignedSnapshot(schema, version, epoch, raftIndex, parentVersion, kind, projectionId, canonicalHash,
                issuedAtMillis, expiresAtMillis, nonce, keyId, signature, rollbackOf, canonicalYaml);
    }

    public SignedSnapshot withSchema(int schema) {
        return new SignedSnapshot(schema, version, epoch, raftIndex, parentVersion, kind, projectionId, canonicalHash,
                issuedAtMillis, expiresAtMillis, nonce, keyId, signature, rollbackOf, canonicalYaml);
    }

    public SignedSnapshot withCanonicalYaml(String canonicalYaml) {
        return new SignedSnapshot(schema, version, epoch, raftIndex, parentVersion, kind, projectionId, canonicalHash,
                issuedAtMillis, expiresAtMillis, nonce, keyId, signature, rollbackOf, canonicalYaml);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof SignedSnapshot that)) {
            return false;
        }
        return schema == that.schema && version == that.version && epoch == that.epoch && raftIndex == that.raftIndex
                && parentVersion == that.parentVersion && kind == that.kind && rollbackOf == that.rollbackOf
                && issuedAtMillis == that.issuedAtMillis && expiresAtMillis == that.expiresAtMillis
                && projectionId.equals(that.projectionId) && keyId.equals(that.keyId)
                && canonicalYaml.equals(that.canonicalYaml) && Arrays.equals(canonicalHash, that.canonicalHash)
                && Arrays.equals(nonce, that.nonce) && Arrays.equals(signature, that.signature);
    }

    @Override
    public int hashCode() {
        return Long.hashCode(version) ^ Arrays.hashCode(canonicalHash);
    }
}
