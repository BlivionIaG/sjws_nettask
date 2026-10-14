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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * One Raft group. A witness votes and acks without storing entries. Before that ack, and again
 * before a vote grant, it forces the last acked {@code (index, term)} and refuses a candidate
 * whose log is behind that mark. A campaign asks for a pre-vote first, so a partitioned
 * candidate cannot raise the term until a majority would elect it. User bytes are not the
 * on-disk record: a leading marker separates user data, configuration, and the leader's no-op.
 */
final class RaftGroup {

    static final byte USER = 0;
    static final byte JOINT = 1;
    static final byte NEW_CONFIG = 2;
    static final byte NOOP = 3;

    static final int VOTE = 1;
    static final int VOTE_RESP = 2;
    static final int APPEND = 3;
    static final int APPEND_RESP = 4;
    static final int TIMEOUT_NOW = 5;
    static final int PRE_VOTE = 6;
    static final int PRE_VOTE_RESP = 7;

    private final ReentrantLock lock = new ReentrantLock();
    /**
     * Committed-entry callbacks and their append futures, submitted under {@link #lock} in log order and
     * run by this group's single delivery thread, so a listener sees entries strictly in index order and
     * never concurrently, and no Raft or appending thread ever runs or waits for a callback.
     */
    private final DeliveryExecutor deliveries;
    private final GroupId groupId;
    private final NodeId self;
    private final boolean witness;
    private final Set<NodeId> witnesses;
    private final List<NodeId> peers;
    private final Function<Integer, NodeId> byWire;
    private final QuorumPolicy quorum;
    private final GroupOptions options;
    private final LogStorage storage;
    private final ClusterRpc rpc;
    private final Clock clock;
    private final GroupFacade facade = new GroupFacade();
    private GroupListener listener;
    private final List<java.util.function.BiConsumer<Long, Optional<NodeId>>> leaderListeners = new ArrayList<>();
    private final List<java.util.function.BiConsumer<Long, Long>> noopListeners = new ArrayList<>();

    private long term;
    private NodeId votedFor;
    private Role role = Role.FOLLOWER;
    private NodeId leader;
    private long commitIndex;
    private long lastApplied;
    private long electionDeadline;
    private long campaignNotBefore;
    private long leaseExpires;
    private long nextHeartbeat;
    private final Set<NodeId> voters = new HashSet<>();
    private final Set<NodeId> learners = new HashSet<>();
    private Set<NodeId> jointOld;
    private Set<NodeId> jointNew;
    private boolean configFollowUpSent;
    private final Set<NodeId> votes = new HashSet<>();
    private final Map<NodeId, Long> matchIndex = new HashMap<>();
    private final Map<NodeId, Long> nextIndex = new HashMap<>();
    private final TreeMap<Long, CompletableFuture<CommittedIndex>> pending = new TreeMap<>();
    private final List<CompletableFuture<Void>> reads = new ArrayList<>();
    private CompletableFuture<Void> membershipChange;
    private long snapIndex;
    private long snapTerm;
    private byte[] snapData = new byte[0];
    private boolean relaxedDirty;
    private boolean forcedBeforeAck;
    private boolean ackedFromMemory;
    /** Highest index this node has forced to its own disk (data nodes only). */
    private long durableIndex;
    /**
     * A data voter that restarts with state on disk may have lost entries it acked from memory
     * (RELAXED). Until it catches up to a leader's commit index, or {@link #recoveryUntil} passes,
     * it neither grants votes or pre-votes nor campaigns, unless it alone is a quorum.
     */
    private boolean recovering;
    private long recoveryUntil;

    private enum Role {
        FOLLOWER, PRE_CANDIDATE, CANDIDATE, LEADER
    }

    RaftGroup(GroupId groupId, NodeId self, boolean witness, Set<NodeId> witnesses, Set<NodeId> voters,
            List<NodeId> peers, Function<Integer, NodeId> byWire, QuorumPolicy quorum, GroupOptions options,
            LogStorage storage, ClusterRpc rpc, Clock clock, GroupListener listener, int deliveryLagLimit) {
        this.groupId = groupId;
        this.self = self;
        this.witness = witness;
        this.witnesses = Set.copyOf(witnesses);
        this.peers = List.copyOf(peers);
        this.byWire = byWire;
        this.quorum = quorum;
        this.options = options;
        this.storage = storage;
        this.rpc = rpc;
        this.clock = clock;
        this.listener = listener;
        this.deliveries = new DeliveryExecutor(groupId.value(), deliveryLagLimit);
        this.voters.addAll(voters);
        try {
            if (storage.hasHardState(groupId)) {
                this.term = storage.hardStateTerm(groupId);
                int vote = storage.hardStateVote(groupId);
                this.votedFor = vote == 0 ? null : byWire.apply(vote);
            } else {
                // Upgrade (or first start): no vote file. Terms only grow along the log, so the
                // last entry carries the highest term this node can have voted in. Treat that term
                // as already voted (for ourselves), so the node cannot grant a second vote in it.
                // A witness keeps no entries; its forced cursor carries the term it last acked.
                long last = witness ? 0L : storage.lastIndex(groupId);
                this.term = witness ? storage.witnessTerm(groupId) : last == 0 ? 0L : storage.termAt(groupId, last);
                this.votedFor = term == 0 ? null : self;
            }
        } catch (IOException failed) {
            throw new UncheckedIOException("cannot load the durable term/vote of " + groupId, failed);
        }
        if (!witness && (term > 0 || lastIndex() > 0)) {
            this.durableIndex = lastIndex();
            this.recovering = true;
            this.recoveryUntil = clock.nanoTime() + options.leaseNanos() + 2 * options.electionTimeout().toNanos();
        }
        if (options.preferred()) {
            this.electionDeadline = Math.max(1L, options.electionTimeout().toNanos() / 5);
        } else {
            this.electionDeadline = staggeredTimeout();
        }
    }

    GroupFacade facade() {
        return facade;
    }

    void setListener(GroupListener listener) {
        lock.lock();
        try {
            this.listener = listener;
        } finally {
            lock.unlock();
        }
    }

    void addLeaderListener(java.util.function.BiConsumer<Long, Optional<NodeId>> listener) {
        Objects.requireNonNull(listener, "listener");
        lock.lock();
        try {
            leaderListeners.add(listener);
        } finally {
            lock.unlock();
        }
    }

    /** Fired after the election no-op is applied, with that entry's term and index. */
    void addNoopListener(java.util.function.BiConsumer<Long, Long> listener) {
        Objects.requireNonNull(listener, "listener");
        lock.lock();
        try {
            noopListeners.add(listener);
        } finally {
            lock.unlock();
        }
    }

    boolean forcedBeforeAck() {
        return forcedBeforeAck;
    }

    boolean ackedFromMemory() {
        return ackedFromMemory;
    }

    /** True while a pre-vote or a vote is in progress. An isolated pre-vote does not raise the term. */
    boolean campaigning() {
        lock.lock();
        try {
            return role == Role.PRE_CANDIDATE || role == Role.CANDIDATE;
        } finally {
            lock.unlock();
        }
    }

    Set<NodeId> voters() {
        lock.lock();
        try {
            return Set.copyOf(voters);
        } finally {
            lock.unlock();
        }
    }

    void tick(long now) {
        var after = new ArrayList<Runnable>();
        lock.lock();
        try {
            if (relaxedDirty) {
                force();
                relaxedDirty = false;
                if (role == Role.LEADER) {
                    maybeCommit(after);
                }
            }
            if (recovering && now >= recoveryUntil) {
                recovering = false;
            }
            if (role == Role.LEADER) {
                if (now >= nextHeartbeat) {
                    nextHeartbeat = now + options.heartbeat().toNanos();
                    replicate();
                }
            } else if (!witness && now >= electionDeadline && now >= campaignNotBefore && !abstaining()) {
                startPreVote(now, after);
            }
        } finally {
            lock.unlock();
        }
        runOnRaftThread(after);
    }

    void handle(NodeId from, SimTransport.Frame frame, long now) {
        var after = new ArrayList<Runnable>();
        lock.lock();
        try {
            var body = ByteBuffer.wrap(frame.body()).order(java.nio.ByteOrder.BIG_ENDIAN);
            switch (frame.type()) {
                case VOTE -> onVote(body, now, after);
                case VOTE_RESP -> onVoteResp(body, now, after);
                case PRE_VOTE -> onPreVote(body, now, after);
                case PRE_VOTE_RESP -> onPreVoteResp(body, now, after);
                case APPEND -> onAppend(from, body, now, after);
                case APPEND_RESP -> onAppendResp(body, now, after);
                case TIMEOUT_NOW -> onTimeoutNow(body, now, after);
                default -> {
                    // Unknown frames are ignored so a newer peer cannot crash an older one.
                }
            }
        } finally {
            lock.unlock();
        }
        runOnRaftThread(after);
    }

    CompletableFuture<CommittedIndex> append(List<ByteBuffer> batch) {
        if (batch == null || batch.isEmpty()) {
            throw new IllegalArgumentException("batch");
        }
        var copies = new ArrayList<byte[]>(batch.size());
        int total = 0;
        for (var buf : batch) {
            Objects.requireNonNull(buf, "entry");
            var copy = new byte[buf.remaining()];
            buf.duplicate().get(copy);
            copies.add(copy);
            total += copy.length;
        }
        var future = new CompletableFuture<CommittedIndex>();
        var after = new ArrayList<Runnable>();
        lock.lock();
        try {
            if (role != Role.LEADER) {
                future.completeExceptionally(notLeader());
                return future;
            }
            if (deliveries.overLimit()) {
                future.completeExceptionally(new ApplyLagException(groupId, deliveries.lag(), deliveries.lagLimit()));
                return future;
            }
            if (total > options.maxBatchBytes()) {
                future.completeExceptionally(new IllegalArgumentException("batch exceeds maxBatchBytes"));
                return future;
            }
            long last = 0;
            for (var copy : copies) {
                last = appendRaw(user(copy));
            }
            pending.put(last, future);
            if (options.durability() == Durability.STRICT) {
                force();
            } else {
                relaxedDirty = true;
            }
            maybeCommit(after);
            replicate();
        } finally {
            lock.unlock();
        }
        run(after);
        return future;
    }

    /** Ask whether a majority would elect us. The term stays put until that comes back true. */
    private void startPreVote(long now, List<Runnable> after) {
        role = Role.PRE_CANDIDATE;
        votes.clear();
        votes.add(self);
        electionDeadline = now + retryTimeout();
        if (isQuorum(votes)) {
            startElection(now, after);
            return;
        }
        send(PRE_VOTE, voteBody(term));
    }

    private void startElection(long now, List<Runnable> after) {
        if (!saveHardState(term + 1, self)) {
            // Nothing changed in memory and nothing is sent; retry after a fresh timeout.
            role = Role.FOLLOWER;
            votes.clear();
            electionDeadline = now + retryTimeout();
            return;
        }
        term++;
        role = Role.CANDIDATE;
        votedFor = self;
        leader = null;
        votes.clear();
        votes.add(self);
        electionDeadline = now + retryTimeout();
        send(VOTE, voteBody(term));
        if (isQuorum(votes)) {
            becomeLeader(now, after);
        }
    }

    /**
     * The election timeout plus a fresh per-node jitter in {@code [0, election)}. Redrawn on
     * every retry so two candidates that share a first-deadline slot (sparse wire ids such as
     * 1 and 7 with three voters) cannot split the vote forever. The wait stays under
     * {@code 2 * election}, unlike the old {@code (wire - 1) * (election / 5)} growth.
     */
    private long retryTimeout() {
        long election = options.electionTimeout().toNanos();
        long jitter = election == 0 ? 0 : ThreadLocalRandom.current().nextLong(election);
        return election + jitter;
    }

    /**
     * First-deadline stagger: {@code ((wire - 1) mod voterCount) * (election / voterCount)}.
     * Contiguous ids {@code 1..n} keep node 1 first; sparse ids stay inside one election period.
     */
    private long staggeredTimeout() {
        long election = options.electionTimeout().toNanos();
        int count = Math.max(1, voters.size());
        return election + (long) Math.floorMod(self.wire() - 1, count) * (election / count);
    }

    private byte[] voteBody(long voteTerm) {
        long last = lastIndex();
        var body = ByteBuffer.allocate(8 + 4 + 8 + 8).order(java.nio.ByteOrder.BIG_ENDIAN);
        body.putLong(voteTerm);
        body.putInt(self.wire());
        body.putLong(last);
        body.putLong(termAt(last));
        return body.array();
    }

    private void becomeLeader(long now, List<Runnable> after) {
        role = Role.LEADER;
        leader = self;
        leaseExpires = now + options.leaseNanos();
        nextHeartbeat = now + options.heartbeat().toNanos();
        for (var peer : peers) {
            nextIndex.put(peer, lastIndex() + 1);
            matchIndex.put(peer, 0L);
        }
        appendRaw(new byte[] {NOOP});
        // Leadership is visible before the no-op callback, so leaderReady stays false in onLeaderChange.
        fireLeader(after);
        maybeCommit(after);
        replicate();
    }

    private void onVote(ByteBuffer body, long now, List<Runnable> after) {
        long theirTerm = body.getLong();
        int candidateWire = body.getInt();
        long lastIndex = body.getLong();
        long lastTerm = body.getLong();
        if (theirTerm > term) {
            if (!stepDown(theirTerm, after)) {
                return;
            }
        }
        var candidate = byWire.apply(candidateWire);
        boolean grant = false;
        if (theirTerm >= term && candidate != null && (!witness || witnessForced())) {
            long ourLast = witness ? witnessCursorIndex() : lastIndex();
            long ourTerm = witness ? witnessCursorTerm() : termAt(ourLast);
            boolean upToDate = lastTerm > ourTerm || (lastTerm == ourTerm && lastIndex >= ourLast);
            if (upToDate && !abstaining() && (votedFor == null || votedFor.equals(candidate))) {
                grant = candidate.equals(votedFor) || saveHardState(term, candidate);
                if (grant) {
                    votedFor = candidate;
                }
            }
            if (grant) {
                electionDeadline = now + options.leaseNanos() + options.electionTimeout().toNanos();
            }
        }
        var reply = ByteBuffer.allocate(8 + 4 + 1).order(java.nio.ByteOrder.BIG_ENDIAN);
        reply.putLong(term);
        reply.putInt(self.wire());
        reply.put((byte) (grant ? 1 : 0));
        if (candidate != null) {
            rpc.send(candidate, new SimTransport.Frame(groupId, VOTE_RESP, reply.array()));
        }
    }

    private void onVoteResp(ByteBuffer body, long now, List<Runnable> after) {
        long theirTerm = body.getLong();
        int voterWire = body.getInt();
        boolean granted = body.get() == 1;
        if (theirTerm > term) {
            stepDown(theirTerm, after); // a failed write leaves memory unchanged either way
            return;
        }
        if (role != Role.CANDIDATE || theirTerm != term || !granted) {
            return;
        }
        var voter = byWire.apply(voterWire);
        if (voter != null) {
            votes.add(voter);
        }
        if (isQuorum(votes)) {
            becomeLeader(now, after);
        }
    }

    /**
     * A pre-vote does not change {@code term} or {@code votedFor}. A node that still has a leader
     * refuses, so a partitioned candidate cannot preempt the side that holds a quorum.
     */
    private void onPreVote(ByteBuffer body, long now, List<Runnable> after) {
        long theirTerm = body.getLong();
        int candidateWire = body.getInt();
        long candidateIndex = body.getLong();
        long candidateTerm = body.getLong();
        var candidate = byWire.apply(candidateWire);
        boolean grant = false;
        // A pre-candidate has already timed out on its leader, and its own pre-vote moved
        // electionDeadline, so that deadline no longer says the leader is fresh.
        boolean leaderFresh = role == Role.LEADER
                || (role == Role.FOLLOWER && leader != null && now < electionDeadline);
        if (theirTerm >= term && candidate != null && !leaderFresh && !abstaining()
                && (!witness || witnessForced())) {
            long ourLast = witness ? witnessCursorIndex() : lastIndex();
            long ourTerm = witness ? witnessCursorTerm() : termAt(ourLast);
            grant = candidateTerm > ourTerm || (candidateTerm == ourTerm && candidateIndex >= ourLast);
        }
        var reply = ByteBuffer.allocate(8 + 4 + 1).order(java.nio.ByteOrder.BIG_ENDIAN);
        // Only our own (durable) term goes out; the pre-candidate accepts a grant at a term <= its own.
        reply.putLong(term);
        reply.putInt(self.wire());
        reply.put((byte) (grant ? 1 : 0));
        if (candidate != null) {
            rpc.send(candidate, new SimTransport.Frame(groupId, PRE_VOTE_RESP, reply.array()));
        }
    }

    private void onPreVoteResp(ByteBuffer body, long now, List<Runnable> after) {
        long theirTerm = body.getLong();
        int voterWire = body.getInt();
        boolean granted = body.get() == 1;
        if (theirTerm > term) {
            stepDown(theirTerm, after); // a failed write leaves memory unchanged either way
            return;
        }
        if (role != Role.PRE_CANDIDATE || theirTerm > term || !granted) {
            return;
        }
        var voter = byWire.apply(voterWire);
        if (voter != null) {
            votes.add(voter);
        }
        if (isQuorum(votes)) {
            startElection(now, after);
        }
    }

    private void onAppend(NodeId from, ByteBuffer body, long now, List<Runnable> after) {
        long theirTerm = body.getLong();
        int leaderWire = body.getInt();
        long prevIndex = body.getLong();
        long prevTerm = body.getLong();
        long leaderCommit = body.getLong();
        int count = body.getInt();
        var entries = new ArrayList<Rec>(count);
        for (int i = 0; i < count; i++) {
            long entryTerm = body.getLong();
            int len = body.getInt();
            var data = new byte[len];
            body.get(data);
            entries.add(new Rec(entryTerm, data));
        }
        if (theirTerm < term) {
            ack(from, false, lastIndex());
            return;
        }
        if (theirTerm > term) {
            if (!stepDown(theirTerm, after)) {
                return;
            }
        }
        if (role == Role.CANDIDATE || role == Role.PRE_CANDIDATE || role == Role.LEADER) {
            role = Role.FOLLOWER;
        }
        var newLeader = byWire.apply(leaderWire);
        if (newLeader != null && !newLeader.equals(leader)) {
            leader = newLeader;
            fireLeader(after);
        }
        electionDeadline = now + options.leaseNanos() + options.electionTimeout().toNanos();
        leaseExpires = now + options.leaseNanos();
        if (witness) {
            long ackIndex = prevIndex + entries.size();
            long ackTerm = entries.isEmpty() ? prevTerm : entries.get(entries.size() - 1).term;
            if (witnessRemember(ackIndex, ackTerm)) {
                ack(from, true, ackIndex);
            }
            return;
        }
        if (!matches(prevIndex, prevTerm)) {
            ack(from, false, lastIndex());
            return;
        }
        long index = prevIndex;
        for (var entry : entries) {
            index++;
            long existing = termAt(index);
            if (existing != entry.term) {
                if (existing != 0) {
                    truncateSuffix(index);
                }
                appendStored(index, entry.term, entry.data);
            }
        }
        if (options.durability() == Durability.STRICT) {
            force();
            forcedBeforeAck = true;
        } else {
            relaxedDirty = true;
            ackedFromMemory = true;
        }
        ack(from, true, index);
        if (leaderCommit > commitIndex) {
            commitIndex = Math.min(leaderCommit, lastIndex());
            apply(after);
        }
        if (recovering && commitIndex >= leaderCommit) {
            recovering = false;
        }
    }

    private void onAppendResp(ByteBuffer body, long now, List<Runnable> after) {
        long theirTerm = body.getLong();
        int followerWire = body.getInt();
        boolean success = body.get() == 1;
        long match = body.getLong();
        if (theirTerm > term) {
            stepDown(theirTerm, after); // a failed write leaves memory unchanged either way
            return;
        }
        if (role != Role.LEADER || theirTerm != term) {
            return;
        }
        var follower = byWire.apply(followerWire);
        if (follower == null) {
            return;
        }
        if (!success) {
            long next = nextIndex.getOrDefault(follower, lastIndex() + 1) - 1;
            nextIndex.put(follower, Math.max(snapIndex + 1, next));
            return;
        }
        matchIndex.put(follower, Math.max(matchIndex.getOrDefault(follower, 0L), match));
        nextIndex.put(follower, matchIndex.get(follower) + 1);
        maybeCommit(after);
        if (quorumFor(lastIndex())) {
            leaseExpires = now + options.leaseNanos();
            for (var read : reads) {
                after.add(() -> read.complete(null));
            }
            reads.clear();
        }
    }

    private void onTimeoutNow(ByteBuffer body, long now, List<Runnable> after) {
        long theirTerm = body.getLong();
        if (witness) {
            return;
        }
        if (theirTerm > term) {
            if (!stepDown(theirTerm, after)) {
                return;
            }
        }
        electionDeadline = now;
        startElection(now, after);
    }

    private void replicate() {
        for (var peer : peers) {
            long next = nextIndex.getOrDefault(peer, lastIndex() + 1);
            if (next <= snapIndex) {
                var body = ByteBuffer.allocate(8 + 4 + 8 + 8 + 8 + 4).order(java.nio.ByteOrder.BIG_ENDIAN);
                body.putLong(term);
                body.putInt(self.wire());
                body.putLong(snapIndex);
                body.putLong(snapTerm);
                body.putLong(commitIndex);
                body.putInt(0);
                rpc.send(peer, new SimTransport.Frame(groupId, APPEND, body.array()));
                nextIndex.put(peer, snapIndex + 1);
                continue;
            }
            long prev = next - 1;
            int count = (int) Math.min(32, lastIndex() - prev);
            var entries = new ArrayList<Rec>(Math.max(0, count));
            for (long index = next; index <= prev + count; index++) {
                var data = read(index);
                if (data == null) {
                    break;
                }
                entries.add(new Rec(termAt(index), data));
            }
            int size = 8 + 4 + 8 + 8 + 8 + 4;
            for (var entry : entries) {
                size += 8 + 4 + entry.data.length;
            }
            var body = ByteBuffer.allocate(size).order(java.nio.ByteOrder.BIG_ENDIAN);
            body.putLong(term);
            body.putInt(self.wire());
            body.putLong(prev);
            body.putLong(termAt(prev));
            body.putLong(commitIndex);
            body.putInt(entries.size());
            for (var entry : entries) {
                body.putLong(entry.term);
                body.putInt(entry.data.length);
                body.put(entry.data);
            }
            rpc.send(peer, new SimTransport.Frame(groupId, APPEND, body.array()));
        }
    }

    private void maybeCommit(List<Runnable> after) {
        long last = lastIndex();
        for (long index = last; index > commitIndex; index--) {
            if (termAt(index) != term) {
                continue;
            }
            if (quorumFor(index)) {
                commitIndex = index;
                apply(after);
                if (commitIndex - snapIndex >= options.snapshotEveryEntries()) {
                    snapshotThrough(commitIndex);
                }
                return;
            }
        }
    }

    private void apply(List<Runnable> after) {
        while (lastApplied < commitIndex) {
            long index = lastApplied + 1;
            if (index <= snapIndex) {
                lastApplied = snapIndex;
                continue;
            }
            var data = read(index);
            if (data == null || data.length == 0) {
                break;
            }
            lastApplied = index;
            switch (data[0]) {
                case USER -> {
                    var payload = new byte[data.length - 1];
                    System.arraycopy(data, 1, payload, 0, payload.length);
                    long entryTerm = termAt(index);
                    deliveries.submit(() -> {
                        if (listener != null) {
                            listener.onCommitted(new CommittedEntry(groupId, entryTerm, index,
                                    ByteBuffer.wrap(payload)));
                        }
                    }, index);
                }
                case JOINT -> applyJoint(data, after);
                case NEW_CONFIG -> applyNew(data, after);
                case NOOP -> {
                    long entryTerm = termAt(index);
                    long applied = index;
                    var copy = List.copyOf(noopListeners);
                    deliveries.submit(() -> {
                        for (var listener : copy) {
                            listener.accept(entryTerm, applied);
                        }
                    }, applied);
                }
                default -> {
                    // Leave unknown markers applied so a bad record cannot stall the group.
                }
            }
        }
        var done = new ArrayList<Map.Entry<Long, CompletableFuture<CommittedIndex>>>();
        for (var entry : pending.entrySet()) {
            if (entry.getKey() <= commitIndex) {
                done.add(entry);
            }
        }
        for (var entry : done) {
            pending.remove(entry.getKey());
            long index = entry.getKey();
            long entryTerm = termAt(index);
            var future = entry.getValue();
            deliveries.submit(() -> future.complete(new CommittedIndex(groupId, entryTerm, index)), future, index);
        }
    }

    private void applyJoint(byte[] data, List<Runnable> after) {
        var body = ByteBuffer.wrap(data).order(java.nio.ByteOrder.BIG_ENDIAN);
        body.get();
        jointOld = readSet(body);
        jointNew = readSet(body);
        if (role == Role.LEADER && !configFollowUpSent) {
            configFollowUpSent = true;
            appendRaw(config(NEW_CONFIG, jointNew, null));
            maybeCommit(after);
            replicate();
        }
    }

    private void applyNew(byte[] data, List<Runnable> after) {
        var body = ByteBuffer.wrap(data).order(java.nio.ByteOrder.BIG_ENDIAN);
        body.get();
        voters.clear();
        voters.addAll(readSet(body));
        jointOld = null;
        jointNew = null;
        configFollowUpSent = false;
        if (membershipChange != null) {
            var future = membershipChange;
            membershipChange = null;
            after.add(() -> future.complete(null));
        }
        if (role == Role.LEADER && !voters.contains(self)) {
            stepDown(term, after);
        }
    }

    private boolean matches(long prevIndex, long prevTerm) {
        if (prevIndex == 0) {
            return true;
        }
        if (prevIndex == snapIndex) {
            return prevTerm == snapTerm;
        }
        return termAt(prevIndex) == prevTerm && prevIndex <= lastIndex();
    }

    /**
     * A witness stores no entry, so a quorum of leader-memory plus witness would leave the entry
     * nowhere durable: a leader crash would lose it and the witness cursor would then refuse every
     * data node forever. When no data peer has acked, the leader counts itself only through
     * {@link #durableIndex}.
     */
    private boolean quorumFor(long index) {
        var acks = new HashSet<NodeId>();
        int dataAcks = 0;
        boolean witnessAcked = false;
        for (var peer : peers) {
            if (matchIndex.getOrDefault(peer, 0L) >= index && isVoter(peer)) {
                acks.add(peer);
                if (!witnesses.contains(peer)) {
                    dataAcks++;
                } else {
                    witnessAcked = true;
                }
            }
        }
        if (countsAsStored(self) && (dataAcks > 0 || !witnessAcked || index <= durableIndex)) {
            acks.add(self);
            dataAcks++;
        }
        if (!isQuorum(acks)) {
            return false;
        }
        return options.durability() != Durability.STRICT || dataAcks >= dataCopiesRequired();
    }

    /** STRICT needs two data disks when the group has two data voters and a witness. */
    private int dataCopiesRequired() {
        int dataVoters = 0;
        boolean witnessVotes = false;
        for (var id : voters) {
            if (witnesses.contains(id)) {
                witnessVotes = true;
            } else {
                dataVoters++;
            }
        }
        if (!witnessVotes || dataVoters < 2) {
            return 1;
        }
        return 2;
    }

    private boolean countsAsStored(NodeId id) {
        return id.equals(self) && !witness && isVoter(id);
    }

    private boolean isVoter(NodeId id) {
        if (jointOld != null) {
            return jointOld.contains(id) || jointNew.contains(id);
        }
        return voters.contains(id);
    }

    private boolean isQuorum(Set<NodeId> acks) {
        if (jointOld != null) {
            return quorum.hasQuorum(jointOld, acks) && quorum.hasQuorum(jointNew, acks);
        }
        return quorum.hasQuorum(voters, acks);
    }

    /** False when a higher term could not be made durable: memory is unchanged and the caller sends nothing. */
    private boolean stepDown(long newTerm, List<Runnable> after) {
        if (newTerm > term && !saveHardState(newTerm, null)) {
            // Every role: a node stuck below the cluster's term should be visible, not only a leader.
            System.getLogger(RaftGroup.class.getName()).log(System.Logger.Level.WARNING,
                    "group " + groupId.value() + ": " + role.name().toLowerCase(java.util.Locale.ROOT) + " at term "
                            + term + " saw term " + newTerm + " but could not persist it; staying in term " + term
                            + " and sending nothing");
            return false;
        }
        boolean wasLeader = role == Role.LEADER;
        if (newTerm > term) {
            term = newTerm;
            votedFor = null;
        }
        role = Role.FOLLOWER;
        if (wasLeader) {
            long wait = options.failbackHysteresis().toNanos();
            if (wait > 0) {
                long until = clock.nanoTime() + wait;
                campaignNotBefore = until;
                if (electionDeadline < until) {
                    electionDeadline = until;
                }
            }
            leader = null;
            var error = notLeader();
            // Failed on the delivery thread too, after every callback already queued for earlier entries.
            for (var future : pending.values()) {
                deliveries.submit(() -> future.completeExceptionally(error), future);
            }
            pending.clear();
            if (membershipChange != null) {
                var change = membershipChange;
                membershipChange = null;
                after.add(() -> change.completeExceptionally(error));
            }
            fireLeader(after);
        }
        return true;
    }

    private void fireLeader(List<Runnable> after) {
        long termNow = term;
        Optional<NodeId> who = Optional.ofNullable(leader);
        var copy = List.copyOf(leaderListeners);
        // On the delivery thread, queued before this term's no-op: a listener sees leadership changes in
        // order with commit callbacks, so leaderReady() is still false inside onLeaderChange (#182 moved
        // the no-op callback to the delivery thread; running this on the caller let the no-op overtake it).
        deliveries.submit(() -> {
            for (var listener : copy) {
                listener.accept(termNow, who);
            }
        }, (java.util.concurrent.CompletableFuture<?>) null);
    }

    private long appendRaw(byte[] data) {
        long index = Math.max(lastIndex(), snapIndex) + 1;
        appendStored(index, term, data);
        return index;
    }

    private void appendStored(long index, long entryTerm, byte[] data) {
        try {
            storage.append(groupId, index, entryTerm, data);
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    private void force() {
        try {
            storage.force(groupId);
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
        if (!witness) {
            durableIndex = lastIndex();
        }
    }

    private boolean abstaining() {
        return recovering && !quorum.hasQuorum(voters, Set.of(self));
    }

    /**
     * Write the next term and vote before memory changes. False on failure: the caller leaves
     * term and votedFor as they were and sends nothing that carries the new term.
     */
    private boolean saveHardState(long nextTerm, NodeId nextVote) {
        try {
            storage.saveHardState(groupId, nextTerm, nextVote == null ? 0 : nextVote.wire());
            return true;
        } catch (IOException | RuntimeException failed) {
            return false;
        }
    }

    /** Force the staged witness cursor. A disk error refuses the vote and does not set votedFor. */
    private boolean witnessForced() {
        try {
            force();
            return true;
        } catch (UncheckedIOException failed) {
            return false;
        }
    }

    /** Stage and force before the ack is sent. A disk error sends no ack. */
    private boolean witnessRemember(long index, long term) {
        try {
            storage.noteWitness(groupId, index, term);
            force();
            return true;
        } catch (IOException | UncheckedIOException failed) {
            return false;
        }
    }

    private long witnessCursorIndex() {
        try {
            return storage.witnessIndex(groupId);
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    private long witnessCursorTerm() {
        try {
            return storage.witnessTerm(groupId);
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    private void truncateSuffix(long fromIndex) {
        durableIndex = Math.min(durableIndex, fromIndex - 1);
        try {
            storage.truncateSuffix(groupId, fromIndex);
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    private byte[] read(long index) {
        try {
            return storage.read(groupId, index);
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    private long termAt(long index) {
        if (index == 0) {
            return 0L;
        }
        if (index == snapIndex) {
            return snapTerm;
        }
        try {
            return storage.termAt(groupId, index);
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    private long lastIndex() {
        return Math.max(storage.lastIndex(groupId), snapIndex);
    }

    private void ack(NodeId to, boolean success, long match) {
        var body = ByteBuffer.allocate(8 + 4 + 1 + 8).order(java.nio.ByteOrder.BIG_ENDIAN);
        body.putLong(term);
        body.putInt(self.wire());
        body.put((byte) (success ? 1 : 0));
        body.putLong(match);
        rpc.send(to, new SimTransport.Frame(groupId, APPEND_RESP, body.array()));
    }

    private void send(int type, byte[] body) {
        var frame = new SimTransport.Frame(groupId, type, body);
        for (var peer : peers) {
            if (voters.contains(peer) || learners.contains(peer)) {
                rpc.send(peer, frame);
            }
        }
    }

    private NotLeaderException notLeader() {
        return new NotLeaderException(groupId, Optional.ofNullable(leader));
    }

    private void snapshotThrough(long index) {
        try {
            var out = new ByteArrayOutputStream();
            out.write(snapData);
            for (long i = snapIndex + 1; i <= index; i++) {
                var data = read(i);
                if (data != null && data.length > 0 && data[0] == USER) {
                    out.write(data, 1, data.length - 1);
                }
            }
            snapData = out.toByteArray();
            snapTerm = termAt(index);
            snapIndex = index;
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    private static byte[] user(byte[] payload) {
        var out = new byte[payload.length + 1];
        out[0] = USER;
        System.arraycopy(payload, 0, out, 1, payload.length);
        return out;
    }

    private static byte[] config(byte kind, Set<NodeId> first, Set<NodeId> second) {
        int size = 1 + 4 + first.size() * 4;
        if (second != null) {
            size += 4 + second.size() * 4;
        }
        var body = ByteBuffer.allocate(size).order(java.nio.ByteOrder.BIG_ENDIAN);
        body.put(kind);
        putSet(body, first);
        if (second != null) {
            putSet(body, second);
        }
        return body.array();
    }

    private static void putSet(ByteBuffer body, Set<NodeId> ids) {
        body.putInt(ids.size());
        for (var id : ids) {
            body.putInt(id.wire());
        }
    }

    private Set<NodeId> readSet(ByteBuffer body) {
        int count = body.getInt();
        var ids = new HashSet<NodeId>();
        for (int i = 0; i < count; i++) {
            var id = byWire.apply(body.getInt());
            if (id != null) {
                ids.add(id);
            }
        }
        return ids;
    }

    private void run(List<Runnable> tasks) {
        for (var task : tasks) {
            task.run();
        }
    }

    /** Tick and frame handling (the Raft thread): never runs or waits for a commit callback. */
    private void runOnRaftThread(List<Runnable> tasks) {
        run(tasks);
    }

    /** Deliveries queued or running (apply lag), for metrics and backpressure tests. */
    long deliveryLag() {
        return deliveries.lag();
    }

    long maxDeliveryLag() {
        return deliveries.maxLag();
    }

    long abandonedDeliveries() {
        return deliveries.abandoned();
    }

    long deliveryLagWarnings() {
        return deliveries.lagWarnings();
    }

    /** True on this group's delivery thread (inside a callback). */
    boolean onDeliveryThread() {
        return deliveries.onExecutorThread();
    }

    /** Waits until every delivery submitted so far has run; false on timeout or on the delivery thread. */
    boolean awaitDelivered(java.time.Duration timeout) {
        return deliveries.awaitIdle(timeout);
    }

    /** Drain for up to {@code budget}, then abandon (futures fail) and stop the delivery thread. */
    boolean closeDeliveries(java.time.Duration budget) {
        return deliveries.close(budget);
    }

    private record Rec(long term, byte[] data) {
    }

    final class GroupFacade implements ReplicationGroup, SnapshotControl {

        private GroupFacade() {
        }

        @Override
        public GroupId groupId() {
            return groupId;
        }

        @Override
        public long term() {
            lock.lock();
            try {
                return term;
            } finally {
                lock.unlock();
            }
        }

        @Override
        public Optional<NodeId> leader() {
            lock.lock();
            try {
                return Optional.ofNullable(leader);
            } finally {
                lock.unlock();
            }
        }

        @Override
        public boolean isLeader() {
            lock.lock();
            try {
                return role == Role.LEADER;
            } finally {
                lock.unlock();
            }
        }

        @Override
        public long leaseExpiresAt() {
            lock.lock();
            try {
                return role == Role.LEADER ? leaseExpires : 0L;
            } finally {
                lock.unlock();
            }
        }

        @Override
        public long commitIndex() {
            lock.lock();
            try {
                return commitIndex;
            } finally {
                lock.unlock();
            }
        }

        @Override
        public long lastApplied() {
            lock.lock();
            try {
                return lastApplied;
            } finally {
                lock.unlock();
            }
        }

        @Override
        public CompletableFuture<Void> readBarrier() {
            var future = new CompletableFuture<Void>();
            lock.lock();
            try {
                if (role != Role.LEADER) {
                    future.completeExceptionally(notLeader());
                    return future;
                }
                if (clock.nanoTime() < leaseExpires) {
                    future.complete(null);
                    return future;
                }
                reads.add(future);
                replicate();
            } finally {
                lock.unlock();
            }
            return future;
        }

        @Override
        public CompletableFuture<Void> changeMembership(Set<NodeId> nextVoters, Set<NodeId> nextLearners) {
            Objects.requireNonNull(nextVoters, "voters");
            Objects.requireNonNull(nextLearners, "learners");
            var future = new CompletableFuture<Void>();
            var after = new ArrayList<Runnable>();
            lock.lock();
            try {
                if (role != Role.LEADER) {
                    future.completeExceptionally(notLeader());
                    return future;
                }
                learners.clear();
                learners.addAll(nextLearners);
                membershipChange = future;
                configFollowUpSent = false;
                jointOld = Set.copyOf(voters);
                jointNew = Set.copyOf(nextVoters);
                appendRaw(config(JOINT, jointOld, jointNew));
                maybeCommit(after);
                replicate();
            } finally {
                lock.unlock();
            }
            run(after);
            return future;
        }

        @Override
        public void transferLeadership(NodeId target) {
            Objects.requireNonNull(target, "target");
            var after = new ArrayList<Runnable>();
            lock.lock();
            try {
                if (role != Role.LEADER || target.equals(self)) {
                    return;
                }
                var body = ByteBuffer.allocate(8 + 4).order(java.nio.ByteOrder.BIG_ENDIAN);
                body.putLong(term);
                body.putInt(self.wire());
                rpc.send(target, new SimTransport.Frame(groupId, TIMEOUT_NOW, body.array()));
                role = Role.FOLLOWER;
                leader = null;
                long now = clock.nanoTime();
                long wait = options.leaseNanos() + options.electionTimeout().toNanos();
                long failback = options.failbackHysteresis().toNanos();
                if (failback > wait) {
                    wait = failback;
                }
                campaignNotBefore = now + failback;
                electionDeadline = now + wait;
                fireLeader(after);
            } finally {
                lock.unlock();
            }
            run(after);
        }

        @Override
        public LogSnapshot take(long lastIncludedIndex) {
            lock.lock();
            try {
                if (lastIncludedIndex > commitIndex || lastIncludedIndex < snapIndex) {
                    throw new IllegalArgumentException("snapshot index");
                }
                snapshotThrough(lastIncludedIndex);
                return new LogSnapshot(groupId, snapIndex, snapTerm, snapData);
            } finally {
                lock.unlock();
            }
        }

        @Override
        public void install(LogSnapshot snapshot) {
            Objects.requireNonNull(snapshot, "snapshot");
            if (!snapshot.groupId().equals(groupId)) {
                throw new IllegalArgumentException("group");
            }
            lock.lock();
            try {
                snapIndex = snapshot.lastIncludedIndex();
                snapTerm = snapshot.lastIncludedTerm();
                snapData = snapshot.data();
                try {
                    storage.truncatePrefix(groupId, snapIndex);
                } catch (IOException failed) {
                    throw new UncheckedIOException(failed);
                }
                if (commitIndex < snapIndex) {
                    commitIndex = snapIndex;
                }
                if (lastApplied < snapIndex) {
                    lastApplied = snapIndex;
                }
            } finally {
                lock.unlock();
            }
        }

        @Override
        public CompletableFuture<Void> truncateAndCompact(long ackedThrough, Compactor compactor) {
            Objects.requireNonNull(compactor, "compactor");
            var future = new CompletableFuture<Void>();
            lock.lock();
            try {
                if (snapIndex < ackedThrough) {
                    future.completeExceptionally(new IllegalArgumentException("not snapshotted"));
                    return future;
                }
                try {
                    storage.truncatePrefix(groupId, ackedThrough);
                } catch (IOException failed) {
                    future.completeExceptionally(failed);
                    return future;
                }
                var current = new LogSnapshot(groupId, snapIndex, snapTerm, snapData);
                var compacted = compactor.compact(current);
                if (compacted != null) {
                    snapData = compacted;
                }
                future.complete(null);
            } finally {
                lock.unlock();
            }
            return future;
        }
    }
}
