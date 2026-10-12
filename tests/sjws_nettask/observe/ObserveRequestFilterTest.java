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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.ServerConfig;
import sjws_nettask.ServerConfig.AccessLogFormat;
import sjws_nettask.ServerConfig.MetricsExposure;
import sjws_nettask.core.Body;
import sjws_nettask.core.Request;
import sjws_nettask.core.RequestHeaders;
import sjws_nettask.core.Response;
import sjws_nettask.core.ResponseHeaders;

/** Branches of the order-0 filter that a live server does not hit. */
class ObserveRequestFilterTest {

    @TempDir
    Path dir;

    @Test
    void noneSkipsTheClockAndTheCounters() {
        assertThrows(NullPointerException.class, () -> new ObserveRequestFilter(null));
        var slot = new ObserverSlot(RequestObserver.NONE);
        var calls = new AtomicInteger();
        var chain = ObserveRequestFilter.chain(slot, request -> {
            calls.incrementAndGet();
            return new Response(200, ResponseHeaders.of(), Body.Empty.INSTANCE);
        });
        assertEquals(200, chain.handle(new Request("GET", "/x")).status());
        assertEquals(1, calls.get());
        assertEquals(0, slot.get().metrics().snapshot().requests());
    }

    @Test
    void probesLoggingAndPeers() throws Exception {
        var log = dir.resolve("peers.log");
        var config = ServerConfig.builder(dir).accessLog(log.toString()).accessLogFormat(AccessLogFormat.COMBINED)
                .metricsExposure(MetricsExposure.LOCALHOST).healthz(true).build();
        try (var observer = RequestObserver.open(config)) {
            var slot = new ObserverSlot(observer);
            var calls = new AtomicInteger();
            var chain = ObserveRequestFilter.chain(slot, request -> {
                calls.incrementAndGet();
                return new Response(200, ResponseHeaders.of().add("content-type", "text/plain"),
                        Body.Bytes.of("file".getBytes(StandardCharsets.US_ASCII)), request.headOnly());
            });
            var forbidden = chain.handle(new Request("GET", "/metrics", RequestHeaders.NONE,
                    new InetSocketAddress("192.0.2.1", 9), false));
            assertEquals(403, forbidden.status());
            assertEquals(0, calls.get());
            var unresolved = InetSocketAddress.createUnresolved("no-such-host.invalid", 9);
            var headers = RequestHeaders.of(Map.of("referer", List.of(""), "user-agent", List.of("ua")));
            var logged = chain.handle(new Request("GET", "/a", headers, unresolved, false, "HTTP/1.0"));
            assertEquals(200, logged.status());
            assertEquals(1, calls.get());
            var missingPeer = chain.handle(new Request("HEAD", "/healthz"));
            assertEquals(200, missingPeer.status());
            assertTrue(missingPeer.headOnly());
            assertEquals(1, calls.get(), "HEAD /healthz is answered by the filter");
            var fallthrough = chain.handle(new Request("POST", "/healthz", RequestHeaders.NONE, null, false));
            assertEquals(200, fallthrough.status());
            assertEquals(2, calls.get());
            var nullReferer = RequestHeaders.of(Map.of("referer", java.util.Collections.singletonList(null),
                    "user-agent", List.of()));
            assertEquals(200, chain.handle(new Request("GET", "/n", nullReferer, null, false)).status());
            var snap = observer.metrics().snapshot();
            assertTrue(snap.requests() >= 5);
            assertEquals(1, snap.protocol("HTTP/1.0"));
            assertTrue(snap.bytesSent() >= 4);
        }
        var text = Files.readString(log);
        assertTrue(text.contains("- - - "), text);
        assertTrue(text.contains("\"GET /a HTTP/1.0\" 200 4 \"-\" \"ua\""), text);
        assertFalse(text.contains("no-such-host"), text);
    }

    @Test
    void commonFormatSkipsHeaderFieldsAndMetricsOnlySkipsTheLog() throws Exception {
        var log = dir.resolve("common.log");
        var common = ServerConfig.builder(dir).accessLog(log.toString()).accessLogFormat(AccessLogFormat.COMMON).build();
        try (var observer = RequestObserver.open(common)) {
            var chain = ObserveRequestFilter.chain(new ObserverSlot(observer), request -> new Response(204,
                    ResponseHeaders.of(), Body.Empty.INSTANCE));
            var headers = RequestHeaders.of(Map.of("referer", java.util.Collections.singletonList(null)));
            assertEquals(204, chain.handle(new Request("GET", "/c", headers, null, false, "HTTP/1.1")).status());
        }
        var text = Files.readString(log);
        assertTrue(text.contains("\"GET /c HTTP/1.1\" 204 - "), text);
        assertFalse(text.contains("referer"), text);

        var metrics = ServerConfig.builder(dir).metricsExposure(MetricsExposure.ON).build();
        try (var observer = RequestObserver.open(metrics)) {
            assertFalse(observer.logging());
            var chain = ObserveRequestFilter.chain(new ObserverSlot(observer),
                    request -> new Response(200, ResponseHeaders.of(), Body.Bytes.of(new byte[] {1, 2})));
            var peer = new InetSocketAddress("127.0.0.1", 1);
            assertEquals(200, chain.handle(new Request("POST", "/metrics", RequestHeaders.NONE, peer, false)).status());
            assertEquals(200, chain.handle(new Request("GET", "/metrics", RequestHeaders.NONE, peer, false)).status());
            assertTrue(observer.metrics().snapshot().requests() >= 2);
            assertTrue(observer.metrics().snapshot().bytesSent() > 2);
        }
    }
}
