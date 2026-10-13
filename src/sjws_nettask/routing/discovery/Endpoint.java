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

import java.util.Map;
import java.util.Objects;

/**
 * One instance of a service, as the control plane (or the config file) reports it.
 *
 * @param id stable identity across sets (pools and outlier state follow {@code address:port},
 *        the id is for logs and the control plane)
 * @param address IP literal or host name (resolved by the connection pool)
 * @param port 1..65535
 * @param weight relative share for {@link LbPolicy#WEIGHTED_RANDOM}, 0..1000000. A {@code SUSPECT}
 *        endpoint is used with weight 0 whatever this says
 * @param labels free-form, never null
 * @param health control-plane view: {@code DEAD} endpoints are dropped from the set,
 *        {@code SUSPECT} ones stay but get weight 0 (picked only when nothing else is healthy)
 * @param zone locality hint, may be null (not used for selection yet)
 */
public record Endpoint(String id, String address, int port, int weight, Map<String, String> labels, Health health,
        String zone) {

    /** Control-plane health. Passive ejection on the gateway is separate and local. */
    public enum Health { READY, SUSPECT, DEAD }

    public Endpoint {
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(health, "health");
        if (address.isBlank()) {
            throw new IllegalArgumentException("endpoint address is empty");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("endpoint port must be 1..65535");
        }
        if (weight < 0 || weight > 1_000_000) {
            throw new IllegalArgumentException("endpoint weight must be 0..1000000");
        }
        labels = labels == null ? Map.of() : Map.copyOf(labels);
        if (id == null || id.isBlank()) {
            id = authority(address, port);
        }
    }

    /** A ready endpoint with weight 1, no labels and no zone. */
    public static Endpoint of(String address, int port) {
        return new Endpoint(null, address, port, 1, Map.of(), Health.READY, null);
    }

    /** {@code address:port}, bracketing an IPv6 literal: the key pools and outlier state follow. */
    public String authority() {
        return authority(this.address, this.port);
    }

    private static String authority(String address, int port) {
        return (address.indexOf(':') >= 0 && !address.startsWith("[") ? "[" + address + "]" : address) + ":" + port;
    }

    /** Weight the balancer uses: 0 for {@code SUSPECT}. */
    public int effectiveWeight() {
        return this.health == Health.SUSPECT ? 0 : this.weight;
    }
}
