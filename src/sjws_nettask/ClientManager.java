/*
 * Copyright (C) 2019 BlivionIaG <BlivionIaG at chenco.tk>
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
package sjws_nettask;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import java.util.function.Supplier;

import sjws_nettask.core.Handler;
import sjws_nettask.core.ResponseHeaders;
import sjws_nettask.core.StaticFileHandler;

/**
 * Accept loop: hands every connection to a virtual thread ({@link Http1Connection} on a
 * blocking {@link SocketChannel}), with a cap on concurrent connections, a per-address cap and
 * back-off after accept errors (e.g. EMFILE). One {@link ConnectionReaper} thread enforces the
 * idle, request-head and write deadlines of all connections.
 *
 * @author BlivionIaG <BlivionIaG at chenco.tk>
 */
public class ClientManager implements Runnable {

    static final long BACKOFF_INITIAL_MS = 10;
    static final long BACKOFF_MAX_MS = 1_000;

    private final ServerSocketChannel server;
    private final ServerConfig config;
    private final StaticFileHandler handler;
    /**
     * Null when connections should call {@link #handler} directly. Otherwise the filter chain,
     * or another terminal handler (a router).
     */
    private final Handler override;
    private final Supplier<ResponseHeaders> extraHeaders;
    private final Semaphore permits;
    private final AtomicInteger active = new AtomicInteger();
    private final sjws_nettask.core.PerIpLimit perIp;
    private final java.util.concurrent.atomic.LongAdder refusedPerIp = new java.util.concurrent.atomic.LongAdder();

    public ClientManager(ServerSocketChannel server) {
        this(server, ServerConfig.defaults(CONSTANTS.DEFAULT_HTML_PATH));
    }

    public ClientManager(ServerSocketChannel server, String htmlPath) {
        this(server, ServerConfig.defaults(htmlPath));
    }

    public ClientManager(ServerSocketChannel server, ServerConfig config) {
        this(server, config, ResponseHeaders::of);
    }

    /**
     * @param extraHeaders fields added to every HTTP/1 response (Alt-Svc hook); called per response
     */
    public ClientManager(ServerSocketChannel server, ServerConfig config, Supplier<ResponseHeaders> extraHeaders) {
        this(server, config, new StaticFileHandler(config.root(), config.allowUnlistableDirs(),
                new sjws_nettask.core.FileCache(config.cacheBytes())), extraHeaders);
    }

    /** Shares {@code handler} with other transports (HTTP/3). */
    public ClientManager(ServerSocketChannel server, ServerConfig config, StaticFileHandler handler,
            Supplier<ResponseHeaders> extraHeaders) {
        this(server, config, handler, extraHeaders, null);
    }

    /**
     * @param override compiled filters or another terminal handler, or null to call {@code handler}
     *        directly
     */
    public ClientManager(ServerSocketChannel server, ServerConfig config, StaticFileHandler handler,
            Supplier<ResponseHeaders> extraHeaders, Handler override) {
        this.server = server;
        this.config = config;
        this.handler = handler;
        this.override = override;
        this.extraHeaders = extraHeaders;
        this.permits = new Semaphore(config.maxConnections());
        this.perIp = new sjws_nettask.core.PerIpLimit(config.maxConnectionsPerIp());
    }

    /** @return connections closed at accept because their address already had the per-IP maximum */
    public long refusedPerIp() {
        return this.refusedPerIp.sum();
    }

    private volatile ConnectionReaper reaper;
    /** How long {@link #run()}'s shutdown waits for a response already being written. */
    private volatile long shutdownTimeoutMs = Daemon.DEFAULT_SHUTDOWN_TIMEOUT.toMillis();

    /** @return the deadline enforcer (null before {@link #run()} starts) */
    ConnectionReaper reaper() {
        return this.reaper;
    }

    /** How long shutdown waits for responses already in progress (idle connections are closed at once). */
    void shutdownTimeout(Duration timeout) {
        long ms = timeout.toMillis();
        if (ms < 0 || ms > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("shutdown timeout out of range");
        }
        this.shutdownTimeoutMs = ms;
    }

    /** @return number of connections currently being served */
    public int activeConnections() {
        return this.active.get();
    }

    @Override
    public void run() {
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        var backoff = new Backoff(BACKOFF_INITIAL_MS, BACKOFF_MAX_MS);
        var deadlines = new ConnectionReaper(this.config.idleTimeout(), this.config.headerTimeout(),
                this.config.writeTimeout());
        this.reaper = deadlines;
        try {
            while (this.server.isOpen()) {
                // At the cap, stop accepting: further clients wait in the kernel backlog.
                if (!this.permits.tryAcquire(200, TimeUnit.MILLISECONDS)) {
                    continue;
                }
                SocketChannel socket;
                try {
                    socket = this.server.accept();
                    backoff.reset();
                } catch (IOException e) {
                    this.permits.release();
                    if (!this.server.isOpen()) {
                        break; // normal shutdown
                    }
                    if (backoff.idle()) { // log the first error of a streak only
                        System.err.println("accept() failed, backing off: " + e);
                    }
                    Thread.sleep(backoff.next()); // e.g. EMFILE: don't spin at 100% CPU
                    continue;
                }
                java.net.InetAddress address;
                try {
                    address = ((InetSocketAddress) socket.getRemoteAddress()).getAddress();
                } catch (IOException e) { // reset between accept and here
                    this.permits.release();
                    closeQuietly(socket);
                    continue;
                }
                if (!this.perIp.tryAcquire(address)) { // one address can't take every connection slot
                    this.refusedPerIp.increment();
                    this.permits.release();
                    closeQuietly(socket);
                    continue;
                }
                try {
                    // responses are written whole; don't wait for Nagle/delayed ACK
                    socket.setOption(StandardSocketOptions.TCP_NODELAY, true);
                } catch (IOException | RuntimeException e) {
                    this.perIp.release(address);
                    this.permits.release();
                    System.err.println("Could not start client: " + e);
                    closeQuietly(socket);
                    continue;
                }
                this.active.incrementAndGet();
                // never rejected: the executor is shut down only after this loop
                executor.execute(() -> {
                    try {
                        var terminal = this.override != null ? this.override : this.handler;
                        new Http1Connection(socket, terminal, this.extraHeaders, deadlines).run();
                    } finally {
                        this.active.decrementAndGet();
                        this.perIp.release(address);
                        this.permits.release();
                    }
                });
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            close(); // stop accepting; in-flight responses are drained below
            // A signalled accept thread must still be able to wait out the drain.
            boolean interrupted = Thread.interrupted();
            try {
                deadlines.beginDrain();
                executor.shutdown();
                boolean finished = false;
                try {
                    finished = executor.awaitTermination(this.shutdownTimeoutMs, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    interrupted = true;
                }
                if (!finished) {
                    int still = this.active.get();
                    if (still > 0) {
                        System.err.println("Shutdown: " + still + " connection(s) still open after "
                                + this.shutdownTimeoutMs + " ms; closing them");
                    }
                    deadlines.forceAll();
                    executor.shutdownNow();
                    try {
                        executor.awaitTermination(1, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
            } finally {
                deadlines.close();
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    public void close() {
        closeQuietly(this.server);
    }

    private static void closeQuietly(AutoCloseable c) {
        try {
            c.close();
        } catch (Exception e) {
            System.err.println(e);
        }
    }
}
