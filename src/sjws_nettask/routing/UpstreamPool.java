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

    /** No response head within the response timeout (a 504, never retried). */
    static final class HeadTimeout extends IOException {
        HeadTimeout() {
            super("no response head in time");
        }
    }

    /** The connection could not be opened (refused, unreachable, TLS failure): a 502. */
    static final class ConnectFailed extends IOException {
        ConnectFailed(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Connect timeouts surface as this (mapped to 504). */
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
    private volatile boolean retired;

    private final ByteBudget budget;

    UpstreamPool(URI upstream, Duration connectTimeout, int maxIdle, ByteBudget budget) {
        this.budget = java.util.Objects.requireNonNull(budget);
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
                // start at 16 KiB and grow to 128 KiB: big bodies move in few large chunks (fewer hand-offs and writes)
                .option(ChannelOption.RCVBUF_ALLOCATOR, new ReadSizes())
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) Math.min(Integer.MAX_VALUE, connectTimeout.toMillis()))
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        if (sslContext != null) {
                            ch.pipeline().addLast(sslContext.newHandler(ch.alloc(), UpstreamPool.this.host,
                                    UpstreamPool.this.port));
                        }
                        ch.pipeline().addLast(new HttpClientCodec(4096, 64 * 1024, 128 * 1024), new Relay());
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
            throw new ConnectFailed("connect to " + this.host + ":" + this.port + ": " + cause, cause);
        }
    }

    /** Returns a channel whose exchange ended cleanly; anything else is closed. */
    void release(Channel ch, boolean reusable) {
        ch.attr(Relay.CURRENT).set(null);
        if (reusable && !this.closed && !this.retired && ch.isActive() && this.idle.size() < this.maxIdle) {
            this.idle.offerFirst(ch);
            if ((this.closed || this.retired) && this.idle.remove(ch)) { // lost a race with close() or retire()
                ch.close();
            }
        } else {
            ch.close();
        }
    }

    ByteBudget budget() {
        return this.budget;
    }

    int idleCount() {
        return this.idle.size();
    }

    /**
     * Stops keeping connections: idle ones close now and each exchange in flight closes its own when
     * it ends. New exchanges still connect. For an endpoint that left the set, so its idle sockets do
     * not linger on the upstream until {@link #close()}. Idempotent.
     */
    void retire() {
        this.retired = true;
        for (var ch = this.idle.pollFirst(); ch != null; ch = this.idle.pollFirst()) {
            ch.close();
        }
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

    /** Largest single socket read: also the per-exchange overshoot of the budget (see {@link ByteBudget}). */
    static final int MAX_READ = 128 * 1024;
    /** Read size while the budget is under pressure (at least 3/4 used). */
    static final int SMALL_READ = 16 * 1024;

    /**
     * Adaptive reads of up to {@link #MAX_READ}, one socket read per {@code read()} call, shrunk to
     * {@link #SMALL_READ} while {@link Reads#small} is set (Netty caches the handle per channel, so
     * the size is switched on the handle, not by replacing the allocator).
     */
    static final class ReadSizes implements io.netty.channel.RecvByteBufAllocator {
        private final io.netty.channel.AdaptiveRecvByteBufAllocator adaptive;

        ReadSizes() {
            this.adaptive = new io.netty.channel.AdaptiveRecvByteBufAllocator(4096, 16384, MAX_READ);
            this.adaptive.maxMessagesPerRead(1);
        }

        @Override
        public Handle newHandle() {
            return new Reads(this.adaptive.newHandle());
        }
    }

    static final class Reads extends io.netty.channel.RecvByteBufAllocator.DelegatingHandle {
        /** Event loop only. */
        boolean small;

        Reads(io.netty.channel.RecvByteBufAllocator.Handle delegate) {
            super(delegate);
        }

        @Override
        public int guess() {
            int g = delegate().guess();
            return this.small ? Math.min(g, SMALL_READ) : g;
        }

        @Override
        public io.netty.buffer.ByteBuf allocate(io.netty.buffer.ByteBufAllocator alloc) {
            return alloc.ioBuffer(guess());
        }
    }

    /** Body bytes the event loop may queue ahead of the request thread before it stops reading. */
    static final int READ_AHEAD = 256 * 1024;

    /**
     * One request/response on a channel. The event loop fills {@link #queue} and keeps reading while
     * fewer than {@link #READ_AHEAD} bytes are queued; past that it pauses, and the request thread
     * resumes it with {@code channel.read()} once it has drained half (bounded-window backpressure).
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
        /** Bytes offered by the event loop and not yet taken. */
        final java.util.concurrent.atomic.AtomicLong queued = new java.util.concurrent.atomic.AtomicLong();
        /** Set by the event loop when it stopped reading because the window was full. */
        volatile boolean paused;
        /** Event loop only: LastHttpContent was seen, nothing more to read for this exchange. */
        boolean complete;

        private final long idleNanos;
        /** Last time body bytes arrived, for the idle timer. */
        volatile long lastRead;
        /** Last time the consumer took a chunk; a paused exchange nobody takes from is abandoned. */
        volatile long lastTake;
        /** Event loop only: the pending head or idle timer. */
        private io.netty.util.concurrent.Future<?> timer;

        final ByteBudget budget;

        Exchange(Channel channel, Duration idle, ByteBudget budget) {
            this.channel = channel;
            this.budget = budget;
            this.idleNanos = idle.toNanos();
        }

        /** Binds the exchange and starts reading; the request is written by the caller (it has a body). */
        void start() {
            this.channel.attr(Relay.CURRENT).set(this);
            this.channel.read();
        }

        /**
         * Binds the exchange, writes a bodyless request, starts reading and arms the head timer, all
         * in one event-loop task (one hand-off per request instead of three).
         */
        void send(io.netty.handler.codec.http.HttpRequest request, Duration headTimeout) {
            this.channel.attr(Relay.CURRENT).set(this);
            this.channel.eventLoop().execute(() -> {
                this.channel.writeAndFlush(request).addListener(this::writeDone);
                this.channel.read();
                armHead(headTimeout);
            });
        }

        /** After the caller wrote the request body: wait at most {@code headTimeout} for the head. */
        void armHeadTimer(Duration headTimeout) {
            this.channel.eventLoop().execute(() -> armHead(headTimeout));
        }

        private void writeDone(io.netty.util.concurrent.Future<? super Void> f) {
            if (!f.isSuccess()) {
                Relay.fail(this, new IOException("upstream write failed: " + f.cause(), f.cause()));
            }
        }

        private void armHead(Duration headTimeout) {
            if (!this.head.isDone()) {
                this.timer = this.channel.eventLoop().schedule(() -> {
                    if (!this.head.isDone()) {
                        Relay.fail(this, new HeadTimeout());
                    }
                }, headTimeout.toNanos(), TimeUnit.NANOSECONDS);
            }
        }

        /** Event loop: the head arrived; from now on the idle timer guards the gaps between body bytes. */
        void headArrived() {
            cancelTimer();
            this.lastRead = System.nanoTime();
            this.lastTake = this.lastRead;
            if (!this.complete) {
                this.timer = this.channel.eventLoop().schedule(this::idleCheck, this.idleNanos, TimeUnit.NANOSECONDS);
            }
        }

        private void idleCheck() {
            if (this.complete || this.abandoned) {
                return;
            }
            long now = System.nanoTime();
            if (this.paused) { // the client is the slow side; its write timeout applies, not ours
                if (now - this.lastTake >= 2 * this.idleNanos) {
                    // backstop: the response was dropped without being written or closed (or the writer
                    // stalled for twice the idle read timeout). Release the budget and the connection.
                    Relay.fail(this, new IOException("streamed body not consumed for " + Duration.ofNanos(2 * this.idleNanos)));
                    drain();
                    this.channel.close();
                    return;
                }
                this.lastRead = now;
            }
            long quiet = now - this.lastRead;
            if (quiet >= this.idleNanos) {
                Relay.fail(this, new IOException("upstream sent nothing for " + Duration.ofNanos(this.idleNanos)));
                this.channel.close();
                return;
            }
            this.timer = this.channel.eventLoop().schedule(this::idleCheck, this.idleNanos - quiet, TimeUnit.NANOSECONDS);
        }

        /** Event loop only. */
        void cancelTimer() {
            if (this.timer != null) {
                this.timer.cancel(false);
                this.timer = null;
            }
        }

        /** Next content chunk, or {@link #END}; throws on failure (including the idle timer firing). */
        Object take() throws IOException, InterruptedException {
            this.lastTake = System.nanoTime();
            var next = this.queue.take();
            if (next instanceof ByteBuf buf) {
                int n = buf.readableBytes();
                this.budget.release(n);
                long left = this.queued.addAndGet(-n);
                if (this.paused && (left == 0 || left < READ_AHEAD / 2 && !this.budget.exhausted())) {
                    this.paused = false;
                    this.channel.read();
                }
            } else if (next instanceof IOException failure) { // Relay.fail only queues IOExceptions
                throw failure;
            } else if (next == END) {
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
                if (o instanceof ByteBuf buf) {
                    this.budget.release(buf.readableBytes());
                    this.queued.addAndGet(-buf.readableBytes());
                }
                ReferenceCountUtil.release(o);
            }
        }
    }

    /** Event-loop side: hands decoded messages to the channel's current exchange. */
    static final class Relay extends ChannelInboundHandlerAdapter {
        /** Event loop only: this channel currently reads {@link #SMALL_READ} at a time. */
        private boolean small;

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
                ex.headArrived();
            }
            if (msg instanceof HttpContent content) {
                ByteBuf data = content.content();
                if (data.isReadable()) {
                    ex.lastRead = System.nanoTime();
                    ex.queued.addAndGet(data.readableBytes());
                    ex.budget.add(data.readableBytes());
                    ex.queue.offer(data.retain());
                }
                if (content instanceof LastHttpContent) {
                    ex.complete = true;
                    ex.cancelTimer();
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
            if (ex == null || ex.abandoned || ex.complete) {
                return;
            }
            boolean pressure = ex.budget.pressured();
            if (pressure != this.small // shrink reads under budget pressure: bounds the overshoot
                    && ctx.channel().unsafe().recvBufAllocHandle() instanceof Reads reads) {
                this.small = pressure;
                reads.small = pressure;
            }
            long q = ex.queued.get();
            boolean full = q >= READ_AHEAD || q > 0 && ex.budget.exhausted(); // window, or the global F5 budget
            if (!ex.head.isDone() || !full) {
                ctx.read(); // read ahead while there is room, without waiting for the consumer
            } else {
                ex.paused = true;
                q = ex.queued.get();
                if (q == 0 || q < READ_AHEAD / 2 && !ex.budget.exhausted()) { // drained between check and flag
                    ex.paused = false;
                    ctx.read();
                }
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

        static void fail(Exchange ex, IOException cause) {
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
