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
package sjws_nettask.h2;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpServerExpectContinueHandler;
import io.netty.handler.codec.http.HttpServerUpgradeHandler;
import io.netty.handler.codec.http2.CleartextHttp2ServerUpgradeHandler;
import io.netty.handler.codec.http2.Http2CodecUtil;
import io.netty.handler.codec.http2.Http2FrameCodec;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2SecurityUtil;
import io.netty.handler.codec.http2.Http2ServerUpgradeCodec;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.ApplicationProtocolNegotiationHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.ssl.SupportedCipherSuiteFilter;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.AsciiString;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import sjws_nettask.HandlerRef;
import sjws_nettask.ServerConfig;
import sjws_nettask.Transport;
import sjws_nettask.core.Handler;
import sjws_nettask.core.StaticFileHandler;
import sjws_nettask.core.PerIpLimit;
import sjws_nettask.tls.TlsMaterial;

/**
 * HTTP/2 transport (RFC 9113) on its own TCP port, separate from the main HTTP/1.1 server so that
 * path pays nothing for it:
 * <ul>
 * <li>TLS listener ({@link ServerConfig#h2Port()}): ALPN {@code h2}, falling back to {@code http/1.1}
 * (served by {@link Http1FallbackHandler}); certificate as for HTTP/3 ({@link TlsMaterial}).</li>
 * <li>optional cleartext listener ({@link ServerConfig#h2cPort()}): h2c by prior knowledge or by
 * {@code Upgrade: h2c} (RFC 7540 §3.2), otherwise plain HTTP/1.1.</li>
 * </ul>
 * Every stream runs the shared {@link Handler} on a virtual thread ({@link Http2StreamHandler}).
 * Hardening, all enforced by Netty's codec with the limits in {@link Limits}: SETTINGS_MAX_CONCURRENT_STREAMS
 * (extra streams get REFUSED_STREAM), SETTINGS_MAX_HEADER_LIST_SIZE, a RST_STREAM rate limit against
 * rapid reset (CVE-2023-44487: GOAWAY ENHANCE_YOUR_CALM and close), a cap on small CONTINUATION
 * frames (CONTINUATION flood) and on empty DATA frames; plus server-wide in-flight request and
 * open-file caps, connection caps (total and per address), a TLS handshake deadline and an idle timeout.
 */
public final class Http2Server implements Transport {

    /**
     * @param maxConcurrentStreams SETTINGS_MAX_CONCURRENT_STREAMS per connection
     * @param maxHeaderListSize SETTINGS_MAX_HEADER_LIST_SIZE (bytes, RFC 9113 §6.5.2 accounting)
     * @param maxRstFrames RST_STREAM frames accepted from a peer per {@code rstWindowSeconds}
     * @param rstWindowSeconds window of the RST_STREAM rate limit
     * @param maxSmallContinuationFrames consecutive small CONTINUATION frames accepted
     * @param maxInflight requests handled at once, server-wide (extra streams: REFUSED_STREAM)
     * @param maxOpenFiles file responses streaming at once, server-wide
     * @param inflightPerConnection requests handled at once on one connection (extra: REFUSED_STREAM)
     * @param filesPerConnection file responses streaming at once on one connection
     * @param fileWait how long a response waits for a file slot before REFUSED_STREAM
     * @param zeroWindowTimeout how long a peer may keep a stream's send window closed (then CANCEL)
     * @param maxConnectionsPerIp HTTP/2 connections per client address (capped by the server-wide setting)
     */
    public record Limits(int maxConcurrentStreams, int maxHeaderListSize, int maxRstFrames, int rstWindowSeconds,
            int maxSmallContinuationFrames, int maxInflight, int maxOpenFiles, int inflightPerConnection,
            int filesPerConnection, Duration fileWait, Duration zeroWindowTimeout, int maxConnectionsPerIp) {

        public static final Limits DEFAULT = new Limits(100, 16 * 1024, 100, 10, 16, 4096, 512);

        /**
         * The global limits with the default per-connection and per-client budgets: in flight per
         * connection = the stream cap (a smaller budget breaks compliant clients, e.g. the JDK client
         * fails on REFUSED_STREAM or a lower SETTINGS_MAX_CONCURRENT_STREAMS), 8 file responses per
         * connection with a 100 ms wait, CANCEL after 5 s of zero window, 16 connections per address.
         */
        public Limits(int maxConcurrentStreams, int maxHeaderListSize, int maxRstFrames, int rstWindowSeconds,
                int maxSmallContinuationFrames, int maxInflight, int maxOpenFiles) {
            this(maxConcurrentStreams, maxHeaderListSize, maxRstFrames, rstWindowSeconds, maxSmallContinuationFrames,
                    maxInflight, maxOpenFiles, maxConcurrentStreams, 8, Duration.ofMillis(100), Duration.ofSeconds(5), 16);
        }
    }

    /** Unflushed PING/SETTINGS ACKs (and RSTs) allowed before the connection is closed. */
    static final int MAX_QUEUED_CONTROL_FRAMES = 1000;

    /** One connection's share of the server-wide budgets. */
    record Budget(Semaphore inflight, Semaphore files) {
    }

    static final io.netty.util.AttributeKey<Budget> BUDGET = io.netty.util.AttributeKey.valueOf("sjws-h2-budget");

    /**
     * What every stream handler needs. {@link #live()} does one volatile read. A null value keeps
     * {@code handler}, so a server that never arms calls the startup handler and nothing else.
     */
    record Shared(Handler handler, ExecutorService executor, Semaphore inflight, Semaphore files,
            Duration writeTimeout, LongAdder refused, Limits limits, HandlerRef armed,
            AtomicBoolean attachBodiesFlag) {

        /** Startup handler, or the one published by a later metrics arm. */
        Handler live() {
            var published = this.armed.get();
            return published != null ? published : this.handler;
        }

        /**
         * Attach request bodies and full header maps when the published configuration needs them
         * (#289). Set from the constructor and {@link Http2Server#armHandler(Handler, boolean)};
         * not inferred from {@link #live()}{@code SjwsServer} arms a lambda that is never a
         * {@link StaticFileHandler}). Static-only keeps the zero-cost DATA release path.
         */
        boolean attachBodies() {
            return this.attachBodiesFlag.get();
        }

        /** @return the connection's budget, created on first use */
        Budget budget(Channel connection) {
            var attr = connection.attr(BUDGET);
            var b = attr.get();
            if (b == null) {
                b = new Budget(new Semaphore(this.limits.inflightPerConnection()),
                        new Semaphore(this.limits.filesPerConnection()));
                var prev = attr.setIfAbsent(b);
                if (prev != null) {
                    b = prev;
                }
            }
            return b;
        }
    }

    private final ServerConfig config;
    private final Handler handler;
    /** Published by {@link #armHandler} after start. A null value keeps {@link #handler}. */
    private final HandlerRef armed = new HandlerRef();
    /**
     * Bodies/full field maps. False for a static-only publish (including the production
     * {@code SjwsServer} live-snapshot lambda). True when a chain or non-static terminal is armed.
     */
    private final AtomicBoolean attachBodies = new AtomicBoolean();
    private final Limits limits;
    private TlsMaterial tls;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicInteger connections = new AtomicInteger();
    private final LongAdder refused = new LongAdder();
    private volatile boolean accepting = true;
    private volatile Duration drainTimeout = Duration.ofSeconds(10);
    private final io.netty.channel.group.ChannelGroup open =
            new io.netty.channel.group.DefaultChannelGroup(io.netty.util.concurrent.GlobalEventExecutor.INSTANCE);
    private PerIpLimit perIp;
    private Shared shared;
    private EventLoopGroup boss;
    private EventLoopGroup workers;
    private ExecutorService executor;
    private Channel tlsChannel;
    private Channel h2cChannel;

    public Http2Server(ServerConfig config, Handler handler) {
        this(config, handler, null, Limits.DEFAULT);
    }

    /** @param tls key material, or null to resolve it from the configuration at {@link #start()} */
    public Http2Server(ServerConfig config, Handler handler, TlsMaterial tls, Limits limits) {
        this.config = Objects.requireNonNull(config, "config");
        this.handler = Objects.requireNonNull(handler, "handler");
        this.tls = tls;
        this.limits = Objects.requireNonNull(limits, "limits");
        this.attachBodies.set(!(handler instanceof StaticFileHandler));
    }

    @Override
    public String name() {
        return "HTTP/2";
    }

    /**
     * Installs the handler a later {@code metricsSnapshot()} published. The next stream uses it;
     * streams already running keep the handler they entered with.
     */
    @Override
    public void armHandler(Handler handler) {
        armHandler(handler, !(handler instanceof StaticFileHandler));
    }

    /**
     * @param attachBodies when false, DATA is released and only the six typed headers are read
     *        (static-only / production live-snapshot arm). When true, bodies and the full field map
     *        are attached. Survives config hot-swaps: each arm republishes the flag.
     */
    public void armHandler(Handler handler, boolean attachBodies) {
        this.armed.set(Objects.requireNonNull(handler, "handler"));
        this.attachBodies.set(attachBodies);
    }

    /** @return whether bodies and full field maps are attached (tests / diagnostics) */
    boolean attachBodies() {
        return this.attachBodies.get();
    }

    /**
     * Refresh the bodies flag without replacing the armed handler (e.g. after a live-snapshot
     * reload that turns routing on or off under the same live-snapshot lambda).
     */
    public void setAttachBodies(boolean attachBodies) {
        this.attachBodies.set(attachBodies);
    }

    @Override
    public Http2Server start() throws Exception {
        try {
            if (this.tls == null) {
                this.tls = TlsMaterial.resolve(this.config);
            }
            var ssl = sslContext(this.tls);
            this.executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("h2-request-", 0).factory());
            this.perIp = new PerIpLimit(Math.min(this.config.maxConnectionsPerIp(), this.limits.maxConnectionsPerIp()));
            this.shared = new Shared(this.handler, this.executor, new Semaphore(this.limits.maxInflight()),
                    new Semaphore(this.limits.maxOpenFiles()), this.config.writeTimeout(), this.refused, this.limits,
                    this.armed, this.attachBodies);
            this.boss = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
            this.workers = new MultiThreadIoEventLoopGroup(0, NioIoHandler.newFactory());
            this.tlsChannel = bind(this.config.h2Port(), new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    if (admit(ch)) {
                        var sslHandler = ssl.newHandler(ch.alloc());
                        sslHandler.setHandshakeTimeoutMillis(Http2Server.this.config.headerTimeout().toMillis());
                        ch.pipeline().addLast(sslHandler, new Alpn());
                    }
                }
            });
            if (this.config.h2cPort() != ServerConfig.H2C_DISABLED) {
                this.h2cChannel = bind(this.config.h2cPort(), new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        if (admit(ch)) {
                            cleartext(ch.pipeline());
                        }
                    }
                });
            }
            return this;
        } catch (Exception e) {
            close();
            throw new IOException("could not start HTTP/2 on tcp/" + this.config.h2Port()
                    + (this.config.h2cPort() != ServerConfig.H2C_DISABLED ? " (h2c tcp/" + this.config.h2cPort() + ")" : "")
                    + ": " + e.getMessage(), e);
        }
    }

    static SslContext sslContext(TlsMaterial tls) throws IOException {
        var provider = SslProvider.isAlpnSupported(SslProvider.OPENSSL) ? SslProvider.OPENSSL : SslProvider.JDK;
        return SslContextBuilder.forServer(tls.key(), tls.chain())
                .sslProvider(provider)
                .protocols("TLSv1.3", "TLSv1.2")
                .ciphers(Http2SecurityUtil.CIPHERS, SupportedCipherSuiteFilter.INSTANCE) // RFC 9113 §9.2.2
                .applicationProtocolConfig(new ApplicationProtocolConfig(ApplicationProtocolConfig.Protocol.ALPN,
                        ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                        ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                        ApplicationProtocolNames.HTTP_2, ApplicationProtocolNames.HTTP_1_1))
                .build();
    }

    private Channel bind(int port, ChannelInitializer<SocketChannel> init) {
        return new ServerBootstrap().group(this.boss, this.workers).channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, this.config.backlog())
                .option(ChannelOption.SO_REUSEADDR, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(init)
                .bind(new InetSocketAddress(port)).syncUninterruptibly().channel();
    }

    /** Connection caps; also installs the idle timeout. Runs on the connection's event loop. */
    private boolean admit(SocketChannel ch) {
        var address = ch.remoteAddress().getAddress();
        if (!this.accepting || this.connections.incrementAndGet() > this.config.maxConnections()) {
            this.connections.decrementAndGet();
            ch.close();
            return false;
        }
        if (!this.perIp.tryAcquire(address)) {
            this.connections.decrementAndGet();
            ch.close();
            return false;
        }
        this.open.add(ch);
        ch.closeFuture().addListener(f -> {
            this.connections.decrementAndGet();
            this.perIp.release(address);
        });
        ch.pipeline().addLast(new IdleStateHandler(0, 0, this.config.idleTimeout().toMillis(), TimeUnit.MILLISECONDS),
                IdleClose.INSTANCE);
        return true;
    }

    Http2FrameCodec frameCodec() {
        var settings = Http2Settings.defaultSettings()
                .maxConcurrentStreams(this.limits.maxConcurrentStreams())
                .maxHeaderListSize(this.limits.maxHeaderListSize());
        return Http2FrameCodecBuilder.forServer()
                .initialSettings(settings)
                .validateHeaders(true)
                .decoderEnforceMaxRstFramesPerWindow(this.limits.maxRstFrames(), this.limits.rstWindowSeconds())
                .decoderEnforceMaxSmallContinuationFrames(this.limits.maxSmallContinuationFrames())
                .decoderEnforceMaxConsecutiveEmptyDataFrames(2)
                .encoderEnforceMaxQueuedControlFrames(MAX_QUEUED_CONTROL_FRAMES) // PING/SETTINGS floods
                .build();
    }

    Http2MultiplexHandler multiplexer() {
        return new Http2MultiplexHandler(new ChannelInitializer<Http2StreamChannel>() {
            @Override
            protected void initChannel(Http2StreamChannel stream) {
                stream.pipeline().addLast(new Http2StreamHandler(Http2Server.this.shared));
            }
        });
    }

    Deadlines.StreamIdle streamIdle(Http2FrameCodec codec) {
        return new Deadlines.StreamIdle(codec, this.config.idleTimeout());
    }

    void http2(ChannelPipeline p) {
        var codec = frameCodec();
        p.addLast(codec, streamIdle(codec), multiplexer(), Quiet.INSTANCE);
    }

    void http1(ChannelPipeline p) {
        var deadline = new Deadlines.HeadDeadline(this.config.headerTimeout());
        p.addLast(deadline.front(), new HttpServerCodec(8192, this.limits.maxHeaderListSize(), 8192),
                new HttpServerExpectContinueHandler(),
                new HttpObjectAggregator(64 * 1024), deadline.back(),
                new Http1FallbackHandler(this.shared), Quiet.INSTANCE);
    }

    /** h2c port: prior knowledge, Upgrade: h2c, or plain HTTP/1.1. */
    private void cleartext(ChannelPipeline p) {
        var source = new HttpServerCodec(8192, this.limits.maxHeaderListSize(), 8192);
        var upgrade = new HttpServerUpgradeHandler(source, protocol ->
                AsciiString.contentEquals(Http2CodecUtil.HTTP_UPGRADE_PROTOCOL_NAME, protocol)
                        ? upgradeCodec()
                        : null, 64 * 1024);
        var prior = new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel ch) {
                // before the trailing handlers (Quiet must stay last)
                var codec = frameCodec();
                ch.pipeline().addBefore("expect-continue", "h2-codec", codec);
                ch.pipeline().addAfter("h2-codec", "h2-idle", streamIdle(codec));
                ch.pipeline().addAfter("h2-idle", "h2-multiplexer", multiplexer());
            }
        };
        var deadline = new Deadlines.HeadDeadline(this.config.headerTimeout());
        p.addLast(deadline.front());
        p.addLast(new CleartextHttp2ServerUpgradeHandler(source, upgrade, prior));
        p.addLast("expect-continue", new HttpServerExpectContinueHandler());
        p.addLast("h1-aggregator", new HttpObjectAggregator(64 * 1024));
        p.addLast(deadline.back());
        p.addLast("h1-fallback", new Http1FallbackHandler(this.shared));
        p.addLast(Quiet.INSTANCE);
    }

    private Http2ServerUpgradeCodec upgradeCodec() {
        var codec = frameCodec();
        return new Http2ServerUpgradeCodec(codec, streamIdle(codec), multiplexer());
    }

    private final class Alpn extends ApplicationProtocolNegotiationHandler {
        Alpn() {
            super(ApplicationProtocolNames.HTTP_1_1);
        }

        @Override
        protected void configurePipeline(ChannelHandlerContext ctx, String protocol) {
            if (ApplicationProtocolNames.HTTP_2.equals(protocol)) {
                http2(ctx.pipeline());
            } else if (ApplicationProtocolNames.HTTP_1_1.equals(protocol)) {
                http1(ctx.pipeline());
            } else {
                ctx.close();
            }
        }

        @Override
        protected void handshakeFailure(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close(); // includes the handshake timeout; not worth a log line a client can trigger
        }
    }

    @io.netty.channel.ChannelHandler.Sharable
    static final class IdleClose extends ChannelInboundHandlerAdapter {
        static final IdleClose INSTANCE = new IdleClose();

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
            if (evt instanceof IdleStateEvent) {
                ctx.close();
            } else {
                super.userEventTriggered(ctx, evt);
            }
        }
    }

    @io.netty.channel.ChannelHandler.Sharable
    static final class Quiet extends ChannelInboundHandlerAdapter {
        static final Quiet INSTANCE = new Quiet();

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            // HTTP/2 protocol errors are the codec's: it sends the right GOAWAY (e.g. ENHANCE_YOUR_CALM)
            // and closes; closing here first would send GOAWAY(NO_ERROR) instead.
            if (Http2CodecUtil.getEmbeddedHttp2Exception(cause) == null) {
                ctx.close();
            }
        }
    }

    @Override
    public int port() {
        return ((InetSocketAddress) this.tlsChannel.localAddress()).getPort();
    }

    /** @return the bound h2c port, or -1 when the cleartext listener is off */
    public int h2cPort() {
        return this.h2cChannel == null ? -1 : ((InetSocketAddress) this.h2cChannel.localAddress()).getPort();
    }

    public TlsMaterial tls() {
        return this.tls;
    }

    /** @return connections currently open (both listeners) */
    public int connections() {
        return this.connections.get();
    }

    /** @return streams refused because the server-wide in-flight cap was reached */
    public long refusedStreams() {
        return this.refused.sum();
    }

    /** How long {@link #close()} lets streams in progress finish after GOAWAY. */
    @Override
    public void shutdownTimeout(Duration timeout) {
        this.drainTimeout = timeout;
    }

    /** Refuses new connections and tells every HTTP/2 peer GOAWAY(NO_ERROR): no new streams. */
    @Override
    public void stopAccepting() {
        this.accepting = false;
        for (var ch : this.open) {
            if (ch.pipeline().get(Http2FrameCodec.class) != null) {
                ch.writeAndFlush(new io.netty.handler.codec.http2.DefaultHttp2GoAwayFrame(
                        io.netty.handler.codec.http2.Http2Error.NO_ERROR));
            }
        }
    }

    /** @return requests currently being handled */
    int inflight() {
        return this.shared == null ? 0 : this.limits.maxInflight() - this.shared.inflight().availablePermits();
    }

    @Override
    public void close() {
        if (!this.closed.compareAndSet(false, true)) {
            return;
        }
        stopAccepting(); // GOAWAY(NO_ERROR) to every HTTP/2 peer
        for (var ch : new Channel[] {this.tlsChannel, this.h2cChannel}) {
            if (ch != null) {
                ch.close().awaitUninterruptibly(2, TimeUnit.SECONDS);
            }
        }
        boolean interrupted = false;
        long deadline = System.nanoTime() + this.drainTimeout.toNanos();
        while (inflight() > 0 && System.nanoTime() < deadline) { // let responses in progress finish
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                interrupted = true;
                break;
            }
        }
        this.open.close().awaitUninterruptibly(2, TimeUnit.SECONDS);
        if (this.executor != null) {
            this.executor.shutdownNow(); // only stragglers past the drain are left
            try {
                this.executor.awaitTermination(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (this.workers != null) {
            this.workers.shutdownGracefully(0, 2, TimeUnit.SECONDS).awaitUninterruptibly(5, TimeUnit.SECONDS);
        }
        if (this.boss != null) {
            this.boss.shutdownGracefully(0, 2, TimeUnit.SECONDS).awaitUninterruptibly(5, TimeUnit.SECONDS);
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
