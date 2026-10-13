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
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Aggregates {@link NodeReport}s on the control plane. The heartbeat receiver calls {@link #ingest}
 * (low frequency, one cumulative snapshot per node). The admin TUI calls {@link #status()}.
 * This class does not open a socket and is not on the data-plane request path.
 * <p>
 * A node that disappears stays until {@link #remove(String)}. Membership is the control plane's.
 */
public final class ClusterBoard {

    /**
     * Safety cap on merged route or API-key names. Data-plane tables are 64 configured ids plus
     * one overflow bucket; anything beyond this cap folds into {@code other}.
     */
    static final int MAX_MERGED_SERIES = 65;

    private final ReentrantLock lock = new ReentrantLock();
    private final LinkedHashMap<String, Sample> nodes = new LinkedHashMap<>();
    private long desiredVersion;

    /** @param version 0 means the control plane has not published a generation yet */
    public void desiredVersion(long version) {
        this.lock.lock();
        try {
            this.desiredVersion = version;
        } finally {
            this.lock.unlock();
        }
    }

    /** Replaces the last report for {@code report.nodeId()}. */
    public void ingest(NodeReport report) {
        Objects.requireNonNull(report, "report");
        this.lock.lock();
        try {
            var previous = this.nodes.get(report.nodeId());
            double rps = previous == null ? 0 : rate(previous, report);
            this.nodes.put(report.nodeId(), new Sample(report, rps));
        } finally {
            this.lock.unlock();
        }
    }

    /** Drops a node. Unknown ids do nothing. */
    public void remove(String nodeId) {
        if (nodeId == null) {
            return;
        }
        this.lock.lock();
        try {
            this.nodes.remove(nodeId);
        } finally {
            this.lock.unlock();
        }
    }

    public ClusterStatus status() {
        this.lock.lock();
        try {
            var nodeRows = new ClusterStatus.Node[this.nodes.size()];
            var merged = LabelSeries.emptyBuckets();
            var routes = new LinkedHashMap<String, long[]>();
            var apiKeys = new LinkedHashMap<String, long[]>();
            long requests = 0;
            long errors = 0;
            double rps = 0;
            int drifted = 0;
            int i = 0;
            for (var sample : this.nodes.values()) {
                var report = sample.report;
                boolean drift = this.desiredVersion != 0 && report.appliedVersion() != this.desiredVersion;
                if (drift) {
                    drifted++;
                }
                requests += report.requests();
                errors += report.errors();
                rps += sample.rps;
                merged = HistogramMerge.sum(merged, report.latencyCounts());
                fold(routes, report.routes());
                fold(apiKeys, report.apiKeys());
                nodeRows[i++] = new ClusterStatus.Node(report.nodeId(), report.alive(), report.ready(),
                        report.draining(), report.appliedVersion(), drift, report.requests(), report.errors(),
                        sample.rps, HistogramMerge.percentileUpperNanos(report.latencyCounts(), 99));
            }
            return new ClusterStatus(this.desiredVersion, rps, HistogramMerge.percentileUpperNanos(merged, 99),
                    requests, errors, drifted, nodeRows, series(routes), series(apiKeys));
        } finally {
            this.lock.unlock();
        }
    }

    /**
     * Requests per second from the previous cumulative snapshot to this one.
     * A drop in the counter (process restart) is 0 for this interval. No positive wall-clock gap
     * keeps the previous rate.
     */
    private static double rate(Sample previous, NodeReport report) {
        long deltaRequests = report.requests() - previous.report.requests();
        long deltaMillis = report.wallMillis() - previous.report.wallMillis();
        if (deltaRequests < 0) {
            return 0;
        }
        if (deltaMillis <= 0) {
            return previous.rps;
        }
        return deltaRequests * 1000.0 / deltaMillis;
    }

    /** values: [requests, errors, bucket0..] */
    private static void fold(LinkedHashMap<String, long[]> into, LabelSeries[] series) {
        for (var label : series) {
            var name = label.name();
            var acc = into.get(name);
            if (acc == null && into.size() >= MAX_MERGED_SERIES && !"other".equals(name)) {
                name = "other";
                acc = into.get(name);
            }
            if (acc == null) {
                acc = new long[2 + ServerMetrics.LATENCY_BOUNDS_NANOS.length];
                into.put(name, acc);
            }
            acc[0] += label.requests();
            acc[1] += label.errors();
            var counts = label.latencyCounts();
            for (int b = 0; b < counts.length; b++) {
                acc[2 + b] += counts[b];
            }
        }
    }

    private static ClusterStatus.Series[] series(LinkedHashMap<String, long[]> folded) {
        var out = new ClusterStatus.Series[folded.size()];
        int i = 0;
        for (var entry : folded.entrySet()) {
            var acc = entry.getValue();
            var counts = Arrays.copyOfRange(acc, 2, acc.length);
            out[i++] = new ClusterStatus.Series(entry.getKey(), acc[0], acc[1],
                    HistogramMerge.percentileUpperNanos(counts, 99));
        }
        return out;
    }

    private static final class Sample {
        final NodeReport report;
        final double rps;

        Sample(NodeReport report, double rps) {
            this.report = report;
            this.rps = rps;
        }
    }
}
