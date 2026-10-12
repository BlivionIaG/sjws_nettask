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
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.DefaultHttp2ResetFrame;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Stream;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.util.ReferenceCountUtil;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import sjws_nettask.core.DateClock;
import sjws_nettask.core.Request;
import sjws_nettask.core.RequestHeaders;
import sjws_nettask.core.RequestRules;
import sjws_nettask.core.Response;
import sjws_nettask.core.ServerInfo;
import sjws_nettask.core.StaticFileHandler;

/**
 * One instance per HTTP/2 stream (child channel of {@code Http2MultiplexHandler}). Maps the first
 * HEADERS frame to a core {@link Request}, runs the shared handler and the {@link ResponseWriter} on a
 * virtual thread, and drops request bodies (DATA is released; the multiplexer returns the window).
 * A stream closed by the peer (RST_STREAM) or by the connection interrupts its task.
 */
final class Http2StreamHandler extends ChannelInboundHandlerAdapter {

    private final Http2Server.Shared shared;
    private boolean started;

    Http2StreamHandler(Http2Server.Shared shared) {
        this.shared = shared;
    }

    /** Pseudo-headers and the fields the core understands; :authority wins over a host field. */
    static Request toRequest(Http2Headers h) {
        var method = h.method();
        var path = h.path();
        var authority = h.authority();
        return new Request(method == null ? "" : method.toString(), path == null ? "" : path.toString(),
                new RequestHeaders(first(h, "if-none-match"), first(h, "if-modified-since"), first(h, "if-range"),
                        first(h, "range"), first(h, "accept-encoding"),
                        authority != null ? authority.toString() : first(h, "host")));
    }

    private static String first(Http2Headers h, String name) {
        var v = h.get(name);
        return v == null ? null : v.toString();
    }

    /** Same request-line rules as HTTP/1.1 and HTTP/3: unknown method 501, bad target 400, too long 414. */
    static Response refusal(Request request) {
        if (request.target().isEmpty()) {
            return StaticFileHandler.error(400, request.headOnly());
        }
        int status = RequestRules.check(request.method(), request.target());
        return status == 0 ? null : StaticFileHandler.error(status, request.headOnly());
    }

    static Http2Headers headers(Response response, String date) {
        var h = new DefaultHttp2Headers();
        h.status(Integer.toString(response.status()));
        h.add("server", ServerInfo.NAME);
        h.add("date", date);
        for (var e : response.headers().entries()) {
            if (!ResponseWriter.NOT_COPIED.contains(e.getKey())) {
                h.add(e.getKey(), e.getValue());
            }
        }
        if (response.mayHaveBody()) {
            h.add("content-length", Long.toString(response.contentLength()));
        }
        return h;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        try {
            if (msg instanceof Http2HeadersFrame frame && !this.started) {
                this.started = true;
                start((Http2StreamChannel) ctx.channel(), toRequest(frame.headers()));
            }
        } finally {
            ReferenceCountUtil.release(msg);
        }
    }

    private void start(Http2StreamChannel stream, Request request) {
        if (!this.shared.inflight().tryAcquire()) {
            this.shared.refused().increment();
            stream.writeAndFlush(new DefaultHttp2ResetFrame(Http2Error.REFUSED_STREAM));
            return;
        }
        var task = new Task(stream, request);
        try {
            this.shared.executor().execute(task);
        } catch (RejectedExecutionException e) { // shutting down
            task.finish();
            stream.writeAndFlush(new DefaultHttp2ResetFrame(Http2Error.REFUSED_STREAM));
            return;
        }
        stream.closeFuture().addListener(f -> task.cancel());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        // A connection error is also delivered to every stream; the codec answers it (GOAWAY with its
        // error code, then close). Closing the stream here first would make it a NO_ERROR shutdown.
        var h2 = io.netty.handler.codec.http2.Http2CodecUtil.getEmbeddedHttp2Exception(cause);
        if (h2 == null || h2 instanceof io.netty.handler.codec.http2.Http2Exception.StreamException) {
            ctx.close(); // a stream error: resetting the stream is all a remote peer can make us do
        }
    }

    private final class Task implements Runnable, ResponseWriter.Sink {

        private final Http2StreamChannel stream;
        private final Request request;
        private final AtomicBoolean finished = new AtomicBoolean();
        private volatile Thread thread;
        private volatile boolean cancelled;

        Task(Http2StreamChannel stream, Request request) {
            this.stream = stream;
            this.request = request;
        }

        void cancel() {
            this.cancelled = true;
            var t = this.thread;
            if (t != null) {
                t.interrupt();
            }
        }

        @Override
        public void run() {
            this.thread = Thread.currentThread();
            try {
                if (this.cancelled || !this.stream.isActive()) {
                    return;
                }
                var refusal = refusal(this.request);
                Response response;
                try {
                    response = refusal != null ? refusal : Http2StreamHandler.this.shared.handler().handle(this.request);
                } catch (RuntimeException e) {
                    response = StaticFileHandler.error(500, this.request.headOnly());
                }
                var outcome = new ResponseWriter(this, Http2StreamHandler.this.shared.writeTimeout(),
                        Http2StreamHandler.this.shared.files()).write(response);
                if (outcome == ResponseWriter.Outcome.COMPLETE) {
                    endRequest();
                }
            } finally {
                this.thread = null;

                finish();
            }
        }

        /**
         * The response is complete but the peer never ended its side (request body we don't read):
         * RST_STREAM(NO_ERROR) as RFC 9113 §8.1 allows, so a half-open stream can't hold the
         * connection's idle timer and a concurrency slot forever.
         */
        private void endRequest() {
            var state = this.stream.stream().state();
            if (state == Http2Stream.State.OPEN || state == Http2Stream.State.HALF_CLOSED_LOCAL) {
                this.stream.writeAndFlush(new DefaultHttp2ResetFrame(Http2Error.NO_ERROR));
            }
        }

        void finish() {
            if (this.finished.compareAndSet(false, true)) {
                Http2StreamHandler.this.shared.inflight().release();
            }
        }

        @Override
        public ChannelFuture head(Response response, boolean endOfResponse) {
            var frame = new DefaultHttp2HeadersFrame(headers(response, DateClock.SYSTEM.now()), endOfResponse);
            return endOfResponse ? this.stream.writeAndFlush(frame) : this.stream.write(frame);
        }

        @Override
        public ChannelFuture data(ByteBuf data, boolean endOfResponse) {
            return this.stream.writeAndFlush(new DefaultHttp2DataFrame(data, endOfResponse));
        }

        @Override
        public boolean active() {
            return this.stream.isActive();
        }

        @Override
        public ByteBufAllocator alloc() {
            return this.stream.alloc();
        }

        @Override
        public void abort(ResponseWriter.Outcome why) {
            if (this.stream.isActive()) {
                this.stream.writeAndFlush(new DefaultHttp2ResetFrame(
                        why == ResponseWriter.Outcome.REJECTED ? Http2Error.REFUSED_STREAM : Http2Error.INTERNAL_ERROR));
            }
        }
    }
}
