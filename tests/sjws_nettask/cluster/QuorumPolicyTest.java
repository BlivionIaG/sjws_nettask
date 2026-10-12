package sjws_nettask.cluster;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;

class QuorumPolicyTest {

    private static NodeId id(int wire) {
        return new NodeId(wire, "n" + wire);
    }

    @Test
    void majorityCountForOneThroughSeven() {
        int[] need = {1, 2, 2, 3, 3, 4, 4};
        for (int n = 1; n <= 7; n++) {
            assertEquals(need[n - 1], QuorumPolicy.majorityCount(n), "n=" + n);
            var voters = new java.util.LinkedHashSet<NodeId>();
            for (int i = 1; i <= n; i++) {
                voters.add(id(i));
            }
            var policy = QuorumPolicy.majority();
            var acks = new java.util.LinkedHashSet<NodeId>();
            assertFalse(policy.hasQuorum(voters, acks));
            for (int i = 1; i < need[n - 1]; i++) {
                acks.add(id(i));
                assertFalse(policy.hasQuorum(voters, acks), "n=" + n + " acks=" + i);
            }
            acks.add(id(need[n - 1]));
            assertTrue(policy.hasQuorum(voters, acks), "n=" + n);
            acks.add(id(9_000 + n));
            assertTrue(policy.hasQuorum(voters, acks), "outsider does not count against n=" + n);
        }
    }

    @Test
    void majorityRejectsEmptyVotersAndBadCounts() {
        var policy = QuorumPolicy.majority();
        assertFalse(policy.hasQuorum(Set.of(), Set.of(id(1))));
        assertThrows(IllegalArgumentException.class, () -> QuorumPolicy.majorityCount(0));
        assertThrows(NullPointerException.class, () -> policy.hasQuorum(null, Set.of()));
        assertThrows(NullPointerException.class, () -> policy.hasQuorum(Set.of(id(1)), null));
    }

    @Test
    void witnessNeedsTheWitnessAndOneDataNode() {
        var witness = id(3);
        var a = id(1);
        var b = id(2);
        var voters = Set.of(a, b, witness);
        var policy = QuorumPolicy.witness(witness);
        assertFalse(policy.hasQuorum(voters, Set.of()));
        assertFalse(policy.hasQuorum(voters, Set.of(witness)));
        assertFalse(policy.hasQuorum(voters, Set.of(a)));
        assertFalse(policy.hasQuorum(voters, Set.of(a, b)));
        assertTrue(policy.hasQuorum(voters, Set.of(a, witness)));
        assertTrue(policy.hasQuorum(voters, Set.of(a, b, witness)));
        assertFalse(policy.hasQuorum(voters, Set.of(witness, id(9))));
        assertFalse(policy.hasQuorum(Set.of(a, b), Set.of(a, witness)));
        assertThrows(NullPointerException.class, () -> QuorumPolicy.witness(null));
        assertThrows(NullPointerException.class, () -> policy.hasQuorum(null, Set.of(witness)));
        assertThrows(NullPointerException.class, () -> policy.hasQuorum(voters, null));
    }

    @Test
    void idsMembersAndTheSystemClockRejectGarbage() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new NodeId(0, "a"));
        assertThrows(IllegalArgumentException.class, () -> new NodeId(65_536, "a"));
        assertThrows(IllegalArgumentException.class, () -> new NodeId(1, " "));
        assertThrows(IllegalArgumentException.class, () -> new NodeId(1, null));
        assertThrows(IllegalArgumentException.class, () -> new SiteId(" "));
        assertThrows(IllegalArgumentException.class, () -> new SiteId(null));
        var site = new SiteId("local");
        var node = new NodeId(1, "a");
        assertThrows(NullPointerException.class,
                () -> new Member(null, site, new InetSocketAddress("127.0.0.1", 1), 0, MemberState.ALIVE, false));
        assertThrows(NullPointerException.class,
                () -> new Member(node, null, new InetSocketAddress("127.0.0.1", 1), 0, MemberState.ALIVE, false));
        assertThrows(NullPointerException.class, () -> new Member(node, site, null, 0, MemberState.ALIVE, false));
        assertThrows(NullPointerException.class,
                () -> new Member(node, site, new InetSocketAddress("127.0.0.1", 1), 0, null, false));
        assertThrows(IllegalArgumentException.class,
                () -> new Member(node, site, new InetSocketAddress("127.0.0.1", 1), -1, MemberState.ALIVE, false));
        assertThrows(IllegalArgumentException.class,
                () -> new Member(node, site, new InetSocketAddress("127.0.0.1", 0), 0, MemberState.ALIVE, false));
        assertThrows(NullPointerException.class, () -> new MembershipView(null, java.util.List.of()));
        assertThrows(NullPointerException.class, () -> new Heartbeat(1, null, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new Heartbeat(2, Heartbeat.Kind.HEARTBEAT, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new Heartbeat(1, Heartbeat.Kind.HEARTBEAT, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new Heartbeat(1, Heartbeat.Kind.HEARTBEAT, 1, 1, 0, 0, 0, 0, 0, 0, 0, -1, 0));
        var view = new MembershipView(node, java.util.List.of());
        assertTrue(view.member(node).isEmpty());
        assertTrue(view.memberByWire(1).isEmpty());
        assertTrue(view.others().isEmpty());
        assertEquals(0, view.count(MemberState.ALIVE));
        long before = Clock.SYSTEM.nanoTime();
        Clock.SYSTEM.sleep(Duration.ZERO);
        Clock.SYSTEM.sleep(Duration.ofNanos(-1));
        Clock.SYSTEM.sleep(Duration.ofNanos(200_000));
        Clock.SYSTEM.sleep(Duration.ofMillis(1));
        assertTrue(Clock.SYSTEM.nanoTime() >= before);
        ClusterEvents.NONE.memberState(node, MemberState.ALIVE, MemberState.DEAD);
        ClusterEvents.NONE.heartbeatRtt(node, 1L);
    }
}
