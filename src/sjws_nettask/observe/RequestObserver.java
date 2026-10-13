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

import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;
import sjws_nettask.ServerConfig;
import sjws_nettask.ServerConfig.MetricsExposure;
import sjws_nettask.core.Body;
import sjws_nettask.core.Response;
import sjws_nettask.core.ResponseHeaders;

/**
 * Access logging, in-process metrics and the optional {@code /healthz} and {@code /metrics} responses.
 * <p>
 * {@link ObserveRequestFilter} is the only caller on the request path. The server registers that
 * filter at order 0 when observability is on, and again if {@link sjws_nettask.SjwsServer#metricsSnapshot()}
 * arms a server that started with {@link #NONE}. Transports do not call {@link #intercept} or
 * {@link #complete}. While the filter is not installed there is no {@link System#nanoTime()}, no
 * counter update and no path check.
 * <p>
 * With access logging off, {@link #complete(int, int, long, long)} only touches striped counters.
 * Logging adds one line-{@code byte[]} and a lock-free enqueue. Neither path waits on I/O.
 * Duration is the time until the handler returns, which is before the transport writes the body.
 */
public final class RequestObserver implements AutoCloseable {

    public static final int HTTP_1_0 = 0;
    public static final int HTTP_1_1 = 1;
    public static final int HTTP_2 = 2;
    public static final int HTTP_3 = 3;
    public static final int PROTOCOL_OTHER = 4;
    public static final int PROTOCOL_COUNT = 5;
    public static final String[] PROTOCOL_NAMES = {"HTTP/1.0", "HTTP/1.1", "HTTP/2", "HTTP/3", "other"};

    /** Prometheus text exposition 0.0.4. Scrapes reject any other version token. */
    public static final String METRICS_CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8";

    /**
     * Observability off. The filter is not installed, so a request does not read the clock, does not
     * update a counter, and does not check {@code /healthz} or {@code /metrics}. A document-root file
     * at those paths is served as on a build without this package.
     */
    public static final RequestObserver NONE = new RequestObserver(new ServerMetrics(), null, MetricsExposure.OFF, false,
            false);

    private static final String HEALTH = "/healthz";
    private static final String METRICS = "/metrics";
    private static final ResponseHeaders PLAIN = ResponseHeaders.of().add("content-type", "text/plain; charset=utf-8")
            .add("cache-control", "no-store").add("x-content-type-options", "nosniff");
    private static final Response HEALTH_GET = new Response(200, PLAIN, Body.Bytes.of("ok\n".getBytes(StandardCharsets.US_ASCII)));
    private static final Response HEALTH_HEAD = new Response(200, PLAIN, Body.Bytes.of("ok\n".getBytes(StandardCharsets.US_ASCII)),
            true);
    private static final Response METRICS_FORBIDDEN = new Response(403, PLAIN,
            Body.Bytes.of("forbidden\n".getBytes(StandardCharsets.US_ASCII)));

    private final ServerMetrics metrics;
    private final AccessLog log;
    private final MetricsExposure exposure;
    private final boolean recording;
    private final boolean healthz;
    /** Read only while rendering {@code /metrics}. Null omits the cluster families. */
    private volatile Supplier<ClusterStatus> cluster;
    /** Read only while rendering {@code /metrics}. Null or {@link LabelSource#NONE} omits gateway families. */
    private volatile LabelSource labels;

    private RequestObserver(ServerMetrics metrics, AccessLog log, MetricsExposure exposure, boolean recording,
            boolean healthz) {
        this.metrics = metrics;
        this.log = log;
        this.exposure = exposure;
        this.recording = recording;
        this.healthz = healthz;
        if (log != null) {
            metrics.setDropped(log::dropped);
        }
    }

    /** @throws java.io.IOException the access-log file cannot be opened */
    public static RequestObserver open(ServerConfig config) throws java.io.IOException {
        AccessLog log = config.accessLog() == null ? null : AccessLog.start(config.accessLog(), config.accessLogFormat());
        return new RequestObserver(new ServerMetrics(), log, config.metricsExposure(), true, config.healthz());
    }

    public ServerMetrics metrics() {
        return this.metrics;
    }

    /** @return true when responses should be counted (false only for {@link #NONE}) */
    public boolean recording() {
        return this.recording;
    }

    /** @return true when a line is formatted for every response */
    public boolean logging() {
        return this.log != null;
    }

    /** @return true when the line includes Referer and User-Agent (combined and JSON) */
    public boolean logHeaders() {
        return this.log != null && this.log.headers();
    }

    /**
     * @return true when {@link #intercept} might replace the handler. False for {@link #NONE} and for
     *         a recording observer whose {@code /healthz} and {@code /metrics} are both off, so the
     *         filter can skip the path check.
     */
    public boolean intercepts() {
        return this.healthz || this.exposure != MetricsExposure.OFF;
    }

    /**
     * @return a response that replaces the application handler, or null to serve the request normally.
     *         {@code /healthz} is answered only when this observer was opened with the probe enabled.
     *         {@code /metrics} is answered only when enabled, and only to a loopback peer when the
     *         exposure is {@link MetricsExposure#LOCALHOST}.
     */
    public Response intercept(String method, String target, boolean loopback) {
        if (!this.recording || target.length() < HEALTH.length() || target.charAt(0) != '/') {
            return null;
        }
        char second = target.charAt(1);
        if (second != 'h' && second != 'm') {
            return null;
        }
        if (this.healthz && isExact(target, HEALTH)) {
            if ("GET".equals(method)) {
                return HEALTH_GET;
            }
            if ("HEAD".equals(method)) {
                return HEALTH_HEAD;
            }
            return null;
        }
        if (second == 'm' && this.exposure != MetricsExposure.OFF && isExact(target, METRICS)) {
            if (!"GET".equals(method) && !"HEAD".equals(method)) {
                return null;
            }
            if (this.exposure == MetricsExposure.LOCALHOST && !loopback) {
                return METRICS_FORBIDDEN;
            }
            return metricsResponse("HEAD".equals(method));
        }
        return null;
    }

    /** Counters only. Use this when {@link #logging()} is false. */
    public void complete(int status, int protocol, long bytes, long durationNanos) {
        if (this.recording) {
            this.metrics.record(status, protocol, bytes, durationNanos);
        }
    }

    /** Counters plus one access-log line. Use this when {@link #logging()} is true. */
    public void complete(int status, int protocol, long bytes, long durationNanos, String remote, String method,
            String target, String protocolName, String referer, String userAgent) {
        if (!this.recording) {
            return;
        }
        this.metrics.record(status, protocol, bytes, durationNanos);
        if (this.log != null) {
            this.log.offer(remote, method, target, protocolName, status, bytes, durationNanos, referer, userAgent);
        }
    }

    public static int protocolId(String version) {
        if ("HTTP/1.1".equals(version)) {
            return HTTP_1_1;
        }
        if ("HTTP/1.0".equals(version)) {
            return HTTP_1_0;
        }
        if (version != null && version.startsWith("HTTP/2")) {
            return HTTP_2;
        }
        if ("HTTP/3".equals(version)) {
            return HTTP_3;
        }
        return PROTOCOL_OTHER;
    }

    @Override
    public void close() {
        if (this.log != null) {
            this.log.close();
        }
    }

    /**
     * Cluster gauges for the next scrape. Null clears them. Not read on the request path.
     * {@link #NONE} ignores this so the off switch stays a constant.
     */
    void attachCluster(Supplier<ClusterStatus> cluster) {
        if (this != NONE) {
            this.cluster = cluster;
        }
    }

    /**
     * Per-route and per-API-key series for the next scrape. Null clears them.
     * Not read on the request path. {@link #NONE} ignores this.
     */
    void attachLabels(LabelSource labels) {
        if (this != NONE) {
            this.labels = labels;
        }
    }

    /** Process counters, plus cluster and gateway families when those sources are attached. */
    String exposition() {
        var sb = new StringBuilder(this.metrics.prometheus());
        var cluster = this.cluster;
        if (cluster != null) {
            MetricsText.appendCluster(sb, cluster.get());
        }
        var labels = this.labels;
        if (labels != null && labels != LabelSource.NONE) {
            MetricsText.appendGateway(sb, labels);
        }
        return sb.toString();
    }

    private Response metricsResponse(boolean head) {
        byte[] text = exposition().getBytes(StandardCharsets.UTF_8);
        var headers = ResponseHeaders.of().add("content-type", METRICS_CONTENT_TYPE)
                .add("cache-control", "no-store").add("x-content-type-options", "nosniff");
        return new Response(200, headers, Body.Bytes.of(text), head);
    }

    private static boolean isExact(String target, String path) {
        int n = path.length();
        if (target.length() < n || !target.regionMatches(0, path, 0, n)) {
            return false;
        }
        return target.length() == n || target.charAt(n) == '?';
    }
}
