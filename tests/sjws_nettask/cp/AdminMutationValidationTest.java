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
package sjws_nettask.cp;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.cluster.FakeClock;

/**
 * Mutating admin requests: a present Origin must be allowlisted; Content-Type must be
 * application/json. GET is unchanged. These assertions fail on master (no checks).
 */
class AdminMutationValidationTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final long TTL = 60_000L;

    @TempDir
    Path dir;

    @Test
    void evilOriginIs403AndNonJsonIs415() throws Exception {
        var keys = KeyRing.generate("online-1", NOW + 86_400_000L, 0, Long.MAX_VALUE / 2);
        var clock = new FakeClock(0);
        try (var store = CpStore.open(dir.resolve("cp"), keys, clock);
                var http = AdminServer.startPlainForTests(store, NOW, TTL, Set.of("https://console.example"))) {
            clock.advance(Duration.ofMillis(100));
            store.drive();
            assertTrue(store.leadership().leaderReady());
            var client = HttpClient.newHttpClient();
            var base = "http://127.0.0.1:" + http.port() + "/cp/v1/rbac";
            var body = "{\"san\":\"urn:sjws:admin:x\",\"role\":\"viewer\"}";

            var evil = client.send(HttpRequest.newBuilder(URI.create(base)).timeout(Duration.ofSeconds(3))
                    .header("Origin", "https://evil.example")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(403, evil.statusCode(), evil.body());

            var form = client.send(HttpRequest.newBuilder(URI.create(base)).timeout(Duration.ofSeconds(3))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("san=x&role=viewer")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(415, form.statusCode(), form.body());

            var plain = client.send(HttpRequest.newBuilder(URI.create(base)).timeout(Duration.ofSeconds(3))
                    .header("Content-Type", "text/plain")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(415, plain.statusCode(), plain.body());

            // Missing Origin + JSON is allowed (CLI); auth still applies (plain listener → 401).
            var cli = client.send(HttpRequest.newBuilder(URI.create(base)).timeout(Duration.ofSeconds(3))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(401, cli.statusCode(), cli.body());

            var allowed = client.send(HttpRequest.newBuilder(URI.create(base)).timeout(Duration.ofSeconds(3))
                    .header("Origin", "https://console.example")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(401, allowed.statusCode(), allowed.body());

            var get = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + http.port()
                    + "/cp/v1/health")).timeout(Duration.ofSeconds(3)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(401, get.statusCode(), get.body());
        }
    }

    @Test
    void emptyAllowlistRefusesAnyOrigin() throws Exception {
        var keys = KeyRing.generate("online-1", NOW + 86_400_000L, 0, Long.MAX_VALUE / 2);
        var clock = new FakeClock(0);
        try (var store = CpStore.open(dir.resolve("cp"), keys, clock);
                var http = AdminServer.startPlainForTests(store, NOW, TTL)) {
            clock.advance(Duration.ofMillis(100));
            store.drive();
            var client = HttpClient.newHttpClient();
            var base = "http://127.0.0.1:" + http.port() + "/cp/v1/config:validate";
            var refused = client.send(HttpRequest.newBuilder(URI.create(base)).timeout(Duration.ofSeconds(3))
                    .header("Origin", "https://anywhere.example")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(403, refused.statusCode(), refused.body());
        }
    }

    @Test
    void jsonContentTypeHelper() {
        assertTrue(AdminServer.isJsonContentType("application/json"));
        assertTrue(AdminServer.isJsonContentType("Application/JSON; charset=utf-8"));
        assertFalse(AdminServer.isJsonContentType(null));
        assertFalse(AdminServer.isJsonContentType("text/plain"));
        assertFalse(AdminServer.isJsonContentType("application/jsonx"));
    }
}
