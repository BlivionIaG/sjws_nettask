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

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Raft voters in one JVM, each with its own {@link TcpTransport} on a loopback socket. */
@Timeout(60)
class TcpTransportTest {

    private static final SiteId SITE = new SiteId("local");
    private static final GroupId GROUP = new GroupId("cp-config");
    private static final ClusterKey KEY = ClusterKey.of("0123456789abcdef0123".getBytes(StandardCharsets.US_ASCII),
            null);
    /** lease 100 ms; 100 * 1.01 + 10 = 111 ms is under the 400 ms election timeout (#151 rule). */
    private static final GroupOptions OPTIONS = new GroupOptions(Durability.RELAXED, 64 * 1024,
            Duration.ofMillis(400), Duration.ofMillis(50), 1_000_000);

    private final List<AutoCloseable> open = new ArrayList<>();

    @AfterEach
    void closeAll() {
        for (int i = open.size() - 1; i >= 0; i--) {
            try {
                open.get(i).close();
            } catch (Exception ignored) {
                // Already closed by the test.
            }
        }
    }

    @Test
    void optionsMeetTheLeaseRule() {
        double drift = 0.01;
        long margin = Duration.ofMillis(10).toNanos();
        assertTrue(OPTIONS.leaseNanos() * (1 + drift) + margin < OPTIONS.electionTimeout().toNanos());
    }

    @Test
    void threeVotersElectCommitAndSurviveALeaderKill() throws Exception {
        var members = members(false, false, false);
        var cluster = start(members, VoterSet.majority(members, ids(members)));
        var leader = awaitLeader(cluster, null);
        assertCommits(cluster.get(leader));
        kill(cluster, leader);
        var next = awaitLeader(cluster, leader);
        assertNotEquals(leader, next);
        assertCommits(cluster.get(next));
    }

    @Test
    void witnessKeepsTheLeaderAcrossADataPartitionAndElectsAfterLeaderIsolation() throws Exception {
        var members = members(false, false, true);
        var witness = members.get(2).id();
        var cluster = start(members, VoterSet.dataAndWitness(members.get(0), members.get(1), members.get(2)));
        var leader = awaitLeader(cluster, null);
        assertNotEquals(witness, leader);
        var other = members.get(0).id().equals(leader) ? members.get(1).id() : members.get(0).id();

        // Data nodes cannot see each other; the leader still reaches the witness and commits.
        partition(cluster, leader, other);
        assertCommits(cluster.get(leader));
        Thread.sleep(OPTIONS.electionTimeout().toMillis() * 3);
        assertTrue(cluster.get(leader).group.isLeader(), "pre-vote must keep the leader");
        heal(cluster, leader, other);
        // Let the other data node catch up, or the witness rightly refuses its shorter log.
        assertCommits(cluster.get(leader));
        Thread.sleep(OPTIONS.heartbeat().toMillis() * 10);

        // Now the leader is cut from everyone; the other data node and the witness elect.
        partition(cluster, leader, other);
        partition(cluster, leader, witness);
        var next = awaitLeader(cluster, leader);
        assertEquals(other, next);
        assertCommits(cluster.get(next));
    }

    @Test
    void rejectsForgedReplayedMisaddressedAndNonMemberFrames() throws Exception {
        var members = members(false, false, false);
        var a = TcpTransport.open(members.get(0).id(), members, KEY, Clock.SYSTEM, 4);
        open.add(a);
        var mac = a.currentMac();
        var to = members.get(1).id();
        var frame = new SimTransport.Frame(GROUP, 7, new byte[] {1, 2, 3});
        var b = TcpTransport.open(to, members, KEY, Clock.SYSTEM, 4);
        open.add(b);
        long now = System.currentTimeMillis();
        var bMac = b.currentMac();

        var good = a.seal(mac, HeartbeatAuthenticator.KEY_CURRENT, to, now, 1, frame);
        var accepted = b.open(good, bMac, null, now);
        assertNotNull(accepted);
        assertEquals(members.get(0).id(), accepted.from());
        assertArrayEquals(new byte[] {1, 2, 3}, accepted.frame().body());
        assertEquals(7, accepted.frame().type());
        assertNull(b.open(good, bMac, null, now));
        assertEquals(1, b.rejected(TcpTransport.Reason.SEQ));

        var tampered = a.seal(mac, HeartbeatAuthenticator.KEY_CURRENT, to, now, 2, frame);
        tampered[tampered.length - 40] ^= 1;
        assertNull(b.open(tampered, bMac, null, now));
        assertEquals(1, b.rejected(TcpTransport.Reason.TAG));
        var previous = a.seal(mac, HeartbeatAuthenticator.KEY_PREVIOUS, to, now, 2, frame);
        assertNull(b.open(previous, bMac, null, now));
        assertEquals(2, b.rejected(TcpTransport.Reason.TAG));

        var misaddressed = a.seal(mac, HeartbeatAuthenticator.KEY_CURRENT, members.get(2).id(), now, 3, frame);
        assertNull(b.open(misaddressed, bMac, null, now));
        assertEquals(1, b.rejected(TcpTransport.Reason.DEST));

        var foreign = sealFrom(new NodeId(9, "n9"), members, to, now, frame);
        assertNull(b.open(foreign, bMac, null, now));
        assertEquals(1, b.rejected(TcpTransport.Reason.MEMBER));
        var fromSelf = b.seal(bMac, HeartbeatAuthenticator.KEY_CURRENT, to, now, 1, frame);
        assertNull(b.open(fromSelf, bMac, null, now));
        assertEquals(2, b.rejected(TcpTransport.Reason.MEMBER));

        assertNull(b.open(a.seal(mac, 0, to, now - 1, 9, frame), bMac, null, now));
        assertEquals(1, b.rejected(TcpTransport.Reason.EPOCH));
        assertNull(b.open(a.seal(mac, 0, to, now + 60_000, 9, frame), bMac, null, now));
        assertEquals(2, b.rejected(TcpTransport.Reason.EPOCH));
        assertNull(b.open(a.seal(mac, 0, to, now, 0, frame), bMac, null, now));
        assertEquals(2, b.rejected(TcpTransport.Reason.SEQ));
        // A newer epoch (restart) resets the sequence; an older one is accepted after silence.
        assertNotNull(b.open(a.seal(mac, 0, to, now + 1, 1, frame), bMac, null, now));
        assertNotNull(b.open(a.seal(mac, 0, to, now, 1, frame), bMac, null,
                now + TcpTransport.EPOCH_SILENCE_MILLIS));

        var badVersion = good.clone();
        badVersion[0] = 9;
        assertNull(b.open(badVersion, bMac, null, now));
        var badKey = good.clone();
        badKey[1] = 5;
        assertNull(b.open(badKey, bMac, null, now));
        assertEquals(2, b.rejected(TcpTransport.Reason.MALFORMED));
    }

    @Test
    void fullQueuesCountDropsAndNeverBlock() throws Exception {
        var members = members(false, false, false);
        var t = TcpTransport.open(members.get(0).id(), members, KEY, Clock.SYSTEM, 1);
        open.add(t);
        var rpc = t.attach(members.get(0).id(), (from, frame) -> { });
        assertThrows(IllegalArgumentException.class, () -> t.attach(members.get(0).id(), (from, frame) -> { }));
        assertThrows(IllegalArgumentException.class, () -> t.attach(members.get(1).id(), (from, frame) -> { }));
        var frame = new SimTransport.Frame(GROUP, 1, new byte[8]);
        long started = System.nanoTime();
        for (int i = 0; i < 10_000; i++) {
            rpc.send(members.get(1).id(), frame); // nobody listens there
        }
        rpc.send(new NodeId(42, "nobody"), frame);
        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(2));
        assertTrue(t.sendDrops() >= 9_000, "drops " + t.sendDrops());
        assertEquals(0, t.deliver(10));
        t.close();
        rpc.send(members.get(1).id(), frame);
    }

    @Test
    void framesCrossTheSocketAndAFullInboundQueueCountsDrops() throws Exception {
        var members = members(false, false, false);
        var a = TcpTransport.open(members.get(0).id(), members, KEY, Clock.SYSTEM, 64);
        var b = TcpTransport.open(members.get(1).id(), members, KEY, Clock.SYSTEM, 2);
        open.add(a);
        open.add(b);
        var got = new ArrayList<SimTransport.Frame>();
        var rpc = a.attach(members.get(0).id(), (from, frame) -> { });
        b.attach(members.get(1).id(), (from, frame) -> got.add(frame));
        for (int i = 0; i < 10; i++) {
            rpc.send(members.get(1).id(), new SimTransport.Frame(GROUP, i, new byte[] {(byte) i}));
        }
        await(() -> b.inboundDrops() >= 8 || got.size() + b.inboundDrops() >= 10, "inbound drops");
        Thread.sleep(200);
        b.deliver(100);
        assertEquals(2, got.size());
        assertEquals(8, b.inboundDrops());
        assertEquals(0, a.sendDrops());
        assertTrue(b.localPort() > 0);
        assertThrows(IllegalArgumentException.class, () -> TcpTransport.open(new NodeId(77, "x"), members, KEY,
                Clock.SYSTEM, 1));
        assertThrows(IllegalArgumentException.class, () -> TcpTransport.open(members.get(2).id(), members, KEY,
                Clock.SYSTEM, 0));
        b.cut(members.get(0).id());
        rpc.send(members.get(1).id(), new SimTransport.Frame(GROUP, 99, new byte[1]));
        Thread.sleep(200);
        b.deliver(100);
        assertEquals(2, got.size());
        b.heal(members.get(0).id());
    }

    @Test
    void oversizedLengthPrefixClosesTheConnection() throws Exception {
        var members = members(false, false, false);
        var b = TcpTransport.open(members.get(1).id(), members, KEY, Clock.SYSTEM, 4);
        open.add(b);
        try (var raw = new Socket("127.0.0.1", b.localPort())) {
            var out = new java.io.DataOutputStream(raw.getOutputStream());
            out.writeInt(Integer.MAX_VALUE);
            out.flush();
            await(() -> b.rejected(TcpTransport.Reason.MALFORMED) == 1, "malformed");
        }
    }

    private byte[] sealFrom(NodeId from, List<Member> base, NodeId to, long now, SimTransport.Frame frame)
            throws IOException {
        var withStranger = new ArrayList<>(base);
        withStranger.add(member(from.wire(), false, port()));
        var t = TcpTransport.open(from, withStranger, KEY, Clock.SYSTEM, 1);
        open.add(t);
        return t.seal(t.currentMac(), 0, to, now, 1, frame);
    }

    private Map<NodeId, Node> start(List<Member> members, VoterSet voters) throws IOException {
        var cluster = new HashMap<NodeId, Node>();
        for (var member : members) {
            var transport = TcpTransport.open(member.id(), members, KEY, Clock.SYSTEM, 1024);
            var node = ReplicatedNode.open(member.id(), voters, Clock.SYSTEM, new MemoryLogStorage(), transport);
            var group = node.openGroup(GROUP, OPTIONS, null);
            open.add(transport);
            open.add(node);
            cluster.put(member.id(), new Node(node, transport, group));
        }
        for (var node : cluster.values()) {
            node.node.begin(Duration.ofMillis(5));
        }
        return cluster;
    }

    private static NodeId awaitLeader(Map<NodeId, Node> cluster, NodeId not) throws InterruptedException {
        var found = new AtomicReference<NodeId>();
        try {
            await(() -> {
                for (var entry : cluster.entrySet()) {
                    if (!entry.getKey().equals(not) && !entry.getValue().dead && entry.getValue().group.isLeader()) {
                        found.set(entry.getKey());
                        return true;
                    }
                }
                return false;
            }, "leader");
        } catch (AssertionError timedOut) {
            fail("no leader: " + describe(cluster));
        }
        return found.get();
    }

    private static String describe(Map<NodeId, Node> cluster) {
        var sb = new StringBuilder();
        for (var e : cluster.entrySet()) {
            var t = e.getValue().transport;
            sb.append(e.getKey().name()).append(e.getValue().dead ? " DEAD" : "").append(" camp=")
                    .append(e.getValue().dead ? "-" : e.getValue().node.campaigning(GROUP)).append(" leader=")
                    .append(e.getValue().group.leader()).append(" drops=").append(t.sendDrops()).append('/').append(t.inboundDrops());
            for (var r : TcpTransport.Reason.values()) {
                sb.append(' ').append(r).append('=').append(t.rejected(r));
            }
            sb.append("; ");
        }
        return sb.toString();
    }

    private static void assertCommits(Node leader) throws Exception {
        var committed = leader.node.append(GROUP, List.of(ByteBuffer.wrap("x".getBytes(StandardCharsets.UTF_8))))
                .get(10, TimeUnit.SECONDS);
        assertTrue(committed.index() > 0);
    }

    private static void kill(Map<NodeId, Node> cluster, NodeId id) {
        var node = cluster.get(id);
        node.dead = true;
        node.node.close();
        node.transport.close();
    }

    private static void partition(Map<NodeId, Node> cluster, NodeId a, NodeId b) {
        cluster.get(a).transport.cut(b);
        cluster.get(b).transport.cut(a);
    }

    private static void heal(Map<NodeId, Node> cluster, NodeId a, NodeId b) {
        cluster.get(a).transport.heal(b);
        cluster.get(b).transport.heal(a);
    }

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail("timed out waiting for " + what);
            }
            Thread.sleep(10);
        }
    }

    private static List<Member> members(boolean... witness) throws IOException {
        var list = new ArrayList<Member>();
        for (int i = 0; i < witness.length; i++) {
            list.add(member(i + 1, witness[i], port()));
        }
        return list;
    }

    private static java.util.Set<NodeId> ids(List<Member> members) {
        var ids = new java.util.LinkedHashSet<NodeId>();
        for (var member : members) {
            ids.add(member.id());
        }
        return ids;
    }

    private static Member member(int wire, boolean witness, int port) {
        return new Member(new NodeId(wire, "n" + wire), SITE, new InetSocketAddress("127.0.0.1", port), 1L,
                MemberState.ALIVE, witness);
    }

    private static int port() throws IOException {
        try (var probe = new ServerSocket(0)) {
            return probe.getLocalPort();
        }
    }

    private static final class Node {
        private final ReplicatedNode node;
        private final TcpTransport transport;
        private final ReplicationGroup group;
        private volatile boolean dead;

        private Node(ReplicatedNode node, TcpTransport transport, ReplicationGroup group) {
            this.node = node;
            this.transport = transport;
            this.group = group;
        }
    }
}
