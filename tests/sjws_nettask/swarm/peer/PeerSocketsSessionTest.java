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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLSession;
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

    /**
     * A dialled peer blocked in a TLS write must not stall {@code reloadTls}: the live entry keeps the plain abort
     * handle and closes it before the SSLSocket.
     */
    @Test
    void reloadTlsDropsBlockedDialWriterWithoutHang() throws Exception {
        var ca = new PeerTestCa(dir.resolve("ca-abort"));
        var ports = EphemeralPorts.probeDistinct(2);
        var map = "a=1@127.0.0.1:" + ports[0] + ",b=2@127.0.0.1:" + ports[1];
        var configA = PeerConfig.parse(map, "a");
        var configB = PeerConfig.parse(map, "b");
        var denyB = Files.writeString(dir.resolve("deny-b.txt"), "# none\n");
        var leafA = ca.node("a", PeerConfig.SAN_PREFIX + "a");
        var serialA = ca.lastSerial();
        var tlsA = CpTls.peerContext(leafA);
        var cfgB = ca.node("b", PeerConfig.SAN_PREFIX + "b");
        cfgB = new sjws_nettask.cp.CpListenerConfig(PeerSockets.LISTENER, cfgB.certPem(), cfgB.keyPem(), cfgB.caPem(),
                denyB, false);
        var tlsB = CpTls.peerContext(cfgB);
        var socketsA = new PeerSockets(tlsA, configA);
        var socketsB = new PeerSockets(tlsB, configB);

        EphemeralPorts.withBindRetry("PeerSocketsSessionTest.abort", () -> {
            try (var listener = socketsA.listen(configA.self().address())) {
                var accepted = new CompletableFuture<SSLSocket>();
                CompletableFuture.runAsync(() -> {
                    try {
                        var ssl = (SSLSocket) listener.accept();
                        socketsA.handshake(ssl, 5_000);
                        socketsA.peerWire(ssl);
                        accepted.complete(ssl);
                    } catch (Exception e) {
                        accepted.completeExceptionally(e);
                    }
                });
                var connected = socketsB.dial(configA.self().address());
                var serverSsl = accepted.get(10, TimeUnit.SECONDS);
                // Stop reading on the acceptor so the dialer's TLS write blocks on the output lock.
                var blocked = new AtomicBoolean();
                var writer = Thread.ofVirtual().name("blocked-tls-write").start(() -> {
                    try {
                        var buf = new byte[64 * 1024];
                        var out = connected.socket().getOutputStream();
                        for (int i = 0; i < 256; i++) {
                            blocked.set(true);
                            out.write(buf);
                            out.flush();
                        }
                    } catch (IOException expected) {
                        // aborted
                    }
                });
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (!blocked.get() && System.nanoTime() < deadline) {
                    Thread.onSpinWait();
                }
                assertTrue(blocked.get(), "writer reached a TLS write");
                Thread.sleep(50); // let the write seat on the TLS lock / TCP window
                Files.writeString(denyB, "serial:" + serialA.toString(16) + "\n");
                long before = socketsB.policyDropped();
                long start = System.nanoTime();
                tlsB.reloadTls();
                long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                assertTrue(tookMs < 3_000, "reloadTls must not hang behind a blocked TLS write; took " + tookMs + " ms");
                assertTrue(socketsB.policyDropped() > before, "dialer drops the blocked peer");
                assertTrue(connected.socket().isClosed() || connected.abort().isClosed());
                writer.join(3_000);
                assertFalse(writer.isAlive(), "blocked writer unblocked by abort");
                serverSsl.close();
            }
            return null;
        });
    }

    /**
     * A reload between handshake revalidation and peerWire registration must still refuse the peer (epoch
     * coordination). Without the retry, peerWire would admit on SAN alone under the new material.
     */
    @Test
    void peerWireRefusesWhenReloadBetweenValidateAndRegister() throws Exception {
        var ca = new PeerTestCa(dir.resolve("ca-toc"));
        var ports = EphemeralPorts.probeDistinct(2);
        var map = "a=1@127.0.0.1:" + ports[0] + ",b=2@127.0.0.1:" + ports[1];
        var configA = PeerConfig.parse(map, "a");
        var configB = PeerConfig.parse(map, "b");
        var deny = Files.writeString(dir.resolve("deny-toc.txt"), "# none\n");
        var cfgA = ca.node("a", PeerConfig.SAN_PREFIX + "a");
        cfgA = new sjws_nettask.cp.CpListenerConfig(PeerSockets.LISTENER, cfgA.certPem(), cfgA.keyPem(), cfgA.caPem(),
                deny, false);
        var tlsA = CpTls.peerContext(cfgA);
        var leafB = ca.node("b", PeerConfig.SAN_PREFIX + "b");
        var serialB = ca.lastSerial();
        var tlsB = CpTls.peerContext(leafB);
        var socketsA = new PeerSockets(tlsA, configA);
        var socketsB = new PeerSockets(tlsB, configB);

        EphemeralPorts.withBindRetry("PeerSocketsSessionTest.toc", () -> {
            try (var listener = socketsA.listen(configA.self().address())) {
                var outcome = new CompletableFuture<Exception>();
                CompletableFuture.runAsync(() -> {
                    try {
                        var ssl = (SSLSocket) listener.accept();
                        socketsA.handshake(ssl, 5_000);
                        socketsA.betweenValidateAndRegister = () -> {
                            try {
                                Files.writeString(deny, "serial:" + serialB.toString(16) + "\n");
                                tlsA.reloadTls();
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        };
                        try {
                            socketsA.peerWire(ssl);
                            outcome.complete(null);
                        } catch (Exception e) {
                            outcome.complete(e);
                        } finally {
                            try {
                                ssl.close();
                            } catch (Exception ignored) {
                            }
                        }
                    } catch (Exception e) {
                        outcome.completeExceptionally(e);
                    }
                });
                try {
                    socketsB.dial(configA.self().address()).socket().close();
                } catch (Exception ignored) {
                    // dialer may see the acceptor close
                }
                var err = outcome.get(15, TimeUnit.SECONDS);
                assertNotNull(err, "peerWire must refuse when reloadTls revokes between validate and register");
            }
            return null;
        });
    }

    /**
     * Accept-side {@code revalidatePeer} must refuse a TLS 1.3 resumed peer after revocation (N4). Asserts resume and
     * that handshake itself throws (not only dial-side refusal), so skipping accept revalidate fails this test.
     */
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
                long firstCreated = warmAccept(socketsA, socketsB, listener, configA);
                long warmCreated = warmAccept(socketsA, socketsB, listener, configA);
                assertTrue(firstCreated > 0 && firstCreated == warmCreated,
                        "warm dial must resume; first=" + firstCreated + " warm=" + warmCreated);

                Files.writeString(deny, "serial:" + serialB.toString(16) + "\n");
                tlsA.reloadTls();

                var hsErr = new AtomicReference<Exception>();
                var acceptor = CompletableFuture.runAsync(() -> {
                    try {
                        var ssl = (SSLSocket) listener.accept();
                        try {
                            socketsA.handshake(ssl, 5_000);
                        } catch (Exception e) {
                            hsErr.set(e);
                        } finally {
                            try {
                                ssl.close();
                            } catch (Exception ignored) {
                            }
                        }
                    } catch (Exception e) {
                        hsErr.set(e);
                    }
                });
                try {
                    socketsB.dial(configA.self().address()).socket().close();
                } catch (Exception ignored) {
                    // dialer may see acceptor refuse
                }
                acceptor.get(15, TimeUnit.SECONDS);
                assertNotNull(hsErr.get(),
                        "accept-side revalidatePeer must refuse a resumed peer after reloadTls revocation");
            }
            return null;
        });
    }

    /** Warm one accept+dial and return the dialer's session creation time (for resume checks). */
    private static long warmAccept(PeerSockets socketsA, PeerSockets socketsB, java.net.ServerSocket listener,
            PeerConfig configA) throws Exception {
        var created = new AtomicLong();
        var accepted = CompletableFuture.supplyAsync(() -> {
            try {
                var ssl = (SSLSocket) listener.accept();
                socketsA.handshake(ssl, 5_000);
                socketsA.peerWire(ssl);
                // Brief application data so TLS 1.3 NewSessionTicket can land.
                ssl.getOutputStream().write(1);
                ssl.getOutputStream().flush();
                if (ssl.getInputStream().read() < 0) {
                    throw new IOException("peer closed");
                }
                ssl.close();
                return null;
            } catch (Exception e) {
                return e;
            }
        });
        var c = socketsB.dial(configA.self().address());
        var ssl = (SSLSocket) c.socket();
        capture(ssl.getSession(), created);
        ssl.getOutputStream().write(1);
        ssl.getOutputStream().flush();
        if (ssl.getInputStream().read() < 0) {
            throw new IOException("peer closed");
        }
        assertNull(accepted.get(10, TimeUnit.SECONDS));
        c.socket().close();
        c.abort().close();
        return created.get();
    }

    private static void capture(SSLSession s, AtomicLong created) {
        created.set(s.getCreationTime());
    }

}
