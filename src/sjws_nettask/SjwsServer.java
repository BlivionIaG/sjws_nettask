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
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
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
import sjws_nettask.config.ConfigException;
import sjws_nettask.config.ConfigLoader;
import sjws_nettask.config.ConfigReloader;
import sjws_nettask.config.ConfigSnapshot;
import sjws_nettask.config.LastGoodStore;
import sjws_nettask.config.LiveConfig;
import sjws_nettask.config.NodeSettings;
import sjws_nettask.config.ReloadResult;
import sjws_nettask.config.SnapshotVerifier;
import sjws_nettask.core.ChainControl;
import sjws_nettask.core.FilterChain;
import sjws_nettask.core.Handler;
import sjws_nettask.core.HandlerContributor;
import sjws_nettask.core.HandlerMounts;
import sjws_nettask.core.RequestFilter;
import sjws_nettask.core.RequestFilters;
import sjws_nettask.core.StaticFileHandler;
import sjws_nettask.h3.Http3Server;
import sjws_nettask.observe.ClusterStatus;
import sjws_nettask.observe.LabelSource;
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
     * Terminal under {@link #chain}, or the live file handler when nothing was compiled and the
     * leaf is the static handler. Used only if a {@link ChainControl} is opened. Not read on the
     * request path while that control is unpublished.
     */
    private final Handler chainLeaf;
    /** Null until {@link #chainControl(RequestFilters, Set)} is called. Unpublished until the first apply. */
    private volatile ChainControl control;
    /** One observe filter shared by every chain-control snapshot. Null until observability is on. */
    private ObserveRequestFilter observeFilter;
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
    /** Set only by a data-role bootstrap. The field type is {@code Object} so standalone does not name {@code cp}. */
    private Object controlPlane;
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
    /** Always invoked by {@link #applyRemote}. No-op only for standalone and an explicit test injection. */
    private final SnapshotVerifier verifier;

    private SjwsServer(ConfigSnapshot snapshot, ConfigReloader reloader, ChallengeBoard board,
            RequestFilters filters, Function<StaticFileHandler, Handler> terminal, HaSettings haSettings,
            SnapshotVerifier verifier, List<HandlerContributor> contributors)
            throws IOException {
        this.verifier = verifier != null ? verifier : SnapshotVerifier.forMode(snapshot.document().node().mode());
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
        Handler chainLeaf;
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
            // The file-handler leaf reads the live snapshot, including under caller filters,
            // so a pushed document root is observed without rebuilding the chain. A caller
            // replacement leaf stays the leaf it was compiled around. Chain control opens on
            // this same leaf. Prefix mounts, when any contributor added one, sit in front of
            // that leaf and under the global filters. No contributor means this leaf is unchanged.
            Handler fallback = leaf == files ? request -> this.live.handler().handle(request) : leaf;
            var found = HandlerMounts.discover(contributors);
            Handler mounted = found.isEmpty() ? fallback : HandlerMounts.install(found, fallback);
            chainLeaf = mounted;
            built = registry == null ? null : registry.build(chainLeaf);
            if (built != null) {
                selected = built;
            } else if (mounted != fallback) {
                selected = mounted;
            } else {
                selected = leaf == files ? null : leaf;
            }
        } catch (RuntimeException e) {
            this.slot.close();
            throw e;
        }
        this.chain = built;
        this.chainLeaf = chainLeaf;
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
     * As {@link #start(ServerConfig, RequestFilters, Function)}, and mounts {@code contributors}
     * in front of the terminal before accepting. Null or empty, and contributors that mount
     * nothing, leave the terminal as that overload does. Service providers of
     * {@link HandlerContributor} are included. Overlapping mounts throw before the port is bound.
     */
    public static SjwsServer start(ServerConfig config, RequestFilters filters,
            Function<StaticFileHandler, Handler> terminal, List<HandlerContributor> contributors)
            throws IOException {
        return start(ConfigLoader.standalone(config), H3Transports.defaultFactory(), H2_FACTORY, filters, terminal,
                null, null, true, contributors);
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

    /**
     * Config-file startup with prefix mounts. Routing, when enabled, stays the fallback behind
     * the mounts. A null list still discovers {@link HandlerContributor} service providers.
     */
    public static SjwsServer start(ConfigLoader.Loaded loaded, List<HandlerContributor> contributors)
            throws IOException {
        return start(loaded, H3Transports.defaultFactory(), true, contributors);
    }

    static SjwsServer start(ConfigLoader.Loaded loaded, Transport.Factory h3Factory, boolean bindContent,
            List<HandlerContributor> contributors) throws IOException {
        return start(loaded, h3Factory, H2_FACTORY, null, null, null, null, bindContent, contributors);
    }

    static SjwsServer start(ServerConfig config, Transport.Factory h3Factory, List<HandlerContributor> contributors)
            throws IOException {
        return start(ConfigLoader.standalone(config), h3Factory, H2_FACTORY, null, null, null, null, true,
                contributors);
    }

    /**
     * @param verifier required for a data or control node. {@link SnapshotVerifier#noop()} is only
     *        for standalone and tests. A null verifier uses {@link SnapshotVerifier#forMode}.
     */
    public static SjwsServer start(ConfigLoader.Loaded loaded, SnapshotVerifier verifier) throws IOException {
        return start(loaded, H3Transports.defaultFactory(), H2_FACTORY, null, null, null, verifier, true, null);
    }

    public static SjwsServer start(ConfigLoader.Loaded loaded, Transport.Factory h3Factory) throws IOException {
        return start(loaded, h3Factory, true);
    }

    /** {@code bindContent} false leaves the content port unbound. The process still stays up until {@link #close()}. */
    public static SjwsServer start(ConfigLoader.Loaded loaded, Transport.Factory h3Factory, boolean bindContent)
            throws IOException {
        return start(loaded, h3Factory, H2_FACTORY, null, null, null, null, bindContent, null);
    }

    private static SjwsServer start(ConfigLoader.Loaded loaded, Transport.Factory h3Factory,
            Transport.Factory h2Factory, RequestFilters filters, Function<StaticFileHandler, Handler> terminal)
            throws IOException {
        return start(loaded, h3Factory, h2Factory, filters, terminal, null);
    }

    private static SjwsServer start(ConfigLoader.Loaded loaded, Transport.Factory h3Factory,
            Transport.Factory h2Factory, RequestFilters filters, Function<StaticFileHandler, Handler> terminal,
            HaSettings ha) throws IOException {
        return start(loaded, h3Factory, h2Factory, filters, terminal, ha, null, true, null);
    }

    private static SjwsServer start(ConfigLoader.Loaded loaded, Transport.Factory h3Factory,
            Transport.Factory h2Factory, RequestFilters filters, Function<StaticFileHandler, Handler> terminal,
            HaSettings ha, SnapshotVerifier verifier, boolean bindContent, List<HandlerContributor> contributors)
            throws IOException {
        var board = new ChallengeBoard();
        var snapshot = ConfigSnapshot.compile(loaded.document(), board, 1);
        if (ha != null && ha.handler() instanceof StaticFileHandler injected) {
            // A test or embedder supplied the file handler. Keep that instance as the snapshot
            // instead of compiling a second one beside it.
            Handler visible = snapshot.handler() == snapshot.files() ? injected : snapshot.handler();
            snapshot = new ConfigSnapshot(snapshot.document(), injected, visible, snapshot.generation(),
                    snapshot.epoch(), snapshot.resources());
        }
        var server = new SjwsServer(snapshot, new ConfigReloader(loaded), board, filters, terminal, ha, verifier,
                contributors);
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
        if (snapshot.document().node().mode() == NodeSettings.Mode.DATA) {
            server.persistLastGood(snapshot.document());
        }
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
                http3.replaceHandler(request -> this.requestHandler().handle(request));
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
            if (transport instanceof sjws_nettask.h2.Http2Server http2 && this.override == null) {
                // Pin a fully armed generation (filters + metrics around the snapshot terminal) so
                // each stream's Task captures one handler with its attachBodies decision (#301).
                var pinned = this.live.handler();
                http2.armHandler(armedAround(pinned), liveNeedsBodies());
            }
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
        return finish(null);
    }

    /**
     * Applies a document pushed by a control plane. The injected {@link SnapshotVerifier} runs
     * before the swap, and a lower non-zero bundle version is rejected before it too. There is no
     * other apply entry. Validation, clamping and compilation are {@link ConfigReloader#apply}; the
     * swap is the same one {@link #reload()} publishes, so a failure keeps the current snapshot.
     * An equal {@link ConfigDocument#canonicalHash()} does not rebuild the snapshot. This does not
     * call {@link ReloadHook#run()}: that re-reads the local file. The last-good file is written
     * after the lock is released, and only for a data node.
     */
    public ReloadResult applyRemote(ConfigDocument incoming) {
        Objects.requireNonNull(incoming, "incoming");
        return finish(incoming);
    }

    private ReloadResult finish(ConfigDocument incoming) {
        ReloadResult result;
        boolean write = false;
        this.reloadLock.lock();
        try {
            var previous = this.live.get();
            try {
                if (incoming == null && this.remoteApplied.get()) {
                    result = reloadLocalOnly();
                } else {
                    if (incoming != null) {
                        this.verifier.verify(incoming);
                    }
                    var prepared = incoming == null
                            ? this.reloader.reload(previous, this.h3 != null, this.board)
                            : this.reloader.apply(previous, incoming, this.h3 != null, this.board, true);
                    if (incoming == null && prepared.applied()
                            && previous.document().node().mode() == NodeSettings.Mode.DATA
                            && controlPlaneSectionsChanged(previous.document(), prepared.snapshot().document())) {
                        result = ReloadResult.rejected(previous, java.util.List.of(
                                "SIGHUP cannot change control-plane sections on a data node: "
                                        + controlPlaneDetail(previous.document(), prepared.snapshot().document())));
                    } else {
                        result = prepared.applied() && prepared.snapshot() == previous ? prepared : publish(prepared);
                    }
                    if (incoming != null && result.applied()
                            && this.live.get().document().node().mode() == NodeSettings.Mode.DATA) {
                        this.remoteApplied.set(true);
                    }
                }
            } catch (ConfigException e) {
                var message = e.getMessage() == null ? "configuration rejected" : e.getMessage();
                result = ReloadResult.rejected(previous, java.util.List.of(message));
            }
            write = result.applied() && result.snapshot() != previous;
        } finally {
            this.reloadLock.unlock();
        }
        if (write) {
            persistLastGood(result.snapshot().document());
        }
        return result;
    }

    /**
     * Attaches the data-plane verifier. Standalone never calls this. The argument is a
     * {@code sjws_nettask.cp.DataApply}; the field stays untyped so this class's constant pool
     * does not force that load until {@link #applyRemote(sjws_nettask.cp.SignedSnapshot)} runs.
     */
    public void attachControlPlane(Object apply) {
        this.controlPlane = Objects.requireNonNull(apply, "apply");
    }

    /**
     * Verifies the signature and the rollback inside this lock, then applies the document.
     * A failure keeps the installed snapshot and does not compile. Standalone does not call this.
     */
    public ReloadResult applyRemote(sjws_nettask.cp.SignedSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        this.reloadLock.lock();
        try {
            if (!(this.controlPlane instanceof sjws_nettask.cp.DataApply apply)) {
                return ReloadResult.rejected(this.live.get(), java.util.List.of("control plane is not attached"));
            }
            var decision = apply.decide(snapshot, this.live.get());
            if (decision.error() != null) {
                return ReloadResult.rejected(this.live.get(), java.util.List.of(decision.error()));
            }
            if (decision.noop()) {
                return new ReloadResult(true, this.live.get().generation(), java.util.List.of(), java.util.List.of(),
                        java.util.List.of(), this.live.get(), null);
            }
            ConfigLoader.Loaded loaded;
            try {
                loaded = apply.parse(decision.yaml());
            } catch (IOException e) {
                return ReloadResult.rejected(this.live.get(), java.util.List.of("snapshot yaml: " + e.getMessage()));
            }
            if (!loaded.ok() || loaded.document() == null) {
                var errors = loaded.errors().isEmpty() ? java.util.List.of("configuration rejected") : loaded.errors();
                return ReloadResult.rejected(this.live.get(), errors);
            }
            var document = loaded.document();
            if (document.bundleVersion() != snapshot.version() || document.schemaVersion() != snapshot.schema()) {
                document = ConfigDocument.of(document.server(), document.acme(), document.routing(),
                        document.middleware(), document.access(), document.node(), snapshot.schema(),
                        snapshot.version(), this.live.get().document().configFile(), document.messaging())
                        .withStateDir(document.stateDir());
            }
            var result = this.reloader.apply(this.live.get(), document, this.h3 != null, this.board, true);
            if (result.applied() && snapshot.epoch() != result.snapshot().epoch()) {
                var snap = result.snapshot().withEpoch(snapshot.epoch());
                result = new ReloadResult(true, snap.generation(), result.warnings(), java.util.List.of(),
                        result.needsRestart(), snap, result.tls());
            }
            var published = result.applied() && result.snapshot() == this.live.get() ? result : publish(result);
            // Last-good (signed envelope, floor, trust, and the config sibling) is written only in DATA mode.
            if (published.applied() && this.live.get().document().node().mode() != NodeSettings.Mode.DATA) {
                apply.record(snapshot);
            } else if (published.applied()) {
                this.remoteApplied.set(true);
                try {
                    apply.remember(snapshot);
                } catch (IOException e) {
                    System.err.println("WARN: control-plane last-good was not saved: " + e.getMessage());
                }
                persistLastGood(published.snapshot().document());
                var appliedMw = published.snapshot().document().middleware();
                sjws_nettask.config.MiddlewareSecrets.logStartupWarn(
                        System.getLogger(SjwsServer.class.getName()), appliedMw, null, null);
            }
            return published;
        } finally {
            this.reloadLock.unlock();
        }
    }

    private static boolean controlPlaneSectionsChanged(ConfigDocument current, ConfigDocument next) {
        return !controlPlaneDetail(current, next).isEmpty();
    }

    private static String controlPlaneDetail(ConfigDocument current, ConfigDocument next) {
        var changed = new StringBuilder();
        if (!sameRouting(current.routing(), next.routing())) {
            changed.append("routing ");
        }
        if (!current.middleware().equals(next.middleware())) {
            changed.append("middleware ");
        }
        if (!current.access().equals(next.access())) {
            changed.append("access ");
        }
        if (!current.acme().equals(next.acme())) {
            changed.append("acme ");
        }
        if (!current.messaging().equals(next.messaging())) {
            changed.append("topics ");
        }
        return changed.toString().trim();
    }

    /** {@link sjws_nettask.config.RouteTable} does not define value equality. */
    private static boolean sameRouting(sjws_nettask.config.Routing left, sjws_nettask.config.Routing right) {
        return left.enabled() == right.enabled() && left.forwardedProto() == right.forwardedProto()
                && left.trustedProxies().equals(right.trustedProxies()) && left.upstreams().equals(right.upstreams())
                && left.routes().routes().equals(right.routes().routes());
    }

    /** Re-read bootstrap local settings on SIGHUP after a pushed config. The request path does not call this. */
    public void setLocalReload(Runnable action) {
        this.localReload = action;
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
            result.snapshot().close(); // never published: release its upstream pools
            return ReloadResult.rejected(this.live.get(), java.util.List.of("HTTP-01 listener: " + e.getMessage()));
        }
        var replaced = this.live.get();
        var next = result.snapshot();
        // HTTP/2: one atomic generation = fully armed chain (filters/metrics) + attachBodies (#301
        // P1/P2a). In-flight Tasks keep the generation they captured; defer closing replaced pools
        // until those Tasks leave (P2b).
        sjws_nettask.h2.Http2Server.Generation previousH2 = null;
        if (this.h2 instanceof sjws_nettask.h2.Http2Server http2 && this.override == null) {
            Handler terminal = next.handler();
            Handler armed = armedAround(terminal);
            boolean bodies = !(terminal instanceof StaticFileHandler) || this.armed.get() != null
                    || (this.control != null && this.control.isLive());
            previousH2 = http2.armHandler(armed, bodies);
            // Keep SjwsServer.armed aligned with the pinned generation when filters/metrics are on.
            if (this.armed.get() != null || (this.control != null && this.control.isLive())) {
                this.armed.set(armed);
            }
        }
        this.live.set(next);
        if (replaced != next) {
            deferSnapshotClose(previousH2, replaced);
        }
        var server = next.document().server();
        if (this.manager != null && this.manager.reaper() != null) {
            this.manager.reaper().update(server.idleTimeout(), server.headerTimeout(), server.writeTimeout());
        }
        if (this.h3 instanceof Http3Server http3) {
            http3.replaceHandler(request -> this.requestHandler().handle(request));
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
        return result;
    }

    private void persistLastGood(ConfigDocument document) {
        if (document.node().mode() != NodeSettings.Mode.DATA) {
            return;
        }
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

    /**
     * Whether the live snapshot's compiled handler may need request bodies / full field maps.
     * A bare {@link StaticFileHandler} does not; a router (YAML {@code routing.enabled}) or any
     * other terminal does. Used for the HTTP/2 live-snapshot arm so production static-only stays
     * zero-cost without disabling bodies for configured proxy routes (#289).
     */
    boolean liveNeedsBodies() {
        return !(this.live.handler() instanceof StaticFileHandler) || this.armed.get() != null
                || (this.control != null && this.control.isLive());
    }

    /**
     * Fully armed handler for one pinned snapshot terminal: control-plane filters (API-key etc.)
     * and the observe/metrics outer filter, compiled around {@code terminal} so HTTP/2 does not
     * re-read {@link #live} under the chain (#301 P1). Without a live chain control, keeps an
     * already-armed observe chain or returns the terminal.
     */
    Handler armedAround(Handler terminal) {
        Objects.requireNonNull(terminal, "terminal");
        Handler frozen = request -> terminal.handle(request);
        var control = this.control;
        if (control != null && control.isLive()) {
            return control.compileAround(frozen);
        }
        if (this.armed.get() != null || this.slot.get().recording() || this.observingAtStart) {
            return sjws_nettask.observe.ObserveRequestFilter.chain(this.slot, frozen);
        }
        return frozen;
    }

    /**
     * After swapping away {@code previous}, close {@code replaced} when its pinned h2 Tasks drain,
     * with a bounded wait so close cannot hang forever (P2b).
     */
    void deferSnapshotClose(sjws_nettask.h2.Http2Server.Generation previous,
            ConfigSnapshot replaced) {
        if (replaced == null) {
            return;
        }
        var once = new java.util.concurrent.atomic.AtomicBoolean();
        Runnable close = () -> {
            if (once.compareAndSet(false, true)) {
                replaced.close();
            }
        };
        if (previous == null) {
            close.run();
            return;
        }
        previous.whenQuiesced(close);
        // Bounded drain: force-close if Tasks never finish (client stall / bug).
        Thread.ofVirtual().name("sjws-snapshot-close").start(() -> {
            try {
                Thread.sleep(java.time.Duration.ofSeconds(30));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            close.run();
        });
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
     *         not call into {@link FilterChain}). After a chain-control document is published, this
     *         is the snapshot now being served, or null when that snapshot calls the terminal directly.
     */
    public FilterChain filterChain() {
        var control = this.control;
        if (control != null && control.isLive()) {
            var handler = control.published();
            return handler instanceof FilterChain live ? live : null;
        }
        var live = this.armed.get();
        if (live instanceof FilterChain liveChain) {
            return liveChain;
        }
        return this.chain;
    }

    /**
     * Opens the control-plane filter slot for this process. Calling this does not change the
     * request path. The first successful {@link ChainControl#apply} publishes one immutable
     * snapshot; until then HTTP/1.1, HTTP/2 and HTTP/3 keep the handler they were started with,
     * including the direct {@link StaticFileHandler#handle} call when no filters were registered.
     * <p>
     * A later apply replaces the compiled handler with a single volatile write on the slot each
     * transport already reads. Requests already inside the previous handler finish on it. An
     * invalid document throws and leaves the published handler in place. {@code registry} supplies
     * the factories; bind them before applying, and do not mutate it concurrently with apply.
     *
     * @param nodeLabels labels of this data-plane node, matched when a document is compiled
     */
    public ChainControl chainControl(RequestFilters registry, Set<String> nodeLabels) {
        Objects.requireNonNull(registry, "registry");
        var existing = this.control;
        if (existing != null) {
            return existing;
        }
        this.reloadLock.lock();
        try {
            if (this.control != null) {
                return this.control;
            }
            var opened = ChainControl.open(registry, nodeLabels, this.chainLeaf, this::observeOuter,
                    null, this::armSnapshot);
            this.control = opened;
            return opened;
        } finally {
            this.reloadLock.unlock();
        }
    }

    /**
     * Observe filter for a chain-control snapshot, or null while observability is off. One instance,
     * so a republish does not stack a second counter.
     */
    private RequestFilter observeOuter() {
        if (!this.observingAtStart && !this.slot.get().recording()) {
            return null;
        }
        var existing = this.observeFilter;
        if (existing != null) {
            return existing;
        }
        var created = new ObserveRequestFilter(this.slot);
        this.observeFilter = created;
        return created;
    }

    /**
     * Stores the compiled snapshot where every transport already reads its handler. One volatile
     * write per transport; the request path does not enter {@link ChainControl}.
     */
    private void armSnapshot(Handler handler) {
        this.armed.set(handler);
        if (this.h3 != null) {
            this.h3.armHandler(handler);
        }
        if (this.h2 instanceof sjws_nettask.h2.Http2Server http2) {
            // Filters need bodies; publish as one generation (P2a).
            http2.armHandler(handler, true);
        } else if (this.h2 != null) {
            this.h2.armHandler(handler);
        }
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
     * Appends {@link ClusterStatus} gauges to {@code /metrics}. Null omits them.
     * The supplier is read only while a scrape is rendered. This does not install the observe
     * filter, does not read the clock, and does not turn counters on.
     */
    public void attachClusterMetrics(Supplier<ClusterStatus> cluster) {
        this.slot.attachCluster(cluster);
    }

    /**
     * Appends per-route and per-API-key series to {@code /metrics}. Null omits them.
     * The source is read only while a scrape is rendered. This does not install the observe filter.
     */
    public void attachGatewayMetrics(LabelSource labels) {
        this.slot.attachLabels(labels);
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
        var control = this.control;
        if (control != null && control.isLive()) {
            control.pinOuter(new ObserveRequestFilter(this.slot));
            return;
        }
        if (this.armed.get() != null) {
            return;
        }
        Handler leaf = this.override != null ? this.override : request -> this.live.handler().handle(request);
        var chain = ObserveRequestFilter.chain(this.slot, leaf);
        this.armed.set(chain);
        if (this.h3 != null) {
            this.h3.armHandler(chain);
        }
        if (this.h2 instanceof sjws_nettask.h2.Http2Server http2) {
            http2.armHandler(chain, true);
        } else if (this.h2 != null) {
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
            this.live.get().close(); // upstream pools, after the listeners drained
        } finally {
            this.shutdownDone.countDown();
        }
        this.slot.close();
    }
}
