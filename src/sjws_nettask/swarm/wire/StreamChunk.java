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
package sjws_nettask.swarm.wire;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * One token-stream chunk (docs/design/agent-swarm.md §5.2 ledger leg, §11.2 {@code TOKEN} frame payload). Layout v2
 * (S2c PR 5, §12 reset rule): {@code [version:u8 = 2][streamId:16][attempt:u32][chunkSeq:u32][flags:u8][bytes]},
 * big-endian, 26-byte header; {@code attempt} is the lease's delivery attempt and is in <b>every</b> chunk, so a lost
 * {@code FIRST} cannot hide a reset. v1 ({@code [1][streamId][chunkSeq][flags][bytes]}, 22 bytes) is still decoded,
 * as attempt 0; {@link #encode} always writes v2. The flag bits {@code LAST}, {@code CANCELLED} and {@code ERROR}
 * share their values with {@link Frame}; {@code FIRST} and {@code RESET} are chunk-only. {@code RESET} is never
 * produced by a worker: the consumer emits it (empty, {@code chunkSeq} 0, the new attempt) when a newer attempt
 * replaces the buffered one. JDK only, so SDK conformance tests can use it without the server.
 */
public final class StreamChunk {

    public static final int VERSION = 2;
    public static final int VERSION_1 = 1;
    public static final int HEADER = 26;
    public static final int HEADER_V1 = 22;
    public static final int FIRST = 0x08;
    /** Consumer-side marker: the stream restarted at {@code chunkSeq} 0 under {@link #attempt()}. */
    public static final int RESET = 0x10;
    public static final int KNOWN_FLAGS = Frame.LAST | Frame.CANCELLED | Frame.ERROR | FIRST | RESET;
    public static final long MAX_ATTEMPT = 0xffff_ffffL;
    public static final long MAX_SEQ = 0xffff_ffffL;
    public static final int MAX_BYTES = Frame.MAX_PAYLOAD - HEADER;

    private final long streamHi;
    private final long streamLo;
    private final long attempt;
    private final long chunkSeq;
    private final int flags;
    private final byte[] bytes;

    /** Copies {@code bytes}; attempt 0. */
    public StreamChunk(long streamHi, long streamLo, long chunkSeq, int flags, byte[] bytes) {
        this(bytes == null ? null : bytes.clone(), streamHi, streamLo, 0, chunkSeq, flags);
    }

    /** Copies {@code bytes}. */
    public StreamChunk(long streamHi, long streamLo, long attempt, long chunkSeq, int flags, byte[] bytes) {
        this(bytes == null ? null : bytes.clone(), streamHi, streamLo, attempt, chunkSeq, flags);
    }

    /** Takes ownership of {@code bytes}. */
    private StreamChunk(byte[] bytes, long streamHi, long streamLo, long attempt, long chunkSeq, int flags) {
        if (attempt < 0 || attempt > MAX_ATTEMPT) {
            throw new IllegalArgumentException("attempt " + attempt);
        }
        if (chunkSeq < 0 || chunkSeq > MAX_SEQ) {
            throw new IllegalArgumentException("chunkSeq " + chunkSeq);
        }
        if ((flags & ~KNOWN_FLAGS) != 0) {
            throw new IllegalArgumentException("flags " + flags);
        }
        if ((flags & (Frame.CANCELLED | Frame.ERROR)) != 0 && (flags & Frame.LAST) == 0) {
            throw new IllegalArgumentException("CANCELLED/ERROR need LAST: " + flags);
        }
        if (bytes == null || bytes.length > MAX_BYTES) {
            throw new IllegalArgumentException("bytes");
        }
        this.streamHi = streamHi;
        this.streamLo = streamLo;
        this.attempt = attempt;
        this.chunkSeq = chunkSeq;
        this.flags = flags;
        this.bytes = bytes;
    }

    /** As the public constructor, but takes ownership of {@code bytes} (no copy): the caller must not reuse the array. */
    public static StreamChunk owned(long streamHi, long streamLo, long chunkSeq, int flags, byte[] bytes) {
        return new StreamChunk(bytes, streamHi, streamLo, 0, chunkSeq, flags);
    }

    /** As {@link #owned(long, long, long, int, byte[])} with an attempt. */
    public static StreamChunk owned(long streamHi, long streamLo, long attempt, long chunkSeq, int flags, byte[] bytes) {
        return new StreamChunk(bytes, streamHi, streamLo, attempt, chunkSeq, flags);
    }

    /** The consumer's {@code RESET(attempt)} marker for stream {@code (hi, lo)}. */
    public static StreamChunk reset(long streamHi, long streamLo, long attempt) {
        return new StreamChunk(new byte[0], streamHi, streamLo, attempt, 0, RESET);
    }

    /** This chunk stamped with {@code attempt} (shares the bytes; this chunk if it already carries it). */
    public StreamChunk withAttempt(long attempt) {
        return attempt == this.attempt ? this : new StreamChunk(this.bytes, this.streamHi, this.streamLo, attempt,
                this.chunkSeq, this.flags);
    }

    /** The delivery attempt that produced this chunk (0 for a v1 chunk). */
    public long attempt() {
        return this.attempt;
    }

    public boolean reset() {
        return (this.flags & RESET) != 0;
    }

    public long streamHi() {
        return this.streamHi;
    }

    public long streamLo() {
        return this.streamLo;
    }

    public long chunkSeq() {
        return this.chunkSeq;
    }

    public int flags() {
        return this.flags;
    }

    /** A copy of the chunk bytes; use {@link #view()} to read without copying. */
    public byte[] bytes() {
        return this.bytes.clone();
    }

    /** A read-only view of the chunk bytes, no copy. */
    public ByteBuffer view() {
        return ByteBuffer.wrap(this.bytes).asReadOnlyBuffer();
    }

    public int size() {
        return this.bytes.length;
    }

    public boolean last() {
        return (this.flags & Frame.LAST) != 0;
    }

    public byte[] encode() {
        return ByteBuffer.allocate(HEADER + this.bytes.length).put((byte) VERSION).putLong(this.streamHi)
                .putLong(this.streamLo).putInt((int) this.attempt).putInt((int) this.chunkSeq).put((byte) this.flags).put(this.bytes).array();
    }

    /** The {@code TOKEN} frame carrying this chunk; the frame's {@code LAST} flag mirrors the chunk's. */
    public Frame toFrame() {
        return new Frame(Frame.Type.TOKEN, this.flags & (Frame.LAST | Frame.CANCELLED | Frame.ERROR), encode());
    }

    public static StreamChunk decode(byte[] payload) {
        if (payload.length < HEADER_V1) {
            throw new IllegalArgumentException("chunk " + payload.length + "B < " + HEADER_V1);
        }
        var in = ByteBuffer.wrap(payload);
        int v = in.get() & 0xff;
        if (v != VERSION && v != VERSION_1) {
            throw new IllegalArgumentException("chunk version " + v);
        }
        int header = v == VERSION ? HEADER : HEADER_V1;
        if (payload.length < header) {
            throw new IllegalArgumentException("chunk " + payload.length + "B < " + header);
        }
        long hi = in.getLong();
        long lo = in.getLong();
        long attempt = v == VERSION ? in.getInt() & MAX_ATTEMPT : 0;
        long seq = in.getInt() & MAX_SEQ;
        int flags = in.get() & 0xff;
        return owned(hi, lo, attempt, seq, flags, Arrays.copyOfRange(payload, header, payload.length));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof StreamChunk c && c.streamHi == this.streamHi && c.streamLo == this.streamLo
                && c.attempt == this.attempt && c.chunkSeq == this.chunkSeq && c.flags == this.flags && Arrays.equals(c.bytes, this.bytes);
    }

    @Override
    public int hashCode() {
        return ((Long.hashCode(this.streamHi ^ this.streamLo) * 31 + Long.hashCode(this.chunkSeq ^ (this.attempt << 32))) * 31 + this.flags) * 31
                + Arrays.hashCode(this.bytes);
    }

    @Override
    public String toString() {
        return "StreamChunk[attempt=" + this.attempt + ", seq=" + this.chunkSeq + ", flags=" + this.flags + ", " + this.bytes.length + "B]";
    }
}
