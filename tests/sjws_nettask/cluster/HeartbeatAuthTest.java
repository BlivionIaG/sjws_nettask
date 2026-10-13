package sjws_nettask.cluster;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** HMAC-SHA256 on the HA UDP heartbeat: spoof, replay, stale, rotation, restart, and a wrong key. */
class HeartbeatAuthTest {

    private static final byte[] CURRENT = "current-cluster-key".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PREVIOUS = "previous-cluster-key".getBytes(StandardCharsets.UTF_8);
    private static final byte[] WRONG = "wrong-cluster-key!!".getBytes(StandardCharsets.UTF_8);
    private static final SiteId SITE = new SiteId("local");
    private static final int[] MEMBERS = {1, 2, 3};
    private static final Clock CLOCK = new FakeClock(0);

    private static ClusterKey key() {
        return ClusterKey.of(CURRENT, PREVIOUS);
    }

    private static HeartbeatAuthenticator auth(ClusterKey key) {
        return new HeartbeatAuthenticator(key, CLOCK);
    }

    private static byte[] body(int from, int to) {
        var raw = new byte[HeartbeatCodec.BYTES];
        HeartbeatCodec.encode(new Heartbeat(Heartbeat.VERSION, Heartbeat.Kind.HEARTBEAT, from, to, 1L, 0L, 4L, 8L, 0,
                0, 1, 0, 0L), raw);
        return raw;
    }

    private static Heartbeat open(HeartbeatAuthenticator auth, byte[] packet, long now) {
        return open(auth, packet, now, 1);
    }

    private static Heartbeat open(HeartbeatAuthenticator auth, byte[] packet, long now, int self) {
        return auth.open(packet, now, self, MEMBERS, MEMBERS.length);
    }

    private static void seal(byte[] key, byte[] payload, long now, long seq, long epoch, int dest, int keyId,
            byte[] packet) {
        HeartbeatAuthenticator.seal(key, payload, now, seq, epoch, dest, keyId, packet);
    }

    @Test
    void roundTripAcceptsAFreshPacketAndRejectsAShortOne() {
        var auth = auth(key());
        var packet = new byte[HeartbeatAuthenticator.PACKET_BYTES];
        long now = 1_700_000_000_000L;
        auth.seal(body(2, 1), packet, now);
        assertEquals(4L, open(auth, packet, now).generation());
        assertEquals(0, auth.rejected());
        assertEquals(auth.bootEpoch(), 0L);
        assertNull(open(auth, null, now));
        assertNull(open(auth, new byte[4], now));
        assertNull(open(auth, new byte[HeartbeatAuthenticator.PACKET_BYTES - 1], now));
        assertEquals(3, auth.rejected(HeartbeatAuthenticator.Reason.MALFORMED));
        assertThrows(IllegalArgumentException.class, () -> auth.seal(new byte[8], packet, now));
        assertThrows(IllegalArgumentException.class,
                () -> seal(CURRENT, body(2, 1), now, 0, 1, 1, HeartbeatAuthenticator.KEY_CURRENT, packet));
    }

    @Test
    void spoofedReplayedStaleRotatedAndWrongKeyPacketsAreDropped() throws Exception {
        var clock = new FakeClock(0L);
        long now = 1_700_000_000_000L;
        clock.setWallMillis(now);
        var net = new SimNetwork();
        var link = net.link(new InetSocketAddress("127.0.0.1", 1));
        var peer = net.link(new InetSocketAddress("127.0.0.1", 2));
        var members = List.of(
                new Member(new NodeId(1, "a"), SITE, new InetSocketAddress("127.0.0.1", 1), 1L, MemberState.ALIVE, false),
                new Member(new NodeId(2, "b"), SITE, new InetSocketAddress("127.0.0.1", 2), 1L, MemberState.ALIVE, false));
        var node = LocalCluster.open(new NodeId(1, "a"), members, link, clock, Duration.ofMillis(100),
                Duration.ofSeconds(5), ClusterEvents.NONE, key());
        var payload = body(2, 1);

        var wrong = new byte[HeartbeatAuthenticator.PACKET_BYTES];
        seal(WRONG, payload, now, 1, now, 1, HeartbeatAuthenticator.KEY_CURRENT, wrong);
        peer.send(new InetSocketAddress("127.0.0.1", 1), wrong, 0, wrong.length);

        var spoof = new byte[HeartbeatAuthenticator.PACKET_BYTES];
        seal(CURRENT, payload, now, 2, now, 1, HeartbeatAuthenticator.KEY_CURRENT, spoof);
        spoof[10] ^= 0x5a;
        peer.send(new InetSocketAddress("127.0.0.1", 1), spoof, 0, spoof.length);

        var stale = new byte[HeartbeatAuthenticator.PACKET_BYTES];
        seal(CURRENT, payload, now - HeartbeatAuthenticator.WINDOW_MILLIS - 1, 3, now, 1,
                HeartbeatAuthenticator.KEY_CURRENT, stale);
        peer.send(new InetSocketAddress("127.0.0.1", 1), stale, 0, stale.length);

        var future = new byte[HeartbeatAuthenticator.PACKET_BYTES];
        seal(CURRENT, payload, now + HeartbeatAuthenticator.WINDOW_MILLIS + 1, 4, now, 1,
                HeartbeatAuthenticator.KEY_CURRENT, future);
        peer.send(new InetSocketAddress("127.0.0.1", 1), future, 0, future.length);

        var rotated = new byte[HeartbeatAuthenticator.PACKET_BYTES];
        seal(PREVIOUS, payload, now, 5, now, 1, HeartbeatAuthenticator.KEY_PREVIOUS, rotated);
        peer.send(new InetSocketAddress("127.0.0.1", 1), rotated, 0, rotated.length);

        node.cycle();
        assertEquals(1, node.received(), "previous key id is accepted during rotation");
        assertEquals(4, node.rejected());
        assertEquals(4L, node.detector().generationOf(new NodeId(2, "b")));

        peer.send(new InetSocketAddress("127.0.0.1", 1), rotated, 0, rotated.length);
        node.cycle();
        assertEquals(1, node.received(), "replay of the rotated packet is dropped");
        assertEquals(5, node.rejected());

        var ended = new HeartbeatAuthenticator(ClusterKey.of(CURRENT, null), clock);
        assertNull(ended.open(rotated, now, 1, new int[] {1, 2}, 2), "rotation ended: previous key id is rejected");
        assertEquals(1, ended.rejected(HeartbeatAuthenticator.Reason.TAG));
        node.close();
        net.close();
    }

    @Test
    void windowIsInclusiveAndAStalePacketDoesNotConsumeTheSequence() {
        var clock = new FakeClock(0L);
        var auth = new HeartbeatAuthenticator(ClusterKey.of(CURRENT, null), clock);
        clock.setWallMillis(50_000L);
        long now = clock.currentTimeMillis();
        var fresh = new byte[HeartbeatAuthenticator.PACKET_BYTES];
        var edge = new byte[HeartbeatAuthenticator.PACKET_BYTES];
        var past = new byte[HeartbeatAuthenticator.PACKET_BYTES];
        seal(CURRENT, body(2, 1), now, 2, 1, 1, HeartbeatAuthenticator.KEY_CURRENT, fresh);
        seal(CURRENT, body(2, 1), now - HeartbeatAuthenticator.WINDOW_MILLIS, 3, 1, 1,
                HeartbeatAuthenticator.KEY_CURRENT, edge);
        seal(CURRENT, body(2, 1), now - HeartbeatAuthenticator.WINDOW_MILLIS - 1, 4, 1, 1,
                HeartbeatAuthenticator.KEY_CURRENT, past);
        assertNull(open(auth, past, clock.currentTimeMillis()));
        assertNotNull(open(auth, fresh, clock.currentTimeMillis()));
        assertNotNull(open(auth, edge, clock.currentTimeMillis()));
        var futureEdge = new byte[HeartbeatAuthenticator.PACKET_BYTES];
        seal(CURRENT, body(2, 1), now + HeartbeatAuthenticator.WINDOW_MILLIS, 5, 1, 1,
                HeartbeatAuthenticator.KEY_CURRENT, futureEdge);
        assertNotNull(open(auth, futureEdge, clock.currentTimeMillis()));
        var tooNew = new byte[HeartbeatAuthenticator.PACKET_BYTES];
        seal(CURRENT, body(2, 1), now + HeartbeatAuthenticator.WINDOW_MILLIS + 1, 6, 1, 1,
                HeartbeatAuthenticator.KEY_CURRENT, tooNew);
        assertNull(open(auth, tooNew, clock.currentTimeMillis()));
        assertEquals(2, auth.rejected(HeartbeatAuthenticator.Reason.TIME));

        var again = new byte[HeartbeatAuthenticator.PACKET_BYTES];
        seal(CURRENT, body(3, 1), now, 1, 1, 1, HeartbeatAuthenticator.KEY_CURRENT, again);
        assertNotNull(open(auth, again, now), "a different sender keeps its own sequence");
        assertNull(open(auth, again, now));
        assertEquals(1, auth.rejected(HeartbeatAuthenticator.Reason.SEQ));
    }

    @Test
    void aForgedBodyUnderAValidTagIsDropped() {
        var auth = auth(ClusterKey.of(CURRENT, null));
        var garbage = new byte[HeartbeatCodec.BYTES];
        var packet = new byte[HeartbeatAuthenticator.PACKET_BYTES];
        seal(CURRENT, garbage, 10_000L, 1, 1, 1, HeartbeatAuthenticator.KEY_CURRENT, packet);
        assertNull(open(auth, packet, 10_000L));
        assertEquals(1, auth.rejected(HeartbeatAuthenticator.Reason.BODY));
        var macFlip = new byte[HeartbeatAuthenticator.PACKET_BYTES];
        seal(CURRENT, body(2, 1), 10_000L, 1, 1, 1, HeartbeatAuthenticator.KEY_CURRENT, macFlip);
        macFlip[HeartbeatAuthenticator.MAC_OFFSET] ^= 0x01;
        assertNull(open(auth, macFlip, 10_000L));
        assertEquals(1, auth.rejected(HeartbeatAuthenticator.Reason.TAG));
        var good = new byte[HeartbeatAuthenticator.PACKET_BYTES];
        seal(CURRENT, body(2, 1), 10_000L, 1, 1, 1, HeartbeatAuthenticator.KEY_CURRENT, good);
        assertNotNull(open(auth, good, 10_000L));
    }

    @Test
    void aRestartedSenderIsAcceptedAndAPreBootCaptureIsNot() {
        var clock = new FakeClock(0L);
        clock.setWallMillis(5_000L);
        var receiver = new HeartbeatAuthenticator(ClusterKey.of(CURRENT, null), clock);
        long now = 8_000L;
        var first = new byte[HeartbeatAuthenticator.PACKET_BYTES];
        seal(CURRENT, body(2, 1), now, 40, 5_000L, 1, HeartbeatAuthenticator.KEY_CURRENT, first);
        assertNotNull(open(receiver, first, now));
        assertEquals(1, receiver.trackedSenders());

        var restarted = new byte[HeartbeatAuthenticator.PACKET_BYTES];
        seal(CURRENT, body(2, 1), now, 1, 6_000L, 1, HeartbeatAuthenticator.KEY_CURRENT, restarted);
        assertNotNull(open(receiver, restarted, now), "a newer boot epoch resets the sequence");
        assertEquals(1, receiver.trackedSenders());

        var oldEpoch = new byte[HeartbeatAuthenticator.PACKET_BYTES];
        seal(CURRENT, body(2, 1), now, 41, 5_000L, 1, HeartbeatAuthenticator.KEY_CURRENT, oldEpoch);
        assertNull(open(receiver, oldEpoch, now), "a packet from the previous boot is rejected");
        assertEquals(1, receiver.rejected(HeartbeatAuthenticator.Reason.EPOCH));
        assertNull(open(receiver, restarted, now));
        assertEquals(1, receiver.rejected(HeartbeatAuthenticator.Reason.SEQ));

        var captured = new byte[HeartbeatAuthenticator.PACKET_BYTES];
        seal(CURRENT, body(2, 1), 4_000L, 1, 4_000L, 1, HeartbeatAuthenticator.KEY_CURRENT, captured);
        var booted = new FakeClock(0L);
        booted.setWallMillis(6_000L);
        var afterRestart = new HeartbeatAuthenticator(ClusterKey.of(CURRENT, null), booted);
        assertNull(open(afterRestart, captured, 6_000L), "a capture from before this process booted is rejected");
        assertEquals(0, afterRestart.trackedSenders());
        assertEquals(1, afterRestart.rejected(HeartbeatAuthenticator.Reason.TIME));
        var live = new byte[HeartbeatAuthenticator.PACKET_BYTES];
        seal(CURRENT, body(2, 1), 6_000L, 1, 9_000L, 1, HeartbeatAuthenticator.KEY_CURRENT, live);
        assertNotNull(open(afterRestart, live, 6_000L));
    }

    @Test
    void aPacketForAnotherPeerAndANonMemberAreDropped() {
        var auth = auth(ClusterKey.of(CURRENT, null));
        long now = 10_000L;
        var forPeer = new byte[HeartbeatAuthenticator.PACKET_BYTES];
        seal(CURRENT, body(2, 1), now, 1, 1, 1, HeartbeatAuthenticator.KEY_CURRENT, forPeer);
        assertNull(open(auth, forPeer, now, 2), "a packet sealed for node 1 is rejected by node 2");
        assertEquals(1, auth.rejected(HeartbeatAuthenticator.Reason.DEST));
        assertEquals(0, auth.trackedSenders());
        assertNotNull(open(auth, forPeer, now, 1));

        var stranger = new byte[HeartbeatAuthenticator.PACKET_BYTES];
        seal(CURRENT, body(9, 1), now, 1, 1, 1, HeartbeatAuthenticator.KEY_CURRENT, stranger);
        int before = auth.trackedSenders();
        assertNull(auth.open(stranger, now, 1, new int[] {1, 2}, 2));
        assertEquals(before, auth.trackedSenders(), "a non-member does not grow the sender map");
        assertEquals(1, auth.rejected(HeartbeatAuthenticator.Reason.MEMBER));

        auth.retainMembers(new int[] {1}, 1);
        assertEquals(0, auth.trackedSenders());
    }

    @Test
    void theInjectedClockDrivesTheServiceWindow() throws Exception {
        var clock = new FakeClock(0L);
        clock.setWallMillis(20_000L);
        var net = new SimNetwork();
        var link = net.link(new InetSocketAddress("127.0.0.1", 1));
        var peer = net.link(new InetSocketAddress("127.0.0.1", 2));
        var members = List.of(
                new Member(new NodeId(1, "a"), SITE, new InetSocketAddress("127.0.0.1", 1), 1L, MemberState.ALIVE, false),
                new Member(new NodeId(2, "b"), SITE, new InetSocketAddress("127.0.0.1", 2), 1L, MemberState.ALIVE, false));
        var node = LocalCluster.open(new NodeId(1, "a"), members, link, clock, Duration.ofMillis(100),
                Duration.ofSeconds(5), ClusterEvents.NONE, ClusterKey.of(CURRENT, null));
        var fresh = new byte[HeartbeatAuthenticator.PACKET_BYTES];
        seal(CURRENT, body(2, 1), clock.currentTimeMillis(), 1, clock.currentTimeMillis(), 1,
                HeartbeatAuthenticator.KEY_CURRENT, fresh);
        peer.send(new InetSocketAddress("127.0.0.1", 1), fresh, 0, fresh.length);
        node.cycle();
        assertEquals(1, node.received());

        clock.setWallMillis(clock.currentTimeMillis() + HeartbeatAuthenticator.WINDOW_MILLIS + 1);
        var late = new byte[HeartbeatAuthenticator.PACKET_BYTES];
        seal(CURRENT, body(2, 1), 20_000L, 2, 20_000L, 1, HeartbeatAuthenticator.KEY_CURRENT, late);
        peer.send(new InetSocketAddress("127.0.0.1", 1), late, 0, late.length);
        node.cycle();
        assertEquals(1, node.received());
        assertTrue(node.rejected(HeartbeatAuthenticator.Reason.TIME) > 0);
        node.close();
        net.close();
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

        var trimmed = "  " + secret + "\n";
        assertEquals(key, ClusterKey.fromEnvironment(
                Map.of(ClusterKey.ENV_CURRENT, trimmed, ClusterKey.ENV_PREVIOUS, " " + previous + " ")));
        var hex = new StringBuilder("hex:");
        for (byte b : secret.getBytes(StandardCharsets.UTF_8)) {
            hex.append(String.format("%02x", b));
        }
        var b64 = "base64:" + Base64.getEncoder().encodeToString(previous.getBytes(StandardCharsets.UTF_8));
        assertEquals(key, ClusterKey.fromEnvironment(
                Map.of(ClusterKey.ENV_CURRENT, hex.toString(), ClusterKey.ENV_PREVIOUS, b64)));

        var missing = assertThrows(IllegalArgumentException.class, () -> ClusterKey.fromEnvironment(Map.of()));
        assertFalse(missing.getMessage().contains(secret));
        var empty = assertThrows(IllegalArgumentException.class,
                () -> ClusterKey.fromEnvironment(Map.of(ClusterKey.ENV_CURRENT, "")));
        assertFalse(empty.getMessage().contains(secret));
        var shortSecret = "short-prev-zzzz";
        var tooShort = assertThrows(IllegalArgumentException.class,
                () -> ClusterKey.fromEnvironment(Map.of(ClusterKey.ENV_CURRENT, secret, ClusterKey.ENV_PREVIOUS, shortSecret)));
        assertFalse(tooShort.getMessage().contains(shortSecret));
        assertFalse(tooShort.getMessage().contains(secret));
        assertThrows(IllegalArgumentException.class,
                () -> ClusterKey.fromEnvironment(Map.of(ClusterKey.ENV_CURRENT, secret, ClusterKey.ENV_PREVIOUS, "")));
        assertThrows(IllegalArgumentException.class,
                () -> ClusterKey.fromEnvironment(Map.of(ClusterKey.ENV_CURRENT, "   ")));
        assertThrows(IllegalArgumentException.class, () -> ClusterKey.fromEnvironment(
                Map.of(ClusterKey.ENV_CURRENT, secret, ClusterKey.ENV_PREVIOUS, "   ")));
        assertThrows(IllegalArgumentException.class, () -> ClusterKey.parse("base64:****"));
        assertThrows(IllegalArgumentException.class, () -> ClusterKey.parse("hex:zz"));
        assertThrows(IllegalArgumentException.class, () -> ClusterKey.parse("hex:0a"));
        assertFalse(assertThrows(IllegalArgumentException.class, () -> ClusterKey.parse("hex:0a")).getMessage()
                .contains("0a"));
        assertThrows(NullPointerException.class, () -> ClusterKey.fromEnvironment(null));
        assertThrows(NullPointerException.class, () -> ClusterKey.of(null, null));
        assertThrows(IllegalArgumentException.class, () -> ClusterKey.of(new byte[15], null));
        assertThrows(IllegalArgumentException.class, () -> ClusterKey.of(CURRENT, new byte[15]));
        assertThrows(NullPointerException.class, () -> new HeartbeatAuthenticator(null, CLOCK));
        assertThrows(NullPointerException.class, () -> new HeartbeatAuthenticator(key(), null));
    }
}
