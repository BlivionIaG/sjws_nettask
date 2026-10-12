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

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Versioned filter chain a control plane can distribute as data. {@link #format} is the wire
 * schema; {@link #version} is the config generation. {@link FilterJson} is the stable UTF-8 JSON
 * encoding ({@link #toJson()}). Applying a document compiles an immutable {@link FilterChain} and
 * publishes it with one volatile write ({@link ChainControl}).
 * <p>
 * Wire object, format 1, canonical key order {@code format}, {@code version}, {@code filters}:
 * <pre>
 * {
 *   "format": 1,
 *   "version": 7,
 *   "filters": [
 *     {
 *       "type": "api-key",
 *       "order": 10,
 *       "params": { "header": "x-api-key", "keys": "a,b" },
 *       "scope": { "route": "/api/*", "labels": "edge" }
 *     }
 *   ]
 * }
 * </pre>
 * Each entry's {@code type} is the {@link RequestFilters#factory} name. {@code order} defaults to
 * 0 (lower runs first on the way in). {@code params} is a string map; numbers and booleans are not
 * accepted on the wire, so a round trip cannot change a value's type. {@code scope} may carry
 * {@code route} ({@link RoutePattern}), {@code labels} ({@link LabelSelector}), or both (AND).
 * Omitting {@code scope} matches every request on every node. An empty {@code filters} array is a
 * valid document and compiles to the terminal handler alone.
 *
 * @param format {@link #FORMAT}
 * @param version config generation, {@code >= 0}; a data plane rejects a version older than the one it is serving
 * @param filters entries in list order; equal {@code order} keeps that order
 */
public record FilterDocument(int format, long version, List<FilterEntry> filters) {

    /** Current wire schema. Unknown values are rejected and do not replace a live chain. */
    public static final int FORMAT = 1;

    /** Largest accepted JSON document, in bytes. Checked before parsing. */
    public static final int MAX_BYTES = 1 << 20;

    public FilterDocument {
        if (format != FORMAT) {
            throw new IllegalArgumentException("unsupported filter document format " + format);
        }
        if (version < 0) {
            throw new IllegalArgumentException("config version must be >= 0");
        }
        Objects.requireNonNull(filters, "filters");
        filters = List.copyOf(filters);
    }

    /** No filters, at {@code version}. Compiles to the terminal handler. */
    public static FilterDocument empty(long version) {
        return new FilterDocument(FORMAT, version, List.of());
    }

    public static FilterDocument of(long version, List<FilterEntry> filters) {
        return new FilterDocument(FORMAT, version, filters);
    }

    /** Canonical JSON text. {@link #fromJson(String)} of the result equals this document. */
    public String toJson() {
        return FilterJson.write(this);
    }

    /** UTF-8 bytes of {@link #toJson()}. */
    public byte[] toJsonBytes() {
        return FilterJson.bytes(this);
    }

    public static FilterDocument fromJson(String json) {
        return FilterJson.read(json);
    }

    /** Strict UTF-8. Malformed bytes are rejected. */
    public static FilterDocument fromJson(byte[] utf8) {
        return FilterJson.read(utf8);
    }

    /**
     * One filter in the document.
     *
     * @param type factory name, trimmed, non-empty
     * @param order lower runs first on the way in and last on the way out
     * @param params filter-defined strings; never null, may be empty, unmodifiable
     * @param scope where the entry applies; never null
     */
    public record FilterEntry(String type, int order, Map<String, String> params, FilterScope scope) {

        public FilterEntry {
            Objects.requireNonNull(type, "type");
            type = type.trim();
            if (type.isEmpty()) {
                throw new IllegalArgumentException("filter type is required");
            }
            if (params == null || params.isEmpty()) {
                params = Map.of();
            } else {
                for (var entry : params.entrySet()) {
                    if (entry.getKey() == null || entry.getKey().isEmpty() || entry.getValue() == null) {
                        throw new IllegalArgumentException("filter params require non-empty string keys and values");
                    }
                }
                params = Map.copyOf(params);
            }
            if (scope == null) {
                scope = FilterScope.all();
            }
        }

        public static FilterEntry of(String type, int order, Map<String, String> params, FilterScope scope) {
            return new FilterEntry(type, order, params, scope);
        }

        /** Global entry, order 0, no params. */
        public static FilterEntry of(String type) {
            return new FilterEntry(type, 0, Map.of(), FilterScope.all());
        }
    }
}
