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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CpConfigTest {

    private static Map<String, Object> base() {
        var y = new HashMap<String, Object>();
        y.put("nodeId", "cp-1");
        y.put("stateDir", "state");
        y.put("keyDir", "/etc/sjws/cp/keys");
        y.put("rootPub", "/etc/sjws/cp/root.pub");
        y.put("watch", Map.of("listen", "127.0.0.1:7443"));
        y.put("admin", Map.of("listen", "[::1]:7444"));
        return y;
    }

    private static List<Object> peers(int full, int witness) {
        var out = new java.util.ArrayList<Object>();
        for (int i = 1; i <= full + witness; i++) {
            out.add(Map.of("id", "cp-" + i, "address", "10.0.0." + i + ":7000", "witness", i > full));
        }
        return out;
    }

    private static CpConfig parse(Map<String, Object> y) {
        return CpConfig.parse(k -> null, Map.of(), y, Path.of("/srv/sjws"));
    }

    @Test
    void defaultsAndRelativePaths() {
        var c = parse(base());
        assertEquals("cp-1", c.nodeId());
        assertEquals(Path.of("/srv/sjws/state"), c.keys().stateDir());
        assertEquals(Path.of("/etc/sjws/cp/root.pub"), c.keys().rootPub());
        assertEquals(7443, c.watchListen().getPort());
        assertEquals("::1", c.adminListen().getHostString());
        assertEquals(0.01, c.maxClockDriftRatio());
        assertEquals(Duration.ofMillis(10), c.leaseSafetyMargin());
        assertFalse(c.keys().insecureSkipPermissionChecks());
        assertEquals(0L, c.keys().minBundleSerial());
        assertNull(c.keys().rootPubSha256());
        assertTrue(c.peers().isEmpty());
        assertTrue(c.adminAllowedOrigins().isEmpty());
        assertTrue(c.warnings().get(0).contains("single-voter"));
    }

    @Test
    void adminAllowedOrigins() {
        var y = base();
        y.put("admin", Map.of("listen", "127.0.0.1:1", "allowedOrigins",
                List.of("https://console.example", "https://ops.example")));
        var c = parse(y);
        assertEquals(List.of("https://console.example", "https://ops.example"), c.adminAllowedOrigins());
        var bad = base();
        bad.put("admin", Map.of("listen", "127.0.0.1:1", "allowedOrigins", "https://x"));
        assertThrows(IllegalArgumentException.class, () -> parse(bad));
    }

    @Test
    void propertyBeatsEnvBeatsYaml() {
        var props = Map.of("sjws.cp.nodeId", "cp-p");
        var env = Map.of("SJWS_CP_NODE_ID", "cp-e", "SJWS_CP_WATCH_LISTEN", "127.0.0.2:1");
        var c = CpConfig.parse(props::get, env, base(), null);
        assertEquals("cp-p", c.nodeId());
        assertEquals("127.0.0.2", c.watchListen().getHostString());
        assertEquals("cp-e", CpConfig.parse(k -> null, env, base(), null).nodeId());
    }

    @Test
    void insecureOnlyFromFileAndWarns() {
        var y = base();
        y.put("insecureSkipPermissionChecks", true);
        var c = parse(y);
        assertTrue(c.keys().insecureSkipPermissionChecks());
        assertTrue(c.warnings().stream().anyMatch(w -> w.contains("insecureSkipPermissionChecks is ON")));
        assertThrows(IllegalArgumentException.class, () -> CpConfig.parse(k -> null,
                Map.of("SJWS_CP_INSECURE_SKIP_PERMISSION_CHECKS", "false"), base(), null));
        assertThrows(IllegalArgumentException.class, () -> CpConfig.parse(
                k -> k.equals("sjws.cp.insecureSkipPermissionChecks") ? "true" : null, Map.of(), base(), null));
        y.put("insecureSkipPermissionChecks", "true");
        assertThrows(IllegalArgumentException.class, () -> parse(y));
    }

    @Test
    void keyFields() {
        var y = base();
        y.put("rootPubSha256", "ab".repeat(32));
        y.put("minBundleSerial", 42);
        y.put("maxClockDriftRatio", 0.02);
        y.put("leaseSafetyMargin", "5ms");
        var c = parse(y);
        assertEquals("ab".repeat(32), c.keys().rootPubSha256());
        assertEquals(42L, c.keys().minBundleSerial());
        assertEquals(0.02, c.maxClockDriftRatio());
        assertEquals(Duration.ofMillis(5), c.leaseSafetyMargin());
        y.remove("rootPubSha256");
        y.put("minBundleSerial", -1);
        assertThrows(IllegalArgumentException.class, () -> parse(y));
        y.put("minBundleSerial", 1);
        y.put("leaseSafetyMargin", 10);
        assertThrows(IllegalArgumentException.class, () -> parse(y));
        y.put("leaseSafetyMargin", "10ms");
        y.put("maxClockDriftRatio", 0.9);
        assertThrows(IllegalArgumentException.class, () -> parse(y));
    }

    @Test
    void requiredAndUnknownKeys() {
        for (var key : List.of("nodeId", "stateDir", "keyDir", "rootPub", "watch", "admin")) {
            var y = base();
            y.remove(key);
            var e = assertThrows(IllegalArgumentException.class, () -> parse(y), key);
            assertTrue(e.getMessage().startsWith("cp."), e.getMessage());
        }
        var y = base();
        y.put("bogus", 1);
        assertThrows(IllegalArgumentException.class, () -> parse(y));
        var z = base();
        z.put("watch", Map.of("listen", "nohost"));
        assertThrows(IllegalArgumentException.class, () -> parse(z));
    }

    @Test
    void voterCounts() {
        for (int[] ok : new int[][] {{3, 0}, {5, 0}, {2, 1}}) {
            var y = base();
            y.put("peers", peers(ok[0], ok[1]));
            var c = parse(y);
            assertEquals(ok[0] + ok[1], c.peers().size());
            assertEquals(1, c.self().wire());
        }
        for (int[] bad : new int[][] {{2, 0}, {1, 0}, {4, 0}, {3, 1}, {1, 1}, {2, 2}}) {
            var y = base();
            y.put("peers", peers(bad[0], bad[1]));
            assertThrows(IllegalArgumentException.class, () -> parse(y), bad[0] + "+" + bad[1]);
        }
    }

    @Test
    void witnessSelfAndPeerErrors() {
        var y = base();
        y.put("nodeId", "cp-3");
        y.put("peers", peers(2, 1));
        assertTrue(parse(y).selfIsWitness());
        y.put("nodeId", "cp-9");
        assertThrows(IllegalArgumentException.class, () -> parse(y));
        var dup = base();
        dup.put("peers", List.of(Map.of("id", "cp-1", "address", "a:1", "wire", 7),
                Map.of("id", "cp-2", "address", "b:1", "wire", 7), Map.of("id", "cp-3", "address", "c:1")));
        assertThrows(IllegalArgumentException.class, () -> parse(dup));
        var sparse = base();
        sparse.put("peers", List.of(Map.of("id", "cp-1", "address", "a:1", "wire", 7),
                Map.of("id", "cp-2", "address", "b:1", "wire", 42), Map.of("id", "cp-3", "address", "c:1")));
        assertEquals(7, parse(sparse).self().wire());
    }
}
