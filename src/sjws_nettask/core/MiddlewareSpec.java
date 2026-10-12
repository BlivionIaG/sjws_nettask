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

import java.util.Map;
import java.util.Objects;

/**
 * One entry of the {@code middleware} list a configuration file will supply. This server does
 * not parse that file; the configuration workstream maps each entry onto this record and passes
 * the list to {@link RequestFilters#addAll}.
 * <p>
 * Shape of that section (ordered list, global entries and per-route entries together):
 * <pre>
 * middleware:
 *   - name: access-log          # required; matches RequestFilters.factory
 *     order: 0                  # optional, default 0; lower runs first (outermost)
 *     options:                  # optional string map, interpreted by that filter
 *       format: combined
 *   - name: reverse-proxy
 *     order: 100
 *     route: /api/*             # optional; omitted, blank or "*" = every request
 *     options:
 *       upstream: http://127.0.0.1:9000
 * </pre>
 * The same {@code name} may appear more than once (different route or options). Equal
 * {@code order} values keep the list order. See {@link RoutePattern} for the route syntax.
 *
 * @param name filter id, trimmed; matched against {@link RequestFilters#factory}
 * @param order lower runs first on the way in and last on the way out
 * @param route path pattern, or null for every request ({@link RoutePattern})
 * @param options filter-defined string values; never null, may be empty, unmodifiable
 */
public record MiddlewareSpec(String name, int order, String route, Map<String, String> options) {

    public MiddlewareSpec {
        Objects.requireNonNull(name, "name");
        name = name.trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("filter name is required");
        }
        if (route != null) {
            var trimmed = route.trim();
            route = trimmed.isEmpty() ? null : trimmed;
        }
        options = options == null || options.isEmpty() ? Map.of() : Map.copyOf(options);
    }

    /** Global filter, order 0, no options. */
    public static MiddlewareSpec of(String name) {
        return new MiddlewareSpec(name, 0, null, Map.of());
    }

    public static MiddlewareSpec of(String name, int order, String route, Map<String, String> options) {
        return new MiddlewareSpec(name, order, route, options);
    }

    /** @return the option value, or null when absent */
    public String option(String key) {
        return this.options.get(key);
    }
}
