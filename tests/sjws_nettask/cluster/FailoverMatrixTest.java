package sjws_nettask.cluster;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Majority of two data nodes and a witness. One failure leaves a quorum. Two nodes are never
 * both inside a live lease, and a STRICT commit is still on a surviving data disk.
 */
@Timeout(30)
class FailoverMatrixTest {

    private static final SiteId SITE = new SiteId("local");
    private static final GroupId GROUP = new GroupId("ha-control");

    @Test
    void loseADataNode() throws Exception {
        for (var mode : Durability.values()) {
            var cluster = cluster(mode);
            var before = commit(cluster, "before-" + mode);
            cut(cluster, 0, 1);
            cut(cluster, 2, 1);
            settle(cluster);
            assertAtMostOneLiveLeader(cluster);
            var again = cluster.nodes.get(0).append(GROUP, List.of(payload("again-" + mode)));
            drive(cluster);
            if (mode == Durability.RELAXED) {
                assertEquals(1, liveCount(cluster), mode.toString());
                assertTrue(succeeded(again), mode.toString());
            } else {
                assertAtMostOneLiveLeader(cluster);
                assertFalse(succeeded(again), mode.toString());
            }
            assertStored(cluster, 0, before, "before-" + mode);
            assertFalse(live(cluster, 1), mode.toString());
        }
    }

    @Test
    void loseTheWitness() throws Exception {
        for (var mode : Durability.values()) {
            var cluster = cluster(mode);
            var before = commit(cluster, "before-" + mode);
            cut(cluster, 0, 2);
            cut(cluster, 1, 2);
            settle(cluster);
            assertEquals(1, liveCount(cluster), mode.toString());
            var again = liveLeader(cluster).append(GROUP, List.of(payload("again-" + mode)));
            drive(cluster);
            assertTrue(succeeded(again), mode.toString());
            assertStored(cluster, 0, before, "before-" + mode);
            assertStored(cluster, 1, before, "before-" + mode);
            assertFalse(live(cluster, 2), mode.toString());
        }
    }

    @Test
    void partitionBetweenTheDataNodes() throws Exception {
        for (var mode : Durability.values()) {
            var cluster = cluster(mode);
            var before = commit(cluster, "before-" + mode);
            cut(cluster, 0, 1);
            settle(cluster);
            assertAtMostOneLiveLeader(cluster);
            if (mode == Durability.RELAXED) {
                assertEquals(1, liveCount(cluster), mode.toString());
                var again = liveLeader(cluster).append(GROUP, List.of(payload("side-" + mode)));
                drive(cluster);
                assertTrue(succeeded(again), mode.toString());
            } else {
                assertAtMostOneLiveLeader(cluster);
                var again = cluster.nodes.get(0).append(GROUP, List.of(payload("side-" + mode)));
                drive(cluster);
                assertFalse(succeeded(again), mode.toString());
            }
            assertStored(cluster, 0, before, "before-" + mode);
            assertStored(cluster, 1, before, "before-" + mode);
            assertNoDivergentPayload(cluster);
        }
    }

    @Test
    void isolatedWitness() throws Exception {
        for (var mode : Durability.values()) {
            var cluster = cluster(mode);
            var before = commit(cluster, "before-" + mode);
            cut(cluster, 0, 2);
            cut(cluster, 1, 2);
            settle(cluster);
            assertEquals(1, liveCount(cluster), mode.toString());
            var again = liveLeader(cluster).append(GROUP, List.of(payload("again-" + mode)));
            drive(cluster);
            assertTrue(succeeded(again), mode.toString());
            assertStored(cluster, 0, before, "before-" + mode);
            assertStored(cluster, 1, before, "before-" + mode);
            assertFalse(live(cluster, 2), mode.toString());
        }
    }

    @Test
    void leaderIsolatedWithTheWitness() throws Exception {
        for (var mode : Durability.values()) {
            var cluster = cluster(mode);
            var before = commit(cluster, "before-" + mode);
            cut(cluster, 0, 1);
            cut(cluster, 2, 1);
            settle(cluster);
            assertAtMostOneLiveLeader(cluster);
            var again = cluster.nodes.get(0).append(GROUP, List.of(payload("with-witness-" + mode)));
            drive(cluster);
            if (mode == Durability.RELAXED) {
                assertTrue(live(cluster, 0), mode.toString());
                assertTrue(succeeded(again), mode.toString());
            } else {
                assertAtMostOneLiveLeader(cluster);
                assertFalse(succeeded(again), mode.toString());
                assertStored(cluster, 1, before, "before-" + mode);
            }
            assertStored(cluster, 0, before, "before-" + mode);
            assertFalse(live(cluster, 1), mode.toString());
            assertNoDivergentPayload(cluster);
        }
    }

    private static Cluster cluster(Durability durability) {
        var clock = new FakeClock(0L);
        var net = new SimTransport();
        var members = List.of(member(1, false), member(2, false), member(3, true));
        var voters = VoterSet.dataAndWitness(members.get(0), members.get(1), members.get(2));
        var opts = new GroupOptions(durability, 1024, Duration.ofMillis(100), Duration.ofMillis(40), 1_000_000);
        var nodes = new ArrayList<ReplicatedNode>();
        var logs = new ArrayList<MemoryLogStorage>();
        for (var member : members) {
            var log = new MemoryLogStorage();
            logs.add(log);
            var node = ReplicatedNode.open(member.id(), voters, clock, log, net);
            node.openGroup(GROUP, opts, null);
            nodes.add(node);
        }
        var cluster = new Cluster(clock, net, nodes, logs, opts);
        clock.advance(Duration.ofMillis(100));
        drive(cluster);
        assertTrue(nodes.get(0).openGroup(GROUP, opts, null).isLeader());
        return cluster;
    }

    private static long commit(Cluster cluster, String text) throws Exception {
        var committed = cluster.nodes.get(0).append(GROUP, List.of(payload(text)));
        drive(cluster);
        assertTrue(committed.isDone(), text);
        long index = committed.get().index();
        assertStored(cluster, 0, index, text);
        assertStored(cluster, 1, index, text);
        return index;
    }

    private static void settle(Cluster cluster) {
        cluster.clock.advance(Duration.ofMillis(500));
        drive(cluster);
        cluster.clock.advance(Duration.ofMillis(40));
        drive(cluster);
    }

    private static int liveCount(Cluster cluster) {
        int live = 0;
        for (int i = 0; i < cluster.nodes.size(); i++) {
            if (live(cluster, i)) {
                live++;
            }
        }
        return live;
    }

    private static void assertAtMostOneLiveLeader(Cluster cluster) {
        assertTrue(liveCount(cluster) <= 1);
    }

    private static boolean succeeded(java.util.concurrent.CompletableFuture<?> future) {
        return future.isDone() && !future.isCompletedExceptionally();
    }

    private static boolean live(Cluster cluster, int index) {
        var group = cluster.nodes.get(index).openGroup(GROUP, cluster.options, null);
        return group.isLeader() && group.leaseExpiresAt() > cluster.clock.nanoTime();
    }

    private static void assertStored(Cluster cluster, int node, long index, String text) throws Exception {
        var raw = cluster.logs.get(node).read(GROUP, index);
        assertTrue(raw != null && raw.length > 1, node + " missing " + index);
        assertEquals(RaftGroup.USER, raw[0]);
        assertArrayEquals(text.getBytes(StandardCharsets.UTF_8), java.util.Arrays.copyOfRange(raw, 1, raw.length));
    }

    private static void assertNoDivergentPayload(Cluster cluster) throws Exception {
        long end = Math.max(cluster.logs.get(0).lastIndex(GROUP), cluster.logs.get(1).lastIndex(GROUP));
        for (long index = 1; index <= end; index++) {
            var left = cluster.logs.get(0).read(GROUP, index);
            var right = cluster.logs.get(1).read(GROUP, index);
            if (left != null && right != null) {
                assertArrayEquals(left, right, "index " + index);
            }
        }
    }

    private static ReplicatedNode liveLeader(Cluster cluster) {
        ReplicatedNode found = null;
        for (int i = 0; i < cluster.nodes.size(); i++) {
            if (live(cluster, i)) {
                assertTrue(found == null, "two live leaders");
                found = cluster.nodes.get(i);
            }
        }
        assertTrue(found != null, "no live leader");
        return found;
    }

    private static void cut(Cluster cluster, int a, int b) {
        cluster.net.cut(cluster.nodes.get(a).self(), cluster.nodes.get(b).self());
    }

    private static void drive(Cluster cluster) {
        ReplicatedNode.driveAll(cluster.nodes);
    }

    private static ByteBuffer payload(String text) {
        return ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
    }

    private static Member member(int wire, boolean witness) {
        return new Member(new NodeId(wire, "n" + wire), SITE, new InetSocketAddress("127.0.0.1", 23_000 + wire), 1L,
                MemberState.ALIVE, witness);
    }

    private record Cluster(FakeClock clock, SimTransport net, List<ReplicatedNode> nodes, List<MemoryLogStorage> logs,
            GroupOptions options) {
    }
}
