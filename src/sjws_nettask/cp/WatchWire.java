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
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Body of one {@code GET /cp/v1/watch} answer: the root-signed trust bundle, then the activated
 * {@link SignedSnapshot} in its {@code last-good.json} form. Nothing here is trusted on decode:
 * {@link DataApply#trusted} checks the bundle against the pin and {@link DataApply#decide} checks
 * the snapshot, both fail closed.
 */
public final class WatchWire {

    /** At most this many online keys in one bundle. */
    static final int MAX_KEYS = 16;

    private WatchWire() {
    }

    public record Message(long trustFloor, byte[] trustSignature, List<TrustKey> keys, SignedSnapshot snapshot) {
    }

    public static String encode(KeyRing keys, SignedSnapshot snapshot) {
        var b64 = Base64.getEncoder();
        var out = new StringBuilder(512 + snapshot.canonicalYaml().length());
        out.append("{\"versionFloor\":").append(keys.versionFloor()).append(",\"trustSignature\":")
                .append(LastGoodFiles.jsonString(b64.encodeToString(keys.trustSignature())))
                .append(",\"keyCount\":").append(keys.keys().size());
        for (int i = 0; i < keys.keys().size(); i++) {
            var k = keys.keys().get(i);
            out.append(",\"k").append(i).append(".keyId\":").append(LastGoodFiles.jsonString(k.keyId()))
                    .append(",\"k").append(i).append(".publicKey\":")
                    .append(LastGoodFiles.jsonString(b64.encodeToString(k.publicKey())))
                    .append(",\"k").append(i).append(".notAfter\":").append(k.notAfterMillis())
                    .append(",\"k").append(i).append(".versionMin\":").append(k.versionMin())
                    .append(",\"k").append(i).append(".versionMax\":").append(k.versionMax());
        }
        out.append(",\"snapshot\":").append(LastGoodFiles.snapshotJson(snapshot)).append('}');
        return out.toString();
    }

    public static Message decode(String json) throws IOException {
        try {
            var b64 = Base64.getDecoder();
            long floor = LastGoodFiles.readLong(json, "versionFloor");
            var signature = b64.decode(LastGoodFiles.readString(json, "trustSignature"));
            long count = LastGoodFiles.readLong(json, "keyCount");
            if (count < 1 || count > MAX_KEYS) {
                throw new IOException("keyCount");
            }
            var keys = new ArrayList<TrustKey>();
            for (int i = 0; i < count; i++) {
                keys.add(new TrustKey(LastGoodFiles.readString(json, "k" + i + ".keyId"),
                        b64.decode(LastGoodFiles.readString(json, "k" + i + ".publicKey")),
                        LastGoodFiles.readLong(json, "k" + i + ".notAfter"),
                        LastGoodFiles.readLong(json, "k" + i + ".versionMin"),
                        LastGoodFiles.readLong(json, "k" + i + ".versionMax")));
            }
            int at = json.indexOf("\"snapshot\":");
            if (at < 0) {
                throw new IOException("missing snapshot");
            }
            return new Message(floor, signature, List.copyOf(keys),
                    DataApply.parseSnapshot(json.substring(at + "\"snapshot\":".length())));
        } catch (IllegalArgumentException e) {
            throw new IOException("watch message: " + e.getMessage(), e);
        }
    }
}
