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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.handler.codec.http3.DefaultHttp3DataFrame;
import io.netty.handler.codec.http3.DefaultHttp3HeadersFrame;
import io.netty.handler.codec.http3.Http3ErrorCode;
import io.netty.handler.codec.http3.Http3HeadersFrame;
import io.netty.handler.codec.quic.QuicStreamChannel;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import sjws_nettask.HTTP.Http1Writer;
import sjws_nettask.core.Body;
import sjws_nettask.core.HttpDate;
import sjws_nettask.core.Response;
import sjws_nettask.core.SafeOpen;

/**
 * Serializes a core {@link Response} on one HTTP/3 request stream: a HEADERS frame
 * ({@code :status}, {@code server}, {@code date}, the representation fields, {@code content-length}),
 * DATA frames, then FIN.
 * <p>
 * Runs on a virtual thread, never on the event loop: file reads block, and before each chunk the
 * writer waits until the previous one has been accepted by the QUIC stream (its write future
 * completes only once quiche took all of it, i.e. within the peer's flow-control window). So at most
 * one chunk per stream waits in memory while the next is read, and a peer that stops reading
 * stalls the writer, which gives up after the write timeout. If the response cannot be completed (file swapped
 * before the head, file shrank, peer stopped reading, stream gone) the stream is reset instead of
 * ending with a short body.
 */
final class Http3Writer {

    static final int CHUNK = 64 * 1024;

    /** Connection-specific fields are forbidden in HTTP/3 (RFC 9114 §4.2); the writer owns the rest. */
    static final Set<String> NOT_COPIED = Set.of("connection", "keep-alive", "proxy-connection", "transfer-encoding",
            "upgrade", "te", "content-length", "date", "server", "alt-svc");

    /** Why a response was not completed. */
    enum Outcome { COMPLETE, SWAPPED, SHRANK, STALLED, CLOSED, FAILED }

    private final QuicStreamChannel channel;
    private final long writeTimeoutNanos;
    private final Semaphore openFiles;
    private ChannelFuture previous; // last DATA write

    Http3Writer(QuicStreamChannel channel, long writeTimeoutNanos, Semaphore openFiles) {
        this.channel = channel;
        this.writeTimeoutNanos = writeTimeoutNanos;
        this.openFiles = openFiles;
    }

    static Http3HeadersFrame headersFrame(Response response, Instant now) {
        var frame = new DefaultHttp3HeadersFrame();
        var h = frame.headers();
        h.status(Integer.toString(response.status()));
        h.add("server", Http1Writer.SERVER);
        h.add("date", HttpDate.format(now));
        for (var e : response.headers().entries()) {
            if (!NOT_COPIED.contains(e.getKey())) {
                h.add(e.getKey(), e.getValue());
            }
        }
        if (response.mayHaveBody()) {
            h.add("content-length", Long.toString(response.contentLength()));
        }
        return frame;
    }

    /** Writes the whole response; on failure the stream has been reset. */
    Outcome write(Response response) {
        try {
            var outcome = send(response);
            if (outcome != Outcome.COMPLETE) {
                reset();
            }
            return outcome;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            reset();
            return Outcome.CLOSED;
        } catch (IOException | RuntimeException e) {
            reset();
            return Outcome.FAILED;
        }
    }

    private Outcome send(Response response) throws IOException, InterruptedException {
        boolean sendBody = !response.headOnly() && response.mayHaveBody() && response.contentLength() > 0;
        var head = headersFrame(response, Instant.now());
        if (sendBody && response.body() instanceof Body.FileBody file) {
            this.openFiles.acquire();
            try {
                FileChannel fc;
                try {
                    fc = SafeOpen.open(file);
                } catch (IOException e) {
                    return Outcome.SWAPPED; // deleted, swapped (symlink in any component, other inode), unreadable
                }
                try (fc) {
                    this.channel.write(head);
                    return sendFile(fc, file);
                }
            } finally {
                this.openFiles.release();
            }
        }
        if (sendBody && response.body() instanceof Body.Bytes bytes) {
            this.channel.write(head);
            return sendBytes(bytes);
        }
        this.channel.writeAndFlush(head).addListener(QuicStreamChannel.SHUTDOWN_OUTPUT);
        return Outcome.COMPLETE;
    }

    private Outcome sendBytes(Body.Bytes bytes) throws InterruptedException {
        var data = bytes.data().duplicate();
        while (data.hasRemaining()) {
            var outcome = ready();
            if (outcome != Outcome.COMPLETE) {
                return outcome;
            }
            int n = Math.min(CHUNK, data.remaining());
            var chunk = data.slice(data.position(), n);
            data.position(data.position() + n);
            emit(Unpooled.wrappedBuffer(chunk), !data.hasRemaining());
        }
        return Outcome.COMPLETE;
    }

    private Outcome sendFile(FileChannel fc, Body.FileBody file) throws IOException, InterruptedException {
        long done = 0;
        while (done < file.length()) {
            var outcome = ready();
            if (outcome != Outcome.COMPLETE) {
                return outcome;
            }
            int n = (int) Math.min(CHUNK, file.length() - done);
            ByteBuf buf = this.channel.alloc().ioBuffer(n);
            try {
                while (buf.readableBytes() < n) {
                    int read = buf.writeBytes(fc, file.offset() + done + buf.readableBytes(), n - buf.readableBytes());
                    if (read <= 0) { // positional reads return -1 at EOF; never spin on 0
                        buf.release();
                        return Outcome.SHRANK;
                    }
                }
            } catch (IOException | RuntimeException e) {
                buf.release();
                throw e;
            }
            done += n;
            emit(buf, done == file.length());
        }
        return Outcome.COMPLETE;
    }

    private void emit(ByteBuf content, boolean lastChunk) {
        var frame = new DefaultHttp3DataFrame(content);
        this.previous = this.channel.writeAndFlush(frame);
        if (lastChunk) {
            this.previous.addListener(QuicStreamChannel.SHUTDOWN_OUTPUT);
        }
    }

    /** Waits until the previous chunk was taken by the stream; COMPLETE means "go on". */
    private Outcome ready() throws InterruptedException {
        if (!this.channel.isActive()) {
            return Outcome.CLOSED;
        }
        if (this.previous == null) {
            return Outcome.COMPLETE;
        }
        // CompletableFuture parks virtual threads cleanly (Netty's await() would pin the carrier).
        var done = new CompletableFuture<Void>();
        this.previous.addListener(f -> done.complete(null));
        try {
            done.get(this.writeTimeoutNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            return Outcome.STALLED; // no progress: the peer stopped reading
        } catch (ExecutionException e) {
            throw new IllegalStateException(e); // never completed exceptionally
        }
        return this.previous.isSuccess() ? Outcome.COMPLETE : Outcome.CLOSED;
    }

    private void reset() {
        if (this.channel.isActive()) {
            this.channel.shutdown(Http3ErrorCode.H3_INTERNAL_ERROR.code());
        }
    }
}
