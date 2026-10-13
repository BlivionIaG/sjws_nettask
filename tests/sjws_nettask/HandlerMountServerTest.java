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
package sjws_nettask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.core.Body;
import sjws_nettask.core.Handler;
import sjws_nettask.core.HandlerContributor;
import sjws_nettask.core.RequestFilters;
import sjws_nettask.core.Response;
import sjws_nettask.core.ResponseHeaders;
import sjws_nettask.core.StaticFileHandler;
import sjws_nettask.routing.Router;
import sjws_nettask.swarm.SwarmModule;

/** Mounts on a real HTTP/1.1 server: none, one, several, overlap, router and filters. */
class HandlerMountServerTest {

    @TempDir
    Path root;

    SjwsServer server;

    @AfterEach
    void stop() {
        if (this.server != null) {
            this.server.close();
        }
    }

    @Test
    void noContributorsKeepTheStaticHandler() throws Exception {
        Files.writeString(this.root.resolve("hello.txt"), "hello");
        this.server = SjwsServer.start(config(), null, null, List.of());
        assertNull(this.server.filterChain());
        assertSame(this.server.handler(), this.server.requestHandler());
        assertInstanceOf(StaticFileHandler.class, this.server.requestHandler());
        var response = HttpTestSupport.get(this.server.tcpPort(), "/hello.txt", 4000);
        assertEquals(200, response.statusCode());
        assertEquals("hello", response.bodyText());
    }

    @Test
    void aContributorThatMountsNothingKeepsTheStaticHandler() throws Exception {
        Files.writeString(this.root.resolve("hello.txt"), "hello");
        this.server = SjwsServer.start(config(), null, null, List.of(mounts -> {
        }));
        assertNull(this.server.filterChain());
        assertSame(this.server.handler(), this.server.requestHandler());
        assertEquals("hello", HttpTestSupport.get(this.server.tcpPort(), "/hello.txt", 4000).bodyText());
    }

    @Test
    void swarmPrefixAnswersAndFilesStayOnTheStaticHandler() throws Exception {
        Files.writeString(this.root.resolve("hello.txt"), "hello");
        var module = new SwarmModule();
        this.server = SjwsServer.start(config(), null, null, List.of(module));
        assertNull(this.server.filterChain());
        int port = this.server.tcpPort();
        var hit = HttpTestSupport.get(port, "/swarm/v1/tasks", 4000);
        assertEquals(405, hit.statusCode());
        assertTrue(hit.bodyText().contains("405"), hit.bodyText());
        assertEquals("hello", HttpTestSupport.get(port, "/hello.txt", 4000).bodyText());
        assertEquals(404, HttpTestSupport.get(port, "/swarm/v2", 4000).statusCode());
        module.close();
    }

    @Test
    void mountsComposeWithARouterAndWithFilters() throws Exception {
        Files.writeString(this.root.resolve("hello.txt"), "hello");
        HandlerContributor mounts = registry -> {
            var wrapped = RequestFilters.create()
                    .add((request, next) -> {
                        var response = next.proceed(request);
                        response.headers().add("x-mount", "1");
                        return response;
                    })
                    .wrapping(request -> text(200, "mod"));
            registry.mount("/mod", wrapped);
            registry.mount("/other", request -> text(200, "other"));
        };
        var filters = RequestFilters.create().add((request, next) -> {
            var response = next.proceed(request);
            response.headers().add("x-global", "1");
            return response;
        });
        this.server = SjwsServer.start(config(), filters, files -> new Router(List.of(
                new Router.Route("/assets", request -> text(200, "asset"))), files), List.of(mounts));
        assertTrue(this.server.filterChain() != null);
        int port = this.server.tcpPort();
        var mod = HttpTestSupport.get(port, "/mod/item", 4000);
        assertEquals(200, mod.statusCode());
        assertEquals("mod", mod.bodyText());
        assertEquals("1", mod.headers().get("x-mount"));
        assertEquals("1", mod.headers().get("x-global"));
        var other = HttpTestSupport.get(port, "/other", 4000);
        assertEquals("other", other.bodyText());
        assertNull(other.headers().get("x-mount"));
        assertEquals("1", other.headers().get("x-global"));
        var asset = HttpTestSupport.get(port, "/assets/app.js", 4000);
        assertEquals("asset", asset.bodyText());
        assertEquals("1", asset.headers().get("x-global"));
        assertEquals("hello", HttpTestSupport.get(port, "/hello.txt", 4000).bodyText());
    }

    @Test
    void overlappingMountsFailBeforeThePortIsBound() {
        HandlerContributor overlap = mounts -> {
            mounts.mount("/api", request -> text(200, "a"));
            mounts.mount("/api/v1", request -> text(200, "b"));
        };
        var error = assertThrows(IllegalArgumentException.class,
                () -> SjwsServer.start(config(), null, null, List.of(overlap)));
        assertTrue(error.getMessage().contains("overlap"), error.getMessage());
    }

    private ServerConfig config() {
        return ServerConfig.builder(this.root).port(0).build();
    }

    private static Response text(int status, String body) {
        return new Response(status, ResponseHeaders.of().add("content-type", "text/plain"),
                Body.Bytes.of(body.getBytes(StandardCharsets.UTF_8)));
    }
}
