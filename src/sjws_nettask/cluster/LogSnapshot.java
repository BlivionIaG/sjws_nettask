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
}
