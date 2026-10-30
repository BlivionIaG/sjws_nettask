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
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Stream;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.util.ReferenceCountUtil;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import sjws_nettask.core.DateClock;
import sjws_nettask.core.Handler;
import sjws_nettask.core.Request;
import sjws_nettask.core.RequestHeaders;
import sjws_nettask.core.RequestRules;
import sjws_nettask.core.Response;
import sjws_nettask.core.ServerInfo;
import sjws_nettask.core.StaticFileHandler;

/**
 * One instance per HTTP/2 stream (child channel of {@code Http2MultiplexHandler}). Maps the first
 * HEADERS frame to a core {@link Request}, runs the shared handler and the {@link ResponseWriter} on a
 * virtual thread. Request content (#289): for a method other than GET/HEAD when bodies are enabled,
 * DATA frames feed an {@link Http2RequestBody}; the stream stops reading while the handler is behind
 * (flow-control window held). Size limits are the handler's. Trailers and unread content are released.
 * A stream closed by the peer (RST_STREAM) or by the connection interrupts its task.
 */
final class Http2StreamHandler extends ChannelInboundHandlerAdapter {

    private final Http2Server.Shared shared;
    private boolean started;
    private Http2RequestBody body; // event loop only; null when we keep no content

    Http2StreamHandler(Http2Server.Shared shared) {
        this.shared = shared;
    }

    /** Pseudo-headers and the fields the core understands; :authority wins over a host field. */
    /**
     * Test-only: when non-null, {@link Task#run()} runs this before resolving the handler (#301).
     * Production never sets it.
     */
    static volatile Runnable beforeDispatch;

    static Request toRequest(Http2Headers h) {
        return toRequest(h, null, false, false);
    }

    /**
     * @param peer connection address, or null when it could not be read
     * @param secure true on the TLS listener
     * @param captureFields copy every regular header into {@link RequestHeaders#fields()}
     */
    static Request toRequest(Http2Headers h, InetSocketAddress peer, boolean secure) {
        return toRequest(h, peer, secure, false);
    }

    static Request toRequest(Http2Headers h, InetSocketAddress peer, boolean secure, boolean captureFields) {
        var method = h.method();
        var path = h.path();
        var authority = h.authority();
        var auth = authority == null ? null : authority.toString();
        var headers = captureFields
                ? RequestHeaders.of(fieldMap(h), auth)
                : new RequestHeaders(first(h, "if-none-match"), first(h, "if-modified-since"), first(h, "if-range"),
                        first(h, "range"), first(h, "accept-encoding"), auth != null ? auth : first(h, "host"));
        return new Request(method == null ? "" : method.toString(), path == null ? "" : path.toString(), headers,
                peer, secure, "HTTP/2");
    }

    /** Lower-case name to values, first-seen order, pseudo-headers excluded. */
    private static Map<String, List<String>> fieldMap(Http2Headers headers) {
        LinkedHashMap<String, List<String>> fields = null;
        for (var entry : headers) {
            var raw = entry.getKey();
            if (raw.length() > 0 && raw.charAt(0) == ':') {
                continue;
            }
            if (fields == null) {
                fields = new LinkedHashMap<>();
            }
            var name = raw.toString();
            var values = fields.get(name);
            if (values == null) {
                values = new ArrayList<>(1);
                fields.put(name, values);
            }
            values.add(entry.getValue().toString());
        }
        if (fields == null) {
            return Map.of();
        }
        for (var entry : fields.entrySet()) {
            entry.setValue(List.copyOf(entry.getValue()));
        }
        return Collections.unmodifiableMap(fields);
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
        if (response.mayHaveBody() && response.contentLength() >= 0) {
            h.add("content-length", Long.toString(response.contentLength()));
        }
        return h;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        try {
            if (msg instanceof Http2HeadersFrame frame) {
                if (!this.started) {
                    this.started = true;
                    var stream = (Http2StreamChannel) ctx.channel();
                    var headers = frame.headers();
                    var request = toRequest(headers, peer(stream), secure(stream), this.shared.attachBodies());
                    start(stream, headers, request, frame.isEndStream());
                } else if (frame.isEndStream() && this.body != null) {
                    // trailers end the request; field values are ignored
                    this.body.end();
                }
                return;
            }
            if (msg instanceof Http2DataFrame data && this.body != null) {
                if (data.content().isReadable()) {
                    this.body.offer(data.content());
                }
                if (data.isEndStream()) {
                    this.body.end();
                }
            }
        } finally {
            ReferenceCountUtil.release(msg);
        }
    }

    private static InetSocketAddress peer(Http2StreamChannel stream) {
        var parent = stream.parent();
        if (parent == null) {
            return null;
        }
        var remote = parent.remoteAddress();
        return remote instanceof InetSocketAddress inet ? inet : null;
    }

    private static boolean secure(Http2StreamChannel stream) {
        var parent = stream.parent();
        return parent != null && parent.pipeline().get(io.netty.handler.ssl.SslHandler.class) != null;
    }

    private void start(Http2StreamChannel stream, Http2Headers headers, Request request, boolean endStream) {
        var budget = this.shared.budget(stream.parent());
        if (!budget.inflight().tryAcquire()) { // one connection can't take the whole server's share
            this.shared.refused().increment();
            stream.writeAndFlush(new DefaultHttp2ResetFrame(Http2Error.REFUSED_STREAM));
            return;
        }
        if (!this.shared.inflight().tryAcquire()) {
            budget.inflight().release();
            this.shared.refused().increment();
            stream.writeAndFlush(new DefaultHttp2ResetFrame(Http2Error.REFUSED_STREAM));
            return;
        }
        // One generation read: handler + attachBodies (#301 / P2a).
        var generation = this.shared.generation();
        generation.enter();
        // Only when bodies are enabled (chain / non-static terminal): static-only path stays zero-cost.
        if (generation.attachBodies() && !request.headOnly() && !"GET".equals(request.method())) {
            long length = contentLength(headers);
            if (length != 0) {
                Runnable sendContinue = expectContinue(headers) ? () -> send100(stream) : null;
                this.body = new Http2RequestBody(stream, length, this.shared.writeTimeout().toNanos(), sendContinue);
                request = request.withBody(this.body);
                if (endStream) {
                    this.body.end();
                }
            }
        } else if (endStream) {
            // nothing to attach
        }
        // Pin the handler generation with the body-capture decision (#301).
        var task = new Task(stream, request, budget, this.body, generation);
        try {
            this.shared.executor().execute(task);
        } catch (RejectedExecutionException e) { // shutting down
            task.finish();
            stream.writeAndFlush(new DefaultHttp2ResetFrame(Http2Error.REFUSED_STREAM));
            return;
        }
        stream.closeFuture().addListener(f -> task.cancel());
    }

    /** @return declared content-length, or -1 when absent (codec already rejects malformed ones) */
    static long contentLength(Http2Headers headers) {
        var value = headers.get("content-length");
        if (value == null) {
            return -1;
        }
        try {
            long length = Long.parseLong(value.toString().trim());
            return length < 0 ? -1 : length;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** RFC 9110 §10.1.1: only {@code 100-continue} is recognized (case-insensitive). */
    static boolean expectContinue(Http2Headers headers) {
        var value = headers.get("expect");
        return value != null && value.toString().equalsIgnoreCase("100-continue");
    }

    /** Interim {@code :status: 100} without END_STREAM; safe to call from a request thread. */
    static void send100(Http2StreamChannel stream) {
        stream.eventLoop().execute(() -> {
            if (!stream.isActive()) {
                return;
            }
            var headers = new DefaultHttp2Headers();
            headers.status("100");
            stream.writeAndFlush(new DefaultHttp2HeadersFrame(headers, false));
        });
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
        private final Http2Server.Budget budget;
        private final Http2RequestBody body; // or null
        /** Generation (handler + attachBodies) captured at construction (#301 / P2a). */
        private final Http2Server.Generation generation;
        private final AtomicBoolean finished = new AtomicBoolean();
        private volatile Thread thread;
        private volatile boolean cancelled;

        Task(Http2StreamChannel stream, Request request, Http2Server.Budget budget, Http2RequestBody body,
                Http2Server.Generation generation) {
            this.stream = stream;
            this.request = request;
            this.budget = budget;
            this.body = body;
            this.generation = generation;
        }

        void cancel() {
            this.cancelled = true;
            if (this.body != null) {
                this.body.fail();
            }
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
                var gate = beforeDispatch;
                if (gate != null) {
                    gate.run();
                }
                var refusal = refusal(this.request);
                Response response;
                try {
                    response = refusal != null ? refusal : this.generation.handler().handle(this.request);
                } catch (RuntimeException e) {
                    response = StaticFileHandler.error(500, this.request.headOnly());
                }
                var shared = Http2StreamHandler.this.shared;
                var outcome = new ResponseWriter(this, shared.writeTimeout(), this.budget.files(), shared.files(),
                        shared.limits().fileWait(), shared.limits().zeroWindowTimeout()).write(response);
                if (outcome == ResponseWriter.Outcome.COMPLETE) {
                    endRequest();
                }
            } finally {
                this.thread = null;

                finish();
            }
        }

        /**
         * The response is complete but the peer never ended its side: RST_STREAM(NO_ERROR) as RFC 9113
         * §8.1 allows (also used by {@link Http2RequestBody#abandon} when content was attached).
         */
        private void endRequest() {
            if (this.body != null) {
                return; // abandon() in finish() sends the RST if needed
            }
            var state = this.stream.stream().state();
            if (state == Http2Stream.State.OPEN || state == Http2Stream.State.HALF_CLOSED_LOCAL) {
                this.stream.writeAndFlush(new DefaultHttp2ResetFrame(Http2Error.NO_ERROR));
            }
        }

        void finish() {
            if (this.finished.compareAndSet(false, true)) {
                if (this.body != null) {
                    this.body.abandon();
                }
                Http2StreamHandler.this.shared.inflight().release();
                this.budget.inflight().release();
                this.generation.leave();
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
        public boolean writable() {
            return this.stream.isWritable(); // follows the stream's remote flow-control window
        }

        @Override
        public ByteBufAllocator alloc() {
            return this.stream.alloc();
        }

        @Override
        public void abort(ResponseWriter.Outcome why) {
            if (this.stream.isActive()) {
                this.stream.writeAndFlush(new DefaultHttp2ResetFrame(
                        switch (why) {
                            case REJECTED -> Http2Error.REFUSED_STREAM;
                            case STARVED -> Http2Error.CANCEL;
                            default -> Http2Error.INTERNAL_ERROR;
                        }));
            }
        }
    }
}
