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
package sjws_nettask.gateway;

import java.util.Base64;

/** Base64 and base64url, padding optional. Used when a gateway filter is compiled or a JWT arrives. */
final class Encoding {

    private Encoding() {
    }

    static byte[] base64(String raw) {
        if (raw == null || raw.isEmpty()) {
            throw new IllegalArgumentException("empty base64");
        }
        var s = raw.replace('-', '+').replace('_', '/');
        int mod = s.length() % 4;
        if (mod == 1) {
            throw new IllegalArgumentException("bad base64");
        }
        if (mod > 0) {
            s = s + "====".substring(mod);
        }
        try {
            return Base64.getDecoder().decode(s);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("bad base64");
        }
    }

    static String base64Url(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }
}
