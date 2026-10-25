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

/**
 * One {@code CREDIT} frame payload, v1 (docs/design/agent-swarm.md §5.2 <i>Backpressure</i>, §11.2; S2c PR 6, #247):
 * {@code [version:u8 = 1][streamId:16][attempt:u32][chunks:u32][bytes:u32]}, big-endian, exactly {@value #SIZE} bytes.
 * {@code chunks} and {@code bytes} are an absolute snapshot of the send credit left, {@code attempt} the lease's attempt;
 * all three are unsigned and decoded into {@code long}. Decoding rejects an empty payload ({@code length}), then a version
 * other than 1 ({@code version}), then a length other than 29 ({@code length}); the message starts with that reason token
 * (goldens: {@code tests/sjws_nettask/swarm/wire/golden-credit-v1.txt}). Frame flags are reserved: sent as 0, ignored on
 * receipt. JDK only, so SDK conformance tests can use it without the server.
 */
public record Credit(long streamHi, long streamLo, long attempt, long chunks, long bytes) {

    public static final int VERSION = 1;
    public static final int SIZE = 29;
    public static final long MAX_U32 = 0xffff_ffffL;

    public Credit {
        if (attempt < 0 || attempt > MAX_U32) {
            throw new IllegalArgumentException("attempt " + attempt);
        }
        if (chunks < 0 || chunks > MAX_U32) {
            throw new IllegalArgumentException("chunks " + chunks);
        }
        if (bytes < 0 || bytes > MAX_U32) {
            throw new IllegalArgumentException("bytes " + bytes);
        }
    }

    public byte[] encode() {
        return ByteBuffer.allocate(SIZE).put((byte) VERSION).putLong(this.streamHi).putLong(this.streamLo)
                .putInt((int) this.attempt).putInt((int) this.chunks).putInt((int) this.bytes).array();
    }

    /** A {@code CREDIT} frame (flags 0) carrying this payload. */
    public Frame toFrame() {
        return new Frame(Frame.Type.CREDIT, 0, encode());
    }

    /** Decodes a v1 payload; {@link IllegalArgumentException} whose message starts with {@code length} or {@code version}. */
    public static Credit decode(byte[] payload) {
        if (payload == null || payload.length == 0) {
            throw new IllegalArgumentException("length 0: a CREDIT payload is " + SIZE + " bytes");
        }
        if ((payload[0] & 0xff) != VERSION) {
            throw new IllegalArgumentException("version " + (payload[0] & 0xff) + ": expected " + VERSION);
        }
        if (payload.length != SIZE) {
            throw new IllegalArgumentException("length " + payload.length + ": a CREDIT payload is " + SIZE + " bytes");
        }
        var b = ByteBuffer.wrap(payload, 1, SIZE - 1);
        return new Credit(b.getLong(), b.getLong(), Integer.toUnsignedLong(b.getInt()), Integer.toUnsignedLong(b.getInt()),
                Integer.toUnsignedLong(b.getInt()));
    }
}
