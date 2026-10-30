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
import sjws_nettask.messaging.ledger.ProducerAppend;
import sjws_nettask.messaging.ledger.ReplicaHooks;
import sjws_nettask.messaging.ledger.WriteRefusedException;
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
 * record with count -1 is a replicated retention decision: {@code base} is the new log start. A clean {@link #close}
 * appends a marker ({@code index = -1}, {@code baseOffset} = the ledger end) once every apply finished.
 *
 * <p>Offsets are assigned from the ledger end, so every node's ledger must hold exactly the replicated records. The
 * open refuses ({@link IllegalArgumentException}, exit 64 at startup, naming the topic and both numbers) a ledger
 * that does not line up: the first replicated open of a non-empty ledger (records from before replication), a ledger
 * that moved past the end a clean close recorded (local writes while replication was off), and a ledger shorter
 * than where replication started. A ledger past the stamps after a crash (stamps not on disk yet) is still recovered
 * by replay, as before.
 *
 * <p><b>Snapshots (#270)</b>, with a {@link Snapshots.Runtime}. Off the produce path, the replicator's scheduler checks
 * each replica: once {@code everyEntries} entries were applied since the last snapshot, the latest applied entry is the
 * point. The ledger is fsynced through it (constraint 1), its segment files are pinned ({@link SnapshotStore}), and the
 * {@link MsgSnapshot} manifest is recorded as the group's snapshot ({@code recordSnapshot}); the consensus log is then
 * truncated up to {@code tailEntries} entries behind it (constraint 5), and the stamp file is cut to start at the point.
 * A follower the leader can no longer serve from the log receives the manifest as a consensus snapshot and, on its
 * delivery thread, fetches the segment data from a pin on any data node ({@link SnapshotTransfer}), swaps it in for
 * its whole ledger, restores the application state, and continues from the installed point. An install marker
 * ({@code <topic>.install}) makes a crash anywhere in that sequence redo it on restart. After a successful fetch the
 * image must match the consensus manifest {@code m} (index/term/endOffset/logStart/sections/per-chunk digests);
 * anything else is refused and the next peer is tried.
 *
 * <p>Entry {@code [R][stampTerm][origin][seq][offset]}: "advance the log start to {@code offset}", proposed by the
 * leader's retention pass and applied on every replica (constraint 3).
 */
final class RaftReplica implements Replica {

    private static final byte MAGIC = 'M';
    /** A batch with a producer id (M2): {@link #MAGIC}'s layout plus {@link #PRODUCER} bytes before the records. */
    private static final byte MAGIC_PRODUCER = 'P';
    private static final byte RETAIN = 'R';
    private static final int HEADER = 1 + 1 + 8 + 4 + 8 + 4;
    private static final int PRODUCER = 4 + 8 + 8;
    private static final int RETAIN_BYTES = 1 + 8 + 4 + 8 + 8;
    private static final Durability[] MODES = Durability.values();
    /**
     * Feature bit this build advertises on every msg group (#278 HA B1): it applies {@link #MAGIC_PRODUCER} entries and
     * fails closed on any entry it cannot apply. The leader proposes a producer entry only when every voter has it.
     */
    static final int FEATURE_PRODUCER_ENTRIES = 1;
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
    /**
     * Restart with a {@link ReplicaHooks.ReplicatedState}: its image was restored at this index, so entries after it
     * that are already in the ledger are fed to {@code onApplied} again (not to the ledger). MAX_VALUE: none.
     */
    private long stateFrom = Long.MAX_VALUE;
    /**
     * Snapshot state sections, one per {@link ReplicaHooks.ReplicatedState} (the hooks' own, plus any registered with
     * {@link #addState} before the group opens: #278's producer table as section 2). Non-empty: applies and the
     * capture share {@link #applyLock}, so every image is the state right after the snapshot index.
     */
    private final List<ReplicaHooks.ReplicatedState> states = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile boolean stateful;
    private final java.util.concurrent.locks.ReentrantLock applyLock = new java.util.concurrent.locks.ReentrantLock();
    /** Set while the group opens when the restored snapshot needs an install; run once the group is open. */
    private MsgSnapshot openInstall;
    /** Coordinates constructor handoff with {@link #onSnapshotRestored} during {@code openGroup} (#288 Codex P1). */
    private final java.util.concurrent.locks.ReentrantLock installHandoff = new java.util.concurrent.locks.ReentrantLock();
    private volatile CompletableFuture<Void> installing;
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
    /** {@link #expected} once the replay reaches {@link #resumeIndex} (from the stamp file), as a cross-check. */
    private long resumeExpected;
    /**
     * Offset cursor while rebuilding the {@link ProducerTable} from entries {@code <= resumeIndex}. Starts at the
     * stamp-header base set in {@link #recover}; duplicate, refused and stale-term entries do not advance it. At
     * {@link #resumeIndex} it is cross-checked against {@link #resumeExpected} and then cleared. Lining up
     * {@link #expected} without a raft replay does not rebuild the table: a snapshot that truncates the log past the
     * stamps must call {@link #restoreProducers} (#288 / #270). {@code -1} means "no stamp-header base"; a replay in
     * that state fails closed.
     */
    private long replayAt = -1;
    /**
     * Producer dedup state, created by the first producer entry applied (#278 B4); null is the empty table.
     * Replaced only by {@link #restoreProducers}.
     */
    private volatile ProducerTable producers;
    /** Non-null once this replica failed closed (see {@link #fence}); volatile: read by producing threads. */
    private volatile String fenced;

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
        if (hooks != null && hooks.state() != null) addState(hooks.state());
        if (snaps != null) {
            // Dedup on + snapshots: section 2 is mandatory; restoreProducers before entry i+1 (#278/#270 seam).
            addState(new ReplicaHooks.ReplicatedState() {
                @Override public int section() { return MsgSnapshot.PRODUCERS; }
                @Override public byte[] snapshotState() {
                    var out = new java.io.ByteArrayOutputStream();
                    try (var d = new java.io.DataOutputStream(out)) {
                        var t = producers;
                        (t != null ? t : new ProducerTable()).write(d);
                    } catch (java.io.IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                    return out.toByteArray();
                }
                @Override public void restoreState(byte[] image) {
                    if (image == null || image.length == 0) {
                        restoreProducers(null);
                        return;
                    }
                    try {
                        restoreProducers(ProducerTable.read(new java.io.DataInputStream(
                                new java.io.ByteArrayInputStream(image))));
                    } catch (java.io.IOException e) {
                        throw new IllegalStateException(groupId.value() + ": PRODUCERS section refused: " + e.getMessage(), e);
                    }
                }
            });
        }
        this.onApplied = hooks != null ? hooks.onApplied() : onApplied;
        this.urls = urls;
        this.snaps = snaps;
        var name = groupId.value();
        this.topic = name.startsWith("msg/") ? name.substring(4) : name;
        this.self = node.self().wire();
        this.stampFile = stampFile;
        this.installMarker = stampFile.resolveSibling(this.topic + ".install");
        this.staging = stampFile.resolveSibling(this.topic + ".staging");
        Files.deleteIfExists(stampFile.resolveSibling(stampFile.getFileName() + ".tmp"));
        this.stamps = FileChannel.open(stampFile, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        this.installPending = Files.exists(this.installMarker);
        long ledgerEnd = local.snapshot().nextOffset();
        this.skipTo = ledgerEnd;
        try {
            recover(ledgerEnd);
        } catch (IOException | RuntimeException e) {
            this.stamps.close();
            throw e;
        }
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
        // openGroup may deliver onSnapshotRestored before returning; defer install via openInstall until
        // group is published under installHandoff so the callback and this handoff cannot disagree (#288 Codex P1).
        var opened = node.openGroup(groupId, options, listener);
        MsgSnapshot owed;
        this.installHandoff.lock();
        try {
            this.group = opened;
            owed = this.openInstall;
            if (owed != null) this.openInstall = null;
        } finally {
            this.installHandoff.unlock();
        }
        this.group.advertiseFeatures(FEATURE_PRODUCER_ENTRIES);
        node.onNoopApplied(groupId, (term, index) -> this.readyTerm = term);
        if (owed != null) {
            var done = new CompletableFuture<Void>();
            this.installing = done;
            Thread.ofVirtual().name("msgrepl-install-" + this.topic).start(() -> {
                try {
                    install(owed);
                } finally {
                    done.complete(null);
                }
            });
        }
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
        if (size == 0) { // header: the ledger end when replication started, which must be 0 (or an install will set it)
            if (ledgerEnd != 0 && !this.installPending) {
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
            this.replayAt = -1;
            return;
        }
        if (size >= 2L * STAMP) { // a clean close left a marker: the ledger must not have moved since
            var last = ByteBuffer.allocate(STAMP);
            while (last.hasRemaining()) if (this.stamps.read(last, size - STAMP + last.position()) < 0) break;
            last.flip();
            if (last.getLong() == CLEAN) {
                last.getLong();
                long cleanEnd = last.getLong();
                if (ledgerEnd > cleanEnd && !this.installPending) {
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
        boolean first = true, gap = false;
        while (pos < size) {
            buf.clear().limit((int) Math.min(buf.capacity(), size - pos));
            while (buf.hasRemaining()) if (this.stamps.read(buf, pos + buf.position()) < 0) break;
            buf.flip();
            while (buf.remaining() >= STAMP) {
                long index = buf.getLong(), term = buf.getLong(), base = buf.getLong();
                int count = buf.getInt();
                if (first) {
                    if (ledgerEnd < base && !this.installPending) {
                        throw refused("ledger end is " + ledgerEnd + " but replication started at offset " + base
                                + ": the ledger was wiped or truncated while its replication state was kept; wipe"
                                + " both, or neither");
                    }
                    this.expected = base;
                    this.resumeIndex = index; // the snapshot point the file starts after (0 before any snapshot)
                    this.resumeExpected = base;
                    this.replayAt = base;
                    first = false;
                } else if (index == CLEAN) {
                    break; // a marker before the end (should not happen): stop, keep what precedes it
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
     * every stamp after {@code index}. The replacement is forced before the rename so a crash cannot leave an empty
     * stamp header over a durable ledger (#288 Codex P1); append-only stamps remain unforced as before.
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
                ch.force(true);
            }
            fault("stamps-written");
            Files.move(tmp, this.stampFile, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            try (var d = FileChannel.open(this.stampFile.getParent(), StandardOpenOption.READ)) {
                d.force(true);
            } catch (IOException ignored) {
                // some file systems refuse a directory fsync; the forced contents still cover the rename target
            }
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

    /** Feeds an entry already in the ledger to {@code onApplied}, after a state image restored at {@link #stateFrom}. */
    private void replayState(CommittedEntry e) {
        var b = e.entry().duplicate();
        if (b.remaining() < HEADER || b.get() != MAGIC) return;
        b.get();
        long stamp = b.getLong();
        b.getInt();
        b.getLong();
        int count = b.getInt();
        if (stamp != e.term() || count < 0) return;
        var records = new ArrayList<byte[]>(count);
        for (int i = 0; i < count && b.remaining() >= 4; i++) {
            int len = b.getInt();
            if (len < 0 || len > b.remaining()) return;
            var r = new byte[len];
            b.get(r);
            records.add(r);
        }
        this.applyLock.lock();
        try {
            this.onApplied.accept(records);
        } finally {
            this.applyLock.unlock();
        }
    }

    static ByteBuffer encodeRetain(long offset, long term, int origin, long seq) {
        return ByteBuffer.allocate(RETAIN_BYTES).put(RETAIN).putLong(term).putInt(origin).putLong(seq).putLong(offset).flip();
    }

    private void onCommitted(CommittedEntry e) {
        if (this.closed) return; // close() recorded the ledger end; the entry is replayed on restart
        if (this.installPending) {
            var f = this.installing;
            if (f != null) f.join(); // the restart install: entries wait for the ledger they apply to
            if (this.installPending) return; // a failed install: nothing applies until a restart redoes it
        }
        this.applyLock.lock();
        try {
            if (this.closed) return;
            apply(e);
        } finally {
            this.applyLock.unlock();
        }
    }

    private void apply(CommittedEntry e) {
        var b = e.entry();
        if (this.fenced != null) {
            failMine(b);
            return;
        }
        if (b.remaining() == RETAIN_BYTES && b.get(b.position()) == RETAIN) {
            onRetain(e, b);
            return;
        }
        if (b.remaining() < HEADER) {
            fence(e, "entry of " + b.remaining() + " bytes is shorter than the header", null);
            return;
        }
        byte magic = b.get();
        if (magic != MAGIC && magic != MAGIC_PRODUCER) {
            fence(e, "unknown entry magic 0x" + Integer.toHexString(magic & 0xff), null);
            return;
        }
        boolean replayed = e.index() <= this.resumeIndex;
        int modeOrdinal = b.get() & 0xff;
        long stamp = b.getLong();
        int origin = b.getInt();
        long seq = b.getLong();
        int count = b.getInt();
        var mine = !replayed && origin == this.self ? this.pending.remove(seq) : null;
        if (modeOrdinal >= MODES.length) {
            fence(e, "unknown durability " + modeOrdinal, mine);
            return;
        }
        var mode = MODES[modeOrdinal];
        if (stamp != e.term()) {
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
            crc.update(b.duplicate());
            digest = (int) crc.getValue();
            if (this.producers == null) this.producers = new ProducerTable();
            long c = this.producers.check(pOrigin, producerId, producerSeq, count, digest);
            if (c != ProducerTable.NEW) {
                if (mine != null) answerProducer(mine, c, pOrigin, producerId, producerSeq);
                return;
            }
        }
        long base;
        if (replayed) {
            if (this.replayAt < 0) {
                fence(e, "replay at index " + e.index() + " with no stamp-header base", null);
                return;
            }
            base = this.replayAt;
            this.replayAt += count;
            if (producerId != 0) this.producers.apply(pOrigin, producerId, producerSeq, base, count, digest);
            if (e.index() > this.stateFrom) {
                // Re-feed APP_STATE after a restored image (offsets); producers already rebuilt above.
                var records = new ArrayList<byte[]>(count);
                var dup = b.duplicate();
                for (int i = 0; i < count; i++) {
                    var r = new byte[dup.getInt()];
                    dup.get(r);
                    records.add(r);
                }
                this.onApplied.accept(records);
            }
            if (e.index() == this.resumeIndex) {
                if (this.replayAt != this.resumeExpected) {
                    fence(e, "replay reached offset " + this.replayAt + " at index " + e.index()
                            + ", the stamp file says " + this.resumeExpected, null);
                }
                this.replayAt = -1;
            }
            return;
        }
        base = this.expected;
        this.expected += count;
        if (producerId != 0) this.producers.apply(pOrigin, producerId, producerSeq, base, count, digest);
        var records = new ArrayList<byte[]>(count);
        for (int i = 0; i < count; i++) {
            var r = new byte[b.getInt()];
            b.get(r);
            records.add(r);
        }
        try {
            writeStamp(e.index(), e.term(), base, count);
            long index = e.index(), term = e.term();
            if (base + count <= this.skipTo) {
                this.appliedIndex = index;
                if (this.stateful) {
                    this.onApplied.accept(records);
                    applied(index, term, base + count);
                } else {
                    applied(index, term, base + count);
                }
                if (mine != null) mine.complete(base);
                return;
            }
            var f = this.local.append(records, mode);
            this.lastLocal = f;
            f.whenComplete((at, err) -> {
                if (err == null) {
                    this.appliedIndex = index;
                    if (this.stateful) {
                        this.applyLock.lock();
                        try {
                            this.onApplied.accept(records);
                            applied(index, term, at + count);
                        } finally {
                            this.applyLock.unlock();
                        }
                    } else {
                        this.onApplied.accept(records);
                        applied(index, term, at + count);
                    }
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

    private void fence(CommittedEntry e, String why, CompletableFuture<Long> mine) {
        var reason = this.groupId.value() + ": failed closed at index " + e.index() + ": " + why;
        this.fenced = reason;
        System.err.println("ERROR: " + reason + "; this replica applies nothing more until it is repaired");
        var err = outcomeUnknown(reason, leaderName(), null);
        if (mine != null) mine.completeExceptionally(err);
        this.pending.values().forEach(p -> p.completeExceptionally(err));
        this.pending.clear();
    }

    private void failMine(ByteBuffer b) {
        if (b.remaining() < HEADER) return;
        byte magic = b.get(b.position());
        if (magic != MAGIC && magic != MAGIC_PRODUCER) return;
        int origin = b.getInt(b.position() + 1 + 1 + 8);
        long seq = b.getLong(b.position() + 1 + 1 + 8 + 4);
        var mine = origin == this.self ? this.pending.remove(seq) : null;
        if (mine != null) mine.completeExceptionally(outcomeUnknown(this.fenced, leaderName(), null));
    }

    String fenced() { return this.fenced; }

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
            // #288 Codex P1: recovery replay must not append another retention stamp after newer batch
            // stamps; recover() would then move resumeIndex backward and re-append those batches.
            if (e.index() > this.resumeIndex) {
                writeStamp(e.index(), e.term(), offset, -1);
            }
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

    Applied lastAppliedForTest() {
        return this.lastApplied;
    }

    boolean installPending() {
        return this.installPending;
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

    /**
     * Takes a snapshot now at the newest applied point (at or past {@code seen}; also tests). With a replicated state,
     * the point and the state image are read together under the apply lock, so the image is the state right after
     * the point's batch, never one with later batches in it (#278's constraint).
     */
    boolean snapshotAt(Applied seen) throws IOException {
        Applied a;
        List<MsgSnapshot.Section> sections = List.of();
        if (this.stateful) {
            this.applyLock.lock();
            try {
                a = this.lastApplied;
                if (a == null || a.index() <= this.recordedIndex) return false;
                var out = new ArrayList<MsgSnapshot.Section>(this.states.size());
                for (var st : this.states) {
                    int flags = st.section() == MsgSnapshot.PRODUCERS ? MsgSnapshot.REQUIRED : 0;
                    out.add(new MsgSnapshot.Section(st.section(), flags, st.snapshotState()));
                }
                sections = out;
            } finally {
                this.applyLock.unlock();
            }
        } else {
            a = this.lastApplied;
            if (a == null) a = seen;
        }
        if (a.index() <= this.recordedIndex) return false; // nothing new since the last one (or the one restored)
        // Constraint 1: the snapshot point must be durable in the segments, not only applied.
        if (this.local.snapshot().durableOffset() < a.end()) {
            this.local.force(); // advances durableOffset with the fsync (#288 MC-N1)
            if (this.log.nextOffset() < a.end()) return false; // not written yet (cannot happen: a is published after the write)
        }
        var m = this.snaps.store.pin(this.topic, this.log, a.index(), a.term(), a.end(), this.logStart, sections,
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
            this.installHandoff.lock();
            try {
                if (this.group == null) { // restored while the group opens (restart): installed off the opening thread
                    this.installPending = true;
                    this.openInstall = m;
                    return;
                }
            } finally {
                this.installHandoff.unlock();
            }
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
        // #288 Codex P1: batches may land after capture and before resetStamps(); recover() then keeps
        // the stamp-header replay cursor while Raft restores the newer durable snapshot. Rebase the
        // cursor to the restored end; keep the stamp-derived resume watermark for the cross-check.
        if (this.replayAt >= 0 && this.replayAt < m.endOffset()) {
            this.replayAt = m.endOffset();
        }
        this.logStart = Math.max(this.logStart, m.logStart());
        this.recordedIndex = Math.max(this.recordedIndex, m.index());
        this.appliedIndex = Math.max(this.appliedIndex, m.index());
        applied(m.index(), m.term(), m.endOffset());
        if (this.stateful) {
            restoreState(m);
            this.stateFrom = m.index();
        }
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
            boolean fetched = false;
            long backoff = 20;
            long maxBytes = this.snaps.settings.maxInstallBytes();
            while (!fetched && !this.closed) {
                for (var peer : peerOrder()) {
                    try {
                        // Fetch the pin, then refuse unless it is exactly the consensus image m (#288 Security B1).
                        var got = SnapshotTransfer.fetch(peer, this.snaps.key, this.topic, m.index(), this.staging,
                                GroupOptions.DEFAULT_MAX_SNAPSHOT_BYTES, this.snaps.clockMs);
                        if (!sameSnapshotImage(got, m)) {
                            warn(this.groupId.value() + ": install from " + peer + ": pin at " + got.index()
                                    + " does not match the consensus snapshot at " + m.index() + "; trying the next peer");
                            clearStaging();
                            continue;
                        }
                        if (maxBytes > 0) {
                            long total = 0;
                            for (var seg : m.segments()) total += seg.bytes();
                            if (total > maxBytes) {
                                throw new IOException("install of " + total + " bytes exceeds ha.snapshot.max_install_bytes "
                                        + maxBytes);
                            }
                        }
                        fetched = true;
                        break;
                    } catch (IOException e) {
                        clearStaging();
                        warn(this.groupId.value() + ": install from " + peer + ": " + e.getMessage());
                    }
                }
                if (!fetched) {
                    Thread.sleep(backoff);
                    backoff = Math.min(2_000, backoff * 2);
                }
            }
            if (!fetched) return;
            fault("install-fetched");
            try {
                this.lastLocal.get(30, java.util.concurrent.TimeUnit.SECONDS); // the writer is drained: nothing in flight
            } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException ignored) {
                // a failed append left nothing in flight either
            }
            // Pause the ledger syncer before replaceWith closes segment channels; an overlapping force would
            // otherwise become a permanent ClosedChannelException writer failure (#288 Codex P1).
            this.local.pauseSync();
            try {
                // Local binding so lock/unlock are visibly the same Lock (CodeQL java/unreleased-lock).
                var readers = this.hooks.readersOut();
                readers.lock();
                try {
                    this.log.replaceWith(this.staging);
                    this.local.reload();
                } finally {
                    readers.unlock();
                }
            } finally {
                this.local.resumeSync();
            }
            fault("install-swapped");
            // Always restore the consensus image m, never a peer-substituted pin (#288 Security B1).
            this.logStart = m.logStart();
            this.resumeIndex = m.index();
            this.expected = m.endOffset();
            this.skipTo = m.endOffset();
            this.recordedIndex = m.index();
            this.appliedIndex = Math.max(this.appliedIndex, m.index());
            this.lastApplied = new Applied(m.index(), m.term(), m.endOffset());
            resetStamps(m.index(), m.term(), m.endOffset(), Long.MAX_VALUE);
            if (this.hooks != null && m.logStart() > 0) {
                this.hooks.dropBelow().accept(m.logStart()); // constraint 3 / #288 MC-B1: same as the restart path
            }
            if (this.stateful) restoreState(m);
            this.hooks.reloaded().run();
            // Publish the verified install as a transferable pin before clearing staging, so this node can
            // seed peers after failover while recordedIndex blocks a fresh capture (#288 Codex P1).
            if (this.snaps != null) {
                var published = this.snaps.store.pinInstalled(m, this.staging);
                if (published == null) {
                    warn(this.groupId.value() + ": installed snapshot at " + m.index()
                            + " could not be published as a pin; peers may need another source");
                }
            }
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

    /**
     * True when {@code got} is the same consensus image as {@code m}: same index/term/endOffset/logStart/chunkBytes,
     * same typed sections (id, flags, bytes), and the same per-segment digests. A peer pin at a higher index, or with
     * any divergent section or chunk, is refused (#288 Security B1).
     */
    static boolean sameSnapshotImage(MsgSnapshot got, MsgSnapshot m) {
        if (got.index() != m.index() || got.term() != m.term() || got.endOffset() != m.endOffset()
                || got.logStart() != m.logStart() || got.chunkBytes() != m.chunkBytes()) {
            return false;
        }
        if (!got.topic().equals(m.topic())) return false;
        if (got.sections().size() != m.sections().size()) return false;
        for (int i = 0; i < m.sections().size(); i++) {
            var a = got.sections().get(i);
            var b = m.sections().get(i);
            if (a.id() != b.id() || a.flags() != b.flags() || !java.util.Arrays.equals(a.data(), b.data())) return false;
        }
        if (got.segments().size() != m.segments().size()) return false;
        for (int i = 0; i < m.segments().size(); i++) {
            var a = got.segments().get(i);
            var b = m.segments().get(i);
            if (a.base() != b.base() || a.next() != b.next() || a.bytes() != b.bytes() || a.markerNext() != b.markerNext()) {
                return false;
            }
            if (a.digests().length != b.digests().length) return false;
            for (int d = 0; d < a.digests().length; d++) {
                if (!java.util.Arrays.equals(a.digests()[d], b.digests()[d])) return false;
            }
        }
        return true;
    }

    /** Drops staging contents after a refused or failed fetch so the next peer starts clean. */
    private void clearStaging() {
        try {
            if (!Files.isDirectory(this.staging)) return;
            try (var stream = Files.list(this.staging)) {
                stream.forEach(f -> {
                    try {
                        Files.deleteIfExists(f);
                    } catch (IOException ignored) {
                    }
                });
            }
        } catch (IOException ignored) {
        }
    }

    /**
     * Restores each registered state's section of {@code m} (empty when the snapshot has none). Refuses a non-empty
     * {@link MsgSnapshot#PRODUCERS} section when no handler is registered for it (#288 MC-N2 / #278 seam).
     */
    /** Tests only: exercise {@link #restoreState} fail-closed paths. */
    void restoreStateForTest(MsgSnapshot m) { restoreState(m); }

    private void restoreState(MsgSnapshot m) {
        boolean haveProducersHandler = false;
        for (var st : this.states) if (st.section() == MsgSnapshot.PRODUCERS) { haveProducersHandler = true; break; }
        var producers = m.section(MsgSnapshot.PRODUCERS);
        if (producers != null && producers.length > 0 && !haveProducersHandler) {
            throw new IllegalStateException(this.groupId.value()
                    + ": snapshot carries a PRODUCERS section but no handler is registered");
        }
        // Dedup + snapshots: section 2 is mandatory. Lining up expected without restoreProducers does not rebuild
        // the table; after truncation a missing section would silently lose dedup (#278 Security via Messaging Core).
        if (haveProducersHandler && producers == null) {
            throw new IllegalStateException(this.groupId.value()
                    + ": snapshot is missing the required PRODUCERS section (dedup cannot be rebuilt after truncation)");
        }
        this.applyLock.lock();
        try {
            for (var st : this.states) {
                var d = m.section(st.section());
                st.restoreState(d == null ? new byte[0] : d);
            }
        } finally {
            this.applyLock.unlock();
        }
    }

    /**
     * Registers a state section (#278: the producer table, section {@link MsgSnapshot#PRODUCERS}). Call from the
     * constructor before the group opens, so the restored snapshot reaches it before the first entry applies.
     */
    void addState(ReplicaHooks.ReplicatedState state) {
        for (var st : this.states) {
            if (st.section() == state.section()) throw new IllegalArgumentException("section " + state.section() + " taken");
        }
        this.states.add(state);
        this.stateful = true;
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
        return propose(records, mode, 0, 0, 0);
    }

    @Override
    public CompletableFuture<ProducerAppend> appendDeduplicated(List<byte[]> records, Durability mode, int producerOrigin,
            long producerId, long producerSeq) {
        if (producerId != 0 && (producerSeq < 1 || producerSeq > ProducerTable.MAX_SEQ)) {
            throw new IllegalArgumentException("producer seq must be 1 to " + ProducerTable.MAX_SEQ);
        }
        if (producerId != 0 && this.producers == null && !this.group.votersHaveFeatures(FEATURE_PRODUCER_ENTRIES)) {
            return CompletableFuture.failedFuture(ProducerTable.dedupUnavailable(this.groupId.value()
                    + ": not every voter has confirmed it applies producer entries; nothing appended"));
        }
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
        if (this.installPending) return CompletableFuture.failedFuture(unavailable("installing a snapshot"));
        if (!leaderReady(term)) return CompletableFuture.failedFuture(unavailable("not the ready leader"));
        long seq = this.seqs.incrementAndGet();
        var acked = new CompletableFuture<Long>();
        this.pending.put(seq, acked);
        var cd = m == Durability.GROUP || m == Durability.EACH ? sjws_nettask.cluster.Durability.STRICT : null;
        var entry = producerId == 0 ? encode(records, m, term, this.self, seq)
                : encode(records, m, term, this.self, seq, producerOrigin, producerId, producerSeq);
        this.node.append(this.groupId, List.of(entry), cd, term).whenComplete((ci, err) -> {
            if (err != null) {
                var p = this.pending.remove(seq);
                if (p != null) p.completeExceptionally(map(err, true));
            }
        });
        return acked;
    }

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

    ProducerTable producers() { return this.producers; }

    void restoreProducers(ProducerTable table) { this.producers = table; }

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
        if (this.tick != null) this.tick.cancel(false);
        this.node.openGroup(this.groupId, RaftReplicator.options(java.time.Duration.ofSeconds(1), java.time.Duration.ofMillis(100)), null);
        // Proposed and maybe in the log: the entry may still commit and be applied (on restart, or by the other
        // replicas), so a resend could duplicate it; not "not leader" (#254).
        var closed = outcomeUnknown(this.groupId.value() + ": closed before the append was acknowledged", leaderName(), null);
        this.pending.values().forEach(p -> p.completeExceptionally(closed));
        this.pending.clear();
        CompletableFuture<?> inFlight;
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
            if (!this.installPending && this.local.snapshot().nextOffset() == end) {
                writeStamp(CLEAN, CLEAN, end, 0);
                this.stamps.force(false);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            // no marker: the next open recovers as after a crash
        }
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
