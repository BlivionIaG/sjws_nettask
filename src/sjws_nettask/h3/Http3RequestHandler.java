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

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http3.Http3DataFrame;
import io.netty.handler.codec.http3.Http3ErrorCode;
import io.netty.handler.codec.http3.Http3Headers;
import io.netty.handler.codec.http3.Http3HeadersValidationException;
import io.netty.handler.codec.http3.Http3HeadersFrame;
import io.netty.handler.codec.http3.Http3RequestStreamInboundHandler;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.util.ReferenceCountUtil;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import sjws_nettask.core.Request;
import sjws_nettask.core.RequestRules;
import sjws_nettask.core.RequestHeaders;
import sjws_nettask.core.Response;
import sjws_nettask.core.StaticFileHandler;
import sjws_nettask.observe.RequestObserver;

/**
 * One instance per request stream. Maps the HEADERS frame to a core {@link Request} as soon as it
 * arrives (request bodies are ignored, like on HTTP/1), runs the shared {@link StaticFileHandler}
 * and the {@link Http3Writer} on a virtual thread, and releases every inbound DATA frame.
 * <p>
 * Each request takes one server-wide in-flight slot ({@link Limits#maxInflight()}), refused with
 * H3_REQUEST_REJECTED when none is free. The task is tied to the stream's lifetime: when the stream
 * closes (peer reset, connection gone) the task is cancelled, which interrupts a writer waiting for
 * a file slot or for flow control, and the slot is returned. Streams at or above the id announced
 * in a GOAWAY (graceful shutdown) are refused the same way.
 */
final class Http3RequestHandler extends Http3RequestStreamInboundHandler {

    private final Http3Server.Shared shared;
    private final Http3Server.Connection connection;
    private boolean started; // event loop only

    Http3RequestHandler(Http3Server.Shared shared, Http3Server.Connection connection) {
        this.shared = shared;
        this.connection = connection;
    }

    /** Pseudo-headers and the fields the core understands; :authority wins over a host field. */
    static Request toRequest(Http3Headers h) {
        var method = h.method();
        var path = h.path();
        var authority = h.authority();
        return new Request(method == null ? "" : method.toString(), path == null ? "" : path.toString(),
                new RequestHeaders(first(h, "if-none-match"), first(h, "if-modified-since"), first(h, "if-range"),
                        first(h, "range"), first(h, "accept-encoding"),
                        authority != null ? authority.toString() : first(h, "host")));
    }

    private static String first(Http3Headers h, String name) {
        var v = h.get(name);
        return v == null ? null : v.toString();
    }

    @Override
    protected void channelRead(ChannelHandlerContext ctx, Http3HeadersFrame frame) {
        try {
            if (this.started) {
                return; // trailers
            }
            this.started = true;
            var stream = (QuicStreamChannel) ctx.channel();
            var headers = frame.headers();
            for (var name : RequestRules.CONNECTION_SPECIFIC) {
                if (headers.contains(name)) { // malformed (RFC 9114 §4.2): stream error, no response
                    this.shared.count(Http3Writer.Outcome.REJECTED);
                    stream.shutdown(Http3ErrorCode.H3_MESSAGE_ERROR.code());
                    return;
                }
            }
            var request = toRequest(headers);
            String referer = "-";
            String userAgent = "-";
            var obs = this.shared.observer();
            if (obs != RequestObserver.NONE && obs.logHeaders()) {
                referer = dash(first(headers, "referer"));
                userAgent = dash(first(headers, "user-agent"));
            }
            start(stream, request, refusal(headers, request), referer, userAgent);
        } finally {
            ReferenceCountUtil.release(frame);
        }
    }

    /**
     * The same request-line rules as HTTP/1.1 (unknown method 501, raw space/CTL/non-ASCII in the
     * target 400, too long 414), plus 431 for an oversized field section. The codec's own limit is
     * set higher ({@link Http3Server#CODEC_MAX_FIELD_SECTION}), so these are answered on the stream
     * instead of closing the whole connection.
     *
     * @return the error response to send, or null to run the handler
     */
    static Response refusal(Http3Headers headers, Request request) {
        long size = 0;
        for (var e : headers) {
            size += e.getKey().length() + e.getValue().length() + 32; // RFC 9114 §4.2.2
        }
        if (size > Http3Server.MAX_FIELD_SECTION) {
            return StaticFileHandler.error(431, request.headOnly());
        }
        int status = RequestRules.check(request.method(), request.target());
        return status == 0 ? null : StaticFileHandler.error(status, request.headOnly());
    }

    private static String dash(String value) {
        return value == null || value.isEmpty() ? "-" : value;
    }

    private void start(QuicStreamChannel stream, Request request, Response refusal, String referer, String userAgent) {
        var limits = this.shared.limits();
        // Under pressure each connection gets only its share, so a few connections (one address) can't
        // hold the whole in-flight cap (verification V1); without pressure a client may use every stream.
        boolean overShare = this.connection.inflight.get() >= limits.inflightPerConnection()
                && limits.inflightContended(this.shared.inflight());
        if (!this.connection.admit(stream.streamId()) || overShare || !this.shared.inflight().tryAcquire()) {
            reject(stream);
            return;
        }
        this.connection.inflight.incrementAndGet();
        var task = new Task(stream, request, refusal, referer, userAgent);
        try {
            this.shared.executor().execute(task);
        } catch (RejectedExecutionException e) { // shutting down
            task.finish(null);
            reject(stream);
            return;
        }
        // Peer reset / connection closed: stop waiting for slots or flow control. The slot is returned
        // when the thread actually ends (not Future.cancel: JDK 21's thread-per-task executor then
        // forgets the thread at once, so neither the in-flight cap nor awaitTermination would see it).
        stream.closeFuture().addListener(f -> task.cancel());
    }

    private void reject(QuicStreamChannel stream) {
        this.shared.count(Http3Writer.Outcome.REJECTED);
        stream.shutdown(Http3ErrorCode.H3_REQUEST_REJECTED.code());
    }

    private Response respond(Request request, RequestObserver obs) {
        if (obs != RequestObserver.NONE && obs.intercepts()) {
            var special = obs.intercept(request.method(), request.target(), this.connection.loopback);
            if (special != null) {
                return special;
            }
        }
        try {
            return this.shared.handler().apply(request);
        } catch (RuntimeException e) { // StaticFileHandler never throws; a test seam might
            return StaticFileHandler.error(500, request.headOnly());
        }
    }

    /** One request: handler + writer on a virtual thread; returns its in-flight slot exactly once. */
    private final class Task implements Runnable {

        private final QuicStreamChannel stream;
        private final Request request;
        private final Response refusal;
        private final String referer;
        private final String userAgent;
        private final AtomicBoolean finished = new AtomicBoolean();
        private volatile Thread thread;
        private volatile boolean cancelled;

        Task(QuicStreamChannel stream, Request request, Response refusal, String referer, String userAgent) {
            this.stream = stream;
            this.request = request;
            this.refusal = refusal;
            this.referer = referer;
            this.userAgent = userAgent;
        }

        /** Interrupts the request's thread, or makes it return at once if it hasn't started yet. */
        void cancel() {
            this.cancelled = true;
            var t = this.thread;
            if (t != null) {
                t.interrupt(); // harmless if the thread has already ended
            }
        }

        @Override
        public void run() {
            this.thread = Thread.currentThread();
            Http3Writer.Outcome outcome = Http3Writer.Outcome.FAILED;
            Response response = null;
            var obs = Http3RequestHandler.this.shared.observer();
            boolean record = obs != RequestObserver.NONE;
            long started = 0;
            if (record) {
                started = System.nanoTime();
            }
            try {
                if (this.cancelled || !this.stream.isActive()) {
                    outcome = Http3Writer.Outcome.CLOSED;
                    return;
                }
                response = this.refusal != null ? this.refusal : respond(this.request, obs);
                outcome = new Http3Writer(this.stream, Http3RequestHandler.this.shared.limits(),
                        Http3RequestHandler.this.shared.serverFiles(), Http3RequestHandler.this.connection)
                        .write(response);
            } finally {
                finish(outcome);
                if (record && response != null) {
                    observe(obs, response, outcome, started);
                }
            }
        }

        /** After the write, so the duration includes it. A reset before a response is not a request. */
        private void observe(RequestObserver obs, Response response, Http3Writer.Outcome outcome, long started) {
            long nanos = System.nanoTime() - started;
            long bytes = outcome == Http3Writer.Outcome.COMPLETE && response.sendsBody()
                    ? Math.max(0L, response.contentLength()) : 0L;
            if (obs.logging()) {
                var conn = Http3RequestHandler.this.connection;
                obs.complete(response.status(), RequestObserver.HTTP_3, bytes, nanos, conn.remote, this.request.method(),
                        this.request.target(), "HTTP/3", this.referer, this.userAgent);
            } else {
                obs.complete(response.status(), RequestObserver.HTTP_3, bytes, nanos);
            }
        }

        void finish(Http3Writer.Outcome outcome) {
            if (this.finished.compareAndSet(false, true)) {
                Http3RequestHandler.this.connection.inflight.decrementAndGet();
                Http3RequestHandler.this.shared.inflight().release();
                if (outcome != null) {
                    Http3RequestHandler.this.shared.count(outcome);
                }
            }
        }
    }

    @Override
    protected void channelRead(ChannelHandlerContext ctx, Http3DataFrame frame) {
        ReferenceCountUtil.release(frame);
    }

    @Override
    protected void channelInputClosed(ChannelHandlerContext ctx) {
        // The response is written independently of the request's end.
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (cause instanceof Http3HeadersValidationException) { // e.g. a missing :path
            ((QuicStreamChannel) ctx.channel()).shutdown(Http3ErrorCode.H3_MESSAGE_ERROR.code());
            return;
        }
        // QuicStreamResetException: the peer aborted the request (RESET_STREAM). Netty leaves the stream
        // open in that case; close it so the task is cancelled. Http3Exception: the codec already closed
        // the stream or the connection. Neither is worth a log line a remote client can trigger at will.
        ctx.close();
    }
}
