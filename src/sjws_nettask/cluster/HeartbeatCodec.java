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
package sjws_nettask.cluster;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.CRC32;

/**
 * Fixed 64-byte heartbeat. Layout (big-endian):
 * magic u16, version u8, kind u8, from u16, to u16, incarnation u64, term u64,
 * generation u64, content hash u64, leaseRemainingMs u32, flags u8, seq u32,
 * suspectWire u16, ackIncarnation u64, reserved u8, crc32 u32.
 */
public final class HeartbeatCodec {

    public static final int BYTES = 64;
    public static final int MAGIC = 0x4841;

    private HeartbeatCodec() {
    }

    public static void encode(Heartbeat beat, byte[] dest) {
        if (dest.length < BYTES) {
            throw new IllegalArgumentException("need 64 bytes");
        }
        var buf = ByteBuffer.wrap(dest, 0, BYTES).order(ByteOrder.BIG_ENDIAN);
        buf.putShort((short) MAGIC);
        buf.put((byte) beat.version());
        buf.put((byte) beat.kind().code());
        buf.putShort((short) beat.fromWire());
        buf.putShort((short) beat.toWire());
        buf.putLong(beat.incarnation());
        buf.putLong(beat.term());
        buf.putLong(beat.generation());
        buf.putLong(beat.contentHash());
        buf.putInt(beat.leaseRemainingMs());
        buf.put((byte) beat.flags());
        buf.putInt(beat.seq());
        buf.putShort((short) beat.suspectWire());
        buf.putLong(beat.ackIncarnation());
        buf.put((byte) 0);
        buf.putInt(checksum(dest));
    }

    /** @return the beat, or null when the length, checksum, magic, version, or kind is wrong */
    public static Heartbeat decode(byte[] src) {
        if (src.length != BYTES) {
            return null;
        }
        if (checksum(src) != readCrc(src)) {
            return null;
        }
        var buf = ByteBuffer.wrap(src).order(ByteOrder.BIG_ENDIAN);
        int magic = Short.toUnsignedInt(buf.getShort());
        if (magic != MAGIC) {
            return null;
        }
        int version = Byte.toUnsignedInt(buf.get());
        if (version != Heartbeat.VERSION) {
            return null;
        }
        var kind = Heartbeat.Kind.fromCode(Byte.toUnsignedInt(buf.get()));
        if (kind == null) {
            return null;
        }
        int from = Short.toUnsignedInt(buf.getShort());
        int to = Short.toUnsignedInt(buf.getShort());
        long incarnation = buf.getLong();
        long term = buf.getLong();
        long generation = buf.getLong();
        long hash = buf.getLong();
        int lease = buf.getInt();
        int flags = Byte.toUnsignedInt(buf.get());
        int seq = buf.getInt();
        int suspect = Short.toUnsignedInt(buf.getShort());
        long ackIncarnation = buf.getLong();
        try {
            return new Heartbeat(version, kind, from, to, incarnation, term, generation, hash, lease, flags, seq,
                    suspect, ackIncarnation);
        } catch (IllegalArgumentException rejected) {
            return null;
        }
    }

    static int checksum(byte[] data) {
        var crc = new CRC32();
        crc.update(data, 0, 60);
        return (int) crc.getValue();
    }

    private static int readCrc(byte[] src) {
        return ByteBuffer.wrap(src, 60, 4).order(ByteOrder.BIG_ENDIAN).getInt();
    }
}
