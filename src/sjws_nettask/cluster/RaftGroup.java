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
    /** User data appended with a STRICT override: every data copy fsyncs it before it counts. */
    static final byte USER_STRICT = 4;

    static final int VOTE = 1;
    static final int VOTE_RESP = 2;
    static final int APPEND = 3;
    static final int APPEND_RESP = 4;
    static final int TIMEOUT_NOW = 5;
    static final int PRE_VOTE = 6;
    static final int PRE_VOTE_RESP = 7;
    /** Leader to a data follower behind the leader's compacted prefix: one chunk of the snapshot image. */
    static final int SNAP_CHUNK = 8;
    /** Follower to leader: the next offset it wants, or that the image is installed. */
    static final int SNAP_RESP = 9;
    /** Default {@link #snapshotChunkBytes}: 1 MiB, well under the 16 MiB transport frame cap. */
    static final int DEFAULT_SNAPSHOT_CHUNK_BYTES = 1 << 20;

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
    /** Leader view: when each peer last answered an append in this term (clock nanos). */
    private final Map<NodeId, Long> heardAt = new HashMap<>();
    private final Map<NodeId, Long> nextIndex = new HashMap<>();
    private final TreeMap<Long, CompletableFuture<CommittedIndex>> pending = new TreeMap<>();
    private final List<CompletableFuture<Void>> reads = new ArrayList<>();
    private CompletableFuture<Void> membershipChange;
    private long snapIndex;
    private long snapTerm;
    private byte[] snapData = new byte[0];
    /** Snapshot images refused or skipped: a taker that threw, returned null or exceeded maxSnapshotBytes. */
    private long skippedSnapshots;
    private SnapshotTaker taker;
    private boolean takerInFlight;
    /** Set with {@link SnapshotControl#setTruncationBound}; volatile because it is read without the lock. */
    private volatile TruncationBound truncationBound;
    /** Highest index this node has dropped from its log prefix (snapshot install or truncation). */
    private long truncatedThrough;
    private boolean relaxedDirty;
    /** Clock time when the oldest entry not yet fsynced was stored. */
    private long dirtySince;
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
    /** Uncommitted indexes (leader view) that carry {@link #USER_STRICT}. */
    private final java.util.TreeMap<Long, Long> strictPending = new java.util.TreeMap<>();
    /** Highest pending STRICT index already reported as stalled, so each entry warns once. */
    private long strictWarnedIndex;
    private long recoveryUntil;
    /** Bytes of image per {@link #SNAP_CHUNK}; package-private so tests can force many chunks. */
    int snapshotChunkBytes = DEFAULT_SNAPSHOT_CHUNK_BYTES;
    /** Leader view: the snapshot transfer in progress to each data follower behind the compacted prefix. */
    private final Map<NodeId, SnapSend> snapSends = new HashMap<>();
    /** Follower view: the image being received, or null. */
    private SnapReceive snapReceive;
    /** Images fully sent and acknowledged (leader) and installed from a leader (follower). */
    private long snapshotsSent;
    private long snapshotsInstalled;
    private boolean persistWarned;
    private long persistWarnAt;
    private long persistWarnSuppressed;

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
        if (options.durability() == Durability.RELAXED && options.relaxedSyncDelay().toNanos() > options.leaseNanos()) {
            System.getLogger(RaftGroup.class.getName()).log(System.Logger.Level.WARNING,
                    "group " + groupId.value() + ": relaxedSyncDelay " + options.relaxedSyncDelay().toMillis()
                            + " ms exceeds the lease (" + java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                                    options.leaseNanos()) + " ms): with one data node down a commit waits that"
                            + " long, and the RELAXED loss window is that wide");
        }
        LogSnapshot restored = null;
        if (!witness && storage.persistsSnapshots()) {
            try {
                restored = storage.loadSnapshot(groupId, options.maxSnapshotBytes());
            } catch (IOException failed) {
                throw new UncheckedIOException("cannot load the durable snapshot of " + groupId, failed);
            }
        }
        if (restored != null) {
            // The log prefix through this index may be gone from disk: the image replaces it.
            this.snapIndex = restored.lastIncludedIndex();
            this.snapTerm = restored.lastIncludedTerm();
            this.snapData = restored.data();
            this.commitIndex = snapIndex;
            this.lastApplied = snapIndex;
            var image = restored;
            deliveries.submit(() -> {
                var current = this.listener;
                if (current != null) {
                    current.onSnapshotRestored(image);
                }
            }, snapIndex);
        }
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
                if (restored != null && restored.lastIncludedTerm() > term) {
                    this.term = restored.lastIncludedTerm();
                }
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
            if (storageFailed != null || closed) {
                return; // failed closed or node closed: no fsync, heartbeat or campaign (until restart)
            }
            if (relaxedDirty && now - dirtySince >= options.relaxedSyncDelay().toNanos()) {
                force();
                relaxedDirty = false;
                if (role == Role.LEADER) {
                    maybeCommit(after);
                }
            }
            if (recovering && now >= recoveryUntil) {
                recovering = false;
            }
            if (role == Role.LEADER && !strictPending.isEmpty()) {
                warnStalledStrict(now);
            }
            if (role == Role.LEADER) {
                if (now >= nextHeartbeat) {
                    nextHeartbeat = now + options.heartbeat().toNanos();
                    replicate();
                }
            } else if (!witness && storageFailed == null && now >= electionDeadline && now >= campaignNotBefore
                    && !abstaining()) {
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
            if (storageFailed != null || closed) {
                return; // failed closed or node closed: no acks, votes or terms (until restart)
            }
            var body = ByteBuffer.wrap(frame.body()).order(java.nio.ByteOrder.BIG_ENDIAN);
            switch (frame.type()) {
                case VOTE -> onVote(body, now, after);
                case VOTE_RESP -> onVoteResp(body, now, after);
                case PRE_VOTE -> onPreVote(body, now, after);
                case PRE_VOTE_RESP -> onPreVoteResp(body, now, after);
                case APPEND -> onAppend(from, body, now, after);
                case APPEND_RESP -> onAppendResp(from, body, now, after);
                case TIMEOUT_NOW -> onTimeoutNow(body, now, after);
                case SNAP_CHUNK -> onSnapChunk(from, body, now, after);
                case SNAP_RESP -> onSnapResp(from, body, now, after);
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
        return append(batch, null);
    }

    /** {@link #append(List, Durability, long)} with no term condition. */
    static final long ANY_TERM = -1L;

    /** {@code durability} null uses {@link GroupOptions#durability()}. */
    CompletableFuture<CommittedIndex> append(List<ByteBuffer> batch, Durability durability) {
        return append(batch, durability, ANY_TERM);
    }

    /**
     * {@code expectedTerm} {@link #ANY_TERM} appends in whatever term this leader is in; otherwise the
     * append is refused with {@link TermChangedException} unless {@code term == expectedTerm}, checked
     * under the lock together with the role, so no step-down or re-election can slip in between.
     */
    CompletableFuture<CommittedIndex> append(List<ByteBuffer> batch, Durability durability, long expectedTerm) {
        boolean marked = durability == Durability.STRICT && options.durability() != Durability.STRICT;
        boolean strict = marked || options.durability() == Durability.STRICT;
        var future = new CompletableFuture<CommittedIndex>();
        // Every refusal comes back as a failed future, never a synchronous throw, so callers that compose on the
        // future (RaftReplica, GenerationSync) see argument errors and storage errors the same way.
        if (batch == null || batch.isEmpty()) {
            future.completeExceptionally(new IllegalArgumentException("batch"));
            return future;
        }
        var copies = new ArrayList<byte[]>(batch.size());
        int total = 0;
        for (var buf : batch) {
            if (buf == null) {
                future.completeExceptionally(new IllegalArgumentException("entry"));
                return future;
            }
            var copy = new byte[buf.remaining()];
            buf.duplicate().get(copy);
            copies.add(copy);
            total += copy.length;
        }
        var after = new ArrayList<Runnable>();
        lock.lock();
        try {
            if (storageFailed != null) {
                future.completeExceptionally(fencedError());
                return future;
            }
            if (closed) {
                future.completeExceptionally(new GroupClosedException(groupId));
                return future;
            }
            if (role != Role.LEADER) {
                future.completeExceptionally(notLeader());
                return future;
            }
            if (expectedTerm != ANY_TERM && term != expectedTerm) {
                future.completeExceptionally(new TermChangedException(groupId, expectedTerm, term));
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
            if (marked) {
                int required = dataCopiesRequired();
                int reachable = reachableDataCopies(clock.nanoTime());
                if (reachable < required) {
                    // Refused before it enters the log: a STRICT entry that cannot commit would
                    // hold every later RELAXED entry behind it (commits are a prefix).
                    future.completeExceptionally(new DurabilityUnavailableException(groupId, reachable, required));
                    return future;
                }
            }
            long first = Math.max(lastIndex(), snapIndex) + 1;
            long last = first - 1;
            // A failed batch still falls through to run(after) below: failing closed queues the step-down's
            // leader-change callback and the pending membership change there (#246; returning early dropped them).
            boolean rolledBack = false;
            try {
                for (var copy : copies) {
                    last = appendRaw(user(copy, marked ? USER_STRICT : USER));
                }
                if (strict) {
                    force();
                } else {
                    markDirty();
                }
            } catch (RuntimeException failed) {
                // The batch is all-or-nothing. Nothing of it has been replicated or counted yet (the lock is held
                // throughout), so it is still safe to take back.
                future.completeExceptionally(rollBackBatch(first, failed, after));
                rolledBack = true;
            }
            if (!rolledBack) {
                try {
                    if (marked) {
                        long now = clock.nanoTime();
                        for (long index = first; index <= last; index++) {
                            strictPending.put(index, now);
                        }
                    }
                    pending.put(last, future);
                    maybeCommit(after);
                    replicate();
                } catch (RuntimeException accepted) {
                    // The batch is in the log and may already be on its way to a follower: its fate is unknown. Fail
                    // the future as such; never throw to the caller, which would read as "not appended" (#254).
                    pending.remove(last, future);
                    future.completeExceptionally(new OutcomeUnknownException(groupId, "a storage error after the batch"
                            + " at " + first + ".." + last + " was appended", Optional.empty(), accepted));
                }
            }
        } finally {
            lock.unlock();
        }
        run(after);
        return future;
    }

    /**
     * Set when a failed batch could not be rolled back. The group then fails closed until the process restarts:
     * it has stepped down, refuses appends, ignores every frame and does not campaign, because its log may hold
     * part of a batch whose future already failed.
     *
     * <p>Guarded by {@link #lock}: written once (null to non-null, never back) in {@link #rollBackBatch} under the
     * lock, and every read (append, tick, handle, the snapshot and truncation calls, {@link #failedClosed()},
     * {@link #fencedError()}) happens under the lock too, so it needs no {@code volatile}. Keep it that way: a
     * read outside the lock could miss the write and let a fenced group touch storage.
     */
    private UncheckedIOException storageFailed;

    /** Under the lock: throws {@link #fencedError()} once the group has failed closed. */
    private void refuseIfFailedClosed() {
        if (storageFailed != null) {
            throw fencedError();
        }
    }

    /** Health/metrics: true once a failed batch could not be rolled back; cleared only by a restart. */
    boolean failedClosed() {
        lock.lock();
        try {
            return storageFailed != null;
        } finally {
            lock.unlock();
        }
    }

    /** Per-batch storage failures: a full trace once per 30 s per exception type, one line otherwise (#254). */
    private static final ThrottledLog WRITE_FAILURES = new ThrottledLog(System.getLogger(RaftGroup.class.getName()),
            java.time.Duration.ofSeconds(30));

    /**
     * Under the lock, when failing closed: the error for writes that were already in the log and still pending.
     * They are not refused like a {@link NotLeaderException} would suggest: they may already be on a follower (or
     * on this node's disk) and commit under a later leader, so their outcome is unknown. Same type and the same
     * storage cause as the failed batch's own error (#254), so callers handle both as a timeout and deduplicate on
     * retry; the fail-closed error rides along as suppressed.
     */
    private OutcomeUnknownException outcomeUnknown(String what) {
        var error = new OutcomeUnknownException(groupId, "failed closed after a log write it could not roll back; "
                + what + " was already in the log", Optional.empty(), storageFailed.getCause());
        error.addSuppressed(storageFailed);
        return error;
    }

    private UncheckedIOException fencedError() {
        return new GroupFencedException(groupId, storageFailed.getCause());
    }

    /**
     * Undo a leader batch from {@code first} on after a storage failure, so it is all-or-nothing. The rollback is
     * a {@link LogStorage#truncateSuffix}, which on {@link FileLogStorage} rewrites the kept records from memory
     * through a temp file, fsync, rename and directory fsync: it also drops any torn bytes the failed write left
     * at the end of the segment, and re-syncs earlier entries whose fsync may have been lost with the failure.
     * If the rollback fails as well, the batch's fate on disk is unknown, so the group fails closed (see
     * {@link #storageFailed}). Returns the error for the batch's future. In that case part of the batch may
     * still be on disk, and after a restart a leader may commit it like any uncommitted entry, so the batch fails
     * with {@link OutcomeUnknownException} (the storage error is its cause): a caller must treat it like a timeout
     * (retry with its own dedup key). A batch rolled back cleanly fails with the storage error itself: nothing of it
     * is in the log, a refusal.
     */
    /**
     * Set once by {@link #closeGroup()}, under {@link #lock}: the node closed. A closed group ignores ticks and frames,
     * never campaigns (so it can never be elected again) and refuses new appends with {@link GroupClosedException}.
     */
    private boolean closed;

    private Throwable rollBackBatch(long first, RuntimeException failed, List<Runnable> after) {
        var log = System.getLogger(RaftGroup.class.getName());
        try {
            truncateSuffix(first);
            if (lastIndex() >= first) {
                throw new IllegalStateException("log still ends at " + lastIndex() + " after truncating from " + first);
            }
            WRITE_FAILURES.log(System.Logger.Level.WARNING, "group " + groupId.value() + ": log write failed at index "
                    + first + " or later; rolled the batch back, nothing of it stays in the log", failed);
            return new BatchRolledBackException(groupId, first, failed); // a refusal: safe to retry (#254)
        } catch (RuntimeException rollback) {
            failed.addSuppressed(rollback);
            var cause = failed instanceof UncheckedIOException io ? io.getCause()
                    : new java.io.IOException("log write failed", failed);
            storageFailed = new UncheckedIOException("group " + groupId.value() + ": log write failed and the batch"
                    + " from index " + first + " could not be rolled back; outcome unknown, failing closed", cause);
            storageFailed.addSuppressed(rollback);
            log.log(System.Logger.Level.ERROR, storageFailed.getMessage(), storageFailed);
            // Writes appended before this batch may still commit (they can already be on a follower), so they fail
            // as outcome unknown, not as not-leader. Done before stepDown, which would otherwise fail them as
            // NotLeaderException; it then finds nothing pending. Same delivery-thread ordering as stepDown.
            for (var entry : pending.entrySet()) {
                var future = entry.getValue();
                var error = outcomeUnknown("the append at index " + entry.getKey());
                deliveries.fail(future, error);
            }
            pending.clear();
            if (membershipChange != null) {
                var change = membershipChange;
                membershipChange = null;
                var error = outcomeUnknown("a membership change");
                after.add(() -> change.completeExceptionally(error));
            }
            // A read barrier has no outcome to lose: it fails with the fenced error, like one issued from now on.
            for (var read : reads) {
                var error = fencedError();
                after.add(() -> read.completeExceptionally(error));
            }
            reads.clear();
            stepDown(term, after); // same term: no hard-state write
            var batch = new OutcomeUnknownException(groupId, "log write failed and the batch from index " + first
                    + " could not be rolled back; the group failed closed", Optional.empty(), cause);
            batch.addSuppressed(storageFailed);
            return batch;
        }
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
        if (closed) {
            return; // a closed node never campaigns, so it is never elected again (#254)
        }
        if (role == Role.LEADER) {
            // No caller campaigns as leader since #255 (tick and pre-vote only run off-leader, onTimeoutNow ignores
            // a leader). Kept so a future caller still leaves leadership through the shared same-term demotion
            // first: pending writes fail, leader is cleared and listeners hear it, even if the write below fails.
            stepDown(term, after);
        }
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
        heardAt.clear();
        strictPending.clear();
        strictWarnedIndex = 0;
        for (long index = commitIndex + 1; index <= lastIndex(); index++) {
            var data = read(index);
            if (data != null && data.length > 0 && data[0] == USER_STRICT) {
                strictPending.put(index, now); // age counts from this leader's term
            }
        }
        appendRaw(new byte[] {NOOP});
        if (options.durability() != Durability.STRICT) {
            // RELAXED: schedule the no-op's fsync like any append. Otherwise a leader elected with only the witness
            // (2+1, other data node down) never counts itself and the no-op stays uncommitted until a user append.
            markDirty();
        }
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
            if (role == Role.LEADER) {
                failPendingWrites(after); // two leaders in one term cannot happen; keep pending empty regardless
            }
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
        boolean strictEntry = false;
        for (var entry : entries) {
            index++;
            strictEntry |= entry.data.length > 0 && entry.data[0] == USER_STRICT;
            long existing = termAt(index);
            if (existing != entry.term) {
                if (existing != 0) {
                    truncateSuffix(index);
                }
                appendStored(index, entry.term, entry.data);
            }
        }
        if (options.durability() == Durability.STRICT || strictEntry) {
            force();
            forcedBeforeAck = true;
        } else {
            markDirty();
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

    private void onAppendResp(NodeId from, ByteBuffer body, long now, List<Runnable> after) {
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
        if (from != null && !from.equals(follower)) {
            // The body names a different voter than the authenticated sender: one member must
            // not refresh another's liveness or matchIndex (#175).
            return;
        }
        heardAt.put(follower, now);
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

    /**
     * A leadership transfer handed to us: campaign now. Acted on only when it can still belong to a live transfer
     * (#255): sent by another voter, not older than our term, and, at our own term, only while we follow and the
     * sender is the leader we know (or we know none yet). Only the leader of a term sends TIMEOUT_NOW in it, so at
     * our term a leader or a candidate can only be looking at a stale or forged one. A higher term is adopted
     * first, as from any frame; the sender led that term and chose us. Anything else is dropped without a reply,
     * so a TIMEOUT_NOW left over from an old transfer can no longer make a sitting leader start an election.
     */
    private void onTimeoutNow(ByteBuffer body, long now, List<Runnable> after) {
        long theirTerm = body.getLong();
        var sender = byWire.apply(body.getInt());
        if (witness || theirTerm < term || sender == null || sender.equals(self) || !isVoter(sender)) {
            return;
        }
        if (theirTerm > term) {
            if (!stepDown(theirTerm, after)) {
                return;
            }
        } else if (role == Role.LEADER || role == Role.CANDIDATE || (leader != null && !leader.equals(sender))) {
            return;
        }
        electionDeadline = now;
        startElection(now, after);
    }

    /**
     * Sends the chunk at the peer's current offset. A transfer pins the image it started with, so a
     * newer local snapshot does not restart it; when it completes, {@code nextIndex} lands past that
     * image and, if the log was compacted further meanwhile, the next round starts a fresh transfer.
     */
    private void sendSnapshotChunk(NodeId peer) {
        var send = snapSends.get(peer);
        if (send == null || send.term != term) {
            send = new SnapSend(term, snapIndex, snapTerm, snapData, LogSnapshot.crc32c(snapIndex, snapTerm, snapData));
            snapSends.put(peer, send);
        }
        int len = Math.min(snapshotChunkBytes, send.data.length - send.offset);
        var body = ByteBuffer.allocate(8 + 4 + 8 + 8 + 4 + 4 + 8 + 4 + 4 + len).order(java.nio.ByteOrder.BIG_ENDIAN);
        body.putLong(term);
        body.putInt(self.wire());
        body.putLong(send.index);
        body.putLong(send.imageTerm);
        body.putInt(send.data.length);
        body.putInt(send.crc32c);
        body.putLong(commitIndex);
        body.putInt(send.offset);
        body.putInt(len);
        body.put(send.data, send.offset, len);
        rpc.send(peer, new SimTransport.Frame(groupId, SNAP_CHUNK, body.array()));
    }

    private void snapResp(NodeId to, boolean done, long match, int nextOffset) {
        var body = ByteBuffer.allocate(8 + 4 + 1 + 8 + 4).order(java.nio.ByteOrder.BIG_ENDIAN);
        body.putLong(term);
        body.putInt(self.wire());
        body.put((byte) (done ? 1 : 0));
        body.putLong(match);
        body.putInt(nextOffset);
        rpc.send(to, new SimTransport.Frame(groupId, SNAP_RESP, body.array()));
    }

    /**
     * Follower side. Leadership checks are those of {@link #onAppend}. Chunks must arrive in order;
     * anything else is answered with the offset wanted. The assembled image is checked against its
     * size cap and CRC32C, then installed exactly as {@link SnapshotControl#install} would: persisted
     * before any prefix delete, the log kept past the image only if it agrees with the image's term.
     */
    private void onSnapChunk(NodeId from, ByteBuffer body, long now, List<Runnable> after) {
        long theirTerm = body.getLong();
        int leaderWire = body.getInt();
        long index = body.getLong();
        long imageTerm = body.getLong();
        int total = body.getInt();
        int crc32c = body.getInt();
        long leaderCommit = body.getLong();
        int offset = body.getInt();
        int len = body.getInt();
        if (len < 0 || len > body.remaining() || offset < 0 || total < 0 || (long) offset + len > total) {
            return;
        }
        if (theirTerm < term) {
            snapResp(from, false, lastIndex(), 0);
            return;
        }
        if (theirTerm > term) {
            if (!stepDown(theirTerm, after)) {
                return;
            }
        }
        if (role == Role.CANDIDATE || role == Role.PRE_CANDIDATE || role == Role.LEADER) {
            if (role == Role.LEADER) {
                failPendingWrites(after); // two leaders in one term cannot happen; keep pending empty regardless
            }
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
            return;
        }
        if (index <= snapIndex || (index <= commitIndex && termAt(index) == imageTerm)) {
            // Already covered: committed entries agree with the leader's (log matching).
            snapReceive = null;
            snapResp(from, true, index, total);
            return;
        }
        if (total > options.maxSnapshotBytes()) {
            System.getLogger(RaftGroup.class.getName()).log(System.Logger.Level.WARNING, "group "
                    + groupId.value() + ": refusing a snapshot of " + total + " bytes from the leader (maxSnapshotBytes "
                    + options.maxSnapshotBytes() + ")");
            snapReceive = null;
            return;
        }
        var receive = snapReceive;
        if (receive == null || receive.term != theirTerm || receive.index != index || receive.crc32c != crc32c
                || receive.data.length != total) {
            if (offset != 0) {
                snapReceive = null;
                snapResp(from, false, lastIndex(), 0);
                return;
            }
            receive = new SnapReceive(theirTerm, index, imageTerm, crc32c, new byte[total]);
            snapReceive = receive;
        }
        if (offset != receive.received) {
            snapResp(from, false, lastIndex(), receive.received);
            return;
        }
        body.get(receive.data, offset, len);
        receive.received += len;
        if (receive.received < total) {
            snapResp(from, false, lastIndex(), receive.received);
            return;
        }
        snapReceive = null;
        if (LogSnapshot.crc32c(index, imageTerm, receive.data) != crc32c) {
            System.getLogger(RaftGroup.class.getName()).log(System.Logger.Level.WARNING, "group "
                    + groupId.value() + ": snapshot from the leader failed its CRC32C; asking again");
            snapResp(from, false, lastIndex(), 0);
            return;
        }
        if (!installFromLeader(index, imageTerm, receive.data, after)) {
            return;
        }
        if (recovering && commitIndex >= leaderCommit) {
            // As in onAppend: the restart gate opens only once this node has the leader's commit point.
            recovering = false;
        }
        snapshotsInstalled++;
        snapResp(from, true, index, total);
    }

    private boolean installFromLeader(long index, long imageTerm, byte[] image, List<Runnable> after) {
        boolean keepSuffix = termAt(index) == imageTerm && storage.lastIndex(groupId) >= index;
        long[] previous = {snapIndex, snapTerm};
        var previousData = snapData;
        snapIndex = index;
        snapTerm = imageTerm;
        snapData = image;
        try {
            persistSnapshotBeforePrefixDelete();
            if (keepSuffix) {
                storage.truncatePrefix(groupId, index);
            } else {
                // The local log disagrees with (or ends before) the image: none of it can be kept.
                storage.truncatePrefix(groupId, Long.MAX_VALUE - 1);
            }
            truncatedThrough = Math.max(truncatedThrough, index);
        } catch (IOException | RuntimeException failed) {
            snapIndex = previous[0];
            snapTerm = previous[1];
            snapData = previousData;
            System.getLogger(RaftGroup.class.getName()).log(System.Logger.Level.WARNING, "group "
                    + groupId.value() + ": cannot install the leader's snapshot at " + index + ": " + failed);
            return false;
        }
        if (commitIndex < index) {
            commitIndex = index;
        }
        if (lastApplied < index) {
            lastApplied = index;
            var installed = new LogSnapshot(groupId, index, imageTerm, image);
            deliveries.submit(() -> {
                var current = this.listener;
                if (current != null) {
                    current.onSnapshotRestored(installed);
                }
            }, index);
        }
        if (durableIndex < index) {
            durableIndex = index;
        }
        return true;
    }

    private void onSnapResp(NodeId from, ByteBuffer body, long now, List<Runnable> after) {
        long theirTerm = body.getLong();
        int followerWire = body.getInt();
        boolean done = body.get() == 1;
        long match = body.getLong();
        int nextOffset = body.getInt();
        if (theirTerm > term) {
            stepDown(theirTerm, after);
            return;
        }
        if (role != Role.LEADER || theirTerm != term) {
            return;
        }
        var follower = byWire.apply(followerWire);
        if (follower == null || (from != null && !from.equals(follower))) {
            return;
        }
        heardAt.put(follower, now);
        var send = snapSends.get(follower);
        if (done) {
            if (send != null) {
                snapSends.remove(follower);
                snapshotsSent++;
            }
            matchIndex.put(follower, Math.max(matchIndex.getOrDefault(follower, 0L), match));
            nextIndex.put(follower, Math.max(nextIndex.getOrDefault(follower, 0L), match + 1));
            maybeCommit(after);
            return;
        }
        if (send == null || nextOffset < 0 || nextOffset > send.data.length) {
            return;
        }
        boolean progressed = nextOffset > send.offset;
        send.offset = nextOffset;
        if (progressed && nextOffset < send.data.length) {
            sendSnapshotChunk(follower);
        }
    }

    /** Tests: whether the restarted-voter gate (see {@link #recovering}) is still closed. */
    boolean recovering() {
        lock.lock();
        try {
            return recovering;
        } finally {
            lock.unlock();
        }
    }

    long snapshotsSent() {
        lock.lock();
        try {
            return snapshotsSent;
        } finally {
            lock.unlock();
        }
    }

    long snapshotsInstalled() {
        lock.lock();
        try {
            return snapshotsInstalled;
        } finally {
            lock.unlock();
        }
    }

    private static final class SnapSend {
        final long term;
        final long index;
        final long imageTerm;
        final byte[] data;
        final int crc32c;
        int offset;

        SnapSend(long term, long index, long imageTerm, byte[] data, int crc32c) {
            this.term = term;
            this.index = index;
            this.imageTerm = imageTerm;
            this.data = data;
            this.crc32c = crc32c;
        }
    }

    private static final class SnapReceive {
        final long term;
        final long index;
        final long imageTerm;
        final int crc32c;
        final byte[] data;
        int received;

        SnapReceive(long term, long index, long imageTerm, int crc32c, byte[] data) {
            this.term = term;
            this.index = index;
            this.imageTerm = imageTerm;
            this.crc32c = crc32c;
            this.data = data;
        }
    }

    private void replicate() {
        for (var peer : peers) {
            long next = nextIndex.getOrDefault(peer, lastIndex() + 1);
            if (next <= snapIndex && !witnesses.contains(peer)) {
                sendSnapshotChunk(peer);
                continue;
            }
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

    /**
     * Commits the highest index above {@code commitIndex} that is in the current term and has a
     * quorum. {@link #quorumFor} only changes value at a peer's matchIndex, at {@code durableIndex},
     * and just below the first pending STRICT entry, so the highest qualifying index is always one
     * of those points (or {@code lastIndex()}). Checking only them keeps this O(voters) per call;
     * scanning every uncommitted index made it O(backlog) under the group lock, on every append and
     * every ack (#194).
     */
    private void maybeCommit(List<Runnable> after) {
        long last = lastIndex();
        if (last <= commitIndex) {
            return;
        }
        var candidates = new java.util.TreeSet<Long>(java.util.Comparator.reverseOrder());
        candidates.add(last);
        for (var peer : peers) {
            candidates.add(matchIndex.getOrDefault(peer, 0L));
        }
        candidates.add(durableIndex);
        var firstStrict = strictPending.ceilingKey(commitIndex + 1);
        if (firstStrict != null) {
            candidates.add(firstStrict - 1);
        }
        for (long index : candidates) {
            if (index <= commitIndex) {
                break;
            }
            if (index > last || termAt(index) != term) {
                continue;
            }
            if (quorumFor(index)) {
                commitIndex = index;
                strictPending.headMap(index, true).clear();
                apply(after);
                if (options.autoSnapshot() && commitIndex - snapIndex >= options.snapshotEveryEntries()) {
                    if (taker == null) {
                        snapshotThrough(commitIndex);
                    } else if (!takerInFlight) {
                        takerInFlight = true;
                        submitTaker(taker, commitIndex, null);
                    }
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
                case USER, USER_STRICT -> {
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
        return !strictUpTo(index) || dataAcks >= dataCopiesRequired();
    }

    /** Committing {@code index} commits a STRICT entry: a STRICT group, or a STRICT override in range. */
    private boolean strictUpTo(long index) {
        if (options.durability() == Durability.STRICT) {
            return true;
        }
        var first = strictPending.ceilingKey(commitIndex + 1);
        return first != null && first <= index;
    }

    /** STRICT needs two data disks when the group has two data voters and a witness. */
    /**
     * A STRICT entry pending longer than 2 x lease is stalled (usually its second data copy died
     * after it was accepted). Warn once per entry: committing resumes only when that data node is
     * back, so an operator has to act.
     */
    private void warnStalledStrict(long now) {
        var oldest = strictPending.firstEntry();
        if (oldest.getKey() <= strictWarnedIndex || now - oldest.getValue() < 2 * options.leaseNanos()) {
            return;
        }
        strictWarnedIndex = oldest.getKey();
        int copies = countsAsStored(self) ? 1 : 0;
        for (var peer : peers) {
            if (matchIndex.getOrDefault(peer, 0L) >= oldest.getKey() && isVoter(peer) && !witnesses.contains(peer)) {
                copies++;
            }
        }
        System.getLogger(RaftGroup.class.getName()).log(System.Logger.Level.WARNING,
                "group " + groupId.value() + ": STRICT entry " + oldest.getKey() + " pending for "
                        + java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(now - oldest.getValue()) + " ms with "
                        + copies + " of " + dataCopiesRequired() + " data copies; " + strictPending.size()
                        + " STRICT and every later entry wait until a data node is back");
    }

    /** Leader view: uncommitted STRICT override entries. */
    int strictPendingCount() {
        lock.lock();
        try {
            return role == Role.LEADER ? strictPending.size() : 0;
        } finally {
            lock.unlock();
        }
    }

    /** Leader view: how long the oldest uncommitted STRICT override has waited, 0 when none. */
    long oldestStrictPendingAgeNanos() {
        lock.lock();
        try {
            var oldest = role == Role.LEADER ? strictPending.firstEntry() : null;
            return oldest == null ? 0L : Math.max(0L, clock.nanoTime() - oldest.getValue());
        } finally {
            lock.unlock();
        }
    }

    /** The leader plus data voters that answered an append within the last lease. */
    private int reachableDataCopies(long now) {
        int count = countsAsStored(self) ? 1 : 0;
        for (var peer : peers) {
            Long heard = heardAt.get(peer);
            if (heard != null && now - heard <= options.leaseNanos() && isVoter(peer) && !witnesses.contains(peer)) {
                count++;
            }
        }
        return count;
    }

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

    /** At most one persist-failure WARNING per group per this interval; the rest are counted. */
    static final long PERSIST_WARN_INTERVAL_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(10);

    /**
     * Every role: a node stuck below the cluster's term should be visible. Rate-limited, because a
     * failing disk sees every higher-term frame (several per heartbeat interval per peer).
     */
    private void warnPersistFailed(long newTerm) {
        long now = clock.nanoTime();
        if (persistWarned && now - persistWarnAt < PERSIST_WARN_INTERVAL_NANOS) {
            persistWarnSuppressed++;
            return;
        }
        String suppressed = persistWarnSuppressed == 0 ? ""
                : " (" + persistWarnSuppressed + " similar warnings suppressed)";
        persistWarned = true;
        persistWarnAt = now;
        persistWarnSuppressed = 0;
        System.getLogger(RaftGroup.class.getName()).log(System.Logger.Level.WARNING,
                "group " + groupId.value() + ": " + role.name().toLowerCase(java.util.Locale.ROOT) + " at term "
                        + term + " saw term " + newTerm + " but could not persist it; staying in term " + term
                        + " and sending nothing" + suppressed);
    }

    /**
     * False when a higher term could not be made durable: term and vote are unchanged and the caller sends nothing.
     * A leader in that case still steps down, at its own term (no write needed): it knows a later term exists, so
     * it must stop reporting itself as leader and stop acking (#255). Its pending writes fail as outcome unknown.
     */
    private boolean stepDown(long newTerm, List<Runnable> after) {
        if (newTerm > term && !saveHardState(newTerm, null)) {
            warnPersistFailed(newTerm);
            if (role == Role.LEADER) {
                stepDown(term, after);
            }
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
            failPendingWrites(after);
            fireLeader(after);
        }
        return true;
    }

    /**
     * Under the lock, on every way out of {@link Role#LEADER} (step-down, leadership transfer, an election started
     * as leader, a same-term demotion): fails this leader's pending appends and its pending membership change with
     * {@link OutcomeUnknownException}, and its waiting read barriers with {@link NotLeaderException}. That keeps
     * {@link #pending} empty whenever this node does not lead, so the commit loop in {@link #apply} can never
     * complete a leader-side future on a follower, where the entry at that index may since have been overwritten by
     * another leader's (#250). The writes are in the log, so their outcome is unknown, not refused: an entry already
     * on a follower may still commit under the next leader, so callers deduplicate on retry (#254).
     */
    private void failPendingWrites(List<Runnable> after) {
        failPendingWrites(after, Optional.ofNullable(self.equals(leader) ? null : leader), true);
    }

    /**
     * As {@link #failPendingWrites(List)}, naming {@code hint} as the leader to retry or read back at. Appends fail on
     * the delivery thread ({@code viaDeliveries}), after every callback queued before; {@link #closeGroup()} passes
     * false because its delivery thread is already closed, and fails them in {@code after}, outside the lock.
     */
    private void failPendingWrites(List<Runnable> after, Optional<NodeId> hint, boolean viaDeliveries) {
        // Accepted writes: in the log, fate unknown (#254). Not NotLeaderException, which says "nothing appended".
        var unknown = new OutcomeUnknownException(groupId, "this node stopped leading before the write committed",
                hint, null);
        // Failed on the delivery thread too, after every callback already queued for earlier entries.
        for (var future : pending.values()) {
            if (viaDeliveries) {
                deliveries.fail(future, unknown);
            } else {
                after.add(() -> future.completeExceptionally(unknown));
            }
        }
        pending.clear();
        // Read barriers waiting for a quorum round would otherwise hang (only a quorum ack as leader completed
        // them), or complete later if this node led again (#246). A read has no outcome to lose: not leader.
        var refused = new NotLeaderException(groupId, hint);
        for (var read : reads) {
            after.add(() -> read.completeExceptionally(refused));
        }
        reads.clear();
        if (membershipChange != null) {
            var change = membershipChange;
            membershipChange = null;
            after.add(() -> change.completeExceptionally(unknown));
        }
    }

    /**
     * {@link ReplicatedNode#close()}, after the Raft thread stopped and this group's deliveries closed: a permanent
     * step-down (#254). It goes through the shared {@link #failPendingWrites} path, so pending appends and a pending
     * membership change fail with {@link OutcomeUnknownException} (they are in the log) and waiting read barriers with
     * {@link NotLeaderException}. Afterwards the group ignores ticks and frames and never campaigns, so it is never
     * elected again; appends are refused with {@link GroupClosedException} (nothing appended). Idempotent.
     */
    /**
     * First step of {@link ReplicatedNode#close()}, before it waits for the Raft thread: from here the group never
     * campaigns, votes, acks or accepts an append, even if that thread is still running a tick (#254). Idempotent.
     */
    void markClosed() {
        lock.lock();
        try {
            closed = true;
            campaignNotBefore = Long.MAX_VALUE;
            electionDeadline = Long.MAX_VALUE;
        } finally {
            lock.unlock();
        }
    }

    void closeGroup() {
        var after = new ArrayList<Runnable>();
        lock.lock();
        try {
            closed = true;
            failPendingWrites(after, Optional.ofNullable(self.equals(leader) ? null : leader), false);
            role = Role.FOLLOWER;
            leader = null;
            campaignNotBefore = Long.MAX_VALUE;
            electionDeadline = Long.MAX_VALUE;
        } finally {
            lock.unlock();
        }
        run(after);
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

    /** RELAXED: fsync later (tick) or now when the entry bound is reached. */
    private void markDirty() {
        if (!relaxedDirty) {
            relaxedDirty = true;
            dirtySince = clock.nanoTime();
        }
        if (lastIndex() - durableIndex >= options.relaxedSyncEntries()) {
            force();
            relaxedDirty = false;
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

    /**
     * Under the lock, before any log prefix is deleted: the current snapshot must be durable, or
     * a restart would have neither the entries nor the image. Refuses on a storage without snapshots.
     */
    private void persistSnapshotBeforePrefixDelete() throws IOException {
        refuseIfFailedClosed();
        if (!storage.persistsSnapshots()) {
            throw new IllegalStateException("group " + groupId.value() + ": the log storage keeps no durable snapshot,"
                    + " so deleting the log prefix would lose committed state on restart; refused");
        }
        storage.saveSnapshot(groupId, new LogSnapshot(groupId, snapIndex, snapTerm, snapData));
    }

    long skippedSnapshots() {
        lock.lock();
        try {
            return skippedSnapshots;
        } finally {
            lock.unlock();
        }
    }

    private void snapshotThrough(long index) {
        try {
            var out = new ByteArrayOutputStream();
            out.write(snapData);
            for (long i = snapIndex + 1; i <= index; i++) {
                var data = read(i);
                if (data != null && data.length > 0 && (data[0] == USER || data[0] == USER_STRICT)) {
                    out.write(data, 1, data.length - 1);
                }
            }
            if (out.size() > options.maxSnapshotBytes()) {
                skippedSnapshots++;
                return;
            }
            snapData = out.toByteArray();
            snapTerm = termAt(index);
            snapIndex = index;
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    /**
     * Queued behind every delivery for indexes up to {@code index} and ahead of later ones, so the
     * taker reads the application state at exactly {@code index}. The result is installed under the
     * lock if it still moves the snapshot forward.
     */
    private void submitTaker(SnapshotTaker use, long index, CompletableFuture<LogSnapshot> done) {
        deliveries.submit(() -> {
            byte[] image;
            try {
                image = use.snapshot(groupId, index);
            } catch (Exception failed) {
                image = null;
                if (done != null) {
                    done.completeExceptionally(failed);
                }
            }
            if (image != null && image.length > options.maxSnapshotBytes()) {
                var tooBig = new IllegalStateException("snapshot image of " + image.length
                        + " bytes exceeds maxSnapshotBytes " + options.maxSnapshotBytes());
                image = null;
                if (done != null) {
                    done.completeExceptionally(tooBig);
                }
            }
            lock.lock();
            try {
                takerInFlight = false;
                if (storageFailed != null) {
                    // Failed closed while the taker ran: install nothing, not even in memory.
                    if (done != null) {
                        done.completeExceptionally(fencedError());
                    }
                    return;
                }
                if (image == null) {
                    skippedSnapshots++;
                }
                if (image != null && index > snapIndex) {
                    snapTerm = termAt(index);
                    snapIndex = index;
                    snapData = image;
                }
                if (done != null && image != null) {
                    done.complete(new LogSnapshot(groupId, snapIndex, snapTerm, snapData));
                } else if (done != null) {
                    done.completeExceptionally(new IllegalStateException("snapshot taker returned no image"));
                }
            } finally {
                lock.unlock();
            }
        }, done);
    }

    private static byte[] user(byte[] payload, byte marker) {
        var out = new byte[payload.length + 1];
        out[0] = marker;
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
        public long leaderTerm() {
            lock.lock();
            try {
                return role == Role.LEADER ? term : -1L;
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
        public long durableIndex() {
            lock.lock();
            try {
                return durableIndex;
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
                if (storageFailed != null) {
                    future.completeExceptionally(fencedError());
                    return future;
                }
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
                if (storageFailed != null) {
                    future.completeExceptionally(fencedError());
                    return future;
                }
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
                // Like stepDown: without this the futures stayed pending and a later commit at their index (another
                // leader's entry) completed them as committed (#250). The transfer target is the likely next leader.
                failPendingWrites(after, Optional.of(target), true);
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
        public void setSnapshotTaker(SnapshotTaker next) {
            lock.lock();
            try {
                taker = next;
            } finally {
                lock.unlock();
            }
        }

        @Override
        public LogSnapshot take(long lastIncludedIndex) {
            CompletableFuture<LogSnapshot> done = null;
            lock.lock();
            try {
                refuseIfFailedClosed();
                if (taker != null) {
                    if (lastIncludedIndex != commitIndex) {
                        throw new IllegalArgumentException("with a SnapshotTaker, take(commitIndex()) only");
                    }
                    if (lastIncludedIndex == snapIndex) {
                        return new LogSnapshot(groupId, snapIndex, snapTerm, snapData);
                    }
                    if (deliveries.onExecutorThread()) {
                        throw new IllegalStateException("take() with a SnapshotTaker cannot run inside a callback");
                    }
                    done = new CompletableFuture<>();
                    submitTaker(taker, lastIncludedIndex, done);
                }
            } finally {
                lock.unlock();
            }
            if (done != null) {
                try {
                    return done.join();
                } catch (java.util.concurrent.CompletionException failed) {
                    throw new IllegalStateException("snapshot taker failed", failed.getCause());
                }
            }
            lock.lock();
            try {
                refuseIfFailedClosed();
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
            if (snapshot.data().length > options.maxSnapshotBytes()) {
                throw new IllegalArgumentException("snapshot image of " + snapshot.data().length
                        + " bytes exceeds maxSnapshotBytes " + options.maxSnapshotBytes());
            }
            lock.lock();
            try {
                refuseIfFailedClosed();
                long index = snapshot.lastIncludedIndex();
                if (index <= snapIndex) {
                    throw new IllegalArgumentException("stale snapshot: index " + index + " does not advance past "
                            + snapIndex);
                }
                long local = termAt(index);
                if (local != 0 && local != snapshot.lastIncludedTerm()) {
                    throw new IllegalArgumentException("snapshot term " + snapshot.lastIncludedTerm()
                            + " conflicts with the local log's term " + local + " at index " + index);
                }
                var previous = new long[] {snapIndex, snapTerm};
                var previousData = snapData;
                snapIndex = index;
                snapTerm = snapshot.lastIncludedTerm();
                snapData = snapshot.data();
                try {
                    persistSnapshotBeforePrefixDelete();
                    storage.truncatePrefix(groupId, snapIndex);
                    truncatedThrough = Math.max(truncatedThrough, snapIndex);
                } catch (IOException | RuntimeException failed) {
                    snapIndex = previous[0];
                    snapTerm = previous[1];
                    snapData = previousData;
                    throw failed instanceof IOException io ? new UncheckedIOException(io) : (RuntimeException) failed;
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
        public void setTruncationBound(TruncationBound bound) {
            truncationBound = bound;
        }

        @Override
        public CompletableFuture<Void> truncateAndCompact(long ackedThrough, Compactor compactor) {
            Objects.requireNonNull(compactor, "compactor");
            var future = new CompletableFuture<Void>();
            if (failedClosed(future)) {
                return future;
            }
            long through = ackedThrough;
            var bound = truncationBound;
            if (bound != null) {
                try {
                    through = Math.min(through, bound.truncatableThrough(groupId, currentSnapIndex()));
                } catch (Exception failed) {
                    future.completeExceptionally(failed);
                    return future;
                }
            }
            lock.lock();
            try {
                if (snapIndex < ackedThrough) {
                    future.completeExceptionally(new IllegalArgumentException("not snapshotted"));
                    return future;
                }
                if (truncateLocked(through, compactor, future)) {
                    future.complete(null);
                }
            } finally {
                lock.unlock();
            }
            return future;
        }

        @Override
        public CompletableFuture<Long> truncateToBound(Compactor compactor) {
            Objects.requireNonNull(compactor, "compactor");
            var future = new CompletableFuture<Long>();
            if (failedClosed(future)) {
                return future;
            }
            var bound = truncationBound;
            if (bound == null) {
                future.completeExceptionally(new IllegalStateException("no truncation bound set"));
                return future;
            }
            long snapshot = currentSnapIndex();
            long limit;
            try {
                limit = bound.truncatableThrough(groupId, snapshot);
            } catch (Exception failed) {
                future.completeExceptionally(failed);
                return future;
            }
            lock.lock();
            try {
                // The snapshot can only have moved forward meanwhile; never past what the bound saw.
                if (truncateLocked(Math.min(snapshot, limit), compactor, future)) {
                    future.complete(truncatedThrough);
                }
            } finally {
                lock.unlock();
            }
            return future;
        }

        /** Fails {@code future} with {@link #fencedError()} when the group has failed closed. */
        private boolean failedClosed(CompletableFuture<?> future) {
            lock.lock();
            try {
                if (storageFailed == null) {
                    return false;
                }
                future.completeExceptionally(fencedError());
                return true;
            } finally {
                lock.unlock();
            }
        }

        private long currentSnapIndex() {
            lock.lock();
            try {
                return snapIndex;
            } finally {
                lock.unlock();
            }
        }

        /**
         * Under the lock: compact, make the snapshot durable, then drop the prefix through
         * {@code through} if that is new. False after failing {@code future}; nothing is deleted then.
         */
        private boolean truncateLocked(long through, Compactor compactor, CompletableFuture<?> future) {
            if (storageFailed != null) { // re-checked under the lock: it may have failed closed since the first look
                future.completeExceptionally(fencedError());
                return false;
            }
            var current = new LogSnapshot(groupId, snapIndex, snapTerm, snapData);
            var compacted = compactor.compact(current);
            if (compacted != null) {
                if (compacted.length > options.maxSnapshotBytes()) {
                    future.completeExceptionally(new IllegalStateException("compacted image of "
                            + compacted.length + " bytes exceeds maxSnapshotBytes " + options.maxSnapshotBytes()));
                    return false;
                }
                snapData = compacted;
            }
            if (through <= truncatedThrough) {
                return true;
            }
            try {
                // Durable image first: after a restart the deleted prefix is rebuilt from it.
                persistSnapshotBeforePrefixDelete();
                storage.truncatePrefix(groupId, through);
            } catch (IOException | RuntimeException failed) {
                future.completeExceptionally(failed);
                return false;
            }
            truncatedThrough = through;
            return true;
        }
    }
}
