package sjws_nettask.cluster;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** HMAC-SHA256 on the HA UDP heartbeat: spoof, replay, stale, rotation, and a wrong key. */
class HeartbeatAuthTest {

    private static final byte[] CURRENT = "current-cluster-key".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PREVIOUS = "previous-cluster-key".getBytes(StandardCharsets.UTF_8);
    private static final byte[] WRONG = "wrong-cluster-key!!".getBytes(StandardCharsets.UTF_8);
    private static final SiteId SITE = new SiteId("local");

    private static ClusterKey key() {
        return ClusterKey.of(CURRENT, PREVIOUS);
    }

    private static byte[] body(int from, int to) {
        var raw = new byte[HeartbeatCodec.BYTES];
        HeartbeatCodec.encode(new Heartbeat(Heartbeat.VERSION, Heartbeat.Kind.HEARTBEAT, from, to, 1L, 0L, 4L, 8L, 0,
                0, 1, 0, 0L), raw);
        return raw;
    }

    @Test
    void roundTripAcceptsAFreshPacketAndRejectsAShortOne() {
        var auth = new HeartbeatAuthenticator(key());
        var packet = new byte[HeartbeatAuthenticator.PACKET_BYTES];
        long now = 1_700_000_000_000L;
        auth.seal(body(2, 1), packet, now);
        assertEquals(4L, auth.open(packet, now).generation());
        assertEquals(0, auth.rejected());
        assertNull(auth.open(null, now));
        assertNull(auth.open(new byte[4], now));
        assertNull(auth.open(new byte[HeartbeatAuthenticator.PACKET_BYTES - 1], now));
        assertEquals(3, auth.rejected());
        assertThrows(IllegalArgumentException.class, () -> auth.seal(new byte[8], packet, now));
        assertThrows(IllegalArgumentException.class,
                () -> HeartbeatAuthenticator.seal(CURRENT, body(2, 1), now, 0, packet));
    }

    @Test
    void spoofedReplayedStaleRotatedAndWrongKeyPacketsAreDropped() throws Exception {
        var clock = new FakeClock(0L);
        var net = new SimNetwork();
        var link = net.link(new InetSocketAddress("127.0.0.1", 1));
        var peer = net.link(new InetSocketAddress("127.0.0.1", 2));
        var members = List.of(
                new Member(new NodeId(1, "a"), SITE, new InetSocketAddress("127.0.0.1", 1), 1L, MemberState.ALIVE, false),
                new Member(new NodeId(2, "b"), SITE, new InetSocketAddress("127.0.0.1", 2), 1L, MemberState.ALIVE, false));
        var node = LocalCluster.open(new NodeId(1, "a"), members, link, clock, Duration.ofMillis(100),
                Duration.ofSeconds(5), ClusterEvents.NONE, key());
        long now = System.currentTimeMillis();
        var payload = body(2, 1);
        var good = new HeartbeatAuthenticator(key());

        var wrong = new byte[HeartbeatAuthenticator.PACKET_BYTES];
        new HeartbeatAuthenticator(ClusterKey.of(WRONG, null)).seal(payload, wrong, now);
        peer.send(new InetSocketAddress("127.0.0.1", 1), wrong, 0, wrong.length);

        var spoof = new byte[HeartbeatAuthenticator.PACKET_BYTES];
        good.seal(payload, spoof, now);
        spoof[10] ^= 0x5a;
        peer.send(new InetSocketAddress("127.0.0.1", 1), spoof, 0, spoof.length);

        var stale = new byte[HeartbeatAuthenticator.PACKET_BYTES];
        good.seal(payload, stale, now - HeartbeatAuthenticator.WINDOW_MILLIS - 1);
        peer.send(new InetSocketAddress("127.0.0.1", 1), stale, 0, stale.length);

        var future = new byte[HeartbeatAuthenticator.PACKET_BYTES];
        good.seal(payload, future, now + HeartbeatAuthenticator.WINDOW_MILLIS + 1);
        peer.send(new InetSocketAddress("127.0.0.1", 1), future, 0, future.length);

        var rotated = new byte[HeartbeatAuthenticator.PACKET_BYTES];
        new HeartbeatAuthenticator(ClusterKey.of(PREVIOUS, null)).seal(payload, rotated, now);
        peer.send(new InetSocketAddress("127.0.0.1", 1), rotated, 0, rotated.length);

        node.cycle();
        assertEquals(1, node.received(), "previous key is accepted during rotation");
        assertEquals(4, node.rejected());
        assertEquals(4L, node.detector().generationOf(new NodeId(2, "b")));

        peer.send(new InetSocketAddress("127.0.0.1", 1), rotated, 0, rotated.length);
        node.cycle();
        assertEquals(1, node.received(), "replay of the rotated packet is dropped");
        assertEquals(5, node.rejected());
        node.close();
        net.close();
    }

    @Test
    void windowIsInclusiveAndAStalePacketDoesNotConsumeTheSequence() {
        var auth = new HeartbeatAuthenticator(ClusterKey.of(CURRENT, null));
        long now = 50_000L;
        var fresh = new byte[HeartbeatAuthenticator.PACKET_BYTES];
        var edge = new byte[HeartbeatAuthenticator.PACKET_BYTES];
        var past = new byte[HeartbeatAuthenticator.PACKET_BYTES];
        HeartbeatAuthenticator.seal(CURRENT, body(2, 1), now, 2, fresh);
        HeartbeatAuthenticator.seal(CURRENT, body(2, 1), now - HeartbeatAuthenticator.WINDOW_MILLIS, 3, edge);
        HeartbeatAuthenticator.seal(CURRENT, body(2, 1), now - HeartbeatAuthenticator.WINDOW_MILLIS - 1, 4, past);
        assertNull(auth.open(past, now));
        assertNotNull(auth.open(fresh, now));
        assertNotNull(auth.open(edge, now));
        var futureEdge = new byte[HeartbeatAuthenticator.PACKET_BYTES];
        HeartbeatAuthenticator.seal(CURRENT, body(2, 1), now + HeartbeatAuthenticator.WINDOW_MILLIS, 5, futureEdge);
        assertNotNull(auth.open(futureEdge, now));
        assertEquals(1, auth.rejected());

        var again = new byte[HeartbeatAuthenticator.PACKET_BYTES];
        HeartbeatAuthenticator.seal(CURRENT, body(3, 1), now, 1, again);
        assertNotNull(auth.open(again, now), "a different sender keeps its own sequence");
        assertNull(auth.open(again, now));
    }

    @Test
    void aForgedBodyUnderAValidTagIsDropped() {
        var auth = new HeartbeatAuthenticator(ClusterKey.of(CURRENT, null));
        var garbage = new byte[HeartbeatCodec.BYTES];
        var packet = new byte[HeartbeatAuthenticator.PACKET_BYTES];
        HeartbeatAuthenticator.seal(CURRENT, garbage, 10_000L, 1, packet);
        assertNull(auth.open(packet, 10_000L));
        assertEquals(1, auth.rejected());
        var macFlip = new byte[HeartbeatAuthenticator.PACKET_BYTES];
        HeartbeatAuthenticator.seal(CURRENT, body(2, 1), 10_000L, 1, macFlip);
        macFlip[HeartbeatAuthenticator.MAC_OFFSET] ^= 0x01;
        assertNull(auth.open(macFlip, 10_000L));
        var good = new byte[HeartbeatAuthenticator.PACKET_BYTES];
        HeartbeatAuthenticator.seal(CURRENT, body(2, 1), 10_000L, 1, good);
        assertNotNull(auth.open(good, 10_000L));
    }

    @Test
    void clusterKeyIsRedactedAndAMissingKeyFailsClosed() {
        var secret = "env-secret-value-012345";
        var previous = "env-previous-value-0123";
        var key = ClusterKey.fromEnvironment(Map.of(ClusterKey.ENV_CURRENT, secret, ClusterKey.ENV_PREVIOUS, previous));
        assertTrue(key.hasPrevious());
        assertEquals(key, ClusterKey.of(secret.getBytes(StandardCharsets.UTF_8), previous.getBytes(StandardCharsets.UTF_8)));
        assertEquals(key.hashCode(), ClusterKey.of(key.current(), key.previous()).hashCode());
        var text = key.toString();
        assertTrue(text.contains("****"));
        assertFalse(text.contains(secret), text);
        assertFalse(text.contains(previous), text);
        assertTrue(ClusterKey.of(CURRENT, null).toString().endsWith("previous=none}"));
        assertFalse(ClusterKey.of(CURRENT, null).equals(key));
        assertNotEquals(key, "nope");

        var missing = assertThrows(IllegalArgumentException.class, () -> ClusterKey.fromEnvironment(Map.of()));
        assertFalse(missing.getMessage().contains(secret));
        var empty = assertThrows(IllegalArgumentException.class,
                () -> ClusterKey.fromEnvironment(Map.of(ClusterKey.ENV_CURRENT, "")));
        assertFalse(empty.getMessage().contains(secret));
        assertThrows(IllegalArgumentException.class,
                () -> ClusterKey.fromEnvironment(Map.of(ClusterKey.ENV_CURRENT, "   ")));
        assertThrows(IllegalArgumentException.class, () -> ClusterKey.fromEnvironment(
                Map.of(ClusterKey.ENV_CURRENT, secret, ClusterKey.ENV_PREVIOUS, "   ")));
        var shortSecret = "short-prev-zzzz";
        var tooShort = assertThrows(IllegalArgumentException.class,
                () -> ClusterKey.fromEnvironment(Map.of(ClusterKey.ENV_CURRENT, secret, ClusterKey.ENV_PREVIOUS, shortSecret)));
        assertFalse(tooShort.getMessage().contains(shortSecret));
        assertFalse(tooShort.getMessage().contains(secret));
        assertThrows(IllegalArgumentException.class,
                () -> ClusterKey.fromEnvironment(Map.of(ClusterKey.ENV_CURRENT, secret, ClusterKey.ENV_PREVIOUS, "")));
        assertThrows(NullPointerException.class, () -> ClusterKey.fromEnvironment(null));
        assertThrows(NullPointerException.class, () -> ClusterKey.of(null, null));
        assertThrows(IllegalArgumentException.class, () -> ClusterKey.of(new byte[15], null));
        assertThrows(IllegalArgumentException.class, () -> ClusterKey.of(CURRENT, new byte[15]));
        assertThrows(NullPointerException.class, () -> new HeartbeatAuthenticator(null));
    }

    /**
     * A restarted member builds a new authenticator, so its sequence starts again at 1.
     * Peers that already accepted a higher sequence from that wire id must still accept it.
     */
    @Test
    void restartedNodeIsNotRejected() {
        var receiver = new HeartbeatAuthenticator(ClusterKey.of(CURRENT, null));
        var running = new HeartbeatAuthenticator(ClusterKey.of(CURRENT, null));
        long now = 1_700_000_000_000L;
        var packet = new byte[HeartbeatAuthenticator.PACKET_BYTES];
        for (int i = 0; i < 5; i++) {
            running.seal(body(2, 1), packet, now);
            assertNotNull(receiver.open(packet, now));
        }
        var restarted = new HeartbeatAuthenticator(ClusterKey.of(CURRENT, null));
        restarted.seal(body(2, 1), packet, now);
        assertNotNull(receiver.open(packet, now), "a restarted node must not be rejected");
        assertEquals(0, receiver.rejected());
    }
}
