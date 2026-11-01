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
package sjws_nettask.h3;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.http3.DefaultHttp3GoAwayFrame;
import io.netty.handler.codec.http3.DefaultHttp3SettingsFrame;
import io.netty.handler.codec.http3.Http3;
import io.netty.handler.codec.http3.Http3ErrorCode;
import io.netty.handler.codec.http3.Http3ServerConnectionHandler;
import io.netty.handler.codec.http3.Http3Settings;
import io.netty.handler.codec.quic.QuicTokenHandler;
import io.netty.handler.codec.quic.Quic;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QLogConfiguration;
import io.netty.handler.codec.quic.QuicChannelOption;
import io.netty.handler.codec.quic.QuicSslContextBuilder;
import io.netty.handler.codec.quic.QuicStreamChannel;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import sjws_nettask.ServerConfig;
import sjws_nettask.Transport;
import sjws_nettask.HandlerRef;
import sjws_nettask.core.FilterChain;
import sjws_nettask.core.Handler;
import sjws_nettask.core.Request;
import sjws_nettask.core.RequestFilters;
import sjws_nettask.core.Response;
import sjws_nettask.core.StaticFileHandler;
import sjws_nettask.observe.ObserveRequestFilter;
import sjws_nettask.observe.ObserverSlot;
import sjws_nettask.observe.RequestObserver;
import sjws_nettask.tls.SwappableKeyManager;
import sjws_nettask.tls.TlsMaterial;

/**
 * HTTP/3 listener: QUIC on UDP ({@code h3Host}:{@code effectiveH3Port()}), TLS 1.3 with ALPN
 * {@code h3}, serving the shared {@link Handler}.
 * <pre>
 *   var h3 = new Http3Server(config, handler).start();   // binds, or throws
 *   int udpPort = h3.boundPort();
 *   ...
 *   h3.close();                                          // idempotent
 * </pre>
 * Threading: one event loop thread for the UDP socket and all QUIC connections; each request runs
 * on its own virtual thread (handler + file reads + backpressured writes), so the event loop never
 * touches the file system.
 */
public final class Http3Server implements Transport {

    /**
     * Receive flow-control windows (bytes) and concurrent request streams per connection. They bound what
     * quiche must buffer for out-of-order data (security review S2). A request body is pulled by its
     * handler: the stream stops reading while {@link Http3RequestBody#HIGH} bytes wait unread, so the
     * client is held to the stream window instead of the server buffering (#281).
     */
    static final long MAX_DATA = 1024L * 1024;
    static final long MAX_STREAM_DATA = 16L * 1024;
    static final long MAX_STREAMS = 64;
    /** Largest request field section answered normally (RFC 9114 §4.2.2 size); larger gets 431. */
    static final long MAX_FIELD_SECTION = 16 * 1024;
    /**
     * SETTINGS_MAX_FIELD_SECTION_SIZE advertised to (and enforced by) the codec. Netty treats a larger
     * section as a connection error, so it sits above {@link #MAX_FIELD_SECTION}: oversized requests are
     * refused per stream (431) and the connection's other requests survive.
     */
    static final long CODEC_MAX_FIELD_SECTION = 64 * 1024;
    /** Address-validation token policy (security review M3). */
    static final QuicTokenHandler TOKENS = NoRetryTokenHandler.INSTANCE;

    private final ServerConfig config;
    /** Swapped by {@link #replaceHandler}. Read once per request. */
    private final AtomicReference<Function<Request, Response>> handlers;
    /**
     * Copied into every request only when a chain, a non-static terminal, or header logging is installed.
     * {@link #withObserver} may set this before {@link #start()}.
     */
    private boolean captureFields;
    /** Published by {@link #armHandler} after start. A null value keeps {@link #handlers}. */
    private final HandlerRef armed = new HandlerRef();
    private boolean observerWrapped;
    private final Map<QuicChannel, Connection> state = new ConcurrentHashMap<>();
    private final BooleanSupplier available;
    private final Supplier<Throwable> unavailabilityCause;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile boolean accepting = true;
    private final java.util.concurrent.atomic.AtomicInteger halfOpen = new java.util.concurrent.atomic.AtomicInteger();
    private final LongAdder droppedHandshakes = new LongAdder();
    private sjws_nettask.core.PerIpLimit perIp;
    private final Map<Http3Writer.Outcome, LongAdder> outcomes = new EnumMap<>(Http3Writer.Outcome.class);
    private Limits limits;
    private Shared shared;
    private volatile TlsMaterial tls;
    private volatile SwappableKeyManager keys;
    private EventLoopGroup group;
    private ExecutorService executor;
    private Channel channel;
    private ObserverSlot slot = new ObserverSlot(RequestObserver.NONE);

    /** Uses the configured PEM files, or a self-signed certificate if none are set. */
    public Http3Server(ServerConfig config, StaticFileHandler handler) {
        this(config, handler, null);
    }

    /** @param tls key material to use, or null to resolve it from the configuration at {@link #start()} */
    public Http3Server(ServerConfig config, StaticFileHandler handler, TlsMaterial tls) {
        this(config, handler, tls, null, null);
    }

    /**
     * @param chain compiled filters, or null to call {@code handler} directly
     *        ({@link FilterChain#dispatch} does not put the chain on that path)
     */
    public Http3Server(ServerConfig config, StaticFileHandler handler, TlsMaterial tls, FilterChain chain) {
        this(config, handler, tls, chain, null);
    }

    /**
     * @param chain compiled filters, or null when none are registered
     * @param terminal another terminal handler (a router, or the HA handler), or null to call
     *        {@code handler} directly when {@code chain} is also null. A non-null chain already
     *        wraps its terminal, so a caller that must run a handler around the chain passes a
     *        null chain and that handler here
     */
    public Http3Server(ServerConfig config, StaticFileHandler handler, TlsMaterial tls, FilterChain chain,
            Handler terminal) {
        this(config, FilterChain.dispatch(handler, terminal, chain), tls, captureFields(chain, handler, terminal));
    }

    /**
     * Full header map only when something besides the static handler will read the request.
     * Decided once here; {@link Http3RequestHandler} does not allocate the map otherwise.
     */
    static boolean captureFields(FilterChain chain, StaticFileHandler files, Handler terminal) {
        return chain != null || (terminal != null && terminal != files);
    }

    /** Test seam: any request-to-response function (e.g. one that changes files between handle and write). */
    Http3Server(ServerConfig config, Function<Request, Response> handler, TlsMaterial tls) {
        this(config, handler, tls, false);
    }

    /**
     * @param captureFields copy every HTTP/3 header into {@link sjws_nettask.core.RequestHeaders#fields()}.
     *        False keeps the six typed fields and allocates no header map
     */
    Http3Server(ServerConfig config, Function<Request, Response> handler, TlsMaterial tls, boolean captureFields) {
        this(config, handler, tls, captureFields, Http3Server::isAvailable, Http3Server::unavailabilityCause);
    }

    /** Test seam: {@code available}/{@code unavailabilityCause} simulate missing natives. */
    Http3Server(ServerConfig config, Function<Request, Response> handler, TlsMaterial tls, BooleanSupplier available,
            Supplier<Throwable> unavailabilityCause) {
        this(config, handler, tls, false, available, unavailabilityCause);
    }

    Http3Server(ServerConfig config, Function<Request, Response> handler, TlsMaterial tls, boolean captureFields,
            BooleanSupplier available, Supplier<Throwable> unavailabilityCause) {
        this.available = Objects.requireNonNull(available, "available");
        this.unavailabilityCause = Objects.requireNonNull(unavailabilityCause, "unavailabilityCause");
        this.config = Objects.requireNonNull(config, "config");
        this.handlers = new AtomicReference<>(Objects.requireNonNull(handler, "handler"));
        this.tls = tls;
        this.captureFields = captureFields;
        this.limits = Limits.of(config);
        for (var o : Http3Writer.Outcome.values()) {
            this.outcomes.put(o, new LongAdder());
        }
    }

    /**
     * Counts and logs this listener's responses on {@code observer}. Call before {@link #start()}.
     * The HTTP/1.1 side uses the same instance so one snapshot covers both protocols.
     */
    public Http3Server withObserver(RequestObserver observer) {
        if (this.started.get()) {
            throw new IllegalStateException("already started");
        }
        var installed = observer == null ? RequestObserver.NONE : observer;
        this.slot.set(installed);
        if (installed.recording() && !this.observerWrapped) {
            this.observerWrapped = true;
            Function<Request, Response> current = this.handlers.get();
            Handler terminal = current::apply;
            this.handlers.set(
                    RequestFilters.create().add(0, new ObserveRequestFilter(this.slot)).build(terminal)::handle);
            if (installed.logHeaders()) {
                this.captureFields = true;
            }
        }
        return this;
    }

    /**
     * Shares the server's slot so a later {@link sjws_nettask.SjwsServer#metricsSnapshot()} is visible
     * on this listener. Call before {@link #start()}.
     */
    public Http3Server withSlot(ObserverSlot slot) {
        if (this.started.get()) {
            throw new IllegalStateException("already started");
        }
        this.slot = Objects.requireNonNull(slot, "slot");
        return this;
    }

    /**
     * State shared by every request stream of this server.
     *
     * @param inflight one permit per request being handled or sent ({@link Limits#maxInflight()})
     * @param serverFiles one permit per file open for sending ({@link Limits#maxOpenFiles()})
     */
    record Shared(Function<Request, Response> handler, ExecutorService executor, Limits limits, Semaphore inflight,
            Semaphore serverFiles, Map<Http3Writer.Outcome, LongAdder> outcomes, boolean captureFields,
            HandlerRef armed) {

        void count(Http3Writer.Outcome outcome) {
            this.outcomes.get(outcome).increment();
        }
    }

    /**
     * Per-connection state. {@code nextStreamId} and {@code goAwayId} are only touched on the
     * connection's event loop.
     */
    static final class Connection {

        final Semaphore files;
        final java.util.concurrent.atomic.AtomicInteger inflight = new java.util.concurrent.atomic.AtomicInteger();
        final RateGuard rate;
        long nextStreamId; // lowest client request stream id not seen yet
        long goAwayId = -1; // -1 = no GOAWAY sent; else streams with id >= this are refused
        /** The client's SETTINGS_MAX_FIELD_SECTION_SIZE; unlimited until its SETTINGS arrive (RFC 9114 §7.2.4.1). */
        volatile long peerMaxFieldSection = Long.MAX_VALUE;

        Connection(Semaphore files) {
            this(files, new RateGuard(1, 0));
        }

        Connection(Semaphore files, RateGuard rate) {
            this.files = files;
            this.rate = rate;
        }

        /** @return false if the stream arrived after a GOAWAY that excluded it */
        boolean admit(long streamId) {
            if (this.goAwayId >= 0 && streamId >= this.goAwayId) {
                return false;
            }
            this.nextStreamId = Math.max(this.nextStreamId, streamId + 4);
            return true;
        }
    }

    /** Test seam: replaces the limits derived from the configuration; call before {@link #start()}. */
    Http3Server limits(Limits newLimits) {
        if (this.started.get()) {
            throw new IllegalStateException("already started");
        }
        this.limits = Objects.requireNonNull(newLimits, "limits");
        return this;
    }

    Limits limits() {
        return this.limits;
    }

    /** @return true if the native QUIC library loads on this platform */
    public static boolean isAvailable() {
        return Quic.isAvailable();
    }

    /** @return why {@link #isAvailable()} is false, or null */
    public static Throwable unavailabilityCause() {
        return Quic.unavailabilityCause();
    }

    /**
     * Loads TLS material and binds the UDP socket.
     *
     * @return this
     * @throws IOException if QUIC is unavailable, the TLS files are invalid or the port can't be bound
     *         (nothing is left running in that case)
     */
    @Override
    public Http3Server start() throws IOException {
        if (!this.started.compareAndSet(false, true) || this.closed.get()) {
            throw new IllegalStateException("already started or closed");
        }
        if (!this.available.getAsBoolean()) {
            var cause = this.unavailabilityCause.get();
            throw new IOException("native QUIC library not available on " + System.getProperty("os.name") + "/"
                    + System.getProperty("os.arch") + " (" + (cause == null ? "unknown cause" : cause.toString())
                    + "); if /tmp is mounted noexec, set -Dio.netty.native.workdir to an exec-mounted directory",
                    cause);
        }
        if (this.tls == null) {
            try {
                this.tls = TlsMaterial.resolve(this.config);
            } catch (IOException | RuntimeException e) {
                close();
                throw e;
            }
        }
        try {
            this.keys = new SwappableKeyManager(this.tls);
            var ssl = QuicSslContextBuilder.forServer(this.keys, null)
                    .applicationProtocols(Http3.supportedApplicationProtocols()).build();
            this.executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("h3-request-", 0).factory());
            this.perIp = new sjws_nettask.core.PerIpLimit(this.limits.connectionsPerIp());
            this.shared = new Shared(request -> this.handlers.get().apply(request), this.executor, this.limits,
                    new Semaphore(this.limits.maxInflight()),
                    new Semaphore(this.limits.maxOpenFiles()), this.outcomes, this.captureFields, this.armed);
            this.group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
            var serverCodec = Http3.newQuicServerCodecBuilder()
                    .sslContext(ssl)
                    .maxIdleTimeout(this.config.idleTimeout().toMillis(), TimeUnit.MILLISECONDS)
                    .initialMaxData(MAX_DATA)
                    .initialMaxStreamDataBidirectionalRemote(MAX_STREAM_DATA)
                    .initialMaxStreamsBidirectional(MAX_STREAMS)
                    .tokenHandler(TOKENS); // no Retry: forged tokens can't bypass address validation
            maybeEnableQlog(serverCodec);
            var codec = serverCodec
                    .handler(new ChannelInitializer<QuicChannel>() {
                        @Override
                        protected void initChannel(QuicChannel quic) {
                            accept(quic);
                        }
                    })
                    .build();
            this.channel = new Bootstrap().group(this.group).channel(NioDatagramChannel.class).handler(codec)
                    .bind(new InetSocketAddress(this.config.h3Host(), this.config.effectiveH3Port()))
                    .syncUninterruptibly().channel();
            return this;
        } catch (Exception e) { // bind() rethrows checked exceptions (BindException) unchecked
            close();
            throw new IOException("could not start HTTP/3 on udp/" + this.config.h3Host() + ":"
                    + this.config.effectiveH3Port() + ": " + e.getMessage(), e);
        }
    }

    /**
     * A new QUIC connection, before its handshake (verification V2). Its source address may be spoofed,
     * so it is only counted as half-open (capped by {@link Limits#maxHalfOpen()}, dropped after
     * {@link Limits#handshakeTimeout()}); the connection limits apply once it is established
     * ({@link Gate}). Runs on the event loop, like every listener below.
     */

    /**
     * Test/CI only (#367): {@code -Dsjws.h3.qlog.dir=<dir>} enables quiche qlog on accepted
     * connections (MAX_STREAM_DATA / STREAM_DATA diagnosis). Must be set on the codec builder
     * before channels register. Off by default.
     */
    private static void maybeEnableQlog(io.netty.handler.codec.quic.QuicServerCodecBuilder builder) {
        var raw = System.getProperty("sjws.h3.qlog.dir");
        if (raw == null || raw.isBlank()) {
            return;
        }
        try {
            var root = Path.of(raw);
            Files.createDirectories(root);
            // Directory path: quiche writes one qlog file per connection under it.
            builder.option(QuicChannelOption.QLOG, new QLogConfiguration(root.toAbsolutePath().toString(),
                    "sjws-h3-server", "HTTP/3 server qlog for #367"));
        } catch (Exception e) {
            throw new IllegalStateException("sjws.h3.qlog.dir=" + raw + ": " + e.getMessage(), e);
        }
    }

    private void accept(QuicChannel quic) {
        if (this.halfOpen.incrementAndGet() > this.limits.maxHalfOpen()) {
            this.halfOpen.decrementAndGet();
            this.droppedHandshakes.increment();
            quic.close(); // nothing useful can be sent to an unvalidated address
            return;
        }
        var pending = new AtomicBoolean(true);
        Runnable established = () -> {
            if (pending.compareAndSet(true, false)) {
                this.halfOpen.decrementAndGet();
            }
        };
        var deadline = quic.eventLoop().schedule(() -> {
            if (!quic.isActive()) {
                this.droppedHandshakes.increment();
                quic.close();
            }
        }, this.limits.handshakeTimeout().toNanos(), TimeUnit.NANOSECONDS);
        quic.closeFuture().addListener(f -> {
            established.run();
            deadline.cancel(false);
        });
        var connection = new Connection(this.limits.newConnectionFiles(),
                new RateGuard(this.limits.writeTimeout().toNanos(), this.limits.minBytesPerSecond()));
        quic.pipeline().addLast(new Gate(connection, established, deadline));
        quic.pipeline().addLast(new Http3ServerConnectionHandler(new ChannelInitializer<QuicStreamChannel>() {
            @Override
            protected void initChannel(QuicStreamChannel stream) {
                stream.pipeline().addLast(new Http3RequestHandler(Http3Server.this.shared, connection));
            }
        }, new PeerSettings(connection), null, new DefaultHttp3SettingsFrame(Http3Settings.defaultSettings()
                .maxFieldSectionSize(CODEC_MAX_FIELD_SECTION)), true));
        quic.pipeline().addLast(QuietErrors.INSTANCE);
    }

    /**
     * On the client's control stream: records its SETTINGS_MAX_FIELD_SECTION_SIZE so {@link Http3Writer} never sends a
     * response field section the client would treat as a connection error. Releases every control frame; the codec has
     * already acted on them (and on any error, which it reports here too).
     */
    static final class PeerSettings extends io.netty.channel.ChannelInboundHandlerAdapter {

        private final Connection connection;

        PeerSettings(Connection connection) {
            this.connection = connection;
        }

        @Override
        public void channelRead(io.netty.channel.ChannelHandlerContext ctx, Object msg) {
            try {
                if (msg instanceof io.netty.handler.codec.http3.Http3SettingsFrame settings) {
                    var max = settings.get(io.netty.handler.codec.http3.Http3SettingIdentifier
                            .HTTP3_SETTINGS_MAX_FIELD_SECTION_SIZE.id());
                    if (max != null && max >= 0) {
                        this.connection.peerMaxFieldSection = max;
                    }
                }
            } finally {
                io.netty.util.ReferenceCountUtil.release(msg);
            }
        }

        @Override
        public void exceptionCaught(io.netty.channel.ChannelHandlerContext ctx, Throwable cause) {
            // the codec already closed the connection; a remote client can trigger this at will (no log line)
        }
    }

    /**
     * Admits a connection once its handshake completes (the peer address is then validated): it counts
     * against {@code h3MaxConnections} and the per-address limit from here on. Refused ones are closed
     * with H3_EXCESSIVE_LOAD (a close before the handshake would never reach the client) and the HTTP/3
     * handler behind this one never sees them become active.
     */
    private final class Gate extends io.netty.channel.ChannelInboundHandlerAdapter {

        private final Connection connection;
        private final Runnable established;
        private final io.netty.util.concurrent.Future<?> deadline;

        Gate(Connection connection, Runnable established, io.netty.util.concurrent.Future<?> deadline) {
            this.connection = connection;
            this.established = established;
            this.deadline = deadline;
        }

        @Override
        public void channelActive(io.netty.channel.ChannelHandlerContext ctx) {
            this.established.run();
            this.deadline.cancel(false);
            var quic = (QuicChannel) ctx.channel();
            var address = quic.remoteSocketAddress() instanceof InetSocketAddress isa ? isa.getAddress() : null;
            boolean admitted = Http3Server.this.accepting
                    && Http3Server.this.state.size() < Http3Server.this.config.h3MaxConnections()
                    && (address == null || Http3Server.this.perIp.tryAcquire(address));
            if (!admitted) {
                quic.close(true, Http3ErrorCode.H3_EXCESSIVE_LOAD.code(), Unpooled.EMPTY_BUFFER);
                return;
            }
            Http3Server.this.state.put(quic, this.connection);
            quic.closeFuture().addListener(f -> {
                Http3Server.this.state.remove(quic);
                if (address != null) {
                    Http3Server.this.perIp.release(address);
                }
            });
            ctx.pipeline().remove(this);
            ctx.fireChannelActive();
        }
    }

    /** @return connections still in their handshake */
    int halfOpen() {
        return this.halfOpen.get();
    }

    /** @return connections dropped before completing their handshake (over the cap or too slow) */
    long droppedHandshakes() {
        return this.droppedHandshakes.sum();
    }

    /** @return requests currently being handled or sent */
    int inflight() {
        return this.shared == null ? 0 : this.limits.maxInflight() - this.shared.inflight().availablePermits();
    }

    @Override
    public String name() {
        return "HTTP/3";
    }

    @Override
    public int port() {
        return boundPort();
    }

    /** @return the UDP port actually bound (useful with port 0) */
    public int boundPort() {
        return localAddress().getPort();
    }

    public InetSocketAddress localAddress() {
        if (this.channel == null) {
            throw new IllegalStateException("not started");
        }
        return (InetSocketAddress) this.channel.localAddress();
    }

    /** @return the TLS material in use (after {@link #start()}, or the material set by {@link #replaceTls}) */
    public TlsMaterial tls() {
        return this.tls;
    }

    /**
     * Certificate for new handshakes. Before {@link #start()} this replaces what start would load
     * from the configuration. After start, BoringSSL reads it on the next handshake.
     */
    public void replaceTls(TlsMaterial material) {
        this.tls = Objects.requireNonNull(material, "material");
        var manager = this.keys;
        if (manager != null) {
            manager.set(material);
        }
    }

    /** The next request uses this handler. A request already in the handler keeps the previous one. */
    public void replaceHandler(Function<Request, Response> handler) {
        this.handlers.set(Objects.requireNonNull(handler, "handler"));
    }

    /** @return QUIC connections currently open */
    @Override
    public int connections() {
        return this.state.size();
    }

    /** Keeps the server's slot, so a filter built against that slot stays on this listener. */
    @Override
    public Http3Server useSlot(ObserverSlot slot) {
        return slot == null ? this : withSlot(slot);
    }

    /** The next request uses {@code handler} instead of the function captured at {@link #start()}. */
    @Override
    public void armHandler(Handler handler) {
        this.armed.set(handler);
    }

    /** @return how many responses ended with this outcome (completed, or why the stream was reset) */
    long responses(Http3Writer.Outcome outcome) {
        return this.outcomes.get(outcome).sum();
    }

    @Override
    public void stopAccepting() {
        this.accepting = false;
    }

    /**
     * Graceful shutdown (RFC 9114 §5.2): refuses new connections, sends every connection a GOAWAY
     * naming the first request stream it will not process, lets in-flight requests finish for up to
     * {@link Limits#drain()}, then closes every connection with H3_NO_ERROR, unbinds the port,
     * interrupts what is still running and stops the event loop. Idempotent; safe after a failed start.
     */
    @Override
    public void close() {
        if (!this.closed.compareAndSet(false, true)) {
            return;
        }
        stopAccepting();
        if (this.shared != null) {
            goAway();
            drain(System.nanoTime() + this.limits.drain().toNanos());
        }
        var closing = new ArrayList<io.netty.channel.ChannelFuture>();
        for (var quic : this.state.keySet()) {
            closing.add(quic.close(true, Http3ErrorCode.H3_NO_ERROR.code(), Unpooled.EMPTY_BUFFER));
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        for (var f : closing) {
            f.awaitUninterruptibly(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        }
        if (this.channel != null) {
            this.channel.close().awaitUninterruptibly(2, TimeUnit.SECONDS);
        }
        if (this.executor != null) {
            this.executor.shutdownNow(); // interrupts writers still waiting for a slot or for flow control
            try {
                this.executor.awaitTermination(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (this.group != null) {
            this.group.shutdownGracefully(0, 2, TimeUnit.SECONDS).awaitUninterruptibly(5, TimeUnit.SECONDS);
        }
    }

    /**
     * Waits until no request is in flight and every connection has stopped sending for two round
     * trips (a completed response may still sit in quiche's send buffer, unacknowledged: closing the
     * connection then would cut it), or until the deadline.
     */
    private void drain(long deadline) {
        var lastSent = new java.util.HashMap<QuicChannel, Long>();
        long quietSince = System.nanoTime();
        while (System.nanoTime() < deadline) {
            boolean quiet = inflight() == 0;
            long quietFor = 100_000_000L; // at least 100 ms
            for (var quic : this.state.keySet()) {
                try {
                    var stats = quic.collectStats().get(1, TimeUnit.SECONDS);
                    var path = quic.collectPathStats(0).get(1, TimeUnit.SECONDS);
                    quietFor = Math.max(quietFor, 2 * TimeUnit.MILLISECONDS.toNanos(Math.max(0, path.rtt() / 1_000_000)));
                    Long before = lastSent.put(quic, stats.sentBytes());
                    quiet &= before != null && before == stats.sentBytes();
                } catch (Exception e) { // connection closed meanwhile: nothing left to drain on it
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
            if (!quiet) {
                quietSince = System.nanoTime();
            } else if (System.nanoTime() - quietSince >= quietFor) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Sends GOAWAY(first unseen request stream id) on every connection's control stream. */
    private void goAway() {
        var sent = new ArrayList<io.netty.util.concurrent.Future<?>>();
        for (var e : this.state.entrySet()) {
            var quic = e.getKey();
            var connection = e.getValue();
            sent.add(quic.eventLoop().submit(() -> {
                connection.goAwayId = connection.nextStreamId;
                var control = Http3.getLocalControlStream(quic);
                if (control != null && control.isActive()) {
                    control.writeAndFlush(new DefaultHttp3GoAwayFrame(connection.goAwayId));
                }
            }));
        }
        for (var f : sent) {
            f.awaitUninterruptibly(1, TimeUnit.SECONDS);
        }
    }
}
