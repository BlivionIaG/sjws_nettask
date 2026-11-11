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
package sjws_nettask.admin;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import sjws_nettask.observe.ClusterStatus;

/**
 * Cluster node list with named health checks for the admin Cluster view (#363). Health is derived
 * from the membership and readiness flags {@link ClusterStatus} already carries (HA owns discovery
 * and the flags; nothing here invents membership): a node that is not alive is {@code DOWN}, a live
 * node failing any other check is {@code DEGRADED}, otherwise {@code UP}.
 */
public final class ClusterView {

    /** Rolled-up node health. */
    public enum Health { UP, DEGRADED, DOWN }

    /** One named check; {@code detail} says what failed and is empty when the check passes. */
    public record Check(String name, boolean ok, String detail) {
    }

    /** A node with its checks and rolled-up health. */
    public record NodeHealth(ClusterStatus.Node node, Health health, List<Check> checks) {

        public NodeHealth {
            checks = List.copyOf(checks);
        }

        /** Failing checks as {@code "name: detail"}, or {@code "ok"} when every check passes. */
        public String summary() {
            var failing = this.checks.stream().filter(c -> !c.ok()).map(c -> c.name() + ": " + c.detail()).toList();
            return failing.isEmpty() ? "ok" : String.join(", ", failing);
        }
    }

    private ClusterView() {
    }

    /** Health checks for one node against the cluster's desired config version. */
    public static NodeHealth health(ClusterStatus.Node node, long desiredVersion) {
        var checks = new ArrayList<Check>(4);
        checks.add(new Check("alive", node.alive(), node.alive() ? "" : "unreachable"));
        checks.add(new Check("ready", node.ready(), node.ready() ? "" : "not ready"));
        checks.add(new Check("serving", !node.draining(), node.draining() ? "draining" : ""));
        checks.add(new Check("config", !node.drifted(), node.drifted()
                ? "applied " + node.appliedVersion() + ", desired " + desiredVersion : ""));
        Health health;
        if (!node.alive()) {
            health = Health.DOWN;
        } else if (checks.stream().allMatch(Check::ok)) {
            health = Health.UP;
        } else {
            health = Health.DEGRADED;
        }
        return new NodeHealth(node, health, checks);
    }

    /** Every node of {@code status}, in its order. */
    public static List<NodeHealth> nodes(ClusterStatus status) {
        var out = new ArrayList<NodeHealth>(status.nodes().length);
        for (var node : status.nodes()) {
            out.add(health(node, status.desiredVersion()));
        }
        return out;
    }

    /**
     * Reads {@code source} on a virtual thread and waits at most {@code timeout}, so a slow status
     * source never stalls the console.
     *
     * @throws TimeoutException when the source did not answer in time (the read is cancelled)
     * @throws IllegalStateException when the source failed or the wait was interrupted
     */
    public static ClusterStatus fetch(Supplier<ClusterStatus> source, Duration timeout) throws TimeoutException {
        var task = new FutureTask<ClusterStatus>(source::get);
        Thread.ofVirtual().name("admin-cluster-status").start(task);
        try {
            return task.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            task.cancel(true);
            throw e;
        } catch (ExecutionException e) {
            throw new IllegalStateException(String.valueOf(e.getCause().getMessage()), e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            task.cancel(true);
            throw new IllegalStateException("interrupted", e);
        }
    }
}
