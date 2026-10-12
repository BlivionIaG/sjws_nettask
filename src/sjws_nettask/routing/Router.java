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

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import sjws_nettask.core.Body;
import sjws_nettask.core.Handler;
import sjws_nettask.core.Request;
import sjws_nettask.core.Response;
import sjws_nettask.core.ResponseHeaders;

/**
 * Routes a request to the first matching {@link Route} (virtual host + path prefix), optionally
 * rewriting the prefix, or to the fallback handler when none matches.
 * <p>
 * Immutable and thread-safe; a plain {@link Handler}, so it composes with decorators.
 */
public final class Router implements Handler {

    /**
     * One rule.
     *
     * @param host exact host ("example.com"), wildcard ("*.example.com", matches sub-domains only),
     *        or null for any host; compared case-insensitively, port ignored
     * @param prefix path prefix starting with "/"; matches on a segment boundary ("/api" matches
     *        "/api" and "/api/x", not "/apix")
     * @param rewrite replacement for the matched prefix (e.g. "/" strips "/api"), or null to keep the target
     * @param handler where matching requests go
     */
    public record Route(String host, String prefix, String rewrite, Handler handler) {

        public Route {
            Objects.requireNonNull(prefix, "prefix");
            Objects.requireNonNull(handler, "handler");
            if (!prefix.startsWith("/") || (rewrite != null && !rewrite.startsWith("/"))) {
                throw new IllegalArgumentException("prefix and rewrite must start with /");
            }
            host = host == null ? null : host.toLowerCase(Locale.ROOT);
        }

        public Route(String prefix, Handler handler) {
            this(null, prefix, null, handler);
        }

        boolean matchesHost(String requestHost) {
            if (this.host == null) {
                return true;
            }
            if (requestHost == null) {
                return false;
            }
            var h = stripPort(requestHost).toLowerCase(Locale.ROOT);
            if (this.host.startsWith("*.")) {
                return h.endsWith(this.host.substring(1));
            }
            return h.equals(this.host);
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
            if (this.rewrite == null) {
                return target;
            }
            var rest = target.substring(this.prefix.length());
            if (this.rewrite.endsWith("/") && rest.startsWith("/")) {
                rest = rest.substring(1);
            } else if (!this.rewrite.endsWith("/") && !rest.isEmpty() && !rest.startsWith("/") && !rest.startsWith("?")) {
                rest = "/" + rest;
            }
            return this.rewrite + rest;
        }
    }

    private final List<Route> routes;
    private final Handler fallback;

    /** @param fallback used when no route matches; null answers 404 */
    public Router(List<Route> routes, Handler fallback) {
        this.routes = List.copyOf(routes);
        this.fallback = fallback;
    }

    public List<Route> routes() {
        return this.routes;
    }

    @Override
    public Response handle(Request request) {
        var target = request.target();
        int q = target.indexOf('?');
        int pathEnd = q < 0 ? target.length() : q;
        for (var r : this.routes) {
            if (r.matchesHost(request.headers().host()) && r.matchesPath(target, pathEnd)) {
                var req = r.rewrite() == null ? request
                        : new Request(request.method(), r.rewriteTarget(target), request.headers());
                return r.handler().handle(req);
            }
        }
        if (this.fallback != null) {
            return this.fallback.handle(request);
        }
        return text(404, "Not Found", request.headOnly());
    }

    static String stripPort(String host) {
        if (host.startsWith("[")) {
            int end = host.indexOf(']');
            return end < 0 ? host : host.substring(0, end + 1);
        }
        int colon = host.indexOf(':');
        return colon < 0 ? host : host.substring(0, colon);
    }

    static Response text(int status, String message, boolean head) {
        var headers = ResponseHeaders.of().add("content-type", "text/plain; charset=utf-8");
        return new Response(status, headers, Body.Bytes.of((message + "\n").getBytes(StandardCharsets.UTF_8)), head);
    }
}
