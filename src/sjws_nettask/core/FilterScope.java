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
 * Where one filter entry applies. A route pattern is tested per request (the same
 * {@link RoutePattern} match the chain already uses, which allocates nothing). A label selector
 * is decided once, when the snapshot is compiled, against the data-plane node's labels.
 * <p>
 * Both may be set; the entry is installed only when the node matches, and then only for requests
 * that match the route. Null route and null labels match every request on every node.
 *
 * @param route canonical {@link RoutePattern} text, or null for every request
 * @param labels canonical {@link LabelSelector} text, or null for every node
 */
public record FilterScope(String route, String labels) {

    public FilterScope {
        route = canonicalRoute(route);
        labels = LabelSelector.parse(labels).canonical();
    }

    /** Every request on every node. */
    public static FilterScope all() {
        return new FilterScope(null, null);
    }

    public static FilterScope route(String route) {
        return new FilterScope(route, null);
    }

    public static FilterScope labels(String labels) {
        return new FilterScope(null, labels);
    }

    public static FilterScope of(String route, String labels) {
        return new FilterScope(route, labels);
    }

    private static String canonicalRoute(String route) {
        if (route == null) {
            return null;
        }
        var trimmed = route.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        var pattern = RoutePattern.parse(trimmed);
        return pattern.isAll() ? null : trimmed;
    }
}
