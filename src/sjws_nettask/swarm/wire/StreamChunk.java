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
 * One token-stream chunk (docs/design/agent-swarm.md §5.2 ledger leg, §11.2 {@code TOKEN} frame payload):
 * {@code [version:u8][streamId:16][chunkSeq:u32][flags:u8][bytes]}, big-endian, 22-byte header. The flag bits
 * {@code LAST}, {@code CANCELLED} and {@code ERROR} share their values with {@link Frame}; {@code FIRST} is chunk-only.
 * JDK only, so SDK conformance tests can use it without the server.
 */
public record StreamChunk(long streamHi, long streamLo, long chunkSeq, int flags, byte[] bytes) {

    public static final int VERSION = 1;
    public static final int HEADER = 22;
    public static final int FIRST = 0x08;
    public static final int KNOWN_FLAGS = Frame.LAST | Frame.CANCELLED | Frame.ERROR | FIRST;
    public static final long MAX_SEQ = 0xffff_ffffL;

    public StreamChunk {
        if (chunkSeq < 0 || chunkSeq > MAX_SEQ) {
            throw new IllegalArgumentException("chunkSeq " + chunkSeq);
        }
        if ((flags & ~KNOWN_FLAGS) != 0) {
            throw new IllegalArgumentException("flags " + flags);
        }
        if ((flags & (Frame.CANCELLED | Frame.ERROR)) != 0 && (flags & Frame.LAST) == 0) {
            throw new IllegalArgumentException("CANCELLED/ERROR need LAST: " + flags);
        }
        if (bytes == null || bytes.length > Frame.MAX_PAYLOAD - HEADER) {
            throw new IllegalArgumentException("bytes");
        }
        bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
        return this.bytes.clone();
    }

    public int size() {
        return this.bytes.length;
    }

    public boolean last() {
        return (this.flags & Frame.LAST) != 0;
    }

    public byte[] encode() {
        return ByteBuffer.allocate(HEADER + this.bytes.length).put((byte) VERSION).putLong(this.streamHi)
                .putLong(this.streamLo).putInt((int) this.chunkSeq).put((byte) this.flags).put(this.bytes).array();
    }

    /** The {@code TOKEN} frame carrying this chunk; the frame's {@code LAST} flag mirrors the chunk's. */
    public Frame toFrame() {
        return new Frame(Frame.Type.TOKEN, this.flags & (Frame.LAST | Frame.CANCELLED | Frame.ERROR), encode());
    }

    public static StreamChunk decode(byte[] payload) {
        if (payload.length < HEADER) {
            throw new IllegalArgumentException("chunk " + payload.length + "B < " + HEADER);
        }
        var in = ByteBuffer.wrap(payload);
        int v = in.get() & 0xff;
        if (v != VERSION) {
            throw new IllegalArgumentException("chunk version " + v);
        }
        long hi = in.getLong();
        long lo = in.getLong();
        long seq = in.getInt() & MAX_SEQ;
        int flags = in.get() & 0xff;
        return new StreamChunk(hi, lo, seq, flags, Arrays.copyOfRange(payload, HEADER, payload.length));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof StreamChunk c && c.streamHi == this.streamHi && c.streamLo == this.streamLo
                && c.chunkSeq == this.chunkSeq && c.flags == this.flags && Arrays.equals(c.bytes, this.bytes);
    }

    @Override
    public int hashCode() {
        return ((Long.hashCode(this.streamHi ^ this.streamLo) * 31 + Long.hashCode(this.chunkSeq)) * 31 + this.flags) * 31
                + Arrays.hashCode(this.bytes);
    }

    @Override
    public String toString() {
        return "StreamChunk[seq=" + this.chunkSeq + ", flags=" + this.flags + ", " + this.bytes.length + "B]";
    }
}
