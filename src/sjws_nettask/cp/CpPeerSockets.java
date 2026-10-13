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
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import sjws_nettask.cluster.TcpTransport;

/**
 * The CONTROL voters' {@link TcpTransport.SocketFactory}: every peer socket comes from Security's
 * {@link CpServerTls#context()} with {@link CpServerTls#parameters()} applied (TLS 1.3, client certificate
 * required), so the listener and the dialer both present the node certificate and trust the peer CA. A reload
 * swaps the material inside the same context, so new connections pick it up. The accepted side's handshake runs
 * lazily on the transport's reader thread, under its read timeout; frames stay HMAC-authenticated on top.
 */
final class CpPeerSockets implements TcpTransport.SocketFactory {

    /** Listener name of the voter links; its {@link CpServerTls} also dials (client side of mTLS). */
    static final String LISTENER = "cp-peer";
    static final int CONNECT_TIMEOUT_MILLIS = 1_000;
    static final int HANDSHAKE_TIMEOUT_MILLIS = 5_000;

    private final CpServerTls tls;

    CpPeerSockets(CpServerTls tls) {
        this.tls = java.util.Objects.requireNonNull(tls, "tls");
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
        var socket = (SSLSocket) tls.context().getSocketFactory().createSocket();
        try {
            socket.setSSLParameters(tls.parameters());
            socket.setTcpNoDelay(true);
            socket.connect(to, CONNECT_TIMEOUT_MILLIS);
            socket.setSoTimeout(HANDSHAKE_TIMEOUT_MILLIS);
            socket.startHandshake();
            socket.setSoTimeout(0);
        } catch (IOException | RuntimeException e) {
            socket.close();
            throw e;
        }
        return socket;
    }

    /** mTLS proves the peer, so the transport's source-address filter is not needed. */
    @Override
    public boolean authenticatesPeers() {
        return true;
    }
}
