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
package sjws_nettask.routing;

import java.util.List;
import java.util.Locale;

/**
 * Reference matcher for the differential test: the first-match linear scan that {@link Router}
 * used before {@link CompiledRoutes}, copied verbatim so a change to {@code Router} cannot change it.
 */
final class LinearRouter {

    private final String[] hosts;
    private final String[] prefixes;

    LinearRouter(List<Router.Route> routes) {
        this.hosts = routes.stream().map(Router.Route::host).toArray(String[]::new);
        this.prefixes = routes.stream().map(Router.Route::prefix).toArray(String[]::new);
    }

    int matchIndex(String requestHost, String target, int pathEnd) {
        for (int i = 0; i < this.hosts.length; i++) {
            if (matchesPath(this.prefixes[i], target, pathEnd) && matchesHost(this.hosts[i], requestHost)) {
                return i;
            }
        }
        return -1;
    }

    static boolean matchesHost(String host, String requestHost) {
        if (host == null) {
            return true;
        }
        if (requestHost == null) {
            return false;
        }
        host = host.toLowerCase(Locale.ROOT);
        int end = hostEnd(requestHost);
        if (host.startsWith("*.")) {
            int suffix = host.length() - 1;
            return end > suffix && requestHost.regionMatches(true, end - suffix, host, 1, suffix);
        }
        return end == host.length() && requestHost.regionMatches(true, 0, host, 0, end);
    }

    static boolean matchesPath(String prefix, String target, int pathEnd) {
        int n = prefix.length();
        if (n > pathEnd || !target.startsWith(prefix)) {
            return false;
        }
        return n == pathEnd || prefix.charAt(n - 1) == '/' || target.charAt(n) == '/';
    }

    static int hostEnd(String host) {
        if (host.startsWith("[")) {
            int close = host.indexOf(']');
            return close < 0 ? host.length() : close + 1;
        }
        int colon = host.indexOf(':');
        return colon < 0 ? host.length() : colon;
    }
}
