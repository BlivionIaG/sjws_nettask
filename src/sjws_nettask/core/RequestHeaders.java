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

/**
 * Request header fields the core understands. All fields are nullable (null = absent).
 * Every transport must populate every field it receives, from day one.
 *
 * @param ifNoneMatch If-None-Match
 * @param ifModifiedSince If-Modified-Since
 * @param ifRange If-Range
 * @param range Range
 * @param acceptEncoding Accept-Encoding
 * @param host Host (HTTP/1) or :authority (HTTP/3)
 */
public record RequestHeaders(String ifNoneMatch, String ifModifiedSince, String ifRange, String range,
        String acceptEncoding, String host) {

    public static final RequestHeaders NONE = new RequestHeaders(null, null, null, null, null, null);

    public static RequestHeaders ifModifiedSince(String value) {
        return new RequestHeaders(null, value, null, null, null, null);
    }
}
