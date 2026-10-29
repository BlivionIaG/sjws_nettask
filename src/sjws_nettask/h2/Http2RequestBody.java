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
import io.netty.handler.codec.http2.DefaultHttp2ResetFrame;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2StreamChannel;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import sjws_nettask.core.RequestBody;

/**
 * The content of one HTTP/2 request (its DATA frames), pulled by the handler on the request's virtual thread (#289).
 * <p>
 * The event loop {@link #offer}s each DATA frame's bytes; once {@link #HIGH} bytes wait unread the stream stops reading
 * ({@code autoRead} off), so the peer stops receiving WINDOW_UPDATE credit and blocks on the stream window
 * (the HTTP/2 initial stream window (64 KiB by default)). Reading resumes when the handler has drained the buffer to {@link #LOW}. What
 * one stream holds is therefore bounded by about {@code HIGH} plus one stream window, whatever the client sends.
 * <p>
 * Each {@link #read} that has to wait is bounded by the write timeout (as on HTTP/1.1). When the request is done
 * ({@link #abandon}) whatever is buffered is released and, if the client has not finished sending, the server asks it
 * to stop with RST_STREAM(NO_ERROR) (RFC 9113 §8.1).
 */
final class Http2RequestBody implements RequestBody {

    /** Unread bytes at which the stream stops reading. */
    static final int HIGH = 16 * 1024; // pause reading around one DATA frame; peer holds at stream window
    /** Unread bytes at or below which a paused stream reads again. */
    static final int LOW = HIGH / 2;

    private final Http2StreamChannel stream;
    private final long length;
    private final long timeoutNanos;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = this.lock.newCondition();
    // guarded by lock
    private final ArrayDeque<ByteBuf> queue = new ArrayDeque<>();
    private int buffered;
    private boolean ended; // FIN received
    private boolean failed; // the stream closed or was reset before FIN
    private boolean abandoned; // the request is done: nothing reads any more
    private boolean paused; // autoRead is off (changed on the event loop only)
    private boolean resumeQueued;
    private long received; // bytes handed to the reader
    private boolean sawData; // a non-empty DATA frame arrived
    private long resolved = UNRESOLVED; // length() without a content-length: 0 or -1 once decided
    private static final long UNRESOLVED = -2;

    /**
     * @param length the declared {@code content-length}, or -1
     * @param timeoutNanos how long one {@link #read} waits for bytes
     */
    Http2RequestBody(Http2StreamChannel stream, long length, long timeoutNanos) {
        this.stream = stream;
        this.length = length;
        this.timeoutNanos = timeoutNanos;
    }

    /**
     * The declared {@code content-length}. Without one, waits (at most the read timeout) for the request's first
     * DATA frame or its end: a request that ends without content is 0 (as an absent body is on HTTP/1.1), one that
     * carries content is -1 (unknown). The answer is fixed once given.
     */
    @Override
    public long length() {
        if (this.length >= 0) {
            return this.length;
        }
        this.lock.lock();
        try {
            if (this.resolved == UNRESOLVED) {
                long deadline = System.nanoTime() + this.timeoutNanos;
                while (!this.sawData && !this.ended && !this.failed && !this.abandoned) {
                    long left = deadline - System.nanoTime();
                    if (left <= 0) {
                        break;
                    }
                    try {
                        this.changed.awaitNanos(left);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                this.resolved = this.ended && !this.sawData ? 0 : -1;
            }
            return this.resolved;
        } finally {
            this.lock.unlock();
        }
    }

    /** Event loop: one DATA frame's content (retained here; the caller releases its own reference). */
    void offer(ByteBuf content) {
        int n = content.readableBytes();
        if (n == 0) {
            return;
        }
        this.lock.lock();
        try {
            if (this.abandoned || this.ended || this.failed) {
                return;
            }
            this.sawData = true;
            this.queue.add(content.retain());
            this.buffered += n;
            this.changed.signalAll();
        } finally {
            this.lock.unlock();
        }
        flow();
    }

    /** Event loop: the client finished the request (FIN). */
    void end() {
        this.lock.lock();
        try {
            this.ended = true;
            this.changed.signalAll();
        } finally {
            this.lock.unlock();
        }
    }

    /** The stream closed or was reset; a reader still waiting gets an IOException unless FIN was already seen. */
    void fail() {
        this.lock.lock();
        try {
            this.failed = true;
            this.changed.signalAll();
        } finally {
            this.lock.unlock();
        }
    }

    @Override
    public int read(byte[] into, int off, int len) throws IOException {
        Objects.checkFromIndexSize(off, len, into.length);
        boolean resume = false;
        int n;
        this.lock.lock();
        try {
            long deadline = 0;
            boolean waited = false;
            while (this.queue.isEmpty()) {
                if (this.abandoned) {
                    throw new IOException("request body already closed");
                }
                if (this.ended) {
                    if (this.length >= 0 && this.received != this.length) { // the codec checks this too
                        throw new IOException("request body ended after " + this.received + " of " + this.length
                                + " bytes");
                    }
                    return -1;
                }
                if (this.failed) {
                    throw new IOException("request stream closed after " + this.received + " body bytes");
                }
                if (len == 0) {
                    return 0;
                }
                long now = System.nanoTime();
                if (!waited) {
                    waited = true;
                    deadline = now + this.timeoutNanos;
                } else if (now - deadline >= 0) {
                    throw new IOException("no request body bytes for "
                            + TimeUnit.NANOSECONDS.toMillis(this.timeoutNanos) + " ms");
                }
                try {
                    this.changed.awaitNanos(deadline - now);
                } catch (InterruptedException e) { // the stream closed (task cancelled) or the server is stopping
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("interrupted while reading the request body");
                }
            }
            if (len == 0) {
                return 0;
            }
            var head = this.queue.peek();
            n = Math.min(len, head.readableBytes());
            head.readBytes(into, off, n);
            if (!head.isReadable()) {
                this.queue.poll().release();
            }
            this.buffered -= n;
            this.received += n;
            if (this.paused && !this.resumeQueued && this.buffered <= LOW) {
                this.resumeQueued = true;
                resume = true;
            }
        } finally {
            this.lock.unlock();
        }
        if (resume) {
            this.stream.eventLoop().execute(this::flow);
        }
        return n;
    }

    /**
     * The request is done (its response was written, or it never ran): drops what is buffered, keeps the stream
     * reading so late DATA is released at once, and asks the client to stop sending if it hasn't finished. Idempotent.
     */
    void abandon() {
        boolean stopSending;
        this.lock.lock();
        try {
            if (this.abandoned) {
                return;
            }
            this.abandoned = true;
            stopSending = !this.ended && !this.failed;
            ByteBuf b;
            while ((b = this.queue.poll()) != null) {
                b.release();
            }
            this.buffered = 0;
            this.changed.signalAll();
        } finally {
            this.lock.unlock();
        }
        this.stream.eventLoop().execute(() -> {
            flow();
            if (stopSending && this.stream.isActive()) {
                this.stream.writeAndFlush(new DefaultHttp2ResetFrame(Http2Error.NO_ERROR));
            }
        });
    }

    /** Event loop only: turns reading off at {@link #HIGH} unread bytes and back on at {@link #LOW} (or when done). */
    private void flow() {
        boolean pause;
        this.lock.lock();
        try {
            this.resumeQueued = false;
            if (this.paused) {
                this.paused = !this.abandoned && this.buffered > LOW;
            } else {
                this.paused = !this.abandoned && this.buffered >= HIGH;
            }
            pause = this.paused;
        } finally {
            this.lock.unlock();
        }
        var config = this.stream.config();
        if (config.isAutoRead() == pause) {
            config.setAutoRead(!pause);
        }
    }

    /** @return bytes received and not yet read (tests) */
    int buffered() {
        this.lock.lock();
        try {
            return this.buffered;
        } finally {
            this.lock.unlock();
        }
    }
}
