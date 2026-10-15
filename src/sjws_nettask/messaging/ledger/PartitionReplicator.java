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

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Opens one {@link Replica} per replicated topic (msg-replication-sketch §0). Installed by the HA bootstrap with
 * {@code MessagingModule.replication}; never referenced when HA is off.
 */
public interface PartitionReplicator extends AutoCloseable {

    /** Topics replicated by this node; opened eagerly so followers receive their entries. */
    Set<String> topics();

    /**
     * Opens the replica of {@code topic} over its local writer. {@code onApplied} sees every applied batch in log
     * order, after it is in {@code local}. {@code defaultMode} is used for appends with a null mode.
     */
    Replica open(String topic, LedgerWriter local, Durability defaultMode, Consumer<List<byte[]>> onApplied) throws IOException;

    @Override
    void close();
}
