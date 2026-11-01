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
import java.util.concurrent.ConcurrentHashMap;
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
 * After every handshake (including a resumed one) the peer is re-checked against the current trust, revocation and
 * pin policy before any application byte is read or written. A successful {@link CpServerTls#reloadTls()} re-checks
 * established peer connections and closes any that no longer pass. Refusals are counted in {@link #identityRefused}; TLS-epoch retry-budget exhaustion in {@link #registerBudgetExhausted}
 * and logged at most once a minute (subject and serial only). A TLS reload whose new certificate does not carry this
 * voter's own pinned SAN is refused (the current material stays), counted in {@link #reloadRefused} and logged.
 * {@code endpointIdentification} stays unset: the pinned SAN, not the host name, is the identity. Closed sockets are
 * removed from {@link #live} when the transport calls {@link #released} (normal close, admit refusal after
 * {@link #peerWire}, or {@link TcpTransport#close}), so a long-running node without reloads does not retain every
 * historical peer socket (#334). {@link #registerLive} retries are bounded (#435); {@link #dropPeersFailingPolicy} removes closed sockets by key so a concurrent Live replace cannot retain them (#435).
 */
final class CpPeerSockets implements TcpTransport.SocketFactory {

    /** Listener name of the voter links; its {@link CpServerTls} also dials (client side of mTLS). */
    static final String LISTENER = "cp-peer";
    static final int CONNECT_TIMEOUT_MILLIS = 1_000;
    static final int HANDSHAKE_TIMEOUT_MILLIS = TcpTransport.HANDSHAKE_TIMEOUT_MILLIS;
    static final long LOG_EVERY_NANOS = 60_000_000_000L;
    /**
     * Upper bound on {@link #registerLive} TLS-epoch retries (#435 / #321 N1). A reload storm that keeps
     * bumping the epoch cannot spin forever; registration fails closed once the budget is spent.
     */
    static final int REGISTER_LIVE_MAX_ATTEMPTS = 32;
    private static final System.Logger LOG = System.getLogger(CpPeerSockets.class.getName());

    /** Pinned peer plus the abort handle that unblocks a stalled TLS write without waiting on SSLSocket.close. */
    private record Live(CpConfig.Peer expected, Socket abort) {
    }

    private final CpServerTls tls;
    private volatile List<CpConfig.Peer> peers;
    private volatile CpConfig.Peer self;
    private final AtomicLong identityRefused = new AtomicLong();
    private final AtomicLong reloadRefused = new AtomicLong();
    private final AtomicLong policyDropped = new AtomicLong();
    /** Times {@link #registerLive} failed closed after spending {@link #REGISTER_LIVE_MAX_ATTEMPTS} (#442 N1). */
    private final AtomicLong registerBudgetExhausted = new AtomicLong();
    private final AtomicLong loggedNanos = new AtomicLong(System.nanoTime() - LOG_EVERY_NANOS);
    /** Bumped at the start of every {@link #dropPeersFailingPolicy} so registration cannot escape a concurrent reload. */
    private final AtomicLong tlsEpoch = new AtomicLong();
    /** Open peer sockets still subject to policy after reload; value holds the pin and the abort handle. */
    private final ConcurrentHashMap<SSLSocket, Live> live = new ConcurrentHashMap<>();
    /**
     * Test hook: runs once after a successful policy check and before {@code live.put}, so a test can fire
     * {@code reloadTls} in the handshake/register window.
     */
    volatile Runnable betweenValidateAndRegister = () -> {
    };
    /**
     * Test hook: runs after a policy-fail decision for a copied {@link Live} and after confirming that entry is
     * still mapped, immediately before abort/close (#435). Tests use it to replace the Live value and prove the
     * drop path still removes the closed socket.
     */
    volatile Runnable betweenDropFailAndAbort = () -> {
    };
    /**
     * Test hook: runs when a copied entry's socket is already closed, before the by-key remove (#435).
     */
    volatile Runnable betweenClosedSweepAndRemove = () -> {
    };

    /**
     * @param peers every {@code cp.peers} entry, this voter included; each must pin a {@code san}
     * @param selfId this voter's {@code cp.peers} id
     * @throws IllegalArgumentException an entry without a pinned SAN, or {@code selfId} not listed
     */
    CpPeerSockets(CpServerTls tls, List<CpConfig.Peer> peers, String selfId) {
        this.tls = Objects.requireNonNull(tls, "tls");
        setPeers(peers, selfId);
        tls.afterReload(this::dropPeersFailingPolicy);
    }

    private void setPeers(List<CpConfig.Peer> peers, String selfId) {
        var copy = List.copyOf(peers);
        CpConfig.Peer found = null;
        for (var peer : copy) {
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
        this.peers = copy;
        this.self = found;
    }

    /**
     * Replaces the pin table (same self id). Established connections that no longer match are closed. Used when the
     * operator changes {@code cp.peers} bindings; tests use it to exercise post-reload pin checks.
     */
    void replacePins(List<CpConfig.Peer> peers) {
        setPeers(peers, self.id());
        dropPeersFailingPolicy();
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

    /** Established connections closed because they failed policy after a TLS reload or pin change. */
    long policyDropped() {
        return policyDropped.get();
    }

    /** Registrations that failed closed after the TLS-epoch retry budget was spent (#442 / #435). */
    long registerBudgetExhausted() {
        return registerBudgetExhausted.get();
    }

    /** Open (or not-yet-reclaimed) entries in the live-peer registry; for tests and ops. */
    int liveSize() {
        return live.size();
    }

    /**
     * The transport is done with this socket: drop it from {@link #live} so a closed peer is not retained until a
     * reload (#334). Idempotent.
     */
    @Override
    public void released(Socket socket) {
        if (socket instanceof SSLSocket ssl) {
            live.remove(ssl);
        }
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
        return dial(to).socket();
    }

    /**
     * Dial with TLS layered over a plain abort handle: an {@code SSLSocket}'s own {@code close()} waits for the TLS
     * output lock a blocked writer holds; the plain socket's {@code close()} never does.
     */
    @Override
    public TcpTransport.SocketFactory.Connected dial(InetSocketAddress to) throws IOException {
        var expected = at(to);
        if (expected == null || expected.equals(self)) {
            throw new IOException("cp-peer: no pinned voter at " + to);
        }
        var plain = new Socket();
        SSLSocket socket;
        try {
            plain.setTcpNoDelay(true);
            plain.connect(to, CONNECT_TIMEOUT_MILLIS);
            socket = (SSLSocket) tls.context().getSocketFactory().createSocket(plain, to.getHostString(), to.getPort(),
                    true);
        } catch (IOException | RuntimeException e) {
            plain.close();
            throw e;
        }
        try {
            socket.setSSLParameters(tls.parameters());
            socket.setSoTimeout(HANDSHAKE_TIMEOUT_MILLIS);
            socket.startHandshake();
            socket.setSoTimeout(0);
            registerLive(socket, expected, plain, false);
        } catch (IOException | RuntimeException e) {
            abortClose(plain);
            closeQuietly(socket);
            live.remove(socket);
            throw e;
        }
        return new TcpTransport.SocketFactory.Connected(socket, plain);
    }

    /**
     * The accepted side's mTLS handshake under one total deadline: a watchdog closes the socket when it passes, so
     * a client trickling handshake bytes cannot stretch it with per-read timeouts. The transport counts the
     * connection as an inbound slot only after this returns. Policy (trust, revocation, pin) is enforced before
     * return, so no application frame is read on a refused peer.
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
                closeQuietly(ssl);
            }
        });
        try {
            ssl.setSoTimeout(totalMillis);
            ssl.startHandshake();
            if (ssl.isClosed()) {
                throw new IOException("cp-peer handshake missed its " + totalMillis + " ms deadline");
            }
            // Trust + revocation before any application byte; pin binding is peerWire (still before read).
            try {
                tls.revalidatePeer(ssl, true);
            } catch (IOException e) {
                warnPolicy(ssl, e.getMessage());
                closeQuietly(ssl);
                throw e;
            }
        } catch (IOException | RuntimeException e) {
            closeQuietly(ssl);
            live.remove(ssl);
            throw e;
        } finally {
            done.set(true);
            watchdog.interrupt();
        }
    }

    /** The wire id of the one pinned voter (not this one) the verified client certificate names; else refused. */
    @Override
    public int peerWire(Socket accepted) {
        var ssl = (SSLSocket) accepted;
        try {
            // Accepted sockets have no separate plain abort handle; abort is the SSLSocket itself.
            return registerLive(ssl, null, ssl, true).wire();
        } catch (IOException e) {
            closeQuietly(ssl);
            live.remove(ssl);
            throw new IllegalStateException("cp-peer certificate names no other pinned voter", e);
        }
    }

    /**
     * Revalidate and pin under the current TLS epoch, then put into {@link #live}. If a reload bumps the epoch
     * between the check and the put, retry so the connection cannot escape both the new-policy check and the reload
     * sweep.
     */
    private CpConfig.Peer registerLive(SSLSocket socket, CpConfig.Peer expected, Socket abort, boolean peerIsClient)
            throws IOException {
        CpConfig.Peer named;
        long epoch;
        int attempts = 0;
        do {
            if (++attempts > REGISTER_LIVE_MAX_ATTEMPTS) {
                long n = registerBudgetExhausted.incrementAndGet();
                long now = System.nanoTime();
                long last = loggedNanos.get();
                if (now - last >= LOG_EVERY_NANOS && loggedNanos.compareAndSet(last, now)) {
                    // Fixed text only: no peer / SAN / exception message (#442 N1).
                    LOG.log(System.Logger.Level.WARNING,
                            "cp-peer: registerLive TLS-epoch retry budget exhausted ({0} attempts);"
                                    + " registration failed closed ({1} so far)",
                            REGISTER_LIVE_MAX_ATTEMPTS, n);
                }
                throw new IOException("cp-peer registration exceeded TLS epoch retry budget ("
                        + REGISTER_LIVE_MAX_ATTEMPTS + " attempts)");
            }
            epoch = tlsEpoch.get();
            named = enforcePolicy(socket, peerIsClient, expected);
            var mid = betweenValidateAndRegister;
            betweenValidateAndRegister = () -> {
            };
            mid.run();
            live.put(socket, new Live(named, abort));
        } while (tlsEpoch.get() != epoch);
        return named;
    }

    /**
     * Trust, revocation and pin binding against current policy. Closes nothing itself; callers close on failure.
     * {@code expected} is required on the dialer; on the acceptor it is null and the peer must name exactly one
     * other pinned voter (returned).
     */
    private CpConfig.Peer enforcePolicy(SSLSocket socket, boolean peerIsClient, CpConfig.Peer expected)
            throws IOException {
        try {
            tls.revalidatePeer(socket, peerIsClient);
        } catch (IOException e) {
            warnPolicy(socket, e.getMessage());
            throw e;
        }
        var named = identify(socket);
        if (expected != null) {
            if (named == null || !named.equals(expected)) {
                refused("the peer did not present the pinned san of " + expected.id());
                throw new IOException("cp-peer did not present the pinned san of " + expected.id());
            }
            return expected;
        }
        if (named == null || named.equals(self)) {
            refused("an accepted connection's certificate names no other pinned voter");
            throw new IOException("cp-peer certificate names no other pinned voter");
        }
        return named;
    }

    /** After reload or pin change: close every live peer that fails current policy. */
    void dropPeersFailingPolicy() {
        tlsEpoch.incrementAndGet();
        for (var entry : List.copyOf(live.entrySet())) {
            var socket = entry.getKey();
            var peer = entry.getValue();
            if (socket.isClosed()) {
                var closedMid = betweenClosedSweepAndRemove;
                betweenClosedSweepAndRemove = () -> {
                };
                // Test hook may replace Live here (concurrent registerLive) before remove.
                closedMid.run();
                // By key: a concurrent registerLive replace must not leave a closed socket in live (#435 / #321 N2).
                live.remove(socket);
                continue;
            }
            // Accepted sockets are server-mode; dialled sockets are client-mode.
            boolean peerIsClient = !socket.getUseClientMode();
            try {
                // Silent here: we WARN once below with policyDropped.
                tls.revalidatePeer(socket, peerIsClient);
                var named = identify(socket);
                if (named == null || !named.equals(peer.expected())) {
                    throw new IOException("cp-peer no longer matches pinned san of " + peer.expected().id());
                }
            } catch (IOException | RuntimeException failed) {
                // Stale snapshot: live value no longer matches this copy (concurrent registerLive replace).
                // Skip abort; the replacement is either already validated under the current epoch, or will
                // fail closed on the registerLive retry / caller cleanup (#442 N2).
                if (live.get(socket) != peer) {
                    continue;
                }
                var mid = betweenDropFailAndAbort;
                betweenDropFailAndAbort = () -> {
                };
                // Test hook may replace Live here (simulating concurrent registerLive) before abort.
                mid.run();
                policyDropped.incrementAndGet();
                warnPolicy(socket, String.valueOf(failed.getMessage()));
                // Abort handle first: SSLSocket.close waits on the TLS write lock a blocked writer holds.
                abortClose(peer.abort());
                if (peer.abort() != socket) {
                    closeQuietly(socket);
                }
                // By key after abort: remove-by-value can miss a concurrent replace and leave a closed socket (#435).
                live.remove(socket);
            }
        }
    }


    /**
     * Test-only (#435): replace the Live abort handle for {@code socket} while keeping the same expected pin.
     * Simulates a concurrent {@code registerLive} put of a new Live for the same SSLSocket. Returns the previous
     * abort handle.
     */
    Socket replaceLiveAbortForTest(SSLSocket socket, Socket newAbort) {
        var cur = live.get(socket);
        if (cur == null) {
            throw new IllegalStateException("no live entry for " + socket);
        }
        live.put(socket, new Live(cur.expected(), newAbort));
        return cur.abort();
    }

    /** Test-only (#435): bump the TLS epoch without sweeping, so registerLive retries. */
    void bumpTlsEpochForTest() {
        tlsEpoch.incrementAndGet();
    }

    private void warnPolicy(SSLSocket socket, String why) {
        long n = identityRefused.incrementAndGet();
        String who = "unknown";
        try {
            var certs = socket.getSession().getPeerCertificates();
            if (certs.length > 0 && certs[0] instanceof X509Certificate leaf) {
                who = CpServerTls.peerLogId(leaf);
            }
        } catch (SSLPeerUnverifiedException | RuntimeException ignored) {
            // keep unknown
        }
        long now = System.nanoTime();
        long last = loggedNanos.get();
        if (now - last >= LOG_EVERY_NANOS && loggedNanos.compareAndSet(last, now)) {
            LOG.log(System.Logger.Level.WARNING,
                    "cp-peer: policy refused {0} ({1}); connection closed ({2} so far)", who, why, n);
        }
    }

    private void refused(String why) {
        long n = identityRefused.incrementAndGet();
        long now = System.nanoTime();
        long last = loggedNanos.get();
        if (now - last >= LOG_EVERY_NANOS && loggedNanos.compareAndSet(last, now)) {
            LOG.log(System.Logger.Level.WARNING, "cp-peer: {0}; connection closed ({1} so far)", why, n);
        }
    }

    /** Closes the abort handle with linger 0 (immediate reset), never waiting behind a blocked TLS write. */
    private static void abortClose(Socket abort) {
        try {
            abort.setSoLinger(true, 0);
        } catch (IOException | RuntimeException ignored) {
            // closing
        }
        closeQuietly(abort);
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // closing
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
