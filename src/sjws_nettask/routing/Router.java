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
import java.util.Objects;
import sjws_nettask.core.Handler;
import sjws_nettask.core.Request;
import sjws_nettask.core.Response;
import sjws_nettask.core.StaticFileHandler;

/**
 * Routes a request to the first matching {@link Route} (virtual host + path prefix), optionally
 * rewriting the prefix, or to the fallback handler when none matches.
 * <p>
 * Matching does not allocate: tables with more than {@link #LINEAR_MAX} routes are compiled
 * once into hash tables ({@link CompiledRoutes}) with the same first-match result. It works on the raw (still percent-encoded) target and Host value.
 * A target with a {@code ..} path segment (also percent-encoded) is answered 400 before any route
 * is tried, so a prefix can never be escaped. Immutable and thread-safe.
 */
public final class Router implements Handler {

    /**
     * One rule.
     *
     * @param host exact host ("example.com"), wildcard ("*.example.com", sub-domains only), or null
     *        for any host; compared case-insensitively, port ignored
     * @param prefix path prefix starting with "/"; matches on a segment boundary ("/api" matches
     *        "/api", "/api/x" and "/api?q", not "/apix")
     * @param rewrite replacement for the matched prefix (e.g. "/" strips "/api"), or null
     * @param handler where matching requests go (wrap it with {@code RequestFilters.wrapping} for
     *        per-route filters)
     */
    public record Route(String host, String prefix, String rewrite, Handler handler) {

        public Route {
            Objects.requireNonNull(prefix, "prefix");
            Objects.requireNonNull(handler, "handler");
            if (!prefix.startsWith("/") || (rewrite != null && !rewrite.startsWith("/"))) {
                throw new IllegalArgumentException("prefix and rewrite must start with /");
            }
            if (prefix.indexOf('?') >= 0 || prefix.indexOf('#') >= 0) {
                throw new IllegalArgumentException("prefix must not contain ? or #: " + prefix);
            }
            if (host != null && (host.isEmpty() || host.equals("*.") || host.endsWith(".")
                    || host.indexOf(':') >= 0 && !host.startsWith("[")
                    || host.startsWith("[") && (!host.endsWith("]") || host.indexOf(']') != host.length() - 1))) {
                throw new IllegalArgumentException("bad host: " + host);
            }
            host = host == null ? null : asciiLower(host);
        }

        public Route(String prefix, Handler handler) {
            this(null, prefix, null, handler);
        }

        /** Allocation-free; see {@link Router#hostMatches}. */
        boolean matchesHost(String requestHost) {
            return hostMatches(this.host, requestHost);
        }

        /** Allocation-free; see {@link Router#pathMatches}. */
        boolean matchesPath(String target, int pathEnd) {
            return pathMatches(this.prefix, target, pathEnd);
        }

        String rewriteTarget(String target) {
            var rest = target.substring(this.prefix.length());
            if (this.rewrite.endsWith("/") && rest.startsWith("/")) {
                rest = rest.substring(1);
            } else if (!this.rewrite.endsWith("/") && !rest.isEmpty() && !rest.startsWith("/") && !rest.startsWith("?")) {
                rest = "/" + rest;
            }
            return this.rewrite + rest;
        }
    }

    /** Up to this many routes a linear scan is used; above it, {@link CompiledRoutes}. */
    static final int LINEAR_MAX = 8;

    private final Route[] routes;
    private final Handler fallback;
    /** Null when the table is small enough to scan. */
    private final CompiledRoutes compiled;

    /** @param fallback used when no route matches; null answers 404 */
    public Router(List<Route> routes, Handler fallback) {
        this(routes, fallback, routes.size() > LINEAR_MAX);
    }

    Router(List<Route> routes, Handler fallback, boolean compile) {
        this.routes = routes.toArray(Route[]::new);
        this.fallback = fallback;
        this.compiled = compile ? new CompiledRoutes(this.routes) : null;
    }

    public List<Route> routes() {
        return List.of(this.routes);
    }

    @Override
    public Response handle(Request request) {
        var target = request.target();
        int pathEnd = pathEnd(target);
        if (hasDotDot(target, pathEnd)) {
            return StaticFileHandler.error(400, request.headOnly());
        }
        int i = matchIndex(request.headers().host(), target, pathEnd);
        if (i >= 0) {
            var r = this.routes[i];
            return r.handler().handle(r.rewrite() == null ? request : request.withTarget(r.rewriteTarget(target)));
        }
        if (this.fallback != null) {
            return this.fallback.handle(request);
        }
        return StaticFileHandler.error(404, request.headOnly());
    }

    /**
     * Declaration index of the first route matching {@code host} and {@code target[0, pathEnd)},
     * or -1. Does not allocate.
     */
    int matchIndex(String host, String target, int pathEnd) {
        if (this.compiled != null) {
            return this.compiled.match(host, target, pathEnd);
        }
        for (int i = 0; i < this.routes.length; i++) {
            var r = this.routes[i];
            if (r.matchesPath(target, pathEnd) && r.matchesHost(host)) {
                return i;
            }
        }
        return -1;
    }

    /** Index of the first {@code ?} in {@code target}, or its length: where the path ends. */
    public static int pathEnd(String target) {
        int q = target.indexOf('?');
        return q < 0 ? target.length() : q;
    }

    /**
     * The path rule shared by the request path and config checks ({@code RouteSpec},
     * {@code RouteTable.shadows()}): {@code target[0, pathEnd)} starts with {@code prefix}
     * (case-sensitive, raw percent-encoded), and the prefix ends at the path end, ends in
     * {@code /}, or is followed by {@code /}. Allocation-free.
     */
    public static boolean pathMatches(String prefix, String target, int pathEnd) {
        int n = prefix.length();
        if (n > pathEnd || !target.startsWith(prefix)) {
            return false;
        }
        return n == pathEnd || prefix.charAt(n - 1) == '/' || target.charAt(n) == '/';
    }

    /** True when every target {@code later} matches is also matched by {@code earlier}. */
    public static boolean prefixCovers(String earlier, String later) {
        return pathMatches(earlier, later, later.length());
    }

    /**
     * The host rule shared by the request path and config checks. {@code routeHost} is null (any
     * request, also without Host), a lower-case exact name or {@code [v6]} literal, or
     * {@code *.suffix} (a sub-domain at any depth, never the apex). Compared with ASCII-only case
     * folding against the request host up to {@link #hostEnd}: port stripped, one trailing dot
     * ignored, and an unclosed {@code [} matches no host-specific route. Allocation-free.
     */
    public static boolean hostMatches(String routeHost, String requestHost) {
        if (routeHost == null) {
            return true;
        }
        if (requestHost == null) {
            return false;
        }
        int end = hostEnd(requestHost);
        if (end < 0) {
            return false;
        }
        if (routeHost.startsWith("*.")) {
            int suffix = routeHost.length() - 1; // ".example.com"
            return end > suffix && asciiRegionEquals(requestHost, end - suffix, routeHost, 1, suffix);
        }
        return end == routeHost.length() && asciiRegionEquals(requestHost, 0, routeHost, 0, end);
    }

    /**
     * End of the host name in a Host value: before {@code :port} and one trailing dot, or after
     * the {@code ]} of an IPv6 literal (a port is only stripped after it). -1 for an unclosed
     * {@code [}.
     */
    public static int hostEnd(String host) {
        if (host.startsWith("[")) {
            int close = host.indexOf(']');
            return close < 0 ? -1 : close + 1;
        }
        int colon = host.indexOf(':');
        int end = colon < 0 ? host.length() : colon;
        return end > 0 && host.charAt(end - 1) == '.' ? end - 1 : end;
    }

    /** {@code a[aFrom, aFrom+n)} equals {@code b[bFrom, bFrom+n)} folding only A-Z to a-z. */
    static boolean asciiRegionEquals(String a, int aFrom, String b, int bFrom, int n) {
        for (int i = 0; i < n; i++) {
            if (asciiLower(a.charAt(aFrom + i)) != asciiLower(b.charAt(bFrom + i))) {
                return false;
            }
        }
        return true;
    }

    static char asciiLower(char c) {
        return c >= 'A' && c <= 'Z' ? (char) (c + 32) : c;
    }

    static String asciiLower(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                var chars = s.toCharArray();
                for (int j = i; j < chars.length; j++) {
                    chars[j] = asciiLower(chars[j]);
                }
                return new String(chars);
            }
        }
        return s;
    }

    /** True if a path segment of {@code target[0, end)} is "..", with either dot possibly "%2e". */
    static boolean hasDotDot(String target, int end) {
        int i = 0;
        while (i < end) {
            int segEnd = target.indexOf('/', i);
            if (segEnd < 0 || segEnd > end) {
                segEnd = end;
            }
            int j = dot(target, i, segEnd);
            if (j > 0) {
                j = dot(target, j, segEnd);
                if (j == segEnd) {
                    return true;
                }
            }
            i = segEnd + 1;
        }
        return false;
    }

    /** @return the index after a "." or "%2e" at {@code i}, or -1 */
    private static int dot(String s, int i, int end) {
        if (i < end && s.charAt(i) == '.') {
            return i + 1;
        }
        if (i + 3 <= end && s.charAt(i) == '%' && s.charAt(i + 1) == '2'
                && (s.charAt(i + 2) == 'e' || s.charAt(i + 2) == 'E')) {
            return i + 3;
        }
        return -1;
    }
}
