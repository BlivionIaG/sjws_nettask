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
import java.util.Objects;

/** One applied user entry. Delivered on the group thread, in index order. */
public record CommittedEntry(GroupId groupId, long term, long index, ByteBuffer entry) {

    public CommittedEntry {
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(entry, "entry");
        entry = entry.asReadOnlyBuffer();
        if (term < 1 || index < 1) {
            throw new IllegalArgumentException("index");
        }
    }
}
