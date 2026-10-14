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

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Live view of one Raft group on this node. */
public interface ReplicationGroup {

    GroupId groupId();

    long term();

    Optional<NodeId> leader();

    boolean isLeader();

    /** Monotonic nanos when this leader's lease ends. {@code 0} when this node is not leader. */
    long leaseExpiresAt();

    long commitIndex();

    /**
     * Highest index this node has fsynced to its own log. Under STRICT it is never behind an entry
     * this node acked; under RELAXED it trails by at most {@link GroupOptions#relaxedSyncDelay()}
     * (rounded up to a tick) and {@link GroupOptions#relaxedSyncEntries()} entries. A witness stores
     * no entries and reports 0. The default, for groups that cannot tell, reports 0.
     * Non-decreasing, except when this node truncates conflicting uncommitted entries: it then drops
     * to just below the first truncated index.
     */
    default long durableIndex() {
        return 0L;
    }

    long lastApplied();

    /** Linearizable read. A lease read when this leader's lease is still valid. */
    CompletableFuture<Void> readBarrier();

    /** Joint consensus: old voters and the new set both have to commit, then only the new set. */
    CompletableFuture<Void> changeMembership(Set<NodeId> voters, Set<NodeId> learners);

    void transferLeadership(NodeId target);
}
