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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.HttpTestSupport;
import sjws_nettask.ServerConfig;
import sjws_nettask.SjwsServer;
import sjws_nettask.cluster.FakeClock;
import sjws_nettask.config.ConfigDocument;
import sjws_nettask.config.ConfigLoader;
import sjws_nettask.config.NodeSettings;
import sjws_nettask.core.RequestFilters;

class CpStoreTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final long TTL = 60_000L;

    @TempDir
    Path dir;

    @Test
    void tamperFailsClosedAndAGoodSnapshotApplies() throws Exception {
        var root = Files.createDirectory(dir.resolve("html"));
        Files.writeString(root.resolve("index.html"), "one");
        var other = Files.createDirectory(dir.resolve("other"));
        Files.writeString(other.resolve("index.html"), "two");
        var keys = KeyRing.generate("online-1", NOW + 86_400_000L, 0, Long.MAX_VALUE / 2);
        var clock = new FakeClock(0);
        try (var store = CpStore.open(dir.resolve("cp"), keys, clock)) {
            elect(store, clock);
            var yaml = "server:\n  port: 0\n  root: " + root.toAbsolutePath() + "\n";
            var proposed = store.propose(yaml);
            assertEquals(200, proposed.status(), proposed.body());
            var conflict = store.propose(yaml);
            assertEquals(409, conflict.status());
            var rbac = store.propose("rbac:\n  a: b\n" + yaml);
            assertEquals(400, rbac.status());
            var activated = store.activate(NOW, TTL);
            assertEquals(200, activated.status(), activated.body());
            var signed = store.activated();
            assertEquals(1L, signed.version());
            assertEquals(SnapshotCrypto.KIND_FULL, signed.kind());
            assertEquals(0L, signed.parentVersion());
            assertEquals(KeyRing.hashHex(signed.canonicalYaml()), signed.canonicalHashHex());

            var flipped = signed.withSignature(flip(signed.signature()));
            var apply = DataApply.open(keys, dir.resolve("pin"), dir.resolve("node"), () -> NOW + 1);
            var rejected = apply.decide(flipped, snapshotZero());
            assertEquals("signature", rejected.error());

            var kind = apply.decide(signed.withKind((byte) 2).withSignature(signed.signature()), snapshotZero());
            assertEquals("kind", kind.error());
            var parent = apply.decide(signed.withParentVersion(4), snapshotZero());
            assertEquals("parentVersion", parent.error());
            var schema = apply.decide(signed.withSchema(9), snapshotZero());
            assertEquals("schema", schema.error());
            var yamlFlip = apply.decide(signed.withCanonicalYaml(signed.canonicalYaml() + "\n"), snapshotZero());
            assertEquals("canonicalHash", yamlFlip.error());

            var loaded = ConfigLoader.load(new String[] {"--config=" + write(dir.resolve("sjws.yaml"),
                    "server:\n  port: 0\n  root: " + root.toAbsolutePath() + "\n")}, java.util.Map.of());
            try (var server = SjwsServer.start(loaded)) {
                assertEquals("one", HttpTestSupport.get(server.tcpPort(), "/", 2000).bodyText());
                server.attachControlPlane(apply);
                var unattached = SjwsServer.start(loaded);
                try {
                    var missing = unattached.applyRemote(signed);
                    assertFalse(missing.applied());
                    assertTrue(missing.errors().get(0).contains("not attached"));
                } finally {
                    unattached.close();
                }
                var bad = server.applyRemote(flipped);
                assertFalse(bad.applied());
                assertEquals("one", HttpTestSupport.get(server.tcpPort(), "/", 2000).bodyText());
                var good = server.applyRemote(signed);
                assertTrue(good.applied(), good.errors().toString());
                assertEquals(1L, good.snapshot().version());
                assertEquals(signed.epoch(), good.snapshot().epoch());
                var again = server.applyRemote(signed);
                assertTrue(again.applied());
                assertEquals(good.generation(), again.generation());
                var last = dir.resolve("node").resolve("config").resolve("last-good.json");
                assertTrue(Files.isRegularFile(last));
                var read = DataApply.readLastGood(dir.resolve("node"));
                assertEquals(signed.canonicalHashHex(), read.canonicalHashHex());
                assertEquals(signed.version(), read.version());
                var floor = Files.readString(dir.resolve("node").resolve("config").resolve("floor.json"));
                assertTrue(floor.contains("\"maxVersion\":1"));

                var secondYaml = "server:\n  port: 0\n  root: " + other.toAbsolutePath() + "\n";
                assertEquals(200, store.propose(secondYaml).status());
                var secondActivation = store.activate(NOW, TTL);
                assertEquals(200, secondActivation.status(), secondActivation.body());
                var moved = server.applyRemote(store.activated());
                assertTrue(moved.applied(), moved.errors().toString());
                assertEquals("two", HttpTestSupport.get(server.tcpPort(), "/", 2000).bodyText());

                var rolled = store.rollback(1, NOW, TTL);
                assertEquals(200, rolled.status(), rolled.body());
                assertEquals(store.history().get(0).canonicalHashHex(), store.activated().canonicalHashHex());
                assertTrue(store.activated().version() > 1);
                var back = server.applyRemote(store.activated());
                assertTrue(back.applied(), back.errors().toString());
                assertEquals(store.activated().version(), back.snapshot().version());
                assertEquals("one", HttpTestSupport.get(server.tcpPort(), "/", 2000).bodyText());
            }
            var diff = store.diff(1, 2, false);
            assertEquals(200, diff.status(), diff.body());
            assertTrue(diff.body().contains("\"section\""));
            assertFalse(diff.body().contains("canonicalYaml"));
        }
    }

    @Test
    void filtersObserveAPushedRootAndDataSighupKeepsControlSections() throws Exception {
        var first = Files.createDirectory(dir.resolve("a"));
        var second = Files.createDirectory(dir.resolve("b"));
        Files.writeString(first.resolve("index.html"), "aaa");
        Files.writeString(second.resolve("index.html"), "bbb");
        var filters = RequestFilters.create().add((request, next) -> next.proceed(request));
        var current = ConfigLoader.load(new String[] {"--config=" + write(dir.resolve("live.yaml"),
                "server:\n  port: 0\n  root: " + first.toAbsolutePath() + "\n")}, java.util.Map.of());
        try (var live = SjwsServer.start(current.document().server(), filters)) {
            assertNotNull(live.filterChain());
            assertEquals("aaa", HttpTestSupport.get(live.tcpPort(), "/", 2000).bodyText());
            var document = ConfigDocument.of(current.document().server().toBuilder().root(second).build(),
                    current.document().acme(), current.document().routing(), current.document().middleware(),
                    current.document().access(), current.document().node(), 1, 1, null, null);
            assertTrue(live.applyRemote(document).applied());
            assertEquals("bbb", HttpTestSupport.get(live.tcpPort(), "/", 2000).bodyText());
        }
        var yaml = dir.resolve("data.yaml");
        Files.writeString(yaml, "node:\n  mode: data\nserver:\n  port: 0\n  root: " + first.toAbsolutePath() + "\n");
        var loaded = ConfigLoader.load(new String[] {"--config=" + yaml}, java.util.Map.of());
        try (var server = SjwsServer.start(loaded)) {
            Files.writeString(yaml, "node:\n  mode: data\nserver:\n  port: 0\n  root: " + second.toAbsolutePath()
                    + "\nrouting:\n  enabled: true\n");
            var rejected = server.reload();
            assertFalse(rejected.applied(), rejected.errors().toString());
            assertEquals("aaa", HttpTestSupport.get(server.tcpPort(), "/", 2000).bodyText());
            Files.writeString(yaml, "node:\n  mode: data\n  labels:\n    site: edge\nserver:\n  port: 0\n  root: "
                    + first.toAbsolutePath() + "\n");
            var labels = server.reload();
            assertTrue(labels.applied(), labels.errors().toString());
            assertEquals(NodeSettings.Mode.DATA, labels.snapshot().document().node().mode());
            assertEquals("edge", labels.snapshot().document().node().labels().get("site"));
        }
    }

    @Test
    void adminRoutesRequireAClientCertificate() throws Exception {
        var root = Files.createDirectory(dir.resolve("html"));
        var keys = KeyRing.generate("online-1", NOW + 86_400_000L, 0, Long.MAX_VALUE / 2);
        var clock = new FakeClock(0);
        try (var store = CpStore.open(dir.resolve("cp"), keys, clock);
                var http = AdminServer.startPlainForTests(store, NOW, TTL)) {
            elect(store, clock);
            var client = HttpClient.newHttpClient();
            var bearer = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + http.port()
                    + "/cp/v1/health")).header("Authorization", "Bearer sjws_root.secret")
                    .header("Idempotency-Key", "retry-1").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(401, bearer.statusCode(), bearer.body());
            var denied = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + http.port()
                    + "/cp/v1/health")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(401, denied.statusCode());
            var yaml = "server:\n  port: 0\n  root: " + root.toAbsolutePath() + "\n";
            var adminCert = new sjws_nettask.controlplane.Rbac.ClientCert("urn:sjws:admin:root");
            var viewerCert = new sjws_nettask.controlplane.Rbac.ClientCert("urn:sjws:admin:viewer");
            var operatorCert = new sjws_nettask.controlplane.Rbac.ClientCert("urn:sjws:admin:operator");
            var admin = new CpAdmin(store, NOW, TTL);
            var health = admin.handle(adminCert, "GET", "/cp/v1/health", null, "");
            assertEquals(200, health.status(), health.body());
            assertTrue(health.body().contains("\"leaderReady\":true"));
            assertEquals(200, admin.handle(adminCert, "POST", "/cp/v1/config", null, yaml).status());
            assertEquals(401, admin.handle(viewerCert, "POST", "/cp/v1/config", null, yaml).status());
            assertEquals(200, admin.handle(adminCert, "POST", "/cp/v1/rbac", null,
                    "{\"san\":\"urn:sjws:admin:viewer\",\"role\":\"viewer\"}").status());
            assertEquals(200, admin.handle(adminCert, "POST", "/cp/v1/rbac", null,
                    "{\"san\":\"urn:sjws:admin:operator\",\"role\":\"operator\"}").status());
            assertEquals(403, admin.handle(viewerCert, "POST", "/cp/v1/config", null, yaml).status());
            assertEquals(403, admin.handle(operatorCert, "POST", "/cp/v1/nodes/n1/revoke", null, "").status());
            assertEquals(200, admin.handle(operatorCert, "POST", "/cp/v1/rollout", null,
                    "{\"selectors\":[\"edge\",\"internal\"]}").status());
            assertEquals(200, admin.handle(operatorCert, "POST", "/cp/v1/rollout/advance", null, "").status());
            assertEquals(1, store.stage());
            assertEquals(200, admin.handle(adminCert, "POST", "/cp/v1/nodes/n1/revoke", null, "").status());
            assertTrue(store.revoked().contains("n1"));
            assertEquals(sjws_nettask.controlplane.Role.ADMIN, store.roleOfSan("urn:sjws:admin:root"));
            assertEquals(sjws_nettask.controlplane.Role.VIEWER, store.roleOfSan("urn:sjws:admin:viewer"));
            assertTrue(store.adminEnrolled());
            assertEquals(401, admin.handle(null, "GET", "/cp/v1/health", null, "").status());
            assertEquals(404, admin.handle(adminCert, "GET", "/cp/v1/missing", null, "").status());
            assertEquals(-1L, CpAdmin.queryLong("from=no", "from"));
            assertNull(CpAdmin.jsonString("{}", "san"));
        }
    }

    @Test
    void jumpExpiryAndFloorReject() throws Exception {
        var keys = KeyRing.generate("online-1", NOW + 1_000L, 1, 10, 2);
        var apply = DataApply.open(keys, dir.resolve("pin"), dir.resolve("node"), () -> NOW);
        var document = ConfigDocument.defaults(ServerConfig.defaults(dir));
        var snapshot = keys.sign(copy(document, 3), 3, 1, 2, NOW, NOW + 1_000, 0);
        var jump = keys.sign(copy(document, 3), (1L << 20) + 5, 1, 2, NOW, NOW + 1_000, 0);
        assertEquals("version jump", apply.decide(jump, installed(0, "")).error());
        var early = keys.sign(copy(document, 3), 3, 1, 2, NOW + 5_000, NOW + 9_000, 0);
        assertEquals("expired", apply.decide(early, installed(0, "")).error());
        var below = keys.sign(copy(document, 1), 1, 1, 2, NOW, NOW + 1_000, 0);
        assertEquals("floor", apply.decide(below, installed(0, "")).error());
        var ok = apply.decide(snapshot, installed(0, ""));
        assertNull(ok.error(), ok.error());
        assertFalse(ok.noop());
        var missing = apply.decide(snapshot.withParentVersion(0).withKind(SnapshotCrypto.KIND_FULL), installed(0, ""));
        assertNull(missing.error());
        var rollback = keys.sign(copy(document, 3), 4, 1, 3, NOW, NOW + 1_000, 3);
        assertEquals("rollback", apply.decide(rollback, installed(3, snapshot.canonicalHashHex())).error());
    }

    private static void elect(CpStore store, FakeClock clock) {
        var saw = new AtomicBoolean();
        var readyDuring = new AtomicBoolean(true);
        store.leadership().onLeaderChange((term, id) -> {
            saw.set(true);
            readyDuring.set(store.leadership().leaderReady());
        });
        clock.advance(Duration.ofMillis(100));
        store.drive();
        assertTrue(saw.get());
        assertFalse(readyDuring.get());
        assertTrue(store.leadership().isLeader());
        assertTrue(store.leadership().leaderReady());
        assertTrue(store.leadership().term() >= 1);
    }

    private static byte[] flip(byte[] signature) {
        var copy = Arrays.copyOf(signature, signature.length);
        copy[0] ^= 0x01;
        return copy;
    }

    private static Path write(Path path, String text) throws Exception {
        Files.writeString(path, text);
        return path;
    }

    private static sjws_nettask.config.AppliedConfig snapshotZero() {
        return new sjws_nettask.config.AppliedConfig() {
            public long version() {
                return 0;
            }

            public String canonicalHash() {
                return "";
            }

            public long epoch() {
                return 0;
            }
        };
    }

    private static sjws_nettask.config.AppliedConfig installed(long version, String hash) {
        return new sjws_nettask.config.AppliedConfig() {
            public long version() {
                return version;
            }

            public String canonicalHash() {
                return hash;
            }

            public long epoch() {
                return 0;
            }
        };
    }

    private static ConfigDocument copy(ConfigDocument document, long version) {
        return ConfigDocument.of(document.server(), document.acme(), document.routing(), document.middleware(),
                document.access(), document.node(), document.schemaVersion(), version, null, null);
    }
}
