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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.cluster.FakeClock;
import sjws_nettask.controlplane.Rbac;
import sjws_nettask.controlplane.Role;

/** History, dry-run, rollout, audit, idempotency keys, and node lag. Certificate RBAC only. */
class ConsoleApiTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final long TTL = 60_000L;

    @TempDir
    Path dir;

    @Test
    void readsHistoryRolloutAuditAndNodeLag() throws Exception {
        var root = Files.createDirectory(dir.resolve("html"));
        var yaml = "server:\n  port: 0\n  root: " + root.toAbsolutePath() + "\n";
        var keys = KeyRing.generate("online-1", NOW + 86_400_000L, 0, Long.MAX_VALUE / 2);
        var clock = new FakeClock(0);
        try (var store = CpStore.open(dir.resolve("cp"), keys, clock)) {
            elect(store, clock);
            var admin = new CpAdmin(store, NOW, TTL);
            var rootCert = new Rbac.ClientCert("urn:sjws:admin:root");
            assertEquals(200, admin.handle(rootCert, "POST", "/cp/v1/rbac", null,
                    "{\"san\":\"urn:sjws:admin:viewer\",\"role\":\"viewer\"}").status());
            assertEquals(200, admin.handle(rootCert, "POST", "/cp/v1/rbac", null,
                    "{\"san\":\"urn:sjws:admin:operator\",\"role\":\"operator\"}").status());
            var viewer = new Rbac.ClientCert("urn:sjws:admin:viewer");
            var operator = new Rbac.ClientCert("urn:sjws:admin:operator");
            assertEquals(403, admin.handle(viewer, "POST", "/cp/v1/config:validate", null, yaml).status());
            assertEquals(401, admin.handle(null, "GET", "/cp/v1/audit", null, "").status());
            assertEquals(401, admin.handle(null, "GET", "/cp/v1/config/1", null, "").status());
            var bad = admin.handle(operator, "POST", "/cp/v1/config:validate", null, ":\n");
            assertEquals(200, bad.status(), bad.body());
            assertTrue(bad.body().contains("\"ok\":false"), bad.body());
            assertEquals(0, store.history().size());
            var dry = admin.handle(operator, "POST", "/cp/v1/config:validate", null, yaml);
            assertEquals(200, dry.status(), dry.body());
            assertTrue(dry.body().contains("\"ok\":true"), dry.body());
            assertEquals(200, admin.handle(operator, "POST", "/cp/v1/config", null, yaml, "propose-1").status());
            assertEquals(200, admin.handle(operator, "POST", "/cp/v1/activate", null, "", "activate-1").status());
            var version = admin.handle(viewer, "GET", "/cp/v1/config/1", null, "");
            assertEquals(200, version.status(), version.body());
            assertTrue(version.body().contains("\"version\":1"), version.body());
            assertTrue(version.body().contains("root:"));
            assertEquals(404, admin.handle(viewer, "GET", "/cp/v1/config/9", null, "").status());
            assertEquals(404, admin.handle(viewer, "GET", "/cp/v1/config/" + "9".repeat(40), null, "").status());
            assertEquals(200, admin.handle(operator, "POST", "/cp/v1/rollout", null,
                    "{\"selectors\":[\"edge\",\"all\"]}", "roll-1").status());
            var rollout = admin.handle(viewer, "GET", "/cp/v1/rollout", null, "");
            assertEquals(200, rollout.status(), rollout.body());
            assertTrue(rollout.body().contains("\"stage\":0"), rollout.body());
            assertTrue(rollout.body().contains("edge"), rollout.body());
            store.reportNode("edge-1", Map.of("zone", "a"), "SUSPECT", 0);
            store.reportNode("edge-1", Map.of("zone", "a\"b"), "nope", 0);
            store.reportNode("cp-1", Map.of(), "DEAD", 0);
            store.reportNode(" ", Map.of(), "READY", 1);
            var nodes = admin.handle(viewer, "GET", "/cp/v1/nodes", null, "");
            assertEquals(200, nodes.status(), nodes.body());
            assertTrue(nodes.body().contains("\"health\":\"SUSPECT\""), nodes.body());
            assertTrue(nodes.body().contains("\"appliedVersion\":0"), nodes.body());
            assertTrue(nodes.body().contains("\"lag\":1"), nodes.body());
            assertTrue(nodes.body().contains("a\\\"b"), nodes.body());
            assertTrue(nodes.body().contains("\"role\":\"control\""), nodes.body());
            var audit = admin.handle(viewer, "GET", "/cp/v1/audit", "limit=1", "");
            assertEquals(200, audit.status(), audit.body());
            assertTrue(audit.body().contains("\"seq\":"), audit.body());
            assertFalse(audit.body().contains("\"seq\":1,"), audit.body());
            var none = admin.handle(viewer, "GET", "/cp/v1/audit", "after=100000&limit=10", "");
            assertEquals("{\"entries\":[]}", none.body());
        }
    }

    @Test
    void idempotencyKeySurvivesReplayAndRejectsADifferentBody() throws Exception {
        var root = Files.createDirectory(dir.resolve("html"));
        var yaml = "server:\n  port: 0\n  root: " + root.toAbsolutePath() + "\n";
        var other = yaml + "\n";
        var keys = KeyRing.generate("online-1", NOW + 86_400_000L, 0, Long.MAX_VALUE / 2);
        var clock = new FakeClock(0);
        var state = dir.resolve("cp");
        try (var store = CpStore.open(state, keys, clock)) {
            elect(store, clock);
            var admin = new CpAdmin(store, NOW, TTL);
            var cert = new Rbac.ClientCert("urn:sjws:admin:root");
            assertEquals(400, admin.handle(cert, "POST", "/cp/v1/config", null, yaml, "bad\nkey").status());
            var first = admin.handle(cert, "POST", "/cp/v1/config", null, yaml, "k1");
            assertEquals(200, first.status(), first.body());
            var again = admin.handle(cert, "POST", "/cp/v1/config", null, yaml, "k1");
            assertEquals(first.body(), again.body());
            assertEquals(409, admin.handle(cert, "POST", "/cp/v1/config", null, other, "k1").status());
            assertEquals(200, admin.handle(cert, "POST", "/cp/v1/activate", null, "", "act").status());
            assertEquals(1, store.history().size());
            var second = admin.handle(cert, "POST", "/cp/v1/activate", null, "", "act");
            assertEquals(200, second.status(), second.body());
            assertEquals(1, store.history().size());
            assertEquals(200, admin.handle(cert, "POST", "/cp/v1/rollout", null, "{\"selectors\":[\"edge\"]}", "r1")
                    .status());
            assertEquals(200, admin.handle(cert, "POST", "/cp/v1/rollout", null, "{\"selectors\":[\"edge\"]}", "r1")
                    .status());
            assertEquals(409, admin.handle(cert, "POST", "/cp/v1/rollout", null, "{\"selectors\":[\"other\"]}", "r1")
                    .status());
            assertEquals(1, store.selectors().size());
            assertEquals(200, admin.handle(cert, "POST", "/cp/v1/rollout/advance", null, "", "adv").status());
            assertEquals(1, store.stage());
            assertEquals(200, admin.handle(cert, "POST", "/cp/v1/rollout/advance", null, "", "adv").status());
            assertEquals(1, store.stage());
            assertEquals(200, admin.handle(cert, "POST", "/cp/v1/nodes/n9/revoke", null, "", "rev").status());
            assertEquals(200, admin.handle(cert, "POST", "/cp/v1/nodes/n9/revoke", null, "", "rev").status());
            assertEquals(1, store.revoked().size());
            assertEquals(200, admin.handle(cert, "POST", "/cp/v1/rbac", null,
                    "{\"san\":\"urn:sjws:admin:viewer\",\"role\":\"viewer\"}", "grant").status());
            assertEquals(Role.VIEWER, store.roleOfSan("urn:sjws:admin:viewer"));
            assertEquals(200, admin.handle(cert, "POST", "/cp/v1/config", null, yaml, "k2").status());
            assertEquals(200, admin.handle(cert, "POST", "/cp/v1/activate", null, "", "act2").status());
            var rolled = admin.handle(cert, "POST", "/cp/v1/rollback", null, "{\"toVersion\":1}", "back");
            assertEquals(200, rolled.status(), rolled.body());
            assertEquals(rolled.body(), admin.handle(cert, "POST", "/cp/v1/rollback", null, "{\"toVersion\":1}", "back")
                    .body());
            assertEquals(3, store.history().size());
        }
        var clock2 = new FakeClock(0);
        try (var store = CpStore.open(state, keys, clock2)) {
            elect(store, clock2);
            var admin = new CpAdmin(store, NOW, TTL);
            var cert = new Rbac.ClientCert("urn:sjws:admin:root");
            var replay = admin.handle(cert, "POST", "/cp/v1/config", null, yaml, "k1");
            assertEquals(200, replay.status(), replay.body());
            assertTrue(replay.body().contains("\"staged\":true"), replay.body());
            assertEquals(3, store.history().size());
        }
    }

    private static void elect(CpStore store, FakeClock clock) {
        clock.advance(Duration.ofMillis(100));
        store.drive();
        assertTrue(store.leadership().leaderReady());
    }
}
