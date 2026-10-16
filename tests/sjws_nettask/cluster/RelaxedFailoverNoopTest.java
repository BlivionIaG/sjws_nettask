package sjws_nettask.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * RELAXED 2+1: when the leader dies, the other data node is elected with only the witness and must commit its election
 * no-op by itself (fsync on the next tick), before any user append. Messaging gates appends on that no-op.
 * Registration goes through {@link ClusterNode} (review-29 item 3b), not a cast to {@link ReplicatedNode}.
 */
@Timeout(30)
class RelaxedFailoverNoopTest {

    private static final GroupId GROUP = new GroupId("msg/t");

    @Test
    void newLeaderWithOnlyTheWitnessCommitsItsNoop() {
        var clock = new FakeClock(0L);
        var net = new SimTransport();
        var members = List.of(member(1, false), member(2, false), member(3, true));
        var voters = VoterSet.dataAndWitness(members.get(0), members.get(1), members.get(2));
        var opts = new GroupOptions(Durability.RELAXED, 1024, Duration.ofMillis(100), Duration.ofMillis(40), Long.MAX_VALUE);
        var nodes = new ArrayList<ReplicatedNode>();
        var readyTerm = new AtomicLong();
        var readyIndex = new AtomicLong();
        for (var m : members) {
            var node = ReplicatedNode.open(m.id(), voters, clock, new MemoryLogStorage(), net);
            node.openGroup(GROUP, opts, null);
            nodes.add(node);
        }
        // Public API: ClusterNode, not ReplicatedNode.
        ClusterNode viaApi = nodes.get(1);
        viaApi.onNoopApplied(GROUP, (term, index) -> {
            readyTerm.set(term);
            readyIndex.set(index);
        });
        clock.advance(Duration.ofMillis(100));
        ReplicatedNode.driveAll(nodes);
        var first = nodes.get(0).openGroup(GROUP, opts, null);
        assertTrue(first.isLeader());
        net.cut(nodes.get(0).self(), nodes.get(1).self());
        net.cut(nodes.get(0).self(), nodes.get(2).self());
        var second = nodes.get(1).openGroup(GROUP, opts, null);
        for (int k = 0; k < 40 && !(second.isLeader() && readyTerm.get() == second.term()); k++) {
            clock.advance(Duration.ofMillis(100));
            ReplicatedNode.driveAll(nodes);
        }
        assertTrue(second.isLeader());
        assertEquals(second.term(), readyTerm.get(), "election no-op never committed");
        assertTrue(readyIndex.get() > 0, "applied index from onNoopApplied");
        assertEquals(second.commitIndex(), second.lastApplied());
    }

    @Test
    void clusterNodeOnNoopAppliedFiresAfterFirstElection() {
        var clock = new FakeClock(0L);
        var net = new SimTransport();
        var members = List.of(member(1, false), member(2, false), member(3, true));
        var voters = VoterSet.dataAndWitness(members.get(0), members.get(1), members.get(2));
        var opts = new GroupOptions(Durability.RELAXED, 1024, Duration.ofMillis(100), Duration.ofMillis(40), Long.MAX_VALUE);
        var nodes = new ArrayList<ReplicatedNode>();
        var readyTerm = new AtomicLong(-1);
        var readyIndex = new AtomicLong(-1);
        for (var m : members) {
            var node = ReplicatedNode.open(m.id(), voters, clock, new MemoryLogStorage(), net);
            node.openGroup(GROUP, opts, null);
            nodes.add(node);
        }
        ClusterNode leaderCandidate = nodes.get(0);
        leaderCandidate.onNoopApplied(GROUP, (term, index) -> {
            readyTerm.set(term);
            readyIndex.set(index);
        });
        for (int k = 0; k < 40 && readyTerm.get() < 0; k++) {
            clock.advance(Duration.ofMillis(100));
            ReplicatedNode.driveAll(nodes);
        }
        var group = nodes.get(0).openGroup(GROUP, opts, null);
        assertTrue(group.isLeader(), "node 1 should win the first election");
        assertEquals(group.term(), readyTerm.get(), "ClusterNode.onNoopApplied must fire with election term");
        assertEquals(group.lastApplied(), readyIndex.get(), "applied index matches lastApplied");
        assertTrue(readyIndex.get() > 0);
    }

    private static Member member(int wire, boolean witness) {
        return new Member(new NodeId(wire, "n" + wire), new SiteId("local"), new InetSocketAddress("127.0.0.1", 25_000 + wire), 1L,
                MemberState.ALIVE, witness);
    }
}
