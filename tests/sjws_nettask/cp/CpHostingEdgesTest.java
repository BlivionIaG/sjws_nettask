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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.cluster.Clock;
import sjws_nettask.cluster.SimTransport;

/** Edge behaviour of CONTROL hosting: listener config, production deps, peer parsing, witness accessors. */
class CpHostingEdgesTest {

    @TempDir
    Path dir;

    private final PrintStream err = new PrintStream(new ByteArrayOutputStream());

    private Map<String, Object> base() {
        var y = new HashMap<String, Object>();
        y.put("nodeId", "cp-1");
        y.put("stateDir", dir.resolve("s").toString());
        y.put("keyDir", dir.resolve("k").toString());
        y.put("rootPub", dir.resolve("root.pub").toString());
        y.put("watch", Map.of("listen", "127.0.0.1:0"));
        y.put("admin", Map.of("listen", "127.0.0.1:0"));
        return y;
    }

    private static CpConfig parse(Map<String, Object> y) {
        return CpConfig.parse(k -> null, Map.of(), y, null);
    }

    @Test
    void listenerConfigPicksTheCaPerListener() throws Exception {
        var tls = Map.of("cert", "c.pem", "key", "k.pem", "ca", "/abs/ca.pem", "adminCa", "admin-ca.pem",
                "revocations", "crl.pem");
        var base = Path.of("/etc/sjws");
        var watch = CpLaunch.listenerConfig(tls, base, "cp-watch", false);
        assertEquals("cp-watch", watch.name());
        assertEquals(Path.of("/etc/sjws/c.pem"), watch.certPem());
        assertEquals(Path.of("/abs/ca.pem"), watch.caPem());
        assertEquals(Path.of("/etc/sjws/crl.pem"), watch.revocations());
        assertFalse(watch.insecureSkipPermissionChecks());
        var admin = CpLaunch.listenerConfig(tls, null, "cp-admin", true);
        assertEquals(Path.of("admin-ca.pem"), admin.caPem());
        assertTrue(admin.insecureSkipPermissionChecks());
    }

    @Test
    void listenerConfigRefusesBadInput() {
        assertThrows(CpKeyException.class, () -> CpLaunch.listenerConfig(null, null, "cp-watch", false));
        assertThrows(CpKeyException.class, () -> CpLaunch.listenerConfig("x", null, "cp-watch", false));
        assertThrows(CpKeyException.class,
                () -> CpLaunch.listenerConfig(Map.of("bogus", "x"), null, "cp-watch", false));
        assertThrows(CpKeyException.class,
                () -> CpLaunch.listenerConfig(Map.of("cert", 3), null, "cp-watch", false));
        assertThrows(CpKeyException.class,
                () -> CpLaunch.listenerConfig(Map.of("cert", " "), null, "cp-watch", false));
        // Missing key or ca: CpListenerConfig refuses, reported as a key error.
        assertThrows(CpKeyException.class,
                () -> CpLaunch.listenerConfig(Map.of("cert", "c", "key", "k"), null, "cp-admin", false));
    }

    @Test
    void productionDepsFailClosed() throws Exception {
        var y = base();
        y.put("tls", Map.of("cert", "c"));
        y.put("insecureSkipPermissionChecks", true);
        var deps = CpLaunch.production(y, dir);
        assertSame(Clock.SYSTEM, deps.clock());
        // cp.peers without the CP-only Raft key refuses (the env var name is reported, never a value).
        var raft = CpLaunch.RAFT_KEY_ENV;
        var ha = sjws_nettask.cluster.ClusterKey.ENV_CURRENT;
        var noKey = assertThrows(IOException.class, () -> CpLaunch.peerKey(Map.of()));
        assertTrue(noKey.getMessage().contains(raft), noKey.getMessage());
        // The HA key alone is not enough: no fallback.
        assertThrows(IOException.class, () -> CpLaunch.peerKey(Map.of(ha, "0123456789abcdef0123")));
        assertThrows(IOException.class, () -> CpLaunch.peerKey(Map.of(raft, " ")));
        var shortKey = assertThrows(IOException.class, () -> CpLaunch.peerKey(Map.of(raft, "short")));
        assertFalse(shortKey.getMessage().contains("short"), shortKey.getMessage());
        assertThrows(IOException.class, () -> CpLaunch.peerKey(Map.of(raft, "0123456789abcdef0123",
                CpLaunch.RAFT_KEY_PREVIOUS_ENV, " ")));
        // Equal to the HA key (current or previous, any encoding) is refused without printing either value.
        var secret = "0123456789abcdef0123";
        for (var env : List.of(Map.of(raft, secret, ha, secret),
                Map.of(raft, "hex:" + java.util.HexFormat.of().formatHex(secret.getBytes()), ha, secret),
                Map.of(raft, "raft-key-differs-0123", sjws_nettask.cluster.ClusterKey.ENV_PREVIOUS, "raft-key-differs-0123"),
                Map.of(raft, "raft-key-differs-0123", CpLaunch.RAFT_KEY_PREVIOUS_ENV, secret, ha, secret))) {
            var same = assertThrows(IOException.class, () -> CpLaunch.peerKey(env), env.keySet().toString());
            assertTrue(same.getMessage().contains("must differ"), same.getMessage());
            assertFalse(same.getMessage().contains(secret) || same.getMessage().contains("raft-key-differs"),
                    same.getMessage());
        }
        assertNotNull(CpLaunch.peerKey(Map.of(raft, "raft-key-differs-0123", ha, secret)));
        assertNotNull(CpLaunch.peerKey(Map.of(raft, "raft-key-differs-0123", ha, "x"))); // unusable HA key
        assertNotNull(CpLaunch.peerKey(Map.of(raft, "raft-key-differs-0123", CpLaunch.RAFT_KEY_PREVIOUS_ENV,
                "raft-key-previous-0123")));
        if (System.getenv(CpLaunch.RAFT_KEY_ENV) == null) {
            var peered = base();
            peered.put("peers", java.util.List.of(Map.of("id", "cp-1", "address", "127.0.0.1:1"),
                    Map.of("id", "cp-2", "address", "127.0.0.1:2"), Map.of("id", "cp-3", "address", "127.0.0.1:3")));
            peered.put("nodeId", "cp-1");
            assertThrows(IOException.class, () -> deps.transport(parse(peered), null));
        }
        assertThrows(CpKeyException.class, () -> deps.serverTls("cp-watch"));
        assertThrows(CpKeyException.class, () -> deps.loadKeys(parse(base()).keys()));
        var none = CpLaunch.production(null, null);
        assertThrows(CpKeyException.class, () -> none.serverTls("cp-admin"));
    }

    @Test
    void launchReportsConfigErrorsAsIo() {
        var e = assertThrows(IOException.class, () -> CpLaunch.launch(Map.of(), null, err));
        assertTrue(e.getMessage().startsWith("control role config:"), e.getMessage());
        var y = base();
        var k = assertThrows(IOException.class, () -> CpLaunch.launch(y, dir, err));
        assertTrue(k.getMessage().contains("key ring not loaded"), k.getMessage());
    }

    @Test
    void serverTlsRefusalAbortsStartAndClosesTheStore() throws Exception {
        var deps = new CpTestDeps() {
            @Override
            public CpServerTls serverTls(String listener) throws CpKeyException {
                throw new CpKeyException("test: no cert");
            }
        };
        var e = assertThrows(IOException.class, () -> CpLaunch.start(parse(base()), deps, err));
        assertTrue(e.getMessage().contains("cp-watch TLS refused"), e.getMessage());
        try (var host = (CpHost) CpLaunch.start(parse(base()), new CpTestDeps(), err)) {
            assertFalse(host.insecurePermissions());
            assertFalse(host.events().isEmpty());
        }
    }

    @Test
    void reloadAfterCloseIsRefusedAndCloseIsIdempotent() throws Exception {
        var host = (CpHost) CpLaunch.start(parse(base()), new CpTestDeps(), err);
        host.close();
        host.close();
        assertFalse(host.ready());
        assertEquals("control role not running", host.reload());
        assertTrue(host.events().contains("close-store"));
    }

    @Test
    void peerParsingErrors() {
        var cases = List.<Object>of("notalist", List.of("notamap"), List.of(Map.of("id", "cp-1", "address", "a:1",
                "bogus", 1)), List.of(Map.of("id", "cp-1", "address", "a:1", "witness", "yes")),
                List.of(Map.of("id", "cp-1", "address", "a:1", "wire", 0)),
                List.of(Map.of("id", "cp-1", "address", "a:1", "wire", 70_000)),
                List.of(Map.of("id", "cp-1", "address", "a:1", "wire", "1")),
                List.of(Map.of("id", 5, "address", "a:1")), List.of(Map.of("address", "a:1")),
                List.of(Map.of("id", "cp-1", "address", "a:b:c")));
        for (var peers : cases) {
            var y = base();
            y.put("peers", peers);
            assertThrows(IllegalArgumentException.class, () -> parse(y), String.valueOf(peers));
        }
        assertThrows(IllegalArgumentException.class, () -> new CpConfig.Peer(0, "x",
                java.net.InetSocketAddress.createUnresolved("a", 1), false));
    }

    @Test
    void nestedAndScalarErrors() {
        var notMap = base();
        notMap.put("watch", "127.0.0.1:1");
        assertThrows(IllegalArgumentException.class, () -> parse(notMap));
        var unknown = base();
        unknown.put("admin", Map.of("listen", "127.0.0.1:1", "port", 1));
        assertThrows(IllegalArgumentException.class, () -> parse(unknown));
        var number = base();
        number.put("nodeId", 7);
        assertThrows(IllegalArgumentException.class, () -> parse(number));
        var ratio = base();
        ratio.put("maxClockDriftRatio", "0.01");
        assertThrows(IllegalArgumentException.class, () -> parse(ratio));
        var margin = base();
        margin.put("leaseSafetyMargin", "soon");
        assertThrows(IllegalArgumentException.class, () -> parse(margin));
        for (var bad : List.of("host", "[::1", "h:1/p", "u@h:1", "h:1?q", "h:1#f")) {
            assertThrows(IllegalArgumentException.class, () -> CpConfig.address(bad, "k"), bad);
        }
        assertEquals("::1", CpConfig.address("[::1]:5", "k").getHostString());
        assertNull(parse(base()).self());
        assertFalse(parse(base()).selfIsWitness());
        assertThrows(IllegalArgumentException.class, () -> CpConfig.fromSystem(Map.of(), null));
    }

    @Test
    void witnessAccessorsAndDrive() throws Exception {
        var y = base();
        y.put("nodeId", "cp-3");
        y.put("peers", List.of(Map.of("id", "cp-1", "address", "127.0.0.1:1"),
                Map.of("id", "cp-2", "address", "127.0.0.1:2"),
                Map.of("id", "cp-3", "address", "127.0.0.1:3", "witness", true)));
        var c = parse(y);
        var w = CpWitness.open(dir.resolve("w"), CpLaunch.nodeId(c.self()), CpLaunch.voters(c), Clock.SYSTEM,
                new SimTransport());
        try {
            w.drive();
            assertFalse(w.isLeader());
            assertEquals(0L, w.term());
            assertEquals(0L, w.ackedIndex());
            assertEquals(0L, w.ackedTerm());
        } finally {
            w.close();
        }
        try (var host = CpLaunch.start(c, new CpTestDeps(), err)) {
            Thread.sleep(Duration.ofMillis(120));
            assertFalse(((CpWitnessHost) host).witness().isLeader(), "alone, a witness never campaigns or leads");
        }
    }
}
