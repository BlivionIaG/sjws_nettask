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
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.clusterharness.EphemeralPorts;

/**
 * Peer-link TLS session handling after a material reload: a reconnect that reuses a cached session
 * against the same listener must still honour a revocation that landed with {@link CpServerTls#reloadTls()}.
 */
@Timeout(60)
class CpPeerTlsSessionTest {

    @TempDir
    Path dir;

    private record Side(Exception error, boolean handshakeReturned, boolean handshakeCompleted, boolean peerVerified,
            long sessionCreatedAt, String sessionIdHex) {
    }

    private record Outcome(Side client, Side server) {
    }

    private Path denylist(String name, BigInteger... serials) throws IOException {
        var text = new StringBuilder("# test denylist\n");
        for (var serial : serials) {
            text.append("serial:").append(serial.toString(16)).append('\n');
        }
        return Files.writeString(dir.resolve(name), text);
    }

    private static CpListenerConfig peer(CpTestCa ca, String node, Path revocations) throws Exception {
        return ca.listener("cp-peer", node, System.currentTimeMillis() + 86_400_000L, revocations, CpTestCa.san(node));
    }

    /** One accept+dial pair against an already-bound listener (same host:port → session cache can hit). */
    private static Outcome exchange(CpPeerSockets server, ServerSocket listener, CpServerTls clientTls)
            throws Exception {
        var serverSide = CompletableFuture.supplyAsync(() -> observeAccept(server, listener));
        var client = observeDial(clientTls, (InetSocketAddress) listener.getLocalSocketAddress());
        return new Outcome(client, serverSide.get(10, TimeUnit.SECONDS));
    }

    private static Side observeAccept(CpPeerSockets server, ServerSocket listener) {
        var completed = new AtomicBoolean();
        var created = new AtomicLong();
        var idHex = new String[1];
        SSLSocket accepted = null;
        boolean handshakeReturned = false;
        try {
            accepted = (SSLSocket) listener.accept();
            accepted.addHandshakeCompletedListener(event -> {
                completed.set(true);
                capture(event.getSession(), created, idHex);
            });
            server.handshake(accepted, 5_000);
            handshakeReturned = true;
            awaitHandshakeSignal(completed, accepted);
            assertEquals(2, server.peerWire(accepted));
            if (created.get() == 0) {
                capture(accepted.getSession(), created, idHex);
            }
            // Brief application data so TLS 1.3 NewSessionTicket can land before close.
            accepted.getOutputStream().write(1);
            accepted.getOutputStream().flush();
            if (accepted.getInputStream().read() < 0) {
                throw new IOException("peer closed before application data");
            }
            return new Side(null, true, completed.get(), peerVerified(accepted), created.get(), idHex[0]);
        } catch (Exception e) {
            return new Side(e, handshakeReturned, completed.get(), peerVerified(accepted), created.get(), idHex[0]);
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

    private static Side observeDial(CpServerTls clientTls, InetSocketAddress to) {
        var completed = new AtomicBoolean();
        var created = new AtomicLong();
        var idHex = new String[1];
        SSLSocket socket = null;
        boolean handshakeReturned = false;
        try {
            socket = (SSLSocket) clientTls.context().getSocketFactory().createSocket();
            socket.setSSLParameters(clientTls.parameters());
            socket.setTcpNoDelay(true);
            socket.addHandshakeCompletedListener(event -> {
                completed.set(true);
                capture(event.getSession(), created, idHex);
            });
            socket.connect(to, CpPeerSockets.CONNECT_TIMEOUT_MILLIS);
            socket.setSoTimeout(CpPeerSockets.HANDSHAKE_TIMEOUT_MILLIS);
            socket.startHandshake();
            handshakeReturned = true;
            awaitHandshakeSignal(completed, socket);
            if (created.get() == 0) {
                capture(socket.getSession(), created, idHex);
            }
            boolean verified = peerVerified(socket);
            boolean hc = completed.get();
            socket.setSoTimeout(5_000);
            socket.getOutputStream().write(1);
            socket.getOutputStream().flush();
            if (socket.getInputStream().read() < 0) {
                throw new IOException("peer closed before application data");
            }
            return new Side(null, true, hc, verified, created.get(), idHex[0]);
        } catch (Exception e) {
            return new Side(e, handshakeReturned, completed.get(), peerVerified(socket), created.get(), idHex[0]);
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

    private static void capture(SSLSession s, AtomicLong created, String[] idHex) {
        created.set(s.getCreationTime());
        byte[] id = s.getId();
        idHex[0] = id == null || id.length == 0 ? "" : HexFormat.of().formatHex(id);
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

    private static void awaitHandshakeSignal(AtomicBoolean completed, SSLSocket socket) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500);
        while (System.nanoTime() < deadline) {
            if (completed.get() || peerVerified(socket)) {
                return;
            }
            Thread.onSpinWait();
        }
    }

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
            if (t instanceof java.security.cert.CertificateException) {
                return;
            }
            if (t instanceof IllegalStateException
                    && String.valueOf(t.getMessage()).contains("pinned")) {
                return;
            }
            String message = String.valueOf(t.getMessage());
            // Peer-link post-handshake material re-check (session handling).
            if (t instanceof IOException && message.contains("refused after handshake")) {
                return;
            }
            if (t instanceof IOException && message.contains("peer closed before application data")) {
                return;
            }
            if (t instanceof IOException
                    && (message.contains("Broken pipe") || message.contains("Connection reset"))) {
                // Mid-handshake refuse, or the peer closed after its own post-handshake refuse.
                return;
            }
        }
        fail(what + ": expected a handshake/material refusal, got "
                + seen.error()
                + " (handshakeReturned=" + seen.handshakeReturned()
                + ", handshakeCompleted=" + seen.handshakeCompleted()
                + ", peerVerified=" + seen.peerVerified() + ")");
    }

    private static void assertConnected(Outcome out, String what) {
        assertNull(out.server().error(), what + " server: " + out.server().error());
        assertNull(out.client().error(), what + " client: " + out.client().error());
        assertTrue(out.server().peerVerified() || out.server().handshakeCompleted(), what + " server verified");
        assertTrue(out.client().peerVerified() || out.client().handshakeCompleted(), what + " client verified");
    }

    private static boolean resumed(Side first, Side warm) {
        return first.sessionCreatedAt() > 0 && first.sessionCreatedAt() == warm.sessionCreatedAt();
    }

    private CpPeerSockets sockets(CpServerTls tls, int port, String self) {
        var peers = List.of(
                new CpConfig.Peer(1, "a", new InetSocketAddress("127.0.0.1", port), false, CpTestCa.san("a")),
                new CpConfig.Peer(2, "b", new InetSocketAddress("127.0.0.1", 1), false, CpTestCa.san("b")));
        return new CpPeerSockets(tls, peers, self);
    }

    /**
     * Warm the dialer's session cache against a fixed listener, confirm reuse, revoke the server on the
     * dialer, then dial again with the same context. The dial must be refused.
     */
    @Test
    void dialerRefusesResumedPeerAfterReload() throws Exception {
        var ca = new CpTestCa(dir.resolve("ca-dialer"));
        var serverTls = CpTls.peerContext(ca.peer("a"));
        var serverSerial = ca.lastSerial();
        var deny = denylist("dialer-deny");
        var dialer = CpTls.peerContext(peer(ca, "b", deny));

        EphemeralPorts.withBindRetry("CpPeerTlsSessionTest.dialer", () -> {
            int port = EphemeralPorts.probe();
            var server = sockets(serverTls, port, "a");
            try (var listener = server.listen(new InetSocketAddress("127.0.0.1", port))) {
                var first = exchange(server, listener, dialer);
                assertConnected(first, "first dial");
                var warm = exchange(server, listener, dialer);
                assertConnected(warm, "warm dial");
                assertTrue(resumed(first.client(), warm.client()),
                        "warm dial must reuse the cached session; firstCreated="
                                + first.client().sessionCreatedAt() + " warmCreated="
                                + warm.client().sessionCreatedAt() + " firstId=" + first.client().sessionIdHex()
                                + " warmId=" + warm.client().sessionIdHex());

                Files.writeString(deny, "serial:" + serverSerial.toString(16) + "\n");
                dialer.reloadTls();

                var after = exchange(server, listener, dialer);
                assertRefused(after.client(),
                        "dialer must refuse a resumed peer after reloadTls with the peer revoked");
            }
            return null;
        });
    }

    /**
     * Warm the acceptor's session cache via a fixed listener, confirm the dialer reuses its session,
     * revoke the client on the acceptor, then accept again. The accept must be refused.
     */
    @Test
    void listenerRefusesResumedPeerAfterReload() throws Exception {
        var ca = new CpTestCa(dir.resolve("ca-listener"));
        var deny = denylist("listener-deny");
        var acceptorTls = CpTls.peerContext(peer(ca, "a", deny));
        var clientCfg = ca.peer("b");
        var clientSerial = ca.lastSerial();
        var dialer = CpTls.peerContext(clientCfg);

        EphemeralPorts.withBindRetry("CpPeerTlsSessionTest.listener", () -> {
            int port = EphemeralPorts.probe();
            var server = sockets(acceptorTls, port, "a");
            try (var listener = server.listen(new InetSocketAddress("127.0.0.1", port))) {
                var first = exchange(server, listener, dialer);
                assertConnected(first, "first dial");
                var warm = exchange(server, listener, dialer);
                assertConnected(warm, "warm dial");
                assertTrue(resumed(first.client(), warm.client()),
                        "warm dial must reuse the cached session; firstCreated="
                                + first.client().sessionCreatedAt() + " warmCreated="
                                + warm.client().sessionCreatedAt() + " firstId=" + first.client().sessionIdHex()
                                + " warmId=" + warm.client().sessionIdHex());

                Files.writeString(deny, "serial:" + clientSerial.toString(16) + "\n");
                acceptorTls.reloadTls();

                var after = exchange(server, listener, dialer);
                assertRefused(after.server(),
                        "acceptor must refuse a resumed peer after reloadTls with the peer revoked");
                assertRefused(after.client(),
                        "dialer must see the acceptor's refusal after reloadTls");
            }
            return null;
        });
    }


    /**
     * Peer cert stays trusted, but after the pin table no longer binds it to that voter the resumed
     * reconnect is refused on both the listener and the dialer.
     */
    @Test
    void resumedPeerRefusedAfterPinUnbind() throws Exception {
        var ca = new CpTestCa(dir.resolve("ca-unbind"));
        var acceptorTls = CpTls.peerContext(ca.peer("a"));
        var dialerTls = CpTls.peerContext(ca.peer("b"));

        EphemeralPorts.withBindRetry("CpPeerTlsSessionTest.unbind", () -> {
            int port = EphemeralPorts.probe();
            var server = sockets(acceptorTls, port, "a");
            try (var listener = server.listen(new InetSocketAddress("127.0.0.1", port))) {
                var first = exchange(server, listener, dialerTls);
                assertConnected(first, "first dial");
                var warm = exchange(server, listener, dialerTls);
                assertConnected(warm, "warm dial");
                assertTrue(resumed(first.client(), warm.client()), "warm dial must resume");

                // Same addresses; b's pin now expects a SAN the certificate does not carry.
                var rebound = List.of(
                        new CpConfig.Peer(1, "a", new InetSocketAddress("127.0.0.1", port), false, CpTestCa.san("a")),
                        new CpConfig.Peer(2, "b", new InetSocketAddress("127.0.0.1", 1), false,
                                "uri:urn:sjws:node:b-rebound"));
                server.replacePins(rebound);
                var after = exchange(server, listener, dialerTls);
                assertRefused(after.server(),
                        "acceptor must refuse a resumed peer whose pin no longer binds");
                assertRefused(after.client(),
                        "dialer must see the acceptor's refusal after pin unbind");

                // Dialer-side: pin for the server address no longer matches the server's cert SAN.
                var dialerRebound = List.of(
                        new CpConfig.Peer(1, "a", new InetSocketAddress("127.0.0.1", port), false,
                                "uri:urn:sjws:node:a-rebound"),
                        new CpConfig.Peer(2, "b", new InetSocketAddress("127.0.0.1", 1), false, CpTestCa.san("b")));
                var dialerSockets = new CpPeerSockets(dialerTls, dialerRebound, "b");
                var acceptAgain = CompletableFuture.supplyAsync(() -> {
                    try {
                        var ssl = (SSLSocket) listener.accept();
                        try {
                            server.handshake(ssl, 5_000);
                        } finally {
                            ssl.close();
                        }
                        return null;
                    } catch (Exception e) {
                        return e;
                    }
                });
                Exception dialErr = null;
                try {
                    dialerSockets.connect(new InetSocketAddress("127.0.0.1", port)).close();
                } catch (Exception e) {
                    dialErr = e;
                }
                acceptAgain.get(10, TimeUnit.SECONDS);
                assertNotNull(dialErr, "dialer connect must refuse when the peer pin no longer binds");
            }
            return null;
        });
    }

    /** An established connection is closed when reloadTls revokes its peer. */
    @Test
    void reloadTlsDropsEstablishedPeer() throws Exception {
        var ca = new CpTestCa(dir.resolve("ca-drop"));
        var deny = denylist("drop-deny");
        var acceptorTls = CpTls.peerContext(peer(ca, "a", deny));
        var clientCfg = ca.peer("b");
        var clientSerial = ca.lastSerial();
        var dialerTls = CpTls.peerContext(clientCfg);

        EphemeralPorts.withBindRetry("CpPeerTlsSessionTest.drop", () -> {
            int port = EphemeralPorts.probe();
            var server = sockets(acceptorTls, port, "a");
            try (var listener = server.listen(new InetSocketAddress("127.0.0.1", port))) {
                var accepted = new CompletableFuture<SSLSocket>();
                var acceptor = CompletableFuture.runAsync(() -> {
                    try {
                        var ssl = (SSLSocket) listener.accept();
                        server.handshake(ssl, 5_000);
                        assertEquals(2, server.peerWire(ssl));
                        accepted.complete(ssl);
                    } catch (Exception e) {
                        accepted.completeExceptionally(e);
                    }
                });
                var client = (SSLSocket) dialerTls.context().getSocketFactory().createSocket();
                client.setSSLParameters(dialerTls.parameters());
                client.connect(new InetSocketAddress("127.0.0.1", port), CpPeerSockets.CONNECT_TIMEOUT_MILLIS);
                client.startHandshake();
                // Dialer-side policy + track via CpPeerSockets.connect path is separate; here the acceptor tracks.
                var live = accepted.get(10, TimeUnit.SECONDS);
                assertFalse(live.isClosed(), "established before reload");
                assertTrue(server.policyDropped() == 0);

                Files.writeString(deny, "serial:" + clientSerial.toString(16) + "\n");
                long before = server.policyDropped();
                acceptorTls.reloadTls();
                assertTrue(server.policyDropped() > before, "reload must drop the established peer");
                assertTrue(live.isClosed(), "established socket closed after reload");
                acceptor.join();
                client.close();
            }
            return null;
        });
    }

    /**
     * A peer refused during handshake never has an application frame accepted: the transport handler
     * sees nothing even when the dialer writes a frame immediately after its own handshake.
     */
    @Test
    void refusedHandshakeAcceptsNoFrame() throws Exception {
        var ca = new CpTestCa(dir.resolve("ca-frame"));
        var serverCfg = ca.peer("a");
        var clientCfg = ca.peer("b");
        var clientSerial = ca.lastSerial();
        var deny = denylist("frame-deny", clientSerial);
        var key = sjws_nettask.cluster.ClusterKey.of("cp-raft-key-for-tests-0123".getBytes(java.nio.charset.StandardCharsets.US_ASCII), null);
        var ports = EphemeralPorts.probeDistinct(2);
        var members = new java.util.ArrayList<sjws_nettask.cluster.Member>();
        for (int wire = 1; wire <= 2; wire++) {
            members.add(new sjws_nettask.cluster.Member(new sjws_nettask.cluster.NodeId(wire, "cp-" + wire),
                    new sjws_nettask.cluster.SiteId("cp"),
                    new InetSocketAddress("127.0.0.1", ports[wire - 1]), 1L, sjws_nettask.cluster.MemberState.ALIVE,
                    false));
        }
        var got = new java.util.ArrayList<sjws_nettask.cluster.SimTransport.Frame>();
        var group = new sjws_nettask.cluster.GroupId("cp-config");
        // Acceptor revokes the dialer's cert from the start.
        var acceptorSockets = new CpPeerSockets(CpTls.peerContext(peer(ca, "a", deny)), List.of(
                new CpConfig.Peer(1, "a", new InetSocketAddress("127.0.0.1", ports[0]), false, CpTestCa.san("a")),
                new CpConfig.Peer(2, "b", new InetSocketAddress("127.0.0.1", ports[1]), false, CpTestCa.san("b"))), "a");
        var dialerSockets = new CpPeerSockets(CpTls.peerContext(clientCfg), List.of(
                new CpConfig.Peer(1, "a", new InetSocketAddress("127.0.0.1", ports[0]), false, CpTestCa.san("a")),
                new CpConfig.Peer(2, "b", new InetSocketAddress("127.0.0.1", ports[1]), false, CpTestCa.san("b"))), "b");
        try (var a = sjws_nettask.cluster.TcpTransport.open(members.get(0).id(), members, key,
                sjws_nettask.cluster.Clock.SYSTEM, 64, acceptorSockets);
                var b = sjws_nettask.cluster.TcpTransport.open(members.get(1).id(), members, key,
                        sjws_nettask.cluster.Clock.SYSTEM, 64, dialerSockets)) {
            a.attach(members.get(0).id(), (from, frame) -> got.add(frame));
            var rpc = b.attach(members.get(1).id(), (from, frame) -> { });
            rpc.send(members.get(0).id(), new sjws_nettask.cluster.SimTransport.Frame(group, 7, new byte[] {7}));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline && a.handshakeFailures() < 1) {
                a.deliver(50);
                b.deliver(50);
                Thread.sleep(10);
            }
            assertTrue(a.handshakeFailures() >= 1, "acceptor counted a handshake failure");
            assertEquals(0, a.inboundConnections(), "no authenticated inbound slot");
            a.deliver(100);
            assertTrue(got.isEmpty(), "no application frame accepted after a refused handshake");
        }
    }

    /**
     * A dialled CP peer blocked in a TLS write must not stall {@code reloadTls}: the live entry keeps the plain
     * abort handle and closes it before the SSLSocket. The writer is latched in the SSL write (stack + state) before
     * reload so an SSLSocket-only close cannot race past an unparked writer.
     */
    @Test
    void reloadTlsDropsBlockedDialWriterWithoutHang() throws Exception {
        var ca = new CpTestCa(dir.resolve("ca-abort"));
        var deny = denylist("abort-deny");
        var serverTls = CpTls.peerContext(ca.peer("a"));
        var serverSerial = ca.lastSerial();
        var dialerTls = CpTls.peerContext(peer(ca, "b", deny));

        EphemeralPorts.withBindRetry("CpPeerTlsSessionTest.abort", () -> {
            int port = EphemeralPorts.probe();
            var server = sockets(serverTls, port, "a");
            var dialer = new CpPeerSockets(dialerTls, List.of(
                    new CpConfig.Peer(1, "a", new InetSocketAddress("127.0.0.1", port), false, CpTestCa.san("a")),
                    new CpConfig.Peer(2, "b", new InetSocketAddress("127.0.0.1", 1), false, CpTestCa.san("b"))), "b");
            try (var listener = server.listen(new InetSocketAddress("127.0.0.1", port))) {
                try {
                    listener.setReceiveBufferSize(4 * 1024);
                } catch (IOException ignored) {
                    // best-effort: smaller window fills faster
                }
                var accepted = new CompletableFuture<SSLSocket>();
                CompletableFuture.runAsync(() -> {
                    try {
                        var ssl = (SSLSocket) listener.accept();
                        server.handshake(ssl, 5_000);
                        server.peerWire(ssl);
                        accepted.complete(ssl);
                    } catch (Exception e) {
                        accepted.completeExceptionally(e);
                    }
                });
                var connected = dialer.dial(new InetSocketAddress("127.0.0.1", port));
                var serverSsl = accepted.get(10, TimeUnit.SECONDS);
                // Non-reading peer + tiny buffers so the dialer's TLS write parks on the output lock / TCP window.
                try {
                    serverSsl.setReceiveBufferSize(4 * 1024);
                    connected.abort().setSendBufferSize(4 * 1024);
                    connected.socket().setSendBufferSize(4 * 1024);
                } catch (IOException ignored) {
                    // best-effort
                }
                var writer = Thread.ofPlatform().daemon().name("cp-blocked-tls-write").start(() -> {
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
                Files.writeString(deny, "serial:" + serverSerial.toString(16) + "\n");
                long before = dialer.policyDropped();
                var reload = CompletableFuture.runAsync(() -> {
                    try {
                        dialerTls.reloadTls();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
                try {
                    reload.get(3, TimeUnit.SECONDS);
                } catch (Exception timedOut) {
                    try {
                        connected.abort().setSoLinger(true, 0);
                        connected.abort().close();
                    } catch (Exception ignored) {
                    }
                    fail("reloadTls must not hang behind a blocked TLS write: " + timedOut);
                }
                assertTrue(dialer.policyDropped() > before, "dialer drops the blocked peer");
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
     * coordination).
     */
    @Test
    void peerWireRefusesWhenReloadBetweenValidateAndRegister() throws Exception {
        var ca = new CpTestCa(dir.resolve("ca-toc"));
        var deny = denylist("toc-deny");
        var acceptorTls = CpTls.peerContext(peer(ca, "a", deny));
        var clientCfg = ca.peer("b");
        var clientSerial = ca.lastSerial();
        var dialerTls = CpTls.peerContext(clientCfg);

        EphemeralPorts.withBindRetry("CpPeerTlsSessionTest.toc", () -> {
            int port = EphemeralPorts.probe();
            var server = sockets(acceptorTls, port, "a");
            try (var listener = server.listen(new InetSocketAddress("127.0.0.1", port))) {
                var outcome = new CompletableFuture<Exception>();
                CompletableFuture.runAsync(() -> {
                    try {
                        var ssl = (SSLSocket) listener.accept();
                        server.handshake(ssl, 5_000);
                        server.betweenValidateAndRegister = () -> {
                            try {
                                Files.writeString(deny, "serial:" + clientSerial.toString(16) + "\n");
                                acceptorTls.reloadTls();
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        };
                        try {
                            server.peerWire(ssl);
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
                Exception dialErr = null;
                try {
                    var client = (SSLSocket) dialerTls.context().getSocketFactory().createSocket();
                    client.setSSLParameters(dialerTls.parameters());
                    client.connect(new InetSocketAddress("127.0.0.1", port), CpPeerSockets.CONNECT_TIMEOUT_MILLIS);
                    client.startHandshake();
                    // Brief wait so acceptor can run peerWire under the hook.
                    Thread.sleep(200);
                    client.close();
                } catch (Exception e) {
                    dialErr = e;
                }
                var err = outcome.get(15, TimeUnit.SECONDS);
                assertNotNull(err, "peerWire must refuse when reloadTls revokes between validate and register"
                        + (dialErr == null ? "" : "; dial also failed: " + dialErr));
            }
            return null;
        });
    }

    /**
     * #334: closed CP peer sockets leave {@code live} when {@link CpPeerSockets#released} runs (as the transport does
     * on close). No TLS reload required. Mutating {@code released} to a no-op leaves liveSize at 1.
     */
    @Test
    void closedPeersReclaimedFromLiveWithoutReload() throws Exception {
        var ca = new CpTestCa(dir.resolve("ca-reclaim"));
        var deny = denylist("reclaim-deny");
        var acceptorTls = CpTls.peerContext(peer(ca, "a", deny));
        var dialerTls = CpTls.peerContext(ca.peer("b"));

        EphemeralPorts.withBindRetry("CpPeerTlsSessionTest.reclaim", () -> {
            int port = EphemeralPorts.probe();
            var server = sockets(acceptorTls, port, "a");
            var dialer = new CpPeerSockets(dialerTls, List.of(
                    new CpConfig.Peer(1, "a", new InetSocketAddress("127.0.0.1", port), false, CpTestCa.san("a")),
                    new CpConfig.Peer(2, "b", new InetSocketAddress("127.0.0.1", 1), false, CpTestCa.san("b"))), "b");
            try (var listener = server.listen(new InetSocketAddress("127.0.0.1", port))) {
                var accepted = new CompletableFuture<SSLSocket>();
                var acceptor = CompletableFuture.runAsync(() -> {
                    try {
                        var ssl = (SSLSocket) listener.accept();
                        server.handshake(ssl, 5_000);
                        assertEquals(2, server.peerWire(ssl));
                        accepted.complete(ssl);
                    } catch (Exception e) {
                        accepted.completeExceptionally(e);
                    }
                });
                var connected = dialer.dial(new InetSocketAddress("127.0.0.1", port));
                var live = accepted.get(10, TimeUnit.SECONDS);
                assertEquals(1, server.liveSize(), "accept registered");
                assertEquals(1, dialer.liveSize(), "dial registered");

                connected.socket().close();
                connected.abort().close();
                live.close();
                assertEquals(1, server.liveSize(), "closed accept still listed until released");
                assertEquals(1, dialer.liveSize(), "closed dial still listed until released");
                server.released(live);
                dialer.released(connected.socket());
                assertEquals(0, server.liveSize(), "acceptor reclaims without reload");
                assertEquals(0, dialer.liveSize(), "dialer reclaims without reload");
                server.released(live);
                dialer.released(connected.socket());
                assertEquals(0, server.liveSize());
                assertEquals(0, dialer.liveSize());
                acceptor.join();
            }
            return null;
        });
    }

    /**
     * #334 end-to-end through {@link sjws_nettask.cluster.TcpTransport}: closing the transports empties both live
     * registries without a reload.
     */
    @Test
    void transportCloseReclaimsLiveWithoutReload() throws Exception {
        var ca = new CpTestCa(dir.resolve("ca-reclaim-t"));
        var deny = denylist("reclaim-t-deny");
        var key = sjws_nettask.cluster.ClusterKey.of("cp-raft-key-for-tests-0123".getBytes(java.nio.charset.StandardCharsets.US_ASCII), null);
        var ports = EphemeralPorts.probeDistinct(2);
        var members = new java.util.ArrayList<sjws_nettask.cluster.Member>();
        for (int wire = 1; wire <= 2; wire++) {
            members.add(new sjws_nettask.cluster.Member(new sjws_nettask.cluster.NodeId(wire, "cp-" + wire),
                    new sjws_nettask.cluster.SiteId("cp"),
                    new InetSocketAddress("127.0.0.1", ports[wire - 1]), 1L, sjws_nettask.cluster.MemberState.ALIVE,
                    false));
        }
        var peers = List.of(
                new CpConfig.Peer(1, "a", new InetSocketAddress("127.0.0.1", ports[0]), false, CpTestCa.san("a")),
                new CpConfig.Peer(2, "b", new InetSocketAddress("127.0.0.1", ports[1]), false, CpTestCa.san("b")));
        var acceptorSockets = new CpPeerSockets(CpTls.peerContext(peer(ca, "a", deny)), peers, "a");
        var dialerSockets = new CpPeerSockets(CpTls.peerContext(ca.peer("b")), peers, "b");
        var group = new sjws_nettask.cluster.GroupId("cp-config");
        EphemeralPorts.withBindRetry("CpPeerTlsSessionTest.reclaimTransport", () -> {
            try (var a = sjws_nettask.cluster.TcpTransport.open(members.get(0).id(), members, key,
                    sjws_nettask.cluster.Clock.SYSTEM, 64, acceptorSockets);
                    var b = sjws_nettask.cluster.TcpTransport.open(members.get(1).id(), members, key,
                            sjws_nettask.cluster.Clock.SYSTEM, 64, dialerSockets)) {
                a.attach(members.get(0).id(), (from, frame) -> {
                });
                var rpc = b.attach(members.get(1).id(), (from, frame) -> {
                });
                rpc.send(members.get(0).id(), new sjws_nettask.cluster.SimTransport.Frame(group, 7, new byte[] {7}));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (System.nanoTime() < deadline
                        && (acceptorSockets.liveSize() < 1 || dialerSockets.liveSize() < 1)) {
                    a.deliver(50);
                    b.deliver(50);
                    Thread.sleep(10);
                }
                assertTrue(acceptorSockets.liveSize() >= 1, "acceptor registered");
                assertTrue(dialerSockets.liveSize() >= 1, "dialer registered");
            }
            assertEquals(0, acceptorSockets.liveSize(), "transport close reclaims acceptor live");
            assertEquals(0, dialerSockets.liveSize(), "transport close reclaims dialer live");
            return null;
        });
    }


    /**
     * #435 / #321 N1: {@code registerLive} fails closed once the TLS-epoch retry budget is spent, instead of
     * spinning forever under a reload storm.
     */
    @Test
    void registerLiveFailsClosedAfterEpochRetryBudget() throws Exception {
        var ca = new CpTestCa(dir.resolve("ca-budget"));
        var deny = denylist("budget-deny");
        var acceptorTls = CpTls.peerContext(peer(ca, "a", deny));
        var dialerTls = CpTls.peerContext(ca.peer("b"));

        EphemeralPorts.withBindRetry("CpPeerTlsSessionTest.budget", () -> {
            int port = EphemeralPorts.probe();
            var server = sockets(acceptorTls, port, "a");
            try (var listener = server.listen(new InetSocketAddress("127.0.0.1", port))) {
                var outcome = new CompletableFuture<Exception>();
                var bumps = new java.util.concurrent.atomic.AtomicInteger();
                CompletableFuture.runAsync(() -> {
                    try {
                        var ssl = (SSLSocket) listener.accept();
                        server.handshake(ssl, 5_000);
                        Runnable bump = new Runnable() {
                            @Override
                            public void run() {
                                bumps.incrementAndGet();
                                // Re-arm: registerLive clears the hook after each attempt.
                                server.betweenValidateAndRegister = this;
                                server.bumpTlsEpochForTest();
                            }
                        };
                        server.betweenValidateAndRegister = bump;
                        try {
                            server.peerWire(ssl);
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
                    var client = (SSLSocket) dialerTls.context().getSocketFactory().createSocket();
                    client.setSSLParameters(dialerTls.parameters());
                    client.connect(new InetSocketAddress("127.0.0.1", port), CpPeerSockets.CONNECT_TIMEOUT_MILLIS);
                    client.startHandshake();
                    Thread.sleep(200);
                    client.close();
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
                assertEquals(CpPeerSockets.REGISTER_LIVE_MAX_ATTEMPTS, bumps.get(),
                        "hook runs once per spent attempt before the budget trip");
            }
            return null;
        });
    }

    /**
     * #435 / #321 N2: if {@code dropPeersFailingPolicy} aborts a copied Live while a concurrent registerLive
     * replace put a newer Live for the same socket, removal is by key so the closed socket cannot stay in
     * {@code live}. Deterministic via {@code betweenDropFailAndAbort}.
     */
    @Test
    void dropRemovesClosedSocketWhenLiveReplacedConcurrently() throws Exception {
        var ca = new CpTestCa(dir.resolve("ca-race"));
        var deny = denylist("race-deny");
        var acceptorTls = CpTls.peerContext(peer(ca, "a", deny));
        var clientCfg = ca.peer("b");
        var clientSerial = ca.lastSerial();
        var dialerTls = CpTls.peerContext(clientCfg);

        EphemeralPorts.withBindRetry("CpPeerTlsSessionTest.dropRace", () -> {
            int port = EphemeralPorts.probe();
            var server = sockets(acceptorTls, port, "a");
            try (var listener = server.listen(new InetSocketAddress("127.0.0.1", port))) {
                var accepted = new CompletableFuture<SSLSocket>();
                CompletableFuture.runAsync(() -> {
                    try {
                        var ssl = (SSLSocket) listener.accept();
                        server.handshake(ssl, 5_000);
                        assertEquals(2, server.peerWire(ssl));
                        accepted.complete(ssl);
                    } catch (Exception e) {
                        accepted.completeExceptionally(e);
                    }
                });
                var client = (SSLSocket) dialerTls.context().getSocketFactory().createSocket();
                client.setSSLParameters(dialerTls.parameters());
                client.connect(new InetSocketAddress("127.0.0.1", port), CpPeerSockets.CONNECT_TIMEOUT_MILLIS);
                client.startHandshake();
                var live = accepted.get(10, TimeUnit.SECONDS);
                assertEquals(1, server.liveSize());
                var replaced = new java.util.concurrent.atomic.AtomicBoolean();
                server.betweenDropFailAndAbort = () -> {
                    try {
                        // Simulate registerLive putting a fresh Live for the same SSLSocket.
                        server.replaceLiveAbortForTest(live, new java.net.Socket());
                        replaced.set(true);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                };
                Files.writeString(deny, "serial:" + clientSerial.toString(16) + "\n");
                acceptorTls.reloadTls();
                assertTrue(replaced.get(), "drop path must hit the replace hook");
                assertTrue(live.isClosed(), "stale abort still closes the SSLSocket");
                assertEquals(0, server.liveSize(), "closed socket must leave live even after Live replace");
                client.close();
            }
            return null;
        });
    }

    /**
     * #435: already-closed sockets are swept from {@code live} by key, so a concurrent Live replace cannot
     * retain them.
     */
    @Test
    void dropSweepsClosedSocketByKeyDespiteLiveReplace() throws Exception {
        var ca = new CpTestCa(dir.resolve("ca-closed-key"));
        var deny = denylist("closed-key-deny");
        var acceptorTls = CpTls.peerContext(peer(ca, "a", deny));
        var dialerTls = CpTls.peerContext(ca.peer("b"));

        EphemeralPorts.withBindRetry("CpPeerTlsSessionTest.closedKey", () -> {
            int port = EphemeralPorts.probe();
            var server = sockets(acceptorTls, port, "a");
            try (var listener = server.listen(new InetSocketAddress("127.0.0.1", port))) {
                var accepted = new CompletableFuture<SSLSocket>();
                CompletableFuture.runAsync(() -> {
                    try {
                        var ssl = (SSLSocket) listener.accept();
                        server.handshake(ssl, 5_000);
                        assertEquals(2, server.peerWire(ssl));
                        accepted.complete(ssl);
                    } catch (Exception e) {
                        accepted.completeExceptionally(e);
                    }
                });
                var client = (SSLSocket) dialerTls.context().getSocketFactory().createSocket();
                client.setSSLParameters(dialerTls.parameters());
                client.connect(new InetSocketAddress("127.0.0.1", port), CpPeerSockets.CONNECT_TIMEOUT_MILLIS);
                client.startHandshake();
                var live = accepted.get(10, TimeUnit.SECONDS);
                live.close();
                client.close();
                assertEquals(1, server.liveSize());
                var replaced = new java.util.concurrent.atomic.AtomicBoolean();
                server.betweenClosedSweepAndRemove = () -> {
                    try {
                        server.replaceLiveAbortForTest(live, new java.net.Socket());
                        replaced.set(true);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                };
                server.dropPeersFailingPolicy();
                assertTrue(replaced.get(), "closed sweep must hit the replace hook");
                assertEquals(0, server.liveSize(), "closed-socket sweep removes by key despite Live replace");
            }
            return null;
        });
    }


}
