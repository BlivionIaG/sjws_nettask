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
import java.util.HashMap;
import sjws_nettask.core.Handler;
import sjws_nettask.core.StaticFileHandler;
import sjws_nettask.routing.Cidr;
import sjws_nettask.routing.ProblemErrorHandler;
import sjws_nettask.routing.ProxyHandler;
import sjws_nettask.routing.RedirectHandler;
import sjws_nettask.routing.Router;
import sjws_nettask.routing.WeightedHandler;

/**
 * Builds the request-path handler for an enabled {@code routing:} section. Called once per
 * snapshot by {@link ConfigSnapshot#compile}, so a reload gets a fresh router (and fresh
 * upstream pools); requests never consult the parsed records.
 */
final class RoutingCompiler {

    private RoutingCompiler() {
    }

    /** One {@link ProxyHandler} (one connection pool) per upstream; unmatched requests go to {@code files}. */
    static Router compile(Routing routing, StaticFileHandler files) {
        var cidrs = routing.trustedProxies().stream().map(Cidr::parse).toList();
        var proto = switch (routing.forwardedProto()) {
            case HTTP -> "http";
            case HTTPS -> "https";
            case AUTO -> null;
        };
        var proxies = new HashMap<String, ProxyHandler>();
        var routes = new ArrayList<Router.Route>();
        for (var spec : routing.routes().routes()) {
            Handler handler = switch (spec.action()) {
                case RouteSpec.Action.Proxy p -> shared(proxies, p.upstream(), proto, cidrs);
                case RouteSpec.Action.Weighted w -> {
                    var backends = new ArrayList<WeightedHandler.Backend>(w.backends().size());
                    for (var b : w.backends()) {
                        backends.add(new WeightedHandler.Backend(shared(proxies, b.upstream(), proto, cidrs),
                                b.weight(), b.pin()));
                    }
                    yield new WeightedHandler(backends, w.sticky());
                }
                case RouteSpec.Action.Redirect r -> new RedirectHandler(r.status(), r.location(), r.keepPath());
                case RouteSpec.Action.Static s -> files.withListings(s.listing());
            };
            if (spec.rateLimit() != null) { // inside the problem wrapper, so a 429 renders like the route's other errors
                handler = limited(spec.rateLimit(), handler);
            }
            var errors = spec.errors();
            if (errors.problem()) { // opt-in wrapper; it applies the rewrite so "instance" is the client's path
                handler = new ProblemErrorHandler(handler, spec.prefix(), spec.rewrite(), errors.typeBase());
                routes.add(new Router.Route(spec.routerHost(), spec.prefix(), null, handler));
            } else {
                routes.add(new Router.Route(spec.routerHost(), spec.prefix(), spec.rewrite(), handler));
            }
        }
        return new Router(routes, files);
    }

    /** The route's handler behind its own {@link sjws_nettask.routing.RateLimitFilter}. */
    static Handler limited(sjws_nettask.routing.RateLimitFilter.Limit limit, Handler handler) {
        return sjws_nettask.core.RequestFilters.create()
                .add(new sjws_nettask.routing.RateLimitFilter(limit))
                .wrapping(handler);
    }

    /** One pool per upstream, shared by every route and backend that names it. */
    private static ProxyHandler shared(java.util.Map<String, ProxyHandler> proxies, Upstream u, String proto,
            java.util.List<Cidr> cidrs) {
        var shared = proxies.get(u.name());
        if (shared == null) {
            shared = proxy(u, proto, cidrs);
            proxies.put(u.name(), shared);
        }
        return shared;
    }

    private static ProxyHandler proxy(Upstream u, String proto, java.util.List<Cidr> cidrs) {
        return new ProxyHandler(new ProxyHandler.Settings(u.url(), u.connectTimeout(), u.responseTimeout(),
                u.idleReadTimeout(), u.maxBody(), proto, cidrs, u.preserveHost(), 256),
                sjws_nettask.routing.ByteBudget.shared(), u.resilience());
    }

}
