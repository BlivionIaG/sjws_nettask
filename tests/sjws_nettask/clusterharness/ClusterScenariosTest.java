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
package sjws_nettask.clusterharness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.clusterharness.ClusterHarness.DataRole;

/**
 * Multi-node cluster harness on top of the file-serving end-to-end test: real listeners,
 * ephemeral ports, a temporary document root, {@code java.net.http.HttpClient}.
 *
 * <p>The data plane with the control plane off (standalone and gateway-off) constructs no
 * control-plane object. Partition, leader kill, slow node, and rollback run against
 * {@link CpStoreControlPlane}: 3 or 5 real {@code cp-config} voters on the manual
 * {@link ClusterClock}, and data-mode servers joined through the real watch.
 */
@Tag("cluster")
@Timeout(20)
class ClusterScenariosTest {

    private static final String BODY = "hello from the temp docroot\n";

    @TempDir
    Path root;

    /** A pushed document must name an existing root; data nodes reject it otherwise (fail closed). */
    private String yamlV1() {
        return "server:\n  port: 0\n  root: " + root.toAbsolutePath() + "\n";
    }

    private String yamlV2() {
        return "server:\n  port: 0\n  root: " + root.toAbsolutePath() + "\n  max_connections: 2048\n";
    }

    @Test
    void dataNodesServeOnEphemeralPorts() throws Exception {
        Files.writeString(root.resolve("hello.txt"), BODY, StandardCharsets.UTF_8);
        try (var cluster = ClusterHarness.open(0, 3, root, DataRole.GATEWAY_OFF, ClusterClock.SYSTEM)) {
            assertEquals(DataRole.GATEWAY_OFF, cluster.role());
            assertEquals(3, cluster.dataNodes().size());
            var ports = new HashSet<Integer>();
            for (var node : cluster.dataNodes()) {
                assertTrue(node.httpPort() > 0, node.id());
                assertTrue(ports.add(node.httpPort()), "duplicate port " + node.httpPort());
                assertEquals(0L, node.version(), "standalone snapshot version");
                assertNull(node.contentHash());
                assertNull(node.server().ha(), "gateway-off attaches no HA runtime");
                var ok = cluster.get(node, "/hello.txt");
                assertEquals(200, ok.statusCode());
                assertEquals(BODY, ok.body());
                assertEquals("text/plain; charset=utf-8",
                        ok.headers().firstValue("content-type").orElseThrow());
            }
            assertEquals("canary", cluster.dataNodes().get(0).labels().get("track"));
            assertEquals("stable", cluster.dataNodes().get(1).labels().get("track"));
            assertEquals("gateway", cluster.dataNodes().get(2).labels().get("role"));
            var missing = cluster.get(cluster.dataNodes().get(0), "/no-such-file.txt");
            assertEquals(404, missing.statusCode());
            assertNoControlPlaneThreads();
        }
    }

    @Test
    void gatewayOffMatchesStandalone() throws Exception {
        Files.writeString(root.resolve("hello.txt"), BODY, StandardCharsets.UTF_8);
        try (var standalone = ClusterHarness.open(0, 1, root, DataRole.STANDALONE, ClusterClock.SYSTEM);
                var gatewayOff = ClusterHarness.open(0, 1, root, DataRole.GATEWAY_OFF, ClusterClock.SYSTEM)) {
            var left = standalone.get(standalone.dataNodes().get(0), "/hello.txt");
            var right = gatewayOff.get(gatewayOff.dataNodes().get(0), "/hello.txt");
            assertEquals(left.statusCode(), right.statusCode());
            assertEquals(left.body(), right.body());
            assertEquals(header(left, "content-type"), header(right, "content-type"));
            assertEquals(header(left, "content-length"), header(right, "content-length"));
            assertEquals(0L, standalone.dataNodes().get(0).version());
            assertEquals(standalone.dataNodes().get(0).version(), gatewayOff.dataNodes().get(0).version());
            assertNull(standalone.dataNodes().get(0).server().ha());
            assertNull(gatewayOff.dataNodes().get(0).server().ha());
            assertNotEquals(standalone.dataNodes().get(0).httpPort(), gatewayOff.dataNodes().get(0).httpPort());
            assertNoControlPlaneThreads();
        }
    }

    @Test
    void pendingControlPlaneStillRefuses() {
        var cp = new PendingControlPlane();
        assertFalse(cp.available());
        var thrown = assertThrows(ControlPlanePendingException.class, () -> cp.push("server:\n  port: 0\n"));
        assertTrue(thrown.getMessage().contains(ControlPlaneDesign.PR), thrown.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> ClusterHarness.open(0, 1, root, DataRole.DATA, ClusterClock.SYSTEM));
    }

    @Test
    void threeVotersElectOneReadyLeader() throws Exception {
        try (var cluster = ClusterHarness.open(3, 1, root, DataRole.GATEWAY_OFF, ClusterClock.Manual.startingAtZero())) {
            var cp = cluster.control();
            assertTrue(cp.available());
            assertEquals(3, cp.nodes().size());
            var leader = cp.leader().orElseThrow();
            assertTrue(leader.term() > 0L);
            int ready = 0;
            var ports = new HashSet<Integer>();
            for (var node : cp.nodes()) {
                ready += node.leaderReady() ? 1 : 0;
                assertTrue(ports.add(node.adminPort()), node.id());
            }
            assertEquals(1, ready);
        }
    }

    @Test
    void controlNodeCountIsThreeOrFive() {
        assertThrows(IllegalArgumentException.class,
                () -> ClusterHarness.open(1, 1, root, DataRole.GATEWAY_OFF, ClusterClock.SYSTEM));
        assertThrows(IllegalArgumentException.class,
                () -> ClusterHarness.open(2, 1, root, DataRole.GATEWAY_OFF, ClusterClock.SYSTEM));
        assertThrows(IllegalArgumentException.class,
                () -> ClusterHarness.open(4, 1, root, DataRole.GATEWAY_OFF, ClusterClock.SYSTEM));
        assertThrows(IllegalArgumentException.class,
                () -> ClusterHarness.open(0, 0, root, DataRole.GATEWAY_OFF, ClusterClock.SYSTEM));
    }

    @Test
    void manualClockAdvancesAndSystemClockDoesNot() {
        var clock = ClusterClock.Manual.startingAtZero();
        assertEquals(0L, clock.nanoTime());
        clock.advance(Duration.ofMillis(1500));
        assertEquals(Duration.ofMillis(1500).toNanos(), clock.nanoTime());
        assertThrows(UnsupportedOperationException.class, () -> ClusterClock.SYSTEM.advance(Duration.ofMillis(1)));
    }

    @Test
    void networkPartitionKeepsLastGoodConfig() throws Exception {
        Files.writeString(root.resolve("hello.txt"), BODY, StandardCharsets.UTF_8);
        var clock = ClusterClock.Manual.startingAtZero();
        try (var cluster = ClusterHarness.open(3, 2, root, DataRole.DATA, clock)) {
            var cp = cluster.control();
            assertQuietDuring(cluster, () -> {
                long version = cp.push(yamlV1());
                cp.awaitConverged(version, Duration.ofSeconds(2));
            });
            var first = cluster.dataNodes().get(0).applied();
            long installed = first.version();
            String hash = first.hash();
            assertTrue(installed > 0L);
            cp.partitionDataFromControl();
            clock.advance(Duration.ofSeconds(5));
            assertQuiet(cluster);
            for (var node : cluster.dataNodes()) {
                var applied = node.applied();
                assertEquals(installed, applied.version(), node.id());
                assertEquals(hash, applied.hash(), node.id());
                assertEquals(BODY, cluster.get(node, "/hello.txt").body());
            }
            cp.heal();
            assertQuietDuring(cluster, () -> {
                long next = cp.push(yamlV2());
                cp.awaitConverged(next, Duration.ofSeconds(2));
            });
            long converged = cluster.dataNodes().get(0).version();
            for (var node : cluster.dataNodes()) {
                assertEquals(converged, node.version(), node.id());
            }
        }
    }

    @Test
    void controlPlaneLeaderKillConvergesOnANewLeader() throws Exception {
        Files.writeString(root.resolve("hello.txt"), BODY, StandardCharsets.UTF_8);
        var clock = ClusterClock.Manual.startingAtZero();
        try (var cluster = ClusterHarness.open(5, 2, root, DataRole.DATA, clock)) {
            var cp = cluster.control();
            long version = cp.push(yamlV1());
            cp.awaitConverged(version, Duration.ofSeconds(2));
            var leader = cp.leader().orElseThrow();
            assertTrue(leader.leaderReady());
            assertTrue(leader.adminPort() > 0);
            int oldPort = leader.adminPort();
            cp.killLeader();
            clock.advance(Duration.ofMillis(1500));
            var next = cp.leader().orElseThrow();
            assertTrue(next.leaderReady());
            assertNotEquals(leader.id(), next.id());
            assertTrue(next.adminPort() > 0);
            assertNotEquals(oldPort, next.adminPort());
            assertQuietDuring(cluster, () -> {
                long pushed = cp.push(yamlV2());
                cp.awaitConverged(pushed, Duration.ofSeconds(2));
            });
            long converged = cluster.dataNodes().get(0).version();
            assertTrue(converged > version);
            for (var node : cluster.dataNodes()) {
                assertEquals(converged, node.version(), node.id());
                assertEquals(200, cluster.get(node, "/hello.txt").statusCode());
            }
        }
    }

    @Test
    void slowNodeStillConvergesWithoutRequestErrors() throws Exception {
        Files.writeString(root.resolve("hello.txt"), BODY, StandardCharsets.UTF_8);
        var clock = ClusterClock.Manual.startingAtZero();
        try (var cluster = ClusterHarness.open(3, 3, root, DataRole.DATA, clock)) {
            var cp = cluster.control();
            var slow = cluster.dataNodes().get(2);
            cp.delay(slow.id(), Duration.ofMillis(200));
            var pushed = new long[1];
            assertQuietDuring(cluster, () -> pushed[0] = cp.push(yamlV1()));
            assertEquals(0L, slow.version(), "the delayed node waits for the clock");
            clock.advance(Duration.ofMillis(200));
            cp.awaitConverged(pushed[0], Duration.ofSeconds(2));
            for (var node : cluster.dataNodes()) {
                assertEquals(pushed[0], node.version(), node.id());
                assertEquals(BODY, cluster.get(node, "/hello.txt").body());
            }
        }
    }

    @Test
    void configRollbackConvergesOnThePreviousHash() throws Exception {
        Files.writeString(root.resolve("hello.txt"), BODY, StandardCharsets.UTF_8);
        var clock = ClusterClock.Manual.startingAtZero();
        try (var cluster = ClusterHarness.open(3, 2, root, DataRole.DATA, clock)) {
            var cp = cluster.control();
            long v1 = cp.push(yamlV1());
            cp.awaitConverged(v1, Duration.ofSeconds(2));
            String hash = cp.contentHash(v1);
            long v2 = cp.push(yamlV2());
            cp.awaitConverged(v2, Duration.ofSeconds(2));
            assertTrue(v2 > v1);
            var rolled = new long[1];
            assertQuietDuring(cluster, () -> rolled[0] = cp.rollback(v1));
            assertTrue(rolled[0] > v2);
            assertEquals(hash, cp.contentHash(rolled[0]));
            cp.awaitConverged(rolled[0], Duration.ofSeconds(2));
            for (var node : cluster.dataNodes()) {
                var applied = node.applied();
                assertEquals(rolled[0], applied.version(), node.id());
                assertEquals(hash, applied.hash(), node.id());
                assertEquals(200, cluster.get(node, "/hello.txt").statusCode());
                assertEquals(BODY, cluster.get(node, "/hello.txt").body());
            }
        }
    }

    private static void assertQuiet(ClusterHarness cluster) throws Exception {
        for (var node : cluster.dataNodes()) {
            var response = cluster.get(node, "/hello.txt");
            assertEquals(200, response.statusCode(), node.id());
            assertEquals(BODY, response.body(), node.id());
        }
    }

    /** Samples every data node while {@code action} runs. Any non-200, body mismatch, or I/O failure fails. */
    private static void assertQuietDuring(ClusterHarness cluster, Action action) throws Exception {
        var failed = new AtomicInteger();
        var done = new AtomicBoolean();
        var sampler = Thread.startVirtualThread(() -> {
            while (!done.get()) {
                for (var node : cluster.dataNodes()) {
                    try {
                        var response = cluster.get(node, "/hello.txt");
                        if (response.statusCode() != 200 || !BODY.equals(response.body())) {
                            failed.incrementAndGet();
                        }
                    } catch (Exception e) {
                        failed.incrementAndGet();
                    }
                }
            }
        });
        try {
            action.run();
        } finally {
            done.set(true);
            sampler.join(2_000);
        }
        assertEquals(0, failed.get(), "content requests failed during the control-plane operation");
    }

    private static void assertNoControlPlaneThreads() {
        for (var thread : Thread.getAllStackTraces().keySet()) {
            var name = thread.getName();
            assertTrue(!name.startsWith("cp-"), name);
        }
    }

    private static String header(HttpResponse<String> response, String name) {
        return response.headers().firstValue(name).orElse("");
    }

    @FunctionalInterface
    private interface Action {
        void run() throws Exception;
    }
}
