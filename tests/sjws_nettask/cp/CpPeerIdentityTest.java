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
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
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
 * #175: each voter's {@code cp-peer} certificate is bound to its {@code cp.peers} id through a pinned URI or DNS
 * SAN, on the dialing side ({@link CpPeerSockets#connect}), on the accepting side ({@link CpPeerSockets#peerWire}
 * with the transport's ORIGIN check), and at startup ({@link CpLaunch#tcpTransport}).
 */
@Timeout(60)
class CpPeerIdentityTest {

    private static final ClusterKey KEY = ClusterKey.of("cp-raft-key-for-tests-0123".getBytes(StandardCharsets.US_ASCII),
            null);

    @TempDir
    Path dir;

    /** Three voters on loopback; {@code ports[i]} is cp-(i+1)'s address. */
    private static List<CpConfig.Peer> voters(int[] ports) {
        var out = new ArrayList<CpConfig.Peer>();
        for (int i = 0; i < ports.length; i++) {
            var id = "cp-" + (i + 1);
            out.add(new CpConfig.Peer(i + 1, id, new InetSocketAddress("127.0.0.1", ports[i]), false, CpTestCa.san(id)));
        }
        return out;
    }

    private static int[] ports(int n) throws IOException {
        var out = new int[n];
        for (int i = 0; i < n; i++) {
            out[i] = freePort();
        }
        return out;
    }

    /** What a dial saw: the dialer's exception, and the application bytes the acceptor received before EOF. */
    private record Dial(Exception client, int bytesAtServer) {
    }

    /**
     * cp-1 dials cp-2's address, where a server presenting {@code serverCert} listens (any {@code peerCa}
     * certificate passes TLS). Returns the dialer's outcome and how many application bytes reached the server.
     */
    private Dial dialCp2(CpPeerSockets dialer, CpListenerConfig serverCert, List<CpConfig.Peer> peers)
            throws Exception {
        var server = new CpPeerSockets(CpTls.serverContext(serverCert), peers, "cp-2");
        try (var listener = server.listen((InetSocketAddress) peers.get(1).address())) {
            var serverSide = CompletableFuture.supplyAsync(() -> {
                try (var accepted = listener.accept()) {
                    server.handshake(accepted, 5_000);
                    accepted.setSoTimeout(5_000);
                    int n = 0;
                    try {
                        while (accepted.getInputStream().read() >= 0) {
                            n++;
                        }
                    } catch (IOException closedByPeer) {
                        // the dialer closed the connection
                    }
                    return n;
                } catch (Exception e) {
                    return 0;
                }
            });
            Exception client = null;
            try (var socket = dialer.connect((InetSocketAddress) peers.get(1).address())) {
                socket.getOutputStream().write(new byte[] {1, 2, 3});
                socket.getOutputStream().flush();
            } catch (Exception e) {
                client = e;
            }
            return new Dial(client, serverSide.get(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void theVoterPinnedAtTheDialledAddressConnects() throws Exception {
        var ca = new CpTestCa(dir.resolve("ca"));
        var peers = voters(ports(3));
        var dialer = new CpPeerSockets(CpTls.serverContext(ca.peer("cp-1")), peers, "cp-1");
        var out = dialCp2(dialer, ca.peer("cp-2"), peers);
        assertNull(out.client(), String.valueOf(out.client()));
        assertEquals(3, out.bytesAtServer());
        assertEquals(0, dialer.identityRefused());
    }

    /** Test 1: the server at cp-2's address presents cp-3's valid certificate. */
    @Test
    void dialingAnAddressThatPresentsAnotherVotersCertificateIsRefusedBeforeAnyByte() throws Exception {
        var ca = new CpTestCa(dir.resolve("ca"));
        var peers = voters(ports(3));
        var dialer = new CpPeerSockets(CpTls.serverContext(ca.peer("cp-1")), peers, "cp-1");
        var out = dialCp2(dialer, ca.peer("cp-3"), peers);
        assertInstanceOf(IOException.class, out.client(), "connect must refuse cp-3's certificate at cp-2's address");
        assertTrue(out.client().getMessage().contains("cp-2"), out.client().getMessage());
        assertEquals(1, dialer.identityRefused(), "the refusal is counted");
        assertEquals(0, out.bytesAtServer(), "no byte was written to the wrong voter");
    }

    /** Test 3: a certificate naming two pinned voters identifies neither, on either side. */
    @Test
    void aCertificateNamingTwoPinnedVotersIsRefused() throws Exception {
        var ca = new CpTestCa(dir.resolve("ca"));
        var peers = voters(ports(3));
        var dialer = new CpPeerSockets(CpTls.serverContext(ca.peer("cp-1")), peers, "cp-1");
        var both = ca.listener("cp-peer", "cp-2", System.currentTimeMillis() + 86_400_000L, null, CpTestCa.san("cp-2"),
                CpTestCa.san("cp-3"));
        var out = dialCp2(dialer, both, peers);
        assertInstanceOf(IOException.class, out.client(), "a server certificate naming cp-2 and cp-3 is refused");
        assertEquals(1, dialer.identityRefused());
        assertEquals(0, out.bytesAtServer());

        // Accepting side: a client certificate naming cp-2 and cp-3 gets no wire.
        var acceptor = new CpPeerSockets(CpTls.serverContext(ca.peer("cp-1")), peers, "cp-1");
        var refused = acceptedPeerWire(acceptor, peers.get(0), CpTls.serverContext(both));
        assertInstanceOf(IllegalStateException.class, refused);
        assertEquals(1, acceptor.identityRefused());
    }

    /** Test 4: only URI and DNS SANs count; a CN-only certificate is refused on both sides. */
    @Test
    void aCnOnlyCertificateIsRefused() throws Exception {
        var ca = new CpTestCa(dir.resolve("ca"));
        var peers = voters(ports(3));
        var dialer = new CpPeerSockets(CpTls.serverContext(ca.peer("cp-1")), peers, "cp-1");
        // CN=urn:sjws:node:cp-2 and no SAN: the CN is never used.
        var cnOnly = ca.listener("cp-peer", CpTestCa.san("cp-2"));
        var out = dialCp2(dialer, cnOnly, peers);
        assertInstanceOf(IOException.class, out.client(), "a CN-only server certificate is refused");
        assertEquals(1, dialer.identityRefused());
        assertEquals(0, out.bytesAtServer());

        var acceptor = new CpPeerSockets(CpTls.serverContext(ca.peer("cp-1")), peers, "cp-1");
        var refused = acceptedPeerWire(acceptor, peers.get(0), CpTls.serverContext(cnOnly));
        assertInstanceOf(IllegalStateException.class, refused);
        assertEquals(1, acceptor.identityRefused());
    }

    @Test
    void aDnsSanPinsLikeAUriSanAndTheAcceptorMapsItToTheWire() throws Exception {
        var ca = new CpTestCa(dir.resolve("ca"));
        var p = ports(3);
        var peers = new ArrayList<>(voters(p));
        peers.set(1, new CpConfig.Peer(2, "cp-2", peers.get(1).address(), false, "cp-2.voters.example"));
        var cert = ca.listener("cp-peer", "x", System.currentTimeMillis() + 86_400_000L, null,
                "dns:cp-2.voters.example", "urn:example:unpinned");
        var acceptor = new CpPeerSockets(CpTls.serverContext(ca.peer("cp-1")), peers, "cp-1");
        assertEquals(2, acceptedPeerWire(acceptor, peers.get(0), CpTls.serverContext(cert)));
        // A client presenting this voter's own certificate is not another voter.
        assertInstanceOf(IllegalStateException.class,
                acceptedPeerWire(acceptor, peers.get(0), CpTls.serverContext(ca.peer("cp-1"))));
        assertEquals(1, acceptor.identityRefused());
        // Dialing an address no voter is pinned at, or this voter's own address, is refused before connecting.
        assertThrows(IOException.class, () -> acceptor.connect(new InetSocketAddress("127.0.0.1", 1)));
        assertThrows(IOException.class, () -> acceptor.connect((InetSocketAddress) peers.get(0).address()));
    }

    /**
     * Accepts one connection on {@code self}'s address from a raw mTLS client presenting {@code clientTls}'s
     * certificate, and returns {@link CpPeerSockets#peerWire} or what it threw.
     */
    private static Object acceptedPeerWire(CpPeerSockets acceptor, CpConfig.Peer self, CpServerTls clientTls)
            throws Exception {
        try (var listener = acceptor.listen(new InetSocketAddress("127.0.0.1", 0))) {
            var serverSide = CompletableFuture.supplyAsync(() -> {
                try (var accepted = listener.accept()) {
                    acceptor.handshake(accepted, 5_000);
                    return (Object) acceptor.peerWire(accepted);
                } catch (Exception e) {
                    return e;
                }
            });
            try (var raw = (SSLSocket) clientTls.context().getSocketFactory().createSocket()) {
                raw.setSSLParameters(clientTls.parameters());
                raw.connect(new InetSocketAddress("127.0.0.1", listener.getLocalPort()), 5_000);
                raw.startHandshake();
                raw.getOutputStream().write(0); // flushes the client's last handshake flight
                raw.getOutputStream().flush();
                return serverSide.get(10, TimeUnit.SECONDS);
            }
        }
    }

    /**
     * Test 2: cp-3's certificate on a connection whose frames are correctly tagged under the shared Raft key but
     * claim sender wire 2 (cp-2): every such frame is dropped as ORIGIN and the handler never sees it.
     */
    @Test
    void framesWhoseSenderIsNotTheCertificatesVoterAreDroppedAsOrigin() throws Exception {
        var ca = new CpTestCa(dir.resolve("ca"));
        var p = ports(3);
        var peers = voters(p);
        var members = new ArrayList<Member>();
        for (var peer : peers) {
            members.add(new Member(CpLaunch.nodeId(peer), new SiteId("cp"), peer.address(), 1L, MemberState.ALIVE,
                    false));
        }
        var seen = new CopyOnWriteArrayList<NodeId>();
        var group = new GroupId("cp-config");
        try (var one = TcpTransport.open(members.get(0).id(), members, KEY, Clock.SYSTEM, 64,
                new CpPeerSockets(CpTls.serverContext(ca.peer("cp-1")), peers, "cp-1"))) {
            one.attach(members.get(0).id(), (from, frame) -> seen.add(from));
            // Sender wire 2 (cp-2), certificate cp-3: the dialer checks only cp-1's certificate, as any voter would.
            try (var two = TcpTransport.open(members.get(1).id(), members, KEY, Clock.SYSTEM, 64,
                    new CpPeerSockets(CpTls.serverContext(ca.peer("cp-3")), peers, "cp-2"))) {
                var rpc = two.attach(members.get(1).id(), (from, frame) -> { });
                await(() -> {
                    rpc.send(members.get(0).id(), new SimTransport.Frame(group, 7, new byte[] {7}));
                    one.deliver(100);
                    return one.rejected(TcpTransport.Reason.ORIGIN) >= 2;
                }, "frames claiming cp-2 over cp-3's certificate are dropped as ORIGIN");
                assertTrue(one.originMismatches() >= 2, "each mismatch closes the connection and is counted");
                assertEquals(0, one.rejected(TcpTransport.Reason.TAG), "the frames were correctly tagged");
                one.deliver(100);
                assertTrue(seen.isEmpty(), "no frame reached the handler: " + seen);
            }
            // The real cp-2 certificate on the same wire is delivered: the drop is about the identity only.
            try (var real = TcpTransport.open(members.get(1).id(), members, KEY, Clock.SYSTEM, 64,
                    new CpPeerSockets(CpTls.serverContext(ca.peer("cp-2")), peers, "cp-2"))) {
                var rpc = real.attach(members.get(1).id(), (from, frame) -> { });
                await(() -> {
                    rpc.send(members.get(0).id(), new SimTransport.Frame(group, 7, new byte[] {7}));
                    one.deliver(100);
                    return !seen.isEmpty();
                }, "cp-2's own certificate delivers");
                assertEquals(members.get(1).id(), seen.get(0));
            }
        }
    }

    /** Test 5a: this voter's own certificate must carry its own pinned SAN, or the start is refused. */
    @Test
    void startupIsRefusedWhenTheOwnCertificateLacksItsPinnedSan() throws Exception {
        var ca = new CpTestCa(dir.resolve("ca"));
        var config = CpConfig.parse(k -> null, Map.of(), yaml("cp-1", ports(3), true), null);
        for (var own : List.of(ca.listener("cp-peer", "cp-1"), ca.peer("cp-2"),
                ca.listener("cp-peer", "cp-1", System.currentTimeMillis() + 86_400_000L, null, CpTestCa.san("cp-1"),
                        CpTestCa.san("cp-2")))) {
            var e = assertThrows(IOException.class,
                    () -> CpLaunch.tcpTransport(config, CpTls.serverContext(own), KEY, Clock.SYSTEM));
            assertTrue(e.getMessage().contains("pinned san"), e.getMessage());
        }
        try (var t = CpLaunch.tcpTransport(config, CpTls.serverContext(ca.peer("cp-1")), KEY, Clock.SYSTEM)) {
            assertTrue(t.localPort() > 0, "its own pinned SAN starts");
        }
    }

    /** Test 5b: with cp.tls set (or voter-link TLS present), every cp.peers entry must pin a SAN. */
    @Test
    void startupIsRefusedWhenAnEntryHasNoPinnedSan() throws Exception {
        var ca = new CpTestCa(dir.resolve("ca"));
        var y = yaml("cp-1", ports(3), false);
        y.put("tls", Map.of("peerCert", "pc", "peerKey", "pk", "peerCa", "pca"));
        var parse = assertThrows(IllegalArgumentException.class, () -> CpConfig.parse(k -> null, Map.of(), y, null));
        assertTrue(parse.getMessage().contains("cp.peers[0].san is required with cp.tls"), parse.getMessage());
        // Without cp.tls (the loopback development links) no SAN is needed.
        var noTls = yaml("cp-1", ports(3), false);
        var config = CpConfig.parse(k -> null, Map.of(), noTls, null);
        try (var t = CpLaunch.tcpTransport(config, null, KEY, Clock.SYSTEM)) {
            assertTrue(t.localPort() > 0);
        }
        // Voter-link TLS handed over without pins is refused at the transport too.
        var e = assertThrows(IOException.class,
                () -> CpLaunch.tcpTransport(config, CpTls.serverContext(ca.peer("cp-1")), KEY, Clock.SYSTEM));
        assertTrue(e.getMessage().contains("san is required"), e.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> new CpPeerSockets(CpTls.serverContext(ca.peer("cp-1")), config.peers(), "cp-1"));
        var pinned = CpConfig.parse(k -> null, Map.of(), yaml("cp-1", ports(3), true), null).peers();
        assertThrows(IllegalArgumentException.class,
                () -> new CpPeerSockets(CpTls.serverContext(ca.peer("cp-1")), pinned, "cp-9"));
    }

    @Test
    void sanConfigIsValidatedAtLoad() throws Exception {
        var y = yaml("cp-1", ports(3), true);
        var config = CpConfig.parse(k -> null, Map.of(), y, null);
        assertEquals(CpTestCa.san("cp-2"), config.peers().get(1).san());
        for (var bad : List.<Object>of("has space", "", 7, "x".repeat(256))) {
            var peers = new ArrayList<Object>(peerList(ports(3), true));
            var entry = new HashMap<String, Object>(castMap(peers.get(1)));
            entry.put("san", bad);
            peers.set(1, entry);
            var broken = yaml("cp-1", ports(3), true);
            broken.put("peers", peers);
            assertThrows(IllegalArgumentException.class, () -> CpConfig.parse(k -> null, Map.of(), broken, null),
                    String.valueOf(bad));
        }
        var dup = yaml("cp-1", ports(3), true);
        var peers = new ArrayList<Object>(peerList(ports(3), true));
        var entry = new HashMap<String, Object>(castMap(peers.get(2)));
        entry.put("san", CpTestCa.san("cp-2"));
        peers.set(2, entry);
        dup.put("peers", peers);
        var e = assertThrows(IllegalArgumentException.class, () -> CpConfig.parse(k -> null, Map.of(), dup, null));
        assertTrue(e.getMessage().contains("duplicate san"), e.getMessage());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object o) {
        return (Map<String, Object>) o;
    }

    private static List<Object> peerList(int[] ports, boolean sans) {
        var peers = new ArrayList<Object>();
        for (int i = 1; i <= ports.length; i++) {
            var m = new HashMap<String, Object>();
            m.put("id", "cp-" + i);
            m.put("address", "127.0.0.1:" + ports[i - 1]);
            if (sans) {
                m.put("san", CpTestCa.san("cp-" + i));
            }
            peers.add(m);
        }
        return peers;
    }

    private Map<String, Object> yaml(String self, int[] ports, boolean sans) {
        var y = new HashMap<String, Object>();
        y.put("nodeId", self);
        y.put("stateDir", dir.resolve(self).toString());
        y.put("keyDir", dir.resolve("keys").toString());
        y.put("rootPub", dir.resolve("root.pub").toString());
        y.put("watch", Map.of("listen", "127.0.0.1:0"));
        y.put("admin", Map.of("listen", "127.0.0.1:0"));
        y.put("peers", peerList(ports, sans));
        return y;
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
            Thread.sleep(20);
        }
    }
}
