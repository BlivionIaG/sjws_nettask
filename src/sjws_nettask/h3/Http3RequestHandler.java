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
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import sjws_nettask.core.Request;
import sjws_nettask.core.RequestHeaders;
import sjws_nettask.core.Response;
import sjws_nettask.core.StaticFileHandler;

/**
 * One instance per request stream. Maps the HEADERS frame to a core {@link Request} as soon as it
 * arrives (request bodies are ignored, like on HTTP/1), runs the shared {@link StaticFileHandler}
 * and the {@link Http3Writer} on a virtual thread, and releases every inbound frame.
 */
final class Http3RequestHandler extends Http3RequestStreamInboundHandler {

    private final Function<Request, Response> handler;
    private final Executor executor;
    private final long writeTimeoutNanos;
    private final Semaphore openFiles;
    private final Map<Http3Writer.Outcome, LongAdder> outcomes;
    private boolean started; // event loop only

    Http3RequestHandler(Function<Request, Response> handler, Executor executor, long writeTimeoutNanos,
            Semaphore openFiles, Map<Http3Writer.Outcome, LongAdder> outcomes) {
        this.handler = handler;
        this.executor = executor;
        this.writeTimeoutNanos = writeTimeoutNanos;
        this.openFiles = openFiles;
        this.outcomes = outcomes;
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
            var request = toRequest(frame.headers());
            var stream = (QuicStreamChannel) ctx.channel();
            try {
                this.executor.execute(() -> this.outcomes.get(
                        new Http3Writer(stream, this.writeTimeoutNanos, this.openFiles).write(respond(request)))
                        .increment());
            } catch (RejectedExecutionException e) { // shutting down
                stream.shutdown(Http3ErrorCode.H3_REQUEST_REJECTED.code());
            }
        } finally {
            ReferenceCountUtil.release(frame);
        }
    }

    private Response respond(Request request) {
        try {
            return this.handler.apply(request);
        } catch (RuntimeException e) { // StaticFileHandler never throws; a test seam might
            return StaticFileHandler.error(500, request.headOnly());
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
        super.exceptionCaught(ctx, cause);
    }
}
