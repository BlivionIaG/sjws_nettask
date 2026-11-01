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
import sjws_nettask.clusterharness.EphemeralPorts;
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
        return EphemeralPorts.probe();
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
        Exception last = null;
        for (int attempt = 1; attempt <= EphemeralPorts.BIND_ATTEMPTS; attempt++) {
            try {
                threeVoterConfigStartsAndReplicatesOnce(attempt);
                return;
            } catch (Exception e) {
                last = e;
                if (!EphemeralPorts.addressInUse(e) || attempt == EphemeralPorts.BIND_ATTEMPTS) {
                    throw e;
                }
                EphemeralPorts.logRetry("HaMessagingStartTest.threeVoter", attempt, e);
            }
        }
        throw last;
    }

    private void threeVoterConfigStartsAndReplicatesOnce(int attempt) throws Exception {
        int[] raft = {freePort(), freePort(), freePort()};
        int[] http = {freePort(), freePort(), freePort()};
        var names = new String[] {"a", "b", "w"};
        // Attempt-suffixed names so a failed bind does not leave raft/ledger state for the next try.
        var tag = attempt == 1 ? "" : ("-" + attempt);
        var modules = new ArrayList<OptionalModule>();
        var servers = new ArrayList<SjwsServer>();
        var err = new ByteArrayOutputStream();
        var errOut = new PrintStream(err, true, StandardCharsets.UTF_8);
        try {
            for (int i = 0; i < 3; i++) {
                var loaded = load(names[i] + tag, cluster(names[i], raft, http, i == 0 ? "on" : "auto"), http[i]);
                assertTrue(loaded.haMessaging().replicate());
                var base = i < 2 ? messaging(names[i] + tag) : OptionalModules.NONE;
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
            assertTrue(Files.isDirectory(dir.resolve("w" + tag + "-state/msgrepl/raft")), "witness keeps its vote state");
            assertTrue(Files.isDirectory(dir.resolve(names[follower] + tag + "-state/msgrepl/apply")));
        } finally {
            for (var s : servers) {
                s.close();
            }
            for (var m : modules) {
                m.close();
            }
        }
    }

    /**
     * #274 MC-B1 on the production path: node a took 3 records on {@code orders} with HA off; restarting it with
     * replication on is refused (OptionalModule.Refused, exit 64) instead of forking the topic's offsets.
     *
     * <p>#297 covered {@link #threeVoterConfigStartsAndReplicates} with bind retries; this refusal path still
     * used TOCTOU {@link EphemeralPorts#probe()} and wrapped {@code HaMessagingStart.install} in
     * {@code assertThrows(Refused)}. {@code assertThrows} turns a stolen Raft port's {@code BindException} into
     * {@code AssertionFailedError} (an Error), which {@link EphemeralPorts#withBindRetry} never catches — so the
     * flake still failed on the first attempt. Catch {@link OptionalModule.Refused} yourself and let bind failures
     * propagate; {@link #refusalRetriesWhenARaftPortIsOccupied} proves the retry with a held port.
     */
    @Test
    @Timeout(60)
    void replicationOnAnExistingLocalTopicIsRefused() throws Exception {
        EphemeralPorts.withBindRetry("HaMessagingStartTest.refusal", () -> {
            replicationOnAnExistingLocalTopicIsRefusedOnce(-1);
            return null;
        });
    }

    /**
     * Occupies {@code a}'s Raft port on the first attempt so install throws {@link java.net.BindException}; the
     * retry must still reach Refused. Mutation: restoring {@code assertThrows(Refused)} around install makes this
     * fail (AssertionFailedError escapes withBindRetry).
     */
    @Test
    @Timeout(60)
    void refusalRetriesWhenARaftPortIsOccupied() throws Exception {
        try (var hog = EphemeralPorts.reserveSocket()) {
            var attempts = new java.util.concurrent.atomic.AtomicInteger();
            EphemeralPorts.withBindRetry("HaMessagingStartTest.refusalOccupied", () -> {
                int n = attempts.incrementAndGet();
                replicationOnAnExistingLocalTopicIsRefusedOnce(n == 1 ? hog.getLocalPort() : -1);
                return null;
            });
            assertTrue(attempts.get() >= 2, "occupied raft port must force at least one retry, got " + attempts.get());
        }
    }

    /**
     * @param forceRaft0 when {@code >= 0}, use that port as node {@code a}'s Raft address (for the occupied-port
     *                   retry proof); otherwise probe a free port
     */
    private void replicationOnAnExistingLocalTopicIsRefusedOnce(int forceRaft0) throws Exception {
        // Fresh name each attempt so a prior bind failure does not leave topic state for the next try.
        var name = "pre-" + Long.toHexString(System.nanoTime());
        int httpPort = freePort();
        var plain = load(name, "# HA off\n", httpPort);
        var mod = messaging(name);
        try (var server = SjwsServer.start(plain, OptionalModules.contributors(mod))) {
            var client = HttpClient.newHttpClient();
            for (int i = 0; i < 3; i++) {
                var r = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + httpPort
                        + "/messaging/v1/topics/orders/records")).POST(HttpRequest.BodyPublishers.ofByteArray(
                                record("pre-" + i))).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, r.statusCode(), r.body());
            }
        } finally {
            mod.close();
        }
        int[] raft = {forceRaft0 >= 0 ? forceRaft0 : freePort(), freePort(), freePort()};
        int[] http = {httpPort, freePort(), freePort()};
        var on = load(name, cluster("a", raft, http, "on"), httpPort);
        var again = messaging(name);
        try {
            // Do not use assertThrows(Refused): a BindException becomes AssertionFailedError (Error), which
            // withBindRetry's catch (Exception) never sees. Catch Refused; let bind failures propagate.
            try {
                HaMessagingStart.install(on, again, System.err);
                fail("expected Refused");
            } catch (OptionalModule.Refused refused) {
                assertTrue(refused.getMessage().contains("replicated topic orders already holds 3 local record(s)"),
                        refused.getMessage());
            }
        } finally {
            again.close();
        }
        // The Raft port was released with the refusal (SO_REUSEADDR: the closed listener can sit in TIME_WAIT).
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (true) {
            try (var s = new ServerSocket()) {
                s.setReuseAddress(true);
                s.bind(new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), raft[0]), 1);
                assertEquals(raft[0], s.getLocalPort());
                break;
            } catch (java.net.BindException e) {
                if (System.nanoTime() >= deadline) {
                    throw e;
                }
                Thread.sleep(50);
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

    /** #274 MC N5: end to end, a member list plus the swarm stream flag exits 64 with the guard's words. */
    @Test
    void runRefusesTheSwarmStreamFlagWithAMemberList() throws Exception {
        int[] p = {freePort(), freePort(), freePort()};
        var file = dir.resolve("stream.yaml");
        Files.writeString(file, "swarm:\n  enabled: true\n" + cluster("a", p, p, "off"));
        var root = Files.createDirectories(dir.resolve("stream-html"));
        var err = new ByteArrayOutputStream();
        var old = System.getProperty("sjws.swarm.stream.enabled");
        System.setProperty("sjws.swarm.stream.enabled", "true");
        int code;
        try {
            code = Sjws_nettask.run(new String[] {"--config=" + file, "--state-dir=" + dir.resolve("stream-state"), "0",
                    root.toString()}, ENV, new PrintStream(new ByteArrayOutputStream()), new PrintStream(err), null,
                    s -> { });
        } finally {
            if (old == null) {
                System.clearProperty("sjws.swarm.stream.enabled");
            } else {
                System.setProperty("sjws.swarm.stream.enabled", old);
            }
        }
        assertEquals(64, code, err.toString());
        assertTrue(err.toString().contains("needs a single-process deployment"), err.toString());
        assertTrue(err.toString().contains("the config file lists ha.members (3 voters)"), err.toString());
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

    /**
     * Same BindException / assertThrows trap as {@link #replicationOnAnExistingLocalTopicIsRefused}: a stolen Raft
     * port on the first or second {@code install} used to surface as {@code BindException} (or
     * {@code AssertionFailedError} from {@code assertThrows(IllegalStateException)}) with no retry. Wrap in
     * {@link EphemeralPorts#withBindRetry}; catch {@link IllegalStateException} yourself so bind failures propagate.
     */
    @Test
    @Timeout(60)
    void aBootstrapFailureThatIsNotARefusalIsAnIllegalState() throws Exception {
        EphemeralPorts.withBindRetry("HaMessagingStartTest.bootstrapFailure", () -> {
            aBootstrapFailureThatIsNotARefusalIsAnIllegalStateOnce(-1);
            return null;
        });
    }

    /**
     * Occupies node {@code b}'s Raft port on the first attempt so the second install throws {@link java.net.BindException}
     * inside what used to be {@code assertThrows(IllegalStateException)}; the retry must still reach IllegalStateException.
     * Mutation: restoring {@code assertThrows(IllegalStateException)} around the second install makes this fail
     * ({@code AssertionFailedError} escapes {@code withBindRetry}).
     */
    @Test
    @Timeout(60)
    void bootstrapFailureRetriesWhenBRaftPortIsOccupied() throws Exception {
        try (var hog = EphemeralPorts.reserveSocket()) {
            var attempts = new java.util.concurrent.atomic.AtomicInteger();
            EphemeralPorts.withBindRetry("HaMessagingStartTest.bootstrapOccupied", () -> {
                int n = attempts.incrementAndGet();
                aBootstrapFailureThatIsNotARefusalIsAnIllegalStateOnce(n == 1 ? hog.getLocalPort() : -1);
                return null;
            });
            assertTrue(attempts.get() >= 2, "occupied b raft port must force at least one retry, got " + attempts.get());
        }
    }

    /**
     * @param forceRaftB when {@code >= 0}, use that port as node {@code b}'s Raft address (occupied-port retry proof);
     *                   otherwise probe a free port
     */
    private void aBootstrapFailureThatIsNotARefusalIsAnIllegalStateOnce(int forceRaftB) throws Exception {
        // Fresh names each attempt so a prior bind failure does not leave raft/module state for the next try.
        var tag = Long.toHexString(System.nanoTime());
        final int[] raft;
        final int[] http;
        if (forceRaftB >= 0) {
            int[] ports = EphemeralPorts.probeDistinct(5);
            raft = new int[] {ports[0], forceRaftB, ports[1]};
            http = new int[] {ports[2], ports[3], ports[4]};
        } else {
            int[] ports = EphemeralPorts.probeDistinct(6);
            raft = new int[] {ports[0], ports[1], ports[2]};
            http = new int[] {ports[3], ports[4], ports[5]};
        }
        var a = load("twice-a-" + tag, cluster("a", raft, http, "on"), http[0]);
        var b = load("twice-b-" + tag, cluster("b", raft, http, "on"), http[1]);
        var mod = messaging("twice-" + tag);
        var first = HaMessagingStart.install(a, mod, System.err);
        try {
            // Do not use assertThrows(IllegalStateException): a BindException becomes AssertionFailedError (Error),
            // which withBindRetry's catch (Exception) never sees. Catch IllegalStateException; let bind failures propagate.
            // the module already has replication installed: MessagingModule.replication refuses a second one
            try {
                HaMessagingStart.install(b, mod, System.err);
                fail("expected IllegalStateException");
            } catch (IllegalStateException e) {
                assertTrue(e.getMessage().contains("HA messaging bootstrap failed"), e.getMessage());
            }
        } finally {
            first.close();
        }
    }
}
