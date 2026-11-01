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
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.http3.DefaultHttp3HeadersFrame;
import io.netty.handler.codec.http3.Http3;
import io.netty.handler.codec.http3.Http3ClientConnectionHandler;
import io.netty.handler.codec.http3.Http3DataFrame;
import io.netty.handler.codec.http3.Http3HeadersFrame;
import io.netty.handler.codec.http3.Http3RequestStreamInboundHandler;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicSslContext;
import io.netty.handler.codec.quic.QuicSslContextBuilder;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.ReferenceCountUtil;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Minimal in-JVM HTTP/3 client for the tests (Netty's client codec). One instance owns an event
 * loop and a UDP socket; {@link #connect} opens QUIC connections that carry any number of requests.
 */
final class H3TestClient implements AutoCloseable {

    /**
     * One response. {@code reset} = the stream ended without FIN (RESET_STREAM / error).
     * Timing fields (ms since Collector start; -1 = never) diagnose mid-transfer stalls (#313).
     */
    record H3Response(int status, Map<String, String> headers, List<String> headerNames, byte[] body, int dataFrames,
            boolean reset, Throwable error, long elapsedMillis, long firstDataMillis, long lastDataMillis) {

        /** Convenience for tests that build synthetic responses without timing. */
        H3Response(int status, Map<String, String> headers, List<String> headerNames, byte[] body, int dataFrames,
                boolean reset, Throwable error) {
            this(status, headers, headerNames, body, dataFrames, reset, error, -1L, -1L, -1L);
        }

        String header(String name) {
            return this.headers.get(name);
        }

        /** Compact progress for assert messages when a stream resets mid-transfer (#313). */
        String progress() {
            return String.format(java.util.Locale.ROOT,
                    "frames=%d bodyLen=%d elapsedMs=%d firstDataMs=%s lastDataMs=%s err=%s",
                    this.dataFrames, this.body.length, this.elapsedMillis,
                    this.firstDataMillis < 0 ? "never" : Long.toString(this.firstDataMillis),
                    this.lastDataMillis < 0 ? "never" : Long.toString(this.lastDataMillis),
                    this.error);
        }
    }

    private final EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
    private final Channel datagram;
    private final List<QuicChannel> connections = new ArrayList<>();
    private final long idleMillis;

    /**
     * Trusts exactly {@code trusted} (pinned), or anything if null.
     * Default idle is 60 s (was 15 s) so a multi-megabyte range under a loaded CI runner cannot
     * idle the shared QUIC connection mid-transfer (rangeParity flake on #306: {@code stream was
     * reset null} at ~15.08 s). The per-stream receive window stays 1 MiB: backpressure tests rely
     * on filling it with {@code setAutoRead(false)} (#310).
     */
    H3TestClient(X509Certificate trusted) throws InterruptedException {
        this(trusted, 60_000, Http3.supportedApplicationProtocols());
    }

    H3TestClient(X509Certificate trusted, long idleMillis, String... alpn) throws InterruptedException {
        this(trusted, idleMillis, 1_000_000, alpn);
    }

    /** @param streamWindow receive window per request stream (bounds what one read can consume) */
    H3TestClient(X509Certificate trusted, long idleMillis, long streamWindow, String... alpn) throws InterruptedException {
        this.idleMillis = idleMillis;
        var builder = QuicSslContextBuilder.forClient().applicationProtocols(alpn);
        QuicSslContext ssl = (trusted == null ? builder.trustManager(InsecureTrustManagerFactory.INSTANCE)
                : builder.trustManager(trusted)).build();
        var codec = Http3.newQuicClientCodecBuilder().sslContext(ssl)
                // SNI + hostname verification against the certificate's subjectAltName "localhost"
                .sslEngineProvider(q -> ssl.newEngine(q.alloc(), "localhost", 443))
                .maxIdleTimeout(idleMillis, TimeUnit.MILLISECONDS)
                .initialMaxData(10_000_000)
                .initialMaxStreamDataBidirectionalLocal(streamWindow)
                .build();
        this.datagram = new Bootstrap().group(this.group).channel(NioDatagramChannel.class).handler(codec)
                .bind(new InetSocketAddress("127.0.0.1", 0)).sync().channel();
    }

    private static io.netty.handler.codec.quic.QuicChannelBootstrap withQlog(
            io.netty.handler.codec.quic.QuicChannelBootstrap boot) {
        H3Qlog.next("client").ifPresent(q -> boot.option(H3Qlog.OPTION, q));
        return boot;
    }

    /** Like {@link #connect(InetSocketAddress)}, passing every connection-level user event to {@code events}. */
    QuicChannel connect(InetSocketAddress server, java.util.function.Consumer<Object> events) throws Exception {
        var quic = withQlog(QuicChannel.newBootstrap(this.datagram)).handler(new io.netty.channel.ChannelInitializer<QuicChannel>() {
            @Override
            protected void initChannel(QuicChannel ch) {
                ch.pipeline().addLast(new Http3ClientConnectionHandler(), new io.netty.channel.ChannelInboundHandlerAdapter() {
                    @Override
                    public void userEventTriggered(io.netty.channel.ChannelHandlerContext ctx, Object evt) {
                        events.accept(evt);
                        ctx.fireUserEventTriggered(evt);
                    }
                });
            }
        }).remoteAddress(server).localAddress(this.datagram.localAddress()).connect().get(10, TimeUnit.SECONDS);
        this.connections.add(quic);
        return quic;
    }

    /**
     * Like {@link #connect(InetSocketAddress)}, advertising SETTINGS_MAX_FIELD_SECTION_SIZE = {@code maxFieldSection}
     * (Netty's client default is 8 KiB, below a {@code max=256} claim's {@code sjws-lease-stream}).
     */
    QuicChannel connect(InetSocketAddress server, long maxFieldSection) throws Exception {
        var settings = new io.netty.handler.codec.http3.DefaultHttp3SettingsFrame();
        settings.put(io.netty.handler.codec.http3.Http3SettingsFrame.HTTP3_SETTINGS_MAX_FIELD_SECTION_SIZE, maxFieldSection);
        var quic = withQlog(QuicChannel.newBootstrap(this.datagram))
                .handler(new Http3ClientConnectionHandler(null, null, null, settings, true))
                .remoteAddress(server).localAddress(this.datagram.localAddress()).connect().get(10, TimeUnit.SECONDS);
        this.connections.add(quic);
        return quic;
    }

    QuicChannel connect(InetSocketAddress server) throws Exception {
        var quic = withQlog(QuicChannel.newBootstrap(this.datagram)).handler(new Http3ClientConnectionHandler())
                .remoteAddress(server).localAddress(this.datagram.localAddress()).connect().get(10, TimeUnit.SECONDS);
        this.connections.add(quic);
        return quic;
    }

    static CompletableFuture<H3Response> request(QuicChannel quic, String method, String path,
            Map<String, String> headers) {
        return request(quic, method, path, headers, null, null);
    }

    /**
     * @param body request body to send after the headers, or null
     * @param streamHook called with the stream before the request is written (e.g. to stop reading)
     */
    static CompletableFuture<H3Response> request(QuicChannel quic, String method, String path,
            Map<String, String> headers, byte[] body, Consumer<QuicStreamChannel> streamHook) {
        return request(quic, method, path, headers, body, streamHook, false);
    }

    /** @param trailers send a trailing HEADERS frame after the body */
    static CompletableFuture<H3Response> request(QuicChannel quic, String method, String path,
            Map<String, String> headers, byte[] body, Consumer<QuicStreamChannel> streamHook, boolean trailers) {
        var result = new CompletableFuture<H3Response>();
        var collector = new Collector(result);
        Http3.newRequestStream(quic, collector).addListener(f -> {
            if (!f.isSuccess()) {
                result.completeExceptionally(f.cause());
                return;
            }
            var stream = (QuicStreamChannel) f.getNow();
            stream.closeFuture().addListener(c -> collector.finish(true));
            if (streamHook != null) {
                streamHook.accept(stream);
            }
            var frame = new DefaultHttp3HeadersFrame();
            if (method != null) {
                frame.headers().method(method);
            }
            if (path != null) {
                frame.headers().path(path);
            }
            frame.headers().scheme("https").authority("localhost");
            headers.forEach((k, v) -> frame.headers().add(k, v));
            if (body == null) {
                stream.writeAndFlush(frame).addListener(QuicStreamChannel.SHUTDOWN_OUTPUT);
            } else {
                stream.write(frame);
                var data = stream.write(new io.netty.handler.codec.http3.DefaultHttp3DataFrame(
                        io.netty.buffer.Unpooled.wrappedBuffer(body)));
                if (trailers) {
                    var trailer = new DefaultHttp3HeadersFrame();
                    trailer.headers().add("x-checksum", "abc");
                    data = stream.write(trailer);
                }
                stream.flush();
                data.addListener(QuicStreamChannel.SHUTDOWN_OUTPUT);
            }
        });
        return result;
    }

    static H3Response get(QuicChannel quic, String path) throws Exception {
        return request(quic, "GET", path, Map.of()).get(30, TimeUnit.SECONDS);
    }

    @Override
    public void close() {
        for (var quic : this.connections) {
            quic.close().awaitUninterruptibly(2, TimeUnit.SECONDS);
        }
        this.datagram.close().awaitUninterruptibly(2, TimeUnit.SECONDS);
        this.group.shutdownGracefully(0, 1, TimeUnit.SECONDS).awaitUninterruptibly(5, TimeUnit.SECONDS);
    }

    long idleMillis() {
        return this.idleMillis;
    }

    private static final class Collector extends Http3RequestStreamInboundHandler {

        private final CompletableFuture<H3Response> result;
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();
        private final Map<String, String> headers = new LinkedHashMap<>();
        private final List<String> names = new ArrayList<>();
        private int status = -1;
        private int dataFrames;
        private Throwable error;
        private final long startedNanos = System.nanoTime();
        private long firstDataNanos = -1;
        private long lastDataNanos = -1;

        Collector(CompletableFuture<H3Response> result) {
            this.result = result;
        }

        @Override
        protected void channelRead(ChannelHandlerContext ctx, Http3HeadersFrame frame) {
            try {
                if (this.status < 0) {
                    this.status = Integer.parseInt(frame.headers().status().toString());
                    for (var e : frame.headers()) {
                        var name = e.getKey().toString();
                        this.names.add(name);
                        if (!name.startsWith(":")) {
                            this.headers.putIfAbsent(name, e.getValue().toString());
                        }
                    }
                }
            } finally {
                ReferenceCountUtil.release(frame);
            }
        }

        @Override
        protected void channelRead(ChannelHandlerContext ctx, Http3DataFrame frame) {
            try {
                this.dataFrames++;
                long now = System.nanoTime();
                if (this.firstDataNanos < 0) {
                    this.firstDataNanos = now;
                }
                this.lastDataNanos = now;
                var content = frame.content();
                var bytes = new byte[content.readableBytes()];
                content.readBytes(bytes);
                this.body.writeBytes(bytes);
            } finally {
                ReferenceCountUtil.release(frame);
            }
        }

        @Override
        protected void channelInputClosed(ChannelHandlerContext ctx) {
            finish(false);
            ctx.close();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            this.error = cause;
            finish(true);
            ctx.close();
        }

        void finish(boolean reset) {
            long now = System.nanoTime();
            long elapsed = (now - this.startedNanos) / 1_000_000L;
            long first = this.firstDataNanos < 0 ? -1L : (this.firstDataNanos - this.startedNanos) / 1_000_000L;
            long last = this.lastDataNanos < 0 ? -1L : (this.lastDataNanos - this.startedNanos) / 1_000_000L;
            this.result.complete(new H3Response(this.status, Map.copyOf(this.headers), List.copyOf(this.names),
                    this.body.toByteArray(), this.dataFrames, reset, this.error, elapsed, first, last));
        }
    }
}
