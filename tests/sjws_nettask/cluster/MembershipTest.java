package sjws_nettask.cluster;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MembershipTest {

    private static final long INTERVAL = 100_000_000L;
    private static final long DEAD = 5_000_000_000L;
    private static final SiteId SITE = new SiteId("local");

    private static NodeId id(int wire, String name) {
        return new NodeId(wire, name);
    }

    private static Member member(NodeId id, int port, MemberState state, long incarnation, boolean witness) {
        return new Member(id, SITE, new InetSocketAddress("127.0.0.1", port), incarnation, state, witness);
    }

    private static Membership detector(NodeId self, List<Member> members, ClusterEvents events) {
        return new Membership(self, members, INTERVAL, DEAD, 0L, events);
    }

    @Test
    void suspectThenDeadAndAHigherIncarnationRevives() {
        var a = id(1, "a");
        var b = id(2, "b");
        var events = new Recording();
        var members = List.of(member(a, 1, MemberState.ALIVE, 1, false), member(b, 2, MemberState.ALIVE, 1, false));
        var detector = detector(a, members, events);
        var views = new AtomicInteger();
        detector.onMembershipChange(view -> views.incrementAndGet());
        detector.tick(INTERVAL);
        detector.tick(2 * INTERVAL);
        assertEquals(MemberState.ALIVE, state(detector, b));
        assertEquals(0, views.get());
        detector.tick(3 * INTERVAL);
        assertEquals(MemberState.SUSPECT, state(detector, b));
        assertEquals(1, views.get());
        assertEquals(List.of(MemberState.SUSPECT), events.states);
        var direct = detector.outbound(b, 4L, 8L, 16L, 0);
        assertEquals(Heartbeat.Kind.HEARTBEAT, direct.kind());
        assertEquals(b.wire(), direct.suspectWire());
        assertEquals(0, direct.flags() & Heartbeat.FLAG_WITNESS);
        detector.tick(DEAD);
        assertEquals(MemberState.DEAD, state(detector, b));
        var revived = new Heartbeat(Heartbeat.VERSION, Heartbeat.Kind.HEARTBEAT, b.wire(), a.wire(), 2L, 4L, 42L, 7L,
                1_000, 0, 3, 0, 0);
        assertTrue(detector.onHeartbeat(revived, DEAD).isEmpty());
        assertEquals(MemberState.ALIVE, state(detector, b));
        assertEquals(2L, detector.view().member(b).orElseThrow().incarnation());
        assertEquals(42L, detector.generationOf(b));
        assertTrue(detector.leaseHeld(b, DEAD + 999_000_000L));
        assertFalse(detector.leaseHeld(b, DEAD + 1_000_000_000L));
        assertFalse(detector.leaseHeld(id(9, "missing"), DEAD));
        assertEquals(-1L, detector.generationOf(id(9, "missing")));
    }

    @Test
    void suspicionOfOurselvesBumpsTheIncarnationAndAStaleOneDoesNot() {
        var a = id(1, "a");
        var b = id(2, "b");
        var members = List.of(member(a, 1, MemberState.ALIVE, 1, false), member(b, 2, MemberState.ALIVE, 1, true));
        var local = detector(a, members, ClusterEvents.NONE);
        local.tick(3 * INTERVAL);
        var accused = detector(b, members, ClusterEvents.NONE);
        var ping = local.outbound(b, 1L, 2L, 3L, 50);
        var reply = accused.onHeartbeat(ping, 3 * INTERVAL).orElseThrow();
        assertEquals(Heartbeat.Kind.ACK, reply.kind());
        assertEquals(2L, reply.incarnation());
        assertEquals(Heartbeat.FLAG_WITNESS, reply.flags());
        assertEquals(ping.seq(), reply.seq());
        local.onHeartbeat(reply, 3 * INTERVAL);
        assertEquals(MemberState.ALIVE, state(local, b));
        assertEquals(2L, local.view().member(b).orElseThrow().incarnation());

        var staleMembers = List.of(member(a, 1, MemberState.ALIVE, 5, false), member(b, 2, MemberState.ALIVE, 4, false));
        var stale = detector(b, staleMembers, ClusterEvents.NONE);
        var old = new Heartbeat(Heartbeat.VERSION, Heartbeat.Kind.HEARTBEAT, a.wire(), b.wire(), 1L, 0, 0, 0, 0, 0, 1,
                b.wire(), 1L);
        assertTrue(stale.onHeartbeat(old, 0L).isEmpty());
        assertEquals(4L, stale.selfIncarnation());
        stale.tick(-1L);
        assertEquals(MemberState.ALIVE, state(stale, a));
    }

    @Test
    void indirectProbeAsksTwoPeersAndTrustsAnAck() {
        var a = id(1, "a");
        var b = id(2, "b");
        var c = id(3, "c");
        var d = id(4, "d");
        var members = List.of(
                member(a, 1, MemberState.ALIVE, 1, false),
                member(b, 2, MemberState.ALIVE, 1, false),
                member(c, 3, MemberState.ALIVE, 1, false),
                member(d, 4, MemberState.ALIVE, 1, false));
        var detector = detector(a, members, ClusterEvents.NONE);
        detector.tick(3 * INTERVAL);
        assertEquals(MemberState.SUSPECT, state(detector, b));
        assertEquals(Heartbeat.Kind.INDIRECT_PING, detector.outbound(c, 0, 0, 0, 0).kind());
        var toD = detector.outbound(d, 0, 0, 0, 0);
        assertEquals(Heartbeat.Kind.INDIRECT_PING, toD.kind());
        assertEquals(b.wire(), toD.suspectWire());
        var toB = detector.outbound(b, 0, 0, 0, 0);
        assertEquals(Heartbeat.Kind.HEARTBEAT, toB.kind());
        assertEquals(b.wire(), toB.suspectWire());

        var prober = detector(c, members, ClusterEvents.NONE);
        var ack = prober.onHeartbeat(toD.kind() == Heartbeat.Kind.INDIRECT_PING
                ? detectorPing(a, c, b, 1L)
                : toD, 0L).orElseThrow();
        // c was built fresh; feed it the ping we actually sent to d only if c is a prober.
        ack = prober.onHeartbeat(new Heartbeat(Heartbeat.VERSION, Heartbeat.Kind.INDIRECT_PING, a.wire(), c.wire(), 1L,
                0, 0, 0, 0, 0, 9, b.wire(), 1L), 0L).orElseThrow();
        assertEquals(Heartbeat.Kind.INDIRECT_ACK, ack.kind());
        assertEquals(b.wire(), ack.suspectWire());
        assertEquals(1L, ack.ackIncarnation());

        var deadish = detector(a, members, ClusterEvents.NONE);
        deadish.tick(3 * INTERVAL);
        var higher = new Heartbeat(Heartbeat.VERSION, Heartbeat.Kind.INDIRECT_ACK, c.wire(), a.wire(), 1L, 0, 0, 0, 0, 0,
                1, b.wire(), 6L);
        deadish.onHeartbeat(higher, 3 * INTERVAL);
        assertEquals(MemberState.ALIVE, state(deadish, b));
        assertEquals(6L, deadish.view().member(b).orElseThrow().incarnation());

        var already = detector(a, members, ClusterEvents.NONE);
        var noop = new Heartbeat(Heartbeat.VERSION, Heartbeat.Kind.INDIRECT_ACK, c.wire(), a.wire(), 1L, 0, 0, 0, 0, 0, 1,
                b.wire(), 1L);
        assertTrue(already.onHeartbeat(noop, 0L).isEmpty());
        assertEquals(MemberState.ALIVE, state(already, b));

        var behind = new Heartbeat(Heartbeat.VERSION, Heartbeat.Kind.INDIRECT_ACK, c.wire(), a.wire(), 1L, 0, 0, 0, 0, 0,
                1, b.wire(), 0L);
        var suspected = detector(a, members, ClusterEvents.NONE);
        suspected.tick(3 * INTERVAL);
        suspected.onHeartbeat(behind, 3 * INTERVAL);
        assertEquals(MemberState.SUSPECT, state(suspected, b));

        var unknownAck = new Heartbeat(Heartbeat.VERSION, Heartbeat.Kind.INDIRECT_ACK, c.wire(), a.wire(), 1L, 0, 0, 0,
                0, 0, 1, 50, 1L);
        assertTrue(detector(a, members, ClusterEvents.NONE).onHeartbeat(unknownAck, 0L).isEmpty());

        var suspectPeer = List.of(
                member(a, 1, MemberState.ALIVE, 1, false),
                member(b, 2, MemberState.SUSPECT, 1, false),
                member(c, 3, MemberState.ALIVE, 1, false));
        var quiet = detector(c, suspectPeer, ClusterEvents.NONE);
        var ask = new Heartbeat(Heartbeat.VERSION, Heartbeat.Kind.INDIRECT_PING, a.wire(), c.wire(), 1L, 0, 0, 0, 0, 0, 1,
                b.wire(), 1L);
        assertTrue(quiet.onHeartbeat(ask, 0L).isEmpty());
        var heartbeatAsk = new Heartbeat(Heartbeat.VERSION, Heartbeat.Kind.HEARTBEAT, a.wire(), c.wire(), 1L, 0, 0, 0,
                Integer.MAX_VALUE, 0, 1, 9, 1L);
        assertTrue(quiet.onHeartbeat(heartbeatAsk, 0L).isEmpty());
    }

    @Test
    void aDeadPeerIsNotAskedToProbeAndAJumpCanSkipSuspect() {
        var a = id(1, "a");
        var b = id(2, "b");
        var c = id(3, "c");
        var d = id(4, "d");
        var members = List.of(
                member(a, 1, MemberState.ALIVE, 1, false),
                member(b, 2, MemberState.ALIVE, 1, false),
                member(c, 3, MemberState.DEAD, 1, false),
                member(d, 4, MemberState.ALIVE, 1, false));
        var detector = detector(a, members, ClusterEvents.NONE);
        detector.tick(3 * INTERVAL);
        assertEquals(Heartbeat.Kind.INDIRECT_PING, detector.outbound(d, 0, 0, 0, 0).kind());
        var toDead = detector.outbound(c, 0, 0, 0, 0);
        assertEquals(Heartbeat.Kind.HEARTBEAT, toDead.kind());
        assertEquals(0, toDead.suspectWire());

        var pair = List.of(member(a, 1, MemberState.ALIVE, 1, false), member(b, 2, MemberState.SUSPECT, 3, false));
        var seeded = new Membership(a, pair, INTERVAL, INTERVAL, 0L, ClusterEvents.NONE);
        seeded.tick(INTERVAL);
        assertEquals(MemberState.DEAD, state(seeded, b));

        var jumped = new Membership(a, List.of(member(a, 1, MemberState.ALIVE, 1, false),
                member(b, 2, MemberState.ALIVE, 1, false)), INTERVAL, INTERVAL, 0L, ClusterEvents.NONE);
        jumped.tick(INTERVAL);
        assertEquals(MemberState.DEAD, state(jumped, b));
    }

    @Test
    void ignoresSelfAndUnknownSendersAndRejectsABadSeed() {
        var a = id(1, "a");
        var b = id(2, "b");
        var members = List.of(member(a, 1, MemberState.ALIVE, 1, false), member(b, 2, MemberState.ALIVE, 1, false));
        var detector = detector(a, members, ClusterEvents.NONE);
        var selfBeat = new Heartbeat(Heartbeat.VERSION, Heartbeat.Kind.HEARTBEAT, a.wire(), b.wire(), 1, 0, 0, 0, 0, 0, 1,
                0, 0);
        assertTrue(detector.onHeartbeat(selfBeat, 0L).isEmpty());
        var unknown = new Heartbeat(Heartbeat.VERSION, Heartbeat.Kind.HEARTBEAT, 9, a.wire(), 1, 0, 0, 0, -5, 0, 1, 0, 0);
        assertTrue(detector.onHeartbeat(unknown, 0L).isEmpty());
        assertFalse(detector.leaseHeld(b, 0L));
        var cleared = new Heartbeat(Heartbeat.VERSION, Heartbeat.Kind.ACK, b.wire(), a.wire(), 2L, 0, 0, 0, 0, 0, 1, 0, 0);
        detector.onHeartbeat(cleared, 0L);
        assertFalse(detector.leaseHeld(b, 0L));
        var negative = new Heartbeat(Heartbeat.VERSION, Heartbeat.Kind.ACK, b.wire(), a.wire(), 2L, 0, 0, 0, -5, 0, 1, 0,
                0);
        detector.onHeartbeat(negative, 0L);
        assertFalse(detector.leaseHeld(b, 0L));
        var huge = new Heartbeat(Heartbeat.VERSION, Heartbeat.Kind.ACK, b.wire(), a.wire(), 2L, 0, 0, 0, Integer.MAX_VALUE,
                0, 1, 0, 0);
        detector.onHeartbeat(huge, 0L);
        assertTrue(detector.leaseHeld(b, 86_400_000L * 1_000_000L - 1));
        assertFalse(detector.leaseHeld(b, 86_400_000L * 1_000_000L));
        assertThrows(NullPointerException.class, () -> detector.onMembershipChange(null));
        assertThrows(IllegalArgumentException.class, () -> detector.outbound(a, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> detector.outbound(id(8, "nope"), 0, 0, 0, 0));
        assertThrows(NullPointerException.class, () -> detector(a, members, null));
        assertThrows(IllegalArgumentException.class,
                () -> new Membership(a, List.of(member(b, 2, MemberState.ALIVE, 1, false)), INTERVAL, DEAD, 0L,
                        ClusterEvents.NONE));
        assertThrows(IllegalArgumentException.class, () -> new Membership(a, members, 0, DEAD, 0L, ClusterEvents.NONE));
        assertThrows(IllegalArgumentException.class,
                () -> new Membership(a, members, INTERVAL, 0, 0L, ClusterEvents.NONE));
        var duplicated = new ArrayList<>(members);
        duplicated.add(member(b, 5, MemberState.ALIVE, 1, false));
        assertThrows(IllegalArgumentException.class, () -> detector(a, duplicated, ClusterEvents.NONE));
        var sameWire = List.of(member(a, 1, MemberState.ALIVE, 1, false), member(id(1, "other"), 4, MemberState.ALIVE, 1, false));
        assertThrows(IllegalArgumentException.class, () -> detector(a, sameWire, ClusterEvents.NONE));
        var withNull = new ArrayList<Member>();
        withNull.add(null);
        assertThrows(NullPointerException.class, () -> detector(a, withNull, ClusterEvents.NONE));
        assertEquals(a, detector.self());
        assertEquals(1, detector.view().others().size());
        assertTrue(detector.view().memberByWire(2).isPresent());
        assertEquals(2, detector.view().count(MemberState.ALIVE));
    }

    private static Heartbeat detectorPing(NodeId from, NodeId to, NodeId suspect, long incarnation) {
        return new Heartbeat(Heartbeat.VERSION, Heartbeat.Kind.INDIRECT_PING, from.wire(), to.wire(), 1L, 0, 0, 0, 0, 0,
                1, suspect.wire(), incarnation);
    }

    private static MemberState state(Membership detector, NodeId id) {
        return detector.view().member(id).orElseThrow().state();
    }

    private static final class Recording implements ClusterEvents {
        final List<MemberState> states = new ArrayList<>();

        @Override
        public void memberState(NodeId id, MemberState from, MemberState to) {
            states.add(to);
        }

        @Override
        public void heartbeatRtt(NodeId peer, long nanos) {
        }
    }
}
