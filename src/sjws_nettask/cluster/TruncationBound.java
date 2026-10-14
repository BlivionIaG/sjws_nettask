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

/**
 * Application-supplied limit on Raft log prefix truncation. Installed with
 * {@link SnapshotControl#setTruncationBound}. The log may be dropped only through an index the
 * application no longer needs from Raft: its own state is durable through it, and nothing it
 * still serves (retention low watermark, the oldest consumer offset, the ledger low watermark)
 * lies at or below it.
 */
@FunctionalInterface
public interface TruncationBound {

    /**
     * The highest index the Raft log may be truncated through, given the current snapshot index.
     * Return {@code Long.MAX_VALUE} for no limit beyond the snapshot, or 0 to keep the whole log.
     * Called on the caller's thread, without the group lock held. A thrown exception truncates
     * nothing.
     */
    long truncatableThrough(GroupId group, long snapshotIndex) throws Exception;
}
