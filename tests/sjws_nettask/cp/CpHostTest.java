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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.cluster.SimTransport;
import sjws_nettask.service.CpReloadable;

/** CONTROL-role hosting (control-plane-hosting.md §1, §6). */
class CpHostTest {

    @TempDir
    Path dir;

    private final PrintStream err = new PrintStream(new ByteArrayOutputStream());

    private Map<String, Object> yaml(String self, int full, int witness) {
        var y = new HashMap<String, Object>();
        y.put("nodeId", self);
        y.put("stateDir", dir.resolve(self).toString());
        y.put("keyDir", dir.resolve("keys").toString());
        y.put("rootPub", dir.resolve("root.pub").toString());
        y.put("watch", Map.of("listen", "127.0.0.1:0"));
        y.put("admin", Map.of("listen", "127.0.0.1:0"));
        if (full + witness > 0) {
            var peers = new ArrayList<Object>();
            for (int i = 1; i <= full + witness; i++) {
                peers.add(Map.of("id", "cp-" + i, "address", "127.0.0.1:" + (21100 + i), "witness", i > full));
            }
            y.put("peers", peers);
        }
        return y;
    }

    private CpConfig config(Map<String, Object> y) {
        return CpConfig.parse(k -> null, Map.of(), y, null);
    }

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail("timed out: " + what);
            }
            Thread.sleep(10);
        }
    }

    private static CpHost leader(List<CpReloadable> hosts) {
        for (var h : hosts) {
            if (h instanceof CpHost host && host.store().leadership().isLeader()) {
                return host;
            }
        }
        return null;
    }

    @Test
    void threeVotersStartElectAndShutDownInOrder() throws Exception {
        var deps = new CpTestDeps();
        var hosts = new ArrayList<CpReloadable>();
        try {
            for (int i = 1; i <= 3; i++) {
                hosts.add(CpLaunch.start(config(yaml("cp-" + i, 3, 0)), deps, err));
            }
            await(() -> leader(hosts) != null && leader(hosts).ready(), "leader ready");
            var leader = leader(hosts);
            assertEquals(1, hosts.stream().filter(h -> ((CpHost) h).ready()).count(), "only the leader is ready");
            assertEquals(List.of("keys", "store", "watch", "admin", "ready"), leader.events());
            assertEquals(3, deps.keyLoads.get());
        } finally {
            for (var h : hosts) {
                h.close();
            }
        }
        var events = ((CpHost) hosts.get(0)).events();
        assertEquals(List.of("keys", "store", "watch", "admin", "ready", "close-admin", "close-watch", "close-store"),
                events);
    }

    @Test
    void twoVotersAndWitnessElectADataLeader() throws Exception {
        var deps = new CpTestDeps();
        var hosts = new ArrayList<CpReloadable>();
        try {
            for (int i = 1; i <= 3; i++) {
                hosts.add(CpLaunch.start(config(yaml("cp-" + i, 2, 1)), deps, err));
            }
            assertInstanceOf(CpWitnessHost.class, hosts.get(2));
            assertEquals(2, deps.keyLoads.get(), "the witness never loads a key ring");
            await(() -> leader(hosts) != null && leader(hosts).ready(), "leader ready");
            var witness = ((CpWitnessHost) hosts.get(2)).witness();
            await(() -> {
                try {
                    return witness.ackedTerm() > 0;
                } catch (IOException e) {
                    return false;
                }
            }, "witness acks");
            assertFalse(witness.isLeader());
            assertNull(hosts.get(2).reload(), "nothing to reload on a witness");
            var leader = leader(hosts);
            var proposed = leader.store().propose("schema: 1\nserver:\n  port: 0\n");
            assertEquals(200, proposed.status(), proposed.body());
        } finally {
            for (var h : hosts) {
                h.close();
            }
        }
    }

    @Test
    void failingTimingCheckRefusesBeforeAnyKeyIsLoaded() {
        var deps = new CpTestDeps();
        var y = yaml("cp-1", 0, 0);
        y.put("leaseSafetyMargin", "20ms"); // 80 * 1.01 + 20 = 100.8 >= 100
        var e = assertThrows(IOException.class, () -> CpLaunch.start(config(y), deps, err));
        assertTrue(e.getMessage().contains("refusing to start"), e.getMessage());
        assertEquals(0, deps.keyLoads.get());
    }

    @Test
    void failureMidStartClosesInReverse() throws Exception {
        var deps = new CpTestDeps();
        var y = yaml("cp-1", 0, 0);
        y.put("admin", Map.of("listen", "10.255.255.1:7444")); // plain listener off loopback: refused
        var e = assertThrows(IOException.class, () -> CpLaunch.start(config(y), deps, err));
        assertTrue(e.getMessage().contains("must be loopback"), e.getMessage());
        // The watch listener and the store were closed: the state dir is free and a fresh start succeeds.
        try (var host = CpLaunch.start(config(yaml("cp-1", 0, 0)), deps, err)) {
            assertNull(host.reload());
        }
    }

    @Test
    void reloadLoadsKeysThenTlsAndSwapsOnlyIfBothSucceed() throws Exception {
        var deps = new CpTestDeps();
        try (var host = (CpHost) CpLaunch.start(config(yaml("cp-1", 0, 0)), deps, err)) {
            var tlsCalls = new java.util.concurrent.atomic.AtomicInteger();
            var tlsFails = new java.util.concurrent.atomic.AtomicBoolean();
            host.tlsReloads.add(() -> {
                tlsCalls.incrementAndGet();
                if (tlsFails.get()) {
                    throw new CpKeyException("test: bad cert");
                }
            });
            var keys = host.store().keys();
            deps.failKeys = true;
            assertNotNull(host.reload());
            assertSame(keys, host.store().keys());
            assertEquals(0, tlsCalls.get(), "TLS is not touched when KeyRing.load fails");
            deps.failKeys = false;
            tlsFails.set(true);
            assertTrue(host.reload().contains("TLS"));
            assertSame(keys, host.store().keys(), "ring not swapped when a listener reload fails");
            tlsFails.set(false);
            assertNull(host.reload());
            assertNotSame(keys, host.store().keys());
            assertEquals(2, tlsCalls.get());
        }
    }

    @Test
    void insecureRingWarnsRepeatedlyAndFlagsHealth() throws Exception {
        var deps = new CpTestDeps();
        deps.insecure = true;
        var out = new ByteArrayOutputStream();
        var print = new PrintStream(out, true);
        try (var host = (CpHost) CpHost.start(config(yaml("cp-1", 0, 0)), deps, print,
                java.time.Duration.ofMillis(20))) {
            await(() -> out.toString().split(java.util.regex.Pattern.quote(CpHost.INSECURE_WARNING), -1).length > 3,
                    "repeated insecure WARN");
            assertTrue(host.insecurePermissions());
            assertTrue(host.store().healthJson().contains("\"insecurePermissions\":true"), host.store().healthJson());
        }
        try (var host = (CpHost) CpLaunch.start(config(yaml("cp-1", 0, 0)), new CpTestDeps(), err)) {
            assertTrue(host.store().healthJson().contains("\"insecurePermissions\":false"));
        }
    }

    @Test
    void productionStartFailsClosedWithoutKeyFiles() {
        var y = yaml("cp-1", 0, 0);
        var e = assertThrows(IOException.class, () -> CpLaunch.launch(y, null, err));
        assertTrue(e.getMessage().contains("key ring not loaded"), e.getMessage());
        assertThrows(IOException.class, () -> CpLaunch.launch(Map.of("nodeId", "x"), null, err));
    }

    @Test
    void insecureOverrideWarnsAtStartup() throws Exception {
        var out = new ByteArrayOutputStream();
        var y = yaml("cp-1", 0, 0);
        y.put("insecureSkipPermissionChecks", true);
        try (var host = CpLaunch.start(config(y), new CpTestDeps(), new PrintStream(out, true))) {
            assertTrue(out.toString().contains("WARN: cp.insecureSkipPermissionChecks is ON"), out.toString());
        }
    }

    @Test
    void witnessHostRefusesWhenSelfIsNotTheWitness() {
        var c = config(yaml("cp-1", 2, 1));
        assertThrows(IllegalArgumentException.class, () -> CpWitness.open(dir.resolve("w"), CpLaunch.nodeId(c.self()),
                CpLaunch.voters(c), sjws_nettask.cluster.Clock.SYSTEM, new SimTransport()));
    }
}
