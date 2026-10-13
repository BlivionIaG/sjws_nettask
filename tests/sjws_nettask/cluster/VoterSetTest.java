package sjws_nettask.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
class VoterSetTest {

    private static final SiteId SITE = new SiteId("local");
    private static final GroupId GROUP = new GroupId("ha-control");

    @Test
    void twoDataNodesPlusAWitnessUseMajorityAndABarePairIsRejected() throws Exception {
        var dataA = member(1, false);
        var dataB = member(2, false);
        var witness = member(3, true);
        var voters = VoterSet.dataAndWitness(dataA, dataB, witness);
        assertEquals(QuorumPolicy.majorityCount(3), 2);
        assertEquals(Set.of(dataA.id(), dataB.id(), witness.id()), voters.voters());
        assertTrue(voters.policy().hasQuorum(voters.voters(), Set.of(dataA.id(), dataB.id())));
        assertTrue(voters.policy().hasQuorum(voters.voters(), Set.of(dataA.id(), witness.id())));
        assertTrue(voters.policy().hasQuorum(voters.voters(), Set.of(dataB.id(), witness.id())));
        assertFalse(voters.policy().hasQuorum(voters.voters(), Set.of(dataA.id())));
        assertFalse(voters.policy().hasQuorum(voters.voters(), Set.of(witness.id())));
        assertFalse(voters.policy().hasQuorum(voters.voters(), Set.of()));

        assertThrows(IllegalArgumentException.class, () -> VoterSet.majority(List.of(dataA, dataB),
                Set.of(dataA.id(), dataB.id())));
        assertThrows(IllegalArgumentException.class, () -> VoterSet.dataAndWitness(witness, dataB, dataA));
        assertThrows(IllegalArgumentException.class, () -> VoterSet.dataAndWitness(dataA, dataB, dataB));
        assertThrows(IllegalArgumentException.class, () -> VoterSet.dataAndWitness(dataA, witness, member(4, true)));
        assertThrows(IllegalArgumentException.class, () -> VoterSet.dataAndWitness(dataA, dataA, witness));
        assertThrows(IllegalArgumentException.class, () -> VoterSet.dataAndWitness(dataA, dataB, member(1, true)));
        assertThrows(IllegalArgumentException.class, () -> VoterSet.dataAndWitness(dataA, dataB, member(2, true)));
        assertThrows(NullPointerException.class, () -> VoterSet.majority(java.util.Arrays.asList(dataA, null),
                Set.of(dataA.id())));
        assertThrows(NullPointerException.class, () -> VoterSet.dataAndWitness(null, dataB, witness));
        assertThrows(NullPointerException.class, () -> VoterSet.majority(null, Set.of()));
        assertThrows(NullPointerException.class, () -> VoterSet.majority(List.of(dataA), null));
        assertThrows(NullPointerException.class, () -> VoterSet.dataAndWitness(dataA, null, witness));
        assertThrows(NullPointerException.class, () -> VoterSet.dataAndWitness(dataA, dataB, null));
        assertThrows(IllegalArgumentException.class, () -> VoterSet.majority(List.of(dataA), Set.of()));
        assertThrows(IllegalArgumentException.class,
                () -> VoterSet.majority(List.of(dataA, dataB), Set.of(dataA.id(), witness.id())));
        assertThrows(IllegalArgumentException.class,
                () -> VoterSet.majority(List.of(dataA, dataA), Set.of(dataA.id())));

        var solo = VoterSet.majority(List.of(dataA), Set.of(dataA.id()));
        assertTrue(solo.policy().hasQuorum(solo.voters(), Set.of(dataA.id())));
        var pairWithWitness = VoterSet.majority(List.of(dataA, witness), Set.of(dataA.id(), witness.id()));
        assertEquals(2, pairWithWitness.voters().size());
        var threeData = VoterSet.majority(List.of(dataA, dataB, member(4, false)),
                Set.of(dataA.id(), dataB.id(), member(4, false).id()));
        assertFalse(threeData.members().get(2).witness());

        var clock = new FakeClock(0L);
        var net = new SimTransport();
        assertThrows(NullPointerException.class,
                () -> ReplicatedNode.open(dataA.id(), (VoterSet) null, clock, new MemoryLogStorage(), net));
        var opts = new GroupOptions(Durability.RELAXED, 1024, Duration.ofMillis(100), Duration.ofMillis(40), 1_000_000);
        var nodes = new ArrayList<ReplicatedNode>();
        var logs = new ArrayList<MemoryLogStorage>();
        for (var member : voters.members()) {
            var log = new MemoryLogStorage();
            logs.add(log);
            var node = ReplicatedNode.open(member.id(), voters, clock, log, net);
            node.openGroup(GROUP, opts, null);
            nodes.add(node);
        }
        clock.advance(Duration.ofMillis(100));
        ReplicatedNode.driveAll(nodes);
        assertTrue(nodes.get(0).openGroup(GROUP, opts, null).isLeader());
        assertTrue(nodes.get(2).witness());
        net.cut(nodes.get(0).self(), nodes.get(1).self());
        var committed = nodes.get(0).append(GROUP, List.of(ByteBuffer.wrap("kept".getBytes(StandardCharsets.UTF_8))));
        ReplicatedNode.driveAll(nodes);
        assertTrue(committed.isDone());
        assertEquals(0L, logs.get(2).lastIndex(GROUP));
        assertTrue(logs.get(2).witnessIndex(GROUP) >= committed.get().index());
    }

    private static Member member(int wire, boolean witness) {
        return new Member(new NodeId(wire, "n" + wire), SITE, new InetSocketAddress("127.0.0.1", 21_000 + wire), 1L,
                MemberState.ALIVE, witness);
    }
}
