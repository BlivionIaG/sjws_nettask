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
     * handle and closes it before the SSLSocket. The writer is latched in the SSL write (stack + state) before
     * reload so an SSLSocket-only close cannot race past an unparked writer.
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
                try {
                    listener.setReceiveBufferSize(4 * 1024);
                } catch (IOException ignored) {
                    // best-effort: smaller window fills faster
                }
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
                // Non-reading peer + tiny buffers so the dialer's TLS write parks on the output lock / TCP window.
                try {
                    serverSsl.setReceiveBufferSize(4 * 1024);
                    connected.abort().setSendBufferSize(4 * 1024);
                    connected.socket().setSendBufferSize(4 * 1024);
                } catch (IOException ignored) {
                    // best-effort
                }
                var writer = Thread.ofPlatform().daemon().name("blocked-tls-write").start(() -> {
                    try {
                        var buf = new byte[64 * 1024];
                        var out = connected.socket().getOutputStream();
                        while (!Thread.currentThread().isInterrupted()) {
                            out.write(buf);
                        }
                    } catch (IOException expected) {
                        // aborted
                    }
                });
                awaitParkedInSslWrite(writer, 10_000);
                Files.writeString(denyB, "serial:" + serialA.toString(16) + "\n");
                long before = socketsB.policyDropped();
                var reload = CompletableFuture.runAsync(() -> {
                    try {
                        tlsB.reloadTls();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
                try {
                    reload.get(3, TimeUnit.SECONDS);
                } catch (Exception timedOut) {
                    // Unblock the hung SSLSocket.close so the suite can finish; the assertion below fails the mutant.
                    try {
                        connected.abort().setSoLinger(true, 0);
                        connected.abort().close();
                    } catch (Exception ignored) {
                    }
                    fail("reloadTls must not hang behind a blocked TLS write: " + timedOut);
                }
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
     * Wait until {@code writer} is parked inside an SSL/socket write. Accepts BLOCKED/WAITING/TIMED_WAITING, or
     * RUNNABLE when the stack shows the native/socket write frame (common while blocked in {@code socketWrite0}).
     */
    private static void awaitParkedInSslWrite(Thread writer, long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        String last = "";
        while (System.nanoTime() < deadline) {
            if (!writer.isAlive()) {
                fail("writer exited before parking in an SSL/socket write");
            }
            var state = writer.getState();
            var frames = writer.getStackTrace();
            last = state + " " + java.util.Arrays.toString(frames);
            if (frames.length > 0 && stackInSslOrSocketWrite(frames)) {
                if (state == Thread.State.BLOCKED || state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING
                        || state == Thread.State.RUNNABLE) {
                    return;
                }
            }
            Thread.sleep(5);
        }
        fail("writer never parked in SSL write within " + timeoutMillis + " ms; last=" + last);
    }

    private static boolean stackInSslOrSocketWrite(StackTraceElement[] frames) {
        for (var f : frames) {
            String c = f.getClassName();
            String m = f.getMethodName();
            if ("socketWrite0".equals(m) || "socketWrite".equals(m)) {
                return true;
            }
            if (m.startsWith("write") && (c.contains("SocketOutputStream") || c.contains("SSLSocket")
                    || c.contains("AppOutputStream") || c.contains("OutputRecord") || c.contains("NioSocketImpl")
                    || c.contains("SocksSocketImpl") || c.contains("Socket$SocketOutputStream")
                    || c.contains("SSLSocketImpl"))) {
                return true;
            }
        }
        return false;
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


    /**
     * #334: a closed dialled peer and the matching accepted peer leave {@code live} when {@link PeerSockets#released}
     * runs (as {@link sjws_nettask.cluster.TcpTransport} does on close / admit refusal / reader exit). No TLS reload
     * is required. Mutating {@code released} to a no-op leaves liveSize at 1.
     */
    @Test
    void closedPeersReclaimedFromLiveWithoutReload() throws Exception {
        var ca = new PeerTestCa(dir.resolve("ca-reclaim"));
        var ports = EphemeralPorts.probeDistinct(2);
        var map = "a=1@127.0.0.1:" + ports[0] + ",b=2@127.0.0.1:" + ports[1];
        var configA = PeerConfig.parse(map, "a");
        var configB = PeerConfig.parse(map, "b");
        var deny = Files.writeString(dir.resolve("deny-reclaim.txt"), "# none\n");
        var cfgA = ca.node("a", PeerConfig.SAN_PREFIX + "a");
        cfgA = new sjws_nettask.cp.CpListenerConfig(PeerSockets.LISTENER, cfgA.certPem(), cfgA.keyPem(), cfgA.caPem(),
                deny, false);
        var tlsA = CpTls.peerContext(cfgA);
        var tlsB = CpTls.peerContext(ca.node("b", PeerConfig.SAN_PREFIX + "b"));
        var socketsA = new PeerSockets(tlsA, configA);
        var socketsB = new PeerSockets(tlsB, configB);

        EphemeralPorts.withBindRetry("PeerSocketsSessionTest.reclaim", () -> {
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
                assertEquals(1, socketsA.liveSize(), "accept registered");
                assertEquals(1, socketsB.liveSize(), "dial registered");
                assertFalse(live.isClosed());

                connected.socket().close();
                connected.abort().close();
                live.close();
                // Closing alone must not be enough historically; release is what TcpTransport calls.
                assertEquals(1, socketsA.liveSize(), "closed accept still listed until released");
                assertEquals(1, socketsB.liveSize(), "closed dial still listed until released");
                socketsA.released(live);
                socketsB.released(connected.socket());
                assertEquals(0, socketsA.liveSize(), "acceptor reclaims without reload");
                assertEquals(0, socketsB.liveSize(), "dialer reclaims without reload");
                // Idempotent.
                socketsA.released(live);
                socketsB.released(connected.socket());
                assertEquals(0, socketsA.liveSize());
                assertEquals(0, socketsB.liveSize());
                acceptor.join();
            }
            return null;
        });
    }

    /**
     * #334 end-to-end: when TcpTransport closes after a successful mTLS hop, both live registries are empty without
     * a reload. Fails if the transport forgets {@link sjws_nettask.cluster.TcpTransport.SocketFactory#released} or if
     * {@link PeerSockets#released} is a no-op.
     */
    @Test
    void transportCloseReclaimsLiveWithoutReload() throws Exception {
        var ca = new PeerTestCa(dir.resolve("ca-reclaim-t"));
        var ports = EphemeralPorts.probeDistinct(2);
        var map = "a=1@127.0.0.1:" + ports[0] + ",b=2@127.0.0.1:" + ports[1];
        var configA = PeerConfig.parse(map, "a");
        var configB = PeerConfig.parse(map, "b");
        var deny = Files.writeString(dir.resolve("deny-reclaim-t.txt"), "# none\n");
        var cfgA = ca.node("a", PeerConfig.SAN_PREFIX + "a");
        cfgA = new sjws_nettask.cp.CpListenerConfig(PeerSockets.LISTENER, cfgA.certPem(), cfgA.keyPem(), cfgA.caPem(),
                deny, false);
        var tlsA = CpTls.peerContext(cfgA);
        var tlsB = CpTls.peerContext(ca.node("b", PeerConfig.SAN_PREFIX + "b"));
        var socketsA = new PeerSockets(tlsA, configA);
        var socketsB = new PeerSockets(tlsB, configB);
        var key = sjws_nettask.cluster.ClusterKey.of("swarm-peer-reclaim-key-012".getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                null);
        var members = configA.members();
        var group = new sjws_nettask.cluster.GroupId("swarm");
        EphemeralPorts.withBindRetry("PeerSocketsSessionTest.reclaimTransport", () -> {
            try (var a = sjws_nettask.cluster.TcpTransport.open(members.get(0).id(), members, key,
                    sjws_nettask.cluster.Clock.SYSTEM, 64, socketsA);
                    var b = sjws_nettask.cluster.TcpTransport.open(members.get(1).id(), members, key,
                            sjws_nettask.cluster.Clock.SYSTEM, 64, socketsB)) {
                a.attach(members.get(0).id(), (from, frame) -> {
                });
                var rpc = b.attach(members.get(1).id(), (from, frame) -> {
                });
                rpc.send(members.get(0).id(), new sjws_nettask.cluster.SimTransport.Frame(group, 1, new byte[] {1}));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (System.nanoTime() < deadline && (socketsA.liveSize() < 1 || socketsB.liveSize() < 1)) {
                    a.deliver(50);
                    b.deliver(50);
                    Thread.sleep(10);
                }
                assertTrue(socketsA.liveSize() >= 1, "acceptor registered the peer");
                assertTrue(socketsB.liveSize() >= 1, "dialer registered the peer");
            }
            assertEquals(0, socketsA.liveSize(), "transport close reclaims acceptor live");
            assertEquals(0, socketsB.liveSize(), "transport close reclaims dialer live");
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
        socketsB.released(c.socket());
        return created.get();
    }

    private static void capture(SSLSession s, AtomicLong created) {
        created.set(s.getCreationTime());
    }


    /**
     * #435 / #321 N1: {@code registerLive} fails closed once the TLS-epoch retry budget is spent.
     */
    @Test
    void registerLiveFailsClosedAfterEpochRetryBudget() throws Exception {
        var ca = new PeerTestCa(dir.resolve("ca-budget"));
        var ports = EphemeralPorts.probeDistinct(2);
        var map = "a=1@127.0.0.1:" + ports[0] + ",b=2@127.0.0.1:" + ports[1];
        var configA = PeerConfig.parse(map, "a");
        var configB = PeerConfig.parse(map, "b");
        var deny = Files.writeString(dir.resolve("deny-budget.txt"), "# none\n");
        var cfgA = ca.node("a", PeerConfig.SAN_PREFIX + "a");
        cfgA = new sjws_nettask.cp.CpListenerConfig(PeerSockets.LISTENER, cfgA.certPem(), cfgA.keyPem(), cfgA.caPem(),
                deny, false);
        var tlsA = CpTls.peerContext(cfgA);
        var tlsB = CpTls.peerContext(ca.node("b", PeerConfig.SAN_PREFIX + "b"));
        var socketsA = new PeerSockets(tlsA, configA);
        var socketsB = new PeerSockets(tlsB, configB);

        EphemeralPorts.withBindRetry("PeerSocketsSessionTest.budget", () -> {
            try (var listener = socketsA.listen(configA.self().address())) {
                var outcome = new CompletableFuture<Exception>();
                var bumps = new java.util.concurrent.atomic.AtomicInteger();
                CompletableFuture.runAsync(() -> {
                    try {
                        var ssl = (SSLSocket) listener.accept();
                        socketsA.handshake(ssl, 5_000);
                        Runnable bump = new Runnable() {
                            @Override
                            public void run() {
                                bumps.incrementAndGet();
                                socketsA.betweenValidateAndRegister = this;
                                socketsA.bumpTlsEpochForTest();
                            }
                        };
                        socketsA.betweenValidateAndRegister = bump;
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
                }
                var err = outcome.get(20, TimeUnit.SECONDS);
                assertNotNull(err, "peerWire must fail closed when the epoch keeps moving");
                Throwable t = err;
                while (t.getCause() != null) {
                    t = t.getCause();
                }
                assertTrue(String.valueOf(t.getMessage()).contains("retry budget")
                                || String.valueOf(err.getMessage()).contains("retry budget"),
                        "message=" + err.getMessage() + " root=" + t.getMessage());
                assertEquals(PeerSockets.REGISTER_LIVE_MAX_ATTEMPTS, bumps.get());
                assertEquals(1L, socketsA.registerBudgetExhausted(),
                        "budget exhaustion increments the registerBudgetExhausted counter");
            }
            return null;
        });
    }

    /**
     * #435 / #321 N2: drop-path abort + concurrent Live replace still clears the closed socket (remove by key).
     */
    @Test
    void dropRemovesClosedSocketWhenLiveReplacedConcurrently() throws Exception {
        var ca = new PeerTestCa(dir.resolve("ca-race"));
        var ports = EphemeralPorts.probeDistinct(2);
        var map = "a=1@127.0.0.1:" + ports[0] + ",b=2@127.0.0.1:" + ports[1];
        var configA = PeerConfig.parse(map, "a");
        var configB = PeerConfig.parse(map, "b");
        var deny = Files.writeString(dir.resolve("deny-race.txt"), "# none\n");
        var cfgA = ca.node("a", PeerConfig.SAN_PREFIX + "a");
        cfgA = new sjws_nettask.cp.CpListenerConfig(PeerSockets.LISTENER, cfgA.certPem(), cfgA.keyPem(), cfgA.caPem(),
                deny, false);
        var tlsA = CpTls.peerContext(cfgA);
        var leafB = ca.node("b", PeerConfig.SAN_PREFIX + "b");
        var serialB = ca.lastSerial();
        var tlsB = CpTls.peerContext(leafB);
        var socketsA = new PeerSockets(tlsA, configA);
        var socketsB = new PeerSockets(tlsB, configB);

        EphemeralPorts.withBindRetry("PeerSocketsSessionTest.dropRace", () -> {
            try (var listener = socketsA.listen(configA.self().address())) {
                var accepted = new CompletableFuture<SSLSocket>();
                CompletableFuture.runAsync(() -> {
                    try {
                        var ssl = (SSLSocket) listener.accept();
                        socketsA.handshake(ssl, 5_000);
                        assertEquals(2, socketsA.peerWire(ssl));
                        accepted.complete(ssl);
                    } catch (Exception e) {
                        accepted.completeExceptionally(e);
                    }
                });
                var dialed = socketsB.dial(configA.self().address());
                var live = accepted.get(10, TimeUnit.SECONDS);
                assertEquals(1, socketsA.liveSize());
                var replaced = new java.util.concurrent.atomic.AtomicBoolean();
                socketsA.betweenDropFailAndAbort = () -> {
                    try {
                        socketsA.replaceLiveAbortForTest(live, new java.net.Socket());
                        replaced.set(true);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                };
                Files.writeString(deny, "serial:" + serialB.toString(16) + "\n");
                tlsA.reloadTls();
                assertTrue(replaced.get(), "drop path must hit the replace hook");
                assertTrue(live.isClosed(), "stale abort still closes the SSLSocket");
                assertEquals(0, socketsA.liveSize(), "closed socket must leave live even after Live replace");
                dialed.socket().close();
            }
            return null;
        });
    }

    /** #435: closed-socket sweep removes by key despite a concurrent Live replace. */
    @Test
    void dropSweepsClosedSocketByKeyDespiteLiveReplace() throws Exception {
        var ca = new PeerTestCa(dir.resolve("ca-closed-key"));
        var ports = EphemeralPorts.probeDistinct(2);
        var map = "a=1@127.0.0.1:" + ports[0] + ",b=2@127.0.0.1:" + ports[1];
        var configA = PeerConfig.parse(map, "a");
        var configB = PeerConfig.parse(map, "b");
        var deny = Files.writeString(dir.resolve("deny-closed-key.txt"), "# none\n");
        var cfgA = ca.node("a", PeerConfig.SAN_PREFIX + "a");
        cfgA = new sjws_nettask.cp.CpListenerConfig(PeerSockets.LISTENER, cfgA.certPem(), cfgA.keyPem(), cfgA.caPem(),
                deny, false);
        var tlsA = CpTls.peerContext(cfgA);
        var tlsB = CpTls.peerContext(ca.node("b", PeerConfig.SAN_PREFIX + "b"));
        var socketsA = new PeerSockets(tlsA, configA);
        var socketsB = new PeerSockets(tlsB, configB);

        EphemeralPorts.withBindRetry("PeerSocketsSessionTest.closedKey", () -> {
            try (var listener = socketsA.listen(configA.self().address())) {
                var accepted = new CompletableFuture<SSLSocket>();
                CompletableFuture.runAsync(() -> {
                    try {
                        var ssl = (SSLSocket) listener.accept();
                        socketsA.handshake(ssl, 5_000);
                        assertEquals(2, socketsA.peerWire(ssl));
                        accepted.complete(ssl);
                    } catch (Exception e) {
                        accepted.completeExceptionally(e);
                    }
                });
                var dialed = socketsB.dial(configA.self().address());
                var live = accepted.get(10, TimeUnit.SECONDS);
                live.close();
                dialed.socket().close();
                assertEquals(1, socketsA.liveSize());
                var replaced = new java.util.concurrent.atomic.AtomicBoolean();
                socketsA.betweenClosedSweepAndRemove = () -> {
                    try {
                        socketsA.replaceLiveAbortForTest(live, new java.net.Socket());
                        replaced.set(true);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                };
                socketsA.dropPeersFailingPolicy();
                assertTrue(replaced.get(), "closed sweep must hit the replace hook");
                assertEquals(0, socketsA.liveSize(), "closed-socket sweep removes by key despite Live replace");
            }
            return null;
        });
    }


}
