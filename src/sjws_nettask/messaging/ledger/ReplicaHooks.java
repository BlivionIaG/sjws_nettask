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
     * truncated (constraint 2 of #270): producer dedup (producerId, epoch, last seq). The replica captures it right
     * after an applied batch and restores it with a snapshot; entries after the capture point are then applied again,
     * so {@link #restoreState} plus re-applying them must give the same state (true of a last-seq-per-producer table).
     */
    public interface ReplicatedState {

        /** The current state, serialized. Called on the thread that applies batches. */
        byte[] snapshotState();

        /** Replace the state with a {@link #snapshotState} image (snapshot restore or install). */
        void restoreState(byte[] image);
    }
}
