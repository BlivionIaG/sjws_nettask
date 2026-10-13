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
package sjws_nettask.gateway;

import java.util.Objects;
import sjws_nettask.core.RequestFilters;

/**
 * Binds the gateway filter factories. Calling this does not put anything on the request path:
 * a factory runs only when a document entry names it, and a server that never opens
 * {@link sjws_nettask.SjwsServer#chainControl} does not load this class.
 */
public final class GatewayFilters {

    private GatewayFilters() {
    }

    /** Local token bucket for {@code rate-limit}. */
    public static RequestFilters install(RequestFilters registry) {
        return install(registry, RateLimitFilter.TokenBucket::create);
    }

    /**
     * @param quotas builds the quota for each {@code rate-limit} entry. The request path calls
     *        {@link Quota#tryAcquire} only.
     */
    public static RequestFilters install(RequestFilters registry, Quota.Factory quotas) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(quotas, "quotas");
        registry.factory("api-key", ApiKeyFilter::create);
        registry.factory("jwt", JwtFilter::create);
        registry.factory("rate-limit", spec -> RateLimitFilter.create(spec, quotas));
        registry.factory("cors", CorsFilter::create);
        registry.factory("request-size", RequestSizeFilter::create);
        registry.factory("header-rewrite", HeaderRewriteFilter::create);
        return registry;
    }
}
