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
 * Body of one {@code GET /cp/v1/watch} answer: the root-signed trust bundle ({@link TrustBundle} bytes), then the activated
 * {@link SignedSnapshot} in its {@code last-good.json} form. Nothing here is trusted on decode:
 * {@link DataApply#trusted} checks the bundle against the pin and {@link DataApply#decide} checks
 * the snapshot, both fail closed.
 */
public final class WatchWire {

    private WatchWire() {
    }

    /**
     * @param bundle the exact root-signed {@link TrustBundle} bytes ({@code KeyRing.bundle().encoded()}); not
     *        trusted until {@link DataApply#trusted} parses it against the pinned root
     */
    public record Message(byte[] bundle, SignedSnapshot snapshot) {
        public Message {
            bundle = bundle.clone();
        }

        @Override
        public byte[] bundle() {
            return bundle.clone();
        }
    }

    public static String encode(KeyRing keys, SignedSnapshot snapshot) {
        var bundle = keys.bundle();
        if (bundle == null) {
            throw new IllegalStateException("key ring has no trust bundle");
        }
        var out = new StringBuilder(512 + snapshot.canonicalYaml().length());
        out.append("{\"bundle\":").append(LastGoodFiles.jsonString(Base64.getEncoder().encodeToString(bundle.encoded())))
                .append(",\"snapshot\":").append(LastGoodFiles.snapshotJson(snapshot)).append('}');
        return out.toString();
    }

    public static Message decode(String json) throws IOException {
        try {
            var text = LastGoodFiles.readString(json, "bundle");
            // Base64 of MAX_BYTES; checked before decoding so an oversized field allocates nothing large.
            if (text.length() > (TrustBundle.MAX_BYTES + 2) / 3 * 4) {
                throw new IOException("bundle size");
            }
            var bundle = Base64.getDecoder().decode(text);
            int at = json.indexOf("\"snapshot\":");
            if (at < 0) {
                throw new IOException("missing snapshot");
            }
            return new Message(bundle, DataApply.parseSnapshot(json.substring(at + "\"snapshot\":".length())));
        } catch (IllegalArgumentException e) {
            throw new IOException("watch message: " + e.getMessage(), e);
        }
    }
}
