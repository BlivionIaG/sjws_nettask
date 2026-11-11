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

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import sjws_nettask.observe.HistogramMerge;
import sjws_nettask.observe.ServerMetrics;
import sjws_nettask.observe.Snapshot;

/**
 * Per-node and aggregate metrics for the admin Metrics view (#366). It reads Observability's
 * {@link Snapshot}s (copies, no hot-path locks) and keeps its names and bucket layout: counters and
 * connection gauges are summed, latency histograms are merged bucket by bucket with
 * {@link HistogramMerge}, and percentiles come from the merged buckets, never from averaging
 * per-node percentiles. No metric registry lives here.
 */
public final class MetricsView {

    /** One node's snapshot. */
    public record Node(String nodeId, Snapshot snapshot) {
        public Node {
            Objects.requireNonNull(nodeId, "nodeId");
            Objects.requireNonNull(snapshot, "snapshot");
        }
    }

    /**
     * What a control can report: the nodes that answered, out of {@code nodesTotal} known nodes.
     * A node that did not answer is missing from {@code nodes}, so it shows as not reporting.
     */
    public record Report(List<Node> nodes, int nodesTotal) {
        public Report {
            nodes = List.copyOf(nodes);
            if (nodesTotal < nodes.size()) {
                throw new IllegalArgumentException("nodesTotal " + nodesTotal + " < " + nodes.size());
            }
        }
    }

    private MetricsView() {
    }

    /** Sum of the snapshots, histograms merged by bucket; null when there are none. */
    public static Snapshot aggregate(List<Snapshot> snapshots) {
        if (snapshots.isEmpty()) {
            return null;
        }
        var first = snapshots.get(0);
        long requests = 0;
        long bytes = 0;
        long dropped = 0;
        int active = 0;
        int h1 = 0;
        int h3 = 0;
        var status = new long[first.requestsByStatus().length];
        var protocols = new long[first.protocols().length];
        var buckets = new long[ServerMetrics.LATENCY_BOUNDS_NANOS.length];
        long count = 0;
        long sum = 0;
        long max = 0;
        for (var s : snapshots) {
            requests += s.requests();
            bytes += s.bytesSent();
            dropped += s.accessLogDropped();
            active += s.activeConnections();
            h1 += s.http1Connections();
            h3 += s.http3Connections();
            add(status, s.requestsByStatus());
            add(protocols, s.protocols());
            var latency = s.latency();
            buckets = HistogramMerge.sum(buckets, latency.counts());
            count += latency.count();
            sum += latency.sumNanos();
            max = Math.max(max, latency.maxNanos());
        }
        return new Snapshot(requests, bytes, active, h1, h3, dropped, status, protocols,
                new Snapshot.Latency(count, sum, max, ServerMetrics.LATENCY_BOUNDS_NANOS, buckets));
    }

    private static void add(long[] into, long[] values) {
        if (values.length != into.length) {
            throw new IllegalArgumentException("snapshot layouts differ: " + values.length + " vs " + into.length);
        }
        for (int i = 0; i < into.length; i++) {
            into[i] += values[i];
        }
    }

    /** Responses whose status is in {@code [hundred, hundred + 99]}. */
    static long statusClass(Snapshot s, int hundred) {
        long n = 0;
        for (int code = hundred; code < hundred + 100; code++) {
            n += s.status(code);
        }
        return n;
    }

    /** Text block for one snapshot, indented by two spaces. */
    static String render(Snapshot s) {
        var latency = s.latency();
        var sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "  requests %d  bytes %d  connections %d (h1 %d, h3 %d)%n", s.requests(),
                s.bytesSent(), s.activeConnections(), s.http1Connections(), s.http3Connections()));
        sb.append(String.format(Locale.ROOT, "  status 1xx %d  2xx %d  3xx %d  4xx %d  5xx %d%n", statusClass(s, 100),
                statusClass(s, 200), statusClass(s, 300), statusClass(s, 400), statusClass(s, 500)));
        sb.append(String.format(Locale.ROOT, "  protocol HTTP/1.0 %d  HTTP/1.1 %d  HTTP/2 %d  HTTP/3 %d%n",
                s.protocol("HTTP/1.0"), s.protocol("HTTP/1.1"), s.protocol("HTTP/2"), s.protocol("HTTP/3")));
        sb.append(String.format(Locale.ROOT, "  latency p50 <= %s  p99 <= %s  max %s  (%d samples)%n",
                seconds(HistogramMerge.percentileUpperNanos(latency.counts(), 50)),
                seconds(HistogramMerge.percentileUpperNanos(latency.counts(), 99)), seconds(latency.maxNanos()),
                latency.count()));
        if (s.accessLogDropped() > 0) {
            sb.append("  access log lines dropped ").append(s.accessLogDropped()).append('\n');
        }
        return sb.toString();
    }

    static String seconds(long nanos) {
        if (nanos <= 0) {
            return "-";
        }
        if (nanos == Long.MAX_VALUE) {
            return "+Inf";
        }
        return String.format(Locale.ROOT, "%.3f s", nanos / 1_000_000_000.0);
    }
}
