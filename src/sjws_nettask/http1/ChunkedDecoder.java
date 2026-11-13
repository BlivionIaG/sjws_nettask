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
package sjws_nettask.http1;

/**
 * Incremental decoder of a chunked request body (RFC 9112 §7.1). The static file path discards
 * the content (it must still consume framing to keep a persistent connection in sync). A proxy
 * can also copy chunk data into a caller buffer via {@link #feed(byte[], int, int, byte[], int, int)}.
 * <p>
 * Strict, like {@link Http1Parser}: chunk sizes are 1-16 hex digits; chunk extensions and trailer
 * fields are skipped (each line bounded by {@code maxLineLength}); the CRLF after chunk data is
 * mandatory; other lines may end with a bare LF, as in the head. A bare CR, a bad size, a missing
 * data CRLF or more than {@code maxTrailers} trailer lines is a 400. Exceeding {@code maxBody}
 * content bytes is reported by {@link #overLimit()} (the caller answers, then closes).
 * <p>
 * Not thread-safe; one per request (or {@link #reset()}).
 */
public final class ChunkedDecoder {

    private enum State { SIZE, EXT, SIZE_LF, DATA, DATA_CR, DATA_LF, TRAILER, TRAILER_LF, DONE }

    private final int maxLineLength;
    private final int maxTrailers;
    private final long maxBody;
    private State state;
    private long chunkSize;
    private int digits;
    private long remaining;
    private long total;
    private int lineLength;
    private int trailers;
    private boolean lastChunk;
    private int copied; // data bytes written to the destination of the last copying feed

    public ChunkedDecoder(int maxLineLength, int maxTrailers, long maxBody) {
        this.maxLineLength = maxLineLength;
        this.maxTrailers = maxTrailers;
        this.maxBody = maxBody;
        reset();
    }

    public void reset() {
        this.state = State.SIZE;
        this.chunkSize = 0;
        this.digits = 0;
        this.remaining = 0;
        this.total = 0;
        this.lineLength = 0;
        this.trailers = 0;
        this.lastChunk = false;
        this.copied = 0;
    }

    /** @return true once the last chunk and the trailer section have been consumed */
    public boolean isComplete() {
        return this.state == State.DONE;
    }

    /** @return content bytes seen so far exceed {@code maxBody}: stop reading, answer and close */
    public boolean overLimit() {
        return this.total > this.maxBody;
    }

    /** @return content (chunk data) bytes seen so far */
    public long contentLength() {
        return this.total;
    }

    /**
     * Consumes bytes up to the end of the body.
     *
     * @return bytes consumed: {@code len}, or fewer when the body ended (the rest is the next
     *         request) or the limit was exceeded
     * @throws ParseError (400) on malformed framing
     */
    public int feed(byte[] buf, int off, int len) throws ParseError {
        return feed(buf, off, len, null, 0, 0);
    }

    /**
     * Consumes from {@code buf} and copies chunk-data bytes into {@code dst}. Returns bytes
     * consumed from {@code buf}. {@link #copied()} is how many data bytes were written to {@code dst}
     * (0 when {@code dst} is null, i.e. discard). Stops early when {@code dst} is full, leaving the
     * decoder mid-chunk so the next call can continue.
     */
    public int feed(byte[] buf, int off, int len, byte[] dst, int dstOff, int dstLen) throws ParseError {
        this.copied = 0;
        int i = off;
        int end = off + len;
        while (i < end && this.state != State.DONE && !overLimit()) {
            if (this.state == State.DATA) {
                int cap = dst == null ? Integer.MAX_VALUE : dstLen - this.copied;
                if (cap == 0) {
                    break;
                }
                i += data(buf, i, end - i, dst, dst == null ? 0 : dstOff + this.copied, cap);
                continue;
            }
            step(buf[i++]);
        }
        return i - off;
    }

    /** As {@link #feed(byte[], int, int)}, reading {@code buf} (heap or direct) with absolute gets from {@code off}. */
    public int feed(java.nio.ByteBuffer buf, int off, int len) throws ParseError {
        return feed(buf, off, len, null, 0, 0);
    }

    /** As {@link #feed(byte[], int, int, byte[], int, int)} over a heap or direct buffer. */
    public int feed(java.nio.ByteBuffer buf, int off, int len, byte[] dst, int dstOff, int dstLen) throws ParseError {
        this.copied = 0;
        int i = off;
        int end = off + len;
        while (i < end && this.state != State.DONE && !overLimit()) {
            if (this.state == State.DATA) {
                int cap = dst == null ? Integer.MAX_VALUE : dstLen - this.copied;
                if (cap == 0) {
                    break;
                }
                i += data(buf, i, end - i, dst, dst == null ? 0 : dstOff + this.copied, cap);
                continue;
            }
            step(buf.get(i++));
        }
        return i - off;
    }

    /** Data bytes written to the destination of the last copying {@code feed}. */
    public int copied() {
        return this.copied;
    }

    private int data(byte[] src, int srcOff, int available, byte[] dst, int dstOff, int cap) {
        int n = take(available, cap, dst != null);
        if (dst != null) {
            System.arraycopy(src, srcOff, dst, dstOff, n);
            this.copied += n;
        }
        return n;
    }

    private int data(java.nio.ByteBuffer src, int srcOff, int available, byte[] dst, int dstOff, int cap) {
        int n = take(available, cap, dst != null);
        if (dst != null) {
            src.get(srcOff, dst, dstOff, n);
            this.copied += n;
        }
        return n;
    }

    private int take(int available, int cap, boolean limited) {
        int n = (int) Math.min(this.remaining, available);
        if (limited) {
            n = Math.min(n, cap);
        }
        this.remaining -= n;
        if (this.remaining == 0) {
            this.state = State.DATA_CR;
        }
        return n;
    }

    private void step(byte b) throws ParseError {
        if ((this.state == State.SIZE || this.state == State.EXT) && b != '\r' && b != '\n') {
            lineByte();
        }
        switch (this.state) {
            case SIZE -> {
                int d = Character.digit(b, 16);
                if (d >= 0) {
                    if (++this.digits > 16 || (this.digits == 16 && this.chunkSize >= 0x0800_0000_0000_0000L)) {
                        throw new ParseError(400, "chunk size too large");
                    }
                    this.chunkSize = this.chunkSize * 16 + d;
                } else if (this.digits == 0) {
                    throw new ParseError(400, "missing chunk size");
                } else if (b == ';' || b == ' ' || b == '\t') {
                    this.state = State.EXT; // BWS before ';' is tolerated (RFC 9112 §7.1.1)
                } else {
                    endSizeLine(b);
                }
            }
            case EXT -> {
                if (b == '\r' || b == '\n') {
                    endSizeLine(b);
                } else if ((b < 0x20 && b != '\t') || b == 0x7f) {
                    throw new ParseError(400, "control character in chunk extension");
                }
            }
            case SIZE_LF, TRAILER_LF -> {
                if (b != '\n') {
                    throw new ParseError(400, "bare CR");
                }
                afterLine();
            }
            case DATA_CR -> {
                if (b != '\r') {
                    throw new ParseError(400, "missing CRLF after chunk data");
                }
                this.state = State.DATA_LF;
            }
            case DATA_LF -> {
                if (b != '\n') {
                    throw new ParseError(400, "missing CRLF after chunk data");
                }
                startSizeLine();
            }
            case TRAILER -> {
                if (b == '\r') {
                    this.state = State.TRAILER_LF;
                } else if (b == '\n') {
                    afterLine();
                } else {
                    if ((b < 0x20 && b != '\t') || b == 0x7f) {
                        throw new ParseError(400, "control character in trailer");
                    }
                    lineByte();
                }
            }
            default -> throw new IllegalStateException(this.state.name());
        }
    }

    private void lineByte() throws ParseError {
        if (++this.lineLength > this.maxLineLength) {
            throw new ParseError(400, "chunk line too long");
        }
    }

    private void endSizeLine(byte b) throws ParseError {
        if (b == '\r') {
            this.state = State.SIZE_LF;
        } else if (b == '\n') {
            afterLine();
        } else {
            throw new ParseError(400, "invalid chunk size");
        }
    }

    /** A size line or a trailer line just ended. */
    private void afterLine() throws ParseError {
        if (this.lastChunk) { // in the trailer section
            if (this.lineLength == 0) {
                this.state = State.DONE;
                return;
            }
            if (++this.trailers > this.maxTrailers) {
                throw new ParseError(400, "too many trailer fields");
            }
            this.lineLength = 0;
            this.state = State.TRAILER;
            return;
        }
        this.lineLength = 0;
        if (this.chunkSize == 0) {
            this.lastChunk = true;
            this.state = State.TRAILER;
            return;
        }
        this.total += this.chunkSize;
        this.remaining = this.chunkSize;
        this.state = State.DATA;
    }

    private void startSizeLine() {
        this.chunkSize = 0;
        this.digits = 0;
        this.lineLength = 0;
        this.state = State.SIZE;
    }
}
