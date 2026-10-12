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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * One process speaking the cluster API. Groups share {@link SimTransport}; frames carry
 * {@link GroupId}. {@link #open} does not start a thread — tests call {@link #drive}.
 * {@link #start} ticks on a virtual thread.
 */
public final class ReplicatedNode implements ClusterNode {

    private final NodeId self;
    private final boolean witness;
    private final Membership membership;
    private final List<Member> members;
    private final Set<NodeId> voters;
    private final QuorumPolicy quorum;
    private final Clock clock;
    private final LogStorage storage;
    private final ClusterRpc rpc;
    private final SimTransport transport;
    private final Map<Integer, NodeId> byWire = new LinkedHashMap<>();
    private final Map<GroupId, RaftGroup> groups = new LinkedHashMap<>();
    private volatile Duration tick;
    private volatile boolean running;
    private Thread thread;

    private ReplicatedNode(NodeId self, List<Member> members, Set<NodeId> voters, QuorumPolicy quorum, Clock clock,
            LogStorage storage, SimTransport transport, Duration tick) {
        this.self = Objects.requireNonNull(self, "self");
        this.members = List.copyOf(members);
        this.voters = Set.copyOf(voters);
        this.quorum = Objects.requireNonNull(quorum, "quorum");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.tick = tick;
        boolean witness = false;
        boolean found = false;
        for (var member : this.members) {
            if (byWire.put(member.id().wire(), member.id()) != null) {
                throw new IllegalArgumentException("duplicate wire");
            }
            if (member.id().equals(self)) {
                found = true;
                witness = member.witness();
            }
        }
        if (!found) {
            throw new IllegalArgumentException("self is not a member");
        }
        this.witness = witness;
        long now = clock.nanoTime();
        this.membership = new Membership(self, this.members, Duration.ofMillis(500).toNanos(),
                Duration.ofSeconds(5).toNanos(), now, ClusterEvents.NONE);
        this.rpc = transport.attach(self, (from, frame) -> {
            var group = groups.get(frame.group());
            if (group != null) {
                group.handle(from, frame, clock.nanoTime());
            }
        });
    }

    /** Membership and replication without a background thread. */
    public static ReplicatedNode open(NodeId self, List<Member> members, Set<NodeId> voters, QuorumPolicy quorum,
            Clock clock, LogStorage storage, SimTransport transport) {
        return new ReplicatedNode(self, members, voters, quorum, clock, storage, transport, null);
    }

    /** Tick and deliver on a virtual thread. Groups should already be open. */
    public void begin(Duration interval) {
        if (interval == null || interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("tick");
        }
        this.tick = interval;
        if (thread != null) {
            return;
        }
        running = true;
        thread = Thread.ofVirtual().name("raft-" + self.name()).start(this::loop);
    }

    public static void driveAll(List<ReplicatedNode> nodes) {
        for (int round = 0; round < 32; round++) {
            for (var node : nodes) {
                node.drive();
            }
        }
    }

    public void drive() {
        long now = clock.nanoTime();
        for (var group : groups.values()) {
            group.tick(now);
        }
        int spins = 0;
        while (transport.deliver(64) > 0 && spins++ < 100) {
            // Handlers enqueue the replies that the next deliver consumes.
        }
    }

    boolean forcedBeforeAck(GroupId groupId) {
        return group(groupId).forcedBeforeAck();
    }

    boolean ackedFromMemory(GroupId groupId) {
        return group(groupId).ackedFromMemory();
    }

    Set<NodeId> voters(GroupId groupId) {
        return group(groupId).voters();
    }

    private RaftGroup group(GroupId groupId) {
        var group = groups.get(groupId);
        if (group == null) {
            throw new IllegalStateException("no group " + groupId);
        }
        return group;
    }

    private void loop() {
        while (running) {
            try {
                drive();
                clock.sleep(tick);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            } catch (RuntimeException failed) {
                try {
                    clock.sleep(tick);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    @Override
    public NodeId self() {
        return self;
    }

    public boolean witness() {
        return witness;
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
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(options, "options");
        var existing = groups.get(groupId);
        if (existing != null) {
            existing.setListener(listener);
            return existing.facade();
        }
        var peers = new ArrayList<NodeId>();
        for (var member : members) {
            if (!member.id().equals(self)) {
                peers.add(member.id());
            }
        }
        var group = new RaftGroup(groupId, self, witness, voters, peers, byWire::get, quorum, options, storage, rpc,
                clock, listener);
        groups.put(groupId, group);
        return group.facade();
    }

    @Override
    public CompletableFuture<CommittedIndex> append(GroupId groupId, List<ByteBuffer> batch) {
        var group = groups.get(groupId);
        if (group == null) {
            var future = new CompletableFuture<CommittedIndex>();
            future.completeExceptionally(new IllegalStateException("no group " + groupId));
            return future;
        }
        return group.append(batch);
    }

    @Override
    public void onLeaderChange(GroupId groupId, BiConsumer<Long, Optional<NodeId>> listener) {
        group(groupId).addLeaderListener(listener);
    }

    /** The election no-op, after it is applied. {@code term} and the applied index. */
    public void onNoopApplied(GroupId groupId, BiConsumer<Long, Long> listener) {
        group(groupId).addNoopListener(listener);
    }

    @Override
    public SnapshotControl snapshots(GroupId groupId) {
        return group(groupId).facade();
    }

    @Override
    public void close() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(1_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
