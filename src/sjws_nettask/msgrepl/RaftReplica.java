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
package sjws_nettask.msgrepl;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import sjws_nettask.cluster.ApplyLagException;
import sjws_nettask.cluster.BatchRolledBackException;
import sjws_nettask.cluster.ClusterNode;
import sjws_nettask.cluster.CommittedEntry;
import sjws_nettask.cluster.CommittedNotAppliedException;
import sjws_nettask.cluster.DurabilityUnavailableException;
import sjws_nettask.cluster.GroupClosedException;
import sjws_nettask.cluster.GroupFencedException;
import sjws_nettask.cluster.GroupId;
import sjws_nettask.cluster.GroupOptions;
import sjws_nettask.cluster.NodeId;
import sjws_nettask.cluster.NotLeaderException;
import sjws_nettask.cluster.OutcomeUnknownException;
import sjws_nettask.cluster.ReplicationGroup;
import sjws_nettask.cluster.TermChangedException;
import sjws_nettask.messaging.ledger.Durability;
import sjws_nettask.messaging.ledger.LeaderUnavailableException;
import sjws_nettask.messaging.ledger.LedgerWriter;
import sjws_nettask.messaging.ledger.Replica;
import sjws_nettask.messaging.ledger.WriteOutcomeUnknownException;
import sjws_nettask.messaging.ledger.WriteRefusedException;
import sjws_nettask.messaging.ledger.ReplicaHooks;
import sjws_nettask.messaging.ledger.SegmentedLog;
import sjws_nettask.cluster.GroupListener;
import sjws_nettask.cluster.LogSnapshot;

/**
 * One partition's consensus group wrapping its local ledger (msg-replication-sketch phase A).
 *
 * <p>Entry = one produce batch: {@code [M][mode][stampTerm:long][origin:int][seq:long][count:int]([len:int][bytes])*}.
 * Offsets are assigned at apply, so every node's ledger holds the same records at the same offsets. The proposer stamps
 * the term it proposed in; an entry that commits in another term is skipped on every node (stale-term fence). The
 * produce ack completes when the entry is committed (STRICT for group/each, RELAXED for none/async) and applied to
 * this node's ledger with the batch's own local mode.
 *
 * <p>{@code <topic>.applied} records {@code [index][term][baseOffset][count]} per applied batch (the per-batch term
 * stamp). The consensus log is replayed from its start on restart; this file plus the ledger end decide which entries
 * are already in the ledger, so replay never duplicates records. Its first record is the header: the snapshot point
 * {@code (index, term, endOffset)} the file starts after (index 0 and the ledger end when replication started). A
 * record with count -1 is a replicated retention decision: {@code base} is the new log start.
 *
 * <p><b>Snapshots (#270)</b>, with a {@link Snapshots.Runtime}. Off the produce path, the replicator's scheduler checks
 * each replica: once {@code everyEntries} entries were applied since the last snapshot, the latest applied entry is the
 * point. The ledger is fsynced through it (constraint 1), its segment files are pinned ({@link SnapshotStore}), and the
 * {@link MsgSnapshot} manifest is recorded as the group's snapshot ({@code recordSnapshot}); the consensus log is then
 * truncated up to {@code tailEntries} entries behind it (constraint 5), and the stamp file is cut to start at the point.
 * A follower the leader can no longer serve from the log receives the manifest as a consensus snapshot and, on its
 * delivery thread, fetches the segment data from a pin on any data node ({@link SnapshotTransfer}), swaps it in for
 * its whole ledger, restores the application state, and continues from the installed point. An install marker
 * ({@code <topic>.install}) makes a crash anywhere in that sequence redo it on restart.
 *
 * <p>Entry {@code [R][stampTerm][origin][seq][offset]}: "advance the log start to {@code offset}", proposed by the
 * leader's retention pass and applied on every replica (constraint 3).
 */
final class RaftReplica implements Replica {

    private static final byte MAGIC = 'M';
    private static final byte RETAIN = 'R';
    private static final int HEADER = 1 + 1 + 8 + 4 + 8 + 4;
    private static final int RETAIN_BYTES = 1 + 8 + 4 + 8 + 8;
    private static final int STAMP = 28;

    private final ClusterNode node;
    private final GroupId groupId;
    private final ReplicationGroup group;
    private final LedgerWriter local;
    private final Durability defaultMode;
    private final Consumer<List<byte[]>> onApplied;
    private final Map<String, String> urls;
    private final int self;
    /** Guarded by {@link #stampLock}: the delivery thread appends, the snapshotter rewrites. */
    private FileChannel stamps;
    private final java.util.concurrent.locks.ReentrantLock stampLock = new java.util.concurrent.locks.ReentrantLock();
    private final Path stampFile;
    private final String topic;
    private final SegmentedLog log;
    private final ReplicaHooks hooks;
    /** Null: no snapshots (the replicator was built without a runtime). */
    private final Snapshots.Runtime snaps;
    private final Path installMarker;
    private final Path staging;
    private java.util.concurrent.ScheduledFuture<?> tick;
    private final java.util.concurrent.atomic.AtomicBoolean snapshotting = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile boolean closed;
    /** An install is owed (marker on disk, or one is running): the ledger is not trusted until it completes. */
    private volatile boolean installPending;
    /** The replicated log start (constraint 3). */
    private volatile long logStart;
    /** Latest applied batch whose records are written: the next snapshot point. */
    private volatile Applied lastApplied;
    /** Consensus index of the last snapshot recorded or restored. */
    private volatile long recordedIndex;
    /** The last local append, so an install can wait for the writer to drain. */
    private volatile CompletableFuture<?> lastLocal = CompletableFuture.completedFuture(null);
    private final AtomicLong installs = new AtomicLong();
    private final AtomicLong snapshotsTaken = new AtomicLong();
    /** Tests only: called at named points of a snapshot or an install to simulate a crash there. Null in production. */
    volatile java.util.function.Consumer<String> faultHook;

    record Applied(long index, long term, long end) { }
    private final java.util.function.LongSupplier nanoClock;
    /** Single-member group: no peers renew the lease, and none can be elected instead. */
    private final boolean solo;
    private final AtomicLong seqs = new AtomicLong();
    private final ConcurrentHashMap<Long, CompletableFuture<Long>> pending = new ConcurrentHashMap<>();
    /** Term whose election no-op this node applied as leader; -1 before. */
    private volatile long readyTerm = -1;
    // Delivery-thread state (one delivery thread per group).
    private long resumeIndex;
    private long expected;
    private long skipTo;
    private volatile long appliedIndex;

    RaftReplica(ClusterNode node, GroupId groupId, GroupOptions options, LedgerWriter local, Durability defaultMode,
            Consumer<List<byte[]>> onApplied, Map<String, String> urls, Path stampFile, java.util.function.LongSupplier nanoClock) throws IOException {
        this(node, groupId, options, local, defaultMode, null, onApplied, urls, stampFile, nanoClock, null);
    }

    RaftReplica(ClusterNode node, GroupId groupId, GroupOptions options, LedgerWriter local, Durability defaultMode,
            ReplicaHooks hooks, Consumer<List<byte[]>> onApplied, Map<String, String> urls, Path stampFile,
            java.util.function.LongSupplier nanoClock, Snapshots.Runtime snaps) throws IOException {
        this.nanoClock = nanoClock;
        this.solo = node.membership().others().isEmpty();
        this.node = node;
        this.groupId = groupId;
        this.local = local;
        this.log = local.log();
        this.defaultMode = defaultMode;
        this.hooks = hooks;
        this.onApplied = hooks != null ? hooks.onApplied() : onApplied;
        this.urls = urls;
        this.snaps = snaps;
        this.topic = groupId.value().substring("msg/".length());
        this.self = node.self().wire();
        this.stampFile = stampFile;
        this.installMarker = stampFile.resolveSibling(this.topic + ".install");
        this.staging = stampFile.resolveSibling(this.topic + ".staging");
        Files.deleteIfExists(stampFile.resolveSibling(stampFile.getFileName() + ".tmp"));
        this.stamps = FileChannel.open(stampFile, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        this.installPending = Files.exists(this.installMarker);
        long ledgerEnd = local.snapshot().nextOffset();
        this.skipTo = ledgerEnd;
        recover(ledgerEnd);
        this.recordedIndex = this.resumeIndex;
        if (this.logStart > 0 && hooks != null) hooks.dropBelow().accept(this.logStart); // a crash may have kept them
        var listener = new GroupListener() {
            @Override
            public void onCommitted(CommittedEntry entry) {
                RaftReplica.this.onCommitted(entry);
            }

            @Override
            public void onSnapshotRestored(LogSnapshot snapshot) {
                RaftReplica.this.onSnapshotRestored(snapshot);
            }
        };
        this.group = node.openGroup(groupId, options, listener);
        node.onNoopApplied(groupId, (term, index) -> this.readyTerm = term);
        if (snaps != null) {
            long tail = snaps.settings.tailEntries();
            node.snapshots(groupId).setTruncationBound((g, snapIndex) -> Math.max(0, snapIndex - tail));
            long every = snaps.settings.check().toMillis();
            this.tick = snaps.exec.scheduleWithFixedDelay(this::snapshotTick, every, every, java.util.concurrent.TimeUnit.MILLISECONDS);
        }
    }

    /** Reads the stamp file: resume after the last batch wholly in the ledger; drops stamps past it. */
    private void recover(long ledgerEnd) throws IOException {
        long size = this.stamps.size() / STAMP * STAMP;
        if (size == 0) { // header: the ledger end when replication started (pre-HA records are not in the group)
            this.stamps.truncate(0);
            writeStamp(0, 0, ledgerEnd, 0);
            this.expected = ledgerEnd;
            return;
        }
        var buf = ByteBuffer.allocate((int) Math.min(size, (long) STAMP << 15));
        long pos = 0, keep = STAMP;
        boolean first = true, gap = false;
        while (pos < size) {
            buf.clear().limit((int) Math.min(buf.capacity(), size - pos));
            while (buf.hasRemaining()) if (this.stamps.read(buf, pos + buf.position()) < 0) break;
            buf.flip();
            while (buf.remaining() >= STAMP) {
                long index = buf.getLong(), term = buf.getLong(), base = buf.getLong();
                int count = buf.getInt();
                if (first) {
                    this.expected = base;
                    this.resumeIndex = index; // the snapshot point the file starts after (0 before any snapshot)
                    first = false;
                } else if (gap) {
                    continue; // after the first batch not in the ledger: everything is re-applied
                } else if (count < 0) { // replicated retention: base is the new log start
                    this.logStart = Math.max(this.logStart, base);
                    this.resumeIndex = index;
                    keep = pos + buf.position();
                } else if (base + count > ledgerEnd) {
                    gap = true;
                } else {
                    this.resumeIndex = index;
                    this.expected = base + count;
                    keep = pos + buf.position();
                }
            }
            pos += buf.limit();
        }
        this.stamps.truncate(keep);
    }

    private void writeStamp(long index, long term, long base, int count) throws IOException {
        var b = ByteBuffer.allocate(STAMP).putLong(index).putLong(term).putLong(base).putInt(count).flip();
        this.stampLock.lock();
        try {
            long at = this.stamps.size();
            while (b.hasRemaining()) at += this.stamps.write(b, at);
        } finally {
            this.stampLock.unlock();
        }
    }

    /**
     * Rewrites the stamp file to start at the snapshot point {@code (index, term, end)}: the header, the log start, and
     * every stamp after {@code index}. Not fsynced, like every stamp: a lost or stale file is rebuilt from the restored
     * snapshot and the ledger end on restart.
     */
    private void resetStamps(long index, long term, long end, long keepAfter) throws IOException {
        var tmp = this.stampFile.resolveSibling(this.stampFile.getFileName() + ".tmp");
        this.stampLock.lock();
        try {
            long size = this.stamps.size() / STAMP * STAMP;
            var all = ByteBuffer.allocate((int) Math.min(size, Integer.MAX_VALUE - 8));
            while (all.hasRemaining()) if (this.stamps.read(all, all.position()) < 0) break;
            all.flip();
            var out = ByteBuffer.allocate(2 * STAMP + all.remaining());
            out.putLong(index).putLong(term).putLong(end).putInt(0);
            out.putLong(index).putLong(term).putLong(this.logStart).putInt(-1);
            all.position(Math.min(all.limit(), STAMP)); // skip the old header
            while (all.remaining() >= STAMP) {
                int at = all.position();
                long i = all.getLong(at);
                if (i > keepAfter) out.put(all.array(), at, STAMP);
                all.position(at + STAMP);
            }
            out.flip();
            try (var ch = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                while (out.hasRemaining()) ch.write(out);
            }
            fault("stamps-written");
            Files.move(tmp, this.stampFile, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            var old = this.stamps;
            this.stamps = FileChannel.open(this.stampFile, StandardOpenOption.READ, StandardOpenOption.WRITE);
            old.close();
        } finally {
            this.stampLock.unlock();
        }
    }

    private void fault(String at) throws IOException {
        var h = this.faultHook;
        if (h != null) {
            try {
                h.accept(at);
            } catch (RuntimeException e) {
                throw new IOException("injected fault at " + at, e);
            }
        }
    }

    static ByteBuffer encode(List<byte[]> records, Durability mode, long term, int origin, long seq) {
        int size = HEADER;
        for (var r : records) size += 4 + r.length;
        var b = ByteBuffer.allocate(size).put(MAGIC).put((byte) mode.ordinal()).putLong(term).putInt(origin).putLong(seq)
                .putInt(records.size());
        for (var r : records) b.putInt(r.length).put(r);
        return b.flip();
    }

    static ByteBuffer encodeRetain(long offset, long term, int origin, long seq) {
        return ByteBuffer.allocate(RETAIN_BYTES).put(RETAIN).putLong(term).putInt(origin).putLong(seq).putLong(offset).flip();
    }

    private void onCommitted(CommittedEntry e) {
        if (e.index() <= this.resumeIndex) return; // already in the ledger (restart replay or installed snapshot)
        var b = e.entry();
        if (b.remaining() == RETAIN_BYTES && b.get(b.position()) == RETAIN) {
            onRetain(e, b);
            return;
        }
        if (b.remaining() < HEADER || b.get() != MAGIC) return;
        var mode = Durability.values()[b.get()];
        long stamp = b.getLong();
        int origin = b.getInt();
        long seq = b.getLong();
        int count = b.getInt();
        var mine = origin == this.self ? this.pending.remove(seq) : null;
        if (stamp != e.term()) { // proposed in an older term, committed by a newer leader: fenced on every node
            if (mine != null) mine.completeExceptionally(unavailable("stale term " + stamp + " < " + e.term()));
            return;
        }
        var records = new ArrayList<byte[]>(count);
        for (int i = 0; i < count; i++) {
            var r = new byte[b.getInt()];
            b.get(r);
            records.add(r);
        }
        long base = this.expected;
        this.expected += count;
        try {
            writeStamp(e.index(), e.term(), base, count);
            long index = e.index(), term = e.term();
            if (base + count <= this.skipTo) { // in the ledger already: a crash lost the stamp, not the records
                this.appliedIndex = index;
                applied(index, term, base + count);
                if (mine != null) mine.complete(base);
                return;
            }
            var f = this.local.append(records, mode);
            this.lastLocal = f;
            f.whenComplete((at, err) -> {
                if (err == null) {
                    this.appliedIndex = index;
                    this.onApplied.accept(records);
                    applied(index, term, at + count);
                    if (mine != null) mine.complete(at);
                } else if (mine != null) {
                    mine.completeExceptionally(err);
                }
            });
        } catch (IOException | RuntimeException ex) {
            if (mine != null) mine.completeExceptionally(ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            if (mine != null) mine.completeExceptionally(ex);
        }
    }

    /** Publishes the newest written batch (completions can arrive out of order across ack modes: keep the max). */
    private void applied(long index, long term, long end) {
        while (true) {
            var cur = this.lastApplied;
            if (cur != null && cur.index() >= index) return;
            if (LAST_APPLIED.compareAndSet(this, cur, new Applied(index, term, end))) return;
        }
    }

    private static final java.lang.invoke.VarHandle LAST_APPLIED;

    static {
        try {
            LAST_APPLIED = java.lang.invoke.MethodHandles.lookup().findVarHandle(RaftReplica.class, "lastApplied", Applied.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** A replicated retention decision: every replica drops the same whole segments below it (constraint 3). */
    private void onRetain(CommittedEntry e, ByteBuffer b) {
        b.get();
        long stamp = b.getLong();
        int origin = b.getInt();
        long seq = b.getLong();
        long offset = b.getLong();
        var mine = origin == this.self ? this.pending.remove(seq) : null;
        if (stamp != e.term()) {
            if (mine != null) mine.completeExceptionally(unavailable("stale term " + stamp + " < " + e.term()));
            return;
        }
        try {
            writeStamp(e.index(), e.term(), offset, -1);
        } catch (IOException ex) {
            System.err.println("WARN: " + this.groupId.value() + ": retention stamp: " + ex);
        }
        if (offset > this.logStart) {
            this.logStart = offset;
            if (this.hooks != null) this.hooks.dropBelow().accept(offset);
        }
        this.appliedIndex = e.index();
        if (mine != null) mine.complete(offset);
    }

    @Override
    public CompletableFuture<Void> advanceLogStart(long offset) {
        if (this.snaps == null || this.hooks == null) return Replica.super.advanceLogStart(offset);
        long term = this.group.leaderTerm();
        if (!leaderReady(term)) return CompletableFuture.failedFuture(unavailable("not the ready leader"));
        long seq = this.seqs.incrementAndGet();
        var done = new CompletableFuture<Long>();
        this.pending.put(seq, done);
        this.node.append(this.groupId, List.of(encodeRetain(offset, term, this.self, seq)), null, term).whenComplete((ci, err) -> {
            if (err != null) {
                var p = this.pending.remove(seq);
                if (p != null) p.completeExceptionally(map(err, true));
            }
        });
        return done.thenApply(x -> null);
    }

    @Override
    public long logStartOffset() {
        return this.logStart;
    }

    long installs() {
        return this.installs.get();
    }

    long snapshotsTaken() {
        return this.snapshotsTaken.get();
    }

    long recordedIndex() {
        return this.recordedIndex;
    }

    /**
     * Scheduler thread, never the produce path: takes a snapshot when one is due (see the class doc). Any failure
     * leaves the previous snapshot and the whole log in place; the next check tries again.
     */
    void snapshotTick() {
        if (this.closed || this.installPending || this.snaps == null || !this.snapshotting.compareAndSet(false, true)) return;
        try {
            var a = this.lastApplied;
            if (a == null || a.index() - this.recordedIndex < this.snaps.settings.everyEntries()) return;
            snapshotAt(a);
        } catch (IOException | RuntimeException e) {
            warn(this.groupId.value() + ": snapshot not taken: " + e);
        } finally {
            this.snapshotting.set(false);
        }
    }

    /** Takes the snapshot at {@code a} now (also tests). */
    boolean snapshotAt(Applied a) throws IOException {
        // Constraint 1: the snapshot point must be durable in the segments, not only applied.
        if (this.local.snapshot().durableOffset() < a.end()) this.log.force();
        var state = this.hooks != null && this.hooks.state() != null ? this.hooks.state().snapshotState() : null;
        var m = this.snaps.store.pin(this.topic, this.log, a.index(), a.term(), a.end(), this.logStart, state,
                this.snaps.settings.chunkBytes());
        if (m == null) return false;
        fault("snapshot-pinned");
        var control = this.node.snapshots(this.groupId);
        control.recordSnapshot(new LogSnapshot(this.groupId, a.index(), a.term(), m.encode()));
        this.recordedIndex = a.index();
        this.snapshotsTaken.incrementAndGet();
        fault("snapshot-recorded");
        try {
            control.truncateToBound(snapshot -> null).get(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            warn(this.groupId.value() + ": truncation after the snapshot at " + a.index() + ": " + e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true;
        }
        resetStamps(a.index(), a.term(), a.end(), a.index());
        return true;
    }

    private volatile long lastWarnNanos = Long.MIN_VALUE / 2;

    /** At most one warning per 10 s per replica: a peer down during an install must not flood the log. */
    private void warn(String message) {
        long now = this.nanoClock.getAsLong();
        if (now - this.lastWarnNanos < 10_000_000_000L) return;
        this.lastWarnNanos = now;
        System.err.println("WARN: " + message);
    }

    /**
     * Delivery thread, on open (the durable snapshot) or after the leader sent one: either this node's own ledger
     * already holds the snapshot point, or the ledger is replaced by an install (constraint 3: nothing below the
     * leader's log start is kept).
     */
    private void onSnapshotRestored(LogSnapshot s) {
        MsgSnapshot m;
        try {
            m = MsgSnapshot.decode(s.data());
            if (!m.topic().equals(this.topic) || m.index() != s.lastIncludedIndex() || m.term() != s.lastIncludedTerm()) {
                throw new IllegalArgumentException("snapshot of " + m.topic() + " at " + m.index() + " delivered to "
                        + this.groupId.value() + " at " + s.lastIncludedIndex());
            }
        } catch (IllegalArgumentException e) {
            System.err.println("ERROR: " + this.groupId.value() + ": unusable snapshot, replica stops applying: " + e.getMessage());
            this.installPending = true; // never append on top of a ledger we cannot place
            return;
        }
        if (this.snaps != null && this.hooks != null && (this.installPending || this.local.snapshot().nextOffset() < m.endOffset())) {
            install(m);
            return;
        }
        if (this.local.snapshot().nextOffset() < m.endOffset()) {
            System.err.println("ERROR: " + this.groupId.value() + ": snapshot at " + m.index() + " needs offsets up to "
                    + m.endOffset() + " that this node cannot install (no snapshot runtime); replica stops applying");
            this.installPending = true;
            return;
        }
        if (this.resumeIndex < m.index()) {
            this.resumeIndex = m.index();
            this.expected = m.endOffset();
        }
        this.logStart = Math.max(this.logStart, m.logStart());
        this.recordedIndex = Math.max(this.recordedIndex, m.index());
        this.appliedIndex = Math.max(this.appliedIndex, m.index());
        applied(m.index(), m.term(), m.endOffset());
        if (this.hooks != null && this.hooks.state() != null && m.state().length > 0) this.hooks.state().restoreState(m.state());
    }

    /**
     * Fetches the newest pin at or above {@code m} from the data nodes (leader first), swaps it in for the whole local
     * ledger, and moves the apply point there. Blocks this group's delivery thread: no later entry may apply before
     * the ledger it lands on. Retries until it succeeds or the replica closes; the marker makes a restart redo it.
     */
    private void install(MsgSnapshot m) {
        try {
            if (!this.installPending) {
                var tmp = this.installMarker.resolveSibling(this.installMarker.getFileName() + ".tmp");
                Files.write(tmp, m.encode());
                try (var ch = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                    ch.force(true);
                }
                Files.move(tmp, this.installMarker, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                SnapshotStore.fsyncDir(this.installMarker.getParent());
                this.installPending = true;
            }
            fault("install-marked");
            MsgSnapshot got = null;
            long backoff = 20;
            while (got == null && !this.closed) {
                for (var peer : peerOrder()) {
                    try {
                        got = SnapshotTransfer.fetch(peer, this.snaps.key, this.topic, m.index(), this.staging,
                                GroupOptions.DEFAULT_MAX_SNAPSHOT_BYTES, this.snaps.clockMs);
                        break;
                    } catch (IOException e) {
                        warn(this.groupId.value() + ": install from " + peer + ": " + e.getMessage());
                    }
                }
                if (got == null) {
                    Thread.sleep(backoff);
                    backoff = Math.min(2_000, backoff * 2);
                }
            }
            if (got == null) return;
            fault("install-fetched");
            try {
                this.lastLocal.get(30, java.util.concurrent.TimeUnit.SECONDS); // the writer is drained: nothing in flight
            } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException ignored) {
                // a failed append left nothing in flight either
            }
            this.hooks.readersOut().lock();
            try {
                this.log.replaceWith(this.staging);
                this.local.reload();
            } finally {
                this.hooks.readersOut().unlock();
            }
            fault("install-swapped");
            this.logStart = got.logStart();
            this.resumeIndex = got.index();
            this.expected = got.endOffset();
            this.skipTo = got.endOffset();
            this.recordedIndex = got.index();
            this.appliedIndex = Math.max(this.appliedIndex, got.index());
            this.lastApplied = new Applied(got.index(), got.term(), got.endOffset());
            resetStamps(got.index(), got.term(), got.endOffset(), Long.MAX_VALUE);
            if (this.hooks.state() != null) this.hooks.state().restoreState(got.state());
            this.hooks.reloaded().run();
            Files.deleteIfExists(this.installMarker);
            SnapshotStore.deleteTree(this.staging);
            this.installPending = false;
            this.installs.incrementAndGet();
        } catch (IOException | RuntimeException e) {
            System.err.println("ERROR: " + this.groupId.value() + ": snapshot install failed, replica stops applying until"
                    + " restart: " + e);
            this.installPending = true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Data nodes to fetch from: the current leader first, then the others; never this node. */
    private List<InetSocketAddress> peerOrder() {
        var out = new ArrayList<InetSocketAddress>();
        var leader = leaderName();
        var me = this.node.self().name();
        if (leader != null && !leader.equals(me) && this.snaps.peers.containsKey(leader)) out.add(this.snaps.peers.get(leader));
        for (var e : this.snaps.peers.entrySet()) {
            if (!e.getKey().equals(me) && !e.getKey().equals(leader)) out.add(e.getValue());
        }
        return out;
    }

    @Override
    public CompletableFuture<Long> append(List<byte[]> records, Durability mode) {
        return appendNoWait(records, mode);
    }

    @Override
    public CompletableFuture<Long> appendNoWait(List<byte[]> records, Durability mode) {
        var m = mode == null ? this.defaultMode : mode;
        long term = this.group.leaderTerm();
        if (this.installPending) return CompletableFuture.failedFuture(unavailable("installing a snapshot"));
        if (!leaderReady(term)) return CompletableFuture.failedFuture(unavailable("not the ready leader"));
        long seq = this.seqs.incrementAndGet();
        var acked = new CompletableFuture<Long>();
        this.pending.put(seq, acked);
        var cd = m == Durability.GROUP || m == Durability.EACH ? sjws_nettask.cluster.Durability.STRICT : null;
        // Conditional on the term just checked: a step-down and re-election between the check and the
        // log write fails the append instead of proposing under the old term's readiness.
        this.node.append(this.groupId, List.of(encode(records, m, term, this.self, seq)), cd, term).whenComplete((ci, err) -> {
            if (err != null) {
                var p = this.pending.remove(seq);
                if (p != null) p.completeExceptionally(map(err, true));
            }
        });
        return acked;
    }

    @Override
    public void awaitCapacity() throws InterruptedException {
        this.local.awaitCapacity();
    }

    /**
     * {@code leaderTerm} is one {@link ReplicationGroup#leaderTerm()} read (role and term together, {@code -1}
     * when not leader). Ready = this term's no-op applied here, still leader in it, and inside its lease (an
     * isolated old leader stops acking).
     */
    private boolean leaderReady(long leaderTerm) {
        return leaderTerm > 0 && this.readyTerm == leaderTerm
                && (this.solo || this.group.leaseExpiresAt() > this.nanoClock.getAsLong());
    }

    @Override
    public boolean leaderReady() {
        return leaderReady(this.group.leaderTerm());
    }

    @Override
    public long term() {
        return this.group.term();
    }

    @Override
    public String leaderName() {
        return this.group.leader().map(NodeId::name).orElse(null);
    }

    @Override
    public String leaderUrl() {
        var n = leaderName();
        return n == null || n.equals(this.node.self().name()) ? null : this.urls.get(n);
    }

    long appliedIndex() {
        return this.appliedIndex;
    }

    @Override
    public CompletableFuture<Void> leaderRead() {
        if (!leaderReady()) return CompletableFuture.failedFuture(unavailable("not the ready leader"));
        return this.group.readBarrier().handle((v, err) -> {
            if (err != null) throw new CompletionException(map(err, false));
            return null;
        });
    }

    private LeaderUnavailableException unavailable(String why) {
        return new LeaderUnavailableException(this.groupId.value() + ": " + why, term(), leaderName(), leaderUrl());
    }

    /**
     * Consensus errors to messaging ones (#254). For a leader read ({@code write} false) only the leadership refusals
     * are translated; anything else passes through, and HTTP answers a generic 503 (a read has no write outcome).
     * For an append:
     * <ul>
     * <li>refused for leadership (not leader, term moved): {@link LeaderUnavailableException}, which HTTP may answer
     * with a 307 that resends the body;
     * <li>refused otherwise, nothing appended: {@link WriteRefusedException}, retryable for apply backpressure, too
     * few STRICT data copies, a closed node and a storage error on a fenced group or a cleanly rolled-back batch,
     * not retryable for argument errors (empty or oversized batch);
     * <li>in the log, fate unknown (leadership lost, failed closed, closed, committed but not applied here):
     * {@link WriteOutcomeUnknownException} with the leader hint, never a redirect: if it commits, a resent body
     * would be a second entry;
     * <li>anything unclassified passes through, and HTTP reports it as outcome unknown (the safe default).
     * </ul>
     * This is the one place where consensus errors become messaging errors: every append and leader-read failure
     * passes through it, and messaging code never has to know a cluster type (MessagingClusterIsolationTest).
     */
    Throwable map(Throwable err, boolean write) {
        var c = err instanceof CompletionException && err.getCause() != null ? err.getCause() : err;
        if (c instanceof NotLeaderException) return unavailable("not leader");
        if (c instanceof TermChangedException) return unavailable("term moved");
        if (!write) {
            return c; // a leader read has no write outcome: never not-appended or outcome unknown; HTTP says 503
        }
        if (c instanceof OutcomeUnknownException u) {
            var name = u.hint().map(NodeId::name).orElseGet(this::leaderName);
            return outcomeUnknown(u.getMessage(), name, c);
        }
        if (c instanceof CommittedNotAppliedException) {
            return outcomeUnknown(c.getMessage(), leaderName(), c);
        }
        if (c instanceof ApplyLagException || c instanceof DurabilityUnavailableException || c instanceof GroupClosedException
                || c instanceof GroupFencedException || c instanceof BatchRolledBackException) {
            return new WriteRefusedException(this.groupId.value() + ": not appended: " + c.getMessage(), true, c);
        }
        if (c instanceof IllegalArgumentException) {
            return new WriteRefusedException(this.groupId.value() + ": not appended: " + c.getMessage(), false, c);
        }
        return c;
    }

    private WriteOutcomeUnknownException outcomeUnknown(String message, String leader, Throwable cause) {
        var url = leader == null || leader.equals(this.node.self().name()) ? null : this.urls.get(leader);
        return new WriteOutcomeUnknownException(message, term(), leader, url, cause);
    }

    @Override
    public void close() {
        this.closed = true;
        if (this.tick != null) this.tick.cancel(false);
        this.node.openGroup(this.groupId, RaftReplicator.options(java.time.Duration.ofSeconds(1), java.time.Duration.ofMillis(100)), null);
        // Proposed and maybe in the log: the entry may still commit and be applied (on restart, or by the other
        // replicas), so a resend could duplicate it; not "not leader" (#254).
        var closed = outcomeUnknown(this.groupId.value() + ": closed before the append was acknowledged", leaderName(), null);
        this.pending.values().forEach(p -> p.completeExceptionally(closed));
        this.pending.clear();
        this.stampLock.lock();
        try {
            this.stamps.close();
        } catch (IOException e) {
            System.err.println("WARN: closing " + this.groupId.value() + " stamps: " + e);
        } finally {
            this.stampLock.unlock();
        }
    }
}
