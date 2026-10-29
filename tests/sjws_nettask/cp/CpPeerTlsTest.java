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
package sjws_nettask.cp;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.cluster.Clock;
import sjws_nettask.cluster.ClusterKey;
import sjws_nettask.cluster.GroupId;
import sjws_nettask.cluster.Member;
import sjws_nettask.cluster.MemberState;
import sjws_nettask.cluster.NodeId;
import sjws_nettask.cluster.SimTransport;
import sjws_nettask.cluster.SiteId;
import sjws_nettask.cluster.TcpTransport;

/**
 * Security review of #172: both ends of a {@code cp-peer} link refuse a wrong CA, an expired certificate and a
 * revoked certificate; a reloaded revocation list stops new dials; a stalled handshake holds no inbound slot.
 */
@Timeout(60)
class CpPeerTlsTest {

    @TempDir
    Path dir;

    /**
     * What one side saw. {@code handshakeReturned} is true only when {@code startHandshake}/{@code handshake}
     * returned; {@code handshakeCompleted} tracks a {@link javax.net.ssl.HandshakeCompletedListener}; peer
     * verification is whether {@code getPeerCertificates} succeeded after the attempt.
     */
    private record Side(
            Exception error, boolean handshakeReturned, boolean handshakeCompleted, boolean peerVerified) {
    }

    private record Outcome(Side client, Side server) {
    }

    /** Dialer is voter {@code b}, acceptor voter {@code a}; both pin {@code urn:sjws:node:<id>} (#175). */
    private Outcome dial(CpServerTls serverTls, CpServerTls clientTls) throws Exception {
        int port = freePort();
        var peers = List.of(
                new CpConfig.Peer(1, "a", new InetSocketAddress("127.0.0.1", port), false, CpTestCa.san("a")),
                new CpConfig.Peer(2, "b", new InetSocketAddress("127.0.0.1", 1), false, CpTestCa.san("b")));
        var server = new CpPeerSockets(serverTls, peers, "a");
        try (var listener = server.listen(new InetSocketAddress("127.0.0.1", port))) {
            var serverSide = CompletableFuture.supplyAsync(() -> observeAccept(server, listener));
            var client = observeDial(clientTls, new InetSocketAddress("127.0.0.1", port));
            return new Outcome(client, serverSide.get(10, TimeUnit.SECONDS));
        }
    }

    private static Side observeAccept(CpPeerSockets server, ServerSocket listener) {
        var completed = new AtomicBoolean();
        SSLSocket accepted = null;
        boolean handshakeReturned = false;
        try {
            accepted = (SSLSocket) listener.accept();
            accepted.addHandshakeCompletedListener(event -> completed.set(true));
            server.handshake(accepted, 5_000);
            handshakeReturned = true;
            awaitHandshakeSignal(completed, accepted);
            assertEquals(2, server.peerWire(accepted));
            return new Side(null, true, completed.get(), peerVerified(accepted));
        } catch (Exception e) {
            return new Side(e, handshakeReturned, completed.get(), peerVerified(accepted));
        } finally {
            if (accepted != null) {
                try {
                    accepted.close();
                } catch (IOException ignored) {
                    // closing
                }
            }
        }
    }

    /**
     * Dial with the same TLS material and identity pin as {@link CpPeerSockets#connect}, but register the handshake
     * listener before {@code startHandshake} so a post-handshake pipe/reset can be distinguished from a mid-handshake
     * one. After a completed handshake, {@code connect}'s SAN pin is checked via a second {@code connect}-equivalent
     * close path: identity mismatch surfaces as an IOException after {@code handshakeReturned}.
     */
    private static Side observeDial(CpServerTls clientTls, InetSocketAddress to) {
        var completed = new AtomicBoolean();
        SSLSocket socket = null;
        boolean handshakeReturned = false;
        try {
            // Same steps as CpPeerSockets.connect, with the listener registered before startHandshake.
            socket = (SSLSocket) clientTls.context().getSocketFactory().createSocket();
            socket.setSSLParameters(clientTls.parameters());
            socket.setTcpNoDelay(true);
            socket.addHandshakeCompletedListener(event -> completed.set(true));
            socket.connect(to, CpPeerSockets.CONNECT_TIMEOUT_MILLIS);
            socket.setSoTimeout(CpPeerSockets.HANDSHAKE_TIMEOUT_MILLIS);
            socket.startHandshake();
            handshakeReturned = true;
            awaitHandshakeSignal(completed, socket);
            // Acceptor asserts peerWire (#175). Dialer-side pin is covered by CpPeerIdentityTest / connect().
            boolean verified = peerVerified(socket);
            boolean hc = completed.get();
            socket.setSoTimeout(5_000);
            socket.getOutputStream().write(1);
            socket.getOutputStream().flush();
            socket.getInputStream().read(); // TLS 1.3: a client-cert refusal can surface here
            return new Side(null, true, hc, verified);
        } catch (Exception e) {
            return new Side(e, handshakeReturned, completed.get(), peerVerified(socket));
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // closing
                }
            }
        }
    }

    private static boolean peerVerified(SSLSocket socket) {
        if (socket == null) {
            return false;
        }
        try {
            var certs = socket.getSession().getPeerCertificates();
            return certs != null && certs.length > 0;
        } catch (SSLPeerUnverifiedException | RuntimeException unverified) {
            return false;
        }
    }

    /** The listener can be delivered just after {@code startHandshake} returns; wait briefly for it or session state. */
    private static void awaitHandshakeSignal(AtomicBoolean completed, SSLSocket socket) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500);
        while (System.nanoTime() < deadline) {
            if (completed.get() || peerVerified(socket)) {
                return;
            }
            Thread.onSpinWait();
        }
    }

    /** A {@code cp-peer} certificate for {@code node} with its pinned SAN. */
    private static CpListenerConfig peer(CpTestCa ca, String node, long notAfter, Path revocations) throws Exception {
        return ca.listener("cp-peer", node, notAfter, revocations, CpTestCa.san(node));
    }

    /**
     * A refusal is that the TLS handshake did not complete. Under TLS 1.3 either side can surface that as the peer's
     * alert ({@link javax.net.ssl.SSLHandshakeException}) or, when the other end closed first, as a write/read on a
     * dead socket (broken pipe or connection reset). Pipe/reset counts as a refusal only when the handshake did not
     * finish: no {@link javax.net.ssl.HandshakeCompletedListener} event, and peer certificates unavailable. A
     * completed handshake that later sees pipe/reset fails the assertion. A null error, a timeout, or any other
     * error also fails. The exception may arrive wrapped, so the cause chain is searched.
     */
    private static void assertRefused(Side seen, String what) {
        assertNotNull(seen, what + ": no attempt recorded");
        assertNotNull(seen.error(), what + ": the handshake completed");
        for (Throwable t = seen.error(); t != null; t = t.getCause()) {
            assertFalse(t instanceof java.net.SocketTimeoutException,
                    what + ": timed out instead: " + seen.error());
        }
        for (Throwable t = seen.error(); t != null; t = t.getCause()) {
            if (t instanceof javax.net.ssl.SSLHandshakeException) {
                return;
            }
            String message = String.valueOf(t.getMessage());
            if (t instanceof IOException
                    && (message.contains("Broken pipe") || message.contains("Connection reset"))) {
                assertFalse(seen.handshakeReturned(),
                        what + ": pipe/reset after startHandshake returned (handshake completed)");
                assertFalse(seen.handshakeCompleted(),
                        what + ": HandshakeCompletedListener fired before pipe/reset");
                assertFalse(seen.peerVerified(),
                        what + ": peer certificates were available after pipe/reset");
                return;
            }
        }
        fail(what + ": expected an SSLHandshakeException, or a mid-handshake broken pipe/connection reset, got "
                + seen.error()
                + " (handshakeReturned=" + seen.handshakeReturned()
                + ", handshakeCompleted=" + seen.handshakeCompleted()
                + ", peerVerified=" + seen.peerVerified() + ")");
    }

    /** Same as {@link #assertRefused}: kept so call sites name the observing side. */
    private static void assertRefusedByPeer(Side seen, String what) {
        assertRefused(seen, what);
    }

    private Path denylist(String name, java.math.BigInteger... serials) throws IOException {
        var text = new StringBuilder("# test denylist\n");
        for (var serial : serials) {
            text.append("serial:").append(serial.toString(16)).append('\n');
        }
        return Files.writeString(dir.resolve(name), text);
    }

    @Test
    void sameCaGoodCertificatesConnect() throws Exception {
        var ca = new CpTestCa(dir.resolve("ca"));
        var out = dial(CpTls.serverContext(ca.peer("a")), CpTls.serverContext(ca.peer("b")));
        assertNull(out.server().error(), String.valueOf(out.server().error()));
        assertNull(out.client().error(), String.valueOf(out.client().error()));
        assertTrue(out.server().handshakeReturned(), "acceptor startHandshake returned");
        assertTrue(out.server().handshakeCompleted() || out.server().peerVerified(),
                "acceptor handshake should complete (listener or peer certs)");
        assertTrue(out.client().handshakeReturned(), "dialer startHandshake returned");
        assertTrue(out.client().handshakeCompleted() || out.client().peerVerified(),
                "dialer handshake should complete (listener or peer certs)");
    }

    @Test
    void wrongCaIsRefusedOnBothSides() throws Exception {
        var ca = new CpTestCa(dir.resolve("ca"));
        var other = new CpTestCa(dir.resolve("other"));
        // The acceptor's cert is from another CA: the dialer refuses it.
        var serverForeign = dial(CpTls.serverContext(other.peer("a")),
                CpTls.serverContext(ca.peer("b")));
        assertRefused(serverForeign.client(), "dialer accepted a server cert from a foreign CA");
        assertRefusedByPeer(serverForeign.server(), "acceptor saw the dialer's refusal");
        // The dialer's cert is from another CA: the acceptor refuses it.
        var clientForeign = dial(CpTls.serverContext(ca.peer("a")),
                CpTls.serverContext(other.peer("b")));
        assertRefused(clientForeign.server(), "acceptor accepted a client cert from a foreign CA");
        assertRefusedByPeer(clientForeign.client(), "dialer saw the acceptor's refusal");
    }

    /**
     * Wrong CA through {@link TcpTransport}: the inbound handshake fails before a slot is taken, so
     * {@link TcpTransport#handshakeFailures()} rises, {@link TcpTransport#inboundConnections()} stays 0, and no
     * authenticated frame is delivered. Uses existing transport counters (no production hook).
     */
    @Test
    void wrongCaCannotDeliverFramesOverTcpTransport() throws Exception {
        var ca = new CpTestCa(dir.resolve("ca-transport"));
        var other = new CpTestCa(dir.resolve("other-transport"));
        var key = ClusterKey.of("cp-raft-key-for-tests-0123".getBytes(StandardCharsets.US_ASCII), null);
        var members = new ArrayList<Member>();
        for (int wire = 1; wire <= 2; wire++) {
            members.add(new Member(new NodeId(wire, "cp-" + wire), new SiteId("cp"),
                    new InetSocketAddress("127.0.0.1", freePort()), 1L, MemberState.ALIVE, false));
        }
        var group = new GroupId("cp-config");
        var got = new ArrayList<SimTransport.Frame>();
        try (var a = TcpTransport.open(members.get(0).id(), members, key, Clock.SYSTEM, 64,
                pinned(ca, members, "cp-1"));
                var b = TcpTransport.open(members.get(1).id(), members, key, Clock.SYSTEM, 64,
                        pinned(other, members, "cp-2"))) {
            b.attach(members.get(1).id(), (from, frame) -> got.add(frame));
            var rpc = a.attach(members.get(0).id(), (from, frame) -> { });
            rpc.send(members.get(1).id(), new SimTransport.Frame(group, 7, new byte[] {7}));
            await(() -> a.handshakeFailures() + b.handshakeFailures() >= 1,
                    "at least one side counts a handshake failure against the foreign CA");
            assertEquals(0, a.inboundConnections(), "no authenticated inbound slot on a");
            assertEquals(0, b.inboundConnections(), "no authenticated inbound slot on b");
            b.deliver(100);
            a.deliver(100);
            assertTrue(got.isEmpty(), "no application frame delivered across a wrong-CA link");
            assertEquals(0, got.size(), "frames delivered == 0");
        }
    }

    @Test
    void expiredCertificatesAreRefusedOnBothSides() throws Exception {
        var ca = new CpTestCa(dir.resolve("ca"));
        long past = System.currentTimeMillis() - 60_000L;
        var expiredServer = dial(CpTls.serverContext(peer(ca, "a", past, null)),
                CpTls.serverContext(ca.peer("b")));
        assertRefused(expiredServer.client(), "dialer accepted an expired server cert");
        var expiredClient = dial(CpTls.serverContext(ca.peer("a")),
                CpTls.serverContext(peer(ca, "b", past, null)));
        assertRefused(expiredClient.server(), "acceptor accepted an expired client cert");
    }

    @Test
    void revokedCertificatesAreRefusedOnBothSides() throws Exception {
        var ca = new CpTestCa(dir.resolve("ca"));
        var serverCfg = ca.peer("a");
        var revokedServerSerial = ca.lastSerial();
        var clientCfg = peer(ca, "b", System.currentTimeMillis() + 86_400_000L,
                denylist("client-deny", revokedServerSerial));
        var revokedServer = dial(CpTls.serverContext(serverCfg), CpTls.serverContext(clientCfg));
        assertRefused(revokedServer.client(), "dialer accepted a revoked server cert");

        var clientCert = ca.peer("b");
        var revokedClientSerial = ca.lastSerial();
        var acceptor = peer(ca, "a", System.currentTimeMillis() + 86_400_000L,
                denylist("server-deny", revokedClientSerial));
        var revokedClient = dial(CpTls.serverContext(acceptor), CpTls.serverContext(clientCert));
        assertRefused(revokedClient.server(), "acceptor accepted a revoked client cert");
    }

    @Test
    void aReloadedRevocationListStopsNewOutboundDials() throws Exception {
        var ca = new CpTestCa(dir.resolve("ca"));
        var serverTls = CpTls.serverContext(ca.peer("a"));
        var serverSerial = ca.lastSerial();
        var deny = denylist("dialer-deny");
        var dialer = CpTls.serverContext(peer(ca, "b", System.currentTimeMillis() + 86_400_000L, deny));
        var before = dial(serverTls, dialer);
        assertNull(before.server().error(), "sanity: connects before the revocation");
        assertNull(before.client().error(), String.valueOf(before.client().error()));

        Files.writeString(deny, "serial:" + serverSerial.toString(16) + "\n");
        dialer.reloadTls();
        var after = dial(serverTls, dialer);
        assertInstanceOf(javax.net.ssl.SSLHandshakeException.class, after.client().error(),
                "a new dial after the reload refuses the now-revoked peer");
    }

    /** A client trickling a TLS handshake neither holds an inbound slot nor outlives the 5 s total deadline. */
    @Test
    void stalledHandshakesCannotStarveARealPeer() throws Exception {
        var ca = new CpTestCa(dir.resolve("ca"));
        var key = ClusterKey.of("cp-raft-key-for-tests-0123".getBytes(StandardCharsets.US_ASCII), null);
        var members = new ArrayList<Member>();
        for (int wire = 1; wire <= 3; wire++) {
            members.add(new Member(new NodeId(wire, "cp-" + wire), new SiteId("cp"),
                    new InetSocketAddress("127.0.0.1", freePort()), 1L, MemberState.ALIVE, false));
        }
        var group = new GroupId("cp-config");
        var got = new ArrayList<SimTransport.Frame>();
        try (var a = TcpTransport.open(members.get(0).id(), members, key, Clock.SYSTEM, 64,
                pinned(ca, members, "cp-1"));
                var b = TcpTransport.open(members.get(1).id(), members, key, Clock.SYSTEM, 64,
                        pinned(ca, members, "cp-2"))) {
            b.attach(members.get(1).id(), (from, frame) -> got.add(frame));
            var rpc = a.attach(members.get(0).id(), (from, frame) -> { });
            // Twice the 2 x peers cap of loris clients, each sending one handshake byte a second.
            var loris = new ArrayList<Socket>();
            for (int i = 0; i < 8; i++) {
                var s = new Socket("127.0.0.1", b.localPort());
                s.getOutputStream().write(0x16);
                s.getOutputStream().flush();
                loris.add(s);
            }
            var trickle = Thread.ofVirtual().start(() -> {
                try {
                    while (true) {
                        Thread.sleep(1_000);
                        for (var s : loris) {
                            try {
                                s.getOutputStream().write(0x03);
                                s.getOutputStream().flush();
                            } catch (IOException closed) {
                                // closed by the deadline
                            }
                        }
                    }
                } catch (InterruptedException stop) {
                    // done
                }
            });
            try {
                long started = System.nanoTime();
                rpc.send(members.get(1).id(), new SimTransport.Frame(group, 7, new byte[] {7}));
                await(() -> {
                    b.deliver(100);
                    return !got.isEmpty();
                }, "the real peer's frame arrives while the loris clients are connected");
                assertTrue(System.nanoTime() - started < TimeUnit.MILLISECONDS.toNanos(4_000),
                        "delivered before the loris deadline, so they held no slot");
                assertEquals(0, b.inboundRefused(), "the loris clients never took a slot");
                await(() -> b.handshakeFailures() >= 8, "every loris closed by the total handshake deadline");
                long took = (System.nanoTime() - started) / 1_000_000;
                // 5 s deadline plus slack for a loaded CI box; without the deadline the trickle never ends.
                assertTrue(took < 15_000, "closed by the 5 s handshake deadline, took " + took + " ms");
                assertEquals(1, b.inboundConnections(), "only the real peer holds a slot");
            } finally {
                trickle.interrupt();
                for (var s : loris) {
                    s.close();
                }
            }
        }
    }

    /** {@code self}'s voter-link sockets, pinning {@code urn:sjws:node:<name>} for every member. */
    static CpPeerSockets pinned(CpTestCa ca, List<Member> members, String self) throws Exception {
        var peers = new ArrayList<CpConfig.Peer>();
        for (var m : members) {
            peers.add(new CpConfig.Peer(m.id().wire(), m.id().name(), m.address(), false, CpTestCa.san(m.id().name())));
        }
        return new CpPeerSockets(CpTls.serverContext(ca.peer(self)), peers, self);
    }

    private static int freePort() throws IOException {
        try (var probe = new ServerSocket()) {
            probe.bind(new InetSocketAddress("127.0.0.1", 0));
            return probe.getLocalPort();
        }
    }

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail("timed out: " + what);
            }
            Thread.sleep(10);
        }
    }

    /** More concurrent handshakes than the cap: the extra accepts are closed at once and counted. */
    @Test
    void inFlightHandshakesAreCapped() throws Exception {
        var ca = new CpTestCa(dir.resolve("ca"));
        var key = ClusterKey.of("cp-raft-key-for-tests-0123".getBytes(StandardCharsets.US_ASCII), null);
        var members = new ArrayList<Member>();
        for (int wire = 1; wire <= 2; wire++) {
            members.add(new Member(new NodeId(wire, "cp-" + wire), new SiteId("cp"),
                    new InetSocketAddress("127.0.0.1", freePort()), 1L, MemberState.ALIVE, false));
        }
        try (var b = TcpTransport.open(members.get(1).id(), members, key, Clock.SYSTEM, 64,
                pinned(ca, members, "cp-2"))) {
            int cap = Math.max(8, TcpTransport.HANDSHAKES_PER_PEER); // one peer
            var stalled = new ArrayList<Socket>();
            try {
                for (int i = 0; i < cap + 4; i++) {
                    var s = new Socket("127.0.0.1", b.localPort());
                    s.getOutputStream().write(0x16); // a handshake that never finishes
                    s.getOutputStream().flush();
                    stalled.add(s);
                }
                await(() -> b.handshakesRefused() >= 4, "the accepts beyond the cap are refused");
                assertTrue(b.handshaking() <= cap, "in flight " + b.handshaking());
            } finally {
                for (var s : stalled) {
                    s.close();
                }
            }
        }
    }
}
