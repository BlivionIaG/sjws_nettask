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
import java.util.function.BooleanSupplier;
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

    /** What each side saw: the dialer's connect, and the acceptor's {@link CpPeerSockets#handshake}. */
    private record Outcome(Exception client, Exception server) {
    }

    private Outcome dial(CpServerTls serverTls, CpServerTls clientTls) throws Exception {
        var server = new CpPeerSockets(serverTls);
        try (var listener = server.listen(new InetSocketAddress("127.0.0.1", 0))) {
            var serverSide = CompletableFuture.supplyAsync(() -> {
                try (var accepted = listener.accept()) {
                    server.handshake(accepted, 5_000);
                    return null;
                } catch (Exception e) {
                    return e;
                }
            });
            Exception client = null;
            try (var socket = new CpPeerSockets(clientTls).connect(
                    new InetSocketAddress("127.0.0.1", listener.getLocalPort()))) {
                socket.setSoTimeout(5_000);
                socket.getOutputStream().write(1);
                socket.getOutputStream().flush();
                socket.getInputStream().read(); // TLS 1.3: a client-cert refusal surfaces here
            } catch (Exception e) {
                client = e;
            }
            return new Outcome(client, serverSide.get(10, TimeUnit.SECONDS));
        }
    }

    /**
     * A refusal is a TLS handshake failure, not a timeout or deadline close that would also make the dial fail.
     * The exception may arrive wrapped, so the cause chain is searched.
     */
    private static void assertRefused(Exception seen, String what) {
        assertNotNull(seen, what);
        for (Throwable t = seen; t != null; t = t.getCause()) {
            if (t instanceof javax.net.ssl.SSLHandshakeException) {
                return;
            }
            assertFalse(t instanceof java.net.SocketTimeoutException, what + ": timed out instead: " + seen);
        }
        fail(what + ": expected an SSLHandshakeException, got " + seen);
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
        var out = dial(CpTls.serverContext(ca.listener("cp-peer", "a")), CpTls.serverContext(ca.listener("cp-peer", "b")));
        assertNull(out.server(), String.valueOf(out.server()));
        assertNull(out.client(), String.valueOf(out.client()));
    }

    @Test
    void wrongCaIsRefusedOnBothSides() throws Exception {
        var ca = new CpTestCa(dir.resolve("ca"));
        var other = new CpTestCa(dir.resolve("other"));
        // The acceptor's cert is from another CA: the dialer refuses it.
        var serverForeign = dial(CpTls.serverContext(other.listener("cp-peer", "a")),
                CpTls.serverContext(ca.listener("cp-peer", "b")));
        assertRefused(serverForeign.client(), "dialer accepted a server cert from a foreign CA");
        assertRefused(serverForeign.server(), "acceptor saw the dialer's refusal");
        // The dialer's cert is from another CA: the acceptor refuses it.
        var clientForeign = dial(CpTls.serverContext(ca.listener("cp-peer", "c")),
                CpTls.serverContext(other.listener("cp-peer", "d")));
        assertRefused(clientForeign.server(), "acceptor accepted a client cert from a foreign CA");
    }

    @Test
    void expiredCertificatesAreRefusedOnBothSides() throws Exception {
        var ca = new CpTestCa(dir.resolve("ca"));
        long past = System.currentTimeMillis() - 60_000L;
        var expiredServer = dial(CpTls.serverContext(ca.listener("cp-peer", "a", past, null)),
                CpTls.serverContext(ca.listener("cp-peer", "b")));
        assertRefused(expiredServer.client(), "dialer accepted an expired server cert");
        var expiredClient = dial(CpTls.serverContext(ca.listener("cp-peer", "c")),
                CpTls.serverContext(ca.listener("cp-peer", "d", past, null)));
        assertRefused(expiredClient.server(), "acceptor accepted an expired client cert");
    }

    @Test
    void revokedCertificatesAreRefusedOnBothSides() throws Exception {
        var ca = new CpTestCa(dir.resolve("ca"));
        var serverCfg = ca.listener("cp-peer", "a");
        var revokedServerSerial = ca.lastSerial();
        var clientCfg = ca.listener("cp-peer", "b", System.currentTimeMillis() + 86_400_000L,
                denylist("client-deny", revokedServerSerial));
        var revokedServer = dial(CpTls.serverContext(serverCfg), CpTls.serverContext(clientCfg));
        assertRefused(revokedServer.client(), "dialer accepted a revoked server cert");

        var clientCert = ca.listener("cp-peer", "c");
        var revokedClientSerial = ca.lastSerial();
        var acceptor = ca.listener("cp-peer", "d", System.currentTimeMillis() + 86_400_000L,
                denylist("server-deny", revokedClientSerial));
        var revokedClient = dial(CpTls.serverContext(acceptor), CpTls.serverContext(clientCert));
        assertRefused(revokedClient.server(), "acceptor accepted a revoked client cert");
    }

    @Test
    void aReloadedRevocationListStopsNewOutboundDials() throws Exception {
        var ca = new CpTestCa(dir.resolve("ca"));
        var serverTls = CpTls.serverContext(ca.listener("cp-peer", "a"));
        var serverSerial = ca.lastSerial();
        var deny = denylist("dialer-deny");
        var dialer = CpTls.serverContext(ca.listener("cp-peer", "b", System.currentTimeMillis() + 86_400_000L, deny));
        var before = dial(serverTls, dialer);
        assertNull(before.server(), "sanity: connects before the revocation");
        assertNull(before.client(), String.valueOf(before.client()));

        Files.writeString(deny, "serial:" + serverSerial.toString(16) + "\n");
        dialer.reloadTls();
        var after = dial(serverTls, dialer);
        assertInstanceOf(javax.net.ssl.SSLHandshakeException.class, after.client(),
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
                new CpPeerSockets(CpTls.serverContext(ca.listener("cp-peer", "cp-1"))));
                var b = TcpTransport.open(members.get(1).id(), members, key, Clock.SYSTEM, 64,
                        new CpPeerSockets(CpTls.serverContext(ca.listener("cp-peer", "cp-2"))))) {
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
                new CpPeerSockets(CpTls.serverContext(ca.listener("cp-peer", "cp-2"))))) {
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
