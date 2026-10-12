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
package sjws_nettask.routing;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.util.ReferenceCountUtil;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import javax.net.ssl.SSLException;

/**
 * Keep-alive HTTP/1.1 connections to one upstream, on a Netty event-loop group shared by every
 * pool in the process (reference-counted: created with the first pool, shut down with the last).
 * <p>
 * Channels run with {@code autoRead} off: the request thread asks for the next socket read only
 * when it has consumed what arrived, so a slow client slows the upstream read (backpressure).
 * One {@link Exchange} runs on a channel at a time; a channel goes back to the idle stack only
 * after its response ended cleanly with keep-alive.
 */
final class UpstreamPool implements AutoCloseable {

    /** Connect timeouts surface as this (mapped to 504); other connect failures are plain IOExceptions (502). */
    static final class ConnectTimeout extends IOException {
        ConnectTimeout(String message) {
            super(message);
        }
    }

    private static final ReentrantLock LOOPS_LOCK = new ReentrantLock();
    private static EventLoopGroup loops;
    private static int users;

    private final String host;
    private final int port;
    private final Duration connectTimeout;
    private final int maxIdle;
    private final ConcurrentLinkedDeque<Channel> idle = new ConcurrentLinkedDeque<>();
    private final Bootstrap bootstrap;
    private volatile boolean closed;

    UpstreamPool(URI upstream, Duration connectTimeout, int maxIdle) {
        boolean tls = "https".equals(upstream.getScheme());
        this.host = upstream.getHost();
        this.port = upstream.getPort() > 0 ? upstream.getPort() : tls ? 443 : 80;
        this.connectTimeout = connectTimeout;
        this.maxIdle = maxIdle;
        SslContext ssl = null;
        if (tls) {
            try {
                ssl = SslContextBuilder.forClient().endpointIdentificationAlgorithm("HTTPS").build();
            } catch (SSLException e) {
                throw new IllegalStateException("TLS client context: " + e.getMessage(), e);
            }
        }
        var sslContext = ssl;
        this.bootstrap = new Bootstrap().group(acquireLoops()).channel(NioSocketChannel.class)
                .option(ChannelOption.AUTO_READ, false)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) Math.min(Integer.MAX_VALUE, connectTimeout.toMillis()))
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        if (sslContext != null) {
                            ch.pipeline().addLast(sslContext.newHandler(ch.alloc(), UpstreamPool.this.host,
                                    UpstreamPool.this.port));
                        }
                        ch.pipeline().addLast(new HttpClientCodec(4096, 64 * 1024, 64 * 1024), new Relay());
                    }
                });
    }

    private static EventLoopGroup acquireLoops() {
        LOOPS_LOCK.lock();
        try {
            if (users++ == 0) {
                loops = new MultiThreadIoEventLoopGroup(Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
                        Thread.ofPlatform().daemon().name("upstream-io-", 0).factory(), NioIoHandler.newFactory());
            }
            return loops;
        } finally {
            LOOPS_LOCK.unlock();
        }
    }

    private static void releaseLoops() {
        LOOPS_LOCK.lock();
        try {
            if (--users == 0) {
                loops.shutdownGracefully(0, 2, TimeUnit.SECONDS);
                loops = null;
            }
        } finally {
            LOOPS_LOCK.unlock();
        }
    }

    /** @return a pooled channel if one is open, else a new connection; {@code reused} says which */
    Lease acquire() throws IOException, InterruptedException {
        if (this.closed) {
            throw new IOException("upstream pool closed");
        }
        for (var ch = this.idle.pollFirst(); ch != null; ch = this.idle.pollFirst()) {
            if (ch.isActive()) {
                return new Lease(ch, true);
            }
        }
        var connected = new CompletableFuture<Channel>();
        this.bootstrap.connect(this.host, this.port).addListener(f -> {
            if (f.isSuccess()) {
                connected.complete(((io.netty.channel.ChannelFuture) f).channel());
            } else {
                connected.completeExceptionally(f.cause());
            }
        });
        try {
            return new Lease(connected.get(this.connectTimeout.toMillis() + 1000, TimeUnit.MILLISECONDS), false);
        } catch (TimeoutException e) {
            throw new ConnectTimeout("connect to " + this.host + ":" + this.port + " timed out");
        } catch (ExecutionException e) {
            var cause = e.getCause();
            if (cause instanceof io.netty.channel.ConnectTimeoutException) {
                throw new ConnectTimeout(cause.getMessage());
            }
            throw new IOException("connect to " + this.host + ":" + this.port + ": " + cause, cause);
        }
    }

    /** Returns a channel whose exchange ended cleanly; anything else is closed. */
    void release(Channel ch, boolean reusable) {
        ch.attr(Relay.CURRENT).set(null);
        if (reusable && !this.closed && ch.isActive() && this.idle.size() < this.maxIdle) {
            this.idle.offerFirst(ch);
            if (this.closed && this.idle.remove(ch)) { // lost a race with close()
                ch.close();
            }
        } else {
            ch.close();
        }
    }

    int idleCount() {
        return this.idle.size();
    }

    /** Closes idle connections; exchanges in flight finish and then close theirs. Idempotent. */
    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        for (var ch = this.idle.pollFirst(); ch != null; ch = this.idle.pollFirst()) {
            ch.close();
        }
        releaseLoops();
    }

    record Lease(Channel channel, boolean reused) {
    }

    /** End-of-response and failure markers in an exchange's queue. */
    private static final Object END = new Object();

    /**
     * One request/response on a channel. The event loop fills {@link #queue}; the request thread
     * drains it and asks for more with {@code channel.read()} only when it is empty.
     */
    static final class Exchange {
        final Channel channel;
        final CompletableFuture<HttpResponse> head = new CompletableFuture<>();
        final LinkedBlockingQueue<Object> queue = new LinkedBlockingQueue<>();
        volatile boolean abandoned;
        boolean ended;
        /** Event loop only: inside a 1xx interim response that is being skipped. */
        boolean interim;
        boolean keepAlive;

        Exchange(Channel channel) {
            this.channel = channel;
        }

        void start() {
            this.channel.attr(Relay.CURRENT).set(this);
            this.channel.read();
        }

        /** Next content chunk, or {@link #END}; throws on failure or when no byte came within {@code idle}. */
        Object take(Duration idle) throws IOException, InterruptedException {
            var next = this.queue.poll();
            if (next == null) {
                this.channel.read();
                next = this.queue.poll(idle.toNanos(), TimeUnit.NANOSECONDS);
                if (next == null) {
                    throw new IOException("upstream sent nothing for " + idle);
                }
            }
            if (next instanceof Throwable t) {
                throw t instanceof IOException io ? io : new IOException(t.toString(), t);
            }
            if (next == END) {
                this.ended = true;
            }
            return next;
        }

        /** Drops queued buffers; the channel is then unusable unless the response ended. */
        void abandon() {
            this.abandoned = true;
            drain();
        }

        void drain() {
            for (var o = this.queue.poll(); o != null; o = this.queue.poll()) {
                ReferenceCountUtil.release(o);
            }
        }
    }

    /** Event-loop side: hands decoded messages to the channel's current exchange. */
    static final class Relay extends ChannelInboundHandlerAdapter {
        static final io.netty.util.AttributeKey<Exchange> CURRENT = io.netty.util.AttributeKey.valueOf("sjws.exchange");

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            var ex = ctx.channel().attr(CURRENT).get();
            if (ex == null || ex.abandoned) {
                ReferenceCountUtil.release(msg);
                return;
            }
            if (ex.interim || msg instanceof HttpResponse r && r.status().code() < 200 && r.status().code() != 101
                    && !r.decoderResult().isFailure()) {
                // 1xx interim (100 Continue, 103 Early Hints): skip it whole and wait for the final response
                ex.interim = !(msg instanceof LastHttpContent);
                ReferenceCountUtil.release(msg);
                return;
            }
            if (msg instanceof HttpResponse response) {
                if (response.decoderResult().isFailure()) {
                    fail(ex, new IOException("malformed upstream response: " + response.decoderResult().cause()));
                    ReferenceCountUtil.release(msg);
                    ctx.close();
                    return;
                }
                ex.head.complete(response);
            }
            if (msg instanceof HttpContent content) {
                ByteBuf data = content.content();
                if (data.isReadable()) {
                    ex.queue.offer(data.retain());
                }
                if (content instanceof LastHttpContent) {
                    ex.queue.offer(END);
                }
                content.release();
            }
            if (ex.abandoned) {
                ex.drain();
            }
        }

        @Override
        public void channelReadComplete(ChannelHandlerContext ctx) {
            var ex = ctx.channel().attr(CURRENT).get();
            if (ex != null && !ex.head.isDone()) {
                ctx.read(); // the head is still incomplete: keep reading without waiting for the consumer
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            var ex = ctx.channel().attr(CURRENT).get();
            if (ex != null) {
                fail(ex, new IOException("upstream closed the connection"));
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            var ex = ctx.channel().attr(CURRENT).get();
            if (ex != null) {
                fail(ex, cause instanceof IOException io ? io : new IOException(cause.toString(), cause));
            }
            ctx.close();
        }

        private static void fail(Exchange ex, IOException cause) {
            if (!ex.head.isDone()) {
                ex.head.completeExceptionally(cause);
            }
            ex.queue.offer(cause);
            if (ex.abandoned) {
                ex.drain();
            }
        }
    }

    static boolean isEnd(Object o) {
        return o == END;
    }

    static ByteBuffer view(ByteBuf buf) {
        return buf.nioBufferCount() == 1 ? buf.nioBuffer() : ByteBuffer.wrap(io.netty.buffer.ByteBufUtil.getBytes(buf));
    }
}
