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
import static org.junit.jupiter.api.Assertions.assertNull;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.ServerConfig;
import sjws_nettask.SjwsServer;
import sjws_nettask.core.Body;
import sjws_nettask.core.HandlerContributor;
import sjws_nettask.core.RequestFilters;
import sjws_nettask.core.Response;
import sjws_nettask.core.ResponseHeaders;
import sjws_nettask.tls.TlsMaterial;

/** The same compiled mounts HTTP/1.1 uses are what HTTP/3 dispatches. */
@Tag("h3")
class HandlerMountHttp3Test {

    @TempDir
    Path root;

    @Test
    void mountAndFileShareTheHttp3Listener() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Http3Server.isAvailable(),
                () -> "native QUIC unavailable: " + Http3Server.unavailabilityCause());
        Files.writeString(this.root.resolve("hello.txt"), "hello");
        var tls = TlsMaterial.selfSigned("localhost");
        var cert = Files.writeString(this.root.resolve("cert.pem"),
                SjwsServerHttp3Test.pem("CERTIFICATE", tls.certificate().getEncoded()));
        var key = Files.writeString(this.root.resolve("key.pem"),
                SjwsServerHttp3Test.pem("PRIVATE KEY", tls.key().getEncoded()));
        HandlerContributor mounts = registry -> registry.mount("/mod",
                request -> new Response(200, ResponseHeaders.of().add("content-type", "text/plain"),
                        Body.Bytes.of("mod".getBytes(StandardCharsets.UTF_8))));
        var filters = RequestFilters.create().add((request, next) -> {
            var response = next.proceed(request);
            response.headers().add("x-global", "h3");
            return response;
        });
        var config = ServerConfig.builder(this.root).port(0).h3Enabled(true).h3Port(0).h3Host("127.0.0.1")
                .tlsCert(cert).tlsKey(key).build();
        try (var server = SjwsServer.start(config, filters, null, List.of(mounts));
                var client = new H3TestClient(tls.certificate())) {
            assertNull(server.h3Failure());
            var quic = client.connect(new InetSocketAddress("127.0.0.1", server.udpPort()));
            var mod = H3TestClient.get(quic, "/mod/item");
            assertEquals(200, mod.status());
            assertEquals("mod", new String(mod.body(), StandardCharsets.UTF_8));
            assertEquals("h3", mod.header("x-global"));
            var file = H3TestClient.get(quic, "/hello.txt");
            assertEquals(200, file.status());
            assertEquals("hello", new String(file.body(), StandardCharsets.UTF_8));
            assertEquals("h3", file.header("x-global"));
        }
    }
}
