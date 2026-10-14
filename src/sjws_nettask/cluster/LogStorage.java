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

    /**
     * Last witness ack that {@link #force} has made durable, or 0. This is not {@link #lastIndex}:
     * a witness records the cursor without storing entries.
     */
    long witnessIndex(GroupId group) throws IOException;

    /** Term paired with {@link #witnessIndex}, or 0. */
    long witnessTerm(GroupId group) throws IOException;

    /**
     * Stage a witness ack when {@code (term, index)} is strictly ahead of the cursor. A lower pair
     * is ignored. The stage is durable only after {@link #force}.
     */
    void noteWitness(GroupId group, long index, long term) throws IOException;

    /**
     * Make {@code term} and {@code votedFor} (a wire id, 0 for none) durable before returning, so a
     * restarted voter cannot vote twice in one term. The default keeps nothing: a storage that does
     * not override it restarts at term 0 with no vote, which is only safe for a single voter.
     */
    default void saveHardState(GroupId group, long term, int votedFor) throws IOException {
        // Not persisted by default.
    }

    /**
     * True when {@link #saveHardState} has ever completed for this group. When false, a voter
     * starts at the term of its last log entry with no vote (an upgrade from a version that did
     * not persist it). Corrupt state must throw rather than read as absent.
     */
    default boolean hasHardState(GroupId group) throws IOException {
        return false;
    }

    /** Term last made durable by {@link #saveHardState}, or 0. */
    default long hardStateTerm(GroupId group) throws IOException {
        return 0L;
    }

    /** Vote (wire id) last made durable by {@link #saveHardState}, or 0 for none. */
    default int hardStateVote(GroupId group) throws IOException {
        return 0;
    }
}
