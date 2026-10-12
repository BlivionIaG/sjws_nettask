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
import sjws_nettask.core.Request;
import sjws_nettask.core.RequestHeaders;
import sjws_nettask.core.StaticFileHandler;
import sjws_nettask.routing.Cidr;
import sjws_nettask.routing.Router;

class RoutingCompilerTest {

    @TempDir
    Path root;

    private ConfigLoader.Loaded load(String routing) throws IOException {
        var file = this.root.resolve("sjws.yaml");
        Files.writeString(file, "server:\n  port: 0\n  root: \"" + this.root.resolve("www") + "\"\n" + routing);
        Files.createDirectories(this.root.resolve("www"));
        Files.writeString(this.root.resolve("www/index.html"), "static");
        var loaded = ConfigLoader.load(new String[] {"--config=" + file, "--no-h3"}, Map.of());
        assertTrue(loaded.ok(), loaded.errors().toString());
        return loaded;
    }

    @Test
    void disabledKeepsTheFileHandler() throws IOException {
        var snapshot = ConfigSnapshot.compile(load("").document(), new sjws_nettask.acme.ChallengeBoard(), 1);
        assertSame(snapshot.files(), snapshot.handler());
        var off = ConfigSnapshot.compile(load("routing:\n  enabled: false\n  routes:\n    - prefix: /\n      static: true\n")
                .document(), new sjws_nettask.acme.ChallengeBoard(), 1);
        assertInstanceOf(StaticFileHandler.class, off.handler());
    }

    @Test
    void compilesEveryActionAndSharesOneProxyPerUpstream() throws IOException {
        var loaded = load("""
                routing:
                  enabled: true
                  forwarded_proto: https
                  trusted_proxies: ["10.0.0.0/8"]
                  upstreams:
                    api: { url: "http://127.0.0.1:9" }
                  routes:
                    - { host: "Example.org", prefix: /v6, rewrite: /, static: true }
                    - { host: "*.example.com", prefix: /a, proxy: api }
                    - { prefix: /b, rewrite: /, proxy: api }
                    - { prefix: /old, redirect: { status: 308, location: /new/, keep_path: true } }
                """);
        var router = assertInstanceOf(Router.class,
                ConfigSnapshot.compile(loaded.document(), new sjws_nettask.acme.ChallengeBoard(), 1).handler());
        var routes = router.routes();
        assertEquals("example.org", routes.get(0).host());
        assertSame(routes.get(1).handler(), routes.get(2).handler());
        assertEquals(308, router.handle(new Request("GET", "/old/x")).status());
        var v6 = new Request("GET", "/v6/", new RequestHeaders(null, null, null, null, null, "example.org:80"));
        assertEquals(200, router.handle(v6).status());
        for (var proto : new String[] {"http", "auto"}) {
            var r = load("routing:\n  enabled: true\n  forwarded_proto: " + proto + "\n");
            assertInstanceOf(Router.class, RoutingCompiler.compile(r.document().routing(), null));
        }
    }

    @Test
    void bracketedIpv6RouteHosts() throws IOException {
        var loaded = load("""
                routing:
                  enabled: true
                  routes:
                    - { host: "[::1]", prefix: /, redirect: { status: 308, location: /v6 } }
                    - { host: "[2001:DB8::1]", prefix: /, static: true }
                """);
        var specs = loaded.document().routing().routes().routes();
        assertEquals("::1", specs.get(0).host());
        assertEquals("[::1]", specs.get(0).routerHost());
        assertEquals("2001:db8::1", specs.get(1).host());
        var router = assertInstanceOf(Router.class,
                ConfigSnapshot.compile(loaded.document(), new sjws_nettask.acme.ChallengeBoard(), 1).handler());
        assertEquals("[::1]", router.routes().get(0).host());
        assertEquals("[2001:db8::1]", router.routes().get(1).host());
        var hit = new Request("GET", "/", new RequestHeaders(null, null, null, null, null, "[::1]:8080"));
        assertEquals(308, router.handle(hit).status());
        var other = new Request("GET", "/", new RequestHeaders(null, null, null, null, null, "[::2]:8080"));
        assertEquals(200, router.handle(other).status()); // no route: static fallback
        var upper = new Request("GET", "/", new RequestHeaders(null, null, null, null, null, "[2001:DB8::1]"));
        assertEquals(200, router.handle(upper).status());
    }

    @Test
    void rejectsBadIpv6RouteHosts() throws IOException {
        for (var host : new String[] {"::1", "[::1]:80", "::1:80", "example.com:80", "[::1", "::1]", "[]",
                "[127.0.0.1]", "[fe80::1%eth0]", "[::g]", "[[::1]]", "[::1]x", "[localhost]"}) {
            var file = this.root.resolve("bad.yaml");
            Files.writeString(file, "server:\n  port: 0\nrouting:\n  enabled: true\n  routes:\n    - { host: \""
                    + host + "\", prefix: /, static: true }\n");
            var loaded = ConfigLoader.load(new String[] {"--config=" + file, "--no-h3"}, Map.of());
            assertFalse(loaded.ok(), host);
            assertTrue(loaded.errors().toString().contains(".host"), host + ": " + loaded.errors());
        }
    }

    @Test
    void cidr() {
        var v4 = Cidr.parse("10.1.0.0/16");
        assertTrue(v4.contains(new InetSocketAddress("10.1.200.3", 1)));
        assertFalse(v4.contains(new InetSocketAddress("10.2.0.1", 1)));
        assertFalse(v4.contains(new InetSocketAddress("::1", 1)));
        assertFalse(v4.contains(null));
        assertFalse(v4.contains(InetSocketAddress.createUnresolved("x", 1)));
        var odd = Cidr.parse(" 192.168.0.0/23 ");
        assertTrue(odd.contains(new InetSocketAddress("192.168.1.9", 1)));
        assertFalse(odd.contains(new InetSocketAddress("192.168.2.9", 1)));
        assertTrue(Cidr.parse("0.0.0.0/0").contains(new InetSocketAddress("8.8.8.8", 1)));
        assertTrue(Cidr.parse("::1").contains(new InetSocketAddress("::1", 1)));
        for (var bad : new String[] {"", "host.example", "10.0.0.0/33", "10.0.0.0/-1", "10.0.0.0/x", "1.2.3.4.5", "::g",
            "999.1.1.1"}) {
            assertThrows(IllegalArgumentException.class, () -> Cidr.parse(bad), bad);
        }
    }

    /** The main entry point's path: config file → ConfigLoader → SjwsServer.start(loaded). */
    @Test
    void servedFromTheConfigFile() throws IOException, InterruptedException {
        var up = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        up.createContext("/", ex -> {
            var body = (ex.getRequestURI() + " xff=" + ex.getRequestHeaders().getFirst("X-Forwarded-For"))
                    .getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        up.start();
        var loaded = load("""
                routing:
                  enabled: true
                  upstreams:
                    api: { url: "http://127.0.0.1:%d" }
                  routes:
                    - { prefix: /api, rewrite: /, proxy: api }
                    - { prefix: /old, rewrite: /, redirect: { status: 308, location: /new/, keep_path: true } }
                    - { prefix: /files, rewrite: /, static: true }
                """.formatted(up.getAddress().getPort()));
        try (var server = SjwsServer.start(loaded)) {
            var client = HttpClient.newHttpClient();
            var base = "http://127.0.0.1:" + server.tcpPort();
            var api = get(client, base + "/api/x?q=1");
            assertEquals(200, api.statusCode());
            assertEquals("/x?q=1 xff=127.0.0.1", api.body());
            var old = get(client, base + "/old/a");
            assertEquals(308, old.statusCode());
            assertEquals("/new/a", old.headers().firstValue("location").orElse(null));
            assertEquals("static", get(client, base + "/files/").body());
            assertEquals("static", get(client, base + "/").body());
            assertEquals(400, get(client, base + "/api/%2e%2e/x").statusCode());
        } finally {
            up.stop(0);
        }
    }

    private static HttpResponse<String> get(HttpClient client, String url) throws IOException, InterruptedException {
        return client.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
