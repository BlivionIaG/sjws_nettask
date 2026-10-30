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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.cluster.Clock;
import sjws_nettask.clusterharness.EphemeralPorts;
import sjws_nettask.cluster.ClusterKey;
import sjws_nettask.cluster.ClusterTransport;
import sjws_nettask.service.CpReloadable;

/**
 * CONTROL voters over real loopback TCP: {@link CpLaunch#tcpTransport} with every socket from
 * {@link CpPeerSockets} over {@link CpServerTls} signed by a test CA (mTLS, TLS 1.3).
 */
@Timeout(90)
class CpTcpVotersTest {

    private static final ClusterKey KEY = ClusterKey.of("cp-voter-frame-key-0123".getBytes(StandardCharsets.US_ASCII),
            null);

    @TempDir
    Path dir;

    private final PrintStream err = new PrintStream(new ByteArrayOutputStream());
    private final List<CpReloadable> hosts = new ArrayList<>();

    @AfterEach
    void closeAll() {
        for (var h : hosts) {
            h.close();
        }
    }

    /** Production-shaped deps: real TcpTransport over mTLS for cp-peer, plain loopback watch/admin. */
    private static final class TcpDeps extends CpTestDeps {
        final CpServerTls peerTls;
        final List<ClusterTransport> transports = new ArrayList<>();
        CpListenerConfig peerConfig;
        int[] ports;

        TcpDeps(CpServerTls peerTls) {
            this.peerTls = peerTls;
        }

        @Override
        public ClusterTransport transport(CpConfig config, CpServerTls tls) {
            try {
                var t = CpLaunch.tcpTransport(config, tls, KEY, Clock.SYSTEM);
                transports.add(t);
                return t;
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }

        @Override
        public CpServerTls serverTls(String listener) {
            return "cp-peer".equals(listener) ? peerTls : null;
        }
    }

    private Map<String, Object> yaml(String self, int[] ports, int full) {
        var y = new HashMap<String, Object>();
        y.put("nodeId", self);
        y.put("stateDir", dir.resolve(self).toString());
        y.put("keyDir", dir.resolve("keys").toString());
        y.put("rootPub", dir.resolve("root.pub").toString());
        y.put("watch", Map.of("listen", "127.0.0.1:0"));
        y.put("admin", Map.of("listen", "127.0.0.1:0"));
        var peers = new ArrayList<Object>();
        for (int i = 1; i <= ports.length; i++) {
            peers.add(Map.of("id", "cp-" + i, "address", "127.0.0.1:" + ports[i - 1], "witness", i > full, "san",
                    CpTestCa.san("cp-" + i)));
        }
        y.put("peers", peers);
        return y;
    }

    private CpTestCa ca;

    private List<TcpDeps> start(int voters, int full) throws Exception {
        return EphemeralPorts.withBindRetry("CpTcpVotersTest.start", () -> startOnce(voters, full));
    }

    private List<TcpDeps> startOnce(int voters, int full) throws Exception {
        // A previous bind-retry attempt may have left partially-started hosts.
        for (var h : hosts) {
            h.close();
        }
        hosts.clear();
        var ca = new CpTestCa(dir.resolve("ca-" + System.nanoTime()));
        this.ca = ca;
        var ports = EphemeralPorts.probeDistinct(voters);
        var all = new ArrayList<TcpDeps>();
        for (int i = 1; i <= voters; i++) {
            var peerConfig = ca.peer("cp-" + i);
            var deps = new TcpDeps(CpTls.serverContext(peerConfig));
            deps.peerConfig = peerConfig;
            deps.ports = ports;
            all.add(deps);
            hosts.add(CpLaunch.start(CpConfig.parse(k -> null, Map.of(), yaml("cp-" + i, ports, full), null), deps,
                    err));
        }
        return all;
    }

    @Test
    void threeVotersOverMtlsElectCommitAndSurviveALeaderKill() throws Exception {
        var deps = start(3, 3);
        await(() -> leader() != null && leader().ready(), "leader ready over TCP+TLS");
        var first = leader();
        assertEquals(200, first.store().propose("schema: 1\nserver:\n  port: 0\n").status());
        for (var d : deps) {
            assertEquals(1, d.transports.size());
            assertEquals(0, ((sjws_nettask.cluster.TcpTransport) d.transports.get(0)).strangersRefused(),
                    "mTLS factory: no source-address filter");
        }

        first.close();
        assertTrue(first.events().contains("close-transport"), first.events().toString());
        await(() -> leader() != null && leader() != first && leader().ready(), "new leader after the kill");
        var second = leader();
        // The proposal staged before the kill replicated over TCP: the new leader still holds it.
        var again = second.store().propose("schema: 1\nserver:\n  port: 1\n");
        assertEquals(409, again.status(), again.body());
        assertTrue(again.body().contains("already staged"), again.body());

        // SIGHUP also reloads the voter-link TLS in place.
        long generation = deps.get(hosts.indexOf(second)).peerTls.generation();
        assertNull(second.reload());
        assertEquals(generation + 1, deps.get(hosts.indexOf(second)).peerTls.generation());
    }

    @Test
    void twoVotersAndAWitnessOverMtlsElectAndFailOver() throws Exception {
        var deps = start(3, 2);
        var witnessHost = assertInstanceOf(CpWitnessHost.class, hosts.get(2));
        await(() -> leader() != null && leader().ready(), "data leader ready");
        var witness = witnessHost.witness();
        await(() -> {
            try {
                return witness.ackedTerm() > 0;
            } catch (IOException e) {
                return false;
            }
        }, "witness acks over TCP+TLS");
        assertEquals(200, leader().store().propose("schema: 1\nserver:\n  port: 0\n").status());
        long generation = deps.get(2).peerTls.generation();
        assertNull(witnessHost.reload(), "witness reloads its voter-link TLS");
        assertEquals(generation + 1, deps.get(2).peerTls.generation());

        var first = leader();
        first.close();
        await(() -> leader() != null && leader() != first, "the other data voter wins with the witness's vote");
    }

    /**
     * SIGHUP on a data voter (CpHost) and on the witness (CpWitnessHost): a new cp-peer certificate without the
     * voter's own pinned SAN is refused, the reload reports the failure, the current material keeps serving, and
     * the peers still accept this voter's new connections.
     */
    @Test
    void aSighupToACertificateWithoutTheOwnPinnedSanIsRefusedOnBothHosts() throws Exception {
        var deps = start(3, 2);
        assertInstanceOf(CpHost.class, hosts.get(0));
        assertInstanceOf(CpWitnessHost.class, hosts.get(2));
        await(() -> leader() != null && leader().ready(), "data leader ready");
        for (int i : new int[] {0, 2}) {
            var d = deps.get(i);
            var self = "cp-" + (i + 1);
            var other = i == 0 ? 1 : 0;
            long generation = d.peerTls.generation();
            var before = d.peerTls.certificate();
            for (var bad : List.of(ca.listener("cp-peer", self), ca.peer("cp-" + (other + 1)))) {
                java.nio.file.Files.write(d.peerConfig.certPem(), java.nio.file.Files.readAllBytes(bad.certPem()));
                java.nio.file.Files.write(d.peerConfig.keyPem(), java.nio.file.Files.readAllBytes(bad.keyPem()));
                var failed = hosts.get(i).reload();
                assertNotNull(failed, self + " accepted a certificate without its pinned SAN");
                assertTrue(failed.contains("reload failed") && failed.contains("pinned san"), failed);
                assertEquals(generation, d.peerTls.generation(), "the current material stays");
                assertEquals(before, d.peerTls.certificate());
            }
            // The material still in use is accepted by a peer: a fresh dial with it is identified on both sides.
            var peerTransport = (sjws_nettask.cluster.TcpTransport) deps.get(other).transports.get(0);
            long failures = peerTransport.handshakeFailures();
            var peers = CpConfig.parse(k -> null, Map.of(), yaml(self, d.ports, 2), null).peers();
            try (var s = new CpPeerSockets(d.peerTls, peers, self).connect(
                    new InetSocketAddress("127.0.0.1", d.ports[other]))) {
                s.getOutputStream().write(0);
                s.getOutputStream().flush();
                Thread.sleep(200);
            }
            assertEquals(failures, peerTransport.handshakeFailures(), "the peer accepted " + self);
        }
        assertEquals(200, leader().store().propose("schema: 1\nserver:\n  port: 0\n").status());
    }

    @Test
    void aPeerPortRefusesClientsWithoutACertificate() throws Exception {
        var ca = new CpTestCa(dir.resolve("ca"));
        EphemeralPorts.withBindRetry("CpTcpVotersTest.aPeerPortRefuses", () -> {
            var ports = EphemeralPorts.probeDistinct(3);
            int port = ports[0];
            var config = CpConfig.parse(k -> null, Map.of(), yaml("cp-1", ports, 3), null);
            try (var t = CpLaunch.tcpTransport(config, CpTls.serverContext(ca.peer("cp-1")), KEY,
                    Clock.SYSTEM)) {
                assertThrows(IOException.class, () -> handshakeWithoutClientCert(port, ca.caPem()));
                // Plain TCP: the TLS server never accepts it as frames.
                try (var plain = new Socket("127.0.0.1", port)) {
                    plain.setSoTimeout(5_000);
                    plain.getOutputStream().write(new byte[] {0, 0, 0, 64, 1, 2, 3});
                    plain.getOutputStream().flush();
                    int got;
                    try {
                        got = plain.getInputStream().read();
                        while (got >= 0) {
                            got = plain.getInputStream().read();
                        }
                    } catch (IOException reset) {
                        got = -1;
                    }
                    assertEquals(-1, got);
                }
                assertEquals(0, t.rejected(sjws_nettask.cluster.TcpTransport.Reason.TAG));
            }
            return null;
        });
    }

    @Test
    void withoutTlsVoterLinksMustBeLoopback() throws Exception {
        // Non-loopback peer: ports need not be free; only the address family is checked.
        var y = yaml("cp-1", new int[] {1, 2, 3}, 3);
        y.put("peers", List.of(Map.of("id", "cp-1", "address", "127.0.0.1:1"),
                Map.of("id", "cp-2", "address", "192.0.2.10:7000"), Map.of("id", "cp-3", "address", "127.0.0.1:2")));
        var config = CpConfig.parse(k -> null, Map.of(), y, null);
        var e = assertThrows(IOException.class, () -> CpLaunch.tcpTransport(config, null, KEY, Clock.SYSTEM));
        assertTrue(e.getMessage().contains("must be loopback"), e.getMessage());
        // Three distinct loopback peers, then bind: probeDistinct avoids duplicate-address; retry covers steal.
        EphemeralPorts.withBindRetry("CpTcpVotersTest.withoutTlsLoopback", () -> {
            var loop = CpConfig.parse(k -> null, Map.of(), yaml("cp-1", EphemeralPorts.probeDistinct(3), 3), null);
            try (var t = CpLaunch.tcpTransport(loop, null, KEY, Clock.SYSTEM)) {
                assertTrue(t.localPort() > 0);
            }
            return null;
        });
    }

    @Test
    void peerListenerNeedsItsOwnCertKeyAndCaWithNoFallback() throws Exception {
        var full = Map.of("cert", "c", "key", "k", "ca", "ca.pem", "peerCert", "pc", "peerKey", "pk", "peerCa",
                "peer.pem");
        var peer = CpLaunch.listenerConfig(full, dir, "cp-peer", false);
        assertEquals(dir.resolve("pc"), peer.certPem());
        assertEquals(dir.resolve("pk"), peer.keyPem());
        assertEquals(dir.resolve("peer.pem"), peer.caPem());
        for (var missing : List.of("peerCert", "peerKey", "peerCa")) {
            var partial = new HashMap<String, Object>(full);
            partial.remove(missing);
            var e = assertThrows(CpKeyException.class, () -> CpLaunch.listenerConfig(partial, dir, "cp-peer", false));
            assertTrue(e.getMessage().contains("cp.tls." + missing), e.getMessage());
        }
        // The watch listener keeps the shared cert and ca.
        var watch = CpLaunch.listenerConfig(full, dir, "cp-watch", false);
        assertEquals(dir.resolve("c"), watch.certPem());
        assertEquals(dir.resolve("ca.pem"), watch.caPem());
    }

    @Test
    void peerCaMustNotBeTheDataNodeCaAndAdminCaEqualToCaOnlyWarns() throws Exception {
        var ca = new CpTestCa(dir.resolve("ca-gen"));
        var other = new CpTestCa(dir.resolve("other-gen"));
        java.nio.file.Files.copy(ca.caPem(), dir.resolve("ca.pem"));
        java.nio.file.Files.copy(ca.caPem(), dir.resolve("copy.pem"));
        java.nio.file.Files.copy(other.caPem(), dir.resolve("peer.pem"));
        // Same CA, different bytes: DER instead of PEM, and a bundle that also carries the CA.
        try (var in = java.nio.file.Files.newInputStream(ca.caPem())) {
            var cert = java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(in);
            java.nio.file.Files.write(dir.resolve("ca.der"), cert.getEncoded());
        }
        java.nio.file.Files.writeString(dir.resolve("bundle.pem"), java.nio.file.Files.readString(other.caPem())
                + java.nio.file.Files.readString(ca.caPem()));
        java.nio.file.Files.writeString(dir.resolve("junk.pem"), "not a certificate");
        assertFalse(java.util.Arrays.equals(java.nio.file.Files.readAllBytes(dir.resolve("ca.der")),
                java.nio.file.Files.readAllBytes(dir.resolve("ca.pem"))), "sanity: the bytes differ");
        for (var sameAsCa : List.of("ca.pem", "./ca.pem", "copy.pem", "ca.der", "bundle.pem")) {
            var y = Map.of("cert", "c", "key", "k", "ca", "ca.pem", "peerCert", "pc", "peerKey", "pk", "peerCa",
                    sameAsCa);
            var e = assertThrows(CpKeyException.class, () -> CpLaunch.listenerConfig(y, dir, "cp-peer", false));
            assertTrue(e.getMessage().contains("must not be the data-node CA"), e.getMessage());
        }
        assertFalse(CpLaunch.sameCa(dir.resolve("peer.pem"), dir.resolve("ca.pem")));
        assertFalse(CpLaunch.sameCa(dir.resolve("absent.pem"), dir.resolve("ca.pem")));
        assertFalse(CpLaunch.sameCa(dir.resolve("junk.pem"), dir.resolve("ca.pem")), "unparsable: left to the loader");
        assertFalse(CpLaunch.sameCa(null, dir.resolve("ca.pem")));
        var admin = CpLaunch.listenerConfig(Map.of("cert", "c", "key", "k", "ca", "ca.pem", "adminCa", "ca.pem"), dir,
                "cp-admin", false);
        assertEquals(dir.resolve("ca.pem"), admin.caPem(), "allowed, with a WARN");
    }

    private CpHost leader() {
        for (var h : hosts) {
            if (h instanceof CpHost host && !host.events().contains("close-store") && host.store() != null
                    && host.store().leadership().isLeader()) {
                return host;
            }
        }
        return null;
    }

    private static void handshakeWithoutClientCert(int port, Path caPem) throws Exception {
        var store = java.security.KeyStore.getInstance("PKCS12");
        store.load(null, null);
        try (var in = java.nio.file.Files.newInputStream(caPem)) {
            store.setCertificateEntry("ca",
                    java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(in));
        }
        var tmf = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(store);
        var ctx = javax.net.ssl.SSLContext.getInstance("TLSv1.3");
        ctx.init(null, tmf.getTrustManagers(), null);
        try (var socket = (javax.net.ssl.SSLSocket) ctx.getSocketFactory().createSocket("127.0.0.1", port)) {
            socket.setSoTimeout(5_000);
            socket.startHandshake();
            socket.getOutputStream().write(new byte[] {0, 0, 0, 64});
            socket.getOutputStream().flush();
            if (socket.getInputStream().read() < 0) {
                throw new java.io.EOFException("closed without a client certificate");
            }
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
}
