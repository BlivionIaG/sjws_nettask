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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.H3Transports;
import sjws_nettask.ServerConfig;
import sjws_nettask.core.RequestFilters;
import sjws_nettask.core.StaticFileHandler;

/**
 * The default HTTP/3 factory runs the same chain HTTP/1.1 uses. A filter failure is a 500 on
 * that stream; the connection stays up.
 */
@Tag("h3")
class FilterHttp3Test {

    @TempDir
    Path root;

    @Test
    void factoryAppliesTheChainAndAFilterErrorStaysOnTheStream() throws Exception {
        Assumptions.assumeTrue(Http3Server.isAvailable(),
                () -> "native QUIC unavailable: " + Http3Server.unavailabilityCause());
        Files.writeString(this.root.resolve("hello.txt"), "hello");
        var handler = new StaticFileHandler(this.root);
        var peer = new AtomicReference<java.net.InetSocketAddress>();
        var chain = RequestFilters.create().add((request, next) -> {
            if (request.target().startsWith("/boom")) {
                throw new IllegalStateException("boom");
            }
            assertTrue(request.secure());
            peer.set(request.peer());
            assertTrue(request.headers().fields().isEmpty() || !request.headers().fields().containsKey(":method"));
            var response = next.proceed(request);
            response.headers().add("x-filter", "h3");
            return response;
        }).build(handler);
        var config = ServerConfig.builder(this.root).h3Enabled(true).h3Host("127.0.0.1").h3Port(0).build();
        try (var unused = H3Transports.defaultFactory().create(config, handler)) {
            assertEquals("HTTP/3", unused.name());
        }
        var server = (Http3Server) H3Transports.defaultFactory().create(config, handler, chain);
        server.start();
        try (var client = new H3TestClient(server.tls().certificate())) {
            var quic = client.connect(server.localAddress());
            var ok = H3TestClient.get(quic, "/hello.txt");
            assertEquals(200, ok.status());
            assertEquals("h3", ok.header("x-filter"));
            assertNotNull(peer.get());
            assertTrue(peer.get().getAddress().isLoopbackAddress(), peer.get().toString());
            assertEquals("hello", new String(ok.body(), StandardCharsets.UTF_8));
            var failed = H3TestClient.get(quic, "/boom");
            assertEquals(500, failed.status());
            assertTrue(new String(failed.body(), StandardCharsets.UTF_8).contains("500 Internal Server Error"),
                    new String(failed.body(), StandardCharsets.UTF_8));
            var again = H3TestClient.get(quic, "/hello.txt");
            assertEquals(200, again.status());
            assertEquals("hello", new String(again.body(), StandardCharsets.UTF_8));
        } finally {
            server.close();
        }
    }
}
