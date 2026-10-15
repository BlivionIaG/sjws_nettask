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

import java.util.concurrent.CompletableFuture;

/**
 * One replicated partition (msg-replication-sketch §0, phase A). Appends go through consensus and are applied, in
 * order, into the local {@link LedgerWriter}; fetches keep reading that local log (the applied data).
 *
 * <p>Messaging-side SPI only: implementations live outside the messaging packages and are installed by the HA bootstrap
 * through {@link PartitionReplicator}, so with HA off no consensus class loads (MessagingClusterIsolationTest).
 * Appends on a node that is not a ready leader fail with {@link LeaderUnavailableException}.
 */
public interface Replica extends PartitionAppender, AutoCloseable {

    /** Leader of the current term and its election no-op is applied: appends and offset commits are accepted. */
    boolean leaderReady();

    /** Current consensus term of this partition. */
    long term();

    /** Leader node name, or null when no leader is known. */
    String leaderName();

    /** Leader base URL for redirects (e.g. {@code http://n1:8080}), or null when unknown. */
    String leaderUrl();

    /**
     * Completes once this node is confirmed as the ready leader (lease or quorum round): a read served afterwards sees
     * every acknowledged append, since an append is acknowledged only after it is applied here. Fails with
     * {@link LeaderUnavailableException} otherwise.
     */
    CompletableFuture<Void> leaderRead();

    @Override
    void close();
}
