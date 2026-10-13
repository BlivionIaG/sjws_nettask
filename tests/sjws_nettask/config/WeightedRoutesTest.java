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
package sjws_nettask.config;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.SjwsServer;

class WeightedRoutesTest {

    @TempDir
    Path dir;

    private static final String UPS = """
            routing:
              enabled: true
              upstreams:
                stable: { url: "http://127.0.0.1:%d" }
                canary: { url: "http://127.0.0.1:%d" }
              routes:
            """;

    private ConfigLoader.Loaded load(String yaml) throws IOException {
        var file = this.dir.resolve("sjws.yaml");
        Files.createDirectories(this.dir.resolve("www"));
        Files.writeString(file, "server:\n  port: 0\n  root: \"" + this.dir.resolve("www") + "\"\n" + yaml);
        return ConfigLoader.load(new String[] {"--config=" + file, "--no-h3"}, Map.of());
    }

    private String error(String routes) throws IOException {
        var loaded = load(UPS.formatted(9, 10) + routes);
        assertFalse(loaded.ok(), routes);
        return loaded.errors().get(0);
    }

    @Test
    void parsesAndRoundTrips() throws IOException {
        var loaded = load(UPS.formatted(9, 10) + """
                    - prefix: /
                      backends:
                        - { upstream: stable, weight: 95 }
                        - { upstream: canary, weight: 5, pin: { header: X-Canary, value: "1" } }
                        - { upstream: canary, weight: 0, pin: { cookie: beta } }
                      sticky: { cookie: session }
                    - prefix: /b
                      backends: [ { upstream: stable } ]
                """);
        assertTrue(loaded.ok(), loaded.errors().toString());
        var w = (RouteSpec.Action.Weighted) loaded.document().routing().routes().routes().get(0).action();
        assertEquals(3, w.backends().size());
        assertEquals(95, w.backends().get(0).weight());
        assertEquals("x-canary", w.backends().get(1).pin().name());
        assertEquals("1", w.backends().get(1).pin().value());
        assertTrue(w.backends().get(2).pin().cookie());
        assertNull(w.backends().get(2).pin().value());
        assertTrue(w.sticky().cookie());
        var plain = (RouteSpec.Action.Weighted) loaded.document().routing().routes().routes().get(1).action();
        assertEquals(1, plain.backends().get(0).weight());
        assertNull(plain.sticky());
        var text = ConfigCodec.text(loaded.document());
        var copy = this.dir.resolve("copy.yaml");
        Files.writeString(copy, text);
        var again = ConfigLoader.load(new String[] {"--config=" + copy, "--no-h3"}, Map.of());
        assertTrue(again.ok(), again.errors() + "\n" + text);
        assertEquals(loaded.document().canonicalHash(), again.document().canonicalHash());
        var headerSticky = load(UPS.formatted(9, 10) + """
                    - prefix: /
                      backends: [ { upstream: stable }, { upstream: canary } ]
                      sticky: { header: X-User }
                """);
        assertTrue(headerSticky.ok(), headerSticky.errors().toString());
        assertTrue(ConfigCodec.text(headerSticky.document()).contains("header: \"x-user\""));
    }

    @Test
    void errorsNameThePath() throws IOException {
        assertTrue(error("    - { prefix: /, backends: [] }\n").contains("1 to 64"));
        assertTrue(error("    - { prefix: /, backends: [ { weight: 1 } ] }\n").contains("upstream is required"));
        assertTrue(error("    - { prefix: /, backends: [ { upstream: nope } ] }\n").contains("unknown upstream"));
        assertTrue(error("    - { prefix: /, backends: [ { upstream: stable, weight: -1 } ] }\n").contains("weight"));
        assertTrue(error("    - { prefix: /, backends: [ { upstream: stable, weight: 0 } ] }\n").contains("pin"));
        assertTrue(error("    - { prefix: /, backends: [ { upstream: stable, wait: 1 } ] }\n").contains("wait"));
        assertTrue(error("    - { prefix: /, backends: [ { upstream: stable } ], proxy: stable }\n")
                .contains("exactly one"));
        assertTrue(error("    - { prefix: /, proxy: stable, sticky: { cookie: s } }\n").contains("only valid"));
        assertTrue(error("    - { prefix: /, backends: [ { upstream: stable } ], sticky: { cookie: s, header: h } }\n")
                .contains("exactly one of header or cookie"));
        assertTrue(error("    - { prefix: /, backends: [ { upstream: stable } ], sticky: {} }\n")
                .contains("exactly one of header or cookie"));
        assertTrue(error("    - { prefix: /, backends: [ { upstream: stable } ], sticky: { cookie: s, value: v } }\n")
                .contains("value"));
        assertTrue(error("    - { prefix: /, backends: [ { upstream: stable, pin: { header: \"a b\" } } ] }\n")
                .contains("invalid name"));
        assertTrue(error("    - { prefix: /, backends: [ { upstream: stable, pin: { header: \"a=\" } } ] }\n")
                .contains("invalid name"));
        assertTrue(error("    - { prefix: /, backends: [ { upstream: stable, pin: { cookie: \"\\u00e9\" } } ] }\n")
                .contains("invalid name"));
        var many = new StringBuilder("    - prefix: /\n      backends:\n");
        for (int i = 0; i < 65; i++) {
            many.append("        - { upstream: stable }\n");
        }
        assertTrue(error(many.toString()).contains("1 to 64"));
    }

    private static HttpServer up(String name) throws IOException {
        var s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/", ex -> {
            var b = name.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        s.start();
        return s;
    }

    @Test
    void servesWeightedAndPinned() throws Exception {
        var a = up("stable");
        var b = up("canary");
        var loaded = load(UPS.formatted(a.getAddress().getPort(), b.getAddress().getPort()) + """
                    - prefix: /
                      backends:
                        - { upstream: stable, weight: 1 }
                        - { upstream: canary, weight: 0, pin: { header: X-Canary } }
                """);
        assertTrue(loaded.ok(), loaded.errors().toString());
        try (var server = SjwsServer.start(loaded)) {
            var client = HttpClient.newHttpClient();
            var url = URI.create("http://127.0.0.1:" + server.tcpPort() + "/");
            for (int i = 0; i < 5; i++) {
                assertEquals("stable", client.send(HttpRequest.newBuilder(url).build(),
                        HttpResponse.BodyHandlers.ofString()).body());
            }
            assertEquals("canary", client.send(HttpRequest.newBuilder(url).header("X-Canary", "yes").build(),
                    HttpResponse.BodyHandlers.ofString()).body());
        } finally {
            a.stop(0);
            b.stop(0);
        }
    }
}
