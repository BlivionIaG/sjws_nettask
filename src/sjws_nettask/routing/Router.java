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
import java.util.Objects;
import sjws_nettask.core.Handler;
import sjws_nettask.core.Request;
import sjws_nettask.core.Response;
import sjws_nettask.core.StaticFileHandler;

/**
 * Routes a request to the first matching {@link Route} (virtual host + path prefix), optionally
 * rewriting the prefix, or to the fallback handler when none matches.
 * <p>
 * Matching does not allocate: it works on the raw (still percent-encoded) target and Host value.
 * A target with a {@code ..} path segment (also percent-encoded, or delimited by {@code ;},
 * a backslash or an encoded slash) is answered 400 before any route
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
            if (host != null && (host.isEmpty() || host.equals("*.") || host.indexOf(':') >= 0 && !host.startsWith("["))) {
                throw new IllegalArgumentException("bad host: " + host);
            }
            host = host == null ? null : host.toLowerCase(Locale.ROOT);
        }

        public Route(String prefix, Handler handler) {
            this(null, prefix, null, handler);
        }

        /** Allocation-free: compares {@code host} with the request Host up to its port. */
        boolean matchesHost(String requestHost) {
            if (this.host == null) {
                return true;
            }
            if (requestHost == null) {
                return false;
            }
            int end = hostEnd(requestHost);
            if (this.host.startsWith("*.")) {
                int suffix = this.host.length() - 1; // ".example.com"
                return end > suffix && requestHost.regionMatches(true, end - suffix, this.host, 1, suffix);
            }
            return end == this.host.length() && requestHost.regionMatches(true, 0, this.host, 0, end);
        }

        /** Allocation-free: {@code pathEnd} is the index of '?' in the target, or its length. */
        boolean matchesPath(String target, int pathEnd) {
            int n = this.prefix.length();
            if (n > pathEnd || !target.startsWith(this.prefix)) {
                return false;
            }
            return n == pathEnd || this.prefix.charAt(n - 1) == '/' || target.charAt(n) == '/';
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

    private final Route[] routes;
    private final Handler fallback;

    /** @param fallback used when no route matches; null answers 404 */
    public Router(List<Route> routes, Handler fallback) {
        this.routes = routes.toArray(Route[]::new);
        this.fallback = fallback;
    }

    public List<Route> routes() {
        return List.of(this.routes);
    }

    @Override
    public Response handle(Request request) {
        var target = request.target();
        int q = target.indexOf('?');
        int pathEnd = q < 0 ? target.length() : q;
        if (hasDotDot(target, pathEnd)) {
            return StaticFileHandler.error(400, request.headOnly());
        }
        var host = request.headers().host();
        for (var r : this.routes) {
            if (r.matchesPath(target, pathEnd) && r.matchesHost(host)) {
                return r.handler().handle(r.rewrite() == null ? request : request.withTarget(r.rewriteTarget(target)));
            }
        }
        if (this.fallback != null) {
            return this.fallback.handle(request);
        }
        return StaticFileHandler.error(404, request.headOnly());
    }

    /** End of the host name: before ":port", or after the "]" of an IPv6 literal. */
    static int hostEnd(String host) {
        if (host.startsWith("[")) {
            int close = host.indexOf(']');
            return close < 0 ? host.length() : close + 1;
        }
        int colon = host.indexOf(':');
        return colon < 0 ? host.length() : colon;
    }

    /**
     * True if a path segment of {@code target[0, end)} is "..", with either dot possibly "%2e".
     * Segments are also split at the separators an upstream may decode or honour ({@code \\},
     * {@code %2f}, {@code %5c}), and a segment ends at {@code ;} (path parameters: some servlet
     * containers read {@code ..;x} as {@code ..}), so none of these spellings can step out of a
     * route prefix once the target is forwarded.
     */
    static boolean hasDotDot(String target, int end) {
        int i = 0;
        while (i <= end) {
            int j = dot(target, i, end);
            if (j > 0) {
                j = dot(target, j, end);
                if (j > 0 && (j == end || target.charAt(j) == ';' || separator(target, j, end) > 0)) {
                    return true;
                }
            }
            // next segment
            int k = i;
            int next = -1;
            while (k < end) {
                int sep = separator(target, k, end);
                if (sep > 0) {
                    next = k + sep;
                    break;
                }
                k++;
            }
            if (next < 0) {
                return false;
            }
            i = next;
        }
        return false;
    }

    /** @return the length of a segment separator at {@code i} ("/", "\\", "%2f", "%5c"), or 0 */
    private static int separator(String s, int i, int end) {
        char c = s.charAt(i);
        if (c == '/' || c == '\\') {
            return 1;
        }
        if (c == '%' && i + 3 <= end) {
            char a = s.charAt(i + 1);
            char b = Character.toLowerCase(s.charAt(i + 2));
            if ((a == '2' && b == 'f') || (a == '5' && b == 'c')) {
                return 3;
            }
        }
        return 0;
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
