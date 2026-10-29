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
package sjws_nettask.clusterharness;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One directed Raft link between two processes, through this JVM: node X dials the proxy port that X's config lists
 * for peer Y, and the proxy forwards to Y's real listener. {@link #cut()} closes every live connection and refuses new
 * ones until {@link #heal()}, which is a network partition of that direction without touching the processes.
 */
final class TcpLinkProxy implements AutoCloseable {

    private final ServerSocket server;
    private final int target;
    private final Set<Socket> live = ConcurrentHashMap.newKeySet();
    private volatile boolean cut;
    private volatile boolean open = true;

    TcpLinkProxy(int target) throws IOException {
        this.target = target;
        this.server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().name("link-proxy-" + port()).start(this::accept);
    }

    int port() {
        return this.server.getLocalPort();
    }

    void cut() {
        this.cut = true;
        for (var s : this.live) {
            closeQuietly(s);
        }
    }

    void heal() {
        this.cut = false;
    }

    private void accept() {
        while (this.open) {
            Socket in;
            try {
                in = this.server.accept();
            } catch (IOException e) {
                return;
            }
            if (this.cut) {
                closeQuietly(in);
                continue;
            }
            Socket out = new Socket();
            try {
                out.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), this.target), 1_000);
            } catch (IOException e) {
                closeQuietly(in);
                closeQuietly(out);
                continue;
            }
            this.live.add(in);
            this.live.add(out);
            Thread.ofVirtual().start(() -> pump(in, out));
            Thread.ofVirtual().start(() -> pump(out, in));
        }
    }

    private void pump(Socket from, Socket to) {
        var buf = new byte[16 << 10];
        try (InputStream is = from.getInputStream(); OutputStream os = to.getOutputStream()) {
            int n;
            while ((n = is.read(buf)) >= 0) {
                if (this.cut) {
                    break;
                }
                os.write(buf, 0, n);
            }
        } catch (IOException ignored) {
            // either side closed
        } finally {
            closeQuietly(from);
            closeQuietly(to);
            this.live.remove(from);
            this.live.remove(to);
        }
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
            // closing
        }
    }

    @Override
    public void close() {
        this.open = false;
        cut();
        try {
            this.server.close();
        } catch (IOException ignored) {
            // closing
        }
    }
}
