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
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** A node whose membership is kept fresh by {@link HeartbeatService}. The caller owns the link. */
public final class LocalCluster implements ClusterNode {

    private final Membership membership;
    private final HeartbeatService heartbeats;

    private LocalCluster(Membership membership, HeartbeatService heartbeats) {
        this.membership = membership;
        this.heartbeats = heartbeats;
    }

    public static LocalCluster start(NodeId self, List<Member> members, PacketLink link, Clock clock,
            Duration interval, ClusterKey key) {
        return start(self, members, link, clock, interval, Duration.ofSeconds(5), ClusterEvents.NONE, key);
    }

    public static LocalCluster start(NodeId self, List<Member> members, PacketLink link, Clock clock,
            Duration interval, Duration deadAfter, ClusterEvents events, ClusterKey key) {
        Objects.requireNonNull(deadAfter, "deadAfter");
        Objects.requireNonNull(key, "cluster key");
        long now = clock.nanoTime();
        var membership = new Membership(self, members, interval.toNanos(), deadAfter.toNanos(), now, events);
        var heartbeats = new HeartbeatService(membership, link, clock, interval, events, key);
        heartbeats.start();
        return new LocalCluster(membership, heartbeats);
    }

    /** One detector step without the background thread. Tests drive a fake clock through this. */
    public static LocalCluster open(NodeId self, List<Member> members, PacketLink link, Clock clock,
            Duration interval, Duration deadAfter, ClusterEvents events, ClusterKey key) {
        Objects.requireNonNull(key, "cluster key");
        long now = clock.nanoTime();
        var membership = new Membership(self, members, interval.toNanos(), deadAfter.toNanos(), now, events);
        var heartbeats = new HeartbeatService(membership, link, clock, interval, events, key);
        return new LocalCluster(membership, heartbeats);
    }

    public void announce(long term, long generation, long contentHash, int leaseRemainingMs) {
        heartbeats.announce(term, generation, contentHash, leaseRemainingMs);
    }

    public void cycle() {
        heartbeats.cycle();
    }

    public int received() {
        return heartbeats.received();
    }

    public int sendFailures() {
        return heartbeats.sendFailures();
    }

    public int rejected() {
        return heartbeats.rejected();
    }

    public int rejected(HeartbeatAuthenticator.Reason reason) {
        return heartbeats.rejected(reason);
    }

    public Membership detector() {
        return membership;
    }

    @Override
    public NodeId self() {
        return membership.self();
    }

    @Override
    public MembershipView membership() {
        return membership.view();
    }

    @Override
    public void onMembershipChange(Consumer<MembershipView> listener) {
        membership.onMembershipChange(listener);
    }

    @Override
    public ReplicationGroup openGroup(GroupId groupId, GroupOptions options, GroupListener listener) {
        throw new UnsupportedOperationException("membership-only node");
    }

    @Override
    public CompletableFuture<CommittedIndex> append(GroupId groupId, List<ByteBuffer> batch) {
        throw new UnsupportedOperationException("membership-only node");
    }

    @Override
    public void onLeaderChange(GroupId groupId, BiConsumer<Long, Optional<NodeId>> listener) {
        throw new UnsupportedOperationException("membership-only node");
    }

    @Override
    public SnapshotControl snapshots(GroupId groupId) {
        throw new UnsupportedOperationException("membership-only node");
    }

    @Override
    public void close() {
        heartbeats.close();
    }
}
