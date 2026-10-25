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
package sjws_nettask;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.config.ConfigLoader;

/**
 * Gate G1: the production bootstrap path ({@link HaMessagingStart#install}, then {@link SjwsServer#start}) on a
 * 2-data-plus-witness config, in one JVM but over real TCP Raft links, file logs and HTTP listeners.
 */
class HaMessagingStartTest {

    static final Map<String, String> ENV = Map.of("WEB_SERVER_HA_CLUSTER_KEY", "0123456789abcdef0123456789abcdef");

    @TempDir
    Path dir;

    static int freePort() throws Exception {
        try (var s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private ConfigLoader.Loaded load(String name, String yaml, int httpPort) throws Exception {
        var file = dir.resolve(name + ".yaml");
        Files.writeString(file, yaml);
        var root = Files.createDirectories(dir.resolve(name + "-html"));
        var loaded = ConfigLoader.load(new String[] {"--config=" + file, "--state-dir=" + dir.resolve(name + "-state"),
                Integer.toString(httpPort), root.toString()}, ENV);
        assertTrue(loaded.ok(), loaded.errors().toString());
        return loaded;
    }

    static String cluster(String self, int[] raft, int[] http, String mode) {
        return """
                ha:
                  enabled: true
                  messaging: %s
                  node_id: %s
                  election_timeout: 500ms
                  heartbeat: 50ms
                  topics: [orders]
                  members:
                    - { id: a, address: "127.0.0.1:%d", http_url: "http://127.0.0.1:%d" }
                    - { id: b, address: "127.0.0.1:%d", http_url: "http://127.0.0.1:%d" }
                    - { id: w, address: "127.0.0.1:%d", witness: true }
                """.formatted(mode, self, raft[0], http[0], raft[1], http[1], raft[2]);
    }

    /** A messaging module built the way the server builds it, on its own directory. */
    private OptionalModule messaging(String name) {
        var old = System.getProperty("sjws.messaging.dir");
        System.setProperty("sjws.messaging.dir", dir.resolve(name + "-msg").toString());
        try {
            return OptionalModules.load(OptionalModules.MESSAGING_CLASS, System.err);
        } finally {
            if (old == null) {
                System.clearProperty("sjws.messaging.dir");
            } else {
                System.setProperty("sjws.messaging.dir", old);
            }
        }
    }

    static byte[] record(String text) {
        var b = text.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(4 + b.length).putInt(b.length).put(b).array();
    }

    @Test
    @Timeout(60)
    void threeVoterConfigStartsAndReplicates() throws Exception {
        int[] raft = {freePort(), freePort(), freePort()};
        int[] http = {freePort(), freePort(), freePort()};
        var names = new String[] {"a", "b", "w"};
        var modules = new ArrayList<OptionalModule>();
        var servers = new ArrayList<SjwsServer>();
        var err = new ByteArrayOutputStream();
        var errOut = new PrintStream(err, true, StandardCharsets.UTF_8);
        try {
            for (int i = 0; i < 3; i++) {
                var loaded = load(names[i], cluster(names[i], raft, http, i == 0 ? "on" : "auto"), http[i]);
                assertTrue(loaded.haMessaging().replicate());
                var base = i < 2 ? messaging(names[i]) : OptionalModules.NONE;
                var mods = HaMessagingStart.install(loaded, base, errOut);
                assertNotSame(base, mods, "replication wraps the modules");
                assertSame(base, HaMessagingStart.messaging(mods) == null ? OptionalModules.NONE
                        : HaMessagingStart.messaging(mods));
                modules.add(mods);
                servers.add(SjwsServer.start(loaded, OptionalModules.contributors(mods)));
            }
            var log = err.toString(StandardCharsets.UTF_8);
            assertTrue(log.contains("ha messaging: replication on, node=a, voters=3, topics=1"), log);
            assertTrue(log.contains("node=w (witness)"), log);
            assertFalse(log.contains("not encrypted"), "loopback members draw no plaintext warning: " + log);

            var client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
            int status = 0;
            String where = null;
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (System.nanoTime() < deadline) {
                for (int i = 0; i < 2 && status != 200; i++) {
                    var url = "http://127.0.0.1:" + http[i] + "/messaging/v1/topics/orders/records";
                    var r = client.send(HttpRequest.newBuilder(URI.create(url))
                            .POST(HttpRequest.BodyPublishers.ofByteArray(record("hello-" + i))).build(),
                            HttpResponse.BodyHandlers.ofString());
                    status = r.statusCode();
                    if (status == 307) {
                        assertTrue(r.headers().firstValue("location").orElse("").startsWith("http://127.0.0.1:"
                                + http[1 - i]), r.headers().map().toString());
                    }
                    if (status == 200) {
                        where = names[i];
                    }
                }
                if (status == 200) {
                    break;
                }
                Thread.sleep(50);
            }
            assertEquals(200, status, "a leader accepted the produce");
            // The follower serves the record once it is applied there: it came through Raft, not a local append.
            int follower = "a".equals(where) ? 1 : 0;
            var fetch = "http://127.0.0.1:" + http[follower] + "/messaging/v1/topics/orders/records?offset=0";
            String body = "";
            while (System.nanoTime() < deadline && !body.contains("hello-")) {
                var r = client.send(HttpRequest.newBuilder(URI.create(fetch)).GET().build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.ISO_8859_1));
                body = r.statusCode() == 200 ? r.body() : "";
                Thread.sleep(50);
            }
            assertTrue(body.contains("hello-"), "follower " + names[follower] + " has the replicated record");
            assertTrue(Files.isDirectory(dir.resolve("w-state/msgrepl/raft")), "witness keeps its vote state");
            assertTrue(Files.isDirectory(dir.resolve(names[follower] + "-state/msgrepl/apply")));
        } finally {
            for (var s : servers) {
                s.close();
            }
            for (var m : modules) {
                m.close();
            }
        }
    }

    @Test
    void offAndNoMemberListReturnTheModulesUntouched() throws Exception {
        int[] p = {freePort(), freePort(), freePort()};
        var off = load("off", cluster("a", p, p, "off"), freePort());
        assertSame(OptionalModules.NONE, HaMessagingStart.install(off, OptionalModules.NONE, System.err));
        var auto = load("auto", "ha:\n  enabled: true\n  messaging: auto\n", freePort());
        assertSame(OptionalModules.NONE, HaMessagingStart.install(auto, OptionalModules.NONE, System.err));
    }

    @Test
    void roleAndModulesMustAgree() throws Exception {
        int[] p = {freePort(), freePort(), freePort()};
        var data = load("data", cluster("a", p, p, "on"), freePort());
        var noMessaging = assertThrows(OptionalModule.Refused.class,
                () -> HaMessagingStart.install(data, OptionalModules.NONE, System.err));
        assertTrue(noMessaging.getMessage().contains("needs messaging enabled"), noMessaging.getMessage());
        var witness = load("wit", cluster("w", p, p, "on"), freePort());
        var mod = messaging("wit");
        try {
            var refused = assertThrows(OptionalModule.Refused.class,
                    () -> HaMessagingStart.install(witness, mod, System.err));
            assertTrue(refused.getMessage().contains("witness"), refused.getMessage());
            OptionalModule other = () -> { };
            assertSame(mod, HaMessagingStart.messaging(new OptionalModules.Both(mod, other)));
        } finally {
            mod.close();
        }
    }

    @Test
    void aBusyRaftPortIsAnIoError() throws Exception {
        try (var busy = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            int[] raft = {busy.getLocalPort(), freePort(), freePort()};
            var loaded = load("busy", cluster("w", new int[] {freePort(), freePort(), busy.getLocalPort()},
                    raft, "on"), freePort());
            assertThrows(java.io.IOException.class,
                    () -> HaMessagingStart.install(loaded, OptionalModules.NONE, System.err));
        }
    }

    @Test
    void messagingIsFoundInsideBoth() {
        OptionalModule other = () -> { };
        assertNull(HaMessagingStart.messaging(other));
        assertNull(HaMessagingStart.messaging(new OptionalModules.Both(other, other)));
    }

    @Test
    void haMembersCountAsMultiProcess() throws Exception {
        int[] p = {freePort(), freePort(), freePort()};
        var off = load("mp", cluster("a", p, p, "off"), freePort());
        assertEquals("the config file lists ha.members (3 voters)",
                Sjws_nettask.multiProcess(null, null, off.haMessaging()));
        assertNull(Sjws_nettask.multiProcess(null, null, sjws_nettask.config.HaMessagingSettings.NONE));
        assertNull(Sjws_nettask.multiProcess(null, Map.of()));
    }

    @Test
    void runRefusesOnWithoutMembersAndADataNodeWithoutMessaging() throws Exception {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        var file = dir.resolve("bad.yaml");
        Files.writeString(file, "ha:\n  enabled: true\n  messaging: on\n");
        var root = Files.createDirectories(dir.resolve("bad-html"));
        int code = Sjws_nettask.run(new String[] {"--config=" + file, "--state-dir=" + dir.resolve("bad-state"), "0",
                root.toString()}, ENV, new PrintStream(out), new PrintStream(err), null, s -> { });
        assertEquals(64, code, err.toString());
        assertTrue(err.toString().contains("ha.messaging: on needs ha.members"), err.toString());

        int[] p = {freePort(), freePort(), freePort()};
        Files.writeString(file, cluster("a", p, p, "on"));
        err.reset();
        code = Sjws_nettask.run(new String[] {"--config=" + file, "--state-dir=" + dir.resolve("bad-state"), "0",
                root.toString()}, ENV, new PrintStream(out), new PrintStream(err), null, s -> { });
        assertEquals(64, code, err.toString());
        assertTrue(err.toString().contains("needs messaging enabled on a data node"), err.toString());
    }

    @Test
    void runExitsWhenTheRaftPortIsBusy() throws Exception {
        try (var busy = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            int[] raft = {freePort(), freePort(), busy.getLocalPort()};
            var file = dir.resolve("busy-run.yaml");
            Files.writeString(file, cluster("w", raft, new int[] {freePort(), freePort()}, "on"));
            var root = Files.createDirectories(dir.resolve("busy-run-html"));
            var err = new ByteArrayOutputStream();
            int code = Sjws_nettask.run(new String[] {"--config=" + file, "--state-dir=" + dir.resolve("busy-run-state"),
                    "0", root.toString()}, ENV, new PrintStream(new ByteArrayOutputStream()), new PrintStream(err), null,
                    s -> { });
            assertEquals(Sjws_nettask.EXIT_CANNOT_LISTEN, code, err.toString());
            assertTrue(err.toString().contains("Failed to start HA messaging"), err.toString());
        }
    }

    @Test
    void aBootstrapFailureThatIsNotARefusalIsAnIllegalState() throws Exception {
        int[] raft = {freePort(), freePort(), freePort()};
        int[] http = {freePort(), freePort(), freePort()};
        var a = load("twice-a", cluster("a", raft, http, "on"), http[0]);
        var b = load("twice-b", cluster("b", raft, http, "on"), http[1]);
        var mod = messaging("twice");
        var first = HaMessagingStart.install(a, mod, System.err);
        try {
            // the module already has replication installed: MessagingModule.replication refuses a second one
            var e = assertThrows(IllegalStateException.class, () -> HaMessagingStart.install(b, mod, System.err));
            assertTrue(e.getMessage().contains("HA messaging bootstrap failed"), e.getMessage());
        } finally {
            first.close();
        }
    }
}
