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
package sjws_nettask.routing.discovery;

import java.util.Locale;

/**
 * How {@code EndpointBalancer} picks an endpoint of a set. New policies are added as new
 * constants; a control plane that sends a name this build does not know gets
 * {@link #WEIGHTED_RANDOM} (see {@link #parse}), so an older gateway keeps serving.
 */
public enum LbPolicy {
    /** Default. Precomputed alias table over effective weights: O(1), no allocation. */
    WEIGHTED_RANDOM,
    /** Rotates over endpoints with a non-zero effective weight (weights otherwise ignored). */
    ROUND_ROBIN,
    /** Two random choices, the one with fewer requests awaiting response headers wins. */
    LEAST_REQUEST;

    /** Config / wire name: {@code weighted_random}, {@code round_robin}, {@code least_request}. */
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** @return the policy, or null when the name is unknown (the caller decides: config rejects, CP falls back) */
    public static LbPolicy parse(String name) {
        if (name == null) {
            return null;
        }
        for (var p : values()) {
            if (p.wireName().equals(name.trim().toLowerCase(Locale.ROOT))) {
                return p;
            }
        }
        return null;
    }

    /** {@link #parse} with the {@link #WEIGHTED_RANDOM} fallback, for control-plane input. */
    public static LbPolicy parseOrDefault(String name) {
        var p = parse(name);
        return p == null ? WEIGHTED_RANDOM : p;
    }
}
