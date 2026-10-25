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
package sjws_nettask.swarm.peer;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.cluster.ClusterKey;
import sjws_nettask.cluster.TcpTransport;
import sjws_nettask.cp.CpTls;
import sjws_nettask.messaging.delivery.Partition;
import sjws_nettask.messaging.delivery.RetryPolicy;
import sjws_nettask.messaging.ledger.Durability;
import sjws_nettask.swarm.peer.PeerLink.Outcome;

/**
 * #236 PR 1: the forwarded produce over real loopback sockets. mTLS cases use {@link PeerSockets} with pinned SANs;
 * the go-back-N and failure cases use plain sockets (the transport is the same) so a test can cut connections.
 */
@Timeout(120)
class PeerLinkTest {

    static final ClusterKey KEY = ClusterKey.of("swarm-peer-key-for-tests-0123".getBytes(StandardCharsets.US_ASCII), null);
    static final RetryPolicy POLICY = new RetryPolicy(3, 100, 1_000);
    static final PeerLink.Limits FAST = new PeerLink.Limits(256, 2048, 1024, 3_600_000L, 150, 1 << 20, 1024, 5, 300);

    @TempDir
    Path dir;

    final List<AutoCloseable> open = new ArrayList<>();
    final Map<String, Partition> partitions = new ConcurrentHashMap<>();
    final List<Integer> origins = new CopyOnWriteArrayList<>();

    @AfterEach
    void closeAll() throws Exception {
        for (int i = open.size() - 1; i >= 0; i--) {
            open.get(i).close();
        }
        for (var p : partitions.values()) {
            p.close();
        }
    }

    static String map(int n) throws IOException {
        var sb = new StringBuilder();
        for (int i = 1; i <= n; i++) {
            int port;
            try (var s = new ServerSocket(0)) {
                port = s.getLocalPort();
            }
            sb.append(i > 1 ? "," : "").append((char) ('a' + i - 1)).append('=').append(i).append("@127.0.0.1:").append(port);
        }
        return sb.toString();
    }

    /** Owner that serves {@code fwd.*} topics from a per-test directory and records the authenticated origins. */
    PeerLink.Owner owner(String node) {
        return (from, topic) -> {
            origins.add(from.wire());
            if (!topic.startsWith("fwd.")) {
                return null;
            }
            var key = node + "/" + topic;
            var p = partitions.get(key);
            if (p == null) {
                p = Partition.open(dir.resolve(node), topic, POLICY, System::currentTimeMillis);
                partitions.put(key, p);
            }
            return p;
        };
    }

    PeerLink link(String map, String self, TcpTransport.SocketFactory sockets, PeerLink.Owner owner,
            PeerLink.Limits limits) throws IOException {
        var l = PeerLink.open(PeerConfig.parse(map, self), KEY, sockets, owner, limits);
        open.add(l);
        return l;
    }

    PeerLink tlsLink(String map, String self, PeerTestCa ca, String certNode, PeerLink.Owner owner) throws Exception {
        var config = PeerConfig.parse(map, self);
        var tls = CpTls.peerContext(ca.node(certNode));
        var l = PeerLink.open(config, KEY, new PeerSockets(tls, config), owner, FAST);
        open.add(l);
        return l;
    }

    static void await(BooleanSupplier ok, String what) throws InterruptedException {
        long end = System.nanoTime() + 20_000_000_000L;
        while (!ok.getAsBoolean()) {
            assertTrue(System.nanoTime() < end, what);
            Thread.sleep(5);
        }
    }

    static PeerLink.Result get(CompletableFuture<PeerLink.Result> f) throws Exception {
        return f.get(30, TimeUnit.SECONDS);
    }

    static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    List<String> payloads(String node, String topic) {
        var p = partitions.get(node + "/" + topic);
        p.subscribe("check", true);
        var out = new ArrayList<String>();
        for (var d : p.poll("check", "c", 10_000, 60_000)) {
            out.add(new String(d.payload(), StandardCharsets.UTF_8));
        }
        return out;
    }

    @Test
    void aWriteForwardedOverMtlsIsAppliedUnderTheAuthenticatedOrigin() throws Exception {
        var ca = new PeerTestCa(dir.resolve("ca"));
        var map = map(2);
        tlsLink(map, "a", ca, "a", owner("a"));
        var b = tlsLink(map, "b", ca, "b", PeerLink.Owner.NONE);
        var r = get(b.produce("a", "fwd.t", b("hello"), Durability.ASYNC, 5_000));
        assertEquals(Outcome.APPENDED, r.outcome());
        assertTrue(r.ok());
        assertEquals(0, r.offset());
        assertEquals(List.of(2), origins);
        assertEquals(List.of("hello"), payloads("a", "fwd.t"));
        assertEquals(1, b.answered(Outcome.APPENDED));
    }

    @Test
    void aTopicTheOwnerDoesNotServeIsForbiddenAndTheNextWriteStartsAFreshStream() throws Exception {
        var map = map(2);
        var a = link(map, "a", TcpTransport.SocketFactory.PLAIN, owner("a"), FAST);
        var b = link(map, "b", TcpTransport.SocketFactory.PLAIN, PeerLink.Owner.NONE, FAST);
        assertEquals(Outcome.FORBIDDEN, get(b.produce("a", "tasks.p#0", b("x"), null, 5_000)).outcome());
        assertEquals(Outcome.FORBIDDEN, get(b.produce("a", "tasks.p#0", b("y"), null, 5_000)).outcome());
        assertEquals(2, a.answered(Outcome.FORBIDDEN), "two refusals sent");
        assertEquals(Outcome.FORBIDDEN, get(a.produce("b", "fwd.t", b("z"), null, 5_000)).outcome(),
                "Owner.NONE serves nothing");
    }

    @Test
    void localRefusalsNeedNoNetwork() throws Exception {
        var map = map(2);
        var b = link(map, "b", TcpTransport.SocketFactory.PLAIN, PeerLink.Owner.NONE, FAST);
        assertEquals(Outcome.UNAVAILABLE, get(b.produce("zz", "fwd.t", b("x"), null, 1)).outcome(), "not a pinned peer");
        assertEquals(Outcome.UNAVAILABLE, get(b.produce("b", "fwd.t", b("x"), null, 1)).outcome(), "itself");
        assertEquals(Outcome.FORBIDDEN, get(b.produce("a", "fwd.t", new byte[(1 << 20) + 1], null, 1)).outcome(),
                "oversize record refused before sending");
        assertEquals(Outcome.FORBIDDEN, get(b.produce("a", "", b("x"), null, 1)).outcome());
        assertEquals(Outcome.FORBIDDEN, get(b.produce("a", "x".repeat(256), b("x"), null, 1)).outcome());
        b.close();
        assertEquals(Outcome.UNAVAILABLE, get(b.produce("a", "fwd.t", b("x"), null, 1)).outcome(), "closed");
        assertThrows(IllegalArgumentException.class, () -> new PeerLink.Limits(0, 1, 1, 0, 1, 0, 1, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new PeerLink.Limits(1025, 1, 1, 0, 1, 0, 1, 1, 0));
    }

    @Test
    void anOversizeRecordIsRefusedByTheOwnerBeforeItIsApplied() throws Exception {
        var map = map(2);
        var small = new PeerLink.Limits(256, 2048, 1024, 3_600_000L, 150, 10, 1024, 5, 300);
        link(map, "a", TcpTransport.SocketFactory.PLAIN, owner("a"), small);
        var b = link(map, "b", TcpTransport.SocketFactory.PLAIN, PeerLink.Owner.NONE, FAST);
        assertEquals(Outcome.FORBIDDEN, get(b.produce("a", "fwd.t", new byte[11], null, 5_000)).outcome());
        assertTrue(origins.isEmpty(), "never reached the owner's resolver");
    }

    @Test
    void retriesAfterACutConnectionDeliverEveryWriteOnceAndInOrder() throws Exception {
        var map = map(2);
        var cutter = new Cutter();
        var a = link(map, "a", TcpTransport.SocketFactory.PLAIN, owner("a"), FAST);
        var b = link(map, "b", cutter, PeerLink.Owner.NONE, FAST);
        var futures = new ArrayList<CompletableFuture<PeerLink.Result>>();
        assertTrue(get(b.produce("a", "fwd.t", b("m0"), Durability.NONE, 20_000)).ok()); // connected: cuts land
        for (int i = 1; i < 200; i++) {
            futures.add(b.produce("a", "fwd.t", b("m" + i), Durability.NONE, 20_000));
            if (i % 40 == 20) {
                cutter.cut(); // frames queued meanwhile are dropped; later ones get OUT_OF_ORDER or time out
            }
        }
        for (var f : futures) {
            assertTrue(get(f).ok(), String.valueOf(f.get()));
        }
        var expected = new ArrayList<String>();
        for (int i = 0; i < 200; i++) {
            expected.add("m" + i);
        }
        assertEquals(expected, payloads("a", "fwd.t"), "go-back-N keeps the order, dedup keeps it exactly once");
        assertEquals(0, b.pending());
        assertTrue(cutter.closed.get() >= 1, "live connections were cut mid-stream");
        // whether a cut lands on in-flight frames (rewind) or between them is timing; both must end exactly once
    }

    @Test
    void theInflightCapAnswersBusyAndAnUnreachableOwnerTimesOutThenOpensTheBreaker() throws Exception {
        var map = map(2);
        var limits = new PeerLink.Limits(2, 2048, 1024, 3_600_000L, 50, 1 << 20, 1024, 1, 60_000);
        var b = link(map, "b", TcpTransport.SocketFactory.PLAIN, PeerLink.Owner.NONE, limits); // "a" never starts
        var first = b.produce("a", "fwd.t", b("1"), null, 300);
        var second = b.produce("a", "fwd.t", b("2"), null, 300);
        assertEquals(Outcome.BUSY, get(b.produce("a", "fwd.t", b("3"), null, 300)).outcome());
        assertEquals(2, b.pending());
        assertEquals(Outcome.TIMEOUT, get(first).outcome());
        assertEquals(Outcome.TIMEOUT, get(second).outcome(), "everything behind a failed write fails with it");
        await(() -> b.breakerOpen("a"), "one timeout opens the breaker at breakerTimeouts=1");
        assertEquals(Outcome.UNAVAILABLE, get(b.produce("a", "fwd.t", b("4"), null, 300)).outcome());
        assertFalse(b.breakerOpen("zz"));
    }

    @Test
    void anOwnerThatLostAcknowledgedSeqsAnswersLost() throws Exception {
        var map = map(2);
        var fresh = new java.util.concurrent.atomic.AtomicBoolean();
        var restarted = new ConcurrentHashMap<String, Partition>();
        PeerLink.Owner owner = (from, topic) -> {
            if (!fresh.get()) {
                return owner("a").partition(from, topic);
            }
            return restarted.computeIfAbsent(topic, t -> {
                try {
                    return Partition.open(dir.resolve("restarted"), t, POLICY, System::currentTimeMillis);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
        };
        link(map, "a", TcpTransport.SocketFactory.PLAIN, owner, FAST);
        var b = link(map, "b", TcpTransport.SocketFactory.PLAIN, PeerLink.Owner.NONE, FAST);
        for (int i = 0; i < 3; i++) {
            assertTrue(get(b.produce("a", "fwd.t", b("m" + i), Durability.NONE, 5_000)).ok());
        }
        fresh.set(true); // the owner came back without the stream's history (NONE durability)
        var lost = get(b.produce("a", "fwd.t", b("m3"), Durability.NONE, 5_000));
        assertEquals(Outcome.LOST, lost.outcome());
        // the owner lost state it had acknowledged: a pending write may have landed before that, so fate unknown
        assertEquals(new PeerLink.Problem(503, PeerLink.Problem.OUTCOME_UNKNOWN_TYPE, false), lost.problem());
        assertEquals(Outcome.APPENDED, get(b.produce("a", "fwd.t", b("m4"), Durability.NONE, 5_000)).outcome(),
                "a new stream starts at seq 1");
        for (var p : restarted.values()) {
            p.close();
        }
    }

    @Test
    void anOwnerFailureIsAFixedCodeAndRetiresTheStream() throws Exception {
        var map = map(2);
        PeerLink.Owner broken = (from, topic) -> {
            throw new IOException("disk on fire at /secret/path");
        };
        var a = link(map, "a", TcpTransport.SocketFactory.PLAIN, broken, FAST);
        var b = link(map, "b", TcpTransport.SocketFactory.PLAIN, PeerLink.Owner.NONE, FAST);
        var r = get(b.produce("a", "fwd.t", b("x"), null, 5_000));
        assertEquals(Outcome.FAILED, r.outcome());
        assertEquals(-1, r.offset());
        assertEquals(1, a.answered(Outcome.FAILED));
    }

    @Test
    void groupDurabilityAnswersOnlyOnceDurable() throws Exception {
        var map = map(2);
        link(map, "a", TcpTransport.SocketFactory.PLAIN, owner("a"), FAST);
        var b = link(map, "b", TcpTransport.SocketFactory.PLAIN, PeerLink.Owner.NONE, FAST);
        var r = get(b.produce("a", "fwd.t", b("durable"), Durability.GROUP, 5_000));
        assertEquals(Outcome.APPENDED, r.outcome());
        var again = get(b.produce("a", "fwd.t", b("again"), Durability.GROUP, 5_000));
        assertEquals(1, again.offset());
    }

    @Test
    void aPeerPresentingAnUnpinnedCertificateIsRefused() throws Exception {
        var ca = new PeerTestCa(dir.resolve("ca"));
        var map = map(2);
        var a = tlsLink(map, "a", ca, "a", owner("a"));
        var b = tlsLink(map, "b", ca, "mallory", PeerLink.Owner.NONE); // chains to the CA, SAN not pinned
        assertEquals(Outcome.TIMEOUT, get(b.produce("a", "fwd.t", b("x"), null, 400)).outcome());
        await(() -> a.transport().handshakeFailures() >= 1, "the owner refused the unpinned identity");
        assertTrue(origins.isEmpty());
    }

    @Test
    void aCertificateNamingTwoPinnedNodesOrNoneIdentifiesNoPeer() throws Exception {
        var ca = new PeerTestCa(dir.resolve("ca"));
        var map = map(3);
        var config = PeerConfig.parse(map, "a");
        var a = tlsLink(map, "a", ca, "a", owner("a"));
        // b's process presents a certificate naming both b and c: identifies neither
        var twoNames = PeerConfig.parse(map, "b");
        var l2 = PeerLink.open(twoNames, KEY, new PeerSockets(CpTls.peerContext(ca.node("bc", PeerConfig.SAN_PREFIX + "b",
                PeerConfig.SAN_PREFIX + "c")), twoNames), PeerLink.Owner.NONE, FAST);
        open.add(l2);
        assertEquals(Outcome.TIMEOUT, get(l2.produce("a", "fwd.t", b("x"), null, 400)).outcome());
        var noSan = PeerConfig.parse(map, "c");
        var l3 = PeerLink.open(noSan, KEY, new PeerSockets(CpTls.peerContext(ca.node("bare")), noSan),
                PeerLink.Owner.NONE, FAST);
        open.add(l3);
        assertEquals(Outcome.TIMEOUT, get(l3.produce("a", "fwd.t", b("x"), null, 400)).outcome());
        await(() -> a.transport().handshakeFailures() >= 2, "both identities refused by the owner");
        assertTrue(origins.isEmpty());
        assertEquals(List.of(), PeerSockets.sans(certOf(ca)));
        assertEquals(config.byName("a"), config.self());
    }

    /** A certificate without a SAN extension, for {@link PeerSockets#sans}. */
    static java.security.cert.X509Certificate certOf(PeerTestCa ca) throws Exception {
        var pem = ca.node("nosan", new String[0]).certPem();
        try (var in = java.nio.file.Files.newInputStream(pem)) {
            return (java.security.cert.X509Certificate) java.security.cert.CertificateFactory.getInstance("X.509")
                    .generateCertificate(in);
        }
    }

    @Test
    void limitsAreRangeChecked() {
        int[][] bad = {
            {0, 1, 1, 0, 1, 0, 1, 1, 0}, {1025, 1, 1, 0, 1, 0, 1, 1, 0}, {1, 0, 1, 0, 1, 0, 1, 1, 0},
            {1, 1, 0, 0, 1, 0, 1, 1, 0}, {1, 1, 1, -1, 1, 0, 1, 1, 0}, {1, 1, 1, 0, 0, 0, 1, 1, 0},
            {1, 1, 1, 0, 1, -1, 1, 1, 0}, {1, 1, 1, 0, 1, PeerLink.Limits.MAX_RECORD_BYTES + 1, 1, 1, 0},
            {1, 1, 1, 0, 1, 0, 0, 1, 0}, {1, 1, 1, 0, 1, 0, 1, 0, 0}, {1, 1, 1, 0, 1, 0, 1, 1, -1},
        };
        for (var v : bad) {
            assertThrows(IllegalArgumentException.class,
                    () -> new PeerLink.Limits(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8]),
                    java.util.Arrays.toString(v));
        }
        assertEquals(256, PeerLink.Limits.DEFAULT.inflightPerProducer());
        var r = new PeerLink.Result(Outcome.DUPLICATE, 3);
        assertTrue(r.ok());
        assertFalse(new PeerLink.Result(Outcome.LOST, -1).ok());
    }

    @Test
    void aCertificateFromAnotherCaIsRefused() throws Exception {
        var ca = new PeerTestCa(dir.resolve("ca"));
        var other = new PeerTestCa(dir.resolve("other"));
        var map = map(2);
        var a = tlsLink(map, "a", ca, "a", owner("a"));
        var b = tlsLink(map, "b", other, "b", PeerLink.Owner.NONE);
        assertEquals(Outcome.TIMEOUT, get(b.produce("a", "fwd.t", b("x"), null, 400)).outcome());
        assertTrue(origins.isEmpty());
        assertTrue(a.transport().handshakeFailures() >= 1 || b.transport().sendDrops() >= 1);
    }

    @Test
    void theDialerRefusesAnOwnerThatDoesNotPresentItsPinnedSan() throws Exception {
        var ca = new PeerTestCa(dir.resolve("ca"));
        var map = map(2);
        tlsLink(map, "a", ca, "b", owner("a")); // the listener at a's address presents b's identity
        var config = PeerConfig.parse(map, "b");
        var sockets = new PeerSockets(CpTls.peerContext(ca.node("b")), config);
        var l = PeerLink.open(config, KEY, sockets, PeerLink.Owner.NONE, FAST);
        open.add(l);
        assertEquals(Outcome.TIMEOUT, get(l.produce("a", "fwd.t", b("x"), null, 400)).outcome());
        await(() -> sockets.identityRefused() >= 1, "dialer refused the wrong server identity");
        assertThrows(IOException.class, () -> sockets.connect(new InetSocketAddress("127.0.0.1", 1)), "unpinned address");
        assertTrue(origins.isEmpty());
    }

    @Test
    void aCorrectlyMacedFrameClaimingAnotherPeersWireOnItsOwnConnectionIsRefusedAndHeldOff() throws Exception {
        // T11 (r4 blocker 1): c holds the shared key and its own valid certificate, but sends frames as wire 2 (b)
        var ca = new PeerTestCa(dir.resolve("ca"));
        var map = map(3);
        var a = tlsLink(map, "a", ca, "a", owner("a"));
        var spoof = PeerConfig.parse(map, "c");
        var nodes = new ArrayList<>(spoof.nodes());
        var c = nodes.get(2);
        var asB = new PeerConfig.Node("b", 2, c.address(), PeerConfig.SAN_PREFIX + "b");
        nodes.set(1, new PeerConfig.Node("c", 3, nodes.get(1).address(), PeerConfig.SAN_PREFIX + "c"));
        nodes.set(2, asB);
        var forged = new PeerConfig(asB, nodes);
        var sockets = new PeerSockets(CpTls.peerContext(ca.node("c")), forged);
        var mallory = PeerLink.open(forged, KEY, sockets, PeerLink.Owner.NONE, FAST);
        open.add(mallory);
        assertEquals(Outcome.TIMEOUT, get(mallory.produce("a", "fwd.t", b("x"), null, 400)).outcome());
        await(() -> a.transport().originMismatches() >= 1, "origin mismatch");
        assertEquals(1, a.transport().rejected(TcpTransport.Reason.ORIGIN));
        // the reconnect inside the hold-off is refused at admission, before any frame is read
        assertEquals(Outcome.TIMEOUT, get(mallory.produce("a", "fwd.t", b("y"), null, 400)).outcome());
        await(() -> a.transport().originHeldOff() >= 1, "held off");
        assertEquals(1, a.transport().originMismatches(), "the held-off reconnect never got to send a frame");
        assertTrue(origins.isEmpty(), "nothing reached the owner");
    }

    @Test
    void malformedFramesAreCountedAndDropped() throws Exception {
        var map = map(2);
        var a = link(map, "a", TcpTransport.SocketFactory.PLAIN, owner("a"), FAST);
        var config = PeerConfig.parse(map, "b");
        var raw = TcpTransport.open(config.self().id(), config.members(), KEY, sjws_nettask.cluster.Clock.SYSTEM, 8,
                TcpTransport.SocketFactory.PLAIN, PeerLink.DOMAIN, true);
        open.add(raw);
        var rpc = raw.attach(config.self().id(), (from, f) -> { });
        var to = config.byName("a").id();
        var g = new sjws_nettask.cluster.GroupId("fwd.t");
        rpc.send(to, new sjws_nettask.cluster.SimTransport.Frame(g, 99, new byte[0]));
        rpc.send(to, new sjws_nettask.cluster.SimTransport.Frame(g, PeerLink.PRODUCE, new byte[3]));
        rpc.send(to, new sjws_nettask.cluster.SimTransport.Frame(g, PeerLink.PRODUCE, new byte[17]));
        var badDurability = java.nio.ByteBuffer.allocate(17).putLong(1).putLong(1).put((byte) 99).array();
        rpc.send(to, new sjws_nettask.cluster.SimTransport.Frame(g, PeerLink.PRODUCE, badDurability));
        rpc.send(to, new sjws_nettask.cluster.SimTransport.Frame(g, PeerLink.ACK, new byte[5]));
        var badCode = java.nio.ByteBuffer.allocate(33).putLong(1).putLong(1).put((byte) 99).putLong(0).putLong(0).array();
        rpc.send(to, new sjws_nettask.cluster.SimTransport.Frame(g, PeerLink.ACK, badCode));
        var stray = java.nio.ByteBuffer.allocate(33).putLong(1).putLong(1).put((byte) 0).putLong(0).putLong(0).array();
        rpc.send(to, new sjws_nettask.cluster.SimTransport.Frame(g, PeerLink.ACK, stray));
        await(() -> a.malformed.get() == 6, "six malformed frames counted, got " + a.malformed.get());
        assertTrue(origins.isEmpty());
        assertTrue(PeerLink.counters(a).containsKey("forwarded"));
    }

    @Test
    void aResendOfAWriteStillWaitingForDurabilityIsAnsweredOnlyAfterIt() throws Exception {
        var map = map(2);
        var a = link(map, "a", TcpTransport.SocketFactory.PLAIN, owner("a"), FAST);
        var config = PeerConfig.parse(map, "b");
        var answers = new CopyOnWriteArrayList<long[]>();
        var raw = TcpTransport.open(config.self().id(), config.members(), KEY, sjws_nettask.cluster.Clock.SYSTEM, 64,
                TcpTransport.SocketFactory.PLAIN, PeerLink.DOMAIN, true);
        open.add(raw);
        var rpc = raw.attach(config.self().id(), (from, f) -> {
            var bb = java.nio.ByteBuffer.wrap(f.body());
            answers.add(new long[] {bb.getLong(), bb.getLong(), bb.get(), bb.getLong()});
        });
        var to = config.byName("a").id();
        var g = new sjws_nettask.cluster.GroupId("fwd.g");
        int group = Durability.GROUP.ordinal() + 1;
        for (int round = 1; round <= 20; round++) {
            var body = java.nio.ByteBuffer.allocate(17 + 1).putLong(7).putLong(round).put((byte) group).put((byte) 'x')
                    .array();
            rpc.send(to, new sjws_nettask.cluster.SimTransport.Frame(g, PeerLink.PRODUCE, body));
            rpc.send(to, new sjws_nettask.cluster.SimTransport.Frame(g, PeerLink.PRODUCE, body)); // resend at once
        }
        await(() -> answers.size() == 40, "both answers of every write, got " + answers.size());
        for (int round = 1; round <= 20; round++) {
            long seq = round;
            var mine = answers.stream().filter(x -> x[1] == seq).toList();
            assertEquals(2, mine.size());
            assertEquals(mine.get(0)[3], mine.get(1)[3], "the resend answers the original offset");
            assertEquals(seq - 1, mine.get(0)[3]);
        }
        assertEquals(20, a.answered(Outcome.APPENDED));
        assertEquals(20, a.answered(Outcome.DUPLICATE));
    }

    /** Plain sockets that a test can cut, to drop frames and force go-back-N. */
    // ---- HA review of #251, B1: no socket write under a lock the readers or the ticker need ----

    /** Binds {@code port} and accepts connections that are never read: a hung owner. */
    static final class Deaf implements AutoCloseable {
        final ServerSocket server;
        final List<Socket> accepted = new CopyOnWriteArrayList<>();

        Deaf(int port) throws IOException {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.setReceiveBufferSize(4096);
            server.bind(new InetSocketAddress("127.0.0.1", port));
            Thread.ofPlatform().daemon().start(() -> {
                while (true) {
                    try {
                        accepted.add(server.accept());
                    } catch (IOException closed) {
                        return;
                    }
                }
            });
        }

        @Override
        public void close() throws IOException {
            server.close();
            for (var s : accepted) {
                s.close();
            }
        }
    }

    /** A link whose transport never cuts a blocked write: only the B1 fix keeps the ticker and readers going. */
    PeerLink unbounded(String map, String self, PeerLink.Owner owner, PeerLink.Limits limits) throws IOException {
        return unbounded(map, self, TcpTransport.SocketFactory.PLAIN, owner, limits);
    }

    PeerLink unbounded(String map, String self, TcpTransport.SocketFactory sockets, PeerLink.Owner owner,
            PeerLink.Limits limits) throws IOException {
        var l = PeerLink.open(PeerConfig.parse(map, self), KEY, sockets, owner, limits, 0);
        open.add(l);
        return l;
    }

    @Test
    void aCallerBlockedForeverOnAHungOwnerHoldsNoLockTheTickerOrReadersNeed() throws Exception {
        var map = map(3);
        var deaf = new Deaf(port(map, "a"));
        open.add(deaf);
        var c = link(map, "c", TcpTransport.SocketFactory.PLAIN, owner("c"), FAST);
        // requestTimeoutMs 2 s: no resend goes through the writer before the flooder is stuck in its inline write;
        // at 2 s the ticker must take the hung stream's lock to rewind and then retire it
        var slow = new PeerLink.Limits(256, 2048, 1024, 3_600_000L, 2_000, 1 << 20, 1024, 5, 300);
        var b = unbounded(map, "b", PeerLink.Owner.NONE, slow);
        var big = new byte[256 << 10];
        var hung = new CopyOnWriteArrayList<CompletableFuture<PeerLink.Result>>();
        hung.add(b.produce("a", "fwd.hung", b("x"), Durability.NONE, 300)); // through the writer, which connects
        await(() -> deaf.accepted.size() == 1, "connected");
        Thread.sleep(200); // the writer is idle again: from here every write is inline on the flooder's thread
        var calls = new AtomicLong();
        var flooder = Thread.ofPlatform().daemon().start(() -> {
            while (true) {
                hung.add(b.produce("a", "fwd.hung", big, Durability.NONE, 300));
                calls.incrementAndGet();
            }
        });
        long before;
        do { // wait until the flooder is stuck inside one produce() call (a's buffers are full)
            before = calls.get();
            Thread.sleep(500);
        } while (calls.get() != before || before == 0);
        assertTrue(flooder.isAlive());
        assertTrue(hung.size() < 256, "stuck before the inflight cap, not answering BUSY: " + hung.size());
        // the ticker still walks every stream: the hung stream's returned writes get their TIMEOUT
        for (var f : hung) {
            assertEquals(Outcome.TIMEOUT, get(f).outcome());
        }
        // and another owner's ACKs, delivered on b's reader thread into onAck, still flow
        for (int i = 0; i < 100; i++) {
            assertTrue(get(b.produce("c", "fwd.ok", b("m" + i), Durability.NONE, 5_000)).ok());
        }
        assertEquals(calls.get(), before, "the flooder is still blocked in its socket write");
        assertEquals(0, b.transport().writeStalls());
        assertEquals(100, payloads("c", "fwd.ok").size());
    }

    static final TcpTransport.SocketFactory TINY_BUFFERS = new TcpTransport.SocketFactory() {
        @Override
        public ServerSocket listen(InetSocketAddress bind) throws IOException {
            var server = new ServerSocket();
            server.setReuseAddress(true);
            server.setReceiveBufferSize(4096);
            server.bind(bind);
            return server;
        }

        @Override
        public Socket connect(InetSocketAddress to) throws IOException {
            var socket = new Socket();
            socket.setTcpNoDelay(true);
            socket.setSendBufferSize(4096);
            socket.connect(to, 2_000);
            return socket;
        }
    };

    static int port(String map, String node) {
        for (var e : map.split(",")) {
            if (e.startsWith(node + "=")) {
                return Integer.parseInt(e.substring(e.lastIndexOf(':') + 1));
            }
        }
        throw new IllegalArgumentException(node);
    }

    @Test
    void anOwnerThatNeverReadsStallsOnlyItsCallerWhileAcksAndTheTickerKeepFlowing() throws Exception {
        var map = map(3);
        var deaf = new Deaf(port(map, "a")); // "a" accepts and never reads
        open.add(deaf);
        var c = link(map, "c", TcpTransport.SocketFactory.PLAIN, owner("c"), FAST);
        var b = link(map, "b", TcpTransport.SocketFactory.PLAIN, PeerLink.Owner.NONE, FAST);
        var big = new byte[256 << 10];
        var hung = new CopyOnWriteArrayList<CompletableFuture<PeerLink.Result>>();
        var longestCallNanos = new AtomicLong();
        var stop = new AtomicBoolean();
        var flooder = Thread.ofPlatform().daemon().start(() -> {
            while (!stop.get()) {
                long t0 = System.nanoTime();
                hung.add(b.produce("a", "fwd.hung", big, Durability.NONE, 400));
                longestCallNanos.accumulateAndGet(System.nanoTime() - t0, Math::max);
            }
        });
        // the flooder fills a's socket and blocks inside produce(); the write-stall bound must cut it
        await(() -> b.transport().writeStalls() >= 1, "a write to the deaf owner was blocked and cut");
        // meanwhile another owner's ACKs (b's reader for c, onAck) and the ticker keep working
        for (int i = 0; i < 100; i++) {
            assertTrue(get(b.produce("c", "fwd.ok", b("m" + i), Durability.NONE, 5_000)).ok());
        }
        await(() -> hung.stream().anyMatch(f -> f.isDone() && f.join().outcome() == Outcome.TIMEOUT),
                "the ticker fires the hung stream's budget");
        stop.set(true);
        flooder.join(10_000);
        assertFalse(flooder.isAlive(), "produce() to the deaf owner returns");
        for (var f : hung) {
            var r = get(f);
            assertFalse(r.ok(), String.valueOf(r));
        }
        assertTrue(longestCallNanos.get() < 5_000_000_000L, "a blocked produce() is bounded: "
                + longestCallNanos.get() / 1_000_000 + " ms");
        assertEquals(100, payloads("c", "fwd.ok").size());
    }

    @Test
    void twoLinksForwardingLargeRecordsToEachOtherDoNotDeadlock() throws Exception {
        var map = map(2);
        // no write-stall bound: nothing but the B1 fix can break a cycle of blocked writes
        // 4 KiB socket buffers: an inline write of a large record blocks until the other side's reader drains it
        var a = unbounded(map, "a", TINY_BUFFERS, owner("a"), FAST);
        var b = unbounded(map, "b", TINY_BUFFERS, owner("b"), FAST);
        int threads = 6;
        int each = 30;
        var record = new byte[900 << 10]; // a few records fill both directions' socket buffers at once
        var futures = new CopyOnWriteArrayList<CompletableFuture<PeerLink.Result>>();
        var workers = new ArrayList<Thread>();
        for (var from : List.of(a, b)) {
            String to = from == a ? "b" : "a";
            for (int t = 0; t < threads; t++) {
                String topic = "fwd.x"; // one stream per direction: every caller and onAck share its lock
                workers.add(Thread.ofPlatform().start(() -> {
                    for (int i = 0; i < each; i++) {
                        futures.add(from.produce(to, topic, record, Durability.NONE, 30_000));
                    }
                }));
            }
        }
        for (var w : workers) {
            w.join(60_000);
            assertFalse(w.isAlive(), "producers on both sides return");
        }
        for (var f : futures) {
            assertTrue(get(f).ok(), String.valueOf(f.get()));
        }
        for (var node : List.of("a", "b")) {
            assertEquals(threads * each, payloads(node, "fwd.x").size(), node + " exactly once");
        }
    }

    // ---- #258 outcomes over a forwarded hop: unknown fate stays unknown, refusals say not appended ----

    /** A scripted owner "a": answers every PRODUCE with {@code code} for its topic, or not at all when absent. */
    sjws_nettask.cluster.ClusterRpc scriptedOwner(String map, Map<String, Outcome> answers) throws IOException {
        var config = PeerConfig.parse(map, "a");
        var raw = TcpTransport.open(config.self().id(), config.members(), KEY, sjws_nettask.cluster.Clock.SYSTEM, 64,
                TcpTransport.SocketFactory.PLAIN, PeerLink.DOMAIN, true);
        open.add(raw);
        var rpc = new sjws_nettask.cluster.ClusterRpc[1];
        rpc[0] = raw.attach(config.self().id(), (from, f) -> {
            var code = answers.get(f.group().value());
            if (f.type() != PeerLink.PRODUCE || code == null) {
                return;
            }
            var in = java.nio.ByteBuffer.wrap(f.body());
            var ack = java.nio.ByteBuffer.allocate(33).putLong(in.getLong()).putLong(in.getLong())
                    .put((byte) code.ordinal()).putLong(-1).putLong(0).array();
            rpc[0].send(from, new sjws_nettask.cluster.SimTransport.Frame(f.group(), PeerLink.ACK, ack));
        });
        return rpc[0];
    }

    @Test
    void eachForwardedOutcomeReachesTheCallerWithItsProblemType() throws Exception {
        var map = map(2);
        scriptedOwner(map, Map.of("fwd.unknown", Outcome.OUTCOME_UNKNOWN, "fwd.failed", Outcome.FAILED,
                "fwd.forbidden", Outcome.FORBIDDEN));
        var b = link(map, "b", TcpTransport.SocketFactory.PLAIN, PeerLink.Owner.NONE, FAST);

        var unknown = get(b.produce("a", "fwd.unknown", b("x"), Durability.GROUP, 5_000));
        assertEquals(Outcome.OUTCOME_UNKNOWN, unknown.outcome(), "never collapsed into FAILED");
        assertEquals(new PeerLink.Problem(503, PeerLink.Problem.OUTCOME_UNKNOWN_TYPE, false), unknown.problem());

        var failed = get(b.produce("a", "fwd.failed", b("x"), Durability.GROUP, 5_000));
        assertEquals(Outcome.FAILED, failed.outcome());
        assertEquals(new PeerLink.Problem(503, PeerLink.Problem.NOT_APPENDED_TYPE, true), failed.problem());

        var forbidden = get(b.produce("a", "fwd.forbidden", b("x"), Durability.GROUP, 5_000));
        assertEquals(Outcome.FORBIDDEN, forbidden.outcome());
        assertEquals(new PeerLink.Problem(400, PeerLink.Problem.NOT_APPENDED_TYPE, false), forbidden.problem());

        // no answer within the budget: the write may have landed
        var silent = get(b.produce("a", "fwd.silent", b("x"), Durability.GROUP, 300));
        assertEquals(Outcome.TIMEOUT, silent.outcome());
        assertEquals(new PeerLink.Problem(503, PeerLink.Problem.OUTCOME_UNKNOWN_TYPE, false), silent.problem());
        assertEquals(1, b.answered(Outcome.OUTCOME_UNKNOWN));
    }

    @Test
    void writesBehindAnOutcomeUnknownAreUnknownToo() throws Exception {
        var map = map(2);
        // only the first write is answered; the rest wait behind it on the same stream
        var answered = new java.util.concurrent.atomic.AtomicBoolean();
        var config = PeerConfig.parse(map, "a");
        var raw = TcpTransport.open(config.self().id(), config.members(), KEY, sjws_nettask.cluster.Clock.SYSTEM, 64,
                TcpTransport.SocketFactory.PLAIN, PeerLink.DOMAIN, true);
        open.add(raw);
        var rpc = new sjws_nettask.cluster.ClusterRpc[1];
        var seen = new java.util.concurrent.atomic.AtomicInteger();
        rpc[0] = raw.attach(config.self().id(), (from, f) -> {
            if (f.type() != PeerLink.PRODUCE || seen.incrementAndGet() < 3 || !answered.compareAndSet(false, true)) {
                return; // answer once all three arrived, with seq 1's outcome
            }
            var ack = java.nio.ByteBuffer.allocate(33).putLong(java.nio.ByteBuffer.wrap(f.body()).getLong()).putLong(1)
                    .put((byte) Outcome.OUTCOME_UNKNOWN.ordinal()).putLong(-1).putLong(0).array();
            rpc[0].send(from, new sjws_nettask.cluster.SimTransport.Frame(f.group(), PeerLink.ACK, ack));
        });
        var b = link(map, "b", TcpTransport.SocketFactory.PLAIN, PeerLink.Owner.NONE, FAST);
        var futures = List.of(b.produce("a", "fwd.t", b("1"), Durability.GROUP, 5_000),
                b.produce("a", "fwd.t", b("2"), Durability.GROUP, 5_000),
                b.produce("a", "fwd.t", b("3"), Durability.GROUP, 5_000));
        for (var f : futures) {
            assertEquals(Outcome.OUTCOME_UNKNOWN, get(f).outcome(), "applied writes may be behind it");
        }
    }

    @Test
    void anOwnerWhoseDurabilityFailsAfterApplyAnswersOutcomeUnknownNotFailed() {
        assertEquals(Outcome.OUTCOME_UNKNOWN, PeerLink.afterDurabilityFailure(Partition.ForwardOutcome.APPENDED));
        assertEquals(Outcome.OUTCOME_UNKNOWN, PeerLink.afterDurabilityFailure(Partition.ForwardOutcome.DUPLICATE));
        assertEquals(Outcome.FAILED, PeerLink.afterDurabilityFailure(Partition.ForwardOutcome.BUSY));
    }

    @Test
    void noForwardedOutcomeIsARedirectOrNotLeaderAndUnknownFateIsNeverRetryable() {
        for (var o : Outcome.values()) {
            var p = PeerLink.Problem.of(o);
            if (o == Outcome.APPENDED || o == Outcome.DUPLICATE) {
                assertNull(p);
                continue;
            }
            assertTrue(p.status() == 503 || p.status() == 400, o + " " + p);
            assertFalse(p.type().contains("not-leader"), o + " " + p);
            if (p.type().equals(PeerLink.Problem.OUTCOME_UNKNOWN_TYPE)) {
                assertEquals(503, p.status());
                assertFalse(p.retryable(), o + ": a resend could duplicate it");
            } else {
                assertEquals(PeerLink.Problem.NOT_APPENDED_TYPE, p.type());
                assertEquals(p.retryable() ? 503 : 400, p.status(), o.toString());
            }
        }
    }

    // ---- Security review of #251 at 6278107, B1: the write-stall bound over real mTLS ----

    /** A pinned mTLS peer at {@code node}'s address that completes handshakes and never reads. */
    static final class DeafTls implements AutoCloseable {
        final javax.net.ssl.SSLServerSocket server;
        final List<Socket> handshaken = new CopyOnWriteArrayList<>();

        DeafTls(PeerTestCa ca, String node, int port) throws Exception {
            var tls = CpTls.peerContext(ca.node(node));
            server = (javax.net.ssl.SSLServerSocket) tls.context().getServerSocketFactory().createServerSocket();
            server.setSSLParameters(tls.parameters());
            server.setReuseAddress(true);
            server.setReceiveBufferSize(4096);
            server.bind(new InetSocketAddress("127.0.0.1", port));
            Thread.ofPlatform().daemon().start(() -> {
                while (true) {
                    try {
                        var s = (javax.net.ssl.SSLSocket) server.accept();
                        Thread.ofPlatform().daemon().start(() -> {
                            try {
                                s.startHandshake();
                                handshaken.add(s);
                            } catch (IOException e) {
                                // a refused or cut handshake
                            }
                        });
                    } catch (IOException closed) {
                        return;
                    }
                }
            });
        }

        @Override
        public void close() throws IOException {
            server.close();
            for (var s : handshaken) {
                try {
                    s.setSoLinger(true, 0);
                } catch (IOException ignored) {
                    // already closed
                }
                s.close();
            }
        }
    }

    /** Floods {@code owner} with 256 KiB writes from a fresh thread; records the longest produce() call. */
    static Thread flood(PeerLink link, String owner, AtomicLong calls, AtomicLong longest, AtomicBoolean stop) {
        var big = new byte[256 << 10];
        return Thread.ofPlatform().daemon().start(() -> {
            while (!stop.get()) {
                long t0 = System.nanoTime();
                link.produce(owner, "fwd.hung", big, Durability.NONE, 300);
                longest.accumulateAndGet(System.nanoTime() - t0, Math::max);
                calls.incrementAndGet();
            }
        });
    }

    @Test
    void overMtlsAStalledWriteIsReleasedTheWatchdogKeepsBoundingAndCloseReturns() throws Exception {
        var ca = new PeerTestCa(dir.resolve("ca"));
        var map = map(3);
        var deafA = new DeafTls(ca, "a", port(map, "a"));
        var deafC = new DeafTls(ca, "c", port(map, "c"));
        open.add(deafA);
        open.add(deafC);
        var config = PeerConfig.parse(map, "b");
        var b = PeerLink.open(config, KEY, new PeerSockets(CpTls.peerContext(ca.node("b")), config),
                PeerLink.Owner.NONE, FAST); // write-stall bound = requestTimeoutMs = 150 ms; closed by hand below
        long boundNanos = FAST.requestTimeoutMs() * 1_000_000L;
        b.produce("a", "fwd.hung", b("x"), Durability.NONE, 300);
        b.produce("c", "fwd.hung", b("x"), Durability.NONE, 300);
        await(() -> deafA.handshaken.size() == 1 && deafC.handshaken.size() == 1, "both mTLS connections up");
        Thread.sleep(200);
        var stop = new AtomicBoolean();
        var callsA = new AtomicLong();
        var longestA = new AtomicLong();
        var floodA = flood(b, "a", callsA, longestA, stop);
        // the first stall: the blocked TLS write to a must be cut within about the bound
        long t0 = System.nanoTime();
        await(() -> b.transport().writeStalls() >= 1, "a's stalled TLS write was cut");
        long before = callsA.get();
        long released = System.nanoTime();
        while (callsA.get() == before && System.nanoTime() - released < 5_000_000_000L) {
            Thread.sleep(5);
        }
        assertTrue(callsA.get() > before, "the stalled produce() to a returned after the stall close");
        // the watchdog is not wedged: it still bounds a second peer
        var callsC = new AtomicLong();
        var longestC = new AtomicLong();
        long stallsBefore = b.transport().writeStalls();
        var floodC = flood(b, "c", callsC, longestC, stop);
        await(() -> b.transport().writeStalls() > stallsBefore + 1, "the watchdog keeps cutting stalled writes");
        stop.set(true);
        floodA.join(10_000);
        floodC.join(10_000);
        assertFalse(floodA.isAlive() || floodC.isAlive(), "every produce() call returns");
        // a call that blocks spans at most one bound plus the watchdog period (and some scheduling slack)
        long limit = 20 * boundNanos;
        assertTrue(longestA.get() < limit, "longest produce() to a " + longestA.get() / 1_000_000 + " ms");
        assertTrue(longestC.get() < limit, "longest produce() to c " + longestC.get() / 1_000_000 + " ms");
        // and close() returns even with writes blocked on both peers' full buffers
        var stop2 = new AtomicBoolean();
        floodA = flood(b, "a", new AtomicLong(), new AtomicLong(), stop2);
        floodC = flood(b, "c", new AtomicLong(), new AtomicLong(), stop2);
        Thread.sleep(100);
        var closer = Thread.ofPlatform().start(() -> b.close());
        closer.join(5_000);
        stop2.set(true);
        assertFalse(closer.isAlive(), "PeerLink.close() returned");
        floodA.join(5_000);
        floodC.join(5_000);
        assertTrue(System.nanoTime() - t0 < 30_000_000_000L);
    }

    static final class Cutter implements TcpTransport.SocketFactory {
        private final List<Socket> sockets = new CopyOnWriteArrayList<>();

        @Override
        public ServerSocket listen(InetSocketAddress bind) throws IOException {
            return PLAIN.listen(bind);
        }

        @Override
        public Socket connect(InetSocketAddress to) throws IOException {
            var s = PLAIN.connect(to);
            sockets.add(s);
            return s;
        }

        final java.util.concurrent.atomic.AtomicInteger closed = new java.util.concurrent.atomic.AtomicInteger();

        void cut() {
            for (var s : sockets) {
                try {
                    closed.incrementAndGet();
                    s.close();
                } catch (IOException ignored) {
                    // cutting
                }
            }
            sockets.clear();
        }
    }
}
