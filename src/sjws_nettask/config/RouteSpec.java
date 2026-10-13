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

import sjws_nettask.routing.Router;

/**
 * One route, in declaration order. Maps onto {@code Route(host, prefix, rewrite, handler)} from
 * the proxy workstream: {@code host} is null when the route matches every host, {@code rewrite}
 * is null when the path is left unchanged, and {@link Action} is the handler kind. Matching uses
 * the same rules as {@link Router} and is not called from a transport.
 *
 * @param host lower-case exact host, or the lower-case pattern including {@code *.} when
 *        {@code wildcard} is true; null matches every host
 * @param hostSuffix for a wildcard, the dotted suffix ({@code *.example.com} stores
 *        {@code .example.com}); null otherwise
 * @param errors how server-generated error pages of this route are rendered
 * @param rateLimit {@code rate_limit:} of this route, or null
 */
public record RouteSpec(String host, boolean wildcard, String hostSuffix, String prefix, String rewrite,
        RouteSpec.Action action, RouteSpec.Errors errors, sjws_nettask.routing.RateLimitFilter.Limit rateLimit) {

    public RouteSpec {
        java.util.Objects.requireNonNull(errors, "errors");
    }

    /** No rate limit. */
    public RouteSpec(String host, boolean wildcard, String hostSuffix, String prefix, String rewrite, Action action,
            Errors errors) {
        this(host, wildcard, hostSuffix, prefix, rewrite, action, errors, null);
    }

    /** HTML error pages (the default). */
    public RouteSpec(String host, boolean wildcard, String hostSuffix, String prefix, String rewrite, Action action) {
        this(host, wildcard, hostSuffix, prefix, rewrite, action, Errors.HTML);
    }

    /**
     * {@code errors: html | problem} and {@code problem_type}.
     *
     * @param problem render supported statuses as RFC 9457 {@code application/problem+json}
     * @param typeBase URI the status is appended to for {@code type}; null gives "about:blank"
     */
    public record Errors(boolean problem, String typeBase) {

        public static final Errors HTML = new Errors(false, null);
    }

    /** Exactly one of proxy, backends, redirect or static. */
    public sealed interface Action permits Action.Proxy, Action.Weighted, Action.Redirect, Action.Static {

        /** {@code proxy: <upstream name>} resolved to the upstream record. */
        record Proxy(Upstream upstream) implements Action {
        }

        /**
         * {@code backends: [{upstream, weight, pin}]} with an optional {@code sticky} key.
         *
         * @param sticky header or cookie whose value is hashed to pick a backend; null picks at random
         */
        record Weighted(java.util.List<Backend> backends, sjws_nettask.routing.WeightedHandler.Match sticky)
                implements Action {
            public Weighted {
                backends = java.util.List.copyOf(backends);
            }
        }

        /** @param pin header or cookie test that sends a request here regardless of weight; may be null */
        record Backend(Upstream upstream, int weight, sjws_nettask.routing.WeightedHandler.Match pin) {
        }

        /**
         * @param status one of 301, 302, 303, 307, 308
         * @param keepPath append the request path after {@code location}
         */
        record Redirect(int status, String location, boolean keepPath) implements Action {
        }

        /**
         * {@code static: true}: the document root.
         *
         * @param listing {@code listing:} (default true); false answers directories without
         *        index.html with 404
         */
        record Static(boolean listing) implements Action {

            public Static() {
                this(true);
            }
        }
    }

    /** {@link Router#pathMatches} on the raw (still percent-encoded) request target. */
    public boolean matchesPath(String target) {
        return target != null && Router.pathMatches(this.prefix, target, Router.pathEnd(target));
    }

    /** {@link Router#hostMatches} against {@link #routerHost()}. Null pattern matches all. */
    public boolean matchesHost(String hostHeader) {
        return Router.hostMatches(routerHost(), hostHeader);
    }

    /**
     * The host as {@link Router.Route} takes it: an IPv6 literal ({@code ::1} in the file) is
     * bracketed ({@code [::1]}), as it appears in a Host header.
     */
    public String routerHost() {
        return this.host != null && !this.wildcard && this.host.indexOf(':') >= 0 ? "[" + this.host + "]"
                : this.host;
    }
}
