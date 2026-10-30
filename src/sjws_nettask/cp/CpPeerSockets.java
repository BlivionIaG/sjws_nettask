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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import sjws_nettask.cluster.TcpTransport;

/**
 * The CONTROL voters' {@link TcpTransport.SocketFactory}: every peer socket comes from Security's
 * {@link CpServerTls#context()} with {@link CpServerTls#parameters()} applied (TLS 1.3, client certificate
 * required), so the listener and the dialer both present the node certificate and trust the peer CA. A reload
 * swaps the material inside the same context, so new connections pick it up. The accepted side's handshake runs
 * explicitly in {@link #handshake}, under a total deadline, before the connection takes an inbound slot; frames
 * stay HMAC-authenticated on top with the CP-only Raft key.
 *
 * <p><b>Certificates are bound to {@code cp.peers} ids (#175).</b> Every {@code cp.peers} entry pins a
 * typed {@code san} ({@code uri:<value>} or {@code dns:<value>}); only URI and DNS subject alternative names count,
 * each matched exactly and only against pins of its own type (no CN fallback), and a certificate
 * that names more than one pinned voter identifies none.
 * <ul>
 * <li>Dialing: after the handshake, the server certificate must name exactly the voter pinned at the dialled
 * address, or the socket is closed and {@link #connect} throws before any frame is written.</li>
 * <li>Accepting: {@link #peerWire} maps the client certificate to its voter's wire id, so the transport drops any
 * frame on that connection that carries another sender wire ({@code TcpTransport.Reason.ORIGIN}).</li>
 * </ul>
 * Refusals are counted in {@link #identityRefused} and logged at most once a minute. A TLS reload whose new
 * certificate does not carry this voter's own pinned SAN is refused (the current material stays), counted in
 * {@link #reloadRefused} and logged. {@code endpointIdentification}
 * stays unset: the pinned SAN, not the host name, is the identity.
 */
final class CpPeerSockets implements TcpTransport.SocketFactory {

    /** Listener name of the voter links; its {@link CpServerTls} also dials (client side of mTLS). */
    static final String LISTENER = "cp-peer";
    static final int CONNECT_TIMEOUT_MILLIS = 1_000;
    static final int HANDSHAKE_TIMEOUT_MILLIS = TcpTransport.HANDSHAKE_TIMEOUT_MILLIS;
    static final long LOG_EVERY_NANOS = 60_000_000_000L;
    private static final System.Logger LOG = System.getLogger(CpPeerSockets.class.getName());

    private final CpServerTls tls;
    private final List<CpConfig.Peer> peers;
    private final CpConfig.Peer self;
    private final AtomicLong identityRefused = new AtomicLong();
    private final AtomicLong reloadRefused = new AtomicLong();
    private final AtomicLong loggedNanos = new AtomicLong(System.nanoTime() - LOG_EVERY_NANOS);

    /**
     * @param peers every {@code cp.peers} entry, this voter included; each must pin a {@code san}
     * @param selfId this voter's {@code cp.peers} id
     * @throws IllegalArgumentException an entry without a pinned SAN, or {@code selfId} not listed
     */
    CpPeerSockets(CpServerTls tls, List<CpConfig.Peer> peers, String selfId) {
        this.tls = Objects.requireNonNull(tls, "tls");
        this.peers = List.copyOf(peers);
        CpConfig.Peer found = null;
        for (var peer : this.peers) {
            if (peer.san() == null) {
                throw new IllegalArgumentException("cp.peers entry " + peer.id() + " has no pinned san");
            }
            if (peer.id().equals(selfId)) {
                found = peer;
            }
        }
        if (found == null) {
            throw new IllegalArgumentException("cp.peers does not list " + selfId);
        }
        this.self = found;
    }

    /**
     * Startup check: this voter's own {@code cp-peer} certificate must carry its own pinned SAN and name no other
     * pinned voter, or the process refuses to start.
     */
    void checkOwnCertificate() throws IOException {
        var problem = ownCertificateProblem(tls.certificate());
        if (problem != null) {
            throw new IOException(problem + "; refusing to start");
        }
        tls.checkLeafOnReload(this::reloadedCertificateProblem);
    }

    /** Null when {@code leaf} names exactly this voter's pinned SAN (and no other voter's); else why not. */
    String ownCertificateProblem(java.security.cert.X509Certificate leaf) {
        var match = leaf == null ? null : identify(leaf);
        if (match == null || !match.equals(self)) {
            return "cp.tls.peerCert does not carry exactly this voter's pinned san " + self.san() + " (cp.peers id "
                    + self.id() + "; URI or DNS SAN, no other voter's)";
        }
        return null;
    }

    /** The TLS reload check: a refused reload keeps the old material, is counted, and is logged. */
    private String reloadedCertificateProblem(java.security.cert.X509Certificate leaf) {
        var problem = ownCertificateProblem(leaf);
        if (problem != null) {
            reloadRefused.incrementAndGet();
            LOG.log(System.Logger.Level.WARNING, "cp-peer: TLS reload refused, keeping the current material: {0}",
                    problem);
        }
        return problem;
    }

    /** TLS reloads refused because the new certificate did not carry this voter's own pinned SAN. */
    long reloadRefused() {
        return reloadRefused.get();
    }

    /** Connections closed because the verified certificate did not name the expected pinned voter. */
    long identityRefused() {
        return identityRefused.get();
    }

    @Override
    public ServerSocket listen(InetSocketAddress bind) throws IOException {
        var server = (SSLServerSocket) tls.context().getServerSocketFactory().createServerSocket();
        try {
            server.setSSLParameters(tls.parameters());
            server.setReuseAddress(true);
            server.bind(bind);
        } catch (IOException | RuntimeException e) {
            server.close();
            throw e;
        }
        return server;
    }

    @Override
    public Socket connect(InetSocketAddress to) throws IOException {
        var expected = at(to);
        if (expected == null || expected.equals(self)) {
            throw new IOException("cp-peer: no pinned voter at " + to);
        }
        var socket = (SSLSocket) tls.context().getSocketFactory().createSocket();
        try {
            socket.setSSLParameters(tls.parameters());
            socket.setTcpNoDelay(true);
            socket.connect(to, CONNECT_TIMEOUT_MILLIS);
            socket.setSoTimeout(HANDSHAKE_TIMEOUT_MILLIS);
            socket.startHandshake();
            socket.setSoTimeout(0);
            tls.revalidatePeer(socket, false);
            var named = identify(socket);
            if (named == null || !named.equals(expected)) {
                refused("the voter at " + to + " did not present the pinned san of " + expected.id());
                throw new IOException("cp-peer at " + to + " did not present the pinned san of " + expected.id());
            }
        } catch (IOException | RuntimeException e) {
            socket.close();
            throw e;
        }
        return socket;
    }

    /**
     * The accepted side's mTLS handshake under one total deadline: a watchdog closes the socket when it passes, so
     * a client trickling handshake bytes cannot stretch it with per-read timeouts. The transport counts the
     * connection as an inbound slot only after this returns.
     */
    @Override
    public void handshake(Socket accepted, int totalMillis) throws IOException {
        var ssl = (SSLSocket) accepted;
        var done = new java.util.concurrent.atomic.AtomicBoolean();
        var watchdog = Thread.ofVirtual().name("cp-peer-handshake-deadline").start(() -> {
            try {
                Thread.sleep(totalMillis);
            } catch (InterruptedException finished) {
                return;
            }
            if (!done.get()) {
                try {
                    ssl.close();
                } catch (IOException ignored) {
                    // Closing a stalled handshake; nothing to report.
                }
            }
        });
        try {
            ssl.setSoTimeout(totalMillis);
            ssl.startHandshake();
            if (!ssl.isClosed()) {
                tls.revalidatePeer(ssl, true);
            }
        } finally {
            done.set(true);
            watchdog.interrupt();
        }
        if (ssl.isClosed()) {
            throw new IOException("cp-peer handshake missed its " + totalMillis + " ms deadline");
        }
    }

    /** The wire id of the one pinned voter (not this one) the verified client certificate names; else refused. */
    @Override
    public int peerWire(Socket accepted) {
        var named = identify((SSLSocket) accepted);
        if (named == null || named.equals(self)) {
            refused("an accepted connection's certificate names no other pinned voter");
            throw new IllegalStateException("cp-peer certificate names no other pinned voter");
        }
        return named.wire();
    }

    private void refused(String why) {
        long n = identityRefused.incrementAndGet();
        long now = System.nanoTime();
        long last = loggedNanos.get();
        if (now - last >= LOG_EVERY_NANOS && loggedNanos.compareAndSet(last, now)) {
            LOG.log(System.Logger.Level.WARNING, "cp-peer: {0}; connection closed ({1} so far)", why, n);
        }
    }

    /** The pinned voter at {@code to} (same host string and port), or null. */
    private CpConfig.Peer at(InetSocketAddress to) {
        for (var peer : peers) {
            var a = peer.address();
            if (a.getPort() == to.getPort() && a.getHostString().equals(to.getHostString())) {
                return peer;
            }
        }
        return null;
    }

    private CpConfig.Peer identify(SSLSocket socket) {
        java.security.cert.Certificate[] chain;
        try {
            chain = socket.getSession().getPeerCertificates();
        } catch (SSLPeerUnverifiedException e) {
            return null;
        }
        return chain.length > 0 && chain[0] instanceof X509Certificate leaf ? identify(leaf) : null;
    }

    /** The single pinned voter {@code leaf} names by URI or DNS SAN, or null (none, or more than one). */
    CpConfig.Peer identify(X509Certificate leaf) {
        CpConfig.Peer found = null;
        for (var san : sans(leaf)) {
            for (var peer : peers) {
                if (peer.san().equals(san)) {
                    if (found != null && !found.equals(peer)) {
                        return null; // names two pinned voters: identifies neither
                    }
                    found = peer;
                }
            }
        }
        return found;
    }

    /**
     * URI (6) and DNS (2) subject alternative names, typed as {@code uri:<value>} and {@code dns:<value>} like the
     * {@code cp.peers} pins, so a pin only ever matches a SAN of its own type; never the subject CN.
     */
    static List<String> sans(X509Certificate cert) {
        var out = new ArrayList<String>();
        try {
            var all = cert.getSubjectAlternativeNames();
            if (all != null) {
                for (var san : all) {
                    if (san.size() >= 2 && san.get(1) instanceof String value && san.get(0) instanceof Integer type
                            && (type == 6 || type == 2)) {
                        out.add((type == 6 ? "uri:" : "dns:") + value);
                    }
                }
            }
        } catch (CertificateParsingException e) {
            return List.of();
        }
        return out;
    }
}
