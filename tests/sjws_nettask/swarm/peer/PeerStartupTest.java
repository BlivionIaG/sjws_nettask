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
package sjws_nettask.swarm.peer;

import static org.junit.jupiter.api.Assertions.*;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** #236 T10 (half-configured startup, each case separately), the peer map syntax and the key checks. */
class PeerStartupTest {

    static final String KEY = "swarm-peer-key-for-tests-0123456789";
    static final String HA_KEY = "ha-cluster-key-for-tests-0123456789";

    @TempDir
    Path dir;

    final Map<String, String> props = new HashMap<>();
    final Map<String, String> env = new HashMap<>();

    PeerStartup.Checked check(String self) {
        return PeerStartup.check(props::get, env, self);
    }

    String refused(String self) {
        return assertThrows(IllegalArgumentException.class, () -> check(self)).getMessage();
    }

    static String map(String... nodes) throws Exception {
        var sb = new StringBuilder();
        int wire = 1;
        for (var n : nodes) {
            int port;
            try (var s = new ServerSocket(0)) {
                port = s.getLocalPort();
            }
            sb.append(sb.isEmpty() ? "" : ",").append(n).append('=').append(wire++).append("@127.0.0.1:").append(port);
        }
        return sb.toString();
    }

    void tls(PeerTestCa ca, String node) throws Exception {
        var m = ca.node(node);
        props.put("sjws.swarm.peer.tls.cert", m.certPem().toString());
        props.put("sjws.swarm.peer.tls.key", m.keyPem().toString());
        props.put("sjws.swarm.peer.tls.ca", m.caPem().toString());
    }

    @Test
    void nothingSetChecksNothing() {
        assertNull(check("a"));
    }

    @Test
    void aKeyWithNoPeerMapIsRefused() {
        env.put(PeerKeys.ENV, KEY);
        assertTrue(refused("a").contains("sjws.swarm.peers is not"));
        env.clear();
        props.put(PeerKeys.FILE_PROPERTY, "/nonexistent");
        assertTrue(refused("a").contains("sjws.swarm.peers is not"));
    }

    @Test
    void aSingleNodeMapNeedsNothing() throws Exception {
        props.put(PeerConfig.PROPERTY, map("a"));
        var c = check("a");
        assertFalse(c.config().multiNode());
        assertNull(c.key());
        assertTrue(c.describe().contains("no peer transport"));
        assertThrows(IllegalStateException.class, () -> PeerStartup.open(c, PeerLink.Owner.NONE, PeerLink.Limits.DEFAULT));
    }

    @Test
    void peersWithNoKeyAreRefused() throws Exception {
        props.put(PeerConfig.PROPERTY, map("a", "b"));
        var why = refused("a");
        assertTrue(why.contains("no peer key"), why);
        env.put(PeerKeys.ENV, " ");
        assertTrue(refused("a").contains("is empty"));
    }

    @Test
    void theHaOrCpKeyReusedIsRefusedAndNeverPrinted() throws Exception {
        props.put(PeerConfig.PROPERTY, map("a", "b"));
        env.put(PeerKeys.ENV, KEY);
        for (var other : PeerKeys.OTHER_KEYS) {
            env.put(other, KEY);
            var why = refused("a");
            assertTrue(why.contains("must differ from " + other), why);
            assertFalse(why.contains(KEY));
            env.remove(other);
        }
        env.put(PeerKeys.ENV_PREVIOUS, HA_KEY);
        env.put("WEB_SERVER_HA_CLUSTER_KEY", HA_KEY);
        assertTrue(refused("a").contains("must differ"), "the previous key is checked too");
        env.put("WEB_SERVER_HA_CLUSTER_KEY", "short");
        assertTrue(refused("a").contains("mTLS is required"), "an unusable HA key cannot equal ours");
    }

    @Test
    void peersWithAKeyButNoTlsAreRefusedEvenOnLoopback() throws Exception {
        props.put(PeerConfig.PROPERTY, map("a", "b"));
        env.put(PeerKeys.ENV, KEY);
        assertTrue(refused("a").contains("mTLS is required"));
        props.put("sjws.swarm.peer.tls.cert", "/x");
        props.put("sjws.swarm.peer.tls.key", "/y");
        assertTrue(refused("a").contains("mTLS is required"), "incomplete TLS material");
        props.put("sjws.swarm.peer.tls.ca", dir.resolve("missing.pem").toString());
        assertTrue(refused("a").contains("TLS material refused"));
    }

    @Test
    void aCertificateWithoutThisNodesPinnedSanIsRefused() throws Exception {
        var ca = new PeerTestCa(dir.resolve("ca"));
        props.put(PeerConfig.PROPERTY, map("a", "b"));
        env.put(PeerKeys.ENV, KEY);
        tls(ca, "b");
        var why = refused("a");
        assertTrue(why.contains("pinned SAN urn:sjws:node:a"), why);
    }

    @Test
    void aCompleteConfigurationPasses() throws Exception {
        var ca = new PeerTestCa(dir.resolve("ca"));
        props.put(PeerConfig.PROPERTY, map("a", "b"));
        env.put(PeerKeys.ENV, KEY);
        env.put(PeerKeys.ENV_PREVIOUS, "base64:" + java.util.Base64.getEncoder().encodeToString(HA_KEY.getBytes()));
        tls(ca, "a");
        var c = check("a");
        assertTrue(c.config().multiNode());
        assertTrue(c.key().hasPrevious());
        assertNotNull(c.tls());
        assertTrue(c.describe().contains("2 nodes checked (mTLS 1.3"), c.describe());
        assertTrue(c.describe().contains("+ previous"));
    }

    @Test
    void theKeyMayComeFromAnOwnerOnlyFileButNotFromBoth() throws Exception {
        var ca = new PeerTestCa(dir.resolve("ca"));
        var file = dir.resolve("peer.key");
        Files.writeString(file, KEY + "\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        props.put(PeerConfig.PROPERTY, map("a", "b"));
        props.put(PeerKeys.FILE_PROPERTY, file.toString());
        tls(ca, "a");
        assertFalse(check("a").key().hasPrevious());
        env.put(PeerKeys.ENV, KEY);
        assertTrue(refused("a").contains("not both"));
        env.clear();
        Files.writeString(file, "short\n");
        assertTrue(refused("a").contains("refused"));
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
        assertThrows(RuntimeException.class, () -> check("a"), "a world-readable key file is refused");
        env.put(PeerKeys.ENV, "short");
        props.remove(PeerKeys.FILE_PROPERTY);
        assertTrue(refused("a").contains(PeerKeys.ENV + " refused"));
    }

    @Test
    void theMapSyntaxIsStrict() {
        var ok = PeerConfig.parse(" a=1@127.0.0.1:7001 , b=2@[::1]:7002#spiffe://x/b ", "b");
        assertEquals("b", ok.self().name());
        assertEquals("urn:sjws:node:a", ok.byName("a").san());
        assertEquals("spiffe://x/b", ok.byWire(2).san());
        assertEquals(ok.byName("a"), ok.bySan("urn:sjws:node:a"));
        assertNull(ok.byWire(9));
        assertNull(ok.bySan("nope"));
        assertNull(ok.byName("nope"));
        assertEquals(2, ok.members().size());
        for (var bad : new String[] {"", " ", "a", "a=1", "a=1@127.0.0.1", "=1@127.0.0.1:1", "a b=1@127.0.0.1:1", "a=x@127.0.0.1:1", "a=1@127.0.0.1:x", "a=0@127.0.0.1:1",
            "a=70000@127.0.0.1:1", "a=1@127.0.0.1:0", "a=1@127.0.0.1:1#", "a=1@127.0.0.1:1,a=2@127.0.0.1:2", "a=1@127.0.0.1:1,b=1@127.0.0.1:2", "a=1@127.0.0.1:1,b=2@127.0.0.1:1",
            "a=1@127.0.0.1:1#s,b=2@127.0.0.1:2#s"}) {
            assertThrows(IllegalArgumentException.class, () -> PeerConfig.parse(bad, "a"), bad);
        }
        var why = assertThrows(IllegalArgumentException.class, () -> PeerConfig.parse("a=1@127.0.0.1:1", "z")).getMessage();
        assertTrue(why.contains("does not list this node"));
        assertThrows(IllegalArgumentException.class, () -> PeerConfig.parse(null, "a"));
    }

    @Test
    void theEnvironmentIsReadWhenNoPropertyIsSet() throws Exception {
        env.put(PeerConfig.ENV, map("a"));
        assertNotNull(check("a"));
        env.put("SJWS_SWARM_PEER_TLS_CERT", "/x");
        assertEquals("/x", PeerConfig.setting(props::get, env, "sjws.swarm.peer.tls.cert", "SJWS_SWARM_PEER_TLS_CERT"));
        props.put("sjws.swarm.peer.tls.cert", " ");
        assertEquals("/x", PeerConfig.setting(props::get, env, "sjws.swarm.peer.tls.cert", "SJWS_SWARM_PEER_TLS_CERT"));
    }
}
