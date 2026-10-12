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

import java.util.Map;
import java.util.Objects;

/**
 * One entry of the {@code middleware} list. The same shape as
 * {@code core.MiddlewareSpec.of(name, order, route, options)} on the filter-chain workstream:
 * this package does not reference that class. The list is stored on the snapshot and not applied.
 * An empty list is no chain.
 * <p>
 * {@code route} is null for every request (omitted, blank, or {@code *}). Otherwise it is
 * {@code /exact}, {@code /prefix/*}, or {@code /*}. Equal {@code order} values keep file order;
 * a lower order is further out. The same {@code name} may appear more than once.
 *
 * @param options filter-defined string values; never null
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

    public static MiddlewareSpec of(String name, int order, String route, Map<String, String> options) {
        return new MiddlewareSpec(name, order, route, options);
    }
}
