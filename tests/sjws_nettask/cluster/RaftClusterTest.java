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
    void prefixDeleteOnADurableLogSavesTheSnapshotToDiskFirst() throws Exception {
        // #214 refused this outright while the snapshot lived only in memory; with #205 the snapshot is
        // saved to disk before the prefix is deleted (a storage that cannot do that still refuses:
        // SnapshotPersistenceTest).
        var again = cluster(1, false, Durability.STRICT, 1_000_000);
        elect(again);
        var solo = again.nodes.get(0);
        var idx = solo.append(GROUP, List.of(bytes("keep"))).get().index();
        var snap = solo.snapshots(GROUP).take(idx);
        var root = java.nio.file.Files.createTempDirectory("durable-snap");
        var followerLog = new FileLogStorage(root);
        followerLog.append(GROUP, 1, 1, "e1".getBytes(StandardCharsets.UTF_8));
        followerLog.force(GROUP);
        var follower = ReplicatedNode.open(member(2, false).id(), List.of(member(1, false), member(2, false)),
                Set.of(member(1, false).id(), member(2, false).id()), QuorumPolicy.majority(), again.clock, followerLog,
                again.net);
        follower.openGroup(GROUP, again.options, null);
        follower.snapshots(GROUP).install(snap);
        var saved = followerLog.loadSnapshot(GROUP, again.options.maxSnapshotBytes());
        assertEquals(idx, saved.lastIncludedIndex());
        assertArrayEquals(snap.data(), saved.data());
        followerLog.close();
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
                Duration.ofMillis(50), Duration.ofSeconds(5), ClusterEvents.NONE,
                ClusterKey.of("0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8), null));
        assertThrows(UnsupportedOperationException.class, () -> local.openGroup(GROUP, options(Durability.STRICT, 1), null));
        assertThrows(UnsupportedOperationException.class, () -> local.append(GROUP, List.of()));
        assertThrows(UnsupportedOperationException.class, () -> local.onLeaderChange(GROUP, (t, l) -> {
        }));
        assertThrows(UnsupportedOperationException.class, () -> local.onNoopApplied(GROUP, (t, i) -> {
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

    @Test
    void sparseWireIdsBoundElectionTimeoutAndFailover() throws Exception {
        // Non-contiguous wire ids: the old (wire - 1) * (election / 5) stagger made node 42
        // wait ~9 election periods. The bounded first deadline plus redrawn retry jitter keep
        // both the first election and a leader-kill failover inside a few election periods.
        var clock = new FakeClock(0L);
        var net = new SimTransport();
        var wires = List.of(1, 7, 42);
        var members = new ArrayList<Member>();
        var voters = new java.util.LinkedHashSet<NodeId>();
        for (int wire : wires) {
            var member = member(wire, false);
            members.add(member);
            voters.add(member.id());
        }
        var opts = options(Durability.STRICT, 1_000_000);
        long election = opts.electionTimeout().toNanos();
        int count = voters.size();
        long maxFirst = 0L;
        for (int wire : wires) {
            long first = election + (long) Math.floorMod(wire - 1, count) * (election / count);
            maxFirst = Math.max(maxFirst, first);
            assertTrue(first < election * 2,
                    "first deadline for wire " + wire + " must stay under 2 * election");
        }
        assertTrue(maxFirst < election + election,
                "sparse wire ids must not grow the first stagger past one election");

        var nodes = new ArrayList<ReplicatedNode>();
        var logs = new ArrayList<MemoryLogStorage>();
        for (var member : members) {
            var log = new MemoryLogStorage();
            logs.add(log);
            var node = ReplicatedNode.open(member.id(), members, voters, QuorumPolicy.majority(), clock, log, net);
            node.openGroup(GROUP, opts, null);
            nodes.add(node);
        }
        var cluster = new Cluster(clock, net, nodes, logs, opts);

        long electBound = election * 6;
        long started = clock.nanoTime();
        while (clock.nanoTime() - started <= electBound) {
            clock.advance(Duration.ofMillis(20));
            ReplicatedNode.driveAll(cluster.nodes);
            if (optionalLeader(cluster).isPresent()) {
                break;
            }
        }
        var leader = leader(cluster);
        assertTrue(clock.nanoTime() - started <= electBound,
                "first election with wires 1,7,42 must finish within 6 election periods");
        var committed = leader.append(GROUP, List.of(bytes("before")));
        ReplicatedNode.driveAll(cluster.nodes);
        assertTrue(committed.isDone());

        var dead = leader.self();
        for (var member : members) {
            if (!member.id().equals(dead)) {
                cluster.net.cut(dead, member.id());
            }
        }

        long failStart = clock.nanoTime();
        long failBound = election * 4;
        NodeId next = null;
        while (clock.nanoTime() - failStart <= failBound) {
            clock.advance(Duration.ofMillis(20));
            ReplicatedNode.driveAll(cluster.nodes);
            for (var node : cluster.nodes) {
                if (!node.self().equals(dead) && node.openGroup(GROUP, opts, null).isLeader()) {
                    next = node.self();
                    break;
                }
            }
            if (next != null) {
                break;
            }
        }
        assertNotNull(next, "failover with sparse wire ids must elect inside 4 election periods");
        assertNotEquals(dead, next);
        assertTrue(clock.nanoTime() - failStart <= failBound);
        final NodeId successorId = next;
        ReplicatedNode successor = null;
        for (var node : cluster.nodes) {
            if (node.self().equals(successorId)) {
                successor = node;
                break;
            }
        }
        assertNotNull(successor);
        var after = successor.append(GROUP, List.of(bytes("after")));
        ReplicatedNode.driveAll(cluster.nodes);
        assertTrue(after.isDone());
    }

    private static Optional<NodeId> optionalLeader(Cluster cluster) {
        for (var node : cluster.nodes) {
            if (node.openGroup(GROUP, cluster.options, null).isLeader()) {
                return Optional.of(node.self());
            }
        }
        return Optional.empty();
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

    @Test
    void callbackThatAppendsDoesNotOverlapOrReorder() throws Exception {
        var cluster = cluster(1, false, Durability.RELAXED, 1_000_000);
        elect(cluster);
        var node = cluster.nodes.get(0);
        var inFlight = new java.util.concurrent.atomic.AtomicInteger();
        var overlaps = new java.util.concurrent.atomic.AtomicInteger();
        var indexes = java.util.Collections.synchronizedList(new ArrayList<Long>());
        var texts = java.util.Collections.synchronizedList(new ArrayList<String>());
        node.openGroup(GROUP, cluster.options, entry -> {
            if (inFlight.incrementAndGet() != 1) {
                overlaps.incrementAndGet();
            }
            try {
                var text = text(entry.entry());
                indexes.add(entry.index());
                texts.add(text);
                if (text.startsWith("p")) {
                    node.append(GROUP, List.of(bytes("c" + text.substring(1)))); // nested append from the delivery thread
                }
                if (text.equals("boom")) {
                    throw new IllegalStateException("test");
                }
            } finally {
                inFlight.decrementAndGet();
            }
        });
        int threads = 8;
        int perThread = 50;
        var start = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        try {
            var futures = new ArrayList<java.util.concurrent.Future<?>>();
            for (int t = 0; t < threads; t++) {
                int id = t;
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        node.append(GROUP, List.of(bytes(i == 7 && id == 0 ? "boom" : "p" + id + "-" + i)))
                                .get(5, java.util.concurrent.TimeUnit.SECONDS);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (var f : futures) {
                f.get(10, java.util.concurrent.TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        var last = node.append(GROUP, List.of(bytes("end"))).get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(0, overlaps.get(), "callbacks overlapped");
        var seen = List.copyOf(indexes);
        for (int i = 1; i < seen.size(); i++) {
            assertTrue(seen.get(i) > seen.get(i - 1), "out of order at " + i + ": " + seen);
        }
        assertEquals(last.index(), seen.get(seen.size() - 1));
        long nested = texts.stream().filter(x -> x.startsWith("c")).count();
        long parents = texts.stream().filter(x -> x.startsWith("p")).count();
        assertEquals(threads * perThread - 1, parents);
        assertEquals(parents, nested, "an append made inside a callback was stranded");
    }

    @Test
    void slowCallbackDoesNotHoldUpHeartbeatsOrVotes() throws Exception {
        var cluster = cluster(3, false, Durability.RELAXED, 1_000_000);
        elect(cluster);
        var leader = leader(cluster);
        long term = leader.openGroup(GROUP, cluster.options, null).term();
        var started = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        leader.openGroup(GROUP, cluster.options, entry -> {
            if (text(entry.entry()).equals("slow")) {
                started.countDown();
                try {
                    release.await(500, java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        var slow = Thread.ofPlatform().start(() -> {
            leader.append(GROUP, List.of(bytes("slow")));
            ReplicatedNode.driveAll(cluster.nodes); // commits "slow"; drive() then waits for its delivery
        });
        try {
            assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS));
            long begin = System.nanoTime();
            for (int round = 0; round < 10; round++) {
                cluster.clock.advance(Duration.ofMillis(40)); // heartbeat interval; election is 100 ms
                for (var node : cluster.nodes) {
                    node.step(); // the Raft thread's path: never waits for a callback
                }
            }
            long tookMillis = (System.nanoTime() - begin) / 1_000_000;
            assertTrue(tookMillis < 300, "ticks waited behind the slow callback: " + tookMillis + " ms");
            for (var node : cluster.nodes) {
                assertEquals(term, node.openGroup(GROUP, cluster.options, null).term(), "no election");
            }
            assertTrue(leader.openGroup(GROUP, cluster.options, null).isLeader());
        } finally {
            release.countDown();
            slow.join(5_000);
        }
    }

    @Test
    void concurrentAppendsWithRaftTicksDeliverEachEntryOnceInOrder() throws Exception {
        var cluster = cluster(1, false, Durability.RELAXED, 1_000_000);
        elect(cluster);
        var node = cluster.nodes.get(0);
        var indexes = java.util.Collections.synchronizedList(new ArrayList<Long>());
        var inFlight = new java.util.concurrent.atomic.AtomicInteger();
        var overlaps = new java.util.concurrent.atomic.AtomicInteger();
        node.openGroup(GROUP, cluster.options, entry -> {
            if (inFlight.incrementAndGet() != 1) {
                overlaps.incrementAndGet();
            }
            indexes.add(entry.index());
            inFlight.decrementAndGet();
        });
        long base = node.append(GROUP, List.of(bytes("base"))).get().index();
        indexes.clear();
        int threads = 8;
        int perThread = 200;
        var start = new java.util.concurrent.CountDownLatch(1);
        var stop = new java.util.concurrent.atomic.AtomicBoolean();
        var ticker = Thread.ofPlatform().start(() -> {
            while (!stop.get()) {
                node.step(); // Raft-thread path, concurrent with appends
            }
        });
        var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        try {
            var futures = new ArrayList<java.util.concurrent.Future<?>>();
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        node.append(GROUP, List.of(bytes("e"))).get(5, java.util.concurrent.TimeUnit.SECONDS);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (var f : futures) {
                f.get(10, java.util.concurrent.TimeUnit.SECONDS);
            }
        } finally {
            stop.set(true);
            ticker.join(5_000);
            pool.shutdownNow();
        }
        assertEquals(0, overlaps.get());
        var seen = List.copyOf(indexes);
        assertEquals(threads * perThread, seen.size(), "each entry delivered exactly once");
        for (int i = 0; i < seen.size(); i++) {
            assertEquals(base + 1 + i, seen.get(i), "in order, no gaps or repeats");
        }
    }

    /**
     * Deterministic version of the slow-callback check, latched instead of timed: while a follower's
     * commit callback is parked on another thread, that follower still handles a heartbeat and a vote
     * request on the Raft path and replies to the vote.
     */
    @Test
    void parkedCallbackDoesNotBlockHeartbeatOrVoteOnFollower() throws Exception {
        var cluster = cluster(3, false, Durability.RELAXED, 1_000_000);
        elect(cluster);
        var leader = leader(cluster);
        var follower = cluster.nodes.stream().filter(n -> n != leader).findFirst().orElseThrow();
        var other = cluster.nodes.stream().filter(n -> n != leader && n != follower).findFirst().orElseThrow();
        long term = leader.openGroup(GROUP, cluster.options, null).term();
        var parked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var inCallback = new java.util.concurrent.atomic.AtomicBoolean();
        follower.openGroup(GROUP, cluster.options, entry -> {
            if (text(entry.entry()).equals("slow")) {
                inCallback.set(true);
                parked.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    inCallback.set(false);
                }
            }
        });
        var drainer = Thread.ofPlatform().start(() -> {
            leader.append(GROUP, List.of(bytes("slow")));
            ReplicatedNode.driveAll(cluster.nodes); // the leader commits "slow"
            cluster.clock.advance(cluster.options.heartbeat());
            ReplicatedNode.driveAll(cluster.nodes); // the next heartbeat carries the commit index: the
                                                    // follower applies "slow" and parks on this thread
        });
        var voteReply = new java.util.concurrent.CompletableFuture<ByteBuffer>();
        var raft = new java.util.concurrent.CompletableFuture<Void>();
        try {
            assertTrue(parked.await(10, java.util.concurrent.TimeUnit.SECONDS), "callback never parked");
            Thread.ofPlatform().start(() -> {
                try {
                    // heartbeat: leader tick sends APPEND, the follower handles it on the Raft path
                    cluster.clock.advance(cluster.options.heartbeat());
                    leader.step();
                    follower.step();
                    leader.step();
                    // vote request from the third member at a higher term, with a log at least as new
                    cluster.net.detach(other.self()); // a probe takes the third member's id
                    var rpc = cluster.net.attach(other.self(), (from, frame) -> {
                        if (frame.type() == RaftGroup.VOTE_RESP) {
                            voteReply.complete(ByteBuffer.wrap(frame.body()));
                        }
                    });
                    var vote = ByteBuffer.allocate(8 + 4 + 8 + 8).order(java.nio.ByteOrder.BIG_ENDIAN);
                    vote.putLong(term + 1).putInt(other.self().wire()).putLong(Long.MAX_VALUE / 2)
                            .putLong(Long.MAX_VALUE / 2);
                    rpc.send(follower.self(), new SimTransport.Frame(GROUP, RaftGroup.VOTE, vote.array()));
                    follower.step();
                    raft.complete(null);
                } catch (Throwable t) {
                    raft.completeExceptionally(t);
                }
            });
            // Safety bound only: on blocking delivery this never completes while the callback is parked.
            raft.get(10, java.util.concurrent.TimeUnit.SECONDS);
            var reply = voteReply.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(inCallback.get(), "the callback was still parked while the follower answered");
            assertEquals(term + 1, reply.getLong(), "the follower handled the vote at the new term");
            assertEquals(follower.self().wire(), reply.getInt());
            assertEquals(1, reply.get(), "vote granted");
        } finally {
            release.countDown();
            drainer.join(10_000);
        }
    }

    @Test
    void callbacksAndFuturesRunOnTheDeliveryThreadInOrder() throws Exception {
        var cluster = cluster(1, false, Durability.RELAXED, 1_000_000);
        elect(cluster);
        var node = cluster.nodes.get(0);
        var callbackThreads = java.util.concurrent.ConcurrentHashMap.<String>newKeySet();
        var events = java.util.Collections.synchronizedList(new ArrayList<String>());
        node.openGroup(GROUP, cluster.options, entry -> {
            callbackThreads.add(Thread.currentThread().getName());
            events.add("cb" + entry.index());
        });
        var futures = new ArrayList<java.util.concurrent.CompletableFuture<?>>();
        var futureThreads = java.util.concurrent.ConcurrentHashMap.<String>newKeySet();
        for (int i = 0; i < 20; i++) {
            var f = node.append(GROUP, List.of(bytes("x" + i)));
            futures.add(f.thenAccept(c -> {
                futureThreads.add(Thread.currentThread().getName());
                events.add("f" + c.index());
            }));
        }
        for (var f : futures) {
            f.get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertEquals(Set.of("raft-deliver-" + GROUP.value()), callbackThreads);
        // thenAccept on an already-complete future runs on the caller, so only check the ones that ran async
        futureThreads.remove(Thread.currentThread().getName());
        assertTrue(callbackThreads.containsAll(futureThreads), "futures complete on the delivery thread");
        var seen = List.copyOf(events);
        for (var e : seen) {
            if (e.startsWith("f")) {
                assertTrue(seen.indexOf("cb" + e.substring(1)) < seen.indexOf(e), "callback before its future: " + seen);
            }
        }
    }

    @Test
    void appendFailsFastWhenApplyLags() throws Exception {
        var cluster = cluster(1, false, Durability.RELAXED, 1_000_000);
        var node = cluster.nodes.get(0);
        var lagged = new GroupId("lagged");
        node.deliveryLagLimit = 4;
        var parked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var handle = node.openGroup(lagged, cluster.options, entry -> {
            if (text(entry.entry()).equals("slow")) {
                parked.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        elect(cluster);
        assertTrue(handle.isLeader()); // (re-opening with a null listener would drop ours)
        var accepted = new ArrayList<java.util.concurrent.CompletableFuture<?>>();
        accepted.add(node.append(lagged, List.of(bytes("slow"))));
        assertTrue(parked.await(5, java.util.concurrent.TimeUnit.SECONDS));
        ApplyLagException refused = null;
        for (int i = 0; i < 20 && refused == null; i++) {
            var f = node.append(lagged, List.of(bytes("e" + i)));
            // Refused like the other append refusals: the returned future fails at once, nothing is appended.
            if (f.isCompletedExceptionally()) {
                var cause = assertThrows(java.util.concurrent.ExecutionException.class, f::get).getCause();
                refused = assertInstanceOf(ApplyLagException.class, cause);
            } else {
                accepted.add(f);
            }
        }
        assertNotNull(refused, "append kept accepting while apply lagged");
        assertTrue(refused.lag() >= 4, "lag " + refused.lag());
        assertTrue(node.deliveryLag(lagged) >= 4);
        release.countDown();
        for (var f : accepted) {
            f.get(5, java.util.concurrent.TimeUnit.SECONDS); // everything accepted is still delivered
        }
        assertTrue(node.maxDeliveryLag(lagged) >= 4);
        node.append(lagged, List.of(bytes("after"))).get(5, java.util.concurrent.TimeUnit.SECONDS);
    }

    @Test
    void closeDuringSlowCallbackIsBoundedAndAbandonsTheRest() throws Exception {
        var cluster = cluster(1, false, Durability.RELAXED, 1_000_000);
        elect(cluster);
        var node = cluster.nodes.get(0);
        var parked = new java.util.concurrent.CountDownLatch(1);
        var interrupted = new java.util.concurrent.atomic.AtomicBoolean();
        var after = new java.util.concurrent.atomic.AtomicInteger();
        node.openGroup(GROUP, cluster.options, entry -> {
            if (text(entry.entry()).equals("slow")) {
                parked.countDown();
                try {
                    Thread.sleep(60_000);
                } catch (InterruptedException e) {
                    interrupted.set(true);
                }
            } else {
                after.incrementAndGet();
            }
        });
        node.append(GROUP, List.of(bytes("slow")));
        assertTrue(parked.await(5, java.util.concurrent.TimeUnit.SECONDS));
        var later = node.append(GROUP, List.of(bytes("later")));
        long begin = System.nanoTime();
        node.close();
        long tookMillis = (System.nanoTime() - begin) / 1_000_000;
        assertTrue(tookMillis < 5_000, "close() waited " + tookMillis + " ms");
        assertTrue(interrupted.get(), "the slow callback was interrupted once the drain budget ran out");
        var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                () -> later.get(1, java.util.concurrent.TimeUnit.SECONDS));
        var notApplied = assertInstanceOf(CommittedNotAppliedException.class, failure.getCause(),
                "a committed entry's future says committed, not a generic failure");
        assertEquals(GROUP.value(), notApplied.group());
        assertTrue(notApplied.index() > 0);
        assertEquals(0, after.get(), "nothing delivered after close()");
        // "later": its callback and its future were abandoned, and counted.
        assertTrue(node.abandonedDeliveries(GROUP) >= 2, "abandoned " + node.abandonedDeliveries(GROUP));
    }

    /**
     * Latched, leader side, 3 voters: while the leader's callback is parked on the delivery thread, the
     * group keeps replicating and committing (a follower applies every entry) and the leader's futures
     * complete in log order only once the callback is released.
     */
    @Test
    void parkedLeaderCallbackStillCommitsAndCompletesFuturesInOrder() throws Exception {
        var cluster = cluster(3, false, Durability.RELAXED, 1_000_000);
        elect(cluster);
        var leader = leader(cluster);
        var parked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var handle = leader.openGroup(GROUP, cluster.options, entry -> {
            if (text(entry.entry()).equals("slow")) {
                parked.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        var follower = cluster.nodes.stream().filter(n -> n != leader).findFirst().orElseThrow();
        var followerSeen = new java.util.concurrent.CopyOnWriteArrayList<String>();
        follower.openGroup(GROUP, cluster.options, entry -> followerSeen.add(text(entry.entry())));
        var completed = new java.util.concurrent.CopyOnWriteArrayList<Long>();
        var futures = new ArrayList<java.util.concurrent.CompletableFuture<?>>();
        futures.add(leader.append(GROUP, List.of(bytes("slow"))).thenAccept(c -> completed.add(c.index())));
        stepAll(cluster);
        assertTrue(parked.await(5, java.util.concurrent.TimeUnit.SECONDS), "callback never parked");
        for (int i = 0; i < 3; i++) {
            futures.add(leader.append(GROUP, List.of(bytes("n" + i))).thenAccept(c -> completed.add(c.index())));
        }
        stepAll(cluster);
        cluster.clock.advance(cluster.options.heartbeat());
        stepAll(cluster); // the heartbeat carries the commit index to the followers
        assertTrue(awaitDelivered(follower), "follower deliveries");
        assertEquals(List.of("slow", "n0", "n1", "n2"), List.copyOf(followerSeen), "committed while parked");
        assertEquals(List.of(), List.copyOf(completed), "no future completes before the parked callback returns");
        assertTrue(handle.isLeader());
        release.countDown();
        for (var f : futures) {
            f.get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
        var order = List.copyOf(completed);
        assertEquals(4, order.size());
        for (int i = 1; i < order.size(); i++) {
            assertTrue(order.get(i) > order.get(i - 1), "futures out of order: " + order);
        }
    }

    private static void stepAll(Cluster cluster) {
        for (int round = 0; round < 4; round++) {
            for (var node : cluster.nodes) {
                node.step();
            }
        }
    }

    private static boolean awaitDelivered(ReplicatedNode node) throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (node.deliveryLag(GROUP) > 0) {
            if (System.nanoTime() > deadline) {
                return false;
            }
            Thread.sleep(1);
        }
        return true;
    }

    @Test
    void closeLogsOneWarningWithTheAbandonedIndexRange() throws Exception {
        var records = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var logger = java.util.logging.Logger.getLogger(RaftGroup.class.getName());
        var handler = new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord record) {
                if (record.getLevel() == java.util.logging.Level.WARNING) {
                    records.add(record.getMessage());
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(handler);
        try {
            var executor = new DeliveryExecutor("g", 100);
            var parked = new java.util.concurrent.CountDownLatch(1);
            executor.submit(() -> {
                parked.countDown();
                try {
                    Thread.sleep(60_000);
                } catch (InterruptedException e) {
                    // interrupted by close
                }
            }, 5);
            assertTrue(parked.await(5, java.util.concurrent.TimeUnit.SECONDS));
            var committed = new java.util.concurrent.CompletableFuture<Void>();
            var uncommitted = new java.util.concurrent.CompletableFuture<Void>();
            executor.submit(() -> { }, 6);
            executor.submit(() -> committed.complete(null), committed, 7);
            executor.submit(() -> uncommitted.complete(null), uncommitted);
            assertTrue(executor.close(Duration.ofMillis(200)));
            assertEquals(3, executor.abandoned());
            var abandonedWarnings = records.stream().filter(m -> m.contains("close abandoned")).toList();
            assertEquals(1, abandonedWarnings.size(), "one WARN per group: " + records);
            assertTrue(abandonedWarnings.get(0).contains("1 commit callbacks and 2 append futures"), abandonedWarnings.get(0));
            assertTrue(abandonedWarnings.get(0).contains("indexes 6..7"), abandonedWarnings.get(0));
            var e = assertThrows(java.util.concurrent.ExecutionException.class, committed::get);
            assertEquals(7, assertInstanceOf(CommittedNotAppliedException.class, e.getCause()).index());
            e = assertThrows(java.util.concurrent.ExecutionException.class, uncommitted::get);
            assertFalse(e.getCause() instanceof CommittedNotAppliedException, "not committed: plain failure");
            // After close: dropped and counted, no further WARN.
            var late = new java.util.concurrent.CompletableFuture<Void>();
            executor.submit(() -> late.complete(null), late, 8);
            assertEquals(4, executor.abandoned());
            assertInstanceOf(CommittedNotAppliedException.class,
                    assertThrows(java.util.concurrent.ExecutionException.class, late::get).getCause());
        } finally {
            logger.removeHandler(handler);
        }
    }

    @Test
    void lagPastTheWarnThresholdIsCountedOncePerBacklog() throws Exception {
        var executor = new DeliveryExecutor("g", 2); // warns at 2 x LAG_WARN_FACTOR = 8
        var release = new java.util.concurrent.CountDownLatch(1);
        executor.submit(() -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, 1);
        for (int i = 2; i <= 20; i++) {
            executor.submit(() -> { }, i);
        }
        assertEquals(1, executor.lagWarnings(), "one warning per backlog");
        release.countDown();
        assertTrue(executor.awaitIdle(Duration.ofSeconds(5)));
        for (int round = 0; round < 2; round++) {
            var hold = new java.util.concurrent.CountDownLatch(1);
            executor.submit(() -> {
                try {
                    hold.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, 100 + round);
            for (int i = 0; i < 10; i++) {
                executor.submit(() -> { }, 200 + i);
            }
            hold.countDown();
            assertTrue(executor.awaitIdle(Duration.ofSeconds(5)));
        }
        assertEquals(3, executor.lagWarnings(), "re-armed each time the queue drained");
        assertTrue(executor.close(Duration.ofSeconds(1)));
    }
}
