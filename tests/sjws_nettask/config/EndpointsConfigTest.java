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
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.SjwsServer;
import sjws_nettask.routing.discovery.Endpoint;
import sjws_nettask.routing.discovery.LbPolicy;
import sjws_nettask.routing.discovery.UpstreamRegistry;
import sjws_nettask.routing.discovery.UpstreamSet;

class EndpointsConfigTest {

    @TempDir
    Path dir;

    private ConfigLoader.Loaded load(String routing) throws IOException {
        var file = this.dir.resolve("sjws.yaml");
        Files.createDirectories(this.dir.resolve("www"));
        Files.writeString(file, "server:\n  port: 0\n  root: \"" + this.dir.resolve("www") + "\"\n" + routing);
        return ConfigLoader.load(new String[] {"--config=" + file, "--no-h3"}, Map.of());
    }

    private String error(String upstream) throws IOException {
        var loaded = load("routing:\n  upstreams:\n    svc:\n" + upstream);
        assertFalse(loaded.ok(), upstream);
        return loaded.errors().get(0);
    }

    @Test
    void parsesAndRoundTrips() throws IOException {
        var loaded = load("""
                routing:
                  upstreams:
                    svc:
                      endpoints:
                        - "http://10.0.0.1:8080/base"
                        - { url: "http://[::1]:8081/base", weight: 3, zone: z1, id: n2, labels: { tier: canary, v: 2 } }
                        - { url: "http://h3/base", weight: 0 }
                      lb: round_robin
                      retry: { attempts: 1 }
                      circuit: { failures: 3, eject_for: 5s, error_rate: 40%, min_requests: 50, window: 30s, max_ejection: 34 }
                    plain:
                      url: "http://127.0.0.1:9"
                      circuit: { failures: 2 }
                """);
        assertTrue(loaded.ok(), loaded.errors().toString());
        var svc = loaded.document().routing().upstreams().get("svc");
        var d = svc.discovery();
        assertEquals(LbPolicy.ROUND_ROBIN, d.lb());
        assertEquals(3, d.endpoints().size());
        assertEquals("10.0.0.1:8080", d.endpoints().get(0).authority());
        assertEquals("[::1]:8081", d.endpoints().get(1).authority());
        assertEquals("n2", d.endpoints().get(1).id());
        assertEquals("canary", d.endpoints().get(1).labels().get("tier"));
        assertEquals("2", d.endpoints().get(1).labels().get("v"));
        assertEquals("h3:80", d.endpoints().get(2).authority());
        assertEquals(0, d.endpoints().get(2).weight());
        assertEquals(URI.create("http://10.0.0.1:8080/base"), svc.url());
        assertEquals(3, d.outlier().consecutive());
        assertEquals(40, d.outlier().errorRatePercent());
        assertEquals(50, d.outlier().minRequests());
        assertEquals(Duration.ofSeconds(30), d.outlier().window());
        assertEquals(34, d.outlier().maxEjectionPercent());
        assertNull(svc.resilience().circuit()); // the per-endpoint circuit replaces the upstream one
        assertEquals(1, svc.resilience().retry().attempts());
        assertNull(loaded.document().routing().upstreams().get("plain").discovery());
        var text = ConfigCodec.text(loaded.document());
        var copy = this.dir.resolve("copy.yaml");
        Files.writeString(copy, text);
        var again = ConfigLoader.load(new String[] {"--config=" + copy, "--no-h3"}, Map.of());
        assertTrue(again.ok(), again.errors() + "\n" + text);
        assertEquals(loaded.document().canonicalHash(), again.document().canonicalHash());
        assertEquals(d, again.document().routing().upstreams().get("svc").discovery());
        var defaults = load("routing:\n  upstreams:\n    s:\n      endpoints: [\"https://a\"]\n");
        assertTrue(defaults.ok(), defaults.errors().toString());
        var dd = defaults.document().routing().upstreams().get("s").discovery();
        assertEquals(LbPolicy.WEIGHTED_RANDOM, dd.lb());
        assertEquals(443, dd.endpoints().get(0).port());
        assertEquals(sjws_nettask.routing.EndpointBalancer.Outlier.DEFAULT, dd.outlier());
    }

    @Test
    void errorsNameThePath() throws IOException {
        assertTrue(error("      lb: round_robin\n").contains("exactly one of url or endpoints"));
        assertTrue(error("      url: \"http://a\"\n      endpoints: [\"http://b\"]\n").contains("exactly one"));
        assertTrue(error("      url: \"http://a\"\n      lb: round_robin\n").contains("only valid with endpoints"));
        assertTrue(error("      endpoints: []\n").contains("1 to 1024"));
        assertTrue(error("      endpoints: [\"http://a\"]\n      lb: ring_hash\n").contains("least_request"));
        assertTrue(error("      endpoints: [\"ftp://a\"]\n").contains("http(s)"));
        assertTrue(error("      endpoints: [\"not a url\"]\n").contains("endpoints[0]"));
        assertTrue(error("      endpoints: [\"http://a/?q\"]\n").contains("query"));
        assertTrue(error("      endpoints: [\"http://a/x\", \"http://b/y\"]\n").contains("scheme and path"));
        assertTrue(error("      endpoints: [\"http://a/x\", \"https://b/x\"]\n").contains("scheme and path"));
        assertTrue(error("      endpoints: [\"http://a:1\", \"http://a:1\"]\n").contains("duplicate"));
        assertTrue(error("      endpoints: [ { url: \"http://a\", weight: 0 } ]\n").contains("weight 0"));
        assertTrue(error("      endpoints: [ { weight: 1 } ]\n").contains("url is required"));
        assertTrue(error("      endpoints: [ { url: \"http://a\", port: 1 } ]\n").contains("port"));
        assertTrue(error("      endpoints: [\"http://a\"]\n      circuit: { error_rate: 0% }\n").contains("error_rate"));
        assertTrue(error("      endpoints: [\"http://a\"]\n      circuit: { error_rate: lots }\n").contains("percentage"));
        assertTrue(error("      endpoints: [\"http://a\"]\n      circuit: { window: 1ms }\n").contains("window"));
        assertTrue(error("      endpoints: [\"http://a\"]\n      circuit: { max_ejection: 101 }\n").contains("max_ejection"));
        assertTrue(error("      endpoints: [\"http://a\"]\n      circuit: { open: 1 }\n").contains("open"));
        assertTrue(error("      url: \"http://a\"\n      circuit: { error_rate: 50% }\n").contains("error_rate"));
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
    void servesFromTheSourceAndAControlPlaneSetSurvivesAReload() throws Exception {
        var a = up("a");
        var b = up("b");
        var c = up("c");
        var yaml = """
                routing:
                  enabled: true
                  upstreams:
                    disc-e2e:
                      endpoints: [ "http://127.0.0.1:%d", "http://127.0.0.1:%d" ]
                      lb: round_robin
                  routes:
                    - { prefix: /, proxy: disc-e2e }
                """.formatted(a.getAddress().getPort(), b.getAddress().getPort());
        var loaded = load(yaml);
        assertTrue(loaded.ok(), loaded.errors().toString());
        try (var server = SjwsServer.start(loaded)) {
            var client = HttpClient.newHttpClient();
            var url = URI.create("http://127.0.0.1:" + server.tcpPort() + "/");
            var seen = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                seen.append(client.send(HttpRequest.newBuilder(url).build(), HttpResponse.BodyHandlers.ofString()).body());
            }
            assertTrue(seen.toString().contains("a") && seen.toString().contains("b"), seen.toString());
            var source = UpstreamRegistry.lookup("disc-e2e");
            assertNotNull(source);
            assertTrue(source.update(new UpstreamSet("disc-e2e", 7,
                    new Endpoint[] {Endpoint.of("127.0.0.1", c.getAddress().getPort())}, LbPolicy.ROUND_ROBIN)));
            for (int i = 0; i < 4; i++) {
                assertEquals("c", client.send(HttpRequest.newBuilder(url).build(), HttpResponse.BodyHandlers.ofString())
                        .body());
            }
            // a recompile (what a reload does) reuses the source and keeps the control plane's set
            var snapshot = ConfigSnapshot.compile(load(yaml).document(), new sjws_nettask.acme.ChallengeBoard(), 2);
            assertEquals(7, source.current().version());
            assertSame(source, UpstreamRegistry.lookup("disc-e2e"));
            assertEquals("c", client.send(HttpRequest.newBuilder(url).build(), HttpResponse.BodyHandlers.ofString())
                    .body());
            snapshot.close();
        } finally {
            a.stop(0);
            b.stop(0);
            c.stop(0);
        }
    }

    @Test
    void routerClosesPoolsBehindWrappers() throws Exception {
        var a = up("a");
        try {
            var loaded = load("""
                    routing:
                      enabled: true
                      upstreams:
                        w1: { url: "http://127.0.0.1:%1$d" }
                        w2: { endpoints: [ "http://127.0.0.1:%1$d" ] }
                      routes:
                        - prefix: /w
                          backends: [ { upstream: w1 }, { upstream: w2 } ]
                          rate_limit: { rate: 1000/s }
                    """.formatted(a.getAddress().getPort()));
            assertTrue(loaded.ok(), loaded.errors().toString());
            var router = RoutingCompiler.compile(loaded.document().routing(), null);
            assertEquals(200, router.handle(new sjws_nettask.core.Request("GET", "/w")).status());
            router.close();
            assertNotEquals(200, router.handle(new sjws_nettask.core.Request("GET", "/w")).status());
        } finally {
            a.stop(0);
        }
    }
}
