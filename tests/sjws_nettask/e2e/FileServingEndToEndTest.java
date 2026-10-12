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
package sjws_nettask.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.ServerConfig;
import sjws_nettask.SjwsServer;

/**
 * Real {@link SjwsServer} on an ephemeral TCP port, a temporary document root, and
 * {@link HttpClient} (HTTP/1.1). {@code SjwsServer.start} returns only after the listener is
 * bound, so these tests do not sleep or retry.
 */
@Tag("e2e")
@Timeout(10)
class FileServingEndToEndTest {

    private static final String BODY = "hello from the temp docroot\n";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(4);

    /** Ignore {@code http_proxy}: the client must open the loopback listener directly. */
    private static final ProxySelector DIRECT = new ProxySelector() {
        @Override
        public List<Proxy> select(URI uri) {
            return List.of(Proxy.NO_PROXY);
        }

        @Override
        public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
        }
    };

    @TempDir
    Path root;

    private SjwsServer server;
    private HttpClient client;

    @BeforeEach
    void start() throws IOException {
        Files.writeString(root.resolve("hello.txt"), BODY, StandardCharsets.UTF_8);
        server = SjwsServer.start(ServerConfig.builder(root).port(0).h3Enabled(false).build());
        assertTrue(server.tcpPort() > 0, "ephemeral port");
        client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .proxy(DIRECT)
                .build();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.close();
        }
        if (client != null) {
            client.close();
        }
    }

    @Test
    void existingFileIs200WithBodyAndContentType() throws Exception {
        var response = get("/hello.txt");
        assertEquals(200, response.statusCode());
        assertEquals(BODY, response.body());
        assertEquals("text/plain; charset=utf-8", response.headers().firstValue("content-type").orElseThrow());
    }

    @Test
    void missingPathIs404() throws Exception {
        var response = get("/no-such-file.txt");
        assertEquals(404, response.statusCode());
    }

    private HttpResponse<String> get(String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.tcpPort() + path))
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
