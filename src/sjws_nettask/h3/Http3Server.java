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
import io.netty.handler.codec.http3.Http3;
import io.netty.handler.codec.http3.Http3ErrorCode;
import io.netty.handler.codec.http3.Http3ServerConnectionHandler;
import io.netty.handler.codec.quic.QuicTokenHandler;
import io.netty.handler.codec.quic.Quic;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicSslContextBuilder;
import io.netty.handler.codec.quic.QuicStreamChannel;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import sjws_nettask.ServerConfig;
import sjws_nettask.core.Request;
import sjws_nettask.core.Response;
import sjws_nettask.core.StaticFileHandler;
import sjws_nettask.tls.TlsMaterial;

/**
 * HTTP/3 listener: QUIC on UDP ({@code h3Host}:{@code effectiveH3Port()}), TLS 1.3 with ALPN
 * {@code h3}, serving the shared {@link StaticFileHandler}.
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
public final class Http3Server implements AutoCloseable {

    /**
     * Receive flow-control windows (bytes) and concurrent request streams per connection. Requests are
     * a HEADERS frame (a few KiB) and bodies are
     * discarded, so the windows only need to fit that: they also bound what quiche must buffer for
     * out-of-order data (security review S2).
     */
    static final long MAX_DATA = 1024L * 1024;
    static final long MAX_STREAM_DATA = 16L * 1024;
    static final long MAX_STREAMS = 64;
    /** Files open for sending at once, across all connections (each in-flight stream holds one fd). */
    static final int MAX_OPEN_FILES = 256;
    /** Address-validation token policy (security review M3). */
    static final QuicTokenHandler TOKENS = NoRetryTokenHandler.INSTANCE;

    private final ServerConfig config;
    private final Function<Request, Response> handler;
    private final Set<QuicChannel> connections = ConcurrentHashMap.newKeySet();
    private final AtomicInteger connectionCount = new AtomicInteger();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Semaphore openFiles = new Semaphore(MAX_OPEN_FILES);
    private final Map<Http3Writer.Outcome, LongAdder> outcomes = new EnumMap<>(Http3Writer.Outcome.class);
    private TlsMaterial tls;
    private EventLoopGroup group;
    private ExecutorService executor;
    private Channel channel;

    /** Uses the configured PEM files, or a self-signed certificate if none are set. */
    public Http3Server(ServerConfig config, StaticFileHandler handler) {
        this(config, handler, null);
    }

    /** @param tls key material to use, or null to resolve it from the configuration at {@link #start()} */
    public Http3Server(ServerConfig config, StaticFileHandler handler, TlsMaterial tls) {
        this(config, Objects.requireNonNull(handler, "handler")::handle, tls);
    }

    /** Test seam: any request-to-response function (e.g. one that changes files between handle and write). */
    Http3Server(ServerConfig config, Function<Request, Response> handler, TlsMaterial tls) {
        this.config = Objects.requireNonNull(config, "config");
        this.handler = Objects.requireNonNull(handler, "handler");
        this.tls = tls;
        for (var o : Http3Writer.Outcome.values()) {
            this.outcomes.put(o, new LongAdder());
        }
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
    public Http3Server start() throws IOException {
        if (!this.started.compareAndSet(false, true) || this.closed.get()) {
            throw new IllegalStateException("already started or closed");
        }
        if (!isAvailable()) {
            throw new IOException("HTTP/3 unavailable: native QUIC library could not be loaded "
                    + "(set -Dio.netty.native.workdir to an exec-mounted directory if /tmp is noexec)",
                    unavailabilityCause());
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
            var ssl = QuicSslContextBuilder.forServer(this.tls.key(), null, this.tls.chain())
                    .applicationProtocols(Http3.supportedApplicationProtocols()).build();
            this.executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("h3-request-", 0).factory());
            this.group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
            var codec = Http3.newQuicServerCodecBuilder()
                    .sslContext(ssl)
                    .maxIdleTimeout(this.config.idleTimeout().toMillis(), TimeUnit.MILLISECONDS)
                    .initialMaxData(MAX_DATA)
                    .initialMaxStreamDataBidirectionalRemote(MAX_STREAM_DATA)
                    .initialMaxStreamsBidirectional(MAX_STREAMS)
                    .tokenHandler(TOKENS) // no Retry: forged tokens can't bypass address validation
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

    private void accept(QuicChannel quic) {
        if (this.closed.get() || this.connectionCount.incrementAndGet() > this.config.h3MaxConnections()) {
            if (!this.closed.get()) {
                this.connectionCount.decrementAndGet();
            }
            quic.close(true, Http3ErrorCode.H3_EXCESSIVE_LOAD.code(), Unpooled.EMPTY_BUFFER);
            return;
        }
        this.connections.add(quic);
        quic.closeFuture().addListener(f -> {
            this.connections.remove(quic);
            this.connectionCount.decrementAndGet();
        });
        long writeTimeout = this.config.writeTimeout().toNanos();
        quic.pipeline().addLast(new Http3ServerConnectionHandler(new ChannelInitializer<QuicStreamChannel>() {
            @Override
            protected void initChannel(QuicStreamChannel stream) {
                stream.pipeline().addLast(new Http3RequestHandler(Http3Server.this.handler, Http3Server.this.executor,
                        writeTimeout, Http3Server.this.openFiles, Http3Server.this.outcomes));
            }
        }));
        quic.pipeline().addLast(QuietErrors.INSTANCE);
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

    /** @return the TLS material in use (after {@link #start()}) */
    public TlsMaterial tls() {
        return this.tls;
    }

    /** @return QUIC connections currently open */
    public int connections() {
        return this.connections.size();
    }

    /** @return how many responses ended with this outcome (completed, or why the stream was reset) */
    long responses(Http3Writer.Outcome outcome) {
        return this.outcomes.get(outcome).sum();
    }

    /**
     * Closes every connection with H3_NO_ERROR, unbinds the port, interrupts in-flight responses and
     * stops the event loop. Idempotent; safe after a failed start.
     */
    @Override
    public void close() {
        if (!this.closed.compareAndSet(false, true)) {
            return;
        }
        var closing = new ArrayList<io.netty.channel.ChannelFuture>();
        for (var quic : this.connections) {
            closing.add(quic.close(true, Http3ErrorCode.H3_NO_ERROR.code(), Unpooled.EMPTY_BUFFER));
        }
        for (var f : closing) {
            f.awaitUninterruptibly(1, TimeUnit.SECONDS);
        }
        if (this.channel != null) {
            this.channel.close().awaitUninterruptibly(2, TimeUnit.SECONDS);
        }
        if (this.executor != null) {
            this.executor.shutdownNow();
        }
        if (this.group != null) {
            this.group.shutdownGracefully(0, 2, TimeUnit.SECONDS).awaitUninterruptibly(5, TimeUnit.SECONDS);
        }
    }
}
