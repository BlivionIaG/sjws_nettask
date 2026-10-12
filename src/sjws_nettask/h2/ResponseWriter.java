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
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import sjws_nettask.core.Body;
import sjws_nettask.core.Response;
import sjws_nettask.core.SafeOpen;
import sjws_nettask.core.StaticFileHandler;

/**
 * Streams a core {@link Response} into a {@link Sink} (an HTTP/2 stream, or an HTTP/1.1 connection
 * reached through the TLS/h2c listener's fallback). Runs on a virtual thread, never on the event loop.
 * <p>
 * Flow control: before each chunk the writer waits until the previous one was actually written. On
 * HTTP/2 that future completes only once Netty's remote flow controller sent the bytes, i.e. within
 * the peer's stream and connection windows, so at most one {@link #CHUNK} per stream is buffered and a
 * peer that stops sending WINDOW_UPDATE stalls only its own stream (reset after the write timeout).
 */
final class ResponseWriter {

    static final int CHUNK = 64 * 1024;

    /** Fields the writer owns or that are connection-specific (forbidden in HTTP/2, RFC 9113 §8.2.2). */
    static final Set<String> NOT_COPIED = Set.of("connection", "keep-alive", "proxy-connection", "transfer-encoding",
            "upgrade", "te", "content-length", "date", "server", "alt-svc");

    enum Outcome { COMPLETE, SWAPPED, SHRANK, STALLED, REJECTED, CLOSED, FAILED }

    /** Where a response goes. Every method is called from the writer's thread. */
    interface Sink {
        /** Writes the head; {@code endOfResponse}: nothing follows (no body). */
        ChannelFuture head(Response response, boolean endOfResponse);

        /** Writes body bytes (takes ownership of {@code data}). */
        ChannelFuture data(ByteBuf data, boolean endOfResponse);

        boolean active();

        ByteBufAllocator alloc();

        /** The response cannot be completed: reset the stream / close the connection. */
        void abort(Outcome why);
    }

    private final Sink sink;
    private final Duration writeTimeout;
    private final Semaphore files;
    private ChannelFuture previous;
    private boolean answered;

    ResponseWriter(Sink sink, Duration writeTimeout, Semaphore files) {
        this.sink = sink;
        this.writeTimeout = writeTimeout;
        this.files = files;
    }

    /** Writes the whole response; on failure the sink has been aborted. */
    Outcome write(Response response) {
        Outcome outcome;
        try {
            outcome = send(response);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            outcome = Outcome.CLOSED;
        } catch (IOException | RuntimeException e) {
            outcome = Outcome.FAILED;
        }
        if (outcome != Outcome.COMPLETE && !this.answered) {
            this.sink.abort(outcome);
        }
        return outcome;
    }

    private Outcome send(Response response) throws IOException, InterruptedException {
        boolean sendBody = response.sendsBody() && response.contentLength() > 0;
        if (sendBody && response.body() instanceof Body.FileBody file) {
            if (!this.files.tryAcquire(this.writeTimeout.toNanos(), TimeUnit.NANOSECONDS)) {
                return Outcome.REJECTED;
            }
            try {
                return sendFile(response, file);
            } finally {
                this.files.release();
            }
        }
        if (sendBody && response.body() instanceof Body.Bytes bytes) {
            this.sink.head(response, false);
            return sendBytes(bytes);
        }
        this.previous = this.sink.head(response, true);
        return awaitLast();
    }

    private Outcome sendFile(Response response, Body.FileBody file) throws IOException, InterruptedException {
        if (!this.sink.active()) {
            return Outcome.CLOSED;
        }
        FileChannel fc;
        try {
            fc = SafeOpen.open(file);
        } catch (IOException e) { // nothing sent yet: answer with the matching error
            var outcome = send(StaticFileHandler.error(SafeOpen.status(e), response.headOnly()));
            this.answered = outcome == Outcome.COMPLETE;
            return Outcome.SWAPPED;
        }
        try (fc) {
            this.sink.head(response, false);
            long done = 0;
            while (done < file.length()) {
                var outcome = ready();
                if (outcome != Outcome.COMPLETE) {
                    return outcome;
                }
                int n = (int) Math.min(CHUNK, file.length() - done);
                ByteBuf buf = this.sink.alloc().ioBuffer(n);
                try {
                    while (buf.readableBytes() < n) {
                        int read = buf.writeBytes(fc, file.offset() + done + buf.readableBytes(),
                                n - buf.readableBytes());
                        if (read <= 0) {
                            buf.release();
                            return Outcome.SHRANK;
                        }
                    }
                } catch (IOException | RuntimeException e) {
                    buf.release();
                    throw e;
                }
                done += n;
                this.previous = this.sink.data(buf, done == file.length());
            }
            return awaitLast();
        }
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
            this.previous = this.sink.data(Unpooled.wrappedBuffer(chunk), !data.hasRemaining());
        }
        return awaitLast();
    }

    /** The last write must also complete: a response is only done once the peer's window took it. */
    private Outcome awaitLast() throws InterruptedException {
        return ready();
    }

    /** Waits until the previous write completed; COMPLETE means "go on". */
    private Outcome ready() throws InterruptedException {
        if (!this.sink.active() && (this.previous == null || !this.previous.isDone())) {
            return Outcome.CLOSED;
        }
        if (this.previous == null) {
            return Outcome.COMPLETE;
        }
        var done = new CompletableFuture<Void>(); // parks virtual threads cleanly
        this.previous.addListener(f -> done.complete(null));
        try {
            done.get(this.writeTimeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException | ExecutionException e) {
            return Outcome.STALLED;
        }
        return this.previous.isSuccess() ? Outcome.COMPLETE : Outcome.CLOSED;
    }
}
