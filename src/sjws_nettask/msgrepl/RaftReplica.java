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
import sjws_nettask.messaging.ledger.ProducerAppend;
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
 *
 * <p><b>Producer dedup (M2).</b> A batch sent with a producer id is entry {@code [P]...}: the same header as {@code [M]},
 * then {@code [producerOrigin:int][producerId:long][producerSeq:long]}, then the records (a batch without one stays a
 * byte-identical {@code [M]} entry). The {@link ProducerTable} decides each {@code P} entry when it is applied, on every
 * node: the next seq is appended, a remembered retry is acknowledged with the original offset, anything else is refused
 * with {@code ProducerSequenceException}; refused and duplicate entries take no offsets. Replay after a restart runs the
 * same decisions from the first entry, including entries already in the ledger, so the table is rebuilt exactly. A
 * duplicate is matched on the producer, the seq, the record count and a CRC32C of the encoded records, computed from
 * the committed entry on every node.
 *
 * <p><b>Mixed versions (#278 HA B1).</b> Builds before M2 skip any entry that is not {@code M}, so a {@code P} entry
 * would append on M2 nodes only and the ledgers would diverge with no error. Every msg group therefore advertises
 * {@link #FEATURE_PRODUCER_ENTRIES} in its append responses, and the leader proposes a {@code P} entry only while every
 * voter of the current configuration has advertised it to this leader (a voter it has not heard from since it started
 * counts as an older build); otherwise the produce is refused before anything is proposed. The check gates the first
 * producer entry: once one has committed (this node applied it, so its producer table exists), later ones are proposed
 * without it, which keeps dedup available right after a failover, when the new leader may not have heard from the old
 * one yet. From this build on, an
 * entry this node cannot apply exactly as its peers do (an unknown magic, a malformed entry, a replay that disagrees
 * with the stamp file) fails the replica closed instead of being skipped. Downgrading a node below M2, or adding a
 * voter that runs an older build, is unsupported once any {@code P} entry has committed: the older build would skip it.
 */
final class RaftReplica implements Replica {

    private static final byte MAGIC = 'M';
    /** A batch with a producer id (M2): {@link #MAGIC}'s layout plus {@link #PRODUCER} bytes before the records. */
    private static final byte MAGIC_PRODUCER = 'P';
    private static final int HEADER = 1 + 1 + 8 + 4 + 8 + 4;
    private static final int PRODUCER = 4 + 8 + 8;
    private static final int STAMP = 28;
    /** Stamp index of the clean-close marker. */
    private static final long CLEAN = -1;
    private static final Durability[] MODES = Durability.values();
    /**
     * Feature bit this build advertises on every msg group (#278 HA B1): it applies {@link #MAGIC_PRODUCER} entries and
     * fails closed on any entry it cannot apply. The leader proposes a producer entry only when every voter has it,
     * because an older build skips entries it does not know and its ledger would silently diverge.
     */
    static final int FEATURE_PRODUCER_ENTRIES = 1;

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
    /** Next offset: from the stamp header's base, advanced by every batch applied, replayed ones included. */
    private long expected;
    /** {@link #expected} once the replay reaches {@link #resumeIndex} (from the stamp file), as a cross-check. */
    private long resumeExpected;
    /**
     * Producer dedup state, created by the first producer entry applied (#278 B4: a topic that never sees one never
     * loads the class); null is the empty table. Replaced only by {@link #restoreProducers}. Written by the delivery
     * thread only; volatile because the producer gate in {@link #appendDeduplicated} reads whether it exists.
     */
    private volatile ProducerTable producers;
    /** Non-null once this replica failed closed (see {@link #fence}); volatile: read by producing threads. */
    private volatile String fenced;
    /** The last local ledger append: a duplicate is acknowledged once the ledger has reached it. */
    private CompletableFuture<Long> lastLocal = CompletableFuture.completedFuture(-1L);
    private final long skipTo;
    private volatile long appliedIndex;
    /** Guards the delivery-thread state against {@link #close}; never contended while open. */
    private final java.util.concurrent.locks.ReentrantLock applyLock = new java.util.concurrent.locks.ReentrantLock();
    private boolean closed;
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
        this.group.advertiseFeatures(FEATURE_PRODUCER_ENTRIES);
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
            this.resumeExpected = ledgerEnd;
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
                    this.expected = base; // replay recomputes every offset from the header's base
                    this.resumeExpected = base;
                    first = false;
                } else if (index == CLEAN) {
                    break; // a marker before the end (should not happen): stop, keep what precedes it
                } else if (base + count <= ledgerEnd) {
                    this.resumeIndex = index;
                    this.resumeExpected = base + count;
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

    /** A {@link #MAGIC_PRODUCER} entry: batch {@code producerSeq} of {@code (producerOrigin, producerId)}. */
    static ByteBuffer encode(List<byte[]> records, Durability mode, long term, int origin, long seq, int producerOrigin,
            long producerId, long producerSeq) {
        int size = HEADER + PRODUCER;
        for (var r : records) size += 4 + r.length;
        var b = ByteBuffer.allocate(size).put(MAGIC_PRODUCER).put((byte) mode.ordinal()).putLong(term).putInt(origin)
                .putLong(seq).putInt(records.size()).putInt(producerOrigin).putLong(producerId).putLong(producerSeq);
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
        var b = e.entry();
        if (this.fenced != null) { // failed closed: applies nothing more; its own batches can no longer be acked here
            failMine(b);
            return;
        }
        if (b.remaining() < HEADER) {
            fence(e, "entry of " + b.remaining() + " bytes is shorter than the header", null);
            return;
        }
        byte magic = b.get();
        if (magic != MAGIC && magic != MAGIC_PRODUCER) { // a newer entry format: never skip it silently (#278 HA B1)
            fence(e, "unknown entry magic 0x" + Integer.toHexString(magic & 0xff), null);
            return;
        }
        boolean replayed = e.index() <= this.resumeIndex; // already in the ledger: only rebuild the state (restart replay)
        int modeOrdinal = b.get();
        long stamp = b.getLong();
        int origin = b.getInt();
        long seq = b.getLong();
        int count = b.getInt();
        var mine = !replayed && origin == this.self ? this.pending.remove(seq) : null;
        if (modeOrdinal < 0 || modeOrdinal >= MODES.length) {
            fence(e, "unknown durability " + modeOrdinal, mine);
            return;
        }
        var mode = MODES[modeOrdinal];
        if (stamp != e.term()) { // proposed in an older term, committed by a newer leader: fenced on every node
            if (mine != null) mine.completeExceptionally(unavailable("stale term " + stamp + " < " + e.term()));
            return;
        }
        int pOrigin = 0;
        long producerId = 0, producerSeq = 0;
        int digest = 0;
        if (magic == MAGIC_PRODUCER) {
            if (b.remaining() < PRODUCER) {
                fence(e, "producer entry of " + (b.remaining() + HEADER) + " bytes is shorter than its header", mine);
                return;
            }
            pOrigin = b.getInt();
            producerId = b.getLong();
            producerSeq = b.getLong();
            if (producerId == 0 || producerSeq < 1) {
                fence(e, "producer entry with id " + producerId + " seq " + producerSeq, mine);
                return;
            }
        }
        if (!wellFormed(b, count)) {
            fence(e, "records region does not hold " + count + " records", mine);
            return;
        }
        if (magic == MAGIC_PRODUCER) {
            var crc = new java.util.zip.CRC32C();
            crc.update(b.duplicate()); // the encoded records region: identical bytes, identical digest on every node
            digest = (int) crc.getValue();
            if (this.producers == null) this.producers = new ProducerTable(); // lazy: null is the empty table
            long c = this.producers.check(pOrigin, producerId, producerSeq, count, digest);
            if (c != ProducerTable.NEW) { // decided identically on every node: takes no offsets
                if (mine != null) answerProducer(mine, c, pOrigin, producerId, producerSeq);
                return;
            }
        }
        long base = this.expected;
        this.expected += count;
        if (producerId != 0) this.producers.apply(pOrigin, producerId, producerSeq, base, count, digest);
        if (replayed) {
            if (e.index() == this.resumeIndex && this.expected != this.resumeExpected) {
                // The replay did not reproduce the offsets this node wrote: the producer table built on the way holds
                // the same wrong bases, so dedup acks would disagree with the ledger and the peers (#278 HA B3).
                fence(e, "replay reached offset " + this.expected + " at index " + e.index()
                        + ", the stamp file says " + this.resumeExpected, null);
            }
            return;
        }
        var records = new ArrayList<byte[]>(count);
        for (int i = 0; i < count; i++) {
            var r = new byte[b.getInt()];
            b.get(r);
            records.add(r);
        }
        try {
            writeStamp(e.index(), e.term(), base, count);
            if (base + count <= this.skipTo) { // in the ledger already: a crash lost the stamp, not the records
                this.appliedIndex = e.index();
                if (mine != null) mine.complete(base);
                return;
            }
            var f = this.local.append(records, mode);
            this.lastLocal = f;
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

    /** The records region holds exactly {@code count} length-prefixed records, nothing short and nothing after. */
    private static boolean wellFormed(ByteBuffer b, int count) {
        if (count < 0) return false;
        int at = b.position(), end = b.limit();
        for (int i = 0; i < count; i++) {
            if (end - at < 4) return false;
            int len = b.getInt(at);
            at += 4;
            if (len < 0 || len > end - at) return false;
            at += len;
        }
        return at == end;
    }

    /**
     * Fails closed (#278 HA B1, B3): an entry this build cannot apply exactly as its peers do (an unknown format, a
     * malformed entry, a replay that disagrees with the stamp file). The group applies nothing more on this node,
     * every batch of this node still waiting for its entry fails, and later appends are refused, rather than letting
     * this ledger silently diverge from the others. An operator has to repair or replace the node (restart it with a
     * build that understands the entry, or rebuild its state from a healthy peer).
     */
    private void fence(CommittedEntry e, String why, CompletableFuture<Long> mine) {
        var reason = this.groupId.value() + ": failed closed at index " + e.index() + ": " + why;
        this.fenced = reason;
        System.err.println("ERROR: " + reason + "; this replica applies nothing more until it is repaired");
        var err = outcomeUnknown(reason, leaderName(), null);
        if (mine != null) mine.completeExceptionally(err);
        this.pending.values().forEach(p -> p.completeExceptionally(err));
        this.pending.clear();
    }

    /** After a fence: this node's own batch committed but will not be applied here; its outcome is unknown. */
    private void failMine(ByteBuffer b) {
        if (b.remaining() < HEADER) return;
        int origin = b.getInt(b.position() + 10);
        long seq = b.getLong(b.position() + 14);
        var mine = origin == this.self ? this.pending.remove(seq) : null;
        if (mine != null) mine.completeExceptionally(outcomeUnknown(this.fenced, leaderName(), null));
    }

    /** Non-null once this replica failed closed: why. */
    String fenced() {
        return this.fenced;
    }

    /**
     * This node's own producer batch that the table did not append: a duplicate completes with the original base
     * offset (encoded {@code -1 - base}, see {@link #appendDeduplicated}) once the local ledger has caught up to the
     * last append, since it holds the original then; else a refusal.
     *
     * <p>{@link #lastLocal} is the latest append, which may be a later, unrelated batch. If that append fails, the
     * duplicate's ack fails with it, and the client sees outcome unknown for a batch that is in the log. That is safe:
     * its retry is a duplicate again and is acknowledged with the original offset once the ledger recovers (#278 HA N4).
     */
    private void answerProducer(CompletableFuture<Long> mine, long c, int pOrigin, long producerId, long producerSeq) {
        if (c >= 0) {
            this.lastLocal.whenComplete((at, err) -> {
                if (err == null) mine.complete(-1 - c);
                else mine.completeExceptionally(err);
            });
            return;
        }
        long next = this.producers.expectedSeq(pOrigin, producerId);
        mine.completeExceptionally(ProducerTable.refusal(c, next, this.groupId.value() + ": producer " + producerId
                + " seq " + producerSeq));
    }

    @Override
    public CompletableFuture<Long> append(List<byte[]> records, Durability mode) {
        return appendNoWait(records, mode);
    }

    @Override
    public CompletableFuture<Long> appendNoWait(List<byte[]> records, Durability mode) {
        return propose(records, mode, 0, 0, 0);
    }

    @Override
    public CompletableFuture<ProducerAppend> appendDeduplicated(List<byte[]> records, Durability mode, int producerOrigin,
            long producerId, long producerSeq) {
        if (producerId != 0 && (producerSeq < 1 || producerSeq > ProducerTable.MAX_SEQ)) {
            throw new IllegalArgumentException("producer seq must be 1 to " + ProducerTable.MAX_SEQ);
        }
        if (producerId != 0 && this.producers == null && !this.group.votersHaveFeatures(FEATURE_PRODUCER_ENTRIES)) {
            // An older voter would skip the entry and diverge; nothing proposed (#278 HA B1). Once a producer entry
            // has committed and been applied here, the group is past the point of no return (older builds and
            // downgrades are unsupported from then on), so a leader that has not heard from a peer since it started,
            // e.g. right after a failover, can still take producer retries.
            return CompletableFuture.failedFuture(ProducerTable.dedupUnavailable(this.groupId.value()
                    + ": not every voter has confirmed it applies producer entries; nothing appended"));
        }
        // id 0: no dedup, at-least-once. A duplicate completes the proposal with -1 - base (answerProducer).
        return propose(records, mode, producerOrigin, producerId, producerSeq)
                .thenApply(v -> v < 0 ? new ProducerAppend(-1 - v, true) : new ProducerAppend(v, false));
    }

    private CompletableFuture<Long> propose(List<byte[]> records, Durability mode, int producerOrigin, long producerId,
            long producerSeq) {
        var m = mode == null ? this.defaultMode : mode;
        var down = this.fenced;
        if (down != null) {
            return CompletableFuture.failedFuture(new WriteRefusedException(down + "; not appended", false, null));
        }
        long term = this.group.leaderTerm();
        if (!leaderReady(term)) return CompletableFuture.failedFuture(unavailable("not the ready leader"));
        long seq = this.seqs.incrementAndGet();
        var acked = new CompletableFuture<Long>();
        this.pending.put(seq, acked);
        var cd = m == Durability.GROUP || m == Durability.EACH ? sjws_nettask.cluster.Durability.STRICT : null;
        var entry = producerId == 0 ? encode(records, m, term, this.self, seq)
                : encode(records, m, term, this.self, seq, producerOrigin, producerId, producerSeq);
        // Conditional on the term just checked: a step-down and re-election between the check and the
        // log write fails the append instead of proposing under the old term's readiness.
        this.node.append(this.groupId, List.of(entry), cd, term).whenComplete((ci, err) -> {
            if (err != null) {
                var p = this.pending.remove(seq);
                if (p != null) p.completeExceptionally(map(err, true));
            }
        });
        return acked;
    }

    /** The producer table, null while empty: owned by the delivery thread; for tests and the #270 snapshot seam. */
    ProducerTable producers() {
        return this.producers;
    }

    /**
     * #270 install hook, delivery thread only: replaces the producer table with {@code table} as read from a snapshot
     * ({@link ProducerTable#read}). A snapshot at index {@code i} must carry the producer table and the next offset
     * ({@link #expected}) as applied through {@code i}, and restore both together before applying {@code i + 1}: once
     * the consensus log is truncated, replay can no longer recompute either from the stamp header's base.
     */
    void restoreProducers(ProducerTable table) { // null: the snapshot predates any producer entry (keeps the gate)
        this.producers = table;
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
        var closedOutcome = outcomeUnknown(this.groupId.value() + ": closed before the append was acknowledged", leaderName(), null);
        this.pending.values().forEach(p -> p.completeExceptionally(closedOutcome));
        this.pending.clear();
        CompletableFuture<Long> inFlight;
        long end;
        this.applyLock.lock();
        try {
            this.closed = true;
            inFlight = this.lastLocal;
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
