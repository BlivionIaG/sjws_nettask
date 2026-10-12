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
package sjws_nettask.config;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC-SHA256 over {@code schema}, {@code version} and {@code canonicalHash}. The canonical hash
 * is recomputed from the document, so a signature over some other hash does not apply.
 */
public final class MacSnapshotVerifier implements SnapshotVerifier {

    private final byte[] key;

    public MacSnapshotVerifier(byte[] key) {
        if (key == null || key.length == 0) {
            throw new IllegalArgumentException("key");
        }
        this.key = key.clone();
    }

    /** Bytes the signature covers: schema, version, then the canonical hash, separated by newlines. */
    public static byte[] covered(int schema, long version, String canonicalHash) {
        return (schema + "\n" + version + "\n" + canonicalHash).getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] sign(byte[] key, ConfigDocument document) {
        return mac(key, covered(document.schemaVersion(), document.bundleVersion(), document.canonicalHash()));
    }

    @Override
    public void verify(ConfigDocument document) {
        var signature = document.signature();
        if (signature == null || signature.length == 0) {
            throw new ConfigException("missing signature");
        }
        var rendered = ConfigCodec.text(document);
        var hash = ConfigCodec.sha256(rendered);
        if (!hash.equals(document.canonicalHash())) {
            throw new ConfigException("canonical hash does not match the document");
        }
        var expect = mac(this.key, covered(document.schemaVersion(), document.bundleVersion(), hash));
        if (!MessageDigest.isEqual(expect, signature)) {
            throw new ConfigException("bad signature");
        }
    }

    private static byte[] mac(byte[] key, byte[] covered) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(covered);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof MacSnapshotVerifier mac && Arrays.equals(this.key, mac.key);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(this.key);
    }
}
