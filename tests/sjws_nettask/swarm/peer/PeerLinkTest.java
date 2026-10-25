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
        for (int i = 0; i < 200; i++) {
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
        assertEquals(Outcome.LOST, get(b.produce("a", "fwd.t", b("m3"), Durability.NONE, 5_000)).outcome());
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
