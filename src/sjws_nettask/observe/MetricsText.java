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

import java.util.LinkedHashMap;
import java.util.Locale;

/**
 * Prometheus text 0.0.4 for the cluster board and for this node's gateway labels.
 * Appended to {@link ServerMetrics#prometheus()} only while a scrape is being rendered.
 * Neither method reads the clock or touches a request counter.
 */
final class MetricsText {

    private MetricsText() {
    }

    /** Gauges from one {@link ClusterStatus}. A null status appends nothing. */
    static void appendCluster(StringBuilder sb, ClusterStatus status) {
        if (status == null) {
            return;
        }
        gauge(sb, "sjws_cluster_desired_version",
                "Config generation the control plane wants, or 0 when none is published.",
                Long.toString(status.desiredVersion()));
        gauge(sb, "sjws_cluster_requests_per_second",
                "Sum of per-node request rates from the latest reports.",
                number(status.requestsPerSecond()));
        gauge(sb, "sjws_cluster_request_p99_seconds",
                "Merged p99 as a log2 bucket upper bound, or 0 when no node has samples.",
                seconds(status.p99Nanos()));
        gauge(sb, "sjws_cluster_requests",
                "Responses summed from the latest report of each node.",
                Long.toString(status.requests()));
        gauge(sb, "sjws_cluster_errors",
                "5xx responses summed from the latest report of each node.",
                Long.toString(status.errors()));
        gauge(sb, "sjws_cluster_config_drifted_nodes",
                "Nodes whose applied generation differs from the desired one.",
                Integer.toString(status.driftedNodes()));

        var nodes = new LinkedHashMap<String, ClusterStatus.Node>();
        for (var node : status.nodes()) {
            nodes.put(node.nodeId(), node);
        }
        if (!nodes.isEmpty()) {
            labeled(sb, "sjws_cluster_node_alive", "gauge", "1 when the node's last report was alive.",
                    nodes, (node, row) -> row.value = node.alive() ? "1" : "0");
            labeled(sb, "sjws_cluster_node_ready", "gauge", "1 when the node is ready.",
                    nodes, (node, row) -> row.value = node.ready() ? "1" : "0");
            labeled(sb, "sjws_cluster_node_draining", "gauge", "1 when the node is draining.",
                    nodes, (node, row) -> row.value = node.draining() ? "1" : "0");
            labeled(sb, "sjws_cluster_node_drifted", "gauge",
                    "1 when the applied generation differs from the desired one.",
                    nodes, (node, row) -> row.value = node.drifted() ? "1" : "0");
            labeled(sb, "sjws_cluster_node_applied_version", "gauge", "Config generation the node reported.",
                    nodes, (node, row) -> row.value = Long.toString(node.appliedVersion()));
            labeled(sb, "sjws_cluster_node_requests", "gauge", "Responses in the node's latest report.",
                    nodes, (node, row) -> row.value = Long.toString(node.requests()));
            labeled(sb, "sjws_cluster_node_errors", "gauge", "5xx responses in the node's latest report.",
                    nodes, (node, row) -> row.value = Long.toString(node.errors()));
            labeled(sb, "sjws_cluster_node_requests_per_second", "gauge",
                    "Request rate since the node's previous report.",
                    nodes, (node, row) -> row.value = number(node.requestsPerSecond()));
            labeled(sb, "sjws_cluster_node_request_p99_seconds", "gauge",
                    "Node p99 as a log2 bucket upper bound, or 0 when it has no samples.",
                    nodes, (node, row) -> row.value = seconds(node.p99Nanos()));
        }
        appendSeries(sb, "sjws_cluster_route", "route", "configured route", status.routes());
        appendSeries(sb, "sjws_cluster_api_key", "api_key", "configured API-key id", status.apiKeys());
    }

    /**
     * This node's route and API-key counters. Empty tables and {@code null} arrays append nothing.
     * Names beyond {@link ClusterBoard#MAX_MERGED_SERIES} fold into {@code other}.
     * These are not histograms: {@link LabelSeries} has bucket counts and no latency sum, so a
     * {@code _sum} would be invented. The p99 gauge is the log2 bucket upper bound.
     */
    static void appendGateway(StringBuilder sb, LabelSource labels) {
        if (labels == null) {
            return;
        }
        appendFolded(sb, "sjws_gateway_route", "route", "configured route id on this node",
                fold(labels.routes()));
        appendFolded(sb, "sjws_gateway_api_key", "api_key", "configured API-key id on this node",
                fold(labels.apiKeys()));
    }

    private static void appendSeries(StringBuilder sb, String prefix, String label, String noun,
            ClusterStatus.Series[] series) {
        var folded = new LinkedHashMap<String, long[]>();
        for (var row : series) {
            var acc = folded.get(row.name());
            if (acc == null) {
                acc = new long[] {0, 0, 0};
                folded.put(row.name(), acc);
            }
            acc[0] += row.requests();
            acc[1] += row.errors();
            acc[2] = wider(acc[2], row.p99Nanos());
        }
        if (folded.isEmpty()) {
            return;
        }
        var requests = new String[folded.size()];
        var errors = new String[folded.size()];
        var p99 = new String[folded.size()];
        var names = new String[folded.size()];
        int i = 0;
        for (var entry : folded.entrySet()) {
            names[i] = label(label, entry.getKey());
            requests[i] = Long.toString(entry.getValue()[0]);
            errors[i] = Long.toString(entry.getValue()[1]);
            p99[i] = seconds(entry.getValue()[2]);
            i++;
        }
        family(sb, prefix + "_requests", "gauge", "Merged responses for one " + noun + ".", names, requests);
        family(sb, prefix + "_errors", "gauge", "Merged 5xx responses for one " + noun + ".", names, errors);
        family(sb, prefix + "_request_p99_seconds", "gauge",
                "Merged p99 for one " + noun + " as a log2 bucket upper bound.", names, p99);
    }

    private static void appendFolded(StringBuilder sb, String prefix, String label, String what,
            LinkedHashMap<String, Acc> folded) {
        if (folded.isEmpty()) {
            return;
        }
        var names = new String[folded.size()];
        var requests = new String[folded.size()];
        var errors = new String[folded.size()];
        var p99 = new String[folded.size()];
        int i = 0;
        for (var entry : folded.entrySet()) {
            var acc = entry.getValue();
            names[i] = label(label, entry.getKey());
            requests[i] = Long.toString(acc.requests);
            errors[i] = Long.toString(acc.errors);
            p99[i] = seconds(HistogramMerge.percentileUpperNanos(acc.buckets, 99));
            i++;
        }
        family(sb, prefix + "_requests_total", "counter", "Responses charged to one " + what + ".", names, requests);
        family(sb, prefix + "_errors_total", "counter", "5xx responses charged to one " + what + ".", names, errors);
        family(sb, prefix + "_request_p99_seconds", "gauge",
                "p99 for one " + what + " as a log2 bucket upper bound, or 0 when it has no samples.", names, p99);
    }

    /** values: requests, errors, then non-cumulative buckets. Duplicate names are summed. */
    private static LinkedHashMap<String, Acc> fold(LabelSeries[] series) {
        var into = new LinkedHashMap<String, Acc>();
        if (series == null) {
            return into;
        }
        for (var label : series) {
            if (label == null) {
                continue;
            }
            var name = label.name();
            var acc = into.get(name);
            if (acc == null && into.size() >= ClusterBoard.MAX_MERGED_SERIES && !"other".equals(name)) {
                name = "other";
                acc = into.get(name);
            }
            if (acc == null) {
                acc = new Acc();
                into.put(name, acc);
            }
            acc.requests += label.requests();
            acc.errors += label.errors();
            var counts = label.latencyCounts();
            for (int b = 0; b < counts.length; b++) {
                acc.buckets[b] += counts[b];
            }
        }
        return into;
    }

    private static long wider(long current, long next) {
        if (next == Long.MAX_VALUE || current != Long.MAX_VALUE && next > current) {
            return next;
        }
        return current;
    }

    private static void labeled(StringBuilder sb, String name, String type, String help,
            LinkedHashMap<String, ClusterStatus.Node> nodes, NodeValue value) {
        var labels = new String[nodes.size()];
        var values = new String[nodes.size()];
        int i = 0;
        for (var node : nodes.values()) {
            var row = new Row();
            value.apply(node, row);
            labels[i] = label("node", node.nodeId());
            values[i] = row.value;
            i++;
        }
        family(sb, name, type, help, labels, values);
    }

    private static void gauge(StringBuilder sb, String name, String help, String value) {
        family(sb, name, "gauge", help, new String[] {null}, new String[] {value});
    }

    private static void family(StringBuilder sb, String name, String type, String help, String[] labels,
            String[] values) {
        sb.append("# HELP ").append(name).append(' ').append(help).append('\n');
        sb.append("# TYPE ").append(name).append(' ').append(type).append('\n');
        for (int i = 0; i < values.length; i++) {
            sb.append(name);
            if (labels[i] != null) {
                sb.append('{').append(labels[i]).append('}');
            }
            sb.append(' ').append(values[i]).append('\n');
        }
    }

    static String label(String name, String value) {
        return name + "=\"" + escape(value) + '"';
    }

    /** Prometheus text escaping: backslash, newline, and double quote. */
    static String escape(String value) {
        var sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '"' -> sb.append("\\\"");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /** A sample value. Whole numbers stay integers. Non-finite values use the exposition tokens. */
    static String number(double value) {
        if (Double.isNaN(value)) {
            return "NaN";
        }
        if (value == Double.POSITIVE_INFINITY) {
            return "+Inf";
        }
        if (value == Double.NEGATIVE_INFINITY) {
            return "-Inf";
        }
        if (value == 0.0) {
            return "0";
        }
        if (value == Math.rint(value) && value >= Long.MIN_VALUE && value <= Long.MAX_VALUE) {
            return Long.toString((long) value);
        }
        return String.format(Locale.ROOT, "%.6f", value);
    }

    /** Seconds for a log2 bound. {@code 0} is no samples. The open bucket is {@code +Inf}. */
    static String seconds(long nanos) {
        if (nanos <= 0) {
            return "0";
        }
        if (nanos == Long.MAX_VALUE) {
            return "+Inf";
        }
        for (int i = 0; i < ServerMetrics.LATENCY_BOUNDS_NANOS.length; i++) {
            if (ServerMetrics.LATENCY_BOUNDS_NANOS[i] == nanos) {
                return ServerMetrics.LATENCY_LE_LABELS[i];
            }
        }
        return String.format(Locale.ROOT, "%.6f", nanos / 1_000_000_000.0);
    }

    private static final class Acc {
        long requests;
        long errors;
        final long[] buckets = LabelSeries.emptyBuckets();
    }

    private static final class Row {
        String value;
    }

    @FunctionalInterface
    private interface NodeValue {
        void apply(ClusterStatus.Node node, Row row);
    }
}
