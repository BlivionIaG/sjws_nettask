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

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.SjwsServer;
import sjws_nettask.cluster.FakeClock;
import sjws_nettask.config.ConfigLoader;
import sjws_nettask.service.NodeEnrollment;
import sjws_nettask.service.ServiceLaunch;

/** Watch server, wire, trust bootstrap and the data-node watch client (design §8, first increment). */
class WatchTest {

    private static final String SECRET = "0123456789abcdef";

    @TempDir
    Path dir;

    private static CpStore elected(Path at, KeyRing keys) throws IOException {
        var clock = new FakeClock(0);
        var store = CpStore.open(at, keys, clock);
        clock.advance(Duration.ofMillis(100));
        store.drive();
        assertTrue(store.leadership().leaderReady());
        return store;
    }

    private static void issue(CpStore store, String tokenId) {
        assertEquals(200, store.issueJoinToken(tokenId, SECRET.getBytes(StandardCharsets.UTF_8),
                System.currentTimeMillis(), JoinTokens.DEFAULT_TTL_MILLIS).status());
    }

    private static KeyRing keys() {
        return KeyRing.generate("online-1", System.currentTimeMillis() + 86_400_000L, 0, Long.MAX_VALUE / 2);
    }

    private static HttpResponse<String> call(int port, String method, String path, String node, String token,
            String secret) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (node != null) {
            b.header(WatchServer.NODE, node);
        }
        if (token != null) {
            b.header(WatchServer.TOKEN_ID, token);
        }
        if (secret != null) {
            b.header(WatchServer.SECRET, secret);
        }
        return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String root() throws IOException {
        var html = Files.createDirectories(this.dir.resolve("html"));
        Files.writeString(html.resolve("index.html"), "x");
        return html.toString();
    }

    @Test
    void watchServerAuthenticatesLongPollsAndRecordsAcks() throws Exception {
        var keys = keys();
        try (var store = elected(this.dir.resolve("cp"), keys);
                var watch = WatchServer.start(store, 0, System::currentTimeMillis)) {
            int p = watch.port();
            var ok = Base64.getEncoder().encodeToString(SECRET.getBytes(StandardCharsets.UTF_8));
            assertEquals(401, call(p, "GET", "/cp/v1/watch", null, null, null).statusCode());
            assertEquals(401, call(p, "GET", "/cp/v1/watch", "gw-1", "t", "%%%").statusCode());
            assertEquals(401, call(p, "GET", "/cp/v1/watch", "gw-1", "t", "c2hvcnQ=").statusCode());
            assertEquals(401, call(p, "GET", "/cp/v1/watch?waitMs=0", "gw-1", "t", ok).statusCode(), "not issued");
            issue(store, "t");
            assertEquals(204, call(p, "GET", "/cp/v1/watch?waitMs=0", "gw-1", "t", ok).statusCode());
            assertEquals(204, call(p, "GET", "/cp/v1/watch?waitMs=0", "gw-1", "t", ok).statusCode(), "joined node polls again");
            assertEquals(401, call(p, "GET", "/cp/v1/watch?waitMs=0", "gw-1", "t",
                    Base64.getEncoder().encodeToString("fedcba9876543210".getBytes(StandardCharsets.UTF_8)))
                    .statusCode(), "wrong secret");
            assertEquals(401, call(p, "GET", "/cp/v1/watch", "gw-2", "t", ok).statusCode(), "token bound to gw-1");
            assertEquals(400, call(p, "GET", "/cp/v1/watch?after=x", "gw-1", "t", ok).statusCode());
            assertEquals(404, call(p, "GET", "/cp/v1/other", "gw-1", "t", ok).statusCode());
            assertEquals(400, call(p, "POST", "/cp/v1/watch/ack", "gw-1", "t", ok).statusCode());
            long t0 = System.nanoTime();
            assertEquals(204, call(p, "GET", "/cp/v1/watch?waitMs=50", "gw-1", "t", ok).statusCode());
            assertTrue(System.nanoTime() - t0 >= 40_000_000L);

            assertEquals(200, store.propose("server:\n  port: 0\n  root: " + root() + "\n").status());
            assertEquals(200, store.activate(System.currentTimeMillis(), 60_000L).status());
            var got = call(p, "GET", "/cp/v1/watch?after=0&waitMs=1000", "gw-1", "t", ok);
            assertEquals(200, got.statusCode());
            var message = WatchWire.decode(got.body());
            assertEquals(store.activated(), message.snapshot());
            assertEquals(keys.keys().size(), message.keys().size());
            assertEquals(204, call(p, "GET", "/cp/v1/watch?after=1&waitMs=0", "gw-1", "t", ok).statusCode());
            assertEquals(204, call(p, "POST", "/cp/v1/watch/ack?version=1", "gw-1", "t", ok).statusCode());
            assertTrue(store.nodesJson().contains("\"gw-1\""), store.nodesJson());
            assertEquals(200, store.revoke("gw-1").status());
            assertEquals(403, call(p, "GET", "/cp/v1/watch", "gw-1", "t", ok).statusCode());
            Thread.currentThread().interrupt();
            assertNull(store.awaitActivated(5, 1_000));
            assertTrue(Thread.interrupted());
        }
    }

    /** keyCount is a long on the wire: bounds at MAX_KEYS, and int-overflow sizes fail closed. */
    @Test
    void keyCountBoundaries() throws Exception {
        var keys = keys();
        try (var store = elected(this.dir.resolve("cp"), keys)) {
            assertEquals(200, store.propose("server:\n  port: 0\n  root: " + root() + "\n").status());
            assertEquals(200, store.activate(System.currentTimeMillis(), 60_000L).status());
            var text = WatchWire.encode(keys, store.activated());
            int from = text.indexOf(",\"k0.keyId\":");
            int to = text.indexOf(",\"snapshot\":");
            var k0 = text.substring(from, to);
            for (int n : new int[] {1, WatchWire.MAX_KEYS - 1, WatchWire.MAX_KEYS}) {
                var extra = new StringBuilder();
                for (int i = 1; i < n; i++) {
                    extra.append(k0.replace("\"k0.", "\"k" + i + "."));
                }
                var padded = text.substring(0, to).replace("\"keyCount\":1", "\"keyCount\":" + n) + extra
                        + text.substring(to);
                assertEquals(n, WatchWire.decode(padded).keys().size(), "keyCount " + n);
            }
            long[] bad = {0L, -1L, WatchWire.MAX_KEYS + 1L, Integer.MAX_VALUE, Integer.MAX_VALUE + 1L,
                    1L << 32, (1L << 32) + 1L, Long.MAX_VALUE, Long.MIN_VALUE};
            for (long n : bad) {
                var e = assertThrows(IOException.class,
                        () -> WatchWire.decode(text.replace("\"keyCount\":1", "\"keyCount\":" + n)), "keyCount " + n);
                assertEquals("keyCount", e.getMessage(), "keyCount " + n);
            }
        }
    }

    @Test
    void wireAndTrustFailClosed() throws Exception {
        var keys = keys();
        try (var store = elected(this.dir.resolve("cp"), keys)) {
            assertEquals(200, store.propose("server:\n  port: 0\n  root: " + root() + "\n").status());
            assertEquals(200, store.activate(System.currentTimeMillis(), 60_000L).status());
            var text = WatchWire.encode(keys, store.activated());
            var message = WatchWire.decode(text);
            var pin = this.dir.resolve("pin");
            LastGoodFiles.writePin(pin, keys.rootPublic(), keys.versionFloor());
            assertNotNull(DataApply.trusted(pin, message, this.dir.resolve("s"), System::currentTimeMillis));
            var other = this.dir.resolve("other.pin");
            LastGoodFiles.writePin(other, keys().rootPublic(), 0);
            assertThrows(IOException.class,
                    () -> DataApply.trusted(other, message, this.dir.resolve("s"), System::currentTimeMillis));
            var high = this.dir.resolve("high.pin");
            LastGoodFiles.writePin(high, keys.rootPublic(), 5);
            assertThrows(IOException.class,
                    () -> DataApply.trusted(high, message, this.dir.resolve("s"), System::currentTimeMillis));
            assertThrows(IOException.class, () -> WatchWire.decode(text.replace("\"keyCount\":1", "\"keyCount\":0")));
            assertThrows(IOException.class, () -> WatchWire.decode(text.replace("\"snapshot\":", "\"snap\":")));
            assertThrows(IOException.class, () -> WatchWire.decode(text.replace("\"k0.keyId\":\"online-1\"",
                    "\"k0.keyId\":\"\"")));
        }
    }

    @Test
    void endpointIsLoopbackHostPort() throws Exception {
        assertEquals(URI.create("http://127.0.0.1:9"), DataWatch.endpoint("127.0.0.1:9"));
        assertEquals(URI.create("http://localhost:9"), DataWatch.endpoint("http://localhost:9"));
        for (var bad : new String[] {null, " ", "https://127.0.0.1:9", "127.0.0.1", "http://[::1", "10.1.2.3:9"}) {
            assertThrows(IOException.class, () -> DataWatch.endpoint(bad), String.valueOf(bad));
        }
        assertEquals(-1L, WatchServer.param("a=b", "a", 0));
        assertEquals(7L, WatchServer.param("x=1&a=7", "a", 0));
        assertEquals(3L, WatchServer.param(null, "a", 3));
    }

    /** A data node through ServiceLaunch: wrong pin is skipped (fail closed), then a CP outage is reported. */
    @Test
    void dataWatchSkipsUntrustedSnapshotsAndReportsOutage() throws Exception {
        var keys = keys();
        var store = elected(this.dir.resolve("cp"), keys);
        issue(store, "node-1");
        var watch = WatchServer.start(store, 0, System::currentTimeMillis);
        var pin = this.dir.resolve("root.pub");
        LastGoodFiles.writePin(pin, keys().rootPublic(), 0); // not this control plane's root
        var token = this.dir.resolve("join.token");
        Files.writeString(token, "token-id=node-1\nsecret=" + SECRET + "\n");
        Files.setPosixFilePermissions(token, PosixFilePermissions.fromString("rw-------"));
        var boot = this.dir.resolve("bootstrap");
        Files.writeString(boot, "role=data\ndata-role=gateway\nnode-id=gw-1\ncontrol-plane=127.0.0.1:" + watch.port()
                + "\npin=" + pin + "\njoin-token=" + token + "\nstate-dir=" + this.dir.resolve("state") + "\n");
        Files.setPosixFilePermissions(boot, PosixFilePermissions.fromString("rw-------"));
        var yaml = this.dir.resolve("sjws.yaml");
        var html = root();
        Files.writeString(yaml, "schema: 1\nnode:\n  mode: data\n  state_dir: " + this.dir.resolve("cfg")
                + "\nserver:\n  port: 0\n  root: " + html + "\n");
        var server = SjwsServer.start(ConfigLoader.load(new String[] {"--config=" + yaml}, Map.of()));
        var hooks = ServiceLaunch.prepare(boot, "data");
        try {
            hooks.onListening(server, this.dir.resolve("node.pid"), Duration.ofSeconds(5));
            await(() -> state(), "connected");
            assertEquals(200, store.propose("server:\n  port: 0\n  root: " + html + "\n").status());
            assertEquals(200, store.activate(System.currentTimeMillis(), 60_000L).status());
            Thread.sleep(300);
            assertEquals(0L, NodeEnrollment.current().binding().configVersion(), "untrusted bundle is not applied");
            assertFalse(Files.exists(this.dir.resolve("state").resolve("config").resolve("last-good.json")));
            watch.close();
            await(() -> state(), "pending");
        } finally {
            hooks.onShutdown();
            server.close();
            watch.close();
            store.close();
        }
    }

    /** Last-good is written only in DATA mode; elsewhere the floor still moves in memory. */
    @Test
    void standaloneApplyWritesNoLastGood() throws Exception {
        var keys = keys();
        try (var store = elected(this.dir.resolve("cp"), keys)) {
            var html = root();
            assertEquals(200, store.propose("server:\n  port: 0\n  root: " + html + "\n").status());
            assertEquals(200, store.activate(System.currentTimeMillis(), 60_000L).status());
            var yaml = this.dir.resolve("sjws.yaml");
            Files.writeString(yaml, "server:\n  port: 0\n  root: " + html + "\n");
            var apply = DataApply.open(keys, this.dir.resolve("pin"), this.dir.resolve("node"),
                    System::currentTimeMillis);
            try (var server = SjwsServer.start(ConfigLoader.load(new String[] {"--config=" + yaml}, Map.of()))) {
                server.attachControlPlane(apply);
                assertTrue(server.applyRemote(store.activated()).applied());
                assertEquals(1L, apply.appliedVersion());
                assertFalse(Files.exists(this.dir.resolve("node").resolve("config").resolve("last-good.json")));
            }
        }
    }

    private static String state() {
        var e = NodeEnrollment.current();
        var b = e == null ? null : e.binding();
        return b == null ? "pending" : b.connectionState();
    }

    private static void await(Supplier<String> what, String want) throws InterruptedException {
        long end = System.nanoTime() + 20_000_000_000L;
        while (!want.equals(what.get())) {
            assertTrue(System.nanoTime() < end, "wanted " + want + ", have " + what.get());
            Thread.sleep(10);
        }
    }
}
