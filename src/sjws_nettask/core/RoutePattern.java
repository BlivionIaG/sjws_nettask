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
 * Path pattern for a per-route filter. Matching uses the raw request target with the query and
 * fragment cut off, and does not percent-decode ({@code %2F} stays {@code %2F}).
 * <ul>
 * <li>{@code null}, blank or {@code *} — every request</li>
 * <li>{@code /exact} — the path equals {@code /exact}</li>
 * <li>{@code /prefix/*} — the path equals {@code /prefix} or starts with {@code /prefix/}</li>
 * <li>{@code /*} — every path that starts with {@code /}</li>
 * </ul>
 * A {@code *} anywhere except that trailing form is rejected. The match itself allocates nothing.
 */
public final class RoutePattern {

    private enum Kind {
        ALL, EXACT, PREFIX
    }

    private static final RoutePattern ALL = new RoutePattern(Kind.ALL, "");

    private final Kind kind;
    /** Exact path, or the prefix with the trailing {@code /*} removed. Empty for {@link Kind#ALL}. */
    private final String path;

    private RoutePattern(Kind kind, String path) {
        this.kind = kind;
        this.path = path;
    }

    public static RoutePattern all() {
        return ALL;
    }

    /**
     * @param route raw route from configuration; null, blank and {@code *} match everything
     * @throws IllegalArgumentException when the pattern is not one of the forms above
     */
    public static RoutePattern parse(String route) {
        if (route == null) {
            return ALL;
        }
        var r = route.trim();
        if (r.isEmpty() || "*".equals(r)) {
            return ALL;
        }
        int star = r.indexOf('*');
        if (star >= 0) {
            // The only wildcard is a trailing "/*" (one star, nothing after it).
            if (star != r.length() - 1 || !r.endsWith("/*")) {
                throw new IllegalArgumentException("bad route: " + route);
            }
            var prefix = r.substring(0, r.length() - 2);
            if (prefix.isEmpty()) {
                prefix = "/"; // "/*" is every origin-form path
            }
            if (!prefix.startsWith("/")) {
                throw new IllegalArgumentException("bad route: " + route);
            }
            return new RoutePattern(Kind.PREFIX, prefix);
        }
        if (!r.startsWith("/")) {
            throw new IllegalArgumentException("bad route: " + route);
        }
        return new RoutePattern(Kind.EXACT, r);
    }

    public boolean isAll() {
        return this.kind == Kind.ALL;
    }

    /** @param target origin-form request target (path, optional query, optional fragment) */
    public boolean matches(String target) {
        if (this.kind == Kind.ALL) {
            return true;
        }
        int end = pathEnd(target);
        if (this.kind == Kind.EXACT) {
            return end == this.path.length() && target.regionMatches(0, this.path, 0, end);
        }
        int n = this.path.length();
        if (end < n || !target.regionMatches(0, this.path, 0, n)) {
            return false;
        }
        // "/*" is stored as "/": every path that starts with "/" matches, including "/x".
        if (n == 1) {
            return true;
        }
        return end == n || target.charAt(n) == '/';
    }

    /** Index of {@code ?} or {@code #}, or the length when neither is present. */
    static int pathEnd(String target) {
        int n = target.length();
        for (int i = 0; i < n; i++) {
            char c = target.charAt(i);
            if (c == '?' || c == '#') {
                return i;
            }
        }
        return n;
    }
}
