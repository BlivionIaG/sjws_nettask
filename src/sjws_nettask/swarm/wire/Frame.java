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
 * One binary frame of the swarm wire protocol v1 (docs/design/agent-swarm.md §11.2):
 * {@code [version:u8][type:u8][flags:u8][len:u24][payload]}, big-endian, 6-byte header, payload up to
 * 2^24 − 1 bytes. Depends on nothing outside the JDK, so SDK conformance tests can use it without the server.
 */
public record Frame(Type type, int flags, byte[] payload) {

    public static final int VERSION = 1;
    public static final int HEADER = 6;
    public static final int MAX_PAYLOAD = (1 << 24) - 1;

    /** Flag bits. {@code LAST} ends a stream; {@code CANCELLED} and {@code ERROR} qualify a {@code LAST}. */
    public static final int LAST = 0x01;
    public static final int CANCELLED = 0x02;
    public static final int ERROR = 0x04;

    /** Frame types; the wire code is the ordinal + 1 and must never be reordered. */
    public enum Type {
        TASK, CLAIM, LEASE, EXTEND, RESULT, TOKEN, ERROR, CANCEL, CREDIT;

        private static final Type[] BY_CODE = values();

        public int code() {
            return ordinal() + 1;
        }

        public static Type of(int code) {
            if (code < 1 || code > BY_CODE.length) {
                throw new IllegalArgumentException("unknown frame type " + code);
            }
            return BY_CODE[code - 1];
        }
    }

    public Frame {
        if (type == null) {
            throw new IllegalArgumentException("type");
        }
        if (flags < 0 || flags > 0xff) {
            throw new IllegalArgumentException("flags " + flags);
        }
        if (payload.length > MAX_PAYLOAD) {
            throw new IllegalArgumentException("payload " + payload.length + " > " + MAX_PAYLOAD);
        }
        payload = payload.clone();
    }

    @Override
    public byte[] payload() {
        return this.payload.clone();
    }

    public byte[] encode() {
        var out = ByteBuffer.allocate(HEADER + this.payload.length);
        out.put((byte) VERSION).put((byte) this.type.code()).put((byte) this.flags);
        int n = this.payload.length;
        out.put((byte) (n >>> 16)).put((byte) (n >>> 8)).put((byte) n);
        return out.put(this.payload).array();
    }

    /**
     * Decodes one frame from {@code in} and advances it past the frame. Returns null, without consuming, when
     * {@code in} does not yet hold a whole frame. A wrong version or unknown type is an
     * {@link IllegalArgumentException}.
     */
    public static Frame decode(ByteBuffer in) {
        if (in.remaining() < HEADER) {
            return null;
        }
        int p = in.position();
        int version = in.get(p) & 0xff;
        if (version != VERSION) {
            throw new IllegalArgumentException("wire version " + version);
        }
        var type = Type.of(in.get(p + 1) & 0xff);
        int flags = in.get(p + 2) & 0xff;
        int len = (in.get(p + 3) & 0xff) << 16 | (in.get(p + 4) & 0xff) << 8 | in.get(p + 5) & 0xff;
        if (in.remaining() < HEADER + len) {
            return null;
        }
        var payload = new byte[len];
        in.position(p + HEADER);
        in.get(payload);
        return new Frame(type, flags, payload);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Frame f && f.type == this.type && f.flags == this.flags && Arrays.equals(f.payload, this.payload);
    }

    @Override
    public int hashCode() {
        return (this.type.hashCode() * 31 + this.flags) * 31 + Arrays.hashCode(this.payload);
    }

    @Override
    public String toString() {
        return "Frame[" + this.type + ", flags=" + this.flags + ", " + this.payload.length + "B]";
    }
}
