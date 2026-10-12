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
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import sjws_nettask.ha.HaRuntime;
import sjws_nettask.ha.HaSettings;
import sjws_nettask.acme.AcmeService;
import sjws_nettask.acme.AcmeStore;
import sjws_nettask.acme.ControlLeadership;
import sjws_nettask.acme.LocalLeadership;
import sjws_nettask.acme.ChallengeBoard;
import sjws_nettask.acme.ChallengeHttpServer;
import sjws_nettask.config.ConfigDocument;
import sjws_nettask.config.NodeSettings;
import sjws_nettask.config.ConfigLoader;
import sjws_nettask.config.ConfigReloader;
import sjws_nettask.config.ConfigSnapshot;
import sjws_nettask.config.LastGoodStore;
import sjws_nettask.config.LiveConfig;
import sjws_nettask.config.ReloadResult;
import sjws_nettask.core.FilterChain;
import sjws_nettask.core.Handler;
import sjws_nettask.core.RequestFilters;
import sjws_nettask.core.StaticFileHandler;
import sjws_nettask.h3.Http3Server;
import sjws_nettask.observe.ObserveRequestFilter;
import sjws_nettask.observe.ObserverSlot;
import sjws_nettask.observe.RequestObserver;
import sjws_nettask.observe.ServerMetrics;
import sjws_nettask.observe.Snapshot;
import sjws_nettask.tls.TlsMaterial;

/**
 * Embeddable server: HTTP/1.1 on TCP plus, when enabled, an HTTP/3 {@link Transport}.
 * Alt-Svc (opt-in, {@link ServerConfig#altSvc()}) is advertised on HTTP/1.1 only after the HTTP/3
 * listener bound successfully, with its real port; if HTTP/3 fails, the failure is logged and
 * HTTP/1.1 keeps serving without Alt-Svc.
 * <p>
 * Each request reads one immutable {@link ConfigSnapshot}. {@link #reload()} (and {@link ReloadHook})
 * publishes the next snapshot; the accept loop and the connection cap stay as they were at bind time.
 */
public final class SjwsServer implements AutoCloseable {

    private final LiveConfig live;
    private final ConfigReloader reloader;
    private final ChallengeBoard board;
    private final ObserverSlot slot;
    /** True when the order-0 observe filter was compiled into {@link #chain} at startup. */
    private final boolean observingAtStart;
    /** Published when counters are armed and the filter was not installed at startup. */
    private final HandlerRef armed = new HandlerRef();
    /** Null when no filters were registered. */
    private final FilterChain chain;
    /**
     * What HTTP/1.1 calls instead of the live snapshot. Null means the snapshot handler
     * (a concrete {@link StaticFileHandler#handle} when that is what the snapshot holds).
     * Otherwise the chain, or a terminal that is not the static handler. Fixed at start.
     */
    private final Handler override;
    /** Null when the server was started without {@link HaSettings}. */
    private final HaRuntime ha;
    private final AltSvc altSvc = new AltSvc();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ReentrantLock reloadLock = new ReentrantLock();
    /** Released when {@link #close()} has finished, so a second caller waits for the drain. */
    private final CountDownLatch shutdownDone = new CountDownLatch(1);
    private volatile Duration shutdownTimeout = Daemon.DEFAULT_SHUTDOWN_TIMEOUT;
    private Transport h3;
    private Throwable h3Failure;
    private Transport h2;
    private Throwable h2Failure;
    private ServerSocketChannel tcp;
    private ClientManager manager;
    private Thread acceptThread;
    /** Released by {@link #close()} so a control process with no content port can leave {@link #awaitTermination()}. */
    private final CountDownLatch contentless = new CountDownLatch(1);
    /** Set after a data node accepts a control-plane document. SIGHUP then keeps that document. */
    private final AtomicBoolean remoteApplied = new AtomicBoolean();
    /** Re-reads bootstrap local settings. Null until the service hook attaches one. */
    private volatile Runnable localReload;
    private AutoCloseable reloadHook;
    private AcmeService acme;
    /** Null on a data node. Not read by the request path. */
    private ControlLeadership leadership;
    private ChallengeHttpServer challengeHttp;

    private SjwsServer(ConfigSnapshot snapshot, ConfigReloader reloader, ChallengeBoard board,
            RequestFilters filters, Function<StaticFileHandler, Handler> terminal, HaSettings haSettings)
            throws IOException {
        this.live = new LiveConfig(snapshot);
        this.reloader = reloader;
        this.board = board;
        var config = snapshot.document().server();
        RequestObserver initial = RequestObserver.NONE;
        if (config.observe()) {
            initial = RequestObserver.open(config);
        }
        this.slot = new ObserverSlot(initial);
        this.observingAtStart = initial.recording();
        FilterChain built;
        Handler selected;
        try {
            var files = snapshot.files();
            Handler injected = haSettings == null ? null : haSettings.handler();
            var leaf = injected != null ? injected : select(files, terminal);
            RequestFilters registry = filters;
            if (this.observingAtStart) {
                // Order 0, added first, so it stays outermost among equal orders.
                registry = RequestFilters.create().add(0, new ObserveRequestFilter(this.slot));
                if (filters != null) {
                    registry.include(filters);
                }
            }
            // No caller filters: the observe chain reads the live snapshot, so a reload still
            // swaps the file handler. Caller filters keep the leaf they were compiled around.
            Handler chainLeaf = leaf;
            if (this.observingAtStart && filters == null && injected == null && leaf == files) {
                chainLeaf = request -> this.live.handler().handle(request);
            }
            built = registry == null ? null : registry.build(chainLeaf);
            selected = built != null ? built : (leaf == files ? null : leaf);
        } catch (RuntimeException e) {
            this.slot.close();
            throw e;
        }
        this.chain = built;
        if (haSettings == null) {
            this.ha = null;
            this.override = selected;
        } else {
            // One handler read. The config snapshot is that handler until a generation is published.
            Supplier<Handler> generationLeaf;
            if (built != null) {
                generationLeaf = () -> built;
            } else if (selected != null) {
                generationLeaf = () -> selected;
            } else {
                generationLeaf = this.live::handler;
            }
            this.ha = HaRuntime.create(haSettings, generationLeaf);
            this.override = this.ha.handler();
        }
        if (config.cacheBytes() > Runtime.getRuntime().maxMemory() / 2) {
            System.err.println("WARN: --cache-bytes=" + config.cacheBytes()
                    + " is more than half of the maximum heap (" + Runtime.getRuntime().maxMemory()
                    + " bytes); raise -Xmx or lower it");
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

    /** @param ha null leaves the server on the single-node path */
    public static SjwsServer start(ServerConfig config, HaSettings ha) throws IOException {
        return start(config, H3Transports.defaultFactory(), H2_FACTORY, null, null, ha);
    }

    /**
     * @param h3Factory used only when {@code config.h3Enabled()}
     * @throws IOException if the TCP port cannot be bound (nothing is left running)
     * @throws java.io.UncheckedIOException if the document root is not a directory
     */
    public static SjwsServer start(ServerConfig config, Transport.Factory h3Factory) throws IOException {
        return start(config, h3Factory, null, null);
    }

    /** @param ha null leaves filters and the file handler on the single-node path */
    public static SjwsServer start(ServerConfig config, Transport.Factory h3Factory, HaSettings ha) throws IOException {
        return start(config, h3Factory, H2_FACTORY, null, null, ha);
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
        return start(config, h3Factory, H2_FACTORY, filters, terminal);
    }

    /**
     * @param ha null leaves {@code filters} and {@code terminal} exactly as the single-node server does.
     *        HTTP/2 uses {@link #H2_FACTORY}
     */
    public static SjwsServer start(ServerConfig config, Transport.Factory h3Factory, RequestFilters filters,
            Function<StaticFileHandler, Handler> terminal, HaSettings ha) throws IOException {
        return start(config, h3Factory, H2_FACTORY, filters, terminal, ha);
    }

    /** Creates the HTTP/2 transport (TLS + optional h2c, see {@link sjws_nettask.h2.Http2Server}). */
    public static final Transport.Factory H2_FACTORY = new Transport.Factory() {
        @Override
        public Transport create(ServerConfig config, StaticFileHandler handler) {
            return new sjws_nettask.h2.Http2Server(config, handler);
        }

        /** HTTP/2 serves what HTTP/1.1 serves: the filter chain / router when there is one. */
        @Override
        public Transport create(ServerConfig config, StaticFileHandler handler, FilterChain chain, Handler terminal) {
            return new sjws_nettask.h2.Http2Server(config, terminal != null ? terminal : handler);
        }
    };

    /**
     * @param h2Factory used only when {@code config.h2Enabled()}; HTTP/2 has its own port, so the
     *        HTTP/1.1 listener and its connections are unchanged by it
     */
    public static SjwsServer start(ServerConfig config, Transport.Factory h3Factory, Transport.Factory h2Factory)
            throws IOException {
        return start(config, h3Factory, h2Factory, null, null);
    }

    /** As {@link #start(ServerConfig, Transport.Factory, RequestFilters, Function)}, with an HTTP/2 factory. */
    public static SjwsServer start(ServerConfig config, Transport.Factory h3Factory, Transport.Factory h2Factory,
            RequestFilters filters, Function<StaticFileHandler, Handler> terminal) throws IOException {
        return start(config, h3Factory, h2Factory, filters, terminal, null);
    }

    /**
     * @param ha null leaves {@code filters} and {@code terminal} exactly as the single-node server does.
     *        Non-null answers probes in the HA handler and reads the config snapshot for everything else
     *        until a generation is published.
     */
    public static SjwsServer start(ServerConfig config, Transport.Factory h3Factory, Transport.Factory h2Factory,
            RequestFilters filters, Function<StaticFileHandler, Handler> terminal, HaSettings ha) throws IOException {
        return start(ConfigLoader.standalone(config), h3Factory, h2Factory, filters, terminal, ha);
    }

    public static SjwsServer start(ConfigLoader.Loaded loaded) throws IOException {
        return start(loaded, H3Transports.defaultFactory());
    }

    public static SjwsServer start(ConfigLoader.Loaded loaded, Transport.Factory h3Factory) throws IOException {
        return start(loaded, h3Factory, true);
    }

    /** {@code bindContent} false leaves the content port unbound. The process still stays up until {@link #close()}. */
    public static SjwsServer start(ConfigLoader.Loaded loaded, Transport.Factory h3Factory, boolean bindContent)
            throws IOException {
        return start(loaded, h3Factory, H2_FACTORY, null, null, null, bindContent);
    }

    private static SjwsServer start(ConfigLoader.Loaded loaded, Transport.Factory h3Factory,
            Transport.Factory h2Factory, RequestFilters filters, Function<StaticFileHandler, Handler> terminal)
            throws IOException {
        return start(loaded, h3Factory, h2Factory, filters, terminal, null);
    }

    private static SjwsServer start(ConfigLoader.Loaded loaded, Transport.Factory h3Factory,
            Transport.Factory h2Factory, RequestFilters filters, Function<StaticFileHandler, Handler> terminal,
            HaSettings ha) throws IOException {
        return start(loaded, h3Factory, h2Factory, filters, terminal, ha, true);
    }

    private static SjwsServer start(ConfigLoader.Loaded loaded, Transport.Factory h3Factory,
            Transport.Factory h2Factory, RequestFilters filters, Function<StaticFileHandler, Handler> terminal,
            HaSettings ha, boolean bindContent) throws IOException {
        var board = new ChallengeBoard();
        var snapshot = ConfigSnapshot.compile(loaded.document(), board, 1);
        if (ha != null && ha.handler() instanceof StaticFileHandler injected) {
            // A test or embedder supplied the file handler. Keep that instance as the snapshot
            // instead of compiling a second one beside it.
            Handler visible = snapshot.handler() == snapshot.files() ? injected : snapshot.handler();
            snapshot = new ConfigSnapshot(snapshot.document(), injected, visible, snapshot.generation());
        }
        var server = new SjwsServer(snapshot, new ConfigReloader(loaded), board, filters, terminal, ha);
        if (bindContent) {
            if (snapshot.document().server().h3Enabled()) {
                server.startH3(h3Factory, storedCertificate(snapshot));
            }
            if (snapshot.document().server().h2Enabled()) {
                server.startH2(h2Factory);
            }
            try {
                server.tcp = bind(snapshot.document().server().port(), snapshot.document().server().backlog());
            } catch (IOException e) {
                server.close();
                throw e;
            }
            Supplier<Handler> handlers = server.override != null ? () -> server.override : server.live::handler;
            server.manager = new ClientManager(server.tcp, snapshot.document().server(), handlers, server.altSvc,
                    null, server.armed);
            server.attachGauges(server.slot.get().metrics());
            if (server.ha != null) {
                server.manager.setDrainSignal(server.ha.lifecycle()::closeAfterResponse);
                server.manager.onAcceptLoopExit(() -> server.ha.health().setAlive(false));
                IntSupplier pause = server.ha.settings().acceptPause();
                if (pause != null) {
                    server.manager.setAcceptPause(pause);
                }
            }
            server.acceptThread = new Thread(server.manager, "accept-loop");
            server.acceptThread.start();
            try {
                server.openChallenge(snapshot);
            } catch (IOException e) {
                server.close();
                throw e;
            }
        } else {
            server.acceptThread = new Thread(() -> {
                try {
                    server.contentless.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "service-idle");
            server.acceptThread.start();
        }
        server.leadership = leadershipFor(snapshot);
        server.reloadHook = ReloadHook.register(server::onReload);
        if (snapshot.document().acme().enabled()) {
            server.startAcme(snapshot);
        }
        server.persistLastGood(snapshot.document());
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

    private void startH3(Transport.Factory factory, TlsMaterial stored) {
        Transport transport = null;
        try {
            // A non-null chain makes FilterChain.dispatch ignore the terminal. With HA attached the
            // handler already wraps the chain, so HTTP/3 must see that handler and not the chain beside it.
            FilterChain published = this.ha == null ? this.chain : null;
            transport = factory.create(config(), handler(), published, this.override, this.slot);
            if (transport instanceof Http3Server http3) {
                if (this.override == null) {
                    http3.replaceHandler(this.live.handler()::handle);
                }
                if (stored != null) {
                    http3.replaceTls(stored);
                }
            }
            transport.start();
            this.h3 = transport;
            if (config().altSvc()) {
                this.altSvc.advertise(transport.port(), config().altSvcMaxAge());
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

    private void startH2(Transport.Factory factory) {
        Transport transport = null;
        try {
            FilterChain published = this.ha == null ? this.chain : null;
            transport = factory.create(config(), handler(), published, this.override, this.slot);
            transport.start();
            this.h2 = transport;
        } catch (Exception | LinkageError e) {
            this.h2Failure = e;
            if (transport != null) {
                try {
                    transport.close();
                } catch (RuntimeException suppressed) {
                    e.addSuppressed(suppressed);
                }
            }
            System.err.println("WARN: HTTP/2 not started: " + e);
        }
    }

    private static TlsMaterial storedCertificate(ConfigSnapshot snapshot) {
        var acme = snapshot.document().acme();
        if (!acme.enabled()) {
            return null;
        }
        try {
            var material = new AcmeStore(acme.storage()).loadCertificate();
            if (material == null || !material.chain()[0].getNotAfter().toInstant().isAfter(Instant.now())) {
                return null;
            }
            return material;
        } catch (IOException e) {
            System.err.println("WARN: stored ACME certificate was not loaded: " + e.getMessage());
            return null;
        }
    }

    private void openChallenge(ConfigSnapshot snapshot) throws IOException {
        var acme = snapshot.document().acme();
        if (acme.enabled() && !acme.onMainListener(snapshot.document().server().port())) {
            this.challengeHttp = ChallengeHttpServer.open(acme.httpPort(), this.board);
        }
    }

    private static ControlLeadership leadershipFor(ConfigSnapshot snapshot) {
        return switch (snapshot.document().node().mode()) {
            case DATA -> null;
            case CONTROL -> LocalLeadership.control();
            case STANDALONE -> LocalLeadership.standalone();
        };
    }

    /**
     * Control-plane leadership for this process. Null when the node is {@code data}.
     * Standalone is already leader-ready. Control stays idle until the group's election
     * NOOP is applied through {@link LocalLeadership#ready(long, long)}.
     */
    public ControlLeadership leadership() {
        return this.leadership;
    }

    private void startAcme(ConfigSnapshot snapshot) {
        if (snapshot.document().node().mode() == NodeSettings.Mode.DATA || this.leadership == null) {
            return;
        }
        this.acme = new AcmeService(snapshot.document().acme(), this.board, this::publishCertificate,
                this.leadership);
        this.acme.start();
    }

    private void publishCertificate(TlsMaterial material) {
        if (this.h3 instanceof Http3Server http3) {
            http3.replaceTls(material);
        }
    }

    /** Logs the result. Registered with {@link ReloadHook}; not installed as a signal handler. */
    private void onReload() {
        var result = reload();
        result.warnings().forEach(warning -> System.err.println("WARN: " + warning));
        result.needsRestart().forEach(warning -> System.err.println("WARN: " + warning));
        result.errors().forEach(error -> System.err.println("ERROR: " + error));
    }

    /**
     * Re-reads the config file, compiles a new snapshot and swaps it in. Bind-time settings that
     * changed are reported in {@link ReloadResult#needsRestart()} and left as they were. A bad file
     * keeps the previous snapshot.
     */
    public ReloadResult reload() {
        this.reloadLock.lock();
        try {
            return reloadLocked();
        } finally {
            this.reloadLock.unlock();
        }
    }

    /**
     * Applies a document pushed by a control plane. Validation, clamping and compilation are
     * {@link ConfigReloader#apply}; the swap is the same one {@link #reload()} publishes, so a
     * failure keeps the current snapshot. This does not call {@link ReloadHook#run()}: that
     * re-reads the local file.
     */
    public ReloadResult applyRemote(ConfigDocument incoming) {
        Objects.requireNonNull(incoming, "incoming");
        this.reloadLock.lock();
        try {
            var result = publish(this.reloader.apply(this.live.get(), incoming, this.h3 != null, this.board, true));
            if (result.applied() && this.live.get().document().node().mode() == NodeSettings.Mode.DATA) {
                this.remoteApplied.set(true);
            }
            return result;
        } finally {
            this.reloadLock.unlock();
        }
    }

    /** Re-read bootstrap local settings on SIGHUP after a pushed config. The request path does not call this. */
    public void setLocalReload(Runnable action) {
        this.localReload = action;
    }

    private ReloadResult reloadLocked() {
        if (this.remoteApplied.get()) {
            return reloadLocalOnly();
        }
        return publish(this.reloader.reload(this.live.get(), this.h3 != null, this.board));
    }

    /**
     * Data node that already accepted a control-plane document. The snapshot stays. Bootstrap
     * listen, peers, labels, and contact are refreshed when a hook is attached.
     */
    private ReloadResult reloadLocalOnly() {
        var warnings = new java.util.ArrayList<String>();
        var local = this.localReload;
        if (local == null) {
            warnings.add("kept the control-plane config; no local bootstrap reload is attached");
        } else {
            try {
                local.run();
                warnings.add("kept the control-plane config; reloaded local bootstrap settings");
            } catch (RuntimeException e) {
                warnings.add("kept the control-plane config; local bootstrap reload failed");
            }
        }
        var current = this.live.get();
        return new ReloadResult(false, current.generation(), warnings, java.util.List.of(), java.util.List.of(),
                current, null);
    }

    private ReloadResult publish(ReloadResult result) {
        if (!result.applied()) {
            return result;
        }
        ChallengeHttpServer opened = null;
        boolean closeOld = false;
        try {
            var acmeSettings = result.snapshot().document().acme();
            int port = result.snapshot().document().server().port();
            boolean dedicated = acmeSettings.enabled() && !acmeSettings.onMainListener(port);
            if (dedicated) {
                if (this.challengeHttp == null || this.challengeHttp.port() != acmeSettings.httpPort()) {
                    opened = ChallengeHttpServer.open(acmeSettings.httpPort(), this.board);
                    closeOld = this.challengeHttp != null;
                }
            } else {
                closeOld = this.challengeHttp != null;
            }
        } catch (IOException e) {
            if (opened != null) {
                opened.close();
            }
            return ReloadResult.rejected(this.live.get(), java.util.List.of("HTTP-01 listener: " + e.getMessage()));
        }
        this.live.set(result.snapshot());
        var server = result.snapshot().document().server();
        if (this.manager != null && this.manager.reaper() != null) {
            this.manager.reaper().update(server.idleTimeout(), server.headerTimeout(), server.writeTimeout());
        }
        if (this.h3 instanceof Http3Server http3) {
            if (this.override == null) {
                http3.replaceHandler(result.snapshot().handler()::handle);
            }
            if (result.tls() != null) {
                http3.replaceTls(result.tls());
            }
        }
        if (closeOld && this.challengeHttp != null) {
            this.challengeHttp.close();
            this.challengeHttp = null;
        }
        if (opened != null) {
            this.challengeHttp = opened;
        }
        if (result.snapshot().document().acme().enabled()) {
            if (this.acme == null) {
                startAcme(result.snapshot());
            } else {
                this.acme.update(result.snapshot().document().acme());
            }
        } else if (this.acme != null) {
            this.acme.update(result.snapshot().document().acme());
        }
        persistLastGood(result.snapshot().document());
        return result;
    }

    private void persistLastGood(ConfigDocument document) {
        try {
            LastGoodStore.write(document);
        } catch (IOException e) {
            System.err.println("WARN: last good config was not saved: " + e.getMessage());
        }
    }

    public ServerConfig config() {
        return this.live.get().document().server();
    }

    public StaticFileHandler handler() {
        return this.live.get().files();
    }

    /**
     * Handler the listeners call. The live config snapshot when no filter chain, replacement
     * terminal, or HA handler is installed, so that path is the concrete file-handler call.
     */
    public Handler requestHandler() {
        if (this.ha != null) {
            return this.ha.handler();
        }
        return this.override != null ? this.override : this.live.handler();
    }

    /** @return null when this server was started without {@link HaSettings} */
    public HaRuntime ha() {
        return this.ha;
    }

    /**
     * LB deregistration delay, then stop accepting and finish in-flight work.
     * With HA detached this is {@link #close()}.
     */
    public void drain() throws InterruptedException {
        if (this.ha == null) {
            close();
            return;
        }
        this.ha.lifecycle().drain(this::stopAcceptingForDrain, this::awaitAcceptLoop);
        close();
    }

    private void stopAcceptingForDrain() {
        if (this.manager != null) {
            this.manager.prepareGracefulDrain(this.ha.settings().drainTimeout().toMillis());
            this.manager.expireIdleConnections();
        }
        if (this.h3 != null) {
            this.h3.stopAccepting();
        }
        if (this.h2 != null) {
            this.h2.stopAccepting();
        }
        if (this.manager != null) {
            this.manager.stopAccepting();
        }
    }

    private void awaitAcceptLoop() {
        if (this.acceptThread == null) {
            return;
        }
        try {
            long wait = this.ha.settings().drainTimeout().toMillis() + 1_000;
            this.acceptThread.join(wait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * @return the compiled filter chain, or null when none were registered (the request path does
     *         not call into {@link FilterChain})
     */
    public FilterChain filterChain() {
        var live = this.armed.get();
        if (live instanceof FilterChain liveChain) {
            return liveChain;
        }
        return this.chain;
    }

    /**
     * Counters the transports are updating right now. While observability is off this is the empty
     * {@link RequestObserver#NONE} registry: reading it does not turn recording on.
     * {@link #metricsSnapshot()} does.
     */
    public ServerMetrics metrics() {
        return this.slot.get().metrics();
    }

    /**
     * Turns request counters on if they were off, then returns them. The admin TUI calls this
     * (through {@link #metricsSnapshot()}) when it draws a status view.
     * <p>
     * The first call replaces {@link RequestObserver#NONE} in the shared {@link ObserverSlot} and, when
     * the order-0 observe filter was not compiled at startup, publishes that filter as the live handler.
     * The next request on each transport enters it and starts sampling the clock and updating
     * {@link java.util.concurrent.atomic.LongAdder}s. Until that call, and unless {@code --healthz},
     * {@code --access-log} or {@code --metrics} was set at startup, no filter is installed: no
     * {@link System#nanoTime()}, no counter updates, no latency compare-and-set, and no {@code /healthz}
     * or {@code /metrics} path checks.
     * <p>
     * Arming does not by itself answer {@code /healthz}. That probe stays opt-in ({@code --healthz}).
     * A document-root file at {@code /healthz} keeps being served after the counters come on.
     */
    public ServerMetrics armMetrics() throws IOException {
        var metrics = this.slot.arm(config()).metrics();
        if (!this.observingAtStart) {
            publishArmed();
        }
        if (this.manager != null) {
            attachGauges(metrics);
        }
        return metrics;
    }

    /** Installs the observe filter once, outside whatever handler startup compiled. */
    private void publishArmed() {
        if (this.armed.get() != null) {
            return;
        }
        Handler leaf = this.override != null ? this.override : request -> this.live.handler().handle(request);
        var chain = ObserveRequestFilter.chain(this.slot, leaf);
        this.armed.set(chain);
        if (this.h3 != null) {
            this.h3.armHandler(chain);
        }
        if (this.h2 != null) {
            this.h2.armHandler(chain);
        }
    }

    /**
     * The admin snapshot. Same as {@code armMetrics().snapshot()}: the first call is what turns
     * counters on. Later calls only read.
     */
    public Snapshot metricsSnapshot() throws IOException {
        return armMetrics().snapshot();
    }

    private void attachGauges(ServerMetrics metrics) {
        metrics.setConnectionGauges(this.manager::activeConnections,
                () -> this.h3 == null ? 0 : this.h3.connections());
    }

    public AltSvc altSvc() {
        return this.altSvc;
    }

    public int tcpPort() {
        if (this.tcp == null) {
            return -1;
        }
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

    /** @return the bound HTTP/2 (TLS) port, or -1 if HTTP/2 is not running */
    public int h2Port() {
        return this.h2 == null ? -1 : this.h2.port();
    }

    /** @return the running HTTP/2 transport, or null */
    public Transport h2() {
        return this.h2;
    }

    /** @return why HTTP/2 could not start, or null */
    public Throwable h2Failure() {
        return this.h2Failure;
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
        if (this.manager != null) {
            this.manager.shutdownTimeout(timeout);
        }
        if (this.h2 != null) {
            this.h2.shutdownTimeout(timeout);
        }
    }

    /** One-line summary for the startup log (never contains secrets). */
    public String describe() {
        if (this.tcp == null) {
            return "content port not bound";
        }
        var sb = new StringBuilder("HTTP/1.1 on tcp/").append(tcpPort());
        if (this.h3 != null) {
            sb.append(", HTTP/3 on udp/").append(udpPort()).append(" (")
                    .append(config().tlsCert() == null ? "self-signed" : "cert " + config().tlsCert())
                    .append(')');
        } else if (config().h3Enabled()) {
            sb.append(", HTTP/3 unavailable");
        }
        if (this.h2 != null) {
            sb.append(", HTTP/2 on tcp/").append(h2Port());
            if (this.h2 instanceof sjws_nettask.h2.Http2Server s && s.h2cPort() >= 0) {
                sb.append(" (h2c tcp/").append(s.h2cPort()).append(')');
            }
        } else if (config().h2Enabled()) {
            sb.append(", HTTP/2 unavailable");
        }
        var config = config();
        sb.append(", serving ").append(config.root());
        if (config.accessLog() != null) {
            sb.append(", access log ").append(config.accessLog());
        }
        if (config.metricsExposure() != ServerConfig.MetricsExposure.OFF) {
            sb.append(", metrics ").append(config.metricsExposure().name().toLowerCase(java.util.Locale.ROOT));
        }
        if (config.healthz()) {
            sb.append(", healthz");
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
        this.contentless.countDown();
        try {
            if (this.reloadHook != null) {
                try {
                    this.reloadHook.close();
                } catch (Exception e) {
                    System.err.println("WARN: " + e);
                }
            }
            if (this.acme != null) {
                this.acme.close();
            }
            if (this.challengeHttp != null) {
                this.challengeHttp.close();
            }
            this.altSvc.withdraw();
            if (this.h3 != null) {
                this.h3.stopAccepting(); // together with TCP, before the listen socket closes
            }
            if (this.h2 != null) {
                this.h2.stopAccepting();
            }
            if (this.manager != null) {
                this.manager.close();
            }
            Thread h2Shutdown = null;
            if (this.h2 != null) { // GOAWAY was sent by stopAccepting; drains in parallel with HTTP/1
                var transport = this.h2;
                h2Shutdown = new Thread(() -> {
                    try {
                        transport.close();
                    } catch (RuntimeException e) {
                        System.err.println("WARN: error closing " + transport.name() + ": " + e);
                    }
                }, "h2-shutdown");
                h2Shutdown.start();
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
                if (h2Shutdown != null) {
                    h2Shutdown.join(waitMs);
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
        this.slot.close();
    }
}
