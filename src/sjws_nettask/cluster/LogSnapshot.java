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

/** State machine image through {@code lastIncludedIndex}, plus the term of that index. */
public record LogSnapshot(GroupId groupId, long lastIncludedIndex, long lastIncludedTerm, byte[] data) {

    public LogSnapshot {
        Objects.requireNonNull(groupId, "groupId");
        data = data == null ? new byte[0] : data.clone();
        if (lastIncludedIndex < 0 || lastIncludedTerm < 0) {
            throw new IllegalArgumentException("snapshot");
        }
    }

    /**
     * CRC32C over index, term and image, for a snapshot that crossed a process or disk boundary.
     * Pass it to {@link SnapshotControl#install(LogSnapshot, int)} on the receiving side.
     */
    public int crc32c() {
        return crc32c(lastIncludedIndex, lastIncludedTerm, data);
    }

    /** The same CRC32C (index, term, image) without copying {@code data}; also used for snapshot transfer. */
    static int crc32c(long lastIncludedIndex, long lastIncludedTerm, byte[] data) {
        var crc = new java.util.zip.CRC32C();
        var head = java.nio.ByteBuffer.allocate(16).putLong(lastIncludedIndex).putLong(lastIncludedTerm);
        crc.update(head.array());
        crc.update(data);
        return (int) crc.getValue();
    }
}
