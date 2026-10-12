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
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import java.util.function.Supplier;

import sjws_nettask.core.ResponseHeaders;
import sjws_nettask.core.StaticFileHandler;

/**
 * Accept loop: hands every connection to a virtual thread, with a cap on
 * concurrent connections and back-off after accept errors (e.g. EMFILE).
 *
 * @author BlivionIaG <BlivionIaG at chenco.tk>
 */
public class ClientManager implements Runnable {

    static final long BACKOFF_INITIAL_MS = 10;
    static final long BACKOFF_MAX_MS = 1_000;

    private final ServerSocket server;
    private final ServerConfig config;
    private final StaticFileHandler handler;
    private final Supplier<ResponseHeaders> extraHeaders;
    private final Semaphore permits;
    private final AtomicInteger active = new AtomicInteger();
    private final sjws_nettask.core.PerIpLimit perIp;
    private final java.util.concurrent.atomic.LongAdder refusedPerIp = new java.util.concurrent.atomic.LongAdder();

    public ClientManager(ServerSocket server) {
        this(server, ServerConfig.defaults(CONSTANTS.DEFAULT_HTML_PATH));
    }

    public ClientManager(ServerSocket server, String htmlPath) {
        this(server, ServerConfig.defaults(htmlPath));
    }

    public ClientManager(ServerSocket server, ServerConfig config) {
        this(server, config, ResponseHeaders::of);
    }

    /**
     * @param extraHeaders fields added to every HTTP/1 response (Alt-Svc hook); called per response
     */
    public ClientManager(ServerSocket server, ServerConfig config, Supplier<ResponseHeaders> extraHeaders) {
        this(server, config, new StaticFileHandler(config.root(), config.allowUnlistableDirs(),
                new sjws_nettask.core.FileCache(config.cacheBytes())), extraHeaders);
    }

    /** Shares {@code handler} with other transports (HTTP/3). */
    public ClientManager(ServerSocket server, ServerConfig config, StaticFileHandler handler,
            Supplier<ResponseHeaders> extraHeaders) {
        this.server = server;
        this.config = config;
        this.handler = handler;
        this.extraHeaders = extraHeaders;
        this.permits = new Semaphore(config.maxConnections());
        this.perIp = new sjws_nettask.core.PerIpLimit(config.maxConnectionsPerIp());
    }

    /** @return connections closed at accept because their address already had the per-IP maximum */
    public long refusedPerIp() {
        return this.refusedPerIp.sum();
    }

    /** @return number of connections currently being served */
    public int activeConnections() {
        return this.active.get();
    }

    @Override
    public void run() {
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        var backoff = new Backoff(BACKOFF_INITIAL_MS, BACKOFF_MAX_MS);
        try {
            while (!this.server.isClosed()) {
                // At the cap, stop accepting: further clients wait in the kernel backlog.
                if (!this.permits.tryAcquire(200, TimeUnit.MILLISECONDS)) {
                    continue;
                }
                Socket socket;
                try {
                    socket = this.server.accept();
                    backoff.reset();
                } catch (IOException e) {
                    this.permits.release();
                    if (this.server.isClosed()) {
                        break; // normal shutdown
                    }
                    if (backoff.idle()) { // log the first error of a streak only
                        System.err.println("accept() failed, backing off: " + e);
                    }
                    Thread.sleep(backoff.next()); // e.g. EMFILE: don't spin at 100% CPU
                    continue;
                }
                var address = socket.getInetAddress();
                if (!this.perIp.tryAcquire(address)) { // one address can't take every connection slot
                    this.refusedPerIp.increment();
                    this.permits.release();
                    closeQuietly(socket);
                    continue;
                }
                try {
                    socket.setTcpNoDelay(true); // responses are flushed whole; don't wait for Nagle/delayed ACK
                    this.active.incrementAndGet();
                    executor.execute(() -> {
                        try {
                            new Client(socket, this.handler, this.config, this.extraHeaders).run();
                        } finally {
                            this.active.decrementAndGet();
                            this.perIp.release(address);
                            this.permits.release();
                        }
                    });
                } catch (IOException | RuntimeException e) {
                    this.active.decrementAndGet();
                    this.perIp.release(address);
                    this.permits.release();
                    System.err.println("Could not start client: " + e);
                    closeQuietly(socket);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            close();
            executor.shutdownNow(); // don't wait for idle keep-alive connections
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
