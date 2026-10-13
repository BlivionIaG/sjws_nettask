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
package sjws_nettask.ha;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import sjws_nettask.cluster.NodeId;

/**
 * Claims the VIP while this node leads {@code ha-control} and the lease is outside the fence
 * window. Releases before the lease expires so the next leader can claim without overlap.
 * Active/active never calls the hook.
 */
public final class VipFence {

    private final FailoverPolicy policy;
    private final NodeId self;
    private final VipHook hook;
    private final MetricsSink metrics;
    private final long releaseBeforeNanos;
    private boolean claimed;

    public VipFence(FailoverPolicy policy, NodeId self, VipHook hook, MetricsSink metrics, Duration releaseBefore) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.self = Objects.requireNonNull(self, "self");
        this.hook = Objects.requireNonNull(hook, "hook");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        Objects.requireNonNull(releaseBefore, "releaseBefore");
        if (releaseBefore.isNegative()) {
            throw new IllegalArgumentException("releaseBefore");
        }
        this.releaseBeforeNanos = releaseBefore.toNanos();
    }

    public static VipFence passive(NodeId self, VipHook hook, Duration releaseBefore) {
        return new VipFence(FailoverPolicy.ACTIVE_PASSIVE, self, hook, MetricsSink.NONE, releaseBefore);
    }

    public static VipFence active(NodeId self) {
        return new VipFence(FailoverPolicy.ACTIVE_ACTIVE, self, VipHook.NONE, MetricsSink.NONE, Duration.ZERO);
    }

    public boolean claimed() {
        return this.claimed;
    }

    public void onLeader(Optional<NodeId> leader) {
        if (this.policy != FailoverPolicy.ACTIVE_PASSIVE) {
            return;
        }
        if (leader != null && leader.isPresent() && leader.get().equals(this.self)) {
            claim();
        } else {
            release();
        }
    }

    /**
     * @param leader whether this node still believes it leads
     * @param now monotonic nanos
     * @param leaseExpiresAt lease deadline from the group, or {@code 0} when it is not leader
     */
    public void poll(boolean leader, long now, long leaseExpiresAt) {
        if (this.policy != FailoverPolicy.ACTIVE_PASSIVE) {
            return;
        }
        if (!leader || leaseExpiresAt == 0L || now >= leaseExpiresAt - this.releaseBeforeNanos) {
            release();
            return;
        }
        claim();
    }

    private void claim() {
        if (this.claimed) {
            return;
        }
        this.claimed = true;
        this.hook.claim(this.self);
        this.metrics.gauge("ha_role", 1);
    }

    private void release() {
        if (!this.claimed) {
            return;
        }
        this.claimed = false;
        this.hook.release(this.self);
        this.metrics.gauge("ha_role", 0);
    }
}
