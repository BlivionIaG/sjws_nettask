package sjws_nettask.cluster;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
class RaftClusterTest {

    private static final SiteId SITE = new SiteId("local");
    private static final GroupId GROUP = new GroupId("ha-control");

    private static GroupOptions options(Durability durability, long snapshotEvery) {
        return new GroupOptions(durability, 1024, Duration.ofMillis(100), Duration.ofMillis(40), snapshotEvery);
    }

    private static Member member(int wire, boolean witness) {
        return new Member(new NodeId(wire, "n" + wire), SITE, new InetSocketAddress("127.0.0.1", 20_000 + wire), 1L,
                MemberState.ALIVE, witness);
    }

    private Cluster cluster(int n, boolean witness, Durability durability, long snapshotEvery) {
        var clock = new FakeClock(0L);
        var net = new SimTransport();
        var members = new ArrayList<Member>();
        var voters = new java.util.LinkedHashSet<NodeId>();
        for (int wire = 1; wire <= n; wire++) {
            boolean isWitness = witness && wire == n;
            var member = member(wire, isWitness);
            members.add(member);
            voters.add(member.id());
        }
        QuorumPolicy policy = witness ? QuorumPolicy.witness(members.get(n - 1).id()) : QuorumPolicy.majority();
        var nodes = new ArrayList<ReplicatedNode>();
        var logs = new ArrayList<MemoryLogStorage>();
        var opts = options(durability, snapshotEvery);
        for (var member : members) {
            var log = new MemoryLogStorage();
            logs.add(log);
            var node = ReplicatedNode.open(member.id(), members, voters, policy, clock, log, net);
            node.openGroup(GROUP, opts, null);
            nodes.add(node);
        }
        return new Cluster(clock, net, nodes, logs, opts);
    }

    private void elect(Cluster cluster) {
        cluster.clock.advance(Duration.ofMillis(100));
        ReplicatedNode.driveAll(cluster.nodes);
    }

    @Test
    void electionAppendOrderAndNotLeaderHint() throws Exception {
        var cluster = cluster(3, false, Durability.STRICT, 1_000_000);
        var seen = new AtomicReference<Optional<NodeId>>(Optional.empty());
        cluster.nodes.get(1).onLeaderChange(GROUP, (term, leader) -> seen.set(leader));
        elect(cluster);
        var leader = leader(cluster);
        assertEquals(new NodeId(1, "n1"), leader.self());
        assertTrue(seen.get().isPresent());
        var group = leader.openGroup(GROUP, cluster.options, null);
        assertTrue(group.isLeader());
        assertEquals(1L, group.term());
        var first = leader.append(GROUP, List.of(bytes("a"), bytes("b"), bytes("c")));
        var applied = new ArrayList<String>();
        leader.openGroup(GROUP, cluster.options, entry -> applied.add(text(entry.entry())));
        ReplicatedNode.driveAll(cluster.nodes);
        var committed = first.get();
        assertEquals(GROUP, committed.groupId());
        assertEquals(List.of("a", "b", "c"), applied);
        assertTrue(group.commitIndex() >= committed.index());
        assertEquals(group.commitIndex(), group.lastApplied());
        var follower = cluster.nodes.get(1);
        var rejected = follower.append(GROUP, List.of(bytes("no")));
        var cause = assertThrows(ExecutionException.class, rejected::get).getCause();
        var notLeader = assertInstanceOf(NotLeaderException.class, cause);
        assertEquals(leader.self(), notLeader.hint().orElseThrow());
        assertEquals(GROUP, notLeader.groupId());
        assertTrue(follower.forcedBeforeAck(GROUP));
        assertFalse(follower.ackedFromMemory(GROUP));
        group.readBarrier().get();
        assertThrows(ExecutionException.class, () -> follower.openGroup(GROUP, cluster.options, null).readBarrier().get());
    }

    @Test
    void defaultAckIsMajorityInMemoryAndStrictStaysOptIn() throws Exception {
        var defaults = new GroupOptions(1024, Duration.ofMillis(100), Duration.ofMillis(40), 1_000_000);
        assertEquals(Durability.RELAXED, defaults.durability());
        var cluster = cluster(3, false, defaults.durability(), 1_000_000);
        elect(cluster);
        var leader = leader(cluster);
        var committed = leader.append(GROUP, List.of(bytes("mem")));
        ReplicatedNode.driveAll(cluster.nodes);
        assertTrue(committed.isDone());
        assertTrue(cluster.nodes.get(1).ackedFromMemory(GROUP));
        assertFalse(cluster.nodes.get(1).forcedBeforeAck(GROUP));

        var strict = cluster(3, false, Durability.STRICT, 1_000_000);
        elect(strict);
        var strictLeader = leader(strict);
        var fsynced = strictLeader.append(GROUP, List.of(bytes("disk")));
        ReplicatedNode.driveAll(strict.nodes);
        assertTrue(fsynced.isDone());
        assertTrue(strict.nodes.get(1).forcedBeforeAck(GROUP));
        assertFalse(strict.nodes.get(1).ackedFromMemory(GROUP));
    }

    @Test
    void relaxedAcksBeforeFsyncAndTwoGroupsStayOrdered() throws Exception {
        var cluster = cluster(3, false, Durability.RELAXED, 1);
        elect(cluster);
        var leader = leader(cluster);
        var other = new GroupId("partition-7");
        var a = new ArrayList<Long>();
        var b = new ArrayList<Long>();
        leader.openGroup(GROUP, cluster.options, entry -> a.add(entry.index()));
        for (var node : cluster.nodes) {
            node.openGroup(other, options(Durability.RELAXED, 1_000_000),
                    node == leader ? entry -> b.add(entry.index()) : null);
        }
        ReplicatedNode.driveAll(cluster.nodes);
        var first = leader.append(GROUP, List.of(bytes("a1"), bytes("a2")));
        var second = leader.append(other, List.of(bytes("b1")));
        ReplicatedNode.driveAll(cluster.nodes);
        assertTrue(first.get().index() > 0);
        assertTrue(second.get().index() > 0);
        assertEquals(List.of(first.get().index() - 1, first.get().index()), a);
        assertEquals(List.of(second.get().index()), b);
        assertNotEquals(leader.snapshots(GROUP).take(first.get().index()).groupId(), other);
        assertTrue(cluster.nodes.get(1).ackedFromMemory(GROUP));
        var trace = cluster.net.trace();
        assertTrue(trace.contains(GROUP) && trace.contains(other));
        long last = 0;
        for (var index : a) {
            assertTrue(index > last);
            last = index;
        }
    }

    @Test
    void witnessAcksWithoutALogAndAMinorityCannotCommit() throws Exception {
        var cluster = cluster(3, true, Durability.STRICT, 1_000_000);
        elect(cluster);
        var leader = leader(cluster);
        var witness = cluster.nodes.get(2);
        assertTrue(witness.witness());
        cluster.net.cut(leader.self(), cluster.nodes.get(1).self());
        var committed = leader.append(GROUP, List.of(bytes("kept")));
        ReplicatedNode.driveAll(cluster.nodes);
        assertFalse(committed.isDone());
        assertEquals(0L, cluster.logs.get(2).lastIndex(GROUP));
        assertTrue(cluster.logs.get(2).witnessIndex(GROUP) > 0L);
        cluster.net.heal(leader.self(), cluster.nodes.get(1).self());
        cluster.clock.advance(Duration.ofMillis(40));
        ReplicatedNode.driveAll(cluster.nodes);
        assertTrue(committed.isDone());

        var majority = cluster(3, false, Durability.STRICT, 1_000_000);
        elect(majority);
        var primary = leader(majority);
        majority.net.cut(primary.self(), majority.nodes.get(1).self());
        majority.net.cut(primary.self(), majority.nodes.get(2).self());
        var stuck = primary.append(GROUP, List.of(bytes("lost")));
        ReplicatedNode.driveAll(majority.nodes);
        assertFalse(stuck.isDone());
        majority.clock.advance(Duration.ofMillis(40));
        majority.net.heal(primary.self(), majority.nodes.get(1).self());
        majority.net.heal(primary.self(), majority.nodes.get(2).self());
        ReplicatedNode.driveAll(majority.nodes);
        assertTrue(stuck.isDone());
    }

    @Test
    void leaseReadMembershipTransferAndSnapshotCompact() throws Exception {
        var cluster = cluster(3, false, Durability.STRICT, 1_000_000);
        elect(cluster);
        var leader = leader(cluster);
        var group = leader.openGroup(GROUP, cluster.options, null);
        assertNull(group.readBarrier().get());
        cluster.clock.advance(Duration.ofMillis(80));
        var refreshed = group.readBarrier();
        assertFalse(refreshed.isDone());
        ReplicatedNode.driveAll(cluster.nodes);
        assertNull(refreshed.get());

        var added = member(4, false);
        var withLearner = fourNodeCluster();
        elect(withLearner);
        var leaderNode = leader(withLearner);
        var adding = leaderNode.openGroup(GROUP, withLearner.options, null);
        var change = adding.changeMembership(Set.of(member(1, false).id(), member(2, false).id(), member(3, false).id(),
                added.id()), Set.of());
        ReplicatedNode.driveAll(withLearner.nodes);
        assertNull(change.get());
        assertTrue(leaderNode.voters(GROUP).contains(added.id()));

        group.transferLeadership(cluster.nodes.get(1).self());
        ReplicatedNode.driveAll(cluster.nodes);
        assertTrue(cluster.nodes.get(1).openGroup(GROUP, cluster.options, null).isLeader());
        assertFalse(group.isLeader());

        var again = cluster(1, false, Durability.STRICT, 1_000_000);
        elect(again);
        var solo = again.nodes.get(0);
        var idx = solo.append(GROUP, List.of(bytes("snap"))).get().index();
        var snap = solo.snapshots(GROUP).take(idx);
        assertTrue(text(ByteBuffer.wrap(snap.data())).contains("snap"));
        var followerLog = new MemoryLogStorage();
        var follower = ReplicatedNode.open(member(2, false).id(), List.of(member(1, false), member(2, false)),
                Set.of(member(1, false).id(), member(2, false).id()), QuorumPolicy.majority(), again.clock, followerLog,
                again.net);
        follower.openGroup(GROUP, again.options, null);
        follower.snapshots(GROUP).install(snap);
        var compacted = follower.snapshots(GROUP).truncateAndCompact(idx, snapshot -> "shorter".getBytes(StandardCharsets.UTF_8));
        assertNull(compacted.get());
        assertNull(followerLog.read(GROUP, 1));
        var taken = follower.snapshots(GROUP).take(idx);
        assertEquals("shorter", new String(taken.data(), StandardCharsets.UTF_8));
        var rejected = follower.snapshots(GROUP).truncateAndCompact(idx + 5, snapshot -> null);
        assertInstanceOf(IllegalArgumentException.class,
                assertThrows(ExecutionException.class, rejected::get).getCause());
        assertThrows(IllegalArgumentException.class, () -> solo.snapshots(GROUP).take(idx + 10));
        assertThrows(IllegalArgumentException.class, () -> solo.snapshots(GROUP).install(
                new LogSnapshot(new GroupId("other"), 1, 1, new byte[0])));
    }

    @Test
    void preferredCampaignsSoonerAndFailbackDelaysTheDemotedLeader() {
        var clock = new FakeClock(0L);
        var net = new SimTransport();
        var members = List.of(member(1, false), member(2, false), member(3, false));
        var voters = Set.of(members.get(0).id(), members.get(1).id(), members.get(2).id());
        var plain = options(Durability.STRICT, 1_000_000).withFailback(Duration.ofSeconds(1));
        var preferred = plain.withPreferred(Duration.ofSeconds(1));
        var nodes = new ArrayList<ReplicatedNode>();
        for (var member : members) {
            var opts = member.id().wire() == 3 ? preferred : plain;
            var node = ReplicatedNode.open(member.id(), members, voters, QuorumPolicy.majority(), clock,
                    new MemoryLogStorage(), net);
            node.openGroup(GROUP, opts, null);
            nodes.add(node);
        }
        clock.advance(Duration.ofMillis(20));
        ReplicatedNode.driveAll(nodes);
        var leader = nodes.get(2).openGroup(GROUP, preferred, null);
        assertTrue(leader.isLeader());
        assertFalse(nodes.get(0).openGroup(GROUP, plain, null).isLeader());
        assertTrue(leader.leaseExpiresAt() > clock.nanoTime());
        assertEquals(0L, nodes.get(0).openGroup(GROUP, plain, null).leaseExpiresAt());

        leader.transferLeadership(members.get(1).id());
        ReplicatedNode.driveAll(nodes);
        assertTrue(nodes.get(1).openGroup(GROUP, plain, null).isLeader());
        assertFalse(leader.isLeader());
        net.cut(members.get(2).id(), members.get(0).id());
        net.cut(members.get(2).id(), members.get(1).id());
        long term = leader.term();
        clock.advance(Duration.ofMillis(200));
        ReplicatedNode.driveAll(nodes);
        assertEquals(term, leader.term());
        assertFalse(leader.isLeader());
        assertFalse(nodes.get(2).campaigning(GROUP));
        clock.advance(Duration.ofSeconds(1));
        ReplicatedNode.driveAll(nodes);
        assertEquals(term, leader.term());
        assertFalse(leader.isLeader());
        assertTrue(nodes.get(2).campaigning(GROUP));
    }

    @Test
    void fileLogRoundTripAndApiGuards() throws Exception {
        var root = Files.createTempDirectory("raft-log");
        var store = new FileLogStorage(root);
        var group = new GroupId("g");
        store.append(group, 1, 2, new byte[] {1, 2});
        store.append(group, 2, 2, new byte[] {3});
        store.force(group);
        assertEquals(1, store.forces(group));
        store.truncatePrefix(group, 1);
        assertNull(store.read(group, 1));
        assertArrayEquals(new byte[] {3}, store.read(group, 2));
        store.close();
        var reopened = new FileLogStorage(root);
        assertArrayEquals(new byte[] {3}, reopened.read(group, 2));
        assertEquals(2L, reopened.termAt(group, 2));
        reopened.truncateSuffix(group, 2);
        assertEquals(0L, reopened.lastIndex(group));
        reopened.close();
        assertThrows(IllegalArgumentException.class, () -> new FileLogStorage(root).append(group, 0, 1, new byte[0]));

        var cluster = cluster(1, false, Durability.STRICT, 1_000_000);
        var node = cluster.nodes.get(0);
        var missing = node.append(new GroupId("missing"), List.of(bytes("x")));
        assertInstanceOf(IllegalStateException.class, assertThrows(ExecutionException.class, missing::get).getCause());
        assertThrows(IllegalArgumentException.class, () -> node.append(GROUP, List.of()));
        assertThrows(IllegalArgumentException.class, () -> node.begin(Duration.ZERO));
        node.begin(Duration.ofMillis(20));
        node.begin(Duration.ofMillis(20));
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (!node.openGroup(GROUP, cluster.options, null).isLeader() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(node.openGroup(GROUP, cluster.options, null).isLeader());
        node.close();
        node.close();

        assertThrows(IllegalArgumentException.class, () -> new GroupId(" "));
        assertThrows(NullPointerException.class, () -> new GroupOptions(null, 1, Duration.ofMillis(1),
                Duration.ofMillis(1), 1));
        assertThrows(IllegalArgumentException.class,
                () -> new GroupOptions(Durability.STRICT, 0, Duration.ofMillis(1), Duration.ofMillis(1), 1));
        assertThrows(IllegalArgumentException.class,
                () -> new GroupOptions(Durability.STRICT, 1, Duration.ZERO, Duration.ofMillis(1), 1));
        assertThrows(IllegalArgumentException.class,
                () -> new GroupOptions(Durability.STRICT, 1, Duration.ofMillis(1), Duration.ofMillis(1), 0));
        assertThrows(IllegalArgumentException.class, () -> options(Durability.STRICT, 1).withFailback(Duration.ofMillis(-1)));
        assertThrows(NullPointerException.class, () -> options(Durability.STRICT, 1).withPreferred(null));
        assertThrows(IllegalArgumentException.class, () -> new CommittedIndex(GROUP, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new LogSnapshot(GROUP, -1, 0, null));
        assertEquals(0, new LogSnapshot(GROUP, 0, 0, null).data().length);
        assertThrows(NullPointerException.class, () -> new CommittedEntry(GROUP, 1, 1, null));
        var beat = new SimTransport();
        var id = new NodeId(1, "a");
        beat.attach(id, (from, frame) -> {
        });
        assertThrows(IllegalArgumentException.class, () -> beat.attach(id, (from, frame) -> {
        }));
        assertThrows(NullPointerException.class, () -> new SimTransport.Frame(null, 1, new byte[0]));
        beat.deliver(1);

        var net = new SimNetwork();
        var link = net.link(new InetSocketAddress("127.0.0.1", 9));
        var local = LocalCluster.open(member(1, false).id(), List.of(member(1, false)), link, Clock.SYSTEM,
                Duration.ofMillis(50), Duration.ofSeconds(5), ClusterEvents.NONE);
        assertThrows(UnsupportedOperationException.class, () -> local.openGroup(GROUP, options(Durability.STRICT, 1), null));
        assertThrows(UnsupportedOperationException.class, () -> local.append(GROUP, List.of()));
        assertThrows(UnsupportedOperationException.class, () -> local.onLeaderChange(GROUP, (t, l) -> {
        }));
        assertThrows(UnsupportedOperationException.class, () -> local.snapshots(GROUP));
        local.close();

        var memory = new MemoryLogStorage();
        assertEquals(0, memory.forces(GROUP));
        assertThrows(IllegalArgumentException.class, () -> memory.append(GROUP, 0, 1, new byte[] {1}));
        memory.append(GROUP, 1, 1, new byte[] {1});
        memory.truncateSuffix(GROUP, 1);
        assertEquals(0L, memory.lastIndex(GROUP));
    }

    private Cluster fourNodeCluster() {
        var clock = new FakeClock(0L);
        var net = new SimTransport();
        var members = List.of(member(1, false), member(2, false), member(3, false), member(4, false));
        var voters = Set.of(members.get(0).id(), members.get(1).id(), members.get(2).id());
        var nodes = new ArrayList<ReplicatedNode>();
        var logs = new ArrayList<MemoryLogStorage>();
        var opts = options(Durability.STRICT, 1_000_000);
        for (var member : members) {
            var log = new MemoryLogStorage();
            logs.add(log);
            var node = ReplicatedNode.open(member.id(), members, voters, QuorumPolicy.majority(), clock, log, net);
            node.openGroup(GROUP, opts, null);
            nodes.add(node);
        }
        return new Cluster(clock, net, nodes, logs, opts);
    }

    private static ReplicatedNode leader(Cluster cluster) {
        for (var node : cluster.nodes) {
            if (node.openGroup(GROUP, cluster.options, null).isLeader()) {
                return node;
            }
        }
        fail("no leader");
        return null;
    }

    private static ByteBuffer bytes(String text) {
        return ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String text(ByteBuffer buffer) {
        var copy = new byte[buffer.remaining()];
        buffer.duplicate().get(copy);
        return new String(copy, StandardCharsets.UTF_8);
    }

    private record Cluster(FakeClock clock, SimTransport net, List<ReplicatedNode> nodes, List<MemoryLogStorage> logs,
            GroupOptions options) {
    }
}
