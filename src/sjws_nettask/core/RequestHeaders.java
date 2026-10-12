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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Request header fields. The typed components are what the static file handler reads. {@link #fields}
 * is every field the transport received, lower-case name to values in first-seen order, pseudo-headers
 * excluded. The static file path does not call {@link #all} or walk {@link #fields}.
 * <p>
 * All typed fields are nullable (null = absent). Every transport must populate every typed field it
 * receives, from day one.
 *
 * @param ifNoneMatch If-None-Match (first value)
 * @param ifModifiedSince If-Modified-Since
 * @param ifRange If-Range
 * @param range Range
 * @param acceptEncoding Accept-Encoding
 * @param host Host (HTTP/1) or :authority (HTTP/3); :authority wins when both are present
 * @param fields raw fields, never null. A non-empty map is stored as given (not copied): pass an
 *        unmodifiable map the caller will not mutate. Empty or null becomes {@link Map#of()}
 */
public record RequestHeaders(String ifNoneMatch, String ifModifiedSince, String ifRange, String range,
        String acceptEncoding, String host, Map<String, List<String>> fields) {

    public static final RequestHeaders NONE = new RequestHeaders(null, null, null, null, null, null);

    public RequestHeaders {
        if (fields == null || fields.isEmpty()) {
            fields = Map.of();
        }
    }

    public RequestHeaders(String ifNoneMatch, String ifModifiedSince, String ifRange, String range,
            String acceptEncoding, String host) {
        this(ifNoneMatch, ifModifiedSince, ifRange, range, acceptEncoding, host, Map.of());
    }

    public static RequestHeaders ifModifiedSince(String value) {
        return new RequestHeaders(null, value, null, null, null, null);
    }

    /**
     * Typed view of {@code fields} plus the map itself. {@code fields} is not copied when it is
     * non-empty. Null is treated as no fields.
     */
    public static RequestHeaders of(Map<String, List<String>> fields) {
        return of(fields, null);
    }

    /**
     * @param hostOverride wins over a {@code host} field when non-null (HTTP/3 {@code :authority})
     */
    public static RequestHeaders of(Map<String, List<String>> fields, String hostOverride) {
        var source = fields == null ? Map.<String, List<String>>of() : fields;
        var host = hostOverride != null ? hostOverride : first(source, "host");
        return new RequestHeaders(first(source, "if-none-match"), first(source, "if-modified-since"),
                first(source, "if-range"), first(source, "range"), first(source, "accept-encoding"), host, source);
    }

    /** Every value of {@code name}, or an empty list. Does not allocate when the name is absent. */
    public List<String> values(String name) {
        var found = this.fields.get(name);
        return found == null ? List.of() : found;
    }

    /**
     * Name/value pairs in first-seen name order, values of one name kept together. Allocates.
     * Not for the static-file hot path.
     */
    public List<Map.Entry<String, String>> all() {
        if (this.fields.isEmpty()) {
            return List.of();
        }
        var flat = new ArrayList<Map.Entry<String, String>>();
        for (var entry : this.fields.entrySet()) {
            for (var value : entry.getValue()) {
                flat.add(Map.entry(entry.getKey(), value));
            }
        }
        return Collections.unmodifiableList(flat);
    }

    private static String first(Map<String, List<String>> fields, String name) {
        var values = fields.get(name);
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.get(0);
    }
}
