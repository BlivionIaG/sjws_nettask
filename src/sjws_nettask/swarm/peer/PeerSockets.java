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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import sjws_nettask.cluster.TcpTransport;
import sjws_nettask.cp.CpServerTls;

/**
 * mTLS for the swarm peer links (#236 r4 §9.5): TLS 1.3, client certificate required, trusting only the swarm-peer
 * CA ({@link CpServerTls}: revocation list honoured, {@link CpServerTls#reloadTls()} swaps material in place). On
 * top of chain validation, identity is pinned per node:
 * <ul>
 * <li>accepted side: the client certificate must carry exactly one SAN that {@link PeerConfig} pins, to a node other
 * than this one; {@link #peerWire} returns that node's wire, and the transport refuses any frame on the connection
 * that claims another wire ({@code TcpTransport.Reason.ORIGIN}).</li>
 * <li>dialing side: the server certificate must carry the SAN pinned for the node at the dialled address, or the
 * connection is closed before any frame is written.</li>
 * </ul>
 * {@link PeerConfig} is fixed at construction: the pin table is never updated in place (the field is volatile only for
 * safe publication); a swarm pin change needs a reconfig that builds a new {@code PeerSockets}. After every handshake
 * the peer is re-checked against the current trust, revocation and pin policy before any application byte is read or
 * written. A successful {@link CpServerTls#reloadTls()} re-checks established peer connections and closes any that no
 * longer pass. Only URI and DNS SANs count (no CN fallback). Closed sockets are removed from the live registry when
 * the transport calls {@link #released} (normal close, admit refusal after {@link #peerWire}, or transport close), so
 * a long-running node without reloads does not retain every historical peer socket (#334). {@code registerLive} retries are bounded (#435); drop-path removal of closed sockets is by key so a concurrent Live replace cannot retain them (#435).
 */
final class PeerSockets implements TcpTransport.SocketFactory {

    static final String LISTENER = "swarm-peer";
    static final int CONNECT_TIMEOUT_MILLIS = 1_000;
    private static final System.Logger LOG = System.getLogger(PeerSockets.class.getName());
    private static final long LOG_EVERY_NANOS = 60_000_000_000L;
    /**
     * Upper bound on {@link #registerLive} TLS-epoch retries (#435 / #321 N1). A reload storm that keeps
     * bumping the epoch cannot spin forever; registration fails closed once the budget is spent.
     */
    static final int REGISTER_LIVE_MAX_ATTEMPTS = 32;

    /** Pinned node plus the abort handle that unblocks a stalled TLS write without waiting on SSLSocket.close. */
    private record Live(PeerConfig.Node expected, Socket abort) {
    }

    private final CpServerTls tls;
    private volatile PeerConfig config;
    private final AtomicLong identityRefused = new AtomicLong();
    private final AtomicLong policyDropped = new AtomicLong();
    /** Times {@link #registerLive} failed closed after spending {@link #REGISTER_LIVE_MAX_ATTEMPTS} (#442 N1). */
    private final AtomicLong registerBudgetExhausted = new AtomicLong();
    private final AtomicLong loggedNanos = new AtomicLong(System.nanoTime() - LOG_EVERY_NANOS);
    /** Bumped at the start of every {@link #dropPeersFailingPolicy} so registration cannot escape a concurrent reload. */
    private final AtomicLong tlsEpoch = new AtomicLong();
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

    PeerSockets(CpServerTls tls, PeerConfig config) {
        this.tls = Objects.requireNonNull(tls, "tls");
        this.config = Objects.requireNonNull(config, "config");
        tls.afterReload(this::dropPeersFailingPolicy);
    }

    /** Connections closed because the verified certificate did not carry the pinned identity. */
    long identityRefused() {
        return identityRefused.get();
    }

    /** Established connections closed because they failed policy after a TLS reload. */
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
     * The transport is done with this socket: drop it from the live registry so a closed peer is not retained until a
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

    @Override
    public TcpTransport.SocketFactory.Connected dial(InetSocketAddress to) throws IOException {
        PeerConfig.Node expected = null;
        for (var n : config.nodes()) {
            if (n.address().equals(to)) {
                expected = n;
            }
        }
        if (expected == null || expected.equals(config.self())) {
            throw new IOException("no pinned swarm peer at " + to);
        }
        // TLS layered over a plain socket that dial() returns as the abort handle: an SSLSocket's own close()
        // waits for the TLS output lock a blocked writer holds, the plain socket's close() never does.
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
            socket.setSoTimeout(TcpTransport.HANDSHAKE_TIMEOUT_MILLIS);
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

    /** The accepted side's handshake under one total deadline (a watchdog closes a trickling client). */
    @Override
    public void handshake(Socket accepted, int totalMillis) throws IOException {
        var ssl = (SSLSocket) accepted;
        var done = new AtomicBoolean();
        var watchdog = Thread.ofVirtual().name("swarm-peer-handshake-deadline").start(() -> {
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
                throw new IOException("swarm-peer handshake missed its " + totalMillis + " ms deadline");
            }
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

    @Override
    public int peerWire(Socket accepted) {
        var ssl = (SSLSocket) accepted;
        try {
            // Accepted sockets have no separate plain abort handle; abort is the SSLSocket itself.
            return registerLive(ssl, null, ssl, true).wire();
        } catch (IOException e) {
            closeQuietly(ssl);
            live.remove(ssl);
            throw new IllegalStateException("swarm peer certificate carries no pinned peer identity", e);
        }
    }

    /**
     * Revalidate and pin under the current TLS epoch, then put into {@link #live}. If a reload bumps the epoch
     * between the check and the put, retry so the connection cannot escape both the new-policy check and the reload
     * sweep.
     */
    private PeerConfig.Node registerLive(SSLSocket socket, PeerConfig.Node expected, Socket abort,
            boolean peerIsClient) throws IOException {
        PeerConfig.Node named;
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
                            "swarm-peer: registerLive TLS-epoch retry budget exhausted ({0} attempts);"
                                    + " registration failed closed ({1} so far)",
                            REGISTER_LIVE_MAX_ATTEMPTS, n);
                }
                throw new IOException("swarm peer registration exceeded TLS epoch retry budget ("
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

    private PeerConfig.Node enforcePolicy(SSLSocket socket, boolean peerIsClient, PeerConfig.Node expected)
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
                identityRefused.incrementAndGet();
                throw new IOException("swarm peer did not present the pinned identity of " + expected.name());
            }
            return expected;
        }
        if (named == null || named.equals(config.self())) {
            identityRefused.incrementAndGet();
            throw new IOException("swarm peer certificate carries no pinned peer identity");
        }
        return named;
    }

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
            boolean peerIsClient = !socket.getUseClientMode();
            try {
                tls.revalidatePeer(socket, peerIsClient);
                var named = identify(socket);
                if (named == null || !named.equals(peer.expected())) {
                    throw new IOException("swarm peer no longer matches pinned identity of " + peer.expected().name());
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
                    "swarm-peer: policy refused {0} ({1}); connection closed ({2} so far)", who, why, n);
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

    /** The single pinned node the verified peer certificate names, or null (none, or more than one). */
    PeerConfig.Node identify(SSLSocket socket) throws IOException {
        java.security.cert.Certificate[] chain;
        try {
            chain = socket.getSession().getPeerCertificates();
        } catch (SSLPeerUnverifiedException e) {
            return null;
        }
        if (chain.length == 0 || !(chain[0] instanceof X509Certificate leaf)) {
            return null;
        }
        PeerConfig.Node found = null;
        for (var san : sans(leaf)) {
            var n = config.bySan(san);
            if (n != null) {
                if (found != null && !found.equals(n)) {
                    return null; // a certificate naming two pinned nodes identifies neither
                }
                found = n;
            }
        }
        return found;
    }

    /** URI (6) and DNS (2) subject alternative names. */
    static List<String> sans(X509Certificate cert) {
        var out = new ArrayList<String>();
        try {
            var all = cert.getSubjectAlternativeNames();
            if (all != null) {
                for (var san : all) {
                    if (san.size() >= 2 && san.get(1) instanceof String value && san.get(0) instanceof Integer type
                            && (type == 6 || type == 2)) {
                        out.add(value);
                    }
                }
            }
        } catch (CertificateParsingException e) {
            return List.of();
        }
        return out;
    }
}
