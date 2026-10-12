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

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import sjws_nettask.core.Handler;
import sjws_nettask.core.RequestFilters;
import sjws_nettask.core.StaticFileHandler;

/**
 * Immutable snapshot of the {@code routing} config section (schema in docs/design/proxy-routing.md
 * §6). This is a thin adapter: the config workstream parses the file into this record, and
 * {@link #terminal()} is what goes to {@code SjwsServer.start(config, h3Factory, filters, terminal)}.
 *
 * @param enabled false (the default) builds nothing: {@link #terminal()} returns null
 * @param forwardedProto "http", "https", or null for "auto" (from {@code request.secure()})
 * @param trustedProxies CIDRs whose X-Forwarded-For / Forwarded are extended
 * @param upstreams named upstreams
 * @param routes ordered, first match wins
 */
public record RoutingConfig(boolean enabled, String forwardedProto, List<String> trustedProxies,
        Map<String, Upstream> upstreams, List<RouteSpec> routes) {

    public static final RoutingConfig DISABLED = new RoutingConfig(false, null, List.of(), Map.of(), List.of());

    public record Upstream(URI url, Duration connectTimeout, Duration responseTimeout, int maxBody) {

        public Upstream(URI url) {
            this(url, Duration.ofSeconds(5), Duration.ofSeconds(30), 16 << 20);
        }
    }

    public record Redirect(int status, String location, boolean keepPath) {
    }

    /**
     * Exactly one of {@code proxy}, {@code redirect}, {@code statics}.
     *
     * @param filters per-route filters, or null; wrap this route's handler only
     */
    public record RouteSpec(String host, String prefix, String rewrite, String proxy, Redirect redirect,
            boolean statics, RequestFilters filters) {
    }

    public RoutingConfig {
        trustedProxies = List.copyOf(trustedProxies);
        upstreams = Map.copyOf(upstreams);
        routes = List.copyOf(routes);
    }

    /**
     * Validates and builds the handler tree. One {@link ProxyHandler} (and connection pool) per
     * upstream, shared by its routes.
     *
     * @throws IllegalArgumentException for an unknown upstream, zero or several actions, bad values
     */
    public Router build(StaticFileHandler files) {
        var cidrs = this.trustedProxies.stream().map(Cidr::parse).toList();
        var proxies = new HashMap<String, ProxyHandler>();
        for (var e : this.upstreams.entrySet()) {
            var u = e.getValue();
            proxies.put(e.getKey(), new ProxyHandler(new ProxyHandler.Settings(u.url(), u.connectTimeout(),
                    u.responseTimeout(), u.maxBody(), this.forwardedProto, cidrs)));
        }
        var built = new ArrayList<Router.Route>();
        for (var r : this.routes) {
            int actions = (r.proxy() != null ? 1 : 0) + (r.redirect() != null ? 1 : 0) + (r.statics() ? 1 : 0);
            if (actions != 1) {
                throw new IllegalArgumentException("route " + r.prefix() + ": exactly one of proxy, redirect, static");
            }
            Handler h;
            if (r.proxy() != null) {
                h = proxies.get(r.proxy());
                if (h == null) {
                    throw new IllegalArgumentException("route " + r.prefix() + ": unknown upstream " + r.proxy());
                }
            } else if (r.redirect() != null) {
                var d = r.redirect();
                h = new RedirectHandler(d.status(), d.location(), d.keepPath());
            } else {
                h = Objects.requireNonNull(files, "files");
            }
            if (r.filters() != null) {
                h = r.filters().wrapping(h);
            }
            built.add(new Router.Route(r.host(), r.prefix(), r.rewrite(), h));
        }
        return new Router(built, files);
    }

    /** @return the terminal selector for {@code SjwsServer.start}, or null when disabled (zero cost) */
    public Function<StaticFileHandler, Handler> terminal() {
        if (!this.enabled) {
            return null;
        }
        return this::build;
    }
}
