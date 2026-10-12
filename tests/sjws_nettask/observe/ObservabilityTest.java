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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.H3Transports;
import sjws_nettask.HttpTestSupport;
import sjws_nettask.ServerConfig;
import sjws_nettask.ServerConfig.AccessLogFormat;
import sjws_nettask.ServerConfig.MetricsExposure;
import sjws_nettask.SjwsServer;
import sjws_nettask.core.StaticFileHandler;
import sjws_nettask.h3.Http3Server;

/** Access log, in-process metrics, /healthz and /metrics. */
class ObservabilityTest {

    private static final long T0 = Instant.parse("2026-10-12T13:55:36Z").toEpochMilli();

    @TempDir
    Path dir;

    @Test
    void clfAndJsonLines() {
        assertEquals(1970, AccessLine.ymd(0) >>> 16);
        assertEquals(1, (AccessLine.ymd(0) >>> 8) & 0xff);
        assertEquals(1, AccessLine.ymd(0) & 0xff);
        var day = Instant.parse("1969-12-31T00:00:00Z").toEpochMilli() / 86_400_000L;
        assertEquals(1969, AccessLine.ymd(day) >>> 16);

        var clf = AccessLine.clfTime(T0);
        assertEquals("[12/Oct/2026:13:55:36 +0000]", new String(clf, StandardCharsets.US_ASCII));

        var common = line(AccessLogFormat.COMMON, 200, 11, 1_500_000L, "http://ref", "ua \"x\"\n");
        assertTrue(common.startsWith("127.0.0.1 - - [12/Oct/2026:13:55:36 +0000] \"GET /a?b HTTP/1.1\" 200 11 "), common);
        assertTrue(common.endsWith(" 1500\n"), common);
        assertFalse(common.contains("http://ref"), common);

        var combined = line(AccessLogFormat.COMBINED, 204, 0, 0, "http://ref", "ua \"x\"\n");
        assertTrue(combined.contains("\"GET /a?b HTTP/1.1\" 204 - \"http://ref\" \"ua ?x?\" 0\n"), combined);

        var json = line(AccessLogFormat.JSON, 200, 0, 1_500L, "say \"hi\"", "caf\u00e9 \uD83D\uDE00");
        assertTrue(json.contains("\"time\":\"2026-10-12T13:55:36.000Z\""), json);
        assertTrue(json.contains("\"path\":\"/a\\\"b\""), json);
        assertTrue(json.contains("\"bytes\":0"), json);
        assertTrue(json.contains("\"duration_us\":1"), json);
        assertTrue(json.contains("\"referer\":\"say \\\"hi\\\"\""), json);
        assertTrue(json.contains("\"user_agent\":\"caf\u00e9 "), json);
        assertTrue(json.endsWith("}\n"), json);

        var controls = AccessLine.format(AccessLogFormat.JSON, T0 + 7, null, null, null, "/\u0001", "HTTP/1.1", 200, 3,
                -1, "-", "-");
        var text = new String(controls, StandardCharsets.UTF_8);
        assertTrue(text.contains("\"remote\":\"-\""), text);
        assertTrue(text.contains("\"path\":\"/\\u0001\""), text);
        assertTrue(text.contains("\"time\":\"2026-10-12T13:55:36.007Z\""), text);
        assertTrue(text.contains("\"duration_us\":0"), text);
    }

    @Test
    void queuePreservesEveryAcceptedLineAndRejectsWhenFull() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new LogQueue(3));
        var queue = new LogQueue(1024);
        int threads = 4;
        int each = 2000;
        var accepted = new AtomicInteger();
        var start = new CountDownLatch(1);
        var producers = new ArrayList<Thread>();
        for (int t = 0; t < threads; t++) {
            int id = t;
            producers.add(Thread.ofPlatform().start(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                for (int i = 0; i < each; i++) {
                    var line = (id + ":" + i).getBytes(StandardCharsets.US_ASCII);
                    while (!queue.offer(line)) {
                        Thread.onSpinWait();
                    }
                    accepted.incrementAndGet();
                }
            }));
        }
        var consumed = new AtomicInteger();
        var consumer = Thread.ofPlatform().start(() -> {
            while (consumed.get() < threads * each) {
                if (queue.poll() != null) {
                    consumed.incrementAndGet();
                } else {
                    Thread.onSpinWait();
                }
            }
        });
        start.countDown();
        for (var p : producers) {
            p.join(5000);
        }
        consumer.join(5000);
        assertEquals(threads * each, accepted.get());
        assertEquals(accepted.get(), consumed.get());
        assertTrue(queue.isFull() == false);

        var tiny = new LogQueue(2);
        assertTrue(tiny.offer(new byte[] {1}));
        assertTrue(tiny.offer(new byte[] {2}));
        assertFalse(tiny.offer(new byte[] {3}));
        assertArrayEquals(new byte[] {1}, tiny.poll());
        assertTrue(tiny.offer(new byte[] {3}));
        assertArrayEquals(new byte[] {2}, tiny.poll());
        assertArrayEquals(new byte[] {3}, tiny.poll());
        assertNull(tiny.poll());
    }

    @Test
    void writerDoesNotBlockTheCallerAndDropsWhenFull(@TempDir Path tmp) throws Exception {
        var blocked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var out = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                write(new byte[] {(byte) b}, 0, 1);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                blocked.countDown();
                try {
                    if (!release.await(2, TimeUnit.SECONDS)) {
                        throw new IOException("stuck");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", e);
                }
            }
        };
        var log = new AccessLog(out, false, AccessLogFormat.COMMON, 4);
        try {
            long t = System.nanoTime();
            log.offer("1.1.1.1", "GET", "/", "HTTP/1.1", 200, 1, 1_000, "-", "-");
            assertTrue(System.nanoTime() - t < 200_000_000L, "offer waited on the stream");
            assertTrue(blocked.await(2, TimeUnit.SECONDS));
            for (int i = 0; i < 20; i++) {
                long start = System.nanoTime();
                log.offer("1.1.1.1", "GET", "/x", "HTTP/1.1", 200, 1, 1, "-", "-");
                assertTrue(System.nanoTime() - start < 200_000_000L, "offer blocked at i=" + i);
            }
            assertTrue(log.dropped() > 0);
        } finally {
            release.countDown();
            log.close();
            log.close();
        }

        var file = tmp.resolve("access.log");
        try (var started = AccessLog.start(file.toString(), AccessLogFormat.JSON)) {
            started.offer("10.0.0.2", "HEAD", "/q", "HTTP/1.0", 304, 0, 2_000, null, null);
            started.offer("10.0.0.2", "GET", "/q", "HTTP/1.0", 200, 4, 3_000, "ref", "agent");
        }
        var text = Files.readString(file);
        assertTrue(text.contains("\"method\":\"HEAD\""), text);
        assertTrue(text.contains("\"protocol\":\"HTTP/1.0\""), text);
        assertTrue(text.contains("\"bytes\":4"), text);
        assertTrue(text.contains("\"referer\":\"ref\""), text);

        var saved = System.out;
        var captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try (var stdout = AccessLog.start("-", AccessLogFormat.COMMON)) {
            stdout.offer("1.2.3.4", "GET", "/stdout", "HTTP/1.1", 200, 2, 5_000, "-", "-");
        } finally {
            System.setOut(saved);
        }
        assertTrue(captured.toString(StandardCharsets.UTF_8).contains("GET /stdout HTTP/1.1"), captured.toString());

        var broken = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("disk full");
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                throw new IOException("disk full");
            }

            @Override
            public void close() throws IOException {
                throw new IOException("close");
            }
        };
        var failing = new AccessLog(broken, true, AccessLogFormat.JSON, 8);
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (failing.dropped() < 1 && System.nanoTime() < deadline) {
            failing.offer("1.1.1.1", "GET", "/", "HTTP/1.1", 500, 1, 1, "-", "-");
            Thread.sleep(10);
        }
        failing.close();
        assertTrue(failing.dropped() >= 1);
    }

    @Test
    void metricsSnapshotAndPrometheus() {
        var metrics = new ServerMetrics();
        assertEquals(0, metrics.snapshot().latency().meanNanos());
        metrics.setConnectionGauges(null, null);
        metrics.setDropped(null);
        metrics.record(200, RequestObserver.HTTP_1_1, 10, 500_000L);
        metrics.record(404, RequestObserver.HTTP_1_0, 0, 6_000_000L);
        metrics.record(200, RequestObserver.HTTP_3, 5, 11_000_000_000L);
        metrics.record(99, -1, -5, -1);
        metrics.record(999, RequestObserver.HTTP_2, 1, ServerMetrics.LATENCY_BOUNDS_NANOS[0]);
        metrics.setConnectionGauges(() -> 2, () -> 1);
        metrics.setDropped(() -> 4);
        var snap = metrics.snapshot();
        assertEquals(4, snap.requests());
        assertEquals(16, snap.bytesSent());
        assertEquals(2, snap.status(200));
        assertEquals(1, snap.status(404));
        assertEquals(1, snap.status(999));
        assertEquals(0, snap.status(-1));
        assertEquals(0, snap.status(99));
        assertEquals(1, snap.protocol("HTTP/1.0"));
        assertEquals(1, snap.protocol("HTTP/1.1"));
        assertEquals(1, snap.protocol("HTTP/2.0"));
        assertEquals(1, snap.protocol("HTTP/3"));
        assertEquals(1, snap.protocol("nope"));
        assertEquals(3, snap.activeConnections());
        assertEquals(2, snap.http1Connections());
        assertEquals(1, snap.http3Connections());
        assertEquals(4, snap.accessLogDropped());
        assertEquals(3, snap.latency().counts()[0]);
        assertEquals(1, snap.latency().counts()[2]);
        assertEquals(1, snap.latency().counts()[snap.latency().counts().length - 1]);
        assertEquals(11_000_000_000L, snap.latency().maxNanos());
        assertTrue(snap.latency().meanNanos() > 0);
        assertEquals(snap.latency().count(), snap.latency().cumulative(99));
        snap.requestsByStatus()[200] = 0;
        assertEquals(2, metrics.snapshot().status(200));

        var text = metrics.prometheus();
        assertTrue(text.contains("# TYPE sjws_http_requests_total counter"), text);
        assertTrue(text.contains("sjws_http_requests_total{status=\"200\"} 2"), text);
        assertTrue(text.contains("sjws_http_protocol_requests_total{protocol=\"HTTP/1.1\"} 1"), text);
        assertTrue(text.contains("sjws_http_protocol_requests_total{protocol=\"other\"} 1"), text);
        assertTrue(text.contains("sjws_http_response_bytes_total 16\n"), text);
        assertTrue(text.contains("sjws_http_request_duration_seconds_bucket{le=\"+Inf\"} " + snap.latency().count()), text);
        assertTrue(text.contains("sjws_connections_active{protocol=\"HTTP/1.1\"} 2"), text);
        assertTrue(text.contains("sjws_connections_active{protocol=\"HTTP/3\"} 1"), text);
        assertTrue(text.contains("sjws_access_log_dropped_total 4\n"), text);
        assertEquals(0, ServerMetrics.bucket(0));
        assertEquals(ServerMetrics.LATENCY_BOUNDS_NANOS.length - 1, ServerMetrics.bucket(Long.MAX_VALUE));
    }

    @Test
    void endpointsAndNone() throws Exception {
        assertNull(RequestObserver.NONE.intercept("GET", "/healthz", true));
        var before = RequestObserver.NONE.metrics().snapshot().requests();
        RequestObserver.NONE.complete(200, RequestObserver.HTTP_1_1, 1, 1);
        RequestObserver.NONE.complete(200, RequestObserver.HTTP_1_1, 1, 1, "r", "GET", "/", "HTTP/1.1", "-", "-");
        assertEquals(before, RequestObserver.NONE.metrics().snapshot().requests());
        assertFalse(RequestObserver.NONE.logging());
        assertFalse(RequestObserver.NONE.logHeaders());
        RequestObserver.NONE.close();

        var cfg = ServerConfig.builder(dir).metricsExposure(MetricsExposure.LOCALHOST).build();
        try (var obs = RequestObserver.open(cfg)) {
            assertFalse(obs.logging());
            assertEquals(200, obs.intercept("GET", "/healthz", false).status());
            assertEquals("ok\n", bodyText(obs.intercept("GET", "/healthz?ready", true), StandardCharsets.US_ASCII));
            assertTrue(obs.intercept("HEAD", "/healthz", true).headOnly());
            assertNull(obs.intercept("POST", "/healthz", true));
            assertNull(obs.intercept("GET", "/health", true));
            assertNull(obs.intercept("GET", "/", true));
            var metrics = obs.intercept("GET", "/metrics", true);
            assertEquals(200, metrics.status());
            assertTrue(bodyText(metrics, StandardCharsets.UTF_8).contains("sjws_http_requests_total"));
            assertTrue(obs.intercept("HEAD", "/metrics?x", true).headOnly());
            assertEquals(403, obs.intercept("GET", "/metrics", false).status());
            assertNull(obs.intercept("POST", "/metrics", true));
            assertNull(obs.intercept("GET", "/hello.txt", true));
        }

        var open = ServerConfig.builder(dir).metricsExposure(MetricsExposure.ON).build();
        try (var obs = RequestObserver.open(open)) {
            assertEquals(200, obs.intercept("GET", "/metrics", false).status());
        }
        var disabled = ServerConfig.builder(dir).metricsExposure(MetricsExposure.OFF).build();
        try (var obs = RequestObserver.open(disabled)) {
            assertNull(obs.intercept("GET", "/metrics", true));
        }
        assertThrows(IOException.class, () -> RequestObserver.open(ServerConfig.builder(dir).accessLog(dir.toString()).build()));
        assertEquals(RequestObserver.HTTP_1_1, RequestObserver.protocolId("HTTP/1.1"));
        assertEquals(RequestObserver.HTTP_1_0, RequestObserver.protocolId("HTTP/1.0"));
        assertEquals(RequestObserver.HTTP_2, RequestObserver.protocolId("HTTP/2"));
        assertEquals(RequestObserver.PROTOCOL_OTHER, RequestObserver.protocolId(null));
        assertEquals(RequestObserver.PROTOCOL_OTHER, RequestObserver.protocolId(""));
    }

    @Test
    void httpServerLogsAndServesProbes() throws Exception {
        Files.writeString(dir.resolve("index.html"), "hello");
        Files.writeString(dir.resolve("metrics"), "not-the-endpoint");
        var log = dir.resolve("access.log");
        var config = ServerConfig.builder(dir).port(0).accessLog(log.toString()).accessLogFormat(AccessLogFormat.COMBINED)
                .metricsExposure(MetricsExposure.LOCALHOST).build();
        try (var server = SjwsServer.start(config)) {
            assertTrue(server.describe().contains("access log " + log), server.describe());
            assertTrue(server.describe().contains("metrics localhost"), server.describe());
            try (var socket = new Socket(InetAddress.getLoopbackAddress(), server.tcpPort())) {
                socket.setSoTimeout(2000);
                HttpTestSupport.write(socket.getOutputStream(),
                        "GET /index.html HTTP/1.1\r\nHost: h\r\nReferer: http://ref\r\nUser-Agent: ua \"x\"\r\n\r\n");
                var ok = HttpTestSupport.readResponse(socket.getInputStream());
                assertEquals(200, ok.statusCode());
                var snap = server.metrics().snapshot();
                assertTrue(snap.http1Connections() >= 1, "gauge while the connection is open: " + snap.http1Connections());
                assertTrue(snap.requests() >= 1);
                assertEquals(1, snap.protocol("HTTP/1.1"));
            }
            var health = HttpTestSupport.get(server.tcpPort(), "/healthz", 2000);
            assertEquals(200, health.statusCode());
            assertEquals("ok\n", health.bodyText());
            var head = HttpTestSupport.rawRequest(server.tcpPort(), "HEAD /healthz HTTP/1.1\r\nHost: h\r\n\r\n", 2000);
            assertEquals(200, head.statusCode());
            assertEquals(0, head.body().length);
            assertEquals("3", head.headers().get("content-length"));
            var metrics = HttpTestSupport.get(server.tcpPort(), "/metrics", 2000);
            assertEquals(200, metrics.statusCode());
            assertTrue(metrics.bodyText().contains("sjws_connections_active"), metrics.bodyText());
            assertFalse(metrics.bodyText().contains("not-the-endpoint"));
            var missing = HttpTestSupport.get(server.tcpPort(), "/nope", 2000);
            assertEquals(404, missing.statusCode());
            var bad = HttpTestSupport.rawRequest(server.tcpPort(), "GET / HTTP/1.1\r\n\r\n", 2000);
            assertEquals(400, bad.statusCode());
        }
        var text = Files.readString(log);
        assertTrue(text.contains("\"GET /index.html HTTP/1.1\" 200 5 \"http://ref\" \"ua ?x\""), text);
        assertTrue(text.contains("\"GET /healthz HTTP/1.1\" 200 3"), text);
        assertTrue(text.contains("\"GET /metrics HTTP/1.1\" 200"), text);
        assertTrue(text.contains(" 404 "), text);
        assertTrue(text.contains(" 400 "), text);

        try (var plain = SjwsServer.start(ServerConfig.builder(dir).port(0).build())) {
            var file = HttpTestSupport.get(plain.tcpPort(), "/metrics", 2000);
            assertEquals(200, file.statusCode());
            assertEquals("not-the-endpoint", file.bodyText());
            assertTrue(plain.metrics().snapshot().requests() >= 1);
            assertFalse(plain.describe().contains("access log"), plain.describe());
        }

        var commonLog = dir.resolve("common.log");
        try (var common = SjwsServer.start(ServerConfig.builder(dir).port(0).accessLog(commonLog.toString())
                .accessLogFormat(AccessLogFormat.COMMON).build())) {
            HttpTestSupport.rawRequest(common.tcpPort(),
                    "GET /index.html HTTP/1.1\r\nHost: h\r\nReferer: http://secret\r\n\r\n", 2000);
        }
        var commonText = Files.readString(commonLog);
        assertTrue(commonText.contains("\"GET /index.html HTTP/1.1\" 200 5 "), commonText);
        assertFalse(commonText.contains("secret"), commonText);
    }

    @Test
    void startupFailuresCloseTheLogAndTheFactoryReceivesTheObserver() throws Exception {
        var log = dir.resolve("early.log");
        var notDir = dir.resolve("file");
        Files.writeString(notDir, "x");
        assertThrows(java.io.UncheckedIOException.class, () -> SjwsServer.start(
                ServerConfig.builder(notDir).port(0).accessLog(log.toString()).build()));
        assertThrows(IOException.class, () -> SjwsServer.start(
                ServerConfig.builder(dir).port(0).accessLog(dir.toString()).build()));

        var handler = new StaticFileHandler(dir);
        var observer = RequestObserver.open(ServerConfig.builder(dir).build());
        var transport = H3Transports.defaultFactory().create(ServerConfig.builder(dir).build(), handler, observer);
        assertInstanceOf(Http3Server.class, transport);
        var http3 = (Http3Server) transport;
        try {
            try {
                http3.start();
            } catch (IOException nativesMissing) {
                // start() sets the started flag before the native check, then leaves nothing bound
            }
            assertThrows(IllegalStateException.class, () -> http3.withObserver(observer));
        } finally {
            http3.close();
            observer.close();
        }
    }

    private static String bodyText(sjws_nettask.core.Response response, java.nio.charset.Charset charset) {
        var data = ((sjws_nettask.core.Body.Bytes) response.body()).data().duplicate();
        var raw = new byte[data.remaining()];
        data.get(raw);
        return new String(raw, charset);
    }

    private static String line(AccessLogFormat format, int status, long bytes, long nanos, String referer, String ua) {
        return new String(AccessLine.format(format, T0, AccessLine.clfTime(T0), "127.0.0.1", "GET", "/a\"b", "HTTP/1.1",
                status, bytes, nanos, referer, ua), StandardCharsets.UTF_8);
    }
}
