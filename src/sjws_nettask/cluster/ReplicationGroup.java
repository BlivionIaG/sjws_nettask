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

    /**
     * The current term if this node is leader in it, else {@code -1}: {@link #isLeader()} and
     * {@link #term()} read together, so a caller cannot pair a role with the wrong term.
     */
    default long leaderTerm() {
        long term = term();
        return isLeader() && term() == term ? term : -1L;
    }

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

    /**
     * Linearizable read. A lease read when this leader's lease is still valid. Fails with
     * {@link NotLeaderException} when this node does not lead or stops leading (or closes) before the read is
     * confirmed: a read has no outcome to lose, so it can simply be retried at the leader.
     */
    CompletableFuture<Void> readBarrier();

    /**
     * Joint consensus: old voters and the new set both have to commit, then only the new set. Refused with
     * {@link NotLeaderException} when this node does not lead. Once the joint config is in the log, losing
     * leadership, failing closed or closing fails it with {@link OutcomeUnknownException}: the change may still
     * complete under the next leader, so read the membership back before retrying (#254).
     */
    CompletableFuture<Void> changeMembership(Set<NodeId> voters, Set<NodeId> learners);

    void transferLeadership(NodeId target);

    /**
     * Feature bits this node advertises to the leader of this group, sent at the end of every append response
     * (#278). The bits are the group owner's, opaque to consensus; once set they stay set for the node's lifetime.
     * A group with none set sends byte-identical responses, and an older build ignores the extra bytes. The default
     * advertises nothing.
     */
    default void advertiseFeatures(int bits) {
    }

    /**
     * Leader only: whether every voter of the current configuration (joint configurations included) has every bit of
     * {@code bits}, this node from {@link #advertiseFeatures} and each peer from its latest append response to this
     * node. A peer this node has not heard from since it started counts as having none, and a peer that answers
     * without the bits loses them. False when this node does not lead. The default is false: unknown fails closed.
     */
    default boolean votersHaveFeatures(int bits) {
        return false;
    }
}
