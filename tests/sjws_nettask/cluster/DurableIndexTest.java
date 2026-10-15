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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** durableIndex and the configurable RELAXED fsync-lag bound. */
@Timeout(30)
class DurableIndexTest {

    private static final GroupId GROUP = new GroupId("msg/2");
    private static final GroupOptions BASE = new GroupOptions(Durability.RELAXED, 1024, Duration.ofMillis(100),
            Duration.ofMillis(40), 1_000_000);

    private record Cluster(FakeClock clock, SimTransport net, List<ReplicatedNode> nodes, List<ReplicationGroup> groups,
            List<MemoryLogStorage> logs) {
        void drive() {
            ReplicatedNode.driveAll(nodes);
        }

        /** Deliver frames without ticking, so only inline forces happen. */
        void deliver() {
            while (net.deliver(64) > 0) {
                // drain
            }
        }

        void close() {
            nodes.forEach(ReplicatedNode::close);
        }
    }

    private static Cluster cluster(GroupOptions opts) {
        var clock = new FakeClock(0L);
        var net = new SimTransport();
        var members = List.of(member(1, false), member(2, false), member(3, true));
        var voters = VoterSet.dataAndWitness(members.get(0), members.get(1), members.get(2));
        var nodes = new ArrayList<ReplicatedNode>();
        var groups = new ArrayList<ReplicationGroup>();
        var logs = new ArrayList<MemoryLogStorage>();
        for (var member : members) {
            var log = new MemoryLogStorage();
            logs.add(log);
            var node = ReplicatedNode.open(member.id(), voters, clock, log, net);
            groups.add(node.openGroup(GROUP, opts, null));
            nodes.add(node);
        }
        var c = new Cluster(clock, net, nodes, groups, logs);
        clock.advance(Duration.ofMillis(100));
        c.drive();
        assertTrue(groups.get(0).isLeader());
        return c;
    }

    private static CompletableFuture<CommittedIndex> append(Cluster c, String text) {
        return c.nodes.get(0).append(GROUP, List.of(ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void relaxedDefaultSyncsOnTheNextTickAndWitnessReportsZero() throws Exception {
        var c = cluster(BASE);
        c.drive();
        long before = c.groups.get(0).durableIndex();
        var f = append(c, "a");
        c.deliver();
        long index = f.get(5, TimeUnit.SECONDS).index();
        assertEquals(before, c.groups.get(0).durableIndex(), "acked from memory, not yet fsynced");
        assertTrue(c.groups.get(1).durableIndex() < index);
        c.drive();
        assertEquals(index, c.groups.get(0).durableIndex());
        assertEquals(index, c.groups.get(1).durableIndex());
        assertEquals(0L, c.groups.get(2).durableIndex(), "the witness stores no entry");
        c.close();
    }

    @Test
    void strictIsDurableBeforeTheAck() throws Exception {
        var c = cluster(new GroupOptions(Durability.STRICT, 1024, Duration.ofMillis(100), Duration.ofMillis(40),
                1_000_000));
        var f = append(c, "a");
        c.deliver();
        long index = f.get(5, TimeUnit.SECONDS).index();
        assertEquals(index, c.groups.get(0).durableIndex());
        assertEquals(index, c.groups.get(1).durableIndex());
        c.close();
    }

    @Test
    void delayBoundHoldsFsyncUntilTheOldestEntryIsThatOld() throws Exception {
        var c = cluster(BASE.withRelaxedSyncBound(Duration.ofMillis(30), Long.MAX_VALUE));
        c.clock.advance(Duration.ofMillis(30));
        c.drive(); // flush the election no-op
        var f = append(c, "a");
        c.drive();
        long index = f.get(5, TimeUnit.SECONDS).index();
        assertTrue(c.groups.get(0).durableIndex() < index, "inside the 30 ms bound");
        c.clock.advance(Duration.ofMillis(29));
        c.drive();
        assertTrue(c.groups.get(0).durableIndex() < index);
        c.clock.advance(Duration.ofMillis(1));
        c.drive();
        assertEquals(index, c.groups.get(0).durableIndex(), "fsynced once the bound is reached");
        assertEquals(index, c.groups.get(1).durableIndex());
        c.close();
    }

    @Test
    void entryBoundForcesInsideTheAppendAndTheAck() throws Exception {
        var c = cluster(BASE.withRelaxedSyncBound(Duration.ofHours(1), 3));
        long start = c.groups.get(0).durableIndex();
        CompletableFuture<CommittedIndex> third = null;
        for (var text : List.of("a", "b", "c")) {
            third = append(c, text);
            assertTrue(c.logs.get(0).lastIndex(GROUP) - c.groups.get(0).durableIndex() < 3,
                    "the leader never holds 3 unsynced entries");
        }
        assertTrue(c.groups.get(0).durableIndex() > start, "forced inside an append, no tick ran");
        c.deliver();
        third.get(5, TimeUnit.SECONDS);
        assertTrue(c.logs.get(1).lastIndex(GROUP) - c.groups.get(1).durableIndex() < 3,
                "the follower never holds 3 unsynced entries");
        c.close();
    }

    @Test
    void optionsValidateTheBoundAndKeepItAcrossWithers() {
        assertThrows(IllegalArgumentException.class, () -> BASE.withRelaxedSyncBound(Duration.ofMillis(-1), 1));
        assertThrows(IllegalArgumentException.class, () -> BASE.withRelaxedSyncBound(Duration.ZERO, 0));
        assertThrows(NullPointerException.class, () -> BASE.withRelaxedSyncBound(null, 1));
        assertEquals(Duration.ZERO, BASE.relaxedSyncDelay());
        assertEquals(Long.MAX_VALUE, BASE.relaxedSyncEntries());
        var bound = BASE.withRelaxedSyncBound(Duration.ofMillis(5), 64);
        var kept = bound.withPreferred(Duration.ofMillis(1)).withFailback(Duration.ofMillis(2));
        assertEquals(Duration.ofMillis(5), kept.relaxedSyncDelay());
        assertEquals(64L, kept.relaxedSyncEntries());
    }

    @Test
    void defaultDurableIndexIsZero() {
        ReplicationGroup bare = new ReplicationGroup() {
            @Override
            public GroupId groupId() {
                return GROUP;
            }

            @Override
            public long term() {
                return 0;
            }

            @Override
            public Optional<NodeId> leader() {
                return Optional.empty();
            }

            @Override
            public boolean isLeader() {
                return false;
            }

            @Override
            public long leaseExpiresAt() {
                return 0;
            }

            @Override
            public long commitIndex() {
                return 0;
            }

            @Override
            public long lastApplied() {
                return 0;
            }

            @Override
            public CompletableFuture<Void> readBarrier() {
                return null;
            }

            @Override
            public CompletableFuture<Void> changeMembership(java.util.Set<NodeId> voters, java.util.Set<NodeId> learners) {
                return null;
            }

            @Override
            public void transferLeadership(NodeId target) {
            }
        };
        assertEquals(0L, bare.durableIndex());
    }

    private static Member member(int wire, boolean witness) {
        return new Member(new NodeId(wire, "n" + wire), new SiteId("local"),
                new InetSocketAddress("127.0.0.1", 23_300 + wire), 1L, MemberState.ALIVE, witness);
    }
}
