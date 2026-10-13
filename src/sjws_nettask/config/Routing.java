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

import java.util.List;
import java.util.Map;

/**
 * Parsed {@code routing:} section. When {@code enabled} is false (the default) nothing on the
 * request path consults it. When it is true the table is still only stored: this server does not
 * proxy, redirect or dispatch.
 *
 * @param trustedProxies validated CIDR literals, declaration order
 * @param upstreams insertion order
 * @param rateLimit global {@code routing.rate_limit:} applied before any route, or null
 */
public record Routing(boolean enabled, ForwardedProto forwardedProto, List<String> trustedProxies,
        Map<String, Upstream> upstreams, RouteTable routes, sjws_nettask.routing.RateLimitFilter.Limit rateLimit) {

    public Routing {
        trustedProxies = List.copyOf(trustedProxies);
        upstreams = Map.copyOf(upstreams);
    }

    /** No global rate limit. */
    public Routing(boolean enabled, ForwardedProto forwardedProto, List<String> trustedProxies,
            Map<String, Upstream> upstreams, RouteTable routes) {
        this(enabled, forwardedProto, trustedProxies, upstreams, routes, null);
    }

    public static Routing disabled() {
        return new Routing(false, ForwardedProto.AUTO, List.of(), Map.of(), new RouteTable(List.of()));
    }
}
