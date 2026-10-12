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

import java.util.ArrayList;
import java.util.List;

/**
 * Routes in declaration order. The first match wins. {@link #match} does not allocate and is not
 * on the request path: transports keep serving the static handler until the proxy workstream
 * builds a router from these specs.
 */
public final class RouteTable {

    private final RouteSpec[] routes;

    public RouteTable(List<RouteSpec> routes) {
        this.routes = routes.toArray(RouteSpec[]::new);
    }

    public List<RouteSpec> routes() {
        return List.of(this.routes);
    }

    public int size() {
        return this.routes.length;
    }

    /** @return the first matching route, or null */
    public RouteSpec match(String hostHeader, String target) {
        for (var route : this.routes) {
            if (route.matchesHost(hostHeader) && route.matchesPath(target)) {
                return route;
            }
        }
        return null;
    }

    /**
     * Routes that can never match because an earlier route already covers their host and prefix.
     * Warnings, not errors.
     */
    public List<String> shadows() {
        var warnings = new ArrayList<String>();
        for (int later = 1; later < this.routes.length; later++) {
            for (int earlier = 0; earlier < later; earlier++) {
                if (covers(this.routes[earlier], this.routes[later])) {
                    warnings.add("routing.routes[" + later + "] is shadowed by routing.routes[" + earlier + "]");
                    break;
                }
            }
        }
        return List.copyOf(warnings);
    }

    static boolean covers(RouteSpec earlier, RouteSpec later) {
        return hostCovers(earlier, later) && prefixCovers(earlier.prefix(), later.prefix());
    }

    private static boolean hostCovers(RouteSpec earlier, RouteSpec later) {
        if (earlier.host() == null) {
            return true;
        }
        if (later.host() == null) {
            return false;
        }
        if (!earlier.wildcard()) {
            return !later.wildcard() && earlier.host().equals(later.host());
        }
        var suffix = earlier.hostSuffix();
        if (later.wildcard()) {
            return later.hostSuffix().equals(suffix) || later.hostSuffix().endsWith(suffix);
        }
        return later.host().length() > suffix.length() && later.host().endsWith(suffix);
    }

    static boolean prefixCovers(String earlier, String later) {
        if (earlier.equals("/")) {
            return true;
        }
        if (later.length() < earlier.length() || !later.startsWith(earlier)) {
            return false;
        }
        return later.length() == earlier.length() || later.charAt(earlier.length()) == '/';
    }
}
