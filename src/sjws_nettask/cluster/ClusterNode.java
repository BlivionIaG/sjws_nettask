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
     * Leader only. The future completes with the committed index after a quorum has the batch; strict groups have
     * also fsynced it. One batch is one ordered span of indexes, and it is all-or-nothing. A failed future says
     * one of two things, and callers must tell them apart (#254):
     * <ul>
     * <li><b>Refused, nothing appended</b>: {@link NotLeaderException} (this node does not lead),
     * {@link TermChangedException}, {@link ApplyLagException}, {@link DurabilityUnavailableException}, an argument
     * error (empty batch, null entry, bad {@code expectedTerm}, too large), a fenced group, or a storage error on a
     * batch the leader rolled back cleanly. Retrying (at the leader) cannot duplicate it.
     * <li><b>Accepted, outcome unknown</b>: {@link OutcomeUnknownException}. The batch entered the log and may still
     * commit, or may be discarded: this node stopped leading before it committed (step-down, leadership transfer,
     * an election started as leader), the group failed closed after a log write it could not roll back (the
     * storage error is the cause; earlier batches still pending fail the same way), or the node closed. Treat it
     * like a timeout: never resend as if nothing had been appended; retry only with your own deduplication, or
     * read back. A node that no longer leads never reports a batch committed (#250).
     * </ul>
     * {@link CommittedNotAppliedException} means the batch committed but this node closed before applying it.
     * Every failure completes the future rather than throwing.
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

    /**
     * Conditional append: as {@link #append(GroupId, List, Durability)}, but only if this node leads
     * the group in {@code expectedTerm}. The leader role and term are checked together, under the same
     * lock as the log write, so a deposed leader's in-flight produce cannot land in a new term under the
     * old term's assumptions. Fails with {@link NotLeaderException} when this node does not lead, and
     * with {@link TermChangedException} when it leads in another term; nothing is appended in either case.
     * The check is at append time: once appended, the entry follows normal Raft rules (a later leader may
     * still commit it, so callers keep their own stale-term fence on apply). {@code expectedTerm} must be
     * positive. The default implementation cannot check the term atomically and fails the future with
     * {@link UnsupportedOperationException}.
     */
    default CompletableFuture<CommittedIndex> append(GroupId groupId, List<ByteBuffer> batch, Durability durability,
            long expectedTerm) {
        var future = new CompletableFuture<CommittedIndex>();
        future.completeExceptionally(new UnsupportedOperationException("conditional append"));
        return future;
    }

    /** Leadership of this group only. Invoked off the request path. */
    void onLeaderChange(GroupId groupId, BiConsumer<Long, Optional<NodeId>> listener);

    /**
     * Election no-op after it is applied. Args are term and applied index. Same registration
     * shape as {@link #onLeaderChange}. Invoked off the request path. Callers that gate serve
     * on leader-ready (Messaging Core, control plane) register here rather than casting to
     * {@code ReplicatedNode}.
     *
     * <p>It fires on every replica that applies the no-op, followers included, not only on the
     * leader that wrote it, and it may arrive after this node has already been deposed. A callback
     * proves only that every entry before the no-op has been applied here; a caller that gates on
     * leadership must re-check the live role and term together (for example
     * {@code group.leaderTerm() == term}) before serving.
     *
     * <p>The default throws {@link UnsupportedOperationException}, as a membership-only node does.
     */
    default void onNoopApplied(GroupId groupId, BiConsumer<Long, Long> listener) {
        throw new UnsupportedOperationException("onNoopApplied");
    }

    SnapshotControl snapshots(GroupId groupId);

    @Override
    void close();
}
