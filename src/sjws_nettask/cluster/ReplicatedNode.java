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
import java.util.HashSet;
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
 * One process speaking the cluster API. Groups share one {@link ClusterTransport}; frames carry
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
    private final ClusterTransport transport;
    private final Map<Integer, NodeId> byWire = new LinkedHashMap<>();
    private final Map<GroupId, RaftGroup> groups = new LinkedHashMap<>();
    private volatile Duration tick;
    private volatile boolean running;
    /**
     * An append wakes the background loop so it replicates now instead of at the next tick. Before #182 a
     * closed-loop producer's next append landed inside the same step (its future completed on the Raft thread);
     * with futures completing on the delivery thread it arrived just after the step and waited a whole tick.
     */
    private final java.util.concurrent.Semaphore wake = new java.util.concurrent.Semaphore(0);
    private Thread thread;

    private ReplicatedNode(NodeId self, List<Member> members, Set<NodeId> voters, QuorumPolicy quorum, Clock clock,
            LogStorage storage, ClusterTransport transport, Duration tick) {
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
            Clock clock, LogStorage storage, ClusterTransport transport) {
        return new ReplicatedNode(self, members, voters, quorum, clock, storage, transport, null);
    }

    /** Opens with {@link VoterSet#policy()}, which is a majority and has already rejected a bare pair. */
    public static ReplicatedNode open(NodeId self, VoterSet voters, Clock clock, LogStorage storage,
            ClusterTransport transport) {
        Objects.requireNonNull(voters, "voters");
        return open(self, voters.members(), voters.voters(), voters.policy(), clock, storage, transport);
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

    /**
     * One manual step (tests, simulations, {@code CpStore.drive()}): ticks, frames, then waits up to
     * {@link #DRIVE_DELIVERY_WAIT} for the deliveries it caused, so callers see callbacks deterministically.
     * The background loop ({@link #begin}) never waits for a callback.
     */
    public void drive() {
        step();
        for (var group : groups.values()) {
            if (!group.onDeliveryThread()) {
                group.awaitDelivered(DRIVE_DELIVERY_WAIT);
            }
        }
    }

    /** Apply-lag limit for groups opened afterwards; tests lower it to exercise backpressure. */
    volatile int deliveryLagLimit = DeliveryExecutor.DEFAULT_LAG_LIMIT;

    /** How long {@link #drive()} waits for deliveries; a slower callback just finishes later. */
    static final Duration DRIVE_DELIVERY_WAIT = Duration.ofSeconds(5);

    /** {@link #drive()} without waiting for deliveries: what the background Raft thread runs. */
    void step() {
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

    boolean campaigning(GroupId groupId) {
        return group(groupId).campaigning();
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
                step();
                pause();
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
        var witnesses = new HashSet<NodeId>();
        for (var member : members) {
            if (member.witness()) {
                witnesses.add(member.id());
            }
            if (!member.id().equals(self)) {
                peers.add(member.id());
            }
        }
        var group = new RaftGroup(groupId, self, witness, witnesses, voters, peers, byWire::get, quorum, options,
                storage, rpc, clock, listener, deliveryLagLimit);
        groups.put(groupId, group);
        return group.facade();
    }

    @Override
    public CompletableFuture<CommittedIndex> append(GroupId groupId, List<ByteBuffer> batch) {
        return append(groupId, batch, null);
    }

    @Override
    public CompletableFuture<CommittedIndex> append(GroupId groupId, List<ByteBuffer> batch, Durability durability) {
        var group = groups.get(groupId);
        if (group == null) {
            var future = new CompletableFuture<CommittedIndex>();
            future.completeExceptionally(new IllegalStateException("no group " + groupId));
            return future;
        }
        var future = group.append(batch, durability);
        if (running && wake.availablePermits() == 0) {
            wake.release();
        }
        return future;
    }

    /** One tick, cut short by an append; a fake clock (tests) keeps its own deterministic sleep. */
    private void pause() throws InterruptedException {
        if (clock != Clock.SYSTEM) {
            clock.sleep(tick);
            return;
        }
        wake.tryAcquire(tick.toNanos(), java.util.concurrent.TimeUnit.NANOSECONDS);
        wake.drainPermits();
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
        for (var group : groups.values()) {
            group.closeDeliveries(CLOSE_DRAIN);
        }
        transport.detach(self);
    }

    /** STRICT override entries the group's leader holds uncommitted (0 on a follower). */
    public int strictPending(GroupId groupId) {
        return group(groupId).strictPendingCount();
    }

    /** Age of the oldest uncommitted STRICT override on the group's leader, 0 when none. */
    public long oldestStrictPendingAgeNanos(GroupId groupId) {
        return group(groupId).oldestStrictPendingAgeNanos();
    }

    /** Snapshot images one group skipped: the taker threw, returned null or exceeded maxSnapshotBytes. */
    public long skippedSnapshots(GroupId groupId) {
        return group(groupId).skippedSnapshots();
    }

    /** Apply lag of one group: deliveries queued or running. */
    public long deliveryLag(GroupId groupId) {
        return group(groupId).deliveryLag();
    }

    /** Deliveries of one group that {@link #close()} dropped after the drain budget (applied on reopen by replay). */
    public long abandonedDeliveries(GroupId groupId) {
        return group(groupId).abandonedDeliveries();
    }

    /** Times one group's apply lag crossed the warn threshold (4 x the lag limit; follower queues are unbounded). */
    public long deliveryLagWarnings(GroupId groupId) {
        return group(groupId).deliveryLagWarnings();
    }

    /** Highest apply lag seen by one group. */
    public long maxDeliveryLag(GroupId groupId) {
        return group(groupId).maxDeliveryLag();
    }

    /** {@link #close()} drains each group's deliveries this long, then abandons the rest. */
    static final Duration CLOSE_DRAIN = Duration.ofSeconds(1);
}
