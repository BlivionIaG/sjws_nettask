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

import java.util.Objects;

/** One decoded heartbeat. The on-wire form is exactly {@link HeartbeatCodec#BYTES} bytes. */
public record Heartbeat(int version, Kind kind, int fromWire, int toWire, long incarnation, long term, long generation,
        long contentHash, int leaseRemainingMs, int flags, int seq, int suspectWire, long ackIncarnation) {

    public static final int VERSION = 1;
    public static final int FLAG_WITNESS = 0x01;

    public Heartbeat {
        Objects.requireNonNull(kind, "kind");
        if (version != VERSION) {
            throw new IllegalArgumentException("version");
        }
        if (fromWire < 1 || fromWire > 0xFFFF || toWire < 1 || toWire > 0xFFFF) {
            throw new IllegalArgumentException("wire id");
        }
        if (suspectWire < 0 || suspectWire > 0xFFFF) {
            throw new IllegalArgumentException("suspect");
        }
    }

    public enum Kind {
        HEARTBEAT(1),
        ACK(2),
        INDIRECT_PING(3),
        INDIRECT_ACK(4);

        private final int code;

        Kind(int code) {
            this.code = code;
        }

        public int code() {
            return code;
        }

        public static Kind fromCode(int code) {
            for (var kind : values()) {
                if (kind.code == code) {
                    return kind;
                }
            }
            return null;
        }
    }
}
