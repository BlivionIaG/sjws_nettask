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

import java.util.concurrent.CompletableFuture;

/** Snapshot and prefix truncation for one group. Off the request path. */
public interface SnapshotControl {

    /** Build a snapshot covering every committed entry through {@code lastIncludedIndex}. */
    LogSnapshot take(long lastIncludedIndex);

    void install(LogSnapshot snapshot);

    /**
     * Drop the log prefix at or below an index that is both snapshotted and acked, then compact.
     */
    CompletableFuture<Void> truncateAndCompact(long ackedThrough, Compactor compactor);

    /**
     * Use {@code taker} (null restores the built-in image) for automatic snapshots and for
     * {@link #take}. With a taker, {@link #take} accepts only the current commit index, because the
     * taker sees the application state at the point it runs. Pair it with
     * {@link GroupOptions#withoutAutoSnapshot()} to snapshot only when the application asks.
     * The default refuses.
     */
    default void setSnapshotTaker(SnapshotTaker taker) {
        throw new UnsupportedOperationException("snapshot taker");
    }
}
