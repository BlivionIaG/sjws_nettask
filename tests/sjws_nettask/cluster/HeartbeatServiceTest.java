package sjws_nettask.cluster;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class HeartbeatServiceTest {

    private static final SiteId SITE = new SiteId("local");
    private static final ClusterKey KEY = ClusterKey.of("0123456789abcdef".getBytes(StandardCharsets.UTF_8), null);

    private static InetSocketAddress at(int port) {
        return new InetSocketAddress("127.0.0.1", port);
    }

    private static Member member(int wire, String name, int port) {
        return new Member(new NodeId(wire, name), SITE, at(port), 1L, MemberState.ALIVE, false);
    }

    @Test
    void simLinkExchangesBeatsSuspectsAndMeasuresRtt() throws Exception {
        var clock = new FakeClock(0L);
        var net = new SimNetwork();
        var aLink = net.link(at(1));
        var bLink = net.link(at(2));
        var rtt = new AtomicLong(-1);
        ClusterEvents events = new ClusterEvents() {
            @Override
            public void memberState(NodeId id, MemberState from, MemberState to) {
            }

            @Override
            public void heartbeatRtt(NodeId peer, long nanos) {
                rtt.set(nanos);
            }
        };
        var members = List.of(member(1, "a", 1), member(2, "b", 2));
        var interval = Duration.ofMillis(100);
        var a = LocalCluster.open(new NodeId(1, "a"), members, aLink, clock, interval, Duration.ofSeconds(5), events,
                KEY);
        var b = LocalCluster.open(new NodeId(2, "b"), members, bLink, clock, interval, Duration.ofSeconds(5),
                ClusterEvents.NONE, KEY);
        a.announce(3L, 9L, 11L, 1_000);
        a.cycle();
        b.cycle();
        a.cycle();
        assertTrue(a.received() > 0);
        assertTrue(b.received() > 0);
        assertTrue(rtt.get() >= 0);
        assertEquals(9L, b.detector().generationOf(new NodeId(1, "a")));
        assertTrue(b.detector().leaseHeld(new NodeId(1, "a"), clock.nanoTime()));

        net.block(at(2), at(1));
        for (int i = 0; i < 4; i++) {
            clock.advance(interval);
            a.cycle();
        }
        assertEquals(MemberState.SUSPECT, a.membership().member(new NodeId(2, "b")).orElseThrow().state());
        net.unblock(at(2), at(1));
        b.cycle();
        a.cycle();
        assertEquals(MemberState.ALIVE, a.membership().member(new NodeId(2, "b")).orElseThrow().state());
        assertEquals(2L, a.membership().member(new NodeId(2, "b")).orElseThrow().incarnation());

        var junk = net.link(at(9));
        assertThrows(IllegalArgumentException.class, () -> net.link(at(9)));
        while (aLink.poll() != null) {
            // drain beats queued while B was catching up
        }
        junk.send(at(1), new byte[4], 0, 4);
        var bad = new byte[HeartbeatCodec.BYTES];
        junk.send(at(1), bad, 0, bad.length);
        int before = a.received();
        int rejected = a.rejected();
        a.cycle();
        assertEquals(before, a.received());
        assertEquals(rejected + 2, a.rejected());

        while (bLink.poll() != null) {
            // leave the queue empty so the next drop is observable
        }
        net.dropNext(1);
        a.cycle();
        assertNull(bLink.poll());
        a.cycle();
        assertNotNull(bLink.poll());
        net.close();
        assertThrows(IOException.class, () -> aLink.send(at(2), bad, 0, bad.length));
        assertThrows(IOException.class, aLink::poll);
        assertThrows(IllegalStateException.class, () -> net.link(at(3)));
        assertThrows(IllegalArgumentException.class, () -> net.dropNext(0));
        a.close();
        b.close();
        a.close();
    }

    @Test
    void sendAndPollFailuresAreCountedAndTheThreadStops() throws Exception {
        var clock = new FakeClock(0L);
        var members = List.of(member(1, "a", 1), member(2, "b", 2));
        var boom = new FlakyLink(at(1));
        var interval = Duration.ofMillis(20);
        var membership = new Membership(new NodeId(1, "a"), members, interval.toNanos(), Duration.ofSeconds(5).toNanos(),
                0L, ClusterEvents.NONE);
        var service = new HeartbeatService(membership, boom, clock, interval, ClusterEvents.NONE, KEY);
        assertThrows(NullPointerException.class,
                () -> new HeartbeatService(membership, boom, clock, interval, ClusterEvents.NONE, null));
        assertThrows(IllegalArgumentException.class,
                () -> new HeartbeatService(membership, boom, clock, Duration.ZERO, ClusterEvents.NONE, KEY));
        assertThrows(IllegalArgumentException.class,
                () -> new HeartbeatService(membership, boom, clock, null, ClusterEvents.NONE, KEY));
        service.cycle();
        assertTrue(service.sendFailures() > 0);
        boom.failSend = false;
        boom.failPoll = true;
        int failures = service.sendFailures();
        service.cycle();
        assertTrue(service.sendFailures() > failures);

        var net = new SimNetwork();
        var link = net.link(at(1));
        var peer = net.link(at(2));
        var throwsRtt = new AtomicInteger();
        ClusterEvents events = new ClusterEvents() {
            @Override
            public void memberState(NodeId id, MemberState from, MemberState to) {
            }

            @Override
            public void heartbeatRtt(NodeId id, long nanos) {
                throwsRtt.incrementAndGet();
                throw new IllegalStateException("rtt");
            }
        };
        var left = LocalCluster.start(new NodeId(1, "a"), members, link, Clock.SYSTEM, interval, Duration.ofSeconds(5),
                events, KEY);
        var right = LocalCluster.start(new NodeId(2, "b"), members, peer, Clock.SYSTEM, interval,
                Duration.ofSeconds(5), ClusterEvents.NONE, KEY);
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (left.sendFailures() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(left.sendFailures() > 0);
        left.close();
        right.close();
        assertTrue(peer.localAddress().getPort() == 2);

        var alone = LocalCluster.open(new NodeId(1, "a"), List.of(member(1, "a", 1)), net.link(at(8)), clock,
                interval, Duration.ofSeconds(5), ClusterEvents.NONE, KEY);
        alone.cycle();
        assertEquals(0, alone.received());
        alone.close();
        net.close();
    }

    @Test
    void udpHeartbeatsArrive() throws Exception {
        try (var leftLink = new UdpPacketLink(new InetSocketAddress("127.0.0.1", 0));
                var rightLink = new UdpPacketLink(new InetSocketAddress("127.0.0.1", 0))) {
            assertNull(leftLink.poll());
            var site = SITE;
            var a = new NodeId(1, "udp-a");
            var b = new NodeId(2, "udp-b");
            var members = List.of(
                    new Member(a, site, leftLink.localAddress(), 1L, MemberState.ALIVE, false),
                    new Member(b, site, rightLink.localAddress(), 1L, MemberState.ALIVE, false));
            var interval = Duration.ofMillis(15);
            var left = LocalCluster.start(a, members, leftLink, Clock.SYSTEM, interval, KEY);
            var right = LocalCluster.start(b, members, rightLink, Clock.SYSTEM, interval, KEY);
            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while ((left.received() == 0 || right.received() == 0) && System.nanoTime() < deadline) {
                Thread.sleep(15);
            }
            assertTrue(left.received() > 0, "left received nothing");
            assertTrue(right.received() > 0, "right received nothing");
            rightLink.send(leftLink.localAddress(), new byte[] {1, 2, 3, 4}, 0, 4);
            Thread.sleep(30);
            left.close();
            right.close();
            leftLink.close();
            assertThrows(IOException.class, () -> leftLink.send(rightLink.localAddress(), new byte[] {1}, 0, 1));
            assertThrows(IOException.class, leftLink::poll);
        }
    }

    private static final class FlakyLink implements PacketLink {
        private final InetSocketAddress local;
        private boolean failSend = true;
        private boolean failPoll;

        private FlakyLink(InetSocketAddress local) {
            this.local = local;
        }

        @Override
        public void send(InetSocketAddress to, byte[] packet, int offset, int length) throws IOException {
            if (failSend) {
                throw new IOException("send");
            }
        }

        @Override
        public Packet poll() throws IOException {
            if (failPoll) {
                throw new IOException("poll");
            }
            return null;
        }

        @Override
        public InetSocketAddress localAddress() {
            return local;
        }

        @Override
        public void close() {
        }
    }
}
