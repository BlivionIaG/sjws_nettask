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
package sjws_nettask.h3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.ServerConfig;
import sjws_nettask.ServerConfig.AccessLogFormat;
import sjws_nettask.ServerConfig.MetricsExposure;
import sjws_nettask.core.StaticFileHandler;
import sjws_nettask.observe.RequestObserver;
import sjws_nettask.tls.TlsMaterial;

/** HTTP/3 uses the same observer as HTTP/1.1: one line, and the protocol counter says HTTP/3. */
@Tag("h3")
class ObservabilityHttp3Test {

    @TempDir
    Path dir;

    @Test
    void recordsHttp3Responses() throws Exception {
        Assumptions.assumeTrue(Http3Server.isAvailable(),
                () -> "native QUIC unavailable: " + Http3Server.unavailabilityCause());
        Files.writeString(dir.resolve("hello.txt"), "hi\n");
        var log = dir.resolve("h3.log");
        var config = ServerConfig.builder(dir).h3Enabled(true).h3Host("127.0.0.1").h3Port(0)
                .accessLog(log.toString()).accessLogFormat(AccessLogFormat.COMBINED)
                .metricsExposure(MetricsExposure.LOCALHOST).healthz(true).build();
        var observer = RequestObserver.open(config);
        var tls = TlsMaterial.selfSigned("localhost");
        var server = new Http3Server(config, new StaticFileHandler(dir), tls).withObserver(observer).start();
        observer.metrics().setConnectionGauges(() -> 0, server::connections);
        try (var client = new H3TestClient(tls.certificate())) {
            var quic = client.connect(server.localAddress());
            var health = H3TestClient.get(quic, "/healthz");
            assertEquals(200, health.status());
            assertEquals("ok\n", new String(health.body(), StandardCharsets.US_ASCII));
            var file = H3TestClient.request(quic, "GET", "/hello.txt",
                    Map.of("referer", "http://ref", "user-agent", "h3-ua")).get(30, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(200, file.status());
            var snap = observer.metrics().snapshot();
            assertTrue(snap.protocol("HTTP/3") >= 2, "protocols " + snap.protocol("HTTP/3"));
            assertTrue(snap.http3Connections() >= 1, "connections " + snap.http3Connections());
            assertEquals(0, snap.http1Connections());
        } finally {
            server.close();
            observer.close();
        }
        var text = Files.readString(log);
        assertTrue(text.contains("\"GET /healthz HTTP/3\" 200 3"), text);
        assertTrue(text.contains("\"GET /hello.txt HTTP/3\" 200 3 \"http://ref\" \"h3-ua\""), text);
    }
}
