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
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;

/**
 * Loopback TCP relay between one data node's watch client and the control voters' watch
 * listeners. Every byte is a real socket byte; the relay only decides when it moves.
 *
 * <ul>
 * <li>{@link #pause()} models a partition: bytes stay queued in both directions, as TCP would
 * retransmit them, until {@link #resume()}.</li>
 * <li>{@link #delay(long)} holds control-to-data bytes until {@link ClusterClock} has moved
 * that far past their arrival.</li>
 * <li>{@link #dropUpstream(int)} resets every connection to a stopped voter, as its dead
 * process would. The client reconnects and the relay dials the next live voter.</li>
 * </ul>
 */
final class WatchRelay implements AutoCloseable {

    private static final byte[] EOF = new byte[0];

    private final ClusterClock clock;
    private final Supplier<List<Integer>> upstreams;
    private final ServerSocket listener;
    private final Set<Pipe> pipes = ConcurrentHashMap.newKeySet();
    private final Thread acceptor;
    private volatile boolean paused;
    private volatile long delayNanos;
    private volatile boolean closed;

    WatchRelay(ClusterClock clock, Supplier<List<Integer>> upstreams) throws IOException {
        this.clock = clock;
        this.upstreams = upstreams;
        this.listener = new ServerSocket();
        this.listener.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        this.acceptor = Thread.ofPlatform().daemon().name("harness-relay-accept").start(this::accept);
    }

    int port() {
        return this.listener.getLocalPort();
    }

    void pause() {
        this.paused = true;
    }

    void resume() {
        this.paused = false;
    }

    void delay(long nanos) {
        this.delayNanos = Math.max(0L, nanos);
    }

    /** True while control-to-data bytes are queued here (delayed or partitioned). */
    boolean holding() {
        for (var pipe : this.pipes) {
            if (pipe.down != null && !pipe.down.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    void dropUpstream(int port) {
        for (var pipe : this.pipes) {
            if (pipe.upstreamPort == port) {
                pipe.close();
            }
        }
    }

    private void accept() {
        while (!this.closed) {
            Socket client;
            try {
                client = this.listener.accept();
            } catch (IOException e) {
                return;
            }
            Socket server = null;
            for (int port : this.upstreams.get()) {
                try {
                    server = new Socket(InetAddress.getLoopbackAddress(), port);
                    var pipe = new Pipe(client, server, port);
                    this.pipes.add(pipe);
                    pipe.start();
                    break;
                } catch (IOException e) {
                    server = null;
                }
            }
            if (server == null) {
                quietly(client);
            }
        }
    }

    @Override
    public void close() {
        this.closed = true;
        quietly(this.listener);
        for (var pipe : this.pipes) {
            pipe.close();
        }
        try {
            this.acceptor.join(1_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void quietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception e) {
            // Already closed.
        }
    }

    private record Chunk(byte[] bytes, long arrived) {
    }

    private final class Pipe {
        private final Socket client;
        private final Socket server;
        private final int upstreamPort;
        private volatile boolean done;
        private volatile ConcurrentLinkedQueue<Chunk> down;

        Pipe(Socket client, Socket server, int upstreamPort) {
            this.client = client;
            this.server = server;
            this.upstreamPort = upstreamPort;
        }

        void start() throws IOException {
            var up = new ConcurrentLinkedQueue<Chunk>();
            var down = new ConcurrentLinkedQueue<Chunk>();
            this.down = down;
            var clientIn = this.client.getInputStream();
            var clientOut = this.client.getOutputStream();
            var serverIn = this.server.getInputStream();
            var serverOut = this.server.getOutputStream();
            Thread.ofVirtual().name("harness-relay-read").start(() -> read(clientIn, up));
            Thread.ofVirtual().name("harness-relay-read").start(() -> read(serverIn, down));
            Thread.ofVirtual().name("harness-relay-write").start(() -> write(up, serverOut, false));
            Thread.ofVirtual().name("harness-relay-write").start(() -> write(down, clientOut, true));
        }

        private void read(InputStream in, ConcurrentLinkedQueue<Chunk> queue) {
            var buffer = new byte[8192];
            try {
                int n;
                while ((n = in.read(buffer)) > 0) {
                    queue.add(new Chunk(Arrays.copyOf(buffer, n), clock.nanoTime()));
                }
            } catch (IOException e) {
                // Reset or closed: deliver what was queued, then end.
            }
            queue.add(new Chunk(EOF, clock.nanoTime()));
        }

        private void write(ConcurrentLinkedQueue<Chunk> queue, OutputStream out, boolean towardData) {
            try {
                while (!this.done) {
                    var next = queue.peek();
                    boolean held = next == null || paused
                            || (towardData && clock.nanoTime() - next.arrived() < delayNanos);
                    if (held) {
                        Thread.sleep(1);
                        continue;
                    }
                    queue.poll();
                    if (next.bytes() == EOF) {
                        break;
                    }
                    out.write(next.bytes());
                    out.flush();
                }
            } catch (IOException | InterruptedException e) {
                // The other side is gone.
            }
            close();
        }

        void close() {
            this.done = true;
            pipes.remove(this);
            quietly(this.client);
            quietly(this.server);
        }
    }
}
