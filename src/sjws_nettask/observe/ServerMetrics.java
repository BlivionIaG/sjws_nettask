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

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/**
 * In-process counters an admin TUI reads through {@link #snapshot()}. Updates are {@link LongAdder}s
 * (striped, no monitor on the request thread): one per status code, one per protocol, one per latency
 * bucket, plus the byte total. The latency max is a single compare-and-set that loses quietly when
 * another thread already published a higher value.
 */
public final class ServerMetrics {

    /** Inclusive bucket bounds. The last bucket is everything above 10s. */
    public static final long[] LATENCY_BOUNDS_NANOS = {
            1_000_000L, 5_000_000L, 10_000_000L, 25_000_000L, 50_000_000L, 100_000_000L, 250_000_000L, 500_000_000L,
            1_000_000_000L, 2_500_000_000L, 5_000_000_000L, 10_000_000_000L, Long.MAX_VALUE};

    private static final String[] LE = {
            "0.001", "0.005", "0.01", "0.025", "0.05", "0.1", "0.25", "0.5", "1", "2.5", "5", "10", "+Inf"};

    private final LongAdder[] byStatus = new LongAdder[1000];
    private final LongAdder[] byProtocol = new LongAdder[RequestObserver.PROTOCOL_COUNT];
    private final LongAdder[] buckets = new LongAdder[LATENCY_BOUNDS_NANOS.length];
    private final LongAdder bytesSent = new LongAdder();
    private final LongAdder latencySum = new LongAdder();
    private final AtomicLong latencyMax = new AtomicLong();
    private volatile IntSupplier http1 = () -> 0;
    private volatile IntSupplier http3 = () -> 0;
    private volatile LongSupplier dropped = () -> 0;

    public ServerMetrics() {
        for (int i = 0; i < this.byStatus.length; i++) {
            this.byStatus[i] = new LongAdder();
        }
        for (int i = 0; i < this.byProtocol.length; i++) {
            this.byProtocol[i] = new LongAdder();
        }
        for (int i = 0; i < this.buckets.length; i++) {
            this.buckets[i] = new LongAdder();
        }
    }

    /**
     * Live connection gauges. Called once the listeners exist; a snapshot taken earlier reports 0.
     * The suppliers are read only when a snapshot is taken, not on the request path.
     */
    public void setConnectionGauges(IntSupplier http1Connections, IntSupplier http3Connections) {
        this.http1 = http1Connections == null ? () -> 0 : http1Connections;
        this.http3 = http3Connections == null ? () -> 0 : http3Connections;
    }

    void setDropped(LongSupplier droppedLines) {
        this.dropped = droppedLines == null ? () -> 0 : droppedLines;
    }

    /** Hot path: a handful of striped increments, no allocation. */
    public void record(int status, int protocol, long bytes, long nanos) {
        if (status >= 100 && status < this.byStatus.length) {
            this.byStatus[status].increment();
        }
        int proto = protocol >= 0 && protocol < this.byProtocol.length ? protocol : RequestObserver.PROTOCOL_OTHER;
        this.byProtocol[proto].increment();
        if (bytes > 0) {
            this.bytesSent.add(bytes);
        }
        if (nanos < 0) {
            nanos = 0;
        }
        this.buckets[bucket(nanos)].increment();
        this.latencySum.add(nanos);
        long prev = this.latencyMax.get();
        while (nanos > prev && !this.latencyMax.weakCompareAndSetVolatile(prev, nanos)) {
            prev = this.latencyMax.get();
        }
    }

    static int bucket(long nanos) {
        for (int i = 0; i < LATENCY_BOUNDS_NANOS.length - 1; i++) {
            if (nanos <= LATENCY_BOUNDS_NANOS[i]) {
                return i;
            }
        }
        return LATENCY_BOUNDS_NANOS.length - 1;
    }

    /** Consistent-enough copy for a TUI or a scrape. Counters can move while it is taken. */
    public Snapshot snapshot() {
        var statuses = new long[this.byStatus.length];
        long requests = 0;
        for (int i = 100; i < statuses.length; i++) {
            long n = this.byStatus[i].sum();
            statuses[i] = n;
            requests += n;
        }
        var protocols = new long[this.byProtocol.length];
        for (int i = 0; i < protocols.length; i++) {
            protocols[i] = this.byProtocol[i].sum();
        }
        var counts = new long[this.buckets.length];
        long latencyCount = 0;
        for (int i = 0; i < counts.length; i++) {
            counts[i] = this.buckets[i].sum();
            latencyCount += counts[i];
        }
        int h1 = this.http1.getAsInt();
        int h3 = this.http3.getAsInt();
        return new Snapshot(requests, this.bytesSent.sum(), h1 + h3, h1, h3, this.dropped.getAsLong(), statuses,
                protocols, new Snapshot.Latency(latencyCount, this.latencySum.sum(), this.latencyMax.get(),
                        LATENCY_BOUNDS_NANOS, counts));
    }

    /** Prometheus text exposition 0.0.4 of one {@link #snapshot()}. */
    public String prometheus() {
        return prometheus(snapshot());
    }

    static String prometheus(Snapshot snap) {
        var sb = new StringBuilder(1024);
        sb.append("# HELP sjws_http_requests_total Responses by status and protocol.\n");
        sb.append("# TYPE sjws_http_requests_total counter\n");
        for (int status = 100; status < snap.requestsByStatus().length; status++) {
            long n = snap.requestsByStatus()[status];
            if (n == 0) {
                continue;
            }
            sb.append("sjws_http_requests_total{status=\"").append(status).append("\"} ").append(n).append('\n');
        }
        sb.append("# HELP sjws_http_protocol_requests_total Responses by protocol.\n");
        sb.append("# TYPE sjws_http_protocol_requests_total counter\n");
        for (int p = 0; p < snap.protocols().length; p++) {
            if (snap.protocols()[p] == 0) {
                continue;
            }
            sb.append("sjws_http_protocol_requests_total{protocol=\"")
                    .append(RequestObserver.PROTOCOL_NAMES[p]).append("\"} ").append(snap.protocols()[p]).append('\n');
        }
        sb.append("# HELP sjws_http_response_bytes_total Response body bytes handed to the transport.\n");
        sb.append("# TYPE sjws_http_response_bytes_total counter\n");
        sb.append("sjws_http_response_bytes_total ").append(snap.bytesSent()).append('\n');
        sb.append("# HELP sjws_http_request_duration_seconds Time to finish the response, including the write.\n");
        sb.append("# TYPE sjws_http_request_duration_seconds histogram\n");
        long cumulative = 0;
        var latency = snap.latency();
        for (int i = 0; i < latency.counts().length; i++) {
            cumulative += latency.counts()[i];
            sb.append("sjws_http_request_duration_seconds_bucket{le=\"").append(LE[i]).append("\"} ")
                    .append(cumulative).append('\n');
        }
        sb.append("sjws_http_request_duration_seconds_sum ")
                .append(String.format(Locale.ROOT, "%.6f", latency.sumNanos() / 1_000_000_000.0)).append('\n');
        sb.append("sjws_http_request_duration_seconds_count ").append(latency.count()).append('\n');
        sb.append("# HELP sjws_connections_active Connections being served.\n");
        sb.append("# TYPE sjws_connections_active gauge\n");
        sb.append("sjws_connections_active{protocol=\"HTTP/1.1\"} ").append(snap.http1Connections()).append('\n');
        sb.append("sjws_connections_active{protocol=\"HTTP/3\"} ").append(snap.http3Connections()).append('\n');
        sb.append("# HELP sjws_access_log_dropped_total Access-log lines dropped before they were written.\n");
        sb.append("# TYPE sjws_access_log_dropped_total counter\n");
        sb.append("sjws_access_log_dropped_total ").append(snap.accessLogDropped()).append('\n');
        return sb.toString();
    }
}
