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

import sjws_nettask.cluster.ClusterNode;
import sjws_nettask.cluster.CommittedEntry;
import sjws_nettask.cluster.GroupId;
import sjws_nettask.cluster.GroupOptions;
import sjws_nettask.cluster.NodeId;
import sjws_nettask.cluster.NotLeaderException;
import sjws_nettask.cluster.ReplicatedNode;
import sjws_nettask.cluster.ReplicationGroup;
import sjws_nettask.messaging.ledger.Durability;
import sjws_nettask.messaging.ledger.LeaderUnavailableException;
import sjws_nettask.messaging.ledger.LedgerWriter;
import sjws_nettask.messaging.ledger.Replica;

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
 * are already in the ledger, so replay never duplicates records.
 */
final class RaftReplica implements Replica {

    private static final byte MAGIC = 'M';
    private static final int HEADER = 1 + 1 + 8 + 4 + 8 + 4;
    private static final int STAMP = 28;

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
    private final AtomicLong seqs = new AtomicLong();
    private final ConcurrentHashMap<Long, CompletableFuture<Long>> pending = new ConcurrentHashMap<>();
    /** Term whose election no-op this node applied as leader; -1 before. */
    private volatile long readyTerm = -1;
    // Delivery-thread state (one delivery thread per group).
    private long resumeIndex;
    private long expected;
    private final long skipTo;
    private volatile long appliedIndex;

    RaftReplica(ClusterNode node, GroupId groupId, GroupOptions options, LedgerWriter local, Durability defaultMode,
            Consumer<List<byte[]>> onApplied, Map<String, String> urls, Path stampFile, java.util.function.LongSupplier nanoClock) throws IOException {
        this.nanoClock = nanoClock;
        this.node = node;
        this.groupId = groupId;
        this.local = local;
        this.defaultMode = defaultMode;
        this.onApplied = onApplied;
        this.urls = urls;
        this.self = node.self().wire();
        this.stamps = FileChannel.open(stampFile, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        long ledgerEnd = local.snapshot().nextOffset();
        this.skipTo = ledgerEnd;
        recover(ledgerEnd);
        this.group = node.openGroup(groupId, options, this::onCommitted);
        if (node instanceof ReplicatedNode rn) {
            rn.onNoopApplied(groupId, (term, index) -> this.readyTerm = term);
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
        boolean first = true;
        while (pos < size) {
            buf.clear().limit((int) Math.min(buf.capacity(), size - pos));
            while (buf.hasRemaining()) if (this.stamps.read(buf, pos + buf.position()) < 0) break;
            buf.flip();
            while (buf.remaining() >= STAMP) {
                long index = buf.getLong(), term = buf.getLong(), base = buf.getLong();
                int count = buf.getInt();
                if (first) {
                    this.expected = base;
                    first = false;
                } else if (base + count <= ledgerEnd) {
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
        long term = this.group.term();
        if (!leaderReady(term)) return CompletableFuture.failedFuture(unavailable("not the ready leader"));
        long seq = this.seqs.incrementAndGet();
        var acked = new CompletableFuture<Long>();
        this.pending.put(seq, acked);
        var cd = m == Durability.GROUP || m == Durability.EACH ? sjws_nettask.cluster.Durability.STRICT : null;
        this.node.append(this.groupId, List.of(encode(records, m, term, this.self, seq)), cd).whenComplete((ci, err) -> {
            if (err != null) {
                var p = this.pending.remove(seq);
                if (p != null) p.completeExceptionally(map(err));
            }
        });
        return acked;
    }

    @Override
    public void awaitCapacity() throws InterruptedException {
        this.local.awaitCapacity();
    }

    private boolean leaderReady(long term) {
        // Ready = this term's no-op applied here, still leader, and inside its lease (an isolated old leader stops acking).
        return this.readyTerm == term && this.group.isLeader() && this.group.leaseExpiresAt() > this.nanoClock.getAsLong();
    }

    @Override
    public boolean leaderReady() {
        return leaderReady(this.group.term());
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
            if (err != null) throw new CompletionException(map(err));
            return null;
        });
    }

    private LeaderUnavailableException unavailable(String why) {
        return new LeaderUnavailableException(this.groupId.value() + ": " + why, term(), leaderName(), leaderUrl());
    }

    private Throwable map(Throwable err) {
        var c = err instanceof CompletionException && err.getCause() != null ? err.getCause() : err;
        return c instanceof NotLeaderException ? unavailable("not leader") : c;
    }

    @Override
    public void close() {
        this.node.openGroup(this.groupId, RaftReplicator.options(java.time.Duration.ofSeconds(1), java.time.Duration.ofMillis(100)), null);
        var closed = unavailable("closed");
        this.pending.values().forEach(p -> p.completeExceptionally(closed));
        this.pending.clear();
        try {
            this.stamps.close();
        } catch (IOException e) {
            System.err.println("WARN: closing " + this.groupId.value() + " stamps: " + e);
        }
    }
}
