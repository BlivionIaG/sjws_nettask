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
package sjws_nettask.http1;

import sjws_nettask.core.Request;
import sjws_nettask.core.RequestHeaders;

/** Maps a parsed HTTP/1.x {@link Http1Parser.Head} to the protocol-neutral core {@link Request}. */
public final class Http1Requests {

    private Http1Requests() {
    }

    public static Request toCore(Http1Parser.Head head) {
        return new Request(head.method(), head.target(), new RequestHeaders(head.header("if-none-match"),
                head.ifModifiedSince(), head.header("if-range"), head.header("range"),
                head.header("accept-encoding"), head.header("host")));
    }
}
