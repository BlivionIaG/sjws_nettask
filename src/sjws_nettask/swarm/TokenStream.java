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
package sjws_nettask.swarm;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;
import sjws_nettask.swarm.wire.Frame;
import sjws_nettask.swarm.wire.StreamChunk;

/**
 * In-process token-stream building blocks for S2c (docs/design/agent-swarm.md §5.2): the producer-side
 * {@link Coalescer} (1–4 KiB or 20 ms) and the consumer-side bounded {@link Reorder} buffer (reorder and dedup by
 * {@code chunkSeq}). Nothing here is wired to a route or a topic yet; nothing loads this class while
 * {@code sjws.swarm.stream.enabled} is off. Not thread-safe: one producer / one consumer per stream.
 */
public final class TokenStream {

    public static final int DEFAULT_CHUNK_BYTES = 4096;
    public static final int MIN_CHUNK_BYTES = 1024;
    public static final int MAX_CHUNK_BYTES = 65536;
    public static final long DEFAULT_FLUSH_MS = 20;

    private TokenStream() {
    }

    /**
     * Stream shard for a stream id: {@code hash(streamId) % shards}, non-negative. {@code shards} comes from
     * {@code sjws.swarm.stream.shards} (default 1), read at startup only, since a resume must land on the same shard.
     */
    public static int shardOf(long streamHi, long streamLo, int shards) {
        if (shards < 1) {
            throw new IllegalArgumentException("shards " + shards);
        }
        long h = streamHi * 0x9E3779B97F4A7C15L ^ streamLo;
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        return (int) Long.remainderUnsigned(h, shards);
    }

    /** Producer side: buffers token bytes and cuts a chunk at {@code chunkBytes} or after {@code flushMs}. */
    public static final class Coalescer {
        private final long hi;
        private final long lo;
        private final int chunkBytes;
        private final long flushNanos;
        private final LongSupplier nanoClock;
        private final ByteArrayOutputStream buf;
        private long seq;
        private long firstAt;
        private boolean closed;

        public Coalescer(long streamHi, long streamLo, int chunkBytes, long flushMs, LongSupplier nanoClock) {
            if (chunkBytes < MIN_CHUNK_BYTES || chunkBytes > MAX_CHUNK_BYTES) {
                throw new IllegalArgumentException("chunkBytes " + chunkBytes);
            }
            if (flushMs < 0) {
                throw new IllegalArgumentException("flushMs " + flushMs);
            }
            this.hi = streamHi;
            this.lo = streamLo;
            this.chunkBytes = chunkBytes;
            this.flushNanos = flushMs * 1_000_000L;
            this.nanoClock = nanoClock;
            this.buf = new ByteArrayOutputStream(chunkBytes);
        }

        /** Appends token bytes; returns the chunks that became full (usually none: an empty immutable list, no allocation). */
        public List<StreamChunk> append(byte[] token) {
            ensureOpen();
            List<StreamChunk> out = List.of();
            if (token.length > 0 && this.buf.size() == 0) {
                this.firstAt = this.nanoClock.getAsLong();
            }
            int off = 0;
            while (off < token.length) {
                int n = Math.min(token.length - off, this.chunkBytes - this.buf.size());
                this.buf.write(token, off, n);
                off += n;
                if (this.buf.size() == this.chunkBytes) {
                    if (out.isEmpty()) {
                        out = new ArrayList<>(2);
                    }
                    out.add(cut(0));
                    if (off < token.length) {
                        this.firstAt = this.nanoClock.getAsLong();
                    }
                }
            }
            return out;
        }

        /** The time-based cut: a chunk if buffered bytes are at least {@code flushMs} old, else null. */
        public StreamChunk poll() {
            ensureOpen();
            if (this.buf.size() == 0 || this.nanoClock.getAsLong() - this.firstAt < this.flushNanos) {
                return null;
            }
            return cut(0);
        }

        /** Ends the stream: the final chunk with {@code LAST} (plus {@code CANCELLED} or {@code ERROR} if given). */
        public StreamChunk finish(int qualifier) {
            ensureOpen();
            if ((qualifier & ~(Frame.CANCELLED | Frame.ERROR)) != 0) {
                throw new IllegalArgumentException("qualifier " + qualifier);
            }
            var c = cut(Frame.LAST | qualifier);
            this.closed = true;
            return c;
        }

        public long nextSeq() {
            return this.seq;
        }

        public int buffered() {
            return this.buf.size();
        }

        private StreamChunk cut(int flags) {
            if (this.seq > StreamChunk.MAX_SEQ) {
                throw new IllegalStateException("chunkSeq exhausted");
            }
            int f = flags | (this.seq == 0 ? StreamChunk.FIRST : 0);
            var c = StreamChunk.owned(this.hi, this.lo, this.seq++, f, this.buf.toByteArray());
            this.buf.reset();
            return c;
        }

        private void ensureOpen() {
            if (this.closed) {
                throw new IllegalStateException("stream finished");
            }
        }
    }

    /**
     * Consumer side: delivers chunks of one stream in {@code chunkSeq} order exactly once. Duplicates (already
     * delivered or already buffered) are dropped; out-of-order chunks wait in a window of at most {@code window}
     * chunks ahead of the next expected seq. A chunk beyond the window is {@link Offer#OVERFLOW} and is not kept
     * (redelivery fills it later).
     */
    public static final class Reorder {

        /**
         * {@code CONFLICT}: a {@code LAST} that disagrees with an earlier {@code LAST}, or that would end the stream at or
         * below a chunk already delivered or buffered. A protocol error, never absorbed; the chunk is not kept.
         */
        public enum Offer { DELIVERED, BUFFERED, DUPLICATE, OVERFLOW, WRONG_STREAM, AFTER_LAST, CONFLICT }

        /** Default reorder window (chunks ahead of the next expected seq); slots are allocated lazily. */
        public static final int DEFAULT_WINDOW = 64;

        private final long hi;
        private final long lo;
        private final int window;
        private StreamChunk[] ring;
        private long next;
        private long maxSeen = -1;
        private int held;
        private long lastSeq = -1;

        public Reorder(long streamHi, long streamLo, int window) {
            if (window < 1 || window > 65536) {
                throw new IllegalArgumentException("window " + window);
            }
            this.hi = streamHi;
            this.lo = streamLo;
            this.window = window;
        }

        public Reorder(long streamHi, long streamLo) {
            this(streamHi, streamLo, DEFAULT_WINDOW);
        }

        /** Offers one chunk; any chunks that became deliverable, in order, are appended to {@code out}. */
        public Offer offer(StreamChunk c, List<StreamChunk> out) {
            if (c.streamHi() != this.hi || c.streamLo() != this.lo) {
                return Offer.WRONG_STREAM;
            }
            long s = c.chunkSeq();
            if (c.last()) {
                if (this.lastSeq >= 0 ? s != this.lastSeq : s <= this.maxSeen) {
                    return Offer.CONFLICT;
                }
            }
            if (s < this.next) {
                return Offer.DUPLICATE;
            }
            if (this.lastSeq >= 0 && s > this.lastSeq) {
                return Offer.AFTER_LAST;
            }
            if (s - this.next >= this.window) {
                return Offer.OVERFLOW;
            }
            if (s != this.next) {
                if (this.ring == null) {
                    this.ring = new StreamChunk[Math.min(this.window, 16)];
                }
                if (s - this.next >= this.ring.length) {
                    grow(s - this.next + 1);
                }
                int i = (int) (s % this.ring.length);
                if (this.ring[i] != null) {
                    return Offer.DUPLICATE;
                }
                this.ring[i] = c;
                this.held++;
                seen(c);
                return Offer.BUFFERED;
            }
            seen(c);
            out.add(c);
            this.next++;
            while (this.held > 0) {
                int j = (int) (this.next % this.ring.length);
                var h = this.ring[j];
                if (h == null) {
                    break;
                }
                this.ring[j] = null;
                this.held--;
                out.add(h);
                this.next++;
            }
            return Offer.DELIVERED;
        }

        /** True when {@code seq} was already delivered or is buffered (a re-offer would be a duplicate). */
        public boolean has(long seq) {
            if (seq < this.next) {
                return true;
            }
            if (this.ring == null || seq - this.next >= this.ring.length) {
                return false;
            }
            var h = this.ring[(int) (seq % this.ring.length)];
            return h != null && h.chunkSeq() == seq;
        }

        private void grow(long need) {
            // need <= window <= 65536 (checked by the OVERFLOW test before the call), so the long counter never overflows
            long n = this.ring.length;
            while (n < need) {
                n = Math.min(n * 2, this.window);
            }
            var r = new StreamChunk[(int) n];
            for (var h : this.ring) {
                if (h != null) {
                    r[(int) (h.chunkSeq() % n)] = h;
                }
            }
            this.ring = r;
        }

        private void seen(StreamChunk c) {
            this.maxSeen = Math.max(this.maxSeen, c.chunkSeq());
            if (c.last()) {
                this.lastSeq = c.chunkSeq();
            }
        }

        public long nextSeq() {
            return this.next;
        }

        public int held() {
            return this.held;
        }

        /** True once the {@code LAST} chunk and every chunk before it have been delivered. */
        public boolean complete() {
            return this.lastSeq >= 0 && this.next > this.lastSeq;
        }
    }
}
