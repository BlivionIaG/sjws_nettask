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
package sjws_nettask.cp;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import sjws_nettask.cluster.GroupId;
import sjws_nettask.cluster.NodeId;
import sjws_nettask.cluster.ReplicatedNode;
import sjws_nettask.cluster.ReplicationGroup;

/**
 * Leadership for one group. {@link #leaderReady()} becomes true only after this leader's election
 * no-op is applied. {@link #onLeaderChange} runs while {@link #leaderReady()} is still false.
 * <p>
 * Leader-change notices arrive through the group's delivery queue, so they can lag the live role while
 * deliveries are backed up (#212). {@link #leaderReady()} therefore also checks the live role and term:
 * a demoted node, or one in a later term, reads false at once rather than when the notice drains.
 */
public final class ControlLeadership {

    private final ReplicationGroup group;
    private final NodeId self;
    private final ReentrantLock lock = new ReentrantLock();
    private final List<BiConsumer<Long, Optional<String>>> changes = new ArrayList<>();
    private final List<BiConsumer<Long, Long>> ready = new ArrayList<>();
    /** Term whose election no-op was applied while this node led it; {@code -1} when not ready. */
    private volatile long readyTerm = -1L;
    private volatile String leaderId;

    private ControlLeadership(ReplicationGroup group, NodeId self) {
        this.group = group;
        this.self = self;
    }

    public static ControlLeadership attach(ReplicatedNode node, GroupId groupId, ReplicationGroup group, NodeId self) {
        var leadership = new ControlLeadership(group, self);
        node.onLeaderChange(groupId, leadership::onLeader);
        node.onNoopApplied(groupId, leadership::onNoop);
        return leadership;
    }

    public boolean isLeader() {
        return group.isLeader();
    }

    /**
     * True only if this node's no-op for the current term was applied and the node is still leader in
     * that same term. A follower returns from one volatile read; a ready leader takes the group lock once.
     */
    public boolean leaderReady() {
        long term = readyTerm;
        return term >= 0 && group.leaderTerm() == term;
    }

    public long term() {
        return group.term();
    }

    public Optional<String> leaderId() {
        return Optional.ofNullable(leaderId);
    }

    public void onLeaderChange(BiConsumer<Long, Optional<String>> listener) {
        lock.lock();
        try {
            changes.add(listener);
        } finally {
            lock.unlock();
        }
    }

    public void leaderReady(BiConsumer<Long, Long> listener) {
        lock.lock();
        try {
            ready.add(listener);
        } finally {
            lock.unlock();
        }
    }

    private void onLeader(long term, Optional<NodeId> leader) {
        boolean mine = leader.isPresent() && leader.get().equals(self);
        leaderId = leader.map(NodeId::name).orElse(null);
        if (!mine) {
            readyTerm = -1L;
        }
        List<BiConsumer<Long, Optional<String>>> copy;
        lock.lock();
        try {
            copy = List.copyOf(changes);
        } finally {
            lock.unlock();
        }
        var id = Optional.ofNullable(leaderId);
        for (var listener : copy) {
            listener.accept(term, id);
        }
    }

    private void onNoop(long term, long index) {
        if (group.leaderTerm() != term || readyTerm == term) {
            return;
        }
        readyTerm = term;
        List<BiConsumer<Long, Long>> copy;
        lock.lock();
        try {
            copy = List.copyOf(ready);
        } finally {
            lock.unlock();
        }
        for (var listener : copy) {
            listener.accept(term, index);
        }
    }
}
