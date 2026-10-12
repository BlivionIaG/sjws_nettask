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
package sjws_nettask.routing;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.ServerConfig;
import sjws_nettask.SjwsServer;
import sjws_nettask.core.RequestFilters;
import sjws_nettask.routing.RoutingConfig.Redirect;
import sjws_nettask.routing.RoutingConfig.RouteSpec;
import sjws_nettask.routing.RoutingConfig.Upstream;

class RoutingConfigTest {

    @TempDir
    Path root;

    @Test
    void disabledBuildsNothing() {
        assertNull(RoutingConfig.DISABLED.terminal());
    }

    @Test
    void validation() {
        var none = new RouteSpec(null, "/a", null, null, null, false, null);
        var two = new RouteSpec(null, "/a", null, "u", new Redirect(301, "/", false), false, null);
        var unknown = new RouteSpec(null, "/a", null, "nope", null, false, null);
        for (var r : List.of(none, two, unknown)) {
            var c = new RoutingConfig(true, null, List.of(), Map.of(), List.of(r));
            assertThrows(IllegalArgumentException.class, () -> c.build(null), r.toString());
        }
        var badCidr = new RoutingConfig(true, null, List.of("not-an-ip"), Map.of(), List.of());
        assertThrows(IllegalArgumentException.class, () -> badCidr.build(null));
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
        for (var bad : new String[] {"", "host.example", "10.0.0.0/33", "10.0.0.0/-1", "10.0.0.0/x", "1.2.3.4.5", "::g", "999.1.1.1"}) {
            assertThrows(IllegalArgumentException.class, () -> Cidr.parse(bad), bad);
        }
    }

    /** End to end through SjwsServer: proxy, redirect, static, per-route filter, fallback. */
    @Test
    void wiredIntoServer() throws IOException, InterruptedException {
        Files.writeString(this.root.resolve("index.html"), "static");
        var up = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        up.createContext("/", ex -> {
            var body = (ex.getRequestURI() + " xff=" + ex.getRequestHeaders().getFirst("X-Forwarded-For"))
                    .getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        up.start();
        var filters = RequestFilters.create().add((req, next) -> {
            var r = next.proceed(req);
            r.headers().set("x-route-filter", "1");
            return r;
        });
        var routing = new RoutingConfig(true, null, List.of(),
                Map.of("api", new Upstream(URI.create("http://127.0.0.1:" + up.getAddress().getPort()))),
                List.of(new RouteSpec(null, "/api", "/", "api", null, false, filters),
                        new RouteSpec(null, "/old", "/", null, new Redirect(308, "/new/", true), false, null),
                        new RouteSpec(null, "/files", "/", null, null, true, null)));
        var config = ServerConfig.builder(this.root).port(0).h3Enabled(false).build();
        try (var server = SjwsServer.start(config, null, null, routing.terminal())) {
            var client = HttpClient.newHttpClient();
            var base = "http://127.0.0.1:" + server.tcpPort();
            var api = client.send(HttpRequest.newBuilder(URI.create(base + "/api/x?q=1")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, api.statusCode());
            assertEquals("/x?q=1 xff=127.0.0.1", api.body());
            assertEquals("1", api.headers().firstValue("x-route-filter").orElse(null));
            var old = client.send(HttpRequest.newBuilder(URI.create(base + "/old/a")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(308, old.statusCode());
            assertEquals("/new/a", old.headers().firstValue("location").orElse(null));
            assertEquals("static", client.send(HttpRequest.newBuilder(URI.create(base + "/files/")).build(),
                    HttpResponse.BodyHandlers.ofString()).body());
            assertEquals("static", client.send(HttpRequest.newBuilder(URI.create(base + "/")).build(),
                    HttpResponse.BodyHandlers.ofString()).body());
        } finally {
            up.stop(0);
        }
    }
}
