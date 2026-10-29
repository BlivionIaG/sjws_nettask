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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Standalone must not load the control plane or the Raft package. */
class ClassLoadTest {

    @TempDir
    Path dir;

    @Test
    void standaloneDoesNotLoadControlPlaneOrCluster() throws Exception {
        var root = Files.createDirectory(dir.resolve("html"));
        var log = dir.resolve("classes.log");
        var javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var process = new ProcessBuilder(javaBin, "-Xlog:class+load=info", "-cp", System.getProperty("java.class.path"),
                "sjws_nettask.ha.BenchServer", "--mode=single", "--port=0", "--root=" + root).redirectError(log.toFile())
                .redirectOutput(log.toFile()).start();
        try {
            var deadline = System.nanoTime() + java.time.Duration.ofSeconds(20).toNanos();
            var text = "";
            while (System.nanoTime() < deadline) {
                if (Files.exists(log)) {
                    text = Files.readString(log);
                    if (text.contains("bench port=")) {
                        break;
                    }
                }
                Thread.sleep(50);
            }
            assertTrue(text.contains("bench port="), text);
            // #175 voter certificate binding: the pinned-identity sockets and the ORIGIN path stay unloaded.
            for (var name : new String[] {"sjws_nettask.cp.CpPeerSockets ", "sjws_nettask.cp.CpConfig$Peer ",
                    "sjws_nettask.cluster.TcpTransport$OriginMismatch "}) {
                assertFalse(text.contains(name), name.strip() + " loaded by a standalone start");
            }
            assertFalse(text.contains("sjws_nettask.cp."), text);
            // S1: gateway secret-ref helper stays unloaded until middleware or CP config touches it.
            assertFalse(text.contains("sjws_nettask.config.MiddlewareSecrets "),
                    "MiddlewareSecrets loaded by a standalone start");
            for (var cp : new String[] {"KeyRing", "KeyRingLoader", "TrustBundle", "TrustState", "CpKeyConfig",
                    "CpListenerConfig", "CpServerTls", "CpTls"}) {
                assertFalse(text.contains("sjws_nettask.cp." + cp + " "), cp);
            }
            assertFalse(text.contains("sjws_nettask.cluster."), text);
            assertFalse(text.contains("sjws_nettask.cluster.TcpTransport"), text);
            assertFalse(text.contains("sjws_nettask.service."), text);
        } finally {
            process.destroy();
            process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            process.destroyForcibly();
        }
    }

    /** control-plane-hosting.md §5, §8.7: a witness host loads no key ring, store or listener. */
    @Test
    void witnessHostLoadsNoKeyRingStoreOrListeners() throws Exception {
        var javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var log = dir.resolve("witness-classes.log");
        var process = new ProcessBuilder(javaBin, "-Xlog:class+load=info", "-cp", System.getProperty("java.class.path"),
                "sjws_nettask.cp.CpWitnessLoadMain").redirectErrorStream(true).redirectOutput(log.toFile()).start();
        String text;
        try {
            boolean done = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            text = Files.readString(log);
            assertTrue(done, "witness subprocess hung: " + text.lines().filter(l -> !l.contains("class,load"))
                    .reduce("", (a, b) -> a + "\n" + b));
        } finally {
            process.destroyForcibly();
        }
        assertTrue(text.contains("witness up"), text);
        assertTrue(text.contains("sjws_nettask.cp.CpWitness "), "sanity: the witness itself is loaded");
        for (var name : java.util.List.of("KeyRing", "CpStore", "WatchServer", "AdminServer", "CpHost ")) {
            assertFalse(text.contains("sjws_nettask.cp." + name), name + " loaded by a witness host");
        }
    }

    /**
     * A real data-role process (bootstrap {@code role=data}): CONTROL hosting stays unloaded, so the CP-off cost
     * is zero there too. Only the data side of {@code cp} (watch client, lifecycle) may load.
     */
    @Test
    void dataRoleStartLoadsNoControlHostingOrVoterTransport() throws Exception {
        var root = Files.createDirectories(dir.resolve("data-html"));
        var boot = sjws_nettask.clusterharness.NodeFixtures.pending(dir, "data").bootstrap();
        var log = dir.resolve("data-classes.log");
        int port;
        try (var probe = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            port = probe.getLocalPort();
        }
        var javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var pb = new ProcessBuilder(javaBin, "-Xlog:class+load=info", "-cp", System.getProperty("java.class.path"),
                "sjws_nettask.Sjws_nettask", "--role=data", "--bootstrap=" + boot,
                "--pid-file=" + dir.resolve("data.pid"), "--state-dir=" + dir.resolve("data-state"),
                Integer.toString(port), root.toString()).redirectErrorStream(true).redirectOutput(log.toFile());
        for (var env : new String[] {"WEB_SERVER_DIR", "WEB_SERVER_PORT", "WEB_SERVER_ROLE", "SJWS_CP_NODE_ID",
                "SJWS_SWARM_ENABLED"}) {
            pb.environment().remove(env);
        }
        var process = pb.start();
        String text = "";
        try {
            var deadline = System.nanoTime() + java.time.Duration.ofSeconds(30).toNanos();
            while (System.nanoTime() < deadline) {
                text = Files.exists(log) ? Files.readString(log) : "";
                if (text.contains("Server started:") || !process.isAlive()) {
                    break;
                }
                Thread.sleep(50);
            }
            Thread.sleep(300); // let anything started right after the listener land in the log
            text = Files.readString(log);
        } finally {
            process.destroy();
            process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            process.destroyForcibly();
        }
        assertTrue(text.contains("Server started:"), text);
        assertTrue(text.contains("control-plane role=data/gateway"), text);
        for (var name : java.util.List.of("sjws_nettask.cp.CpLaunch ", "sjws_nettask.cp.CpHost ",
                "sjws_nettask.cp.CpHostDeps ", "sjws_nettask.cp.CpWitness", "sjws_nettask.cp.CpStore ",
                "sjws_nettask.cp.WatchServer ", "sjws_nettask.cp.AdminServer ", "sjws_nettask.cp.KeyRing ",
                "sjws_nettask.cp.KeyRingLoader ", "sjws_nettask.cp.CpServerTls ", "sjws_nettask.cp.CpPeerSockets ",
                "sjws_nettask.cp.CpConfig ", "sjws_nettask.cp.CpConfig$Peer ", "sjws_nettask.cluster.TcpTransport ",
                "sjws_nettask.cluster.TcpTransport$OriginMismatch ", "sjws_nettask.cluster.RaftGroup ",
                "sjws_nettask.cluster.ReplicatedNode ")) {
            assertFalse(text.contains(name), name.strip() + " loaded by a data-role start");
        }
    }

    /**
     * Gate G1 (inceptor): {@code ha.messaging: off} with a member list, and {@code auto} with no member list, both cost
     * nothing, as does {@code auto} with a member list but {@code ha.enabled: false}. A real server process with
     * messaging on takes a produce and a fetch, and loads no {@code msgrepl} class
     * and none of the Raft machinery ({@code ReplicatedNode}, {@code RaftGroup}, {@code TcpTransport}, the log storage).
     * With {@code ha.enabled: true} only the key type ({@code ClusterKey}) may load, as before this gate. #270: neither
     * do the snapshot pieces ({@code ReplicaHooks}, the msgrepl snapshot store and transfer), with an
     * {@code ha.snapshot} block configured.
     */
    @Test
    void haMessagingOffAndNoMemberListLoadNoReplication() throws Exception {
        var members = """
                  node_id: a
                  topics: [orders]
                  members:
                    - { id: a, address: "127.0.0.1:1", http_url: "http://127.0.0.1:2" }
                    - { id: b, address: "127.0.0.1:3", http_url: "http://127.0.0.1:4" }
                    - { id: w, address: "127.0.0.1:5", witness: true }
                """;
        var off = startAndProduce("off", "ha:\n  enabled: true\n  messaging: off\n"
                + "  snapshot: { every_entries: 100, log_tail_entries: 10 }\n" + members);
        assertNoReplication(off, true);
        var auto = startAndProduce("auto", "ha:\n  messaging: auto\n");
        assertNoReplication(auto, false);
        // auto with a member list but ha.enabled false: single node, with the warning, and no key resolved.
        var autoMembers = startAndProduce("auto-members", "ha:\n  enabled: false\n  messaging: auto\n" + members);
        assertNoReplication(autoMembers, false);
        assertTrue(autoMembers.contains("ha.enabled is false: ha.messaging: auto stays single-node"), autoMembers);
        var absent = startAndProduce("absent", "# no ha block\n");
        assertNoReplication(absent, false);
    }

    private static void assertNoReplication(String text, boolean keyMayLoad) {
        assertTrue(text.contains("sjws_nettask.messaging.http.MessagingModule "), "sanity: messaging is on");
        assertFalse(text.contains("sjws_nettask.msgrepl."), "a msgrepl class loaded");
        // #270: the snapshot hooks and the replicated-state interface stay unloaded too.
        assertFalse(text.contains("sjws_nettask.messaging.ledger.ReplicaHooks"), "ReplicaHooks loaded with HA off");
        assertFalse(text.contains("ha messaging: replication on"), text);
        text.lines().filter(l -> l.contains("sjws_nettask.cluster.")).forEach(l -> {
            assertTrue(keyMayLoad && l.contains("sjws_nettask.cluster.ClusterKey"), "cluster class loaded: " + l);
        });
    }

    /** Starts the server with messaging on and {@code yaml}, produces and fetches one record; returns the log. */
    private String startAndProduce(String name, String yaml) throws Exception {
        var root = Files.createDirectories(dir.resolve(name + "-html"));
        var file = dir.resolve(name + ".yaml");
        Files.writeString(file, yaml);
        var log = dir.resolve(name + "-classes.log");
        int port;
        try (var probe = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            port = probe.getLocalPort();
        }
        var javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var pb = new ProcessBuilder(javaBin, "-Xlog:class+load=info", "-Dsjws.messaging.enabled=true",
                "-Dsjws.messaging.dir=" + dir.resolve(name + "-msg"), "-cp", System.getProperty("java.class.path"),
                "sjws_nettask.Sjws_nettask", "--config=" + file, "--state-dir=" + dir.resolve(name + "-state"),
                Integer.toString(port), root.toString()).redirectErrorStream(true).redirectOutput(log.toFile());
        for (var env : new String[] {"WEB_SERVER_DIR", "WEB_SERVER_PORT", "WEB_SERVER_CONFIG", "SJWS_SWARM_ENABLED"}) {
            pb.environment().remove(env);
        }
        pb.environment().put("WEB_SERVER_HA_CLUSTER_KEY", "0123456789abcdef0123456789abcdef");
        var process = pb.start();
        String text = "";
        try {
            var deadline = System.nanoTime() + java.time.Duration.ofSeconds(30).toNanos();
            while (System.nanoTime() < deadline) {
                text = Files.exists(log) ? Files.readString(log) : "";
                if (text.contains("Server started:") || !process.isAlive()) {
                    break;
                }
                Thread.sleep(50);
            }
            assertTrue(text.contains("Server started:"), text);
            var client = java.net.http.HttpClient.newHttpClient();
            var url = java.net.URI.create("http://127.0.0.1:" + port + "/messaging/v1/topics/orders/records");
            var produce = client.send(java.net.http.HttpRequest.newBuilder(url)
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(new byte[] {0, 0, 0, 2, 'h', 'i'}))
                    .build(), java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(200, produce.statusCode(), produce.body());
            var fetch = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(url + "?offset=0")).build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(200, fetch.statusCode());
            Thread.sleep(300); // anything loaded right after the requests lands in the log
            text = Files.readString(log);
        } finally {
            process.destroy();
            process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            process.destroyForcibly();
        }
        return text;
    }
}
