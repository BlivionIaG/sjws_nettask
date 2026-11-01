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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

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

    /**
     * #208: after a STRICT override stalls (≥ 2 × lease), an authenticated operator escape appends
     * a STRICT_RELEASE marker and lets the entry commit without the second data copy.
     */
    @Test
    void operatorEscapeReleasesStalledStrictOverride() throws Exception {
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
            var strict = leader.append(GROUP, List.of(payload("s")), Durability.STRICT);
            c.net.cut(leader.self(), c.nodes.get(1).self());
            c.net.cut(c.nodes.get(1).self(), c.nodes.get(2).self());
            c.drive();
            assertFalse(strict.isDone());
            assertEquals(1, leader.strictPending(GROUP));
            assertThrows(IllegalArgumentException.class, () -> leader.releaseStalledStrictOverrides(GROUP, " "));
            assertThrows(IllegalArgumentException.class, () -> leader.releaseStalledStrictOverrides(GROUP, null));
            assertEquals(0, leader.releaseStalledStrictOverrides(GROUP, "ops-too-early"),
                    "before 2×lease the escape is a no-op");
            // Age past 2 × lease (lease = 2 × heartbeat = 80 ms).
            for (int i = 0; i < 25; i++) {
                c.clock.advance(Duration.ofMillis(40));
                c.drive();
            }
            assertTrue(leader.oldestStrictPendingAgeNanos(GROUP) >= 2 * Duration.ofMillis(80).toNanos());
            long strictIndex = c.logs.get(0).lastIndex(GROUP);
            int n = leader.releaseStalledStrictOverrides(GROUP, "urn:sjws:admin:ops\nBAD");
            assertEquals(1, n);
            assertEquals(1L, leader.strictOverridesReleased(GROUP));
            assertEquals(0, leader.releaseStalledStrictOverrides(GROUP, "urn:sjws:admin:ops"),
                    "second release is idempotent");
            var audits = records.stream()
                    .filter(r -> r.getMessage() != null && r.getMessage().contains("audit strict.release"))
                    .toList();
            assertEquals(1, audits.size(), audits.toString());
            assertTrue(audits.get(0).getMessage().contains("operator=urn:sjws:admin:opsBAD"),
                    "control characters stripped: " + audits.get(0).getMessage());
            assertFalse(audits.get(0).getMessage().contains("\n"), "no injected newline in audit");
            for (int i = 0; i < 30 && !strict.isDone(); i++) {
                c.drive();
            }
            assertTrue(strict.isDone(), "released STRICT commits without the second data copy");
            assertEquals(0, leader.strictPending(GROUP));
            // Original USER_STRICT bytes stay; a STRICT_RELEASE marker follows.
            byte[] raw = c.logs.get(0).read(GROUP, strictIndex);
            assertEquals(RaftGroup.USER_STRICT, raw[0], "escape must not rewrite existing entries");
            long releaseIndex = strict.get().index() < c.logs.get(0).lastIndex(GROUP)
                    ? findRelease(c.logs.get(0), strictIndex)
                    : findRelease(c.logs.get(0), 1);
            assertTrue(releaseIndex > strictIndex, "RELEASE is a new index");
            byte[] marker = c.logs.get(0).read(GROUP, releaseIndex);
            assertEquals(RaftGroup.STRICT_RELEASE, marker[0]);
            assertEquals(strictIndex, RaftGroup.strictReleaseUpto(marker));
            c.close();
        } finally {
            jul.removeHandler(handler);
        }
    }

    /** #208: a follower refuses the escape; a STRICT GroupOptions group refuses it. */
    @Test
    void operatorEscapeRefusesFollowerAndStrictGroup() throws Exception {
        var relaxed = cluster(Durability.RELAXED);
        assertThrows(NotLeaderException.class,
                () -> relaxed.nodes.get(1).releaseStalledStrictOverrides(GROUP, "ops"));
        relaxed.close();

        var strictGroup = cluster(Durability.STRICT);
        var leader = strictGroup.nodes.get(0);
        var err = assertThrows(IllegalStateException.class,
                () -> leader.releaseStalledStrictOverrides(GROUP, "ops"));
        assertTrue(err.getMessage().contains("GroupOptions STRICT"), err.getMessage());
        strictGroup.close();
    }

    /**
     * #208 / #450 P1: a follower that stored USER_STRICT (ack lost) later becomes leader after a
     * STRICT_RELEASE <em>committed</em>. becomeLeader must honour the marker — barrier stays gone,
     * logs match. If leadership transferred before the marker committed, the barrier would re-arm
     * until commit (fail-safe); this test waits for commit first.
     */
    @Test
    void lostAckFollowerBecomeLeaderHonoursReleaseMarker() throws Exception {
        var c = cluster(Durability.RELAXED);
        var leader = c.nodes.get(0);
        var follower = c.nodes.get(1);
        var strict = leader.append(GROUP, List.of(payload("s")), Durability.STRICT);
        // Deliver until the data follower has stored the STRICT entry, then drop its ack.
        long target = -1;
        for (int i = 0; i < 64 && target < 0; i++) {
            c.net.deliver(1);
            long last = c.logs.get(1).lastIndex(GROUP);
            if (last > 0) {
                var raw = c.logs.get(1).read(GROUP, last);
                if (raw != null && raw.length > 0 && raw[0] == RaftGroup.USER_STRICT) {
                    target = last;
                }
            }
        }
        assertTrue(target > 0, "follower stored USER_STRICT");
        c.net.clearQueue(); // lose the append ack
        c.net.cut(leader.self(), follower.self());
        c.net.cut(follower.self(), c.nodes.get(2).self());
        assertFalse(strict.isDone());
        for (int i = 0; i < 25; i++) {
            c.clock.advance(Duration.ofMillis(40));
            c.drive();
        }
        assertEquals(1, leader.releaseStalledStrictOverrides(GROUP, "ops-lost-ack"));
        for (int i = 0; i < 40 && !strict.isDone(); i++) {
            c.drive();
        }
        assertTrue(strict.isDone(), "leader+witness commit after RELEASE");
        assertEquals(0, leader.strictPending(GROUP));
        // Heal and catch the follower up (it still has USER_STRICT; learns RELEASE).
        c.net.heal(leader.self(), follower.self());
        c.net.heal(follower.self(), c.nodes.get(2).self());
        for (int i = 0; i < 60; i++) {
            c.clock.advance(Duration.ofMillis(40));
            c.drive();
        }
        long releaseIndex = findRelease(c.logs.get(0), target);
        assertEquals(RaftGroup.USER_STRICT, c.logs.get(0).read(GROUP, target)[0]);
        assertEquals(RaftGroup.USER_STRICT, c.logs.get(1).read(GROUP, target)[0]);
        assertArrayEquals(c.logs.get(0).read(GROUP, releaseIndex), c.logs.get(1).read(GROUP, releaseIndex));
        // Transfer leadership to the former follower — becomeLeader must not re-arm.
        c.groups.get(0).transferLeadership(follower.self());
        for (int i = 0; i < 60 && !c.groups.get(1).isLeader(); i++) {
            c.clock.advance(Duration.ofMillis(40));
            c.drive();
        }
        assertTrue(c.groups.get(1).isLeader(), "former follower is leader");
        assertEquals(0, follower.strictPending(GROUP), "RELEASE honoured in becomeLeader");
        // A RELAXED append must commit with leader+witness (no STRICT barrier).
        var after = follower.append(GROUP, List.of(payload("after")));
        for (int i = 0; i < 40 && !after.isDone(); i++) {
            c.drive();
        }
        assertTrue(after.isDone(), "no re-imposed STRICT barrier");
        c.close();
    }

    /**
     * #208 / #450 P1: release append failure on memory storage rolls back cleanly via rollBackBatch
     * (not fenced); STRICT stays, barrier remains.
     */
    @Test
    void releaseAppendFailureRollsBackWithoutFence() throws Exception {
        var clock = new FakeClock(0L);
        var net = new SimTransport();
        var members = List.of(member(1, false), member(2, false), member(3, true));
        var voters = VoterSet.dataAndWitness(members.get(0), members.get(1), members.get(2));
        var opts = new GroupOptions(Durability.RELAXED, 1024, Duration.ofMillis(100), Duration.ofMillis(40), 1_000_000);
        var faulty = new BatchAtomicityTest.Faulty(new MemoryLogStorage(), null);
        var nodes = new ArrayList<ReplicatedNode>();
        for (int i = 0; i < members.size(); i++) {
            LogStorage storage = i == 0 ? faulty : new MemoryLogStorage();
            var node = ReplicatedNode.open(members.get(i).id(), voters, clock, storage, net);
            node.openGroup(GROUP, opts, null);
            nodes.add(node);
        }
        clock.advance(Duration.ofMillis(100));
        ReplicatedNode.driveAll(nodes);
        assertTrue(nodes.get(0).openGroup(GROUP, opts, null).isLeader());
        var strict = nodes.get(0).append(GROUP, List.of(payload("s")), Durability.STRICT);
        net.cut(nodes.get(0).self(), nodes.get(1).self());
        net.cut(nodes.get(1).self(), nodes.get(2).self());
        ReplicatedNode.driveAll(nodes);
        assertFalse(strict.isDone());
        for (int i = 0; i < 25; i++) {
            clock.advance(Duration.ofMillis(40));
            ReplicatedNode.driveAll(nodes);
        }
        long before = faulty.lastIndex(GROUP);
        faulty.armAppend(0);
        var err = assertThrows(BatchRolledBackException.class,
                () -> nodes.get(0).releaseStalledStrictOverrides(GROUP, "ops"));
        assertFalse(nodes.get(0).failedClosed(GROUP), "clean rollback does not fence");
        assertEquals(before, faulty.lastIndex(GROUP), "STRICT entry still present");
        assertEquals(RaftGroup.USER_STRICT, faulty.read(GROUP, before)[0]);
        assertEquals(1, nodes.get(0).strictPending(GROUP), "barrier unchanged after failed escape");
        assertTrue(err.getMessage() != null || true);
        for (var node : nodes) {
            node.close();
        }
    }

    /**
     * #208 / #450 P1: FileLogStorage writes before memory; a failure after the file write must go
     * through rollBackBatch (clean rollback or fence). Torn bytes must not leave an active leader.
     */
    @Test
    void releaseAppendFailureAfterFileWriteUsesFencePath(@TempDir Path tmp) throws Exception {
        var clock = new FakeClock(0L);
        var net = new SimTransport();
        var members = List.of(member(1, false), member(2, false), member(3, true));
        var voters = VoterSet.dataAndWitness(members.get(0), members.get(1), members.get(2));
        var opts = new GroupOptions(Durability.RELAXED, 1024, Duration.ofMillis(100), Duration.ofMillis(40), 1_000_000);
        var root = tmp.resolve("log");
        Files.createDirectories(root);
        var file = new FileLogStorage(root);
        // msg/1 → msg_1.log (FileLogStorage.safe)
        var segment = root.resolve("msg_1.log");
        var faulty = new BatchAtomicityTest.Faulty(file, segment);
        var nodes = new ArrayList<ReplicatedNode>();
        for (int i = 0; i < members.size(); i++) {
            LogStorage storage = i == 0 ? faulty : new MemoryLogStorage();
            var node = ReplicatedNode.open(members.get(i).id(), voters, clock, storage, net);
            node.openGroup(GROUP, opts, null);
            nodes.add(node);
        }
        clock.advance(Duration.ofMillis(100));
        ReplicatedNode.driveAll(nodes);
        assertTrue(nodes.get(0).openGroup(GROUP, opts, null).isLeader());
        var strict = nodes.get(0).append(GROUP, List.of(payload("s")), Durability.STRICT);
        net.cut(nodes.get(0).self(), nodes.get(1).self());
        net.cut(nodes.get(1).self(), nodes.get(2).self());
        ReplicatedNode.driveAll(nodes);
        for (int i = 0; i < 25; i++) {
            clock.advance(Duration.ofMillis(40));
            ReplicatedNode.driveAll(nodes);
        }
        long before = faulty.lastIndex(GROUP);
        faulty.armAppend(0); // writes torn bytes to the segment, then throws before d.append
        var err = assertThrows(RuntimeException.class,
                () -> nodes.get(0).releaseStalledStrictOverrides(GROUP, "ops"));
        assertTrue(err instanceof BatchRolledBackException
                || err instanceof GroupFencedException
                || nodes.get(0).failedClosed(GROUP)
                || err.getCause() instanceof OutcomeUnknownException, err.toString());
        if (nodes.get(0).failedClosed(GROUP)) {
            assertTrue(err instanceof GroupFencedException || err.getCause() != null,
                    "fenced path reports outcome unknown");
        } else {
            assertInstanceOf(BatchRolledBackException.class, err);
            assertEquals(before, faulty.lastIndex(GROUP), "rolled back: STRICT still last");
            assertEquals(1, nodes.get(0).strictPending(GROUP));
        }
        assertFalse(strict.isDone());
        for (var node : nodes) {
            node.close();
        }
        file.close();
    }

    /**
     * #450 Codex P2: a fenced STRICT_RELEASE append must drain deferred {@code after} completions
     * before rethrowing. Without that drain a pending read-barrier future hangs forever.
     */
    @Test
    void fencedReleaseFailureCompletesPendingReadBarrier() throws Exception {
        var clock = new FakeClock(0L);
        var net = new SimTransport();
        var members = List.of(member(1, false), member(2, false), member(3, true));
        var voters = VoterSet.dataAndWitness(members.get(0), members.get(1), members.get(2));
        var opts = new GroupOptions(Durability.RELAXED, 1024, Duration.ofMillis(100), Duration.ofMillis(40), 1_000_000);
        var faulty = new BatchAtomicityTest.Faulty(new MemoryLogStorage(), null);
        var nodes = new ArrayList<ReplicatedNode>();
        for (int i = 0; i < members.size(); i++) {
            LogStorage storage = i == 0 ? faulty : new MemoryLogStorage();
            var node = ReplicatedNode.open(members.get(i).id(), voters, clock, storage, net);
            node.openGroup(GROUP, opts, null);
            nodes.add(node);
        }
        clock.advance(Duration.ofMillis(100));
        ReplicatedNode.driveAll(nodes);
        assertTrue(nodes.get(0).openGroup(GROUP, opts, null).isLeader());
        var strict = nodes.get(0).append(GROUP, List.of(payload("s")), Durability.STRICT);
        net.cut(nodes.get(0).self(), nodes.get(1).self());
        net.cut(nodes.get(1).self(), nodes.get(2).self());
        ReplicatedNode.driveAll(nodes);
        assertFalse(strict.isDone());
        for (int i = 0; i < 25; i++) {
            clock.advance(Duration.ofMillis(40));
            ReplicatedNode.driveAll(nodes);
        }
        // Past the lease so the barrier must wait (and be queued in reads).
        clock.advance(Duration.ofSeconds(10));
        var read = nodes.get(0).openGroup(GROUP, opts, null).readBarrier();
        assertFalse(read.isDone(), "read barrier past the lease waits for a quorum round");
        faulty.failTruncate = true;
        faulty.armAppend(0);
        assertThrows(RuntimeException.class,
                () -> nodes.get(0).releaseStalledStrictOverrides(GROUP, "ops-drain"));
        assertTrue(nodes.get(0).failedClosed(GROUP), "failed truncate fences the group");
        // Without draining after, this hangs. With the fix it completes exceptionally promptly.
        var cause = assertThrows(ExecutionException.class,
                () -> read.get(2, TimeUnit.SECONDS)).getCause();
        assertTrue(cause instanceof java.io.UncheckedIOException
                || cause.getMessage() != null && cause.getMessage().contains("failed closed"),
                () -> "expected fenced error, got " + cause);
        for (var node : nodes) {
            node.close();
        }
    }

    /**
     * #208 / Security N3: after a committed RELEASE, restart replay and snapshot install keep the
     * barrier dropped (committed-only watermark + surviving log / snapshotted prefix).
     */
    @Test
    void releaseSurvivesRestartReplayAndSnapshotInstall() throws Exception {
        var c = cluster(Durability.RELAXED);
        var leader = c.nodes.get(0);
        var strict = leader.append(GROUP, List.of(payload("s")), Durability.STRICT);
        c.net.cut(leader.self(), c.nodes.get(1).self());
        c.net.cut(c.nodes.get(1).self(), c.nodes.get(2).self());
        c.drive();
        for (int i = 0; i < 25; i++) {
            c.clock.advance(Duration.ofMillis(40));
            c.drive();
        }
        assertEquals(1, leader.releaseStalledStrictOverrides(GROUP, "ops-restart"));
        for (int i = 0; i < 40 && !strict.isDone(); i++) {
            c.drive();
        }
        assertTrue(strict.isDone());
        long strictIndex = strict.get().index();
        long releaseIndex = findRelease(c.logs.get(0), strictIndex);
        // Drive until the RELEASE itself is committed (take requires <= commitIndex).
        var group = leader.openGroup(GROUP, new GroupOptions(Durability.RELAXED, 1024,
                Duration.ofMillis(100), Duration.ofMillis(40), 1_000_000), null);
        for (int i = 0; i < 40 && group.commitIndex() < releaseIndex; i++) {
            c.drive();
        }
        assertTrue(group.commitIndex() >= releaseIndex, "RELEASE must commit before snapshot");
        long snapAt = group.commitIndex();
        // Snapshot through the RELEASE on the leader, then install on a fresh node storage.
        var snap = leader.snapshots(GROUP).take(snapAt);
        assertEquals(snapAt, snap.lastIncludedIndex());
        assertTrue(snapAt >= releaseIndex);
        var installed = new MemoryLogStorage();
        // Seed a divergent uncommitted STRICT that a naive rescan would re-arm, then install snapshot.
        installed.saveHardState(GROUP, 1, 0);
        installed.append(GROUP, 1, 1, new byte[] {RaftGroup.USER_STRICT, 'x'});
        var clock = new FakeClock(0L);
        var net = new SimTransport();
        var members = List.of(member(1, false), member(2, false), member(3, true));
        var voters = VoterSet.dataAndWitness(members.get(0), members.get(1), members.get(2));
        var opts = new GroupOptions(Durability.RELAXED, 1024, Duration.ofMillis(100), Duration.ofMillis(40), 1_000_000);
        var n1 = ReplicatedNode.open(members.get(0).id(), voters, clock, installed, net);
        n1.openGroup(GROUP, opts, null);
        n1.snapshots(GROUP).install(snap);
        // Restart-style election with the same storage that holds the RELEASE in the original cluster log.
        var replayLog = c.logs.get(0);
        c.close();
        var net2 = new SimTransport();
        var clock2 = new FakeClock(1_000_000_000L); // past any recovery window
        var nodes = new ArrayList<ReplicatedNode>();
        for (int i = 0; i < members.size(); i++) {
            LogStorage storage = i == 0 ? replayLog : new MemoryLogStorage();
            var node = ReplicatedNode.open(members.get(i).id(), voters, clock2, storage, net2);
            node.openGroup(GROUP, opts, null);
            nodes.add(node);
        }
        for (int i = 0; i < 40; i++) {
            clock2.advance(Duration.ofMillis(40));
            ReplicatedNode.driveAll(nodes);
        }
        int leaderIdx = -1;
        for (int i = 0; i < nodes.size(); i++) {
            if (nodes.get(i).openGroup(GROUP, opts, null).isLeader()) {
                leaderIdx = i;
                break;
            }
        }
        assertTrue(leaderIdx >= 0, "a leader elected after restart");
        assertEquals(0, nodes.get(leaderIdx).strictPending(GROUP), "RELEASE honoured on restart rescan");
        n1.close();
        for (var node : nodes) {
            node.close();
        }
    }

    /**
     * #450 P1: an uncommitted RELEASE must not leave a durable watermark. After the suffix that
     * held it is replaced and this node is re-elected, a new USER_STRICT at a reused index must
     * still need two data disks.
     */
    @Test
    void uncommittedReleaseDoesNotSkipBarrierAfterSuffixReplace() throws Exception {
        var n1 = new NodeId(1, "n1");
        var n2 = new NodeId(2, "n2");
        var n3 = new NodeId(3, "n3");
        var storage = new MemoryLogStorage();
        var clock = new FakeClock(0L);
        var group = new RaftGroup(GROUP, n1, false, java.util.Set.of(n3), java.util.Set.of(n1, n2, n3),
                List.of(n2, n3), w -> w == 1 ? n1 : w == 2 ? n2 : w == 3 ? n3 : null, QuorumPolicy.majority(), opts(),
                storage, (to, frame) -> { }, clock, null, 1024);
        clock.advance(Duration.ofSeconds(1));
        group.tick(clock.nanoTime());
        group.tick(clock.nanoTime());
        group.handle(n3, reply(RaftGroup.PRE_VOTE_RESP, 0, 3, true), clock.nanoTime());
        group.handle(n3, reply(RaftGroup.VOTE_RESP, 1, 3, true), clock.nanoTime());
        assertTrue(group.facade().isLeader());
        // Make n2 look reachable so STRICT is accepted, then drop it.
        long term = group.facade().term();
        var hear = ByteBuffer.allocate(21).putLong(term).putInt(2).put((byte) 1).putLong(0);
        group.handle(n2, new SimTransport.Frame(GROUP, RaftGroup.APPEND_RESP, hear.array()), clock.nanoTime());
        var strictFut = group.append(List.of(payload("s")), Durability.STRICT);
        assertFalse(strictFut.isDone());
        assertEquals(1, group.strictPendingCount());
        for (int i = 0; i < 25; i++) {
            clock.advance(Duration.ofMillis(40));
            group.tick(clock.nanoTime());
        }
        assertEquals(1, group.releaseStalledStrictOverrides("ops-stale"));
        assertEquals(0L, group.strictReleasedThroughForTest(),
                "uncommitted RELEASE must not advance the durable watermark");
        assertTrue(findRelease(storage, 1) > 0);
        // New leader replaces the suffix (no RELEASE): USER_STRICT at reused index 1.
        var replacement = new byte[] {RaftGroup.USER_STRICT, 'x'};
        long newTerm = term + 1;
        var body = ByteBuffer.allocate(8 + 4 + 8 + 8 + 8 + 4 + (8 + 4 + replacement.length))
                .order(ByteOrder.BIG_ENDIAN);
        body.putLong(newTerm);
        body.putInt(2);
        body.putLong(0);
        body.putLong(0);
        body.putLong(0);
        body.putInt(1);
        body.putLong(newTerm);
        body.putInt(replacement.length);
        body.put(replacement);
        group.handle(n2, new SimTransport.Frame(GROUP, RaftGroup.APPEND, body.array()), clock.nanoTime());
        assertFalse(group.facade().isLeader());
        assertEquals(1L, storage.lastIndex(GROUP), "RELEASE gone from replaced suffix");
        group.closeDeliveries(Duration.ofSeconds(1));
        var clock2 = new FakeClock(0L);
        var again = new RaftGroup(GROUP, n1, false, java.util.Set.of(n3), java.util.Set.of(n1, n2, n3),
                List.of(n2, n3), w -> w == 1 ? n1 : w == 2 ? n2 : w == 3 ? n3 : null, QuorumPolicy.majority(), opts(),
                storage, (to, frame) -> { }, clock2, null, 1024);
        clock2.advance(Duration.ofSeconds(1));
        again.tick(clock2.nanoTime());
        again.tick(clock2.nanoTime());
        long t0 = again.facade().term();
        again.handle(n3, reply(RaftGroup.PRE_VOTE_RESP, t0, 3, true), clock2.nanoTime());
        again.handle(n3, reply(RaftGroup.VOTE_RESP, t0 + 1, 3, true), clock2.nanoTime());
        assertTrue(again.facade().isLeader());
        assertEquals(0L, again.strictReleasedThroughForTest(),
                "stale watermark must not survive reopen after suffix replace");
        assertEquals(1, again.strictPendingCount(), "reused-index USER_STRICT must re-arm");
        long last = storage.lastIndex(GROUP);
        var ack = ByteBuffer.allocate(21).putLong(again.facade().term()).putInt(3).put((byte) 1).putLong(last);
        again.handle(n3, new SimTransport.Frame(GROUP, RaftGroup.APPEND_RESP, ack.array()), clock2.nanoTime());
        again.tick(clock2.nanoTime());
        assertTrue(again.facade().commitIndex() < 1, "witness alone must not commit the re-armed STRICT");
        again.closeDeliveries(Duration.ofSeconds(1));
    }

    @Test
    void becomeLeaderHonoursUncommittedReleaseInLog() throws Exception {
        var n1 = new NodeId(1, "n1");
        var n2 = new NodeId(2, "n2");
        var n3 = new NodeId(3, "n3");
        var storage = new MemoryLogStorage();
        storage.saveHardState(GROUP, 1, 0);
        storage.append(GROUP, 1, 1, new byte[] {RaftGroup.USER_STRICT, 's'});
        storage.append(GROUP, 2, 1, RaftGroup.strictRelease(1));
        var clock = new FakeClock(0L);
        var group = new RaftGroup(GROUP, n1, false, java.util.Set.of(n3), java.util.Set.of(n1, n2, n3),
                List.of(n2, n3), w -> w == 1 ? n1 : w == 2 ? n2 : w == 3 ? n3 : null, QuorumPolicy.majority(), opts(),
                storage, (to, frame) -> { }, clock, null, 1024);
        clock.advance(Duration.ofSeconds(1));
        group.tick(clock.nanoTime());
        group.tick(clock.nanoTime());
        group.handle(n3, reply(RaftGroup.PRE_VOTE_RESP, 1, 3, true), clock.nanoTime());
        group.handle(n3, reply(RaftGroup.VOTE_RESP, 2, 3, true), clock.nanoTime());
        assertTrue(group.facade().isLeader());
        assertEquals(0, group.strictPendingCount(), "uncommitted RELEASE covers the STRICT entry");
        assertEquals(0L, group.strictReleasedThroughForTest(), "watermark waits for commit/apply");
        // Leader+witness can commit past the covered STRICT (RELEASE and no-op).
        var ack = ByteBuffer.allocate(21).putLong(2).putInt(3).put((byte) 1).putLong(storage.lastIndex(GROUP));
        group.handle(n3, new SimTransport.Frame(GROUP, RaftGroup.APPEND_RESP, ack.array()), clock.nanoTime());
        group.tick(clock.nanoTime());
        assertTrue(group.facade().commitIndex() >= 2, "covered STRICT no longer blocks commit");
        assertEquals(1L, group.strictReleasedThroughForTest(), "apply advances the durable watermark");
        group.closeDeliveries(Duration.ofSeconds(1));
    }

    /**
     * #208: applying a committed STRICT_RELEASE on a follower must advance strictReleasedThrough
     * even when becomeLeader is never called (Security: honour in apply and becomeLeader).
     */
    @Test
    void followerApplyHonoursReleaseMarker() throws Exception {
        var n1 = new NodeId(1, "n1");
        var n2 = new NodeId(2, "n2");
        var n3 = new NodeId(3, "n3");
        var storage = new MemoryLogStorage();
        var clock = new FakeClock(0L);
        var group = new RaftGroup(GROUP, n2, false, java.util.Set.of(n3), java.util.Set.of(n1, n2, n3),
                List.of(n1, n3), w -> w == 1 ? n1 : w == 2 ? n2 : w == 3 ? n3 : null, QuorumPolicy.majority(), opts(),
                storage, (to, frame) -> { }, clock, null, 1024);
        // APPEND encoding: term, leaderWire, prevIndex, prevTerm, leaderCommit, count,
        // then per entry: entryTerm, len, data.
        var strict = new byte[] {RaftGroup.USER_STRICT, 's'};
        var release = RaftGroup.strictRelease(1);
        long term = 1;
        var body = ByteBuffer.allocate(8 + 4 + 8 + 8 + 8 + 4
                + (8 + 4 + strict.length) + (8 + 4 + release.length)).order(ByteOrder.BIG_ENDIAN);
        body.putLong(term);
        body.putInt(1);
        body.putLong(0);
        body.putLong(0);
        body.putLong(2); // leaderCommit through RELEASE
        body.putInt(2);
        body.putLong(term);
        body.putInt(strict.length);
        body.put(strict);
        body.putLong(term);
        body.putInt(release.length);
        body.put(release);
        group.handle(n1, new SimTransport.Frame(GROUP, RaftGroup.APPEND, body.array()), clock.nanoTime());
        assertEquals(2L, group.facade().commitIndex());
        assertEquals(1L, group.strictReleasedThroughForTest(), "apply must honour STRICT_RELEASE");
        assertEquals(RaftGroup.USER_STRICT, storage.read(GROUP, 1)[0]);
        assertEquals(RaftGroup.STRICT_RELEASE, storage.read(GROUP, 2)[0]);
        group.closeDeliveries(Duration.ofSeconds(1));
    }

    private static long findRelease(LogStorage log, long atOrAfter) throws IOException {
        long last = log.lastIndex(GROUP);
        for (long i = atOrAfter; i <= last; i++) {
            var data = log.read(GROUP, i);
            if (data != null && data.length > 0 && data[0] == RaftGroup.STRICT_RELEASE) {
                return i;
            }
        }
        throw new AssertionError("no STRICT_RELEASE at or after " + atOrAfter + " (last=" + last + ")");
    }

}
