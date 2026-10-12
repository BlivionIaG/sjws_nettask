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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import sjws_nettask.core.FilterChain;
import sjws_nettask.core.Handler;
import sjws_nettask.core.RequestFilters;
import sjws_nettask.core.StaticFileHandler;

/**
 * Embeddable server: HTTP/1.1 on TCP plus, when enabled, an HTTP/3 {@link Transport}.
 * Alt-Svc (opt-in, {@link ServerConfig#altSvc()}) is advertised on HTTP/1.1 only after the HTTP/3
 * listener bound successfully, with its real port; if HTTP/3 fails, the failure is logged and
 * HTTP/1.1 keeps serving without Alt-Svc.
 */
public final class SjwsServer implements AutoCloseable {

    private final ServerConfig config;
    private final StaticFileHandler handler;
    /** Null when no filters were registered. */
    private final FilterChain chain;
    /**
     * What HTTP/1.1 calls. Null means {@link #handler} directly (concrete {@code handle}, no
     * {@link Handler} indirection). Otherwise the chain, or a terminal that is not the static handler.
     */
    private final Handler override;
    private final AltSvc altSvc = new AltSvc();
    private final AtomicBoolean closed = new AtomicBoolean();
    private Transport h3;
    private Throwable h3Failure;
    private ServerSocketChannel tcp;
    private ClientManager manager;
    private Thread acceptThread;

    private SjwsServer(ServerConfig config, RequestFilters filters, Function<StaticFileHandler, Handler> terminal) {
        this.config = config;
        this.handler = new StaticFileHandler(config.root(), config.allowUnlistableDirs(),
                new sjws_nettask.core.FileCache(config.cacheBytes()));
        var leaf = select(this.handler, terminal);
        this.chain = filters == null ? null : filters.build(leaf);
        this.override = this.chain != null ? this.chain : (leaf == this.handler ? null : leaf);
        if (config.cacheBytes() > Runtime.getRuntime().maxMemory() / 2) {
            System.err.println("WARN: --cache-bytes=" + config.cacheBytes() + " is more than half of the maximum heap ("
                    + Runtime.getRuntime().maxMemory() + " bytes); raise -Xmx or lower it");
        }
    }

    /**
     * A null selector, a null return, or the static handler itself all keep the direct file-handler call.
     */
    private static Handler select(StaticFileHandler files, Function<StaticFileHandler, Handler> terminal) {
        if (terminal == null) {
            return files;
        }
        var selected = terminal.apply(files);
        if (selected == null || selected == files) {
            return files;
        }
        return selected;
    }

    public static SjwsServer start(ServerConfig config) throws IOException {
        return start(config, H3Transports.defaultFactory(), null, null);
    }

    /**
     * @param h3Factory used only when {@code config.h3Enabled()}
     * @throws IOException if the TCP port cannot be bound (nothing is left running)
     * @throws java.io.UncheckedIOException if the document root is not a directory
     */
    public static SjwsServer start(ServerConfig config, Transport.Factory h3Factory) throws IOException {
        return start(config, h3Factory, null, null);
    }

    /**
     * HTTP/1.1 and HTTP/3 share {@code filters}. Null or empty leaves the static file handler
     * on the hot path ({@link #filterChain()} stays null).
     */
    public static SjwsServer start(ServerConfig config, RequestFilters filters) throws IOException {
        return start(config, H3Transports.defaultFactory(), filters, null);
    }

    /**
     * @param terminal chooses the handler filters wrap (a router around {@code files}, or {@code files}
     *        itself). Null keeps the static file handler. Per-route filters wrap that route's own
     *        handler with {@link RequestFilters#wrapping} and do not use this selector
     */
    public static SjwsServer start(ServerConfig config, RequestFilters filters,
            Function<StaticFileHandler, Handler> terminal) throws IOException {
        return start(config, H3Transports.defaultFactory(), filters, terminal);
    }

    /**
     * @param h3Factory used only when {@code config.h3Enabled()}
     * @param filters request filters, or null; compiled once here and passed to both transports
     * @throws IOException if the TCP port cannot be bound (nothing is left running)
     * @throws java.io.UncheckedIOException if the document root is not a directory
     */
    public static SjwsServer start(ServerConfig config, Transport.Factory h3Factory, RequestFilters filters)
            throws IOException {
        return start(config, h3Factory, filters, null);
    }

    /**
     * @param h3Factory used only when {@code config.h3Enabled()}
     * @param filters request filters, or null; compiled once here around {@code terminal} and passed
     *        to both transports
     * @param terminal chooses the leaf handler, or null for the static file handler. Filters wrap
     *        that leaf. When this returns the static handler and {@code filters} is null or empty,
     *        neither transport enters {@link FilterChain}
     * @throws IOException if the TCP port cannot be bound (nothing is left running)
     * @throws java.io.UncheckedIOException if the document root is not a directory
     */
    public static SjwsServer start(ServerConfig config, Transport.Factory h3Factory, RequestFilters filters,
            Function<StaticFileHandler, Handler> terminal) throws IOException {
        var server = new SjwsServer(config, filters, terminal);
        if (config.h3Enabled()) {
            server.startH3(h3Factory);
        }
        try {
            server.tcp = bind(config.port(), config.backlog());
        } catch (IOException e) {
            server.close();
            throw e;
        }
        server.manager = new ClientManager(server.tcp, config, server.handler, server.altSvc, server.override);
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
            transport = factory.create(this.config, this.handler, this.chain, this.override);
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

    /**
     * @return the compiled filter chain, or null when none were registered (the request path does
     *         not call into {@link FilterChain})
     */
    public FilterChain filterChain() {
        return this.chain;
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
        return sb.append(", serving ").append(this.config.root()).toString();
    }

    /** Blocks until the accept loop ends (after {@link #close()} or a fatal accept error). */
    public void awaitTermination() throws InterruptedException {
        this.acceptThread.join();
    }

    /** Stops both listeners. Idempotent; safe if partially started. */
    @Override
    public void close() {
        if (!this.closed.compareAndSet(false, true)) {
            return;
        }
        this.altSvc.withdraw();
        if (this.h3 != null) {
            this.h3.stopAccepting(); // together with TCP; its graceful drain happens below
        }
        if (this.manager != null) {
            this.manager.close();
        }
        if (this.acceptThread != null) {
            try {
                this.acceptThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (this.h3 != null) {
            try {
                this.h3.close();
            } catch (RuntimeException e) {
                System.err.println("WARN: error closing " + this.h3.name() + ": " + e);
            }
        }
    }
}
