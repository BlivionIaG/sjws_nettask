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

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Membership plus per-group replication. HTTP failover and Messaging Core are callers.
 * This package does not import HTTP. Total order holds inside one {@link GroupId} only.
 */
public interface ClusterNode extends AutoCloseable {

    NodeId self();

    MembershipView membership();

    void onMembershipChange(Consumer<MembershipView> listener);

    /** Idempotent. Wires the per-group callback and snapshot hooks. One transport, many groups. */
    ReplicationGroup openGroup(GroupId groupId, GroupOptions options, GroupListener listener);

    /**
     * Leader only; otherwise the future completes with {@link NotLeaderException}. The future
     * completes with the committed index after a quorum has the batch. Strict groups have also
     * fsynced it. One batch is one ordered span of indexes.
     */
    CompletableFuture<CommittedIndex> append(GroupId groupId, List<ByteBuffer> batch);

    /**
     * As {@link #append(GroupId, List)} with a per-batch durability. {@code null} uses the group's
     * {@link GroupOptions#durability()}. A STRICT batch in a RELAXED group completes only once the
     * leader and every data copy counted toward its commit have fsynced it (two data disks in the
     * two-data-node-plus-witness group); RELAXED batches around it keep acking from memory. A RELAXED
     * override in a STRICT group is still STRICT: a group never weakens below its option. Committing
     * a later index commits earlier ones, so a RELAXED batch behind a pending STRICT one waits for it.
     * The default implementation cannot honour an override, so it delegates {@code null} and fails
     * the future with {@link UnsupportedOperationException} otherwise.
     */
    default CompletableFuture<CommittedIndex> append(GroupId groupId, List<ByteBuffer> batch, Durability durability) {
        if (durability == null) {
            return append(groupId, batch);
        }
        var future = new CompletableFuture<CommittedIndex>();
        future.completeExceptionally(new UnsupportedOperationException("per-append durability"));
        return future;
    }

    /** Leadership of this group only. Invoked off the request path. */
    void onLeaderChange(GroupId groupId, BiConsumer<Long, Optional<NodeId>> listener);

    SnapshotControl snapshots(GroupId groupId);

    @Override
    void close();
}
