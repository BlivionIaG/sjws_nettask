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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;

import sjws_nettask.TestCommands;
import sjws_nettask.HttpTestSupport;
import sjws_nettask.ServerConfig;
import sjws_nettask.ServerConfig.MetricsExposure;
import sjws_nettask.SjwsServer;
import sjws_nettask.admin.EmbeddedServerControl;
import sjws_nettask.admin.LogBuffer;

/** Prometheus text 0.0.4, a real scrape, and the example Grafana dashboard. */
class PrometheusScrapeTest {

    private static final String NAME = "[a-zA-Z_:][a-zA-Z0-9_:]*";
    private static final String LABEL = "[a-zA-Z_][a-zA-Z0-9_]*";

    @TempDir
    Path dir;

    @Test
    void sampleTokens() {
        assertEquals("0", MetricsText.number(0));
        assertEquals("0", MetricsText.number(-0.0));
        assertEquals("1000", MetricsText.number(1000));
        assertEquals("12.500000", MetricsText.number(12.5));
        assertEquals("NaN", MetricsText.number(Double.NaN));
        assertEquals("+Inf", MetricsText.number(Double.POSITIVE_INFINITY));
        assertEquals("-Inf", MetricsText.number(Double.NEGATIVE_INFINITY));
        assertTrue(MetricsText.number(1e20).startsWith("1"));
        assertEquals("0", MetricsText.seconds(-1));
        assertEquals("0", MetricsText.seconds(0));
        assertEquals("+Inf", MetricsText.seconds(Long.MAX_VALUE));
        assertEquals("0.001", MetricsText.seconds(ServerMetrics.LATENCY_BOUNDS_NANOS[0]));
        assertEquals("0.001500", MetricsText.seconds(1_500_000L));
        assertEquals("plain", MetricsText.escape("plain"));
        assertEquals("a\\\"b\\\\\\n", MetricsText.escape("a\"b\\\n"));
    }

    @Test
    void expositionIsText004() throws Exception {
        var counts = LabelSeries.emptyBuckets();
        counts[0] = 3;
        var later = buckets(1, 0);
        var status = new ClusterStatus(4, 12.5, ServerMetrics.LATENCY_BOUNDS_NANOS[1], 20, 2, 1, new ClusterStatus.Node[] {
                node("plain", true, true, 1, 0, 1_000_000L),
                node("plain", true, false, 9, 1000, 1_000_000L),
                node("a\"b\\\n", false, true, 4, Double.POSITIVE_INFINITY, Long.MAX_VALUE) },
                new ClusterStatus.Series[] {
                        new ClusterStatus.Series("wide", 2, 1, 1_000_000L),
                        new ClusterStatus.Series("wide", 3, 1, 2_000_000L),
                        new ClusterStatus.Series("open", 1, 0, Long.MAX_VALUE),
                        new ClusterStatus.Series("open", 1, 0, 1_000_000L),
                        new ClusterStatus.Series("odd", 1, 0, 1_500_000L) },
                new ClusterStatus.Series[] { new ClusterStatus.Series("kid", 8, 1, 1_000_000L) });
        var gateway = source(new LabelSeries[] {
                new LabelSeries("/api", 2, 1, counts),
                new LabelSeries("/api", 3, 4, later) },
                new LabelSeries[] { new LabelSeries("kid", 4, 0, counts) });

        var cfg = ServerConfig.builder(dir).metricsExposure(MetricsExposure.ON).build();
        try (var obs = RequestObserver.open(cfg)) {
            obs.complete(200, RequestObserver.HTTP_1_1, 10, 1_500_000L);
            obs.complete(500, RequestObserver.HTTP_3, 1, 11_000_000_000L);
            var plain = obs.exposition();
            assertFalse(plain.contains("sjws_cluster_"));
            assertFalse(plain.contains("sjws_gateway_"));
            obs.attachCluster(() -> null);
            assertFalse(obs.exposition().contains("sjws_cluster_desired_version"));
            obs.attachLabels(LabelSource.NONE);
            assertFalse(obs.exposition().contains("sjws_gateway_"));
            obs.attachCluster(() -> status);
            obs.attachLabels(gateway);
            var text = obs.exposition();
            assertTrue(text.startsWith(plain));
            assertTrue(text.contains("sjws_http_requests_total{status=\"200\"} 1\n"), text);
            assertTrue(text.contains("sjws_http_requests_total{status=\"500\"} 1\n"), text);
            assertTrue(text.contains("sjws_cluster_desired_version 4\n"), text);
            assertTrue(text.contains("sjws_cluster_requests_per_second 12.500000\n"), text);
            assertTrue(text.contains("sjws_cluster_request_p99_seconds 0.002\n"), text);
            assertTrue(text.contains("sjws_cluster_node_requests{node=\"plain\"} 9\n"), text);
            assertEquals(2, count(text, "sjws_cluster_node_requests{"));
            assertTrue(text.contains("sjws_cluster_node_alive{node=\"a\\\"b\\\\\\n\"} 0\n"), text);
            assertTrue(text.contains("sjws_cluster_node_ready{node=\"plain\"} 1\n"), text);
            assertTrue(text.contains("sjws_cluster_node_draining{node=\"a\\\"b\\\\\\n\"} 1\n"), text);
            assertTrue(text.contains("sjws_cluster_node_requests_per_second{node=\"plain\"} 1000\n"), text);
            assertTrue(text.contains("sjws_cluster_node_requests_per_second{node=\"a\\\"b\\\\\\n\"} +Inf\n"), text);
            assertTrue(text.contains("sjws_cluster_node_request_p99_seconds{node=\"a\\\"b\\\\\\n\"} +Inf\n"), text);
            assertTrue(text.contains("sjws_cluster_route_requests{route=\"wide\"} 5\n"), text);
            assertTrue(text.contains("sjws_cluster_route_errors{route=\"wide\"} 2\n"), text);
            assertTrue(text.contains("sjws_cluster_route_request_p99_seconds{route=\"wide\"} 0.002\n"), text);
            assertTrue(text.contains("sjws_cluster_route_request_p99_seconds{route=\"open\"} +Inf\n"), text);
            assertTrue(text.contains("sjws_cluster_route_request_p99_seconds{route=\"odd\"} 0.001500\n"), text);
            assertTrue(text.contains("sjws_cluster_api_key_requests{api_key=\"kid\"} 8\n"), text);
            assertTrue(text.contains("sjws_gateway_route_requests_total{route=\"/api\"} 5\n"), text);
            assertTrue(text.contains("sjws_gateway_route_errors_total{route=\"/api\"} 5\n"), text);
            assertTrue(text.contains("sjws_gateway_route_request_p99_seconds{route=\"/api\"} 0.001\n"), text);
            assertTrue(text.contains("sjws_gateway_api_key_requests_total{api_key=\"kid\"} 4\n"), text);
            assertExposition(text);
            var response = obs.intercept("GET", "/metrics", false);
            assertEquals(200, response.status());
            assertEquals(RequestObserver.METRICS_CONTENT_TYPE, response.headers().get("content-type"));
            assertEquals("no-store", response.headers().get("cache-control"));
            assertEquals(text, body(response));
            assertTrue(obs.intercept("HEAD", "/metrics", true).headOnly());
            checkMetrics(text);
        }
        RequestObserver.NONE.attachCluster(() -> status);
        RequestObserver.NONE.attachLabels(gateway);
        assertFalse(RequestObserver.NONE.exposition().contains("sjws_cluster_"));
        assertFalse(RequestObserver.NONE.exposition().contains("sjws_gateway_"));
    }

    @Test
    void gatewayNamesFoldAndEmptyTablesStayOut() throws Exception {
        var many = new LabelSeries[ClusterBoard.MAX_MERGED_SERIES + 1];
        for (int i = 0; i < ClusterBoard.MAX_MERGED_SERIES; i++) {
            many[i] = new LabelSeries("r" + i, 1, 0, LabelSeries.emptyBuckets());
        }
        many[ClusterBoard.MAX_MERGED_SERIES] = new LabelSeries("r-extra", 5, 2, buckets(0, 4));
        var capped = new LabelSeries[ClusterBoard.MAX_MERGED_SERIES + 1];
        for (int i = 0; i < ClusterBoard.MAX_MERGED_SERIES - 1; i++) {
            capped[i] = new LabelSeries("k" + i, 1, 0, LabelSeries.emptyBuckets());
        }
        capped[ClusterBoard.MAX_MERGED_SERIES - 1] = new LabelSeries("other", 1, 0, LabelSeries.emptyBuckets());
        capped[ClusterBoard.MAX_MERGED_SERIES] = new LabelSeries("k-extra", 5, 2, buckets(0, 4));
        var cfg = ServerConfig.builder(dir).metricsExposure(MetricsExposure.ON).build();
        try (var obs = RequestObserver.open(cfg)) {
            obs.attachLabels(source(many, capped));
            var text = obs.exposition();
            assertTrue(text.contains("sjws_gateway_route_requests_total{route=\"other\"} 5\n"), text);
            assertTrue(text.contains("sjws_gateway_route_errors_total{route=\"other\"} 2\n"), text);
            assertEquals(ClusterBoard.MAX_MERGED_SERIES + 1, count(text, "sjws_gateway_route_requests_total{"));
            assertTrue(text.contains("sjws_gateway_api_key_requests_total{api_key=\"other\"} 6\n"), text);
            assertEquals(ClusterBoard.MAX_MERGED_SERIES, count(text, "sjws_gateway_api_key_requests_total{"));
            obs.attachLabels(source(new LabelSeries[] {null}, null));
            var quiet = obs.exposition();
            assertFalse(quiet.contains("sjws_gateway_"));
            obs.attachLabels(null);
            assertFalse(obs.exposition().contains("sjws_gateway_"));
            MetricsText.appendCluster(new StringBuilder(), null);
            MetricsText.appendGateway(new StringBuilder(), null);
        }
        var empty = new ClusterStatus(0, 0, 0, 0, 0, 0, new ClusterStatus.Node[0], new ClusterStatus.Series[0],
                new ClusterStatus.Series[0]);
        var sb = new StringBuilder();
        MetricsText.appendCluster(sb, empty);
        var scalars = sb.toString();
        assertTrue(scalars.contains("sjws_cluster_requests 0\n"), scalars);
        assertTrue(scalars.contains("sjws_cluster_requests_per_second 0\n"), scalars);
        assertFalse(scalars.contains("sjws_cluster_node_"));
        assertFalse(scalars.contains("sjws_cluster_route_"));
        assertFalse(scalars.contains("sjws_cluster_api_key_"));
        assertExposition(scalars);
    }

    @Test
    void attachingDoesNotArmTheRequestPath() throws Exception {
        Files.writeString(dir.resolve("metrics"), "from-disk");
        var cfg = ServerConfig.builder(dir).port(0).build();
        try (var server = SjwsServer.start(cfg)) {
            server.attachClusterMetrics(() -> new ClusterStatus(1, 0, 0, 0, 0, 0, new ClusterStatus.Node[0],
                    new ClusterStatus.Series[0], new ClusterStatus.Series[0]));
            server.attachGatewayMetrics(source(new LabelSeries[] {new LabelSeries("/api", 1, 0, LabelSeries.emptyBuckets())},
                    new LabelSeries[0]));
            assertNull(server.filterChain());
            var file = HttpTestSupport.get(server.tcpPort(), "/metrics", 2000);
            assertEquals(200, file.statusCode());
            assertEquals("from-disk", file.bodyText());
            assertEquals(0, server.metrics().snapshot().requests());
        }
    }

    @Test
    void sourcesSurviveArmAndSet() throws Exception {
        var status = new ClusterStatus(2, 0, 0, 1, 0, 0, new ClusterStatus.Node[0], new ClusterStatus.Series[0],
                new ClusterStatus.Series[0]);
        var slot = new ObserverSlot(RequestObserver.NONE);
        slot.attachCluster(() -> status);
        assertFalse(RequestObserver.NONE.exposition().contains("sjws_cluster_"));
        var cfg = ServerConfig.builder(dir).metricsExposure(MetricsExposure.ON).build();
        var armed = slot.arm(cfg);
        try {
            assertTrue(armed.exposition().contains("sjws_cluster_desired_version 2\n"));
            var gateway = source(new LabelSeries[] {new LabelSeries("/v1", 1, 0, LabelSeries.emptyBuckets())},
                    new LabelSeries[0]);
            slot.attachLabels(gateway);
            assertTrue(armed.exposition().contains("sjws_gateway_route_requests_total{route=\"/v1\"} 1\n"));
        } finally {
            armed.close();
        }
        var fresh = new ObserverSlot(RequestObserver.NONE);
        fresh.attachLabels(source(new LabelSeries[] {new LabelSeries("/set", 1, 0, LabelSeries.emptyBuckets())},
                new LabelSeries[0]));
        try (var obs = RequestObserver.open(cfg)) {
            fresh.set(obs);
            assertTrue(obs.exposition().contains("sjws_gateway_route_requests_total{route=\"/set\"} 1\n"));
            fresh.set(null);
            assertEquals(RequestObserver.NONE, fresh.get());
        }
    }

    @Test
    void embeddedControlPublishesTheBoard() throws Exception {
        var board = new ClusterBoard();
        board.desiredVersion(3);
        var counts = buckets(4, 0);
        board.ingest(new NodeReport("edge", 1, 1_000, true, true, false, 3, 1, 10, 1, 0, 0, 0, counts,
                new LabelSeries[] {new LabelSeries("/api", 10, 1, counts)},
                new LabelSeries[] {new LabelSeries("kid", 10, 0, counts)}));
        board.ingest(new NodeReport("edge", 2, 2_000, true, true, false, 3, 1, 20, 1, 0, 0, 0, counts,
                new LabelSeries[] {new LabelSeries("/api", 20, 1, counts)},
                new LabelSeries[] {new LabelSeries("kid", 20, 0, counts)}));
        try (var control = new EmbeddedServerControl(
                () -> ServerConfig.builder(dir).port(0).metricsExposure(MetricsExposure.ON).build(), new LogBuffer(8))) {
            control.attachCluster(board);
            control.start();
            var first = scrape(control);
            assertTrue(first.contains("sjws_cluster_desired_version 3\n"), first);
            assertTrue(first.contains("sjws_cluster_requests_per_second 10\n"), first);
            assertTrue(first.contains("sjws_cluster_route_requests{route=\"/api\"} 20\n"), first);
            assertTrue(first.contains("sjws_cluster_api_key_requests{api_key=\"kid\"} 20\n"), first);
            assertEquals(RequestObserver.METRICS_CONTENT_TYPE, HttpTestSupport.get(control.status().tcpPort(), "/metrics", 2000)
                    .headers().get("content-type"));
            control.attachCluster(null);
            assertFalse(scrape(control).contains("sjws_cluster_"));
            control.attachCluster(board);
            assertTrue(scrape(control).contains("sjws_cluster_desired_version 3\n"));
        }
    }

    @Test
    void prometheusScrapesLoopbackMetrics() throws Exception {
        var prometheus = tool("PROMETHEUS", "prometheus");
        Assumptions.assumeTrue(prometheus != null, "prometheus binary not installed");
        Files.writeString(dir.resolve("index.html"), "hello");
        var counts = buckets(2, 0);
        var board = new ClusterBoard();
        board.ingest(new NodeReport("edge", 1, 1_000, true, true, false, 1, 1, 5, 0, 0, 0, 0, counts,
                new LabelSeries[] {new LabelSeries("/api", 5, 0, counts)}, new LabelSeries[0]));
        var cfg = ServerConfig.builder(dir).port(0).metricsExposure(MetricsExposure.LOCALHOST).build();
        try (var server = SjwsServer.start(cfg)) {
            server.attachClusterMetrics(board::status);
            server.attachGatewayMetrics(source(new LabelSeries[] {new LabelSeries("/api", 5, 1, counts)},
                    new LabelSeries[] {new LabelSeries("kid", 5, 1, counts)}));
            HttpTestSupport.get(server.tcpPort(), "/index.html", 2000);
            var page = HttpTestSupport.get(server.tcpPort(), "/metrics", 2000);
            assertEquals(200, page.statusCode());
            assertEquals(RequestObserver.METRICS_CONTENT_TYPE, page.headers().get("content-type"));
            assertTrue(page.bodyText().contains("sjws_http_requests_total{status=\"200\"}"), page.bodyText());
            assertTrue(page.bodyText().contains("sjws_gateway_route_requests_total{route=\"/api\"} 5\n"), page.bodyText());
            assertExposition(page.bodyText());
            checkMetrics(page.bodyText());

            int promPort;
            try (var socket = new ServerSocket(0)) {
                promPort = socket.getLocalPort();
            }
            var config = dir.resolve("prometheus.yml");
            Files.writeString(config, """
                    global:
                      scrape_interval: 1s
                      scrape_timeout: 1s
                    scrape_configs:
                      - job_name: sjws
                        metrics_path: /metrics
                        static_configs:
                          - targets: ["127.0.0.1:%d"]
                    """.formatted(server.tcpPort()));
            var data = dir.resolve("tsdb");
            Files.createDirectories(data);
            var proc = new ProcessBuilder(prometheus.toString(), "--config.file=" + config, "--storage.tsdb.path=" + data,
                    "--web.listen-address=127.0.0.1:" + promPort, "--log.level=warn").redirectErrorStream(true)
                    .redirectOutput(dir.resolve("prometheus.log").toFile()).start();
            try {
                var base = "http://127.0.0.1:" + promPort;
                var targets = awaitBody(base + "/api/v1/targets", body -> body.contains("\"health\":\"up\""), Duration.ofSeconds(30));
                assertTrue(targets.contains("\"health\":\"up\""), targets + "\n" + Files.readString(dir.resolve("prometheus.log")));
                var count = query(base, "sjws_http_request_duration_seconds_count");
                assertTrue(count.contains("\"status\":\"success\""), count);
                assertTrue(count.contains("sjws_http_request_duration_seconds_count"), count);
                var cluster = query(base, "sjws_cluster_requests");
                assertTrue(cluster.contains("\"value\""), cluster);
                var route = query(base, "sjws_gateway_route_errors_total");
                assertTrue(route.contains("/api"), route);
            } finally {
                proc.destroy();
                if (!proc.waitFor(3, TimeUnit.SECONDS)) {
                    proc.destroyForcibly();
                }
            }
        }
    }

    @Test
    void grafanaDashboardIsValid() throws Exception {
        var json = Path.of("contrib/grafana/sjws.json");
        var config = Path.of("contrib/grafana/prometheus.yml");
        assertTrue(Files.isRegularFile(json), json.toAbsolutePath().toString());
        assertTrue(Files.isRegularFile(config));
        var py = new ProcessBuilder(TestCommands.resolve("python3"), "-c", """
                import json, sys
                d = json.load(open(sys.argv[1]))
                assert d["title"] == "sjws" and d["uid"] == "sjws-observe"
                assert d["schemaVersion"] >= 39
                assert len(d["panels"]) >= 16
                titles = [p["title"] for p in d["panels"]]
                for need in ["Request rate", "Latency p50", "Latency p99", "Errors by status", "Response bytes",
                             "Connections", "Access-log drops", "Cluster request rate", "Route request rate"]:
                    assert need in titles, titles
                """, json.toString()).redirectErrorStream(true).start();
        var pyOut = new String(py.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(py.waitFor(10, TimeUnit.SECONDS));
        assertEquals(0, py.exitValue(), pyOut);

        var exprs = exprs(Files.readString(json));
        assertTrue(exprs.size() >= 16, exprs.toString());
        assertTrue(exprs.stream().anyMatch(e -> e.contains("histogram_quantile(0.50")));
        assertTrue(exprs.stream().anyMatch(e -> e.contains("histogram_quantile(0.99")));
        assertTrue(exprs.stream().anyMatch(e -> e.contains("sjws_http_requests_total")));
        assertTrue(exprs.stream().anyMatch(e -> e.contains("sjws_http_response_bytes_total")));
        assertTrue(exprs.stream().anyMatch(e -> e.contains("sjws_connections_active")));
        assertTrue(exprs.stream().anyMatch(e -> e.contains("sjws_access_log_dropped_total")));
        assertTrue(exprs.stream().anyMatch(e -> e.contains("sjws_gateway_route_requests_total")));
        assertTrue(exprs.stream().anyMatch(e -> e.contains("sjws_cluster_requests_per_second")));
        var rules = dir.resolve("dashboard.yml");
        var yaml = new StringBuilder("groups:\n  - name: sjws_dashboard\n    rules:\n");
        for (int i = 0; i < exprs.size(); i++) {
            yaml.append("      - record: sjws_dashboard_check_").append(i).append('\n');
            yaml.append("        expr: \"").append(exprs.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append("\"\n");
        }
        Files.writeString(rules, yaml);
        var promtool = tool("PROMTOOL", "promtool");
        if (promtool != null) {
            run(promtool, null, "check", "rules", rules.toString());
            run(promtool, null, "check", "config", config.toString());
        }
    }

    private static String scrape(EmbeddedServerControl control) throws Exception {
        return HttpTestSupport.get(control.status().tcpPort(), "/metrics", 2000).bodyText();
    }

    private static String query(String base, String expr) throws Exception {
        var url = base + "/api/v1/query?query=" + URLEncoder.encode(expr, StandardCharsets.UTF_8);
        return awaitBody(url, body -> body.contains("\"result\":[") && !body.contains("\"result\":[]"), Duration.ofSeconds(20));
    }

    private static String awaitBody(String url, java.util.function.Predicate<String> ready, Duration timeout) throws Exception {
        long end = System.nanoTime() + timeout.toNanos();
        String last = "";
        while (System.nanoTime() < end) {
            try {
                var conn = (java.net.HttpURLConnection) URI.create(url).toURL().openConnection();
                conn.setConnectTimeout(1000);
                conn.setReadTimeout(1000);
                try (var in = conn.getResponseCode() < 400 ? conn.getInputStream() : conn.getErrorStream()) {
                    last = in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
                if (ready.test(last)) {
                    return last;
                }
            } catch (java.io.IOException ignored) {
                last = ignored.toString();
            }
            Thread.sleep(200);
        }
        return last;
    }

    private static void checkMetrics(String text) throws Exception {
        var promtool = tool("PROMTOOL", "promtool");
        if (promtool == null) {
            return;
        }
        var file = Files.createTempFile("sjws-metrics", ".txt");
        Files.writeString(file, text);
        run(promtool, file, "check", "metrics");
    }

    private static void run(Path bin, Path input, String... args) throws Exception {
        var cmd = new ArrayList<String>();
        cmd.add(bin.toString());
        cmd.addAll(List.of(args));
        var proc = new ProcessBuilder(cmd);
        if (input != null) {
            proc.redirectInput(input.toFile());
        }
        var started = proc.redirectErrorStream(true).start();
        var out = new String(started.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(started.waitFor(30, TimeUnit.SECONDS), out);
        assertEquals(0, started.exitValue(), out);
    }

    private static Path tool(String env, String name) {
        var raw = System.getenv(env);
        if (raw != null && !raw.isBlank() && Files.isExecutable(Path.of(raw))) {
            return Path.of(raw);
        }
        var path = System.getenv("PATH");
        if (path != null) {
            for (var entry : path.split(":")) {
                var candidate = Path.of(entry).resolve(name);
                if (Files.isExecutable(candidate)) {
                    return candidate;
                }
            }
        }
        if ("true".equals(System.getenv("CI")) && System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux")) {
            fail(name + " is required on Linux CI (" + env + ")");
        }
        return null;
    }

    private static void assertExposition(String text) {
        assertTrue(text.endsWith("\n"), text);
        assertFalse(text.contains("\n\n"), text);
        var seen = new HashSet<String>();
        String family = null;
        String type = null;
        String histogram = null;
        var les = new ArrayList<Double>();
        var buckets = new ArrayList<Double>();
        boolean sawSum = false;
        Double count = null;
        for (var line : text.split("\n", -1)) {
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("# HELP ")) {
                closeHistogram(histogram, les, buckets, sawSum, count);
                histogram = null;
                var rest = line.substring("# HELP ".length());
                int space = rest.indexOf(' ');
                assertTrue(space > 1, line);
                family = rest.substring(0, space);
                assertTrue(family.matches(NAME), family);
                assertFalse(rest.substring(space + 1).isBlank(), line);
                type = null;
            } else if (line.startsWith("# TYPE ")) {
                assertNotNull(family, line);
                assertNull(type, line);
                var rest = line.substring("# TYPE ".length());
                int space = rest.indexOf(' ');
                assertEquals(family, rest.substring(0, space), line);
                type = rest.substring(space + 1);
                assertTrue(type.equals("counter") || type.equals("gauge") || type.equals("histogram"), type);
                if ("histogram".equals(type)) {
                    histogram = family;
                    les = new ArrayList<>();
                    buckets = new ArrayList<>();
                    sawSum = false;
                    count = null;
                }
            } else if (line.startsWith("#")) {
                fail(line);
            } else {
                assertNotNull(type, line);
                var sample = parse(line);
                assertTrue(seen.add(sample.name + sample.labels), line);
                for (var label : sample.labelNames) {
                    assertTrue(label.matches(LABEL), label);
                }
                if (histogram != null) {
                    if (sample.name.equals(histogram + "_bucket")) {
                        double le = le(sample.labels);
                        if (!les.isEmpty()) {
                            assertTrue(le > les.get(les.size() - 1), line);
                            assertTrue(sample.value >= buckets.get(buckets.size() - 1), line);
                        }
                        les.add(le);
                        buckets.add(sample.value);
                    } else if (sample.name.equals(histogram + "_sum")) {
                        sawSum = true;
                    } else if (sample.name.equals(histogram + "_count")) {
                        count = sample.value;
                    } else {
                        fail(line);
                    }
                } else {
                    assertEquals(family, sample.name, line);
                    if ("counter".equals(type)) {
                        assertTrue(sample.name.endsWith("_total"), line);
                    }
                }
            }
        }
        closeHistogram(histogram, les, buckets, sawSum, count);
    }

    private static void closeHistogram(String histogram, List<Double> les, List<Double> buckets, boolean sawSum, Double count) {
        if (histogram == null) {
            return;
        }
        assertFalse(les.isEmpty(), histogram);
        assertEquals(Double.POSITIVE_INFINITY, les.get(les.size() - 1));
        assertEquals("+Inf", ServerMetrics.LATENCY_LE_LABELS[ServerMetrics.LATENCY_LE_LABELS.length - 1]);
        assertTrue(sawSum, histogram);
        assertNotNull(count, histogram);
        assertEquals(count, buckets.get(buckets.size() - 1));
    }

    private static double le(String labels) {
        int at = labels.indexOf("le=\"");
        assertTrue(at >= 0, labels);
        var raw = quoted(labels, at + 4);
        if ("+Inf".equals(raw)) {
            return Double.POSITIVE_INFINITY;
        }
        return Double.parseDouble(raw);
    }

    private static Sample parse(String line) {
        int brace = line.indexOf('{');
        String name;
        String labels = "";
        String raw;
        if (brace < 0) {
            int space = line.indexOf(' ');
            name = line.substring(0, space);
            raw = line.substring(space + 1);
        } else {
            name = line.substring(0, brace);
            int end = brace + 1;
            boolean escape = false;
            for (; end < line.length(); end++) {
                char c = line.charAt(end);
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '}') {
                    break;
                }
            }
            labels = line.substring(brace + 1, end);
            raw = line.substring(end + 1).trim();
        }
        assertTrue(name.matches(NAME), name);
        var labelNames = new ArrayList<String>();
        if (!labels.isEmpty()) {
            int i = 0;
            while (i < labels.length()) {
                int eq = labels.indexOf('=', i);
                var label = labels.substring(i, eq);
                labelNames.add(label);
                assertEquals('"', labels.charAt(eq + 1));
                i = skipQuoted(labels, eq + 1);
                if (i < labels.length() && labels.charAt(i) == ',') {
                    i++;
                }
            }
        }
        return new Sample(name, labels, labelNames, value(raw));
    }

    private static int skipQuoted(String text, int openQuote) {
        boolean escape = false;
        for (int i = openQuote + 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escape) {
                escape = false;
            } else if (c == '\\') {
                escape = true;
            } else if (c == '"') {
                return i + 1;
            }
        }
        fail(text);
        return text.length();
    }

    private static String quoted(String text, int start) {
        var sb = new StringBuilder();
        boolean escape = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escape) {
                sb.append(c);
                escape = false;
            } else if (c == '\\') {
                escape = true;
            } else if (c == '"') {
                return sb.toString();
            } else {
                sb.append(c);
            }
        }
        fail(text);
        return "";
    }

    private static double value(String raw) {
        return switch (raw) {
            case "+Inf" -> Double.POSITIVE_INFINITY;
            case "-Inf" -> Double.NEGATIVE_INFINITY;
            case "NaN" -> Double.NaN;
            default -> Double.parseDouble(raw);
        };
    }

    private static List<String> exprs(String json) {
        var out = new ArrayList<String>();
        int from = 0;
        while (true) {
            int key = json.indexOf("\"expr\"", from);
            if (key < 0) {
                break;
            }
            int quote = json.indexOf('"', json.indexOf(':', key) + 1);
            var sb = new StringBuilder();
            int p = quote + 1;
            for (; p < json.length(); p++) {
                char c = json.charAt(p);
                if (c == '\\') {
                    char n = json.charAt(++p);
                    sb.append(n == 'n' ? '\n' : n == 't' ? '\t' : n);
                } else if (c == '"') {
                    break;
                } else {
                    sb.append(c);
                }
            }
            out.add(sb.toString());
            from = p + 1;
        }
        return out;
    }

    private static int count(String text, String needle) {
        int n = 0;
        int at = 0;
        while ((at = text.indexOf(needle, at)) >= 0) {
            n++;
            at += needle.length();
        }
        return n;
    }

    private static String body(sjws_nettask.core.Response response) {
        var data = ((sjws_nettask.core.Body.Bytes) response.body()).data().duplicate();
        var raw = new byte[data.remaining()];
        data.get(raw);
        return new String(raw, StandardCharsets.UTF_8);
    }

    private static long[] buckets(long first, long second) {
        var counts = LabelSeries.emptyBuckets();
        counts[0] = first;
        counts[1] = second;
        return counts;
    }

    private static ClusterStatus.Node node(String id, boolean alive, boolean draining, long requests, double rps, long p99) {
        return new ClusterStatus.Node(id, alive, !draining, draining, draining ? 1 : 4, draining, requests, 1, rps, p99);
    }

    private static LabelSource source(LabelSeries[] routes, LabelSeries[] apiKeys) {
        return new LabelSource() {
            @Override
            public LabelSeries[] routes() {
                return routes;
            }

            @Override
            public LabelSeries[] apiKeys() {
                return apiKeys;
            }
        };
    }

    private record Sample(String name, String labels, List<String> labelNames, double value) {
    }
}
