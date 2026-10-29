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
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import sjws_nettask.core.Request;
import sjws_nettask.core.RequestRules;
import sjws_nettask.core.RequestHeaders;
import sjws_nettask.core.Response;
import sjws_nettask.core.StaticFileHandler;

/**
 * One instance per request stream. Maps the HEADERS frame to a core {@link Request} as soon as it
 * arrives, runs the shared {@link StaticFileHandler} and the {@link Http3Writer} on a virtual thread.
 * <p>
 * Request content (#281): for a method other than GET and HEAD the DATA frames feed an
 * {@link Http3RequestBody} attached to the request, which the handler pulls with backpressure (the
 * stream stops reading while the handler is behind). Size limits are the handler's, as on HTTP/1.1
 * ({@code content-length} is {@link sjws_nettask.core.RequestBody#length()}, -1 when absent). GET/HEAD
 * content, trailers and whatever the handler leaves unread are released.
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
    private Http3RequestBody body; // event loop only; null when the request carries no content we keep

    Http3RequestHandler(Http3Server.Shared shared, Http3Server.Connection connection) {
        this.shared = shared;
        this.connection = connection;
    }

    /** Six typed fields only. No header-map allocation. */
    static Request toRequest(Http3Headers h) {
        return toRequest(h, null, false);
    }

    /** @param peer client UDP address, or null */
    static Request toRequest(Http3Headers h, InetSocketAddress peer) {
        return toRequest(h, peer, false);
    }

    /**
     * @param captureFields when true, copy every regular header before the HEADERS frame is
     *        released (the handler runs later, on another thread, so the frame cannot be retained).
     *        Pseudo-headers stay out of the map; {@code :authority} still wins for
     *        {@link RequestHeaders#host()}. When false, only the six typed fields are read, which
     *        is the static-handler path
     */
    static Request toRequest(Http3Headers h, InetSocketAddress peer, boolean captureFields) {
        var method = h.method();
        var path = h.path();
        var authority = h.authority();
        var auth = authority == null ? null : authority.toString();
        var headers = captureFields
                ? RequestHeaders.of(fieldMap(h), auth)
                : new RequestHeaders(first(h, "if-none-match"), first(h, "if-modified-since"), first(h, "if-range"),
                        first(h, "range"), first(h, "accept-encoding"), auth != null ? auth : first(h, "host"));
        return new Request(method == null ? "" : method.toString(), path == null ? "" : path.toString(), headers,
                peer, true);
    }

    private static String first(Http3Headers h, String name) {
        var value = h.get(name);
        return value == null ? null : value.toString();
    }

    /**
     * Client UDP address. {@link QuicStreamChannel#remoteAddress()} is a stream id, not the peer;
     * the connection's {@code remoteSocketAddress} is the socket address.
     */
    static InetSocketAddress peer(QuicStreamChannel stream) {
        var connection = stream.parent();
        if (connection == null) {
            return null;
        }
        var remote = connection.remoteSocketAddress();
        if (remote instanceof InetSocketAddress inet) {
            return inet;
        }
        return null;
    }

    /** Lower-case name to values, first-seen order, pseudo-headers excluded. Empty when there are none. */
    private static Map<String, List<String>> fieldMap(Http3Headers headers) {
        LinkedHashMap<String, List<String>> fields = null;
        for (var entry : headers) {
            var raw = entry.getKey();
            if (raw.charAt(0) == ':') {
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
            var request = toRequest(headers, peer(stream), this.shared.captureFields());
            start(stream, headers, request, refusal(headers, request));
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

    private void start(QuicStreamChannel stream, Http3Headers headers, Request request, Response refusal) {
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
        // Only a chain or another terminal can read content: the static handler alone never does (zero cost there).
        if (refusal == null && this.shared.captureFields() && !request.headOnly() && !"GET".equals(request.method())) {
            long length = contentLength(headers);
            if (length != 0) {
                this.body = new Http3RequestBody(stream, length, limits.writeTimeout().toNanos());
                request = request.withBody(this.body);
            }
        }
        var task = new Task(stream, request, refusal, this.body);
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

    /**
     * @return the declared {@code content-length}, or -1 when there is none (the codec has already refused a
     *         malformed or inconsistent one: RFC 9114 §4.1.2)
     */
    static long contentLength(Http3Headers headers) {
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

    private void reject(QuicStreamChannel stream) {
        this.shared.count(Http3Writer.Outcome.REJECTED);
        stream.shutdown(Http3ErrorCode.H3_REQUEST_REJECTED.code());
    }

    private Response apply(Request request) {
        var live = this.shared.armed().get();
        try {
            if (live != null) {
                return live.handle(request);
            }
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
        private final Http3RequestBody body; // or null
        private final AtomicBoolean finished = new AtomicBoolean();
        private volatile Thread thread;
        private volatile boolean cancelled;

        Task(QuicStreamChannel stream, Request request, Response refusal, Http3RequestBody body) {
            this.stream = stream;
            this.request = request;
            this.refusal = refusal;
            this.body = body;
        }

        /** Interrupts the request's thread, or makes it return at once if it hasn't started yet. */
        void cancel() {
            this.cancelled = true;
            if (this.body != null) {
                this.body.fail();
            }
            var t = this.thread;
            if (t != null) {
                t.interrupt(); // harmless if the thread has already ended
            }
        }

        @Override
        public void run() {
            this.thread = Thread.currentThread();
            Http3Writer.Outcome outcome = Http3Writer.Outcome.FAILED;
            try {
                if (this.cancelled || !this.stream.isActive()) {
                    outcome = Http3Writer.Outcome.CLOSED;
                    return;
                }
                var response = this.refusal != null ? this.refusal : apply(this.request);
                outcome = new Http3Writer(this.stream, Http3RequestHandler.this.shared.limits(),
                        Http3RequestHandler.this.shared.serverFiles(), Http3RequestHandler.this.connection)
                        .write(response);
            } finally {
                finish(outcome);
            }
        }

        void finish(Http3Writer.Outcome outcome) {
            if (this.finished.compareAndSet(false, true)) {
                if (this.body != null) {
                    this.body.abandon();
                }
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
        try {
            if (this.body != null) {
                this.body.offer(frame.content());
            }
        } finally {
            ReferenceCountUtil.release(frame);
        }
    }

    @Override
    protected void channelInputClosed(ChannelHandlerContext ctx) {
        // The response is written independently of the request's end; a body reader sees its end here.
        if (this.body != null) {
            this.body.end();
        }
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
