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

import java.io.IOException;

/** Append-only log for one process. Each group has its own index space. Indexes start at 1. */
public interface LogStorage {

    void append(GroupId group, long index, long term, byte[] data) throws IOException;

    byte[] read(GroupId group, long index) throws IOException;

    /** Term at {@code index}, or 0 when that index is not stored. */
    long termAt(GroupId group, long index) throws IOException;

    long lastIndex(GroupId group);

    /** Delete {@code fromIndex} and everything after it. */
    void truncateSuffix(GroupId group, long fromIndex) throws IOException;

    /** Delete every stored index at or below {@code throughIndex}. */
    void truncatePrefix(GroupId group, long throughIndex) throws IOException;

    void force(GroupId group) throws IOException;

    int forces(GroupId group);
}
