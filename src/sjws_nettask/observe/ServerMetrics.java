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

    /**
     * Stable latency histogram. Cluster code merges nodes by summing {@code counts[i]} with
     * {@code counts[i]}: the index is the contract, and these bounds do not move.
     * <p>
     * Fixed log scale, ratio exactly 2, from 1 ms through 16.384 s, then one {@code +Inf} bucket.
     * Index {@code i} counts samples in {@code (bound[i-1], bound[i]]} ({@code bound[-1]} is 0).
     * The snapshot counts are not cumulative. Prometheus {@code le} labels are the same bounds in
     * seconds ({@link #LATENCY_LE_LABELS}); that exposition is cumulative, so two nodes still merge
     * by adding the series that share a label.
     */
    public static final int LATENCY_FINITE_BUCKETS = 15;
    public static final long LATENCY_FIRST_BOUND_NANOS = 1_000_000L;
    public static final long[] LATENCY_BOUNDS_NANOS;
    /** Prometheus {@code le} label for each {@link #LATENCY_BOUNDS_NANOS} entry. The last is {@code +Inf}. */
    public static final String[] LATENCY_LE_LABELS;

    static {
        var bounds = new long[LATENCY_FINITE_BUCKETS + 1];
        var labels = new String[bounds.length];
        long nanos = LATENCY_FIRST_BOUND_NANOS;
        for (int i = 0; i < LATENCY_FINITE_BUCKETS; i++) {
            bounds[i] = nanos;
            labels[i] = leSeconds(nanos);
            nanos *= 2;
        }
        bounds[LATENCY_FINITE_BUCKETS] = Long.MAX_VALUE;
        labels[LATENCY_FINITE_BUCKETS] = "+Inf";
        LATENCY_BOUNDS_NANOS = bounds;
        LATENCY_LE_LABELS = labels;
    }

    private final LongAdder[] byStatus = new LongAdder[1000];
    private final LongAdder[] byProtocol = new LongAdder[RequestObserver.PROTOCOL_COUNT];
    private final LongAdder[] buckets = new LongAdder[LATENCY_BOUNDS_NANOS.length];
    private final LongAdder bytesSent = new LongAdder();
    private final LongAdder latencySum = new LongAdder();
    private final AtomicLong latencyMax = new AtomicLong();
    private volatile IntSupplier http1 = () -> 0;
    private volatile IntSupplier http3 = () -> 0;
    /** HTTP/1.1 accepts closed at the per-IP cap; 0 until {@link #setRefusedPerIp} attaches. */
    private volatile LongSupplier refusedPerIp = () -> 0;
    private volatile LongSupplier dropped = () -> 0;
    /** #315: msgrepl install pin-round exhaustions; 0 until {@link #setMsgreplInstallCounters} attaches. */
    private volatile LongSupplier msgreplInstallPinExhausted = () -> 0;
    /** #315 N2: msgrepl oversized-install abandonments; 0 until attached. */
    private volatile LongSupplier msgreplInstallOversizedAbandoned = () -> 0;
    /** #259: CpStore Idempotency-Key evictions; 0 until {@link #setCpIdempotencyEvictions} attaches. */
    private volatile LongSupplier cpIdempotencyEvictions = () -> 0;

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

    /**
     * HTTP/1.1 accepts closed because {@code max_connections_per_ip} was hit. Read only at scrape time.
     * Null resets to 0. Core connections family (not swarm-gated). Does not install the observe filter.
     */
    public void setRefusedPerIp(LongSupplier refused) {
        this.refusedPerIp = refused == null ? () -> 0 : refused;
    }

    void setDropped(LongSupplier droppedLines) {
        this.dropped = droppedLines == null ? () -> 0 : droppedLines;
    }

    /**
     * Attach msgrepl install counters (#315 N5). Suppliers are read only at snapshot/scrape time, never on the
     * content request path. Null resets that series to 0. Attaching does not install the observe filter.
     */
    public void setMsgreplInstallCounters(LongSupplier pinExhausted, LongSupplier oversizedAbandoned) {
        this.msgreplInstallPinExhausted = pinExhausted == null ? () -> 0 : pinExhausted;
        this.msgreplInstallOversizedAbandoned = oversizedAbandoned == null ? () -> 0 : oversizedAbandoned;
    }

    /**
     * Attach the CP Idempotency-Key eviction counter (#259). Read only at scrape time. Null resets to 0.
     */
    public void setCpIdempotencyEvictions(LongSupplier evictions) {
        this.cpIdempotencyEvictions = evictions == null ? () -> 0 : evictions;
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

    /** Seconds label for a bound that is an integer number of milliseconds. Three fractional digits. */
    private static String leSeconds(long nanos) {
        long millis = nanos / 1_000_000L;
        long whole = millis / 1000L;
        int frac = (int) (millis % 1000L);
        return whole + "." + (frac < 10 ? "00" : frac < 100 ? "0" : "") + frac;
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

    /** Prometheus text exposition 0.0.4 of one {@link #snapshot()}, plus attached msgrepl install counters (#315). */
    public String prometheus() {
        var text = prometheus(snapshot(), this.msgreplInstallPinExhausted.getAsLong(),
                this.msgreplInstallOversizedAbandoned.getAsLong(), this.cpIdempotencyEvictions.getAsLong());
        // Core connections family (Observability: sjws_connections_*), not swarm-gated: always HELP+TYPE.
        return text
                + "# HELP sjws_connections_refused_per_ip_total HTTP/1.1 accepts closed because max_connections_per_ip was exceeded.\n"
                + "# TYPE sjws_connections_refused_per_ip_total counter\n"
                + "sjws_connections_refused_per_ip_total " + this.refusedPerIp.getAsLong() + "\n";
    }

    static String prometheus(Snapshot snap) {
        return prometheus(snap, 0L, 0L, 0L);
    }

    static String prometheus(Snapshot snap, long installPinExhausted, long installOversizedAbandoned) {
        return prometheus(snap, installPinExhausted, installOversizedAbandoned, 0L);
    }

    static String prometheus(Snapshot snap, long installPinExhausted, long installOversizedAbandoned,
            long cpIdempotencyEvicted) {
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
        sb.append("# HELP sjws_http_request_duration_seconds Time until the handler returns the response, before the socket write.\n");
        sb.append("# TYPE sjws_http_request_duration_seconds histogram\n");
        long cumulative = 0;
        var latency = snap.latency();
        for (int i = 0; i < latency.counts().length; i++) {
            cumulative += latency.counts()[i];
            sb.append("sjws_http_request_duration_seconds_bucket{le=\"").append(LATENCY_LE_LABELS[i]).append("\"} ")
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
        // #315 N5: msgrepl install observability (sibling style to access_log_dropped; always published, 0 until attached).
        sb.append("# HELP sjws_msgrepl_install_pin_exhausted_total Snapshot installs that gave up after the divergent-pin round budget.\n");
        sb.append("# TYPE sjws_msgrepl_install_pin_exhausted_total counter\n");
        sb.append("sjws_msgrepl_install_pin_exhausted_total ").append(installPinExhausted).append('\n');
        sb.append("# HELP sjws_msgrepl_install_oversized_abandoned_total Consensus snapshot installs abandoned because the image exceeded ha.snapshot.max_install_bytes.\n");
        sb.append("# TYPE sjws_msgrepl_install_oversized_abandoned_total counter\n");
        sb.append("sjws_msgrepl_install_oversized_abandoned_total ").append(installOversizedAbandoned).append('\n');
        sb.append("# HELP sjws_cp_idempotency_evicted_total Idempotency-Key entries dropped by the CP replay window or hard cap (#259).\n");
        sb.append("# TYPE sjws_cp_idempotency_evicted_total counter\n");
        sb.append("sjws_cp_idempotency_evicted_total ").append(cpIdempotencyEvicted).append('\n');
        return sb.toString();
    }
}
