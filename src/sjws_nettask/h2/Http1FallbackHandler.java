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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import java.net.InetSocketAddress;
import java.util.concurrent.RejectedExecutionException;
import sjws_nettask.core.DateClock;
import sjws_nettask.core.Request;
import sjws_nettask.core.RequestHeaders;
import sjws_nettask.core.Response;
import sjws_nettask.core.ServerInfo;
import sjws_nettask.core.StaticFileHandler;
import sjws_nettask.http1.Http1Writer;

/**
 * HTTP/1.1 on the HTTP/2 listener: TLS clients that don't offer h2 in ALPN, and h2c-port clients
 * that neither upgrade nor send the prior-knowledge preface. Requests on a connection are served one
 * at a time (reading pauses while a response is written), through the same core handler and
 * {@link ResponseWriter}. Pins the published {@link Http2Server.Generation} with enter/leave so
 * deferred snapshot close waits for fallback work too (B2 / #301). This is not the main HTTP/1.1
 * server ({@code Http1Connection}), which is untouched by HTTP/2.
 */
final class Http1FallbackHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    /** Test-only: runs in {@link Task#run} before the handler (B2). Production never sets it. */
    static volatile Runnable beforeDispatch;

    /** Pipelined requests decoded in the same read as one being served (they are answered in order). */
    static final int MAX_PIPELINED = 16;

    private final Http2Server.Shared shared;
    private final java.util.ArrayDeque<Task> pending = new java.util.ArrayDeque<>(); // event loop only
    private boolean busy; // event loop only

    Http1FallbackHandler(Http2Server.Shared shared) {
        this.shared = shared;
    }

    static Request toRequest(FullHttpRequest r) {
        return toRequest(r, null, false);
    }

    static Request toRequest(FullHttpRequest r, InetSocketAddress peer, boolean secure) {
        var h = r.headers();
        return new Request(r.method().name(), r.uri(), new RequestHeaders(h.get("if-none-match"),
                h.get("if-modified-since"), h.get("if-range"), h.get("range"), h.get("accept-encoding"), h.get("host")),
                peer, secure, r.protocolVersion().text());
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest msg) {
        var channel = ctx.channel();
        var remote = channel.remoteAddress();
        var request = toRequest(msg, remote instanceof InetSocketAddress inet ? inet : null,
                channel.pipeline().get(io.netty.handler.ssl.SslHandler.class) != null);
        boolean keepAlive = HttpUtil.isKeepAlive(msg);
        Response refusal;
        if (msg.decoderResult().isFailure()) {
            refusal = StaticFileHandler.error(400, false);
            keepAlive = false;
        } else if (msg.protocolVersion().equals(HttpVersion.HTTP_1_1) && msg.headers().getAll(HttpHeaderNames.HOST).size() != 1
                || msg.headers().getAll(HttpHeaderNames.HOST).size() > 1) {
            refusal = StaticFileHandler.error(400, request.headOnly());
            keepAlive = false;
        } else {
            refusal = Http2StreamHandler.refusal(request);
        }
        channel.config().setAutoRead(false); // one request at a time; reading resumes when idle again
        var task = new Task(channel, request, refusal, keepAlive);
        if (this.busy) {
            if (this.pending.size() >= MAX_PIPELINED) {
                channel.close();
                return;
            }
            this.pending.add(task);
            return;
        }
        run(task);
    }

    private void run(Task task) {
        this.busy = true;
        Http2Server.Generation generation;
        for (;;) {
            generation = this.shared.generation();
            if (generation.tryEnter()) {
                break;
            }
        }
        task.generation = generation;
        if (!this.shared.inflight().tryAcquire()) { // the same server-wide cap as HTTP/2 streams
            generation.leave();
            this.shared.refused().increment();
            task.channel.writeAndFlush(unavailable()).addListener(ChannelFutureListener.CLOSE);
            return;
        }
        try {
            this.shared.executor().execute(task);
        } catch (RejectedExecutionException e) {
            this.shared.inflight().release();
            generation.leave();
            task.channel.close();
        }
    }

    /** 503 with Retry-After, then the connection closes (server busy). */
    static DefaultFullHttpResponse unavailable() {
        var r = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.SERVICE_UNAVAILABLE);
        r.headers().add("Server", ServerInfo.NAME).add("Date", DateClock.SYSTEM.now())
                .add("Content-Length", "0").add("Retry-After", "1").add("Connection", "close");
        return r;
    }

    /** On the event loop, after a response was written completely (and the connection stays open). */
    private void next(Channel channel) {
        var task = this.pending.poll();
        if (task != null) {
            run(task);
            return;
        }
        this.busy = false;
        channel.config().setAutoRead(true);
        channel.read();
    }

    /** h2c: once the connection switched to HTTP/2, the HTTP/1 handlers behind the codec must go. */
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof io.netty.handler.codec.http.HttpServerUpgradeHandler.UpgradeEvent
                || evt instanceof io.netty.handler.codec.http2.CleartextHttp2ServerUpgradeHandler.PriorKnowledgeUpgradeEvent) {
            var p = ctx.pipeline();
            for (var name : new String[] {"expect-continue", "h1-aggregator"}) {
                if (p.get(name) != null) {
                    p.remove(name);
                }
            }
            p.remove(this);
        }
        ctx.fireUserEventTriggered(evt);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        ctx.close();
    }

    private final class Task implements Runnable, ResponseWriter.Sink {

        private final Channel channel;
        private final Request request;
        private final Response refusal;
        private final boolean keepAlive;
        /** Set in {@link #run(Task)} after {@link Http2Server.Generation#tryEnter}. */
        Http2Server.Generation generation;

        Task(Channel channel, Request request, Response refusal, boolean keepAlive) {
            this.channel = channel;
            this.request = request;
            this.refusal = refusal;
            this.keepAlive = keepAlive;
        }

        @Override
        public void run() {
            var thread = Thread.currentThread();
            ChannelFutureListener interrupt = f -> thread.interrupt();
            this.channel.closeFuture().addListener(interrupt);
            ResponseWriter.Outcome outcome = ResponseWriter.Outcome.FAILED;
            try {
                var gate = beforeDispatch;
                if (gate != null) {
                    gate.run();
                }
                Response response;
                try {
                    response = this.refusal != null ? this.refusal
                            : this.generation.handler().handle(this.request);
                } catch (RuntimeException e) {
                    response = StaticFileHandler.error(500, this.request.headOnly());
                }
                var shared = Http1FallbackHandler.this.shared;
                outcome = new ResponseWriter(this, shared.writeTimeout(), null, shared.files(),
                        shared.limits().fileWait(), shared.writeTimeout()).write(response);
            } finally {
                Http1FallbackHandler.this.shared.inflight().release();
                if (this.generation != null) {
                    this.generation.leave();
                }
                this.channel.closeFuture().removeListener(interrupt);
                if (outcome == ResponseWriter.Outcome.COMPLETE && this.keepAlive && this.refusal == null) {
                    this.channel.eventLoop().execute(() -> next(this.channel));
                } else {
                    this.channel.close();
                }
            }
        }

        @Override
        public ChannelFuture head(Response response, boolean endOfResponse) {
            var r = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(response.status()));
            var h = r.headers();
            h.add("Server", ServerInfo.NAME);
            h.add("Date", DateClock.SYSTEM.now());
            for (var e : response.headers().entries()) {
                if (!ResponseWriter.NOT_COPIED.contains(e.getKey())) {
                    h.add(Http1Writer.canonicalName(e.getKey()), e.getValue());
                }
            }
            h.add("Connection", this.keepAlive && this.refusal == null ? "keep-alive" : "close");
            if (response.mayHaveBody() && response.contentLength() >= 0) {
                h.add("Content-Length", Long.toString(response.contentLength()));
            } else if (response.mayHaveBody() && !response.headOnly()) {
                h.add("Transfer-Encoding", "chunked"); // Body.Stream of unknown length
            }
            if (endOfResponse) {
                this.channel.write(r);
                return this.channel.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
            }
            return this.channel.write(r);
        }

        @Override
        public ChannelFuture data(ByteBuf data, boolean endOfResponse) {
            return this.channel.writeAndFlush(endOfResponse ? new DefaultLastHttpContent(data) : new DefaultHttpContent(data));
        }

        @Override
        public boolean active() {
            return this.channel.isActive();
        }

        @Override
        public ByteBufAllocator alloc() {
            return this.channel.alloc();
        }

        @Override
        public void abort(ResponseWriter.Outcome why) {
            if (why == ResponseWriter.Outcome.REJECTED) { // no file slot: nothing was sent yet
                this.channel.writeAndFlush(unavailable()).addListener(ChannelFutureListener.CLOSE);
                return;
            }
            this.channel.close();
        }
    }
}
