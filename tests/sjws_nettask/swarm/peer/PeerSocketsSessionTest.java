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

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLSocket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.clusterharness.EphemeralPorts;
import sjws_nettask.cp.CpTls;

/** Swarm peer-link TLS session handling after reloadTls. */
@Timeout(60)
class PeerSocketsSessionTest {

    @TempDir
    Path dir;

    @Test
    void reloadTlsDropsEstablishedSwarmPeer() throws Exception {
        var ca = new PeerTestCa(dir.resolve("ca"));
        var ports = EphemeralPorts.probeDistinct(2);
        var map = "a=1@127.0.0.1:" + ports[0] + ",b=2@127.0.0.1:" + ports[1];
        var configA = PeerConfig.parse(map, "a");
        var configB = PeerConfig.parse(map, "b");
        var deny = Files.writeString(dir.resolve("deny.txt"), "# none\n");
        var cfgA = ca.node("a", PeerConfig.SAN_PREFIX + "a");
        // rebuild with revocations path
        cfgA = new sjws_nettask.cp.CpListenerConfig(PeerSockets.LISTENER, cfgA.certPem(), cfgA.keyPem(), cfgA.caPem(),
                deny, false);
        var tlsA = CpTls.peerContext(cfgA);
        var leafB = ca.node("b", PeerConfig.SAN_PREFIX + "b");
        var serialB = ca.lastSerial();
        var tlsB = CpTls.peerContext(leafB);
        var socketsA = new PeerSockets(tlsA, configA);
        var socketsB = new PeerSockets(tlsB, configB);

        EphemeralPorts.withBindRetry("PeerSocketsSessionTest.drop", () -> {
            try (var listener = socketsA.listen(configA.self().address())) {
                var accepted = new CompletableFuture<SSLSocket>();
                var acceptor = CompletableFuture.runAsync(() -> {
                    try {
                        var ssl = (SSLSocket) listener.accept();
                        socketsA.handshake(ssl, 5_000);
                        assertEquals(2, socketsA.peerWire(ssl));
                        accepted.complete(ssl);
                    } catch (Exception e) {
                        accepted.completeExceptionally(e);
                    }
                });
                var connected = socketsB.dial(configA.self().address());
                var live = accepted.get(10, TimeUnit.SECONDS);
                assertFalse(live.isClosed());
                Files.writeString(deny, "serial:" + serialB.toString(16) + "\n");
                long before = socketsA.policyDropped();
                tlsA.reloadTls();
                assertTrue(socketsA.policyDropped() > before, "reload drops the established swarm peer");
                assertTrue(live.isClosed(), "established socket closed");
                connected.socket().close();
                connected.abort().close();
                acceptor.join();
            }
            return null;
        });
    }

    @Test
    void resumedSwarmPeerRefusedAfterRevocation() throws Exception {
        var ca = new PeerTestCa(dir.resolve("ca2"));
        var ports = EphemeralPorts.probeDistinct(2);
        var map = "a=1@127.0.0.1:" + ports[0] + ",b=2@127.0.0.1:" + ports[1];
        var configA = PeerConfig.parse(map, "a");
        var configB = PeerConfig.parse(map, "b");
        var deny = Files.writeString(dir.resolve("deny2.txt"), "# none\n");
        var cfgA = ca.node("a", PeerConfig.SAN_PREFIX + "a");
        cfgA = new sjws_nettask.cp.CpListenerConfig(PeerSockets.LISTENER, cfgA.certPem(), cfgA.keyPem(), cfgA.caPem(),
                deny, false);
        var tlsA = CpTls.peerContext(cfgA);
        var leafB = ca.node("b", PeerConfig.SAN_PREFIX + "b");
        var serialB = ca.lastSerial();
        var tlsB = CpTls.peerContext(leafB);
        var socketsA = new PeerSockets(tlsA, configA);
        var socketsB = new PeerSockets(tlsB, configB);

        EphemeralPorts.withBindRetry("PeerSocketsSessionTest.resume", () -> {
            try (var listener = socketsA.listen(configA.self().address())) {
                // warm
                for (int i = 0; i < 2; i++) {
                    var accepted = CompletableFuture.supplyAsync(() -> {
                        try {
                            var ssl = (SSLSocket) listener.accept();
                            socketsA.handshake(ssl, 5_000);
                            socketsA.peerWire(ssl);
                            ssl.close();
                            return null;
                        } catch (Exception e) {
                            return e;
                        }
                    });
                    var c = socketsB.dial(configA.self().address());
                    assertNull(accepted.get(10, TimeUnit.SECONDS));
                    c.socket().close();
                    c.abort().close();
                }
                Files.writeString(deny, "serial:" + serialB.toString(16) + "\n");
                tlsA.reloadTls();
                var refused = CompletableFuture.supplyAsync(() -> {
                    try {
                        var ssl = (SSLSocket) listener.accept();
                        try {
                            socketsA.handshake(ssl, 5_000);
                            return null;
                        } catch (Exception e) {
                            return e;
                        } finally {
                            try { ssl.close(); } catch (Exception ignored) {}
                        }
                    } catch (Exception e) {
                        return e;
                    }
                });
                Exception dialErr = null;
                try {
                    socketsB.dial(configA.self().address()).socket().close();
                } catch (Exception e) {
                    dialErr = e;
                }
                var hsErr = refused.get(10, TimeUnit.SECONDS);
                assertTrue(dialErr != null || hsErr != null,
                        "resumed swarm peer must be refused after reloadTls revocation");
            }
            return null;
        });
    }
}
