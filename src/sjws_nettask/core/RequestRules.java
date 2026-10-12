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
package sjws_nettask.core;

import java.util.Set;

/**
 * Request-line rules shared by every transport, so that HTTP/1.1 and HTTP/3 accept and refuse the
 * same requests with the same status (security review S4).
 */
public final class RequestRules {

    /** Methods we recognise; of these only GET and HEAD are implemented (others: 405, unknown: 501). */
    public static final Set<String> KNOWN_METHODS = Set.of(
            "GET", "HEAD", "POST", "PUT", "DELETE", "CONNECT", "OPTIONS", "TRACE", "PATCH");

    /**
     * Connection-specific fields: meaningless in HTTP/3, where a request carrying one is malformed
     * (RFC 9114 §4.2). ({@code te} other than "trailers" is rejected by the HTTP/3 codec itself.)
     */
    public static final Set<String> CONNECTION_SPECIFIC = Set.of(
            "connection", "keep-alive", "proxy-connection", "transfer-encoding", "upgrade");

    /** Longest request target accepted (HTTP/1: the request line limit); longer gets 414. */
    public static final int MAX_TARGET_LENGTH = 8192;

    private RequestRules() {
    }

    /** @return 501 for an unknown method, else 0 */
    public static int checkMethod(String method) {
        return KNOWN_METHODS.contains(method) ? 0 : 501;
    }

    /**
     * @return 400 if the target has a character outside visible ASCII (0x21..0x7e: no space, CR/LF, other
     *         controls, DEL or raw non-ASCII; those must be percent-encoded), 414 if it is too long, else 0
     */
    public static int checkTarget(String target) {
        for (int i = 0; i < target.length(); i++) {
            char c = target.charAt(i);
            if (c <= 0x20 || c >= 0x7f) {
                return 400;
            }
        }
        return target.length() > MAX_TARGET_LENGTH ? 414 : 0;
    }

    /** @return the first non-zero of {@link #checkMethod} and {@link #checkTarget} (HTTP/1 order), or 0 */
    public static int check(String method, String target) {
        int status = checkMethod(method);
        return status != 0 ? status : checkTarget(target);
    }
}
