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

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.SjwsServer;
import sjws_nettask.routing.RateLimitFilter.By;

class RateLimitConfigTest {

    @TempDir
    Path dir;

    private ConfigLoader.Loaded load(String routing) throws IOException {
        var file = this.dir.resolve("sjws.yaml");
        Files.createDirectories(this.dir.resolve("www"));
        Files.writeString(this.dir.resolve("www/index.html"), "static");
        Files.writeString(file, "server:\n  port: 0\n  root: \"" + this.dir.resolve("www") + "\"\n" + routing);
        return ConfigLoader.load(new String[] {"--config=" + file, "--no-h3"}, Map.of());
    }

    private String error(String routing) throws IOException {
        var loaded = load(routing);
        assertFalse(loaded.ok(), routing);
        return loaded.errors().get(0);
    }

    private static String route(String limit) {
        return "routing:\n  enabled: true\n  routes:\n    - prefix: /\n      static: true\n      rate_limit: " + limit + "\n";
    }

    @Test
    void parsesDefaultsAndRoundTrips() throws IOException {
        var loaded = load("""
                routing:
                  enabled: true
                  rate_limit: { by: header, header: X-Api-Key, rate: 600/m, burst: 20, buckets: 1024 }
                  upstreams:
                    api:
                      url: "http://127.0.0.1:9"
                      retry: { attempts: 3, on: [connect, 503], budget: 10% }
                      circuit: { failures: 4, eject_for: 2s }
                  routes:
                    - { prefix: /api, proxy: api, rate_limit: { by: route, rate: 100 } }
                    - { prefix: /, static: true, rate_limit: { rate: 50/s } }
                """);
        assertTrue(loaded.ok(), loaded.errors().toString());
        var routing = loaded.document().routing();
        var global = routing.rateLimit();
        assertEquals(By.HEADER, global.by());
        assertEquals("x-api-key", global.header());
        assertEquals(600, global.count());
        assertEquals('m', global.unit());
        assertEquals(20, global.burst());
        assertEquals(1024, global.buckets());
        var api = routing.routes().routes().get(0).rateLimit();
        assertEquals(By.ROUTE, api.by());
        assertEquals(100, api.burst()); // default burst: one second's worth
        assertEquals(1, api.buckets());
        var files = routing.routes().routes().get(1).rateLimit();
        assertEquals(By.PEER, files.by());
        assertEquals(4096, files.buckets());
        var text = ConfigCodec.text(loaded.document());
        assertTrue(text.contains("retry: { attempts: 3, on: [connect, 503], budget: 10 }"), text);
        assertTrue(text.contains("circuit: { failures: 4"), text);
        var copy = this.dir.resolve("copy.yaml");
        Files.writeString(copy, text);
        var again = ConfigLoader.load(new String[] {"--config=" + copy, "--no-h3"}, Map.of());
        assertTrue(again.ok(), again.errors() + "\n" + text);
        assertEquals(loaded.document().canonicalHash(), again.document().canonicalHash());
        assertEquals(global, again.document().routing().rateLimit());
        assertEquals(loaded.document().routing().upstreams().get("api").resilience(),
                again.document().routing().upstreams().get("api").resilience());
        var slow = load(route("{ rate: 5/h }"));
        assertEquals(1, slow.document().routing().routes().routes().get(0).rateLimit().burst());
        var off = load("routing:\n  rate_limit: { rate: 1/s }\n");
        assertTrue(ConfigCodec.text(off.document()).contains("rate_limit:"));
        var circuitOnly = load("routing:\n  upstreams:\n    a:\n      url: \"http://127.0.0.1:9\"\n      circuit: {}\n");
        assertTrue(circuitOnly.ok(), circuitOnly.errors().toString());
        assertFalse(ConfigCodec.text(circuitOnly.document()).contains("retry:"));
    }

    @Test
    void errorsNameThePath() throws IOException {
        assertTrue(error(route("{ by: ip, rate: 1 }")).contains("expected peer, header or route"));
        assertTrue(error(route("{ by: header, rate: 1 }")).contains("header is required"));
        assertTrue(error(route("{ by: header, header: \"a b\", rate: 1 }")).contains("invalid name"));
        assertTrue(error(route("{ header: x, rate: 1 }")).contains("only valid with by: header"));
        assertTrue(error(route("{ burst: 1 }")).contains("rate is required"));
        assertTrue(error(route("{ rate: 1/d }")).contains("N/s"));
        assertTrue(error(route("{ rate: lots }")).contains("N/s"));
        assertTrue(error(route("{ rate: 1/ss }")).contains("N/s"));
        assertTrue(error(route("{ rate: 0/s }")).contains("1..1000000000"));
        assertTrue(error(route("{ rate: 1, burst: 0 }")).contains("burst"));
        assertTrue(error(route("{ rate: 1, buckets: 1000 }")).contains("power of two"));
        assertTrue(error(route("{ rate: 1, per: s }")).contains("per"));
        assertTrue(error("routing:\n  rate_limit: { by: route, rate: 1 }\n").contains("only valid on a route"));
    }

    @Test
    void servesA429WithRetryAfter() throws Exception {
        var loaded = load("""
                routing:
                  enabled: true
                  rate_limit: { rate: 1/h, burst: 3 }
                  routes:
                    - { prefix: /tight, rewrite: /, static: true, rate_limit: { by: route, rate: 1/h, burst: 1 } }
                    - { prefix: /, static: true }
                """);
        assertTrue(loaded.ok(), loaded.errors().toString());
        try (var server = SjwsServer.start(loaded)) {
            var client = HttpClient.newHttpClient();
            var base = "http://127.0.0.1:" + server.tcpPort();
            assertEquals(200, get(client, base + "/tight/").statusCode());
            var refused = get(client, base + "/tight/");
            assertEquals(429, refused.statusCode());
            assertTrue(Long.parseLong(refused.headers().firstValue("retry-after").orElse("0")) > 3000);
            assertEquals(200, get(client, base + "/").statusCode()); // the global burst of 3 is spent here
            assertEquals(429, get(client, base + "/").statusCode());
        }
    }

    private static HttpResponse<String> get(HttpClient client, String url) throws IOException, InterruptedException {
        return client.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
