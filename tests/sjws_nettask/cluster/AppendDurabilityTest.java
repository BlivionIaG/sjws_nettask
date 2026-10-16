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
package sjws_nettask.cluster;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Per-append durability on the two-data-node-plus-witness group. */
@Timeout(30)
class AppendDurabilityTest {

    private static final GroupId GROUP = new GroupId("msg/1");

    private record Cluster(FakeClock clock, SimTransport net, List<ReplicatedNode> nodes, List<MemoryLogStorage> logs,
            List<List<String>> seen, List<ReplicationGroup> groups) {
        void drive() {
            ReplicatedNode.driveAll(nodes);
        }

        void close() {
            for (var node : nodes) {
                node.close();
            }
        }
    }

    private static Cluster cluster(Durability mode) {
        var clock = new FakeClock(0L);
        var net = new SimTransport();
        var members = List.of(member(1, false), member(2, false), member(3, true));
        var voters = VoterSet.dataAndWitness(members.get(0), members.get(1), members.get(2));
        var opts = new GroupOptions(mode, 1024, Duration.ofMillis(100), Duration.ofMillis(40), 1_000_000);
        var nodes = new ArrayList<ReplicatedNode>();
        var logs = new ArrayList<MemoryLogStorage>();
        var seen = new ArrayList<List<String>>();
        var groups = new ArrayList<ReplicationGroup>();
        for (var member : members) {
            var log = new MemoryLogStorage();
            var got = new CopyOnWriteArrayList<String>();
            logs.add(log);
            seen.add(got);
            var node = ReplicatedNode.open(member.id(), voters, clock, log, net);
            groups.add(node.openGroup(GROUP, opts,
                    entry -> got.add(StandardCharsets.UTF_8.decode(entry.entry()).toString())));
            nodes.add(node);
        }
        var cluster = new Cluster(clock, net, nodes, logs, seen, groups);
        clock.advance(Duration.ofMillis(100));
        cluster.drive();
        assertTrue(nodes.get(0).openGroup(GROUP, opts, null).isLeader());
        return cluster;
    }

    @Test
    void strictOverrideInARelaxedGroupFsyncsBothDataCopiesBeforeCommit() throws Exception {
        var c = cluster(Durability.RELAXED);
        int leaderForces = c.logs.get(0).forces(GROUP);
        int followerForces = c.logs.get(1).forces(GROUP);
        var strict = c.nodes.get(0).append(GROUP, List.of(payload("s")), Durability.STRICT);
        assertTrue(c.logs.get(0).forces(GROUP) > leaderForces, "leader forces inside append");
        while (c.net.deliver(64) > 0) {
            // replicate and ack without ticking, so only the strict path can force
        }
        assertTrue(c.logs.get(1).forces(GROUP) > followerForces, "follower forced before its ack");
        assertTrue(c.nodes.get(1).forcedBeforeAck(GROUP));
        strict.get(5, TimeUnit.SECONDS);
        var relaxed = c.nodes.get(0).append(GROUP, List.of(payload("r")));
        c.drive();
        relaxed.get(5, TimeUnit.SECONDS);
        assertTrue(c.nodes.get(1).ackedFromMemory(GROUP));
        c.clock.advance(Duration.ofMillis(40)); // next heartbeat carries the new commit index
        c.drive();
        assertEquals(List.of("s", "r"), c.seen.get(1), "the marker is not part of the payload");
        c.close();
    }

    @Test
    void strictOverrideNeedsTheSecondDataDiskWhileRelaxedDoesNot() throws Exception {
        var c = cluster(Durability.RELAXED);
        c.net.cut(c.nodes.get(0).self(), c.nodes.get(1).self());
        c.net.cut(c.nodes.get(1).self(), c.nodes.get(2).self());
        var relaxed = c.nodes.get(0).append(GROUP, List.of(payload("r")), Durability.RELAXED);
        c.drive();
        relaxed.get(5, TimeUnit.SECONDS);
        var strict = c.nodes.get(0).append(GROUP, List.of(payload("s")), Durability.STRICT);
        var after = c.nodes.get(0).append(GROUP, List.of(payload("after")));
        c.drive();
        assertFalse(strict.isDone(), "leader disk + witness vote is not two data copies");
        assertFalse(after.isDone(), "a later index cannot commit past a pending STRICT one");
        c.net.heal(c.nodes.get(0).self(), c.nodes.get(1).self());
        c.net.heal(c.nodes.get(1).self(), c.nodes.get(2).self());
        c.clock.advance(Duration.ofMillis(40));
        c.drive();
        strict.get(5, TimeUnit.SECONDS);
        after.get(5, TimeUnit.SECONDS);
        c.close();
    }

    /**
     * Security #203 blocker 1: with one data node gone for longer than a lease, a STRICT override is
     * refused before it enters the log, so it cannot wedge the RELAXED entries that follow.
     */
    @Test
    void strictOverrideIsRefusedUpFrontWhenTheSecondDataCopyIsUnreachable() throws Exception {
        var c = cluster(Durability.RELAXED);
        c.net.cut(c.nodes.get(0).self(), c.nodes.get(1).self());
        c.net.cut(c.nodes.get(1).self(), c.nodes.get(2).self());
        for (int i = 0; i < 25; i++) { // 1 s of heartbeats that n2 never answers
            c.clock.advance(Duration.ofMillis(40));
            c.drive();
        }
        assertTrue(c.groups.get(0).isLeader(), "leader + witness keep the group");
        long lastBefore = c.logs.get(0).lastIndex(GROUP);
        var strict = c.nodes.get(0).append(GROUP, List.of(payload("s")), Durability.STRICT);
        assertTrue(strict.isCompletedExceptionally(), "refused at once, not left pending");
        var failure = assertThrows(java.util.concurrent.ExecutionException.class, strict::get);
        var refused = (DurabilityUnavailableException) failure.getCause();
        assertEquals(1, refused.reachable());
        assertEquals(2, refused.required());
        assertEquals(lastBefore, c.logs.get(0).lastIndex(GROUP), "nothing appended");
        var relaxed = c.nodes.get(0).append(GROUP, List.of(payload("r")));
        c.clock.advance(Duration.ofMillis(40));
        c.drive();
        relaxed.get(5, TimeUnit.SECONDS);
        c.net.heal(c.nodes.get(0).self(), c.nodes.get(1).self());
        c.net.heal(c.nodes.get(1).self(), c.nodes.get(2).self());
        for (int i = 0; i < 25 && !c.groups.get(0).isLeader(); i++) {
            c.clock.advance(Duration.ofMillis(40));
            c.drive();
        }
        int leaderIdx = c.groups.get(0).isLeader() ? 0 : 1;
        for (int i = 0; i < 5; i++) {
            c.clock.advance(Duration.ofMillis(40));
            c.drive();
        }
        var again = c.nodes.get(leaderIdx).append(GROUP, List.of(payload("s2")), Durability.STRICT);
        c.drive();
        again.get(5, TimeUnit.SECONDS);
        c.close();
    }

    /** Security #203 re-review: a STRICT entry stalled after acceptance shows in gauges and one WARN. */
    @Test
    void strictEntryStalledAfterAcceptanceIsVisible() throws Exception {
        var records = new java.util.concurrent.CopyOnWriteArrayList<java.util.logging.LogRecord>();
        var handler = new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        var jul = java.util.logging.Logger.getLogger(RaftGroup.class.getName());
        jul.addHandler(handler);
        try {
            var c = cluster(Durability.RELAXED);
            var leader = c.nodes.get(0);
            assertEquals(0, leader.strictPending(GROUP));
            assertEquals(0L, leader.oldestStrictPendingAgeNanos(GROUP));
            var strict = leader.append(GROUP, List.of(payload("s")), Durability.STRICT); // n2 was just heard
            c.net.cut(leader.self(), c.nodes.get(1).self());
            c.net.cut(c.nodes.get(1).self(), c.nodes.get(2).self());
            for (int i = 0; i < 25; i++) {
                c.clock.advance(Duration.ofMillis(40));
                c.drive();
            }
            assertFalse(strict.isDone());
            assertEquals(1, leader.strictPending(GROUP));
            assertTrue(leader.oldestStrictPendingAgeNanos(GROUP) >= Duration.ofMillis(900).toNanos());
            var warnings = records.stream().filter(r -> r.getLevel() == java.util.logging.Level.WARNING
                    && r.getMessage().contains("STRICT entry")).toList();
            assertEquals(1, warnings.size(), "one WARN per stalled entry, not one per tick");
            assertTrue(warnings.get(0).getMessage().contains("1 of 2 data copies"), warnings.get(0).getMessage());
            assertEquals(0, c.nodes.get(1).strictPending(GROUP), "followers report 0");
            c.net.heal(leader.self(), c.nodes.get(1).self());
            c.net.heal(c.nodes.get(1).self(), c.nodes.get(2).self());
            for (int i = 0; i < 25 && !strict.isDone(); i++) {
                c.clock.advance(Duration.ofMillis(40));
                c.drive();
            }
            if (c.groups.get(0).isLeader()) {
                strict.get(5, TimeUnit.SECONDS);
                assertEquals(0, leader.strictPending(GROUP));
                assertEquals(0L, leader.oldestStrictPendingAgeNanos(GROUP));
            }
            c.close();
        } finally {
            jul.removeHandler(handler);
        }
    }

    @Test
    void appendRespNamingAnotherVoterIsDropped() throws Exception {
        var n1 = new NodeId(1, "n1");
        var n2 = new NodeId(2, "n2");
        var n3 = new NodeId(3, "n3");
        var clock = new FakeClock(0L);
        var group = new RaftGroup(GROUP, n1, false, java.util.Set.of(n3), java.util.Set.of(n1, n2, n3),
                List.of(n2, n3), w -> w == 1 ? n1 : w == 2 ? n2 : w == 3 ? n3 : null, QuorumPolicy.majority(), opts(),
                new MemoryLogStorage(), (to, frame) -> { }, clock, null, 1024);
        clock.advance(Duration.ofSeconds(1));
        group.tick(clock.nanoTime());
        group.tick(clock.nanoTime());
        group.handle(n3, reply(RaftGroup.PRE_VOTE_RESP, 0, 3, true), clock.nanoTime());
        group.handle(n3, reply(RaftGroup.VOTE_RESP, 1, 3, true), clock.nanoTime());
        assertTrue(group.facade().isLeader());
        long term = group.facade().term();
        // The witness (authenticated as n3) sends an ack in n2's name: dropped.
        var forged = ByteBuffer.allocate(21).putLong(term).putInt(2).put((byte) 1).putLong(1);
        group.handle(n3, new SimTransport.Frame(GROUP, RaftGroup.APPEND_RESP, forged.array()), clock.nanoTime());
        var refused = group.append(List.of(payload("s")), Durability.STRICT);
        assertTrue(refused.isCompletedExceptionally(), "a forged ack must not make n2 look reachable");
        var genuine = ByteBuffer.allocate(21).putLong(term).putInt(2).put((byte) 1).putLong(1);
        group.handle(n2, new SimTransport.Frame(GROUP, RaftGroup.APPEND_RESP, genuine.array()), clock.nanoTime());
        var accepted = group.append(List.of(payload("s")), Durability.STRICT);
        assertFalse(accepted.isCompletedExceptionally(), "n2's own ack counts");
        group.closeDeliveries(Duration.ofSeconds(1));
    }

    @Test
    void relaxedOverrideInAStrictGroupStaysStrict() throws Exception {
        var c = cluster(Durability.STRICT);
        int forces = c.logs.get(0).forces(GROUP);
        var future = c.nodes.get(0).append(GROUP, List.of(payload("x")), Durability.RELAXED);
        assertTrue(c.logs.get(0).forces(GROUP) > forces);
        c.drive();
        future.get(5, TimeUnit.SECONDS);
        var raw = c.logs.get(0).read(GROUP, future.get().index());
        assertEquals(RaftGroup.USER, raw[0], "a STRICT group needs no per-entry marker");
        c.close();
    }

    /** A leader elected over an uncommitted STRICT entry (from an earlier term) still needs two data disks. */
    @Test
    void newLeaderKeepsAnUncommittedStrictEntryStrict() throws Exception {
        var n1 = new NodeId(1, "n1");
        var n2 = new NodeId(2, "n2");
        var n3 = new NodeId(3, "n3");
        var storage = new MemoryLogStorage();
        storage.saveHardState(GROUP, 1, 0);
        storage.append(GROUP, 1, 1, new byte[] {RaftGroup.USER_STRICT, 's'});
        var clock = new FakeClock(0L);
        var sent = new ArrayList<SimTransport.Frame>();
        var group = new RaftGroup(GROUP, n1, false, java.util.Set.of(n3), java.util.Set.of(n1, n2, n3),
                List.of(n2, n3), w -> w == 1 ? n1 : w == 2 ? n2 : w == 3 ? n3 : null, QuorumPolicy.majority(), opts(),
                storage, (to, frame) -> sent.add(frame), clock, null, 1024);
        clock.advance(Duration.ofSeconds(1)); // past the restart recovery window
        group.tick(clock.nanoTime());
        group.tick(clock.nanoTime());
        assertTrue(group.campaigning());
        group.handle(n3, reply(RaftGroup.PRE_VOTE_RESP, 1, 3, true), clock.nanoTime());
        group.handle(n3, reply(RaftGroup.VOTE_RESP, 2, 3, true), clock.nanoTime());
        assertTrue(group.facade().isLeader());
        var ack = ByteBuffer.allocate(21).putLong(2).putInt(3).put((byte) 1).putLong(2);
        group.handle(n3, new SimTransport.Frame(GROUP, RaftGroup.APPEND_RESP, ack.array()), clock.nanoTime());
        group.tick(clock.nanoTime()); // leader forces: a RELAXED entry would now commit on disk + witness
        assertEquals(0L, group.facade().commitIndex(), "one data disk + witness is not enough for STRICT");
        var ack2 = ByteBuffer.allocate(21).putLong(2).putInt(2).put((byte) 1).putLong(2);
        group.handle(n2, new SimTransport.Frame(GROUP, RaftGroup.APPEND_RESP, ack2.array()), clock.nanoTime());
        assertEquals(2L, group.facade().commitIndex());
        group.closeDeliveries(Duration.ofSeconds(1));
    }

    private static SimTransport.Frame reply(int type, long term, int wire, boolean grant) {
        var body = ByteBuffer.allocate(13).putLong(term).putInt(wire).put((byte) (grant ? 1 : 0));
        return new SimTransport.Frame(GROUP, type, body.array());
    }

    @Test
    void defaultOverloadDelegatesNullAndRefusesAnOverride() throws Exception {
        var calls = new ArrayList<String>();
        ClusterNode node = new ClusterNode() {
            @Override
            public NodeId self() {
                return new NodeId(1, "n1");
            }

            @Override
            public MembershipView membership() {
                return null;
            }

            @Override
            public void onMembershipChange(Consumer<MembershipView> listener) {
            }

            @Override
            public ReplicationGroup openGroup(GroupId groupId, GroupOptions options, GroupListener listener) {
                return null;
            }

            @Override
            public CompletableFuture<CommittedIndex> append(GroupId groupId, List<ByteBuffer> batch) {
                calls.add("plain");
                return CompletableFuture.completedFuture(new CommittedIndex(groupId, 1, 1));
            }

            @Override
            public void onLeaderChange(GroupId groupId, BiConsumer<Long, Optional<NodeId>> listener) {
            }

            @Override
            public void onNoopApplied(GroupId groupId, BiConsumer<Long, Long> listener) {
            }

            @Override
            public SnapshotControl snapshots(GroupId groupId) {
                return null;
            }

            @Override
            public void close() {
            }
        };
        assertEquals(1L, node.append(GROUP, List.of(payload("a")), null).get().index());
        assertEquals(List.of("plain"), calls);
        var refused = node.append(GROUP, List.of(payload("a")), Durability.STRICT);
        var error = org.junit.jupiter.api.Assertions.assertThrows(ExecutionException.class, refused::get);
        assertInstanceOf(UnsupportedOperationException.class, error.getCause());
        assertArrayEquals(new String[] {"plain"}, calls.toArray(new String[0]));
    }

    private static GroupOptions opts() {
        return new GroupOptions(Durability.RELAXED, 1024, Duration.ofMillis(100), Duration.ofMillis(40), 1_000_000);
    }

    private static ByteBuffer payload(String text) {
        return ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
    }

    private static Member member(int wire, boolean witness) {
        return new Member(new NodeId(wire, "n" + wire), new SiteId("local"),
                new InetSocketAddress("127.0.0.1", 23_200 + wire), 1L, MemberState.ALIVE, witness);
    }
}
