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
package sjws_nettask.messaging.ledger;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.Lock;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

/**
 * What a replicated topic gives its {@link Replica} beyond the writer (#270 snapshots). Built by the messaging module
 * only when HA replication is installed, so with HA off this type never loads.
 *
 * @param onApplied    every applied batch in log order, after it is in the local writer
 * @param dropBelow    a replicated "advance log start" decision: delete whole sealed segments that end at or below the
 *                     offset (called on every replica in log order, never from a local clock)
 * @param readersOut   excludes fetches while the replica swaps the local log for an installed snapshot
 * @param reloaded     after a snapshot install replaced the local log: rebuild anything derived from it (consumer
 *                     offsets re-read {@code __offsets})
 * @param state        application state that must survive log truncation (producer dedup, M2), or null
 */
public record ReplicaHooks(Consumer<List<byte[]>> onApplied, LongConsumer dropBelow, Lock readersOut, Runnable reloaded,
        ReplicatedState state) {

    public ReplicaHooks {
        Objects.requireNonNull(onApplied, "onApplied");
        Objects.requireNonNull(dropBelow, "dropBelow");
        Objects.requireNonNull(readersOut, "readersOut");
        Objects.requireNonNull(reloaded, "reloaded");
    }

    /**
     * State a partition keeps outside its records that a snapshot must carry once the consensus log behind it is
     * truncated (constraint 2 of #270): producer dedup (producerId, epoch, last seq). The replica calls
     * {@code onApplied} and captures this state under one apply lock, so an image is exactly the state after the
     * snapshot index's batch; it is restored before the next entry applies (restart, or install). On restart, entries
     * after the snapshot that are already in the ledger are fed to {@code onApplied} again, so
     * {@link #restoreState} plus re-applying them must give the same state (true of a last-seq-per-producer table).
     */
    public interface ReplicatedState {

        /** Snapshot section for the replica's general application state. */
        int APP_STATE = 1;
        /** Snapshot section reserved for the producer dedup table (#278, its own versioned encoding). */
        int PRODUCERS = 2;

        /** The snapshot section this state is stored in: {@link #APP_STATE} unless overridden. */
        default int section() {
            return APP_STATE;
        }

        /** The current state, serialized. Called under the replica's apply lock: keep it fast (it delays applies). */
        byte[] snapshotState();

        /** Replace the state with a {@link #snapshotState} image (snapshot restore or install). */
        void restoreState(byte[] image);
    }
}
