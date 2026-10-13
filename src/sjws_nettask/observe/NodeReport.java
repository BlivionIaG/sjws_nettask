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
 * Cumulative snapshot a data-plane node hands the control plane on its heartbeat.
 * One report covers every request since the counters started, not one request.
 * The membership UDP datagram stays the fixed 64-byte packet from the HA design
 * (generation and content hash only). This object is the separate control payload.
 *
 * @param sequence increases only when a new snapshot is taken, not when a cached beat is reused
 * @param wallMillis sender clock, for the receiver's rate window
 * @param appliedVersion config generation the node is serving, or 0 when none has been published
 * @param errors responses with status {@code >= 500}
 * @param clientErrors responses with status 400..499
 * @param latencyCounts {@link ServerMetrics#LATENCY_BOUNDS_NANOS}, not cumulative
 */
public record NodeReport(String nodeId, long sequence, long wallMillis, boolean alive, boolean ready, boolean draining,
        long appliedVersion, long contentHash, long requests, long errors, long clientErrors, long bytesSent,
        long latencySumNanos, long[] latencyCounts, LabelSeries[] routes, LabelSeries[] apiKeys) {

    public NodeReport {
        Objects.requireNonNull(nodeId, "nodeId");
        if (nodeId.isEmpty()) {
            throw new IllegalArgumentException("nodeId is empty");
        }
        if (latencyCounts == null || latencyCounts.length != ServerMetrics.LATENCY_BOUNDS_NANOS.length) {
            throw new IllegalArgumentException("latency histogram must have "
                    + ServerMetrics.LATENCY_BOUNDS_NANOS.length + " buckets");
        }
        latencyCounts = latencyCounts.clone();
        routes = routes == null ? new LabelSeries[0] : routes.clone();
        apiKeys = apiKeys == null ? new LabelSeries[0] : apiKeys.clone();
        if (Arrays.asList(routes).contains(null) || Arrays.asList(apiKeys).contains(null)) {
            throw new IllegalArgumentException("label series contains null");
        }
    }
}
