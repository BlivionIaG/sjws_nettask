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

import java.util.Arrays;
import java.util.Objects;

/**
 * A full snapshot of one service's endpoints (never a delta). Immutable: the array is copied in
 * and {@link #ready()} hands out a copy; the balancer reads {@link #endpointAt} instead.
 *
 * @param service the upstream name in {@code routing.upstreams}
 * @param version monotonic per service; {@link UpstreamSource#update} ignores a stale or equal one
 * @param ready endpoints as reported; {@code DEAD} ones are dropped by {@link #withoutDead()}
 * @param policy null means {@link LbPolicy#WEIGHTED_RANDOM}
 */
public record UpstreamSet(String service, long version, Endpoint[] ready, LbPolicy policy) {

    public UpstreamSet {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(ready, "ready");
        ready = ready.clone();
        for (var e : ready) {
            Objects.requireNonNull(e, "endpoint");
        }
        if (policy == null) {
            policy = LbPolicy.WEIGHTED_RANDOM;
        }
    }

    @Override
    public Endpoint[] ready() {
        return this.ready.clone();
    }

    public int size() {
        return this.ready.length;
    }

    public Endpoint endpointAt(int i) {
        return this.ready[i];
    }

    /** The same set without {@code DEAD} endpoints (this instance when there are none). */
    public UpstreamSet withoutDead() {
        int dead = 0;
        for (var e : this.ready) {
            if (e.health() == Endpoint.Health.DEAD) {
                dead++;
            }
        }
        if (dead == 0) {
            return this;
        }
        var kept = new Endpoint[this.ready.length - dead];
        int k = 0;
        for (var e : this.ready) {
            if (e.health() != Endpoint.Health.DEAD) {
                kept[k++] = e;
            }
        }
        return new UpstreamSet(this.service, this.version, kept, this.policy);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof UpstreamSet s && s.version == this.version && s.service.equals(this.service)
                && s.policy == this.policy && Arrays.equals(s.ready, this.ready);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.service, this.version, this.policy, Arrays.hashCode(this.ready));
    }

    @Override
    public String toString() {
        return "UpstreamSet[" + this.service + " v" + this.version + " " + this.policy.wireName() + " "
                + Arrays.toString(this.ready) + "]";
    }
}
