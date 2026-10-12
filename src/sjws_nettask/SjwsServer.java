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
package sjws_nettask;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.channels.ServerSocketChannel;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import sjws_nettask.core.StaticFileHandler;
import sjws_nettask.observe.RequestObserver;
import sjws_nettask.observe.ServerMetrics;

/**
 * Embeddable server: HTTP/1.1 on TCP plus, when enabled, an HTTP/3 {@link Transport}.
 * Alt-Svc (opt-in, {@link ServerConfig#altSvc()}) is advertised on HTTP/1.1 only after the HTTP/3
 * listener bound successfully, with its real port; if HTTP/3 fails, the failure is logged and
 * HTTP/1.1 keeps serving without Alt-Svc.
 */
public final class SjwsServer implements AutoCloseable {

    private final ServerConfig config;
    private final RequestObserver observer;
    private final StaticFileHandler handler;
    private final AltSvc altSvc = new AltSvc();
    private final AtomicBoolean closed = new AtomicBoolean();
    /** Released when {@link #close()} has finished, so a second caller waits for the drain. */
    private final CountDownLatch shutdownDone = new CountDownLatch(1);
    private volatile Duration shutdownTimeout = Daemon.DEFAULT_SHUTDOWN_TIMEOUT;
    private Transport h3;
    private Throwable h3Failure;
    private ServerSocketChannel tcp;
    private ClientManager manager;
    private Thread acceptThread;

    private SjwsServer(ServerConfig config) throws IOException {
        this.config = config;
        this.observer = RequestObserver.open(config);
        try {
            this.handler = new StaticFileHandler(config.root(), config.allowUnlistableDirs(),
                    new sjws_nettask.core.FileCache(config.cacheBytes()));
        } catch (RuntimeException e) {
            this.observer.close();
            throw e;
        }
        if (config.cacheBytes() > Runtime.getRuntime().maxMemory() / 2) {
            System.err.println("WARN: --cache-bytes=" + config.cacheBytes() + " is more than half of the maximum heap ("
                    + Runtime.getRuntime().maxMemory() + " bytes); raise -Xmx or lower it");
        }
    }

    public static SjwsServer start(ServerConfig config) throws IOException {
        return start(config, H3Transports.defaultFactory());
    }

    /**
     * @param h3Factory used only when {@code config.h3Enabled()}
     * @throws IOException if the TCP port cannot be bound (nothing is left running)
     * @throws java.io.UncheckedIOException if the document root is not a directory
     */
    public static SjwsServer start(ServerConfig config, Transport.Factory h3Factory) throws IOException {
        var server = new SjwsServer(config);
        if (config.h3Enabled()) {
            server.startH3(h3Factory);
        }
        try {
            server.tcp = bind(config.port(), config.backlog());
        } catch (IOException e) {
            server.close();
            throw e;
        }
        server.manager = new ClientManager(server.tcp, config, server.handler, server.altSvc, server.observer);
        server.observer.metrics().setConnectionGauges(server.manager::activeConnections,
                () -> server.h3 == null ? 0 : server.h3.connections());
        server.acceptThread = new Thread(server.manager, "accept-loop");
        server.acceptThread.start();
        return server;
    }

    /** Wildcard address, SO_REUSEADDR (as {@code new ServerSocket(port)}), the configured backlog. */
    static ServerSocketChannel bind(int port, int backlog) throws IOException {
        var channel = ServerSocketChannel.open();
        try {
            channel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
            channel.bind(new InetSocketAddress(port), backlog);
            return channel;
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
    }

    private void startH3(Transport.Factory factory) {
        Transport transport = null;
        try {
            transport = factory.create(this.config, this.handler, this.observer);
            transport.start();
            this.h3 = transport;
            if (this.config.altSvc()) {
                this.altSvc.advertise(transport.port(), this.config.altSvcMaxAge());
            }
        } catch (Exception | LinkageError e) { // LinkageError: QUIC natives missing for this platform
            this.h3Failure = e;
            if (transport != null) {
                try {
                    transport.close();
                } catch (RuntimeException suppressed) {
                    e.addSuppressed(suppressed);
                }
            }
            System.err.println("WARN: HTTP/3 not started, serving HTTP/1.1 only (no Alt-Svc): " + e);
        }
    }

    public ServerConfig config() {
        return this.config;
    }

    public StaticFileHandler handler() {
        return this.handler;
    }

    /** Live counters, latency histogram and connection gauges for an admin TUI. */
    public ServerMetrics metrics() {
        return this.observer.metrics();
    }

    public AltSvc altSvc() {
        return this.altSvc;
    }

    public int tcpPort() {
        try {
            return ((InetSocketAddress) this.tcp.getLocalAddress()).getPort();
        } catch (IOException e) { // closed
            return -1;
        }
    }

    /** @return the bound HTTP/3 (UDP) port, or -1 if HTTP/3 is not running */
    public int udpPort() {
        return this.h3 == null ? -1 : this.h3.port();
    }

    /** @return why HTTP/3 could not start, or null */
    public Throwable h3Failure() {
        return this.h3Failure;
    }

    public ClientManager clientManager() {
        return this.manager;
    }

    /**
     * How long {@link #close()} waits for HTTP/1 responses already in progress after it stops
     * accepting. Idle connections are closed immediately. HTTP/3 keeps its own 2 s GOAWAY drain,
     * run in parallel with this wait.
     */
    void shutdownTimeout(Duration timeout) {
        if (timeout.isNegative() || timeout.toMillis() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("shutdown timeout out of range");
        }
        this.shutdownTimeout = timeout;
        this.manager.shutdownTimeout(timeout);
    }

    /** One-line summary for the startup log (never contains secrets). */
    public String describe() {
        var sb = new StringBuilder("HTTP/1.1 on tcp/").append(tcpPort());
        if (this.h3 != null) {
            sb.append(", HTTP/3 on udp/").append(udpPort()).append(" (")
                    .append(this.config.tlsCert() == null ? "self-signed" : "cert " + this.config.tlsCert())
                    .append(')');
        } else if (this.config.h3Enabled()) {
            sb.append(", HTTP/3 unavailable");
        }
        sb.append(", serving ").append(this.config.root());
        if (this.config.accessLog() != null) {
            sb.append(", access log ").append(this.config.accessLog());
        }
        if (this.config.metricsExposure() != ServerConfig.MetricsExposure.OFF) {
            sb.append(", metrics ").append(this.config.metricsExposure().name().toLowerCase(java.util.Locale.ROOT));
        }
        return sb.toString();
    }

    /** Blocks until the accept loop ends (after {@link #close()} or a fatal accept error). */
    public void awaitTermination() throws InterruptedException {
        this.acceptThread.join();
    }

    /**
     * Stops both listeners. New connections are refused at once; responses already in progress
     * are given {@link #shutdownTimeout(Duration)} to finish (HTTP/3 in parallel, up to its own
     * 2 s drain). Idempotent: a second call waits for the first to finish. Safe if partially started.
     */
    @Override
    public void close() {
        long waitMs = this.shutdownTimeout.toMillis() + 3_000;
        if (!this.closed.compareAndSet(false, true)) {
            try {
                this.shutdownDone.await(waitMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return;
        }
        try {
            this.altSvc.withdraw();
            if (this.h3 != null) {
                this.h3.stopAccepting(); // together with TCP, before the listen socket closes
            }
            if (this.manager != null) {
                this.manager.close();
            }
            Thread h3Shutdown = null;
            if (this.h3 != null) {
                var transport = this.h3;
                h3Shutdown = new Thread(() -> {
                    try {
                        transport.close();
                    } catch (RuntimeException e) {
                        System.err.println("WARN: error closing " + transport.name() + ": " + e);
                    }
                }, "h3-shutdown");
                h3Shutdown.start();
            }
            // An already-interrupted closer must still wait out the drain, then keep the flag.
            boolean interrupted = Thread.interrupted();
            try {
                if (this.acceptThread != null) {
                    this.acceptThread.join(waitMs);
                }
                if (h3Shutdown != null) {
                    h3Shutdown.join(waitMs);
                }
            } catch (InterruptedException e) {
                interrupted = true;
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        } finally {
            this.shutdownDone.countDown();
        }
        this.observer.close();
    }
}
