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
package sjws_nettask.observe;

import java.util.Arrays;
import java.util.Objects;

/**
 * Cluster view built from the latest {@link NodeReport} of each data-plane node.
 * Rates are per second over the wall-clock gap between that node's last two reports.
 * {@code p99Nanos} is a bucket upper bound from the merged log2 histogram, or 0 when no samples exist.
 *
 * @param desiredVersion config generation the control plane wants, or 0 when none has been published
 * @param driftedNodes nodes whose applied generation differs from {@code desiredVersion}
 */
public record ClusterStatus(long desiredVersion, double requestsPerSecond, long p99Nanos, long requests, long errors,
        int driftedNodes, Node[] nodes, Series[] routes, Series[] apiKeys) {

    public ClusterStatus {
        nodes = nodes == null ? new Node[0] : nodes.clone();
        routes = routes == null ? new Series[0] : routes.clone();
        apiKeys = apiKeys == null ? new Series[0] : apiKeys.clone();
        if (Arrays.asList(nodes).contains(null) || Arrays.asList(routes).contains(null)
                || Arrays.asList(apiKeys).contains(null)) {
            throw new IllegalArgumentException("cluster status contains null");
        }
    }

    /**
     * @param drifted applied generation differs from the desired one; false when nothing is desired yet
     * @param p99Nanos bucket upper bound for this node, or 0 when it has no samples
     */
    public record Node(String nodeId, boolean alive, boolean ready, boolean draining, long appliedVersion,
            boolean drifted, long requests, long errors, double requestsPerSecond, long p99Nanos) {

        public Node {
            Objects.requireNonNull(nodeId, "nodeId");
            if (nodeId.isEmpty()) {
                throw new IllegalArgumentException("nodeId is empty");
            }
        }
    }

    /** Merged per-route or per-API-key series. {@code name} is a configured id, never a secret. */
    public record Series(String name, long requests, long errors, long p99Nanos) {

        public Series {
            Objects.requireNonNull(name, "name");
            if (name.isEmpty()) {
                throw new IllegalArgumentException("series name is empty");
            }
        }
    }
}
