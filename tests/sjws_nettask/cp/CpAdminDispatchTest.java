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

import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.cluster.FakeClock;
import sjws_nettask.controlplane.Permission;
import sjws_nettask.controlplane.Rbac;
import sjws_nettask.controlplane.Role;

/**
 * Explicit route dispatch: grant is only reachable from {@code POST /cp/v1/rbac}. An authorized route
 * without a handler must not fall through to grant.
 */
class CpAdminDispatchTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final long TTL = 60_000L;

    @TempDir
    Path dir;

    @AfterEach
    void clearHook() {
        CpAdmin.testPermission = null;
    }

    @Test
    void authorizedRouteWithoutHandlerDoesNotGrant() throws Exception {
        var keys = KeyRing.generate("online-1", NOW + 86_400_000L, 0, Long.MAX_VALUE / 2);
        var clock = new FakeClock(0);
        try (var store = CpStore.open(dir.resolve("cp"), keys, clock)) {
            clock.advance(Duration.ofMillis(100));
            store.drive();
            assertTrue(store.leadership().leaderReady());
            var admin = new CpAdmin(store, NOW, TTL);
            var cert = new Rbac.ClientCert("urn:sjws:admin:root");
            // Test-only: authorize a path that has no handler (production hook is null).
            CpAdmin.testPermission = (method, route) -> "POST".equals(method) && "/cp/v1/test-unmatched".equals(route)
                    ? Permission.MANAGE_OPERATORS
                    : null;
            var body = "{\"san\":\"urn:sjws:admin:should-not-grant\",\"role\":\"viewer\"}";
            var outcome = admin.handle(cert, "POST", "/cp/v1/test-unmatched", null, body);
            assertEquals(404, outcome.status(), outcome.body());
            assertNull(store.roleOfSan("urn:sjws:admin:should-not-grant"));
            // The real rbac route still grants.
            assertEquals(200, admin.handle(cert, "POST", "/cp/v1/rbac", null, body).status());
            assertEquals(Role.VIEWER, store.roleOfSan("urn:sjws:admin:should-not-grant"));
        }
    }

    @Test
    void knownRouteWrongMethodIs405() throws Exception {
        var keys = KeyRing.generate("online-1", NOW + 86_400_000L, 0, Long.MAX_VALUE / 2);
        var clock = new FakeClock(0);
        try (var store = CpStore.open(dir.resolve("cp"), keys, clock)) {
            clock.advance(Duration.ofMillis(100));
            store.drive();
            assertTrue(store.leadership().leaderReady());
            var admin = new CpAdmin(store, NOW, TTL);
            var cert = new Rbac.ClientCert("urn:sjws:admin:root");
            assertEquals(405, admin.handle(cert, "DELETE", "/cp/v1/health", null, "").status());
            assertEquals(405, admin.handle(cert, "PUT", "/cp/v1/rbac", null, "{}").status());
            assertEquals(404, admin.handle(cert, "GET", "/cp/v1/no-such", null, "").status());
        }
    }
}
