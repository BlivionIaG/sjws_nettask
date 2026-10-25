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
 * Only URI and DNS SANs count (no CN fallback).
 */
final class PeerSockets implements TcpTransport.SocketFactory {

    static final String LISTENER = "swarm-peer";
    static final int CONNECT_TIMEOUT_MILLIS = 1_000;

    private final CpServerTls tls;
    private final PeerConfig config;
    private final AtomicLong identityRefused = new AtomicLong();

    PeerSockets(CpServerTls tls, PeerConfig config) {
        this.tls = Objects.requireNonNull(tls, "tls");
        this.config = Objects.requireNonNull(config, "config");
    }

    /** Connections closed because the verified certificate did not carry the pinned identity. */
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
        PeerConfig.Node expected = null;
        for (var n : config.nodes()) {
            if (n.address().equals(to)) {
                expected = n;
            }
        }
        if (expected == null || expected.equals(config.self())) {
            throw new IOException("no pinned swarm peer at " + to);
        }
        // TLS layered over a plain socket that this factory keeps as the abort handle: an SSLSocket's own close()
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
            var node = identify(socket);
            if (node == null || !node.equals(expected)) {
                identityRefused.incrementAndGet();
                throw new IOException("swarm peer at " + to + " did not present the pinned identity of " + expected.name());
            }
        } catch (IOException | RuntimeException e) {
            plain.close();
            socket.close();
            throw e;
        }
        abortHandles.put(socket, plain);
        return socket;
    }

    /** The plain socket under a socket {@link #connect} returned; weak keys, so a dropped connection is not kept. */
    private final java.util.Map<Socket, Socket> abortHandles =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    @Override
    public Socket abortHandle(Socket connected) {
        var plain = abortHandles.get(connected);
        return plain != null ? plain : connected;
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
                try {
                    ssl.close();
                } catch (IOException ignored) {
                    // closing a stalled handshake
                }
            }
        });
        try {
            ssl.setSoTimeout(totalMillis);
            ssl.startHandshake();
        } finally {
            done.set(true);
            watchdog.interrupt();
        }
        if (ssl.isClosed()) {
            throw new IOException("swarm-peer handshake missed its " + totalMillis + " ms deadline");
        }
    }

    @Override
    public int peerWire(Socket accepted) {
        PeerConfig.Node node;
        try {
            node = identify((SSLSocket) accepted);
        } catch (IOException unverified) {
            node = null;
        }
        if (node == null || node.equals(config.self())) {
            identityRefused.incrementAndGet();
            throw new IllegalStateException("swarm peer certificate carries no pinned peer identity");
        }
        return node.wire();
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
