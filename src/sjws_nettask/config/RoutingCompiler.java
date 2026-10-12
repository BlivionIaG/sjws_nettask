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
import sjws_nettask.routing.ProxyHandler;
import sjws_nettask.routing.RedirectHandler;
import sjws_nettask.routing.Router;

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
                case RouteSpec.Action.Proxy p -> {
                    var shared = proxies.get(p.upstream().name());
                    if (shared == null) {
                        shared = proxy(p.upstream(), proto, cidrs);
                        proxies.put(p.upstream().name(), shared);
                    }
                    yield shared;
                }
                case RouteSpec.Action.Redirect r -> new RedirectHandler(r.status(), r.location(), r.keepPath());
                case RouteSpec.Action.Static s -> files;
            };
            routes.add(new Router.Route(spec.host(), spec.prefix(), spec.rewrite(), handler));
        }
        return new Router(routes, files);
    }

    private static ProxyHandler proxy(Upstream u, String proto, java.util.List<Cidr> cidrs) {
        return new ProxyHandler(new ProxyHandler.Settings(u.url(), u.connectTimeout(), u.responseTimeout(),
                u.idleReadTimeout(), u.maxBody(), proto, cidrs, u.preserveHost(), 256));
    }

}
