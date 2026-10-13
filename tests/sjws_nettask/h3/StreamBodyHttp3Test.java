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

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.H3Transports;
import sjws_nettask.ServerConfig;
import sjws_nettask.core.Body;
import sjws_nettask.core.Handler;
import sjws_nettask.core.Response;
import sjws_nettask.core.ResponseHeaders;
import sjws_nettask.core.TestSource;

/** {@link Body.Stream} over HTTP/3: known and unknown length, HEAD, and a short stream resets. */
@Tag("h3")
class StreamBodyHttp3Test {

    @TempDir
    Path root;

    @Test
    void streamsOverHttp3() throws Exception {
        Assumptions.assumeTrue(Http3Server.isAvailable(),
                () -> "native QUIC unavailable: " + Http3Server.unavailabilityCause());
        Handler streams = request -> switch (request.target()) {
            case "/known" -> new Response(200, ResponseHeaders.of(), new Body.Stream(6,
                    new TestSource(false, "abc".getBytes(), "def".getBytes())), request.headOnly());
            case "/unknown" -> new Response(200, ResponseHeaders.of(), new Body.Stream(-1,
                    new TestSource(true, "xy".getBytes(), "z".getBytes())));
            case "/empty" -> new Response(200, ResponseHeaders.of(), new Body.Stream(-1, new TestSource(false)));
            default -> new Response(200, ResponseHeaders.of(), new Body.Stream(10, new TestSource(false, "ab".getBytes())));
        };
        var handler = new sjws_nettask.core.StaticFileHandler(this.root);
        var chain = sjws_nettask.core.RequestFilters.create().add((request, next) -> streams.handle(request)).build(handler);
        var config = ServerConfig.builder(this.root).h3Enabled(true).h3Host("127.0.0.1").h3Port(0).build();
        var server = (Http3Server) H3Transports.defaultFactory().create(config, handler, chain);
        server.start();
        try (var client = new H3TestClient(server.tls().certificate())) {
            var quic = client.connect(server.localAddress());
            var known = H3TestClient.get(quic, "/known");
            assertEquals(200, known.status());
            assertEquals("6", known.header("content-length"));
            assertEquals("abcdef", new String(known.body(), StandardCharsets.UTF_8));
            var unknown = H3TestClient.get(quic, "/unknown");
            assertNull(unknown.header("content-length"));
            assertEquals("xyz", new String(unknown.body(), StandardCharsets.UTF_8));
            assertEquals(0, H3TestClient.get(quic, "/empty").body().length);
            var head = H3TestClient.request(quic, "HEAD", "/known", java.util.Map.of()).get(5, TimeUnit.SECONDS);
            assertEquals("6", head.header("content-length"));
            assertEquals(0, head.body().length);
            var shortResponse = H3TestClient.request(quic, "GET", "/short", java.util.Map.of()).get(5, TimeUnit.SECONDS);
            assertTrue(shortResponse.reset() || shortResponse.body().length < 10, "a short stream must not end cleanly");
        } finally {
            server.close();
        }
    }
}
