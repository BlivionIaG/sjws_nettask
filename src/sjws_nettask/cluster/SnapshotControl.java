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

    /**
     * Replace the state through {@code snapshot.lastIncludedIndex()}. Refused (nothing changes)
     * when the image exceeds {@link GroupOptions#maxSnapshotBytes()}, the index does not advance
     * past the current snapshot, or the local log holds that index with a different term. The image
     * is made durable before the log prefix it covers is deleted; a storage without durable
     * snapshots refuses.
     */
    void install(LogSnapshot snapshot);

    /** {@link #install(LogSnapshot)} after checking {@link LogSnapshot#crc32c()} against {@code expectedCrc32c}. */
    default void install(LogSnapshot snapshot, int expectedCrc32c) {
        if (snapshot.crc32c() != expectedCrc32c) {
            throw new IllegalArgumentException("snapshot fails its CRC32C; refused");
        }
        install(snapshot);
    }

    /**
     * Compact the snapshot, make it durable, then drop the log prefix at or below an index that is
     * both snapshotted and acked. A storage without durable snapshots refuses (the future fails and
     * nothing is deleted). A data follower whose next entry falls inside the dropped prefix
     * catches up from the leader through a chunked snapshot transfer.
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

    /**
     * Record an image the application built itself of state it has already applied through
     * {@code snapshot.lastIncludedIndex()} (#270: a log built from durable application data, such as
     * the messaging segments, snapshots at the point its own data is fsynced, which can trail the commit
     * index). The image is made durable (written off the group lock, so appends are not held up) and
     * becomes the snapshot sent to a follower behind the truncated prefix; nothing is truncated and the
     * listener is not called. Refused (nothing changes) when the image exceeds
     * {@link GroupOptions#maxSnapshotBytes()}, the index does not advance past the current snapshot, is
     * past the applied index, or the log holds it with another term. The default refuses.
     */
    default void recordSnapshot(LogSnapshot snapshot) {
        throw new UnsupportedOperationException("recordSnapshot");
    }

    /**
     * Use {@code bound} (null removes it) to limit prefix truncation: with a bound,
     * {@link #truncateAndCompact} never drops past it, and {@link #truncateToBound} truncates
     * through {@code min(snapshot index, bound)}. Automatic snapshots never truncate the log, with or
     * without a bound. With a bound set, a data follower whose next entry is still in the log (the tail
     * the bound keeps behind the snapshot) is sent entries rather than the snapshot. The default refuses.
     */
    default void setTruncationBound(TruncationBound bound) {
        throw new UnsupportedOperationException("truncation bound");
    }

    /**
     * Truncate through {@code min(snapshot index, bound)} and compact. Completes with the index the
     * log is now truncated through (unchanged when the bound allows nothing new); fails when no bound
     * is set or the bound throws, truncating nothing. The default refuses.
     */
    default CompletableFuture<Long> truncateToBound(Compactor compactor) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("truncation bound"));
    }
}
