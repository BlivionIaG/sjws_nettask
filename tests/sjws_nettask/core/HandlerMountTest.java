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
package sjws_nettask.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Prefix mounts compile at startup. An empty registry returns the fallback instance. */
class HandlerMountTest {

    @TempDir
    Path dir;

    @Test
    void noContributorsReturnTheSameHandler() {
        Handler fallback = request -> text(404, "no");
        assertSame(fallback, HandlerMounts.install(List.of(), fallback));
        HandlerContributor quiet = mounts -> {
        };
        assertSame(fallback, HandlerMounts.install(List.of(quiet), fallback));
        assertTrue(HandlerMounts.discover(null).isEmpty());
        assertTrue(HandlerMounts.discover(List.of()).isEmpty());
    }

    @Test
    void onePrefixAndAnExactNeighbour() {
        Handler fallback = request -> text(404, request.target());
        Handler mounted = HandlerMounts.install(List.of(mounts -> {
            mounts.mount("/api", request -> text(200, "api"));
            mounts.mount(" /exact/ ", request -> text(200, "exact-prefix"));
            mounts.mount(RoutePattern.parse("/only"), request -> text(200, "only"));
        }), fallback);
        assertEquals("api", body(mounted, "/api"));
        assertEquals("api", body(mounted, "/api/"));
        assertEquals("api", body(mounted, "/api/v1?x=1"));
        assertEquals("api", body(mounted, "/api/%62"));
        assertEquals("/apiv2", body(mounted, "/apiv2"));
        assertEquals("exact-prefix", body(mounted, "/exact/item"));
        assertEquals("only", body(mounted, "/only"));
        assertEquals("/only/more", body(mounted, "/only/more"));
        assertEquals(200, mounted.handle(new Request("HEAD", "/api")).status());
    }

    @Test
    void twoContributorsStayDistinct() {
        Handler fallback = request -> text(404, "miss");
        Handler mounted = HandlerMounts.install(List.of(
                mounts -> mounts.mount("/a", request -> text(201, "a")),
                mounts -> mounts.mount("/b/*", request -> text(202, "b"))), fallback);
        assertEquals(201, mounted.handle(new Request("GET", "/a")).status());
        assertEquals(202, mounted.handle(new Request("GET", "/b/c")).status());
        assertEquals("miss", body(mounted, "/c"));
    }

    @Test
    void overlapsFailFast() {
        Handler handler = request -> text(200, "x");
        assertOverlap("/a", "/a/b", "/a/*", "/a/b/*");
        assertOverlap("/a/", "/a", "/a/*");
        var exact = RoutePattern.parse("/only");
        var message = assertThrows(IllegalArgumentException.class, () -> HandlerMounts.install(List.of(mounts -> {
            mounts.mount(exact, handler);
            mounts.mount("/only", handler);
        }), handler)).getMessage();
        assertTrue(message.contains("/only") && message.contains("/only/*"), message);
        message = assertThrows(IllegalArgumentException.class, () -> HandlerMounts.install(List.of(mounts -> {
            mounts.mount(RoutePattern.all(), handler);
            mounts.mount("/x", handler);
        }), handler)).getMessage();
        assertTrue(message.contains("*"), message);
        assertThrows(IllegalArgumentException.class, () -> HandlerMounts.install(List.of(mounts -> {
            mounts.mount("/*", handler);
            mounts.mount(RoutePattern.parse("/index.html"), handler);
        }), handler));
        assertFalse(RoutePattern.parse("/api/*").overlaps(null));
        assertFalse(RoutePattern.parse("/api/*").overlaps(RoutePattern.parse("/apiv2")));
        assertFalse(RoutePattern.parse("/").overlaps(RoutePattern.parse("/foo/*")));
    }

    @Test
    void badMountsAndAClosedRegistry() {
        Handler handler = request -> text(200, "x");
        assertThrows(NullPointerException.class, () -> HandlerMounts.install(null, handler));
        assertThrows(NullPointerException.class, () -> HandlerMounts.install(List.of(), null));
        assertThrows(IllegalArgumentException.class,
                () -> HandlerMounts.install(java.util.Arrays.asList((HandlerContributor) null), handler));
        assertThrows(IllegalArgumentException.class,
                () -> HandlerMounts.discover(java.util.Arrays.asList((HandlerContributor) null)));
        assertThrows(NullPointerException.class,
                () -> HandlerMounts.install(List.of(mounts -> mounts.mount((RoutePattern) null, handler)), handler));
        assertThrows(NullPointerException.class, () -> HandlerMounts.install(List.of(mounts -> mounts.mount("/a", null)), handler));
        assertThrows(IllegalArgumentException.class, () -> HandlerMounts.install(List.of(mounts -> mounts.mount("  ", handler)), handler));
        assertThrows(IllegalArgumentException.class, () -> HandlerMounts.install(List.of(mounts -> mounts.mount("api", handler)), handler));
        assertThrows(IllegalArgumentException.class, () -> HandlerMounts.install(List.of(mounts -> mounts.mount("/a?x", handler)), handler));
        assertThrows(IllegalArgumentException.class, () -> HandlerMounts.install(List.of(mounts -> mounts.mount("/a#b", handler)), handler));
        var held = new HandlerMounts[1];
        HandlerMounts.install(List.of(mounts -> held[0] = mounts), handler);
        assertThrows(IllegalStateException.class, () -> held[0].mount("/late", handler));
        assertThrows(IllegalStateException.class, () -> held[0].mount(RoutePattern.parse("/late"), handler));
    }

    @Test
    void serviceLoaderFindsAProviderBesideAnExplicitContributor() throws Exception {
        var services = this.dir.resolve("META-INF/services");
        Files.createDirectories(services);
        Files.writeString(services.resolve(HandlerContributor.class.getName()),
                ServiceContributor.class.getName() + "\n");
        try (var loader = new URLClassLoader(new URL[] {this.dir.toUri().toURL()},
                HandlerMountTest.class.getClassLoader())) {
            HandlerContributor explicit = mounts -> mounts.mount("/explicit", request -> text(200, "explicit"));
            var found = HandlerMounts.discover(loader, List.of(explicit));
            assertEquals(2, found.size());
            assertTrue(found.get(0) instanceof ServiceContributor);
            assertSame(explicit, found.get(1));
            var handler = HandlerMounts.install(found, request -> text(404, "no"));
            assertEquals("service", body(handler, "/from-service"));
            assertEquals("explicit", body(handler, "/explicit"));
        }
    }

    private static void assertOverlap(String first, String second, String... expected) {
        Handler handler = request -> text(200, "x");
        var message = assertThrows(IllegalArgumentException.class, () -> HandlerMounts.install(List.of(mounts -> {
            mounts.mount(first, handler);
            mounts.mount(second, handler);
        }), handler)).getMessage();
        for (var piece : expected) {
            assertTrue(message.contains(piece), message);
        }
    }

    private static String body(Handler handler, String target) {
        var response = handler.handle(new Request("GET", target));
        if (response.body() instanceof Body.Bytes bytes) {
            var data = bytes.data().duplicate();
            var raw = new byte[data.remaining()];
            data.get(raw);
            return new String(raw, StandardCharsets.UTF_8);
        }
        return "";
    }

    private static Response text(int status, String body) {
        return new Response(status, ResponseHeaders.of().add("content-type", "text/plain"),
                Body.Bytes.of(body.getBytes(StandardCharsets.UTF_8)));
    }

    /** Public so a one-off service file can name it. Not on the server classpath as a provider. */
    public static final class ServiceContributor implements HandlerContributor {
        @Override
        public void contribute(HandlerMounts mounts) {
            mounts.mount("/from-service", request -> new Response(200,
                    ResponseHeaders.of().add("content-type", "text/plain"),
                    Body.Bytes.of("service".getBytes(StandardCharsets.UTF_8))));
        }
    }
}
