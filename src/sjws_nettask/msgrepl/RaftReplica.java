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
import java.nio.ByteBuffer;
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
 * are already in the ledger, so replay never duplicates records. A clean {@link #close} appends a marker
 * ({@code index = -1}, {@code baseOffset} = the ledger end) once every apply finished.
 *
 * <p>Offsets are assigned from the ledger end, so every node's ledger must hold exactly the replicated records. The
 * open refuses ({@link IllegalArgumentException}, exit 64 at startup, naming the topic and both numbers) a ledger
 * that does not line up: the first replicated open of a non-empty ledger (records from before replication), a ledger
 * that moved past the end a clean close recorded (local writes while replication was off), and a ledger shorter
 * than where replication started. A ledger past the stamps after a crash (stamps not on disk yet) is still recovered
 * by replay, as before.
 */
final class RaftReplica implements Replica {

    private static final byte MAGIC = 'M';
    private static final int HEADER = 1 + 1 + 8 + 4 + 8 + 4;
    private static final int STAMP = 28;
    /** Stamp index of the clean-close marker. */
    private static final long CLEAN = -1;

    private final ClusterNode node;
    private final GroupId groupId;
    private final ReplicationGroup group;
    private final LedgerWriter local;
    private final Durability defaultMode;
    private final Consumer<List<byte[]>> onApplied;
    private final Map<String, String> urls;
    private final int self;
    private final FileChannel stamps;
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
    private final long skipTo;
    private volatile long appliedIndex;
    /** Guards the delivery-thread state against {@link #close}; never contended while open. */
    private final java.util.concurrent.locks.ReentrantLock applyLock = new java.util.concurrent.locks.ReentrantLock();
    private boolean closed;
    /** The latest local append of a committed batch (close waits for it before the clean marker). */
    private CompletableFuture<Long> lastApply = CompletableFuture.completedFuture(0L);
    private final String topic;

    RaftReplica(ClusterNode node, GroupId groupId, GroupOptions options, LedgerWriter local, Durability defaultMode,
            Consumer<List<byte[]>> onApplied, Map<String, String> urls, Path stampFile, java.util.function.LongSupplier nanoClock) throws IOException {
        this.nanoClock = nanoClock;
        this.solo = node.membership().others().isEmpty();
        this.node = node;
        this.groupId = groupId;
        this.local = local;
        this.defaultMode = defaultMode;
        this.onApplied = onApplied;
        this.urls = urls;
        this.self = node.self().wire();
        var name = groupId.value();
        this.topic = name.startsWith("msg/") ? name.substring(4) : name;
        this.stamps = FileChannel.open(stampFile, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        long ledgerEnd = local.snapshot().nextOffset();
        this.skipTo = ledgerEnd;
        try {
            recover(ledgerEnd);
        } catch (IOException | RuntimeException e) {
            this.stamps.close();
            throw e;
        }
        this.group = node.openGroup(groupId, options, this::onCommitted);
        node.onNoopApplied(groupId, (term, index) -> this.readyTerm = term);
    }

    /** Reads the stamp file: resume after the last batch wholly in the ledger; drops stamps past it. */
    private void recover(long ledgerEnd) throws IOException {
        long size = this.stamps.size() / STAMP * STAMP;
        if (size == 0) { // header: the ledger end when replication started, which must be 0
            if (ledgerEnd != 0) {
                throw refused("already holds " + ledgerEnd + " local record(s) (offsets 0.." + (ledgerEnd - 1)
                        + ") from before replication, but replication starts every node at offset 0; start a"
                        + " replicated topic from an empty log on every data node (see \"Migrating to HA"
                        + " messaging\" in high-availability.md)");
            }
            this.stamps.truncate(0);
            writeStamp(0, 0, ledgerEnd, 0);
            this.stamps.force(false);
            this.expected = ledgerEnd;
            return;
        }
        if (size >= 2L * STAMP) { // a clean close left a marker: the ledger must not have moved since
            var last = ByteBuffer.allocate(STAMP);
            while (last.hasRemaining()) if (this.stamps.read(last, size - STAMP + last.position()) < 0) break;
            last.flip();
            if (last.getLong() == CLEAN) {
                last.getLong();
                long cleanEnd = last.getLong();
                if (ledgerEnd > cleanEnd) {
                    throw refused("ledger end is " + ledgerEnd + " but replication stopped cleanly at " + cleanEnd
                            + ": " + (ledgerEnd - cleanEnd) + " record(s) were written while replication was off,"
                            + " and the other nodes do not have them; wipe this topic on every data node (or re-seed"
                            + " it) before turning replication back on (see \"Rollback\" in high-availability.md)");
                }
                size -= STAMP; // dropped below with the rest of the tail
            }
        }
        var buf = ByteBuffer.allocate((int) Math.min(size, (long) STAMP << 15));
        long pos = 0, keep = STAMP;
        boolean first = true;
        while (pos < size) {
            buf.clear().limit((int) Math.min(buf.capacity(), size - pos));
            while (buf.hasRemaining()) if (this.stamps.read(buf, pos + buf.position()) < 0) break;
            buf.flip();
            while (buf.remaining() >= STAMP) {
                long index = buf.getLong(), term = buf.getLong(), base = buf.getLong();
                int count = buf.getInt();
                if (first) {
                    if (ledgerEnd < base) {
                        throw refused("ledger end is " + ledgerEnd + " but replication started at offset " + base
                                + ": the ledger was wiped or truncated while its replication state was kept; wipe"
                                + " both, or neither");
                    }
                    this.expected = base;
                    first = false;
                } else if (index == CLEAN) {
                    break; // a marker before the end (should not happen): stop, keep what precedes it
                } else if (base + count <= ledgerEnd) {
                    this.resumeIndex = index;
                    this.expected = base + count;
                    keep = pos + buf.position();
                }
            }
            pos += buf.limit();
        }
        this.stamps.truncate(keep);
        this.stamps.force(false); // the dropped marker must not come back after a crash
    }

    private IllegalArgumentException refused(String why) {
        var what = "__offsets".equals(this.topic) ? "consumer offsets (__offsets, replicated with the topics)"
                : "replicated topic " + this.topic;
        return new IllegalArgumentException("ha messaging: " + what + " " + why + "; refusing to start");
    }

    private void writeStamp(long index, long term, long base, int count) throws IOException {
        var b = ByteBuffer.allocate(STAMP).putLong(index).putLong(term).putLong(base).putInt(count).flip();
        long at = this.stamps.size();
        while (b.hasRemaining()) at += this.stamps.write(b, at);
    }

    static ByteBuffer encode(List<byte[]> records, Durability mode, long term, int origin, long seq) {
        int size = HEADER;
        for (var r : records) size += 4 + r.length;
        var b = ByteBuffer.allocate(size).put(MAGIC).put((byte) mode.ordinal()).putLong(term).putInt(origin).putLong(seq)
                .putInt(records.size());
        for (var r : records) b.putInt(r.length).put(r);
        return b.flip();
    }

    private void onCommitted(CommittedEntry e) {
        this.applyLock.lock();
        try {
            if (this.closed) return; // close() recorded the ledger end; the entry is replayed on restart
            apply(e);
        } finally {
            this.applyLock.unlock();
        }
    }

    private void apply(CommittedEntry e) {
        if (e.index() <= this.resumeIndex) return; // already in the ledger (restart replay)
        var b = e.entry();
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
            if (base + count <= this.skipTo) { // in the ledger already: a crash lost the stamp, not the records
                this.appliedIndex = e.index();
                if (mine != null) mine.complete(base);
                return;
            }
            var f = this.local.append(records, mode);
            this.lastApply = f;
            long index = e.index();
            f.whenComplete((at, err) -> {
                if (err == null) {
                    this.appliedIndex = index;
                    this.onApplied.accept(records);
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

    @Override
    public CompletableFuture<Long> append(List<byte[]> records, Durability mode) {
        return appendNoWait(records, mode);
    }

    @Override
    public CompletableFuture<Long> appendNoWait(List<byte[]> records, Durability mode) {
        var m = mode == null ? this.defaultMode : mode;
        long term = this.group.leaderTerm();
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
        this.node.openGroup(this.groupId, RaftReplicator.options(java.time.Duration.ofSeconds(1), java.time.Duration.ofMillis(100)), null);
        // Proposed and maybe in the log: the entry may still commit and be applied (on restart, or by the other
        // replicas), so a resend could duplicate it; not "not leader" (#254).
        var closed = outcomeUnknown(this.groupId.value() + ": closed before the append was acknowledged", leaderName(), null);
        this.pending.values().forEach(p -> p.completeExceptionally(closed));
        this.pending.clear();
        CompletableFuture<Long> inFlight;
        long end;
        this.applyLock.lock();
        try {
            this.closed = true;
            inFlight = this.lastApply;
            end = this.expected;
        } finally {
            this.applyLock.unlock();
        }
        try {
            inFlight.get(5, java.util.concurrent.TimeUnit.SECONDS);
            if (this.local.snapshot().nextOffset() == end) { // every stamped batch is in the ledger: a clean end
                writeStamp(CLEAN, CLEAN, end, 0);
                this.stamps.force(false);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            // no marker: the next open recovers as after a crash
        }
        try {
            this.stamps.close();
        } catch (IOException e) {
            System.err.println("WARN: closing " + this.groupId.value() + " stamps: " + e);
        }
    }
}
